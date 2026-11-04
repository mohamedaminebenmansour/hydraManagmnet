package tn.hydra.user;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.auth.PasswordTokenService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.mail.MailService;
import tn.hydra.security.PasswordPolicy;
import tn.hydra.security.SessionRevoker;
import tn.hydra.security.TemporaryPasswordGenerator;
import tn.hydra.security.ToxicPairService;

import java.time.Instant;
import java.util.*;

@Service
public class UserService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final EventLogService audit;
    private final SessionRevoker sessions;
    private final ToxicPairService toxicPairs;

    /** Field-injected so the 6-arg constructor (used by the mock UserServiceTest) stays unchanged. */
    @Autowired(required = false)
    private PasswordPolicy passwordPolicy;
    @Autowired(required = false)
    private TemporaryPasswordGenerator tempPasswordGenerator;
    @Autowired(required = false)
    private MailService mailService;
    @Autowired(required = false)
    private PasswordTokenService passwordTokens;

    public UserService(JdbcTemplate jdbc, AppUserRepository users, PasswordEncoder encoder,
                       EventLogService audit, SessionRevoker sessions, ToxicPairService toxicPairs) {
        this.jdbc = jdbc; this.users = users; this.encoder = encoder;
        this.audit = audit; this.sessions = sessions; this.toxicPairs = toxicPairs;
    }

    // ══════════════════ RESPONSE SHAPE ══════════════════

    public record UserResponse(UUID id, String email, String fullName, boolean isOwner,
                               boolean isActive, boolean mustChangePassword,
                               String phone, String jobTitle,
                               Instant lastLoginAt, Instant passwordChangedAt,
                               String status, int projectCount, List<String> projectNames,
                               List<String> roleCodes, List<String> roleLabels,
                               String temporaryPassword, Boolean invitationSent) {

        /** Old 8-arg shape kept for callers that don't need the new fields (mock tests). */
        public UserResponse(UUID id, String email, String fullName, boolean isOwner,
                            boolean isActive, List<String> roleCodes, List<String> roleLabels) {
            this(id, email, fullName, isOwner, isActive, false, null, null, null, null, null, 0, List.of(),
                    roleCodes, roleLabels, null, null);
        }
    }

    /** Lightweight picker for assignee dropdowns (ticket forms etc.). */
    public record UserOption(UUID id, String fullName, String email) {}

    // ══════════════════ 1. CHANGE MY PASSWORD ══════════════════

    @Transactional
    public void changeMyPassword(Authentication auth, String oldPassword, String newPassword) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        if (!encoder.matches(oldPassword, me.getPasswordHash()))
            throw new BusinessRuleException("WRONG_PASSWORD", "Ancien mot de passe incorrect");
        List<String> errors = validatePassword(me, newPassword);
        if (!errors.isEmpty())
            throw new BusinessRuleException("WEAK_PASSWORD", errors.get(0));

        jdbc.update("""
                update app_user set password_hash = ?, must_change_password = false,
                                  password_changed_at = now() where id = ?
                """, encoder.encode(newPassword), me.getId());
        // update the session flag so the filter stops blocking
        setSessionFlag(me.getEmail(), false);
        // all OTHER devices logged out; the current one survives so the user is not
        // logged out right after changing his own password
        String currentSession = currentSessionId();
        if (currentSession != null) sessions.revokeAllExcept(me.getEmail(), currentSession);
        else sessions.revokeAllFor(me.getEmail());     // non-web fallback: nobody survives
        audit.log("PASSWORD_CHANGED", "app_user", me.getId(), null);

        // U2: send the « changed » email after commit (never breaks the action)
        if (mailService != null && mailService.isEnabled()) {
            sendChangedEmail(me);
        }
    }

    // ══════════════════ 2. LIST USERS ══════════════════

    public List<UserResponse> listUsers(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        // ONE SQL query: users + their roles (multiple rows per user) in one go.
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select u.id, u.email, u.full_name, u.is_owner, u.is_active, u.must_change_password,
                       u.phone, u.job_title, u.last_login_at, u.password_changed_at,
                       r.code, r.label
                from app_user u
                left join user_role ur on ur.user_id = u.id
                left join role r on r.id = ur.role_id
                where u.org_id = ?
                order by u.full_name, r.code
                """, me.getOrgId());

        // ONE extra query: project membership counts + names (batched, not per-user)
        Map<UUID, List<Map<String, Object>>> projectsByUser = new HashMap<>();
        List<Map<String, Object>> pmRows = jdbc.queryForList("""
                select pm.user_id, p.name, pm.project_role
                from project_member pm
                join project p on p.id = pm.project_id
                where p.org_id = ?
                order by pm.user_id, p.name
                """, me.getOrgId());
        for (Map<String, Object> r : pmRows) {
            projectsByUser.computeIfAbsent((UUID) r.get("user_id"), k -> new ArrayList<>()).add(r);
        }

        Map<UUID, UserResponseBuilder> byId = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            UserResponseBuilder b = byId.get(id);
            String roleCode = (String) row.get("code");
            if (b == null) {
                b = new UserResponseBuilder();
                b.id = id;
                b.email = (String) row.get("email");
                b.fullName = (String) row.get("full_name");
                b.isOwner = Boolean.TRUE.equals(row.get("is_owner"));
                b.isActive = Boolean.TRUE.equals(row.get("is_active"));
                b.mustChangePassword = Boolean.TRUE.equals(row.get("must_change_password"));
                b.phone = (String) row.get("phone");
                b.jobTitle = (String) row.get("job_title");
                b.lastLoginAt = toInstant(row.get("last_login_at"));
                b.passwordChangedAt = toInstant(row.get("password_changed_at"));
                b.roleCodes = new ArrayList<>();
                b.roleLabels = new ArrayList<>();
                List<Map<String, Object>> projects = projectsByUser.getOrDefault(id, List.of());
                b.projectCount = projects.size();
                b.projectNames = projects.stream().limit(10).map(p -> (String) p.get("name")).toList();
                byId.put(id, b);
            }
            if (roleCode != null) {
                b.roleCodes.add(roleCode);
                b.roleLabels.add((String) row.get("label"));
            }
        }

        List<UserResponse> out = new ArrayList<>();
        for (UserResponseBuilder b : byId.values()) {
            out.add(new UserResponse(b.id, b.email, b.fullName, b.isOwner, b.isActive, b.mustChangePassword,
                    b.phone, b.jobTitle, b.lastLoginAt, b.passwordChangedAt,
                    userStatus(b.isOwner, b.isActive, b.mustChangePassword, b.passwordChangedAt, b.lastLoginAt, b.projectCount > 0),
                    b.projectCount, b.projectNames, b.roleCodes, b.roleLabels, null, null));
        }
        return out;
    }

    // ══════════════════ 2b. ACTIVE USER OPTIONS ══════════════════

    /** Active users of the caller's org, for assignee pickers (needs no USER_* permission). */
    public List<UserOption> userOptions(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        return jdbc.queryForList("""
                select id, full_name, email
                from app_user
                where org_id = ? and is_active = true
                order by full_name
                """, me.getOrgId()).stream()
                .map(r -> new UserOption((UUID) r.get("id"),
                        (String) r.get("full_name"), (String) r.get("email")))
                .toList();
    }

    // ══════════════════ 3. CREATE USER ══════════════════

    /** Old 6-arg signature (no sendInvitation) — kept for mock tests + old callers. */
    @Transactional
    public UserResponse createUser(Authentication auth, String fullName, String email,
                                   String password, List<String> roleCodes, String overrideReason) {
        return createUser(auth, fullName, email, password, roleCodes, overrideReason, false);
    }

    /**
     * Full create with {@code sendInvitation} (U2):
     * <ul>
     *   <li>sendInvitation=true AND mail enabled → create with a random unusable password
     *       hash, must_change_password=true, send INVITE email. Response:
     *       temporaryPassword=null, invitationSent=true.</li>
     *   <li>sendInvitation=true AND mail disabled → ignore the option, return a temp
     *       password (U1 behaviour).</li>
     *   <li>sendInvitation=false → U1 behaviour (temp password if password blank).</li>
     * </ul>
     */
    @Transactional
    public UserResponse createUser(Authentication auth, String fullName, String email,
                                   String password, List<String> roleCodes, String overrideReason,
                                   boolean sendInvitation) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();

        if (isBlank(fullName) || isBlank(email))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Nom et email sont obligatoires");
        if (!email.contains("@"))
            throw new BusinessRuleException("EMAIL_INVALID", "Format d'email invalide");
        if (users.findByEmail(email).isPresent())
            throw new BusinessRuleException("EMAIL_EXISTS", "Un utilisateur avec cet email existe déjà");
        if (roleCodes == null || roleCodes.isEmpty())
            throw new BusinessRuleException("NO_ROLE", "Sélectionnez au moins un rôle");

        Set<String> codes = new LinkedHashSet<>(roleCodes);
        Map<String, UUID> roleIds = roleIds(codes, me.getOrgId());
        if (roleIds.size() != codes.size())
            throw new BusinessRuleException("UNKNOWN_ROLE", "Un des rôles sélectionnés n'existe pas");

        List<ToxicPairService.RolePairConflict> conflicts = toxicPairs.conflictingPairs(codes);
        if (!conflicts.isEmpty() && isBlank(overrideReason))
            throw new BusinessRuleException("TOXIC_PAIR", conflicts.get(0).reason());

        // U2: invitation flow (mail enabled + sendInvitation requested)
        boolean useInvitation = sendInvitation && mailService != null && mailService.isEnabled()
                && passwordTokens != null;

        String effectivePassword;
        String temporaryPassword = null;
        boolean invitationSent = false;

        if (useInvitation) {
            // random unusable password hash — the user activates via the invite link
            effectivePassword = java.util.UUID.randomUUID().toString();
        } else if (isBlank(password)) {
            if (tempPasswordGenerator == null)
                throw new BusinessRuleException("WEAK_PASSWORD", "Aucun mot de passe fourni");
            temporaryPassword = tempPasswordGenerator.generate();
            effectivePassword = temporaryPassword;
        } else {
            List<String> errors = validatePassword(null, password);
            if (!errors.isEmpty())
                throw new BusinessRuleException("WEAK_PASSWORD", errors.get(0));
            effectivePassword = password;
        }

        UUID id = jdbc.queryForObject("""
                insert into app_user (org_id, email, password_hash, full_name,
                                      is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, false, true, true) returning id
                """, UUID.class, me.getOrgId(), email.toLowerCase(Locale.ROOT),
                encoder.encode(effectivePassword), fullName.trim());

        for (String code : codes) {
            jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                    id, roleIds.get(code), me.getId());
        }

        if (!conflicts.isEmpty()) {
            for (ToxicPairService.RolePairConflict c : conflicts) {
                jdbc.update("""
                        insert into toxic_pair_override (org_id, user_id, role_a, role_b, reason, granted_by)
                        values (?, ?, ?, ?, ?, ?)
                        """, me.getOrgId(), id, c.roleA(), c.roleB(), overrideReason, me.getId());
            }
            audit.log("TOXIC_PAIR_OVERRIDDEN", "app_user", id,
                    "{\"reason\":\"" + jsonEscape(overrideReason) + "\"}");
        }

        audit.log("USER_CREATED", "app_user", id,
                "{\"email\":\"" + jsonEscape(email) + "\",\"roles\":\"" + jsonEscape(String.join(",", codes)) + "\"}");

        // U2: send the invitation email after commit
        if (useInvitation) {
            AppUser newUser = users.findById(id).orElseThrow();
            String rawToken = passwordTokens.create(newUser, "INVITE", null);
            String link = passwordTokens.resetLink(rawToken);
            String companyName = jdbc.queryForObject("select name from organization where id = ?",
                    String.class, me.getOrgId());
            String html = tn.hydra.auth.PasswordResetController.loadTemplate("templates/mail/invite.html")
                    .replace("{{firstName}}", firstNameOf(newUser))
                    .replace("{{companyName}}", companyName == null ? "" : companyName)
                    .replace("{{invitedByName}}", me.getFullName() == null ? "" : me.getFullName())
                    .replace("{{link}}", link);
            mailService.sendAfterCommit(email, "Votre accès à HYDRA — " + (companyName == null ? "" : companyName), html);
            invitationSent = true;
        }

        return new UserResponse(id, email, fullName.trim(), false, true, true,
                null, null, null, null, "PENDING", 0, List.of(),
                new ArrayList<>(codes), labelsOf(roleIds, codes), temporaryPassword,
                invitationSent ? true : null);
    }

    // ══════════════════ 4. DEACTIVATE / REACTIVATE ══════════════════

    @Transactional
    public void deactivateUser(Authentication auth, UUID id) {
        AppUser target = editable(auth, id);
        if (!Boolean.TRUE.equals(target.getIsActive()))
            throw new BusinessRuleException("ALREADY_INACTIVE", "Cet utilisateur est déjà désactivé");

        jdbc.update("update app_user set is_active = false where id = ?", target.getId());
        sessions.revokeAllFor(target.getEmail());       // kill switch: logged out everywhere, now
        audit.log("USER_DEACTIVATED", "app_user", target.getId(),
                "{\"email\":\"" + jsonEscape(target.getEmail()) + "\"}");
    }

    @Transactional
    public void reactivateUser(Authentication auth, UUID id) {
        AppUser target = editable(auth, id);
        if (Boolean.TRUE.equals(target.getIsActive()))
            throw new BusinessRuleException("ALREADY_ACTIVE", "Cet utilisateur est déjà actif");

        jdbc.update("update app_user set is_active = true where id = ?", target.getId());
        audit.log("USER_REACTIVATED", "app_user", target.getId(),
                "{\"email\":\"" + jsonEscape(target.getEmail()) + "\"}");
    }

    // ══════════════════ 5. ADMIN RESET PASSWORD ══════════════════

    @Transactional
    public String resetPassword(Authentication auth, UUID id, String newPassword) {
        AppUser target = editable(auth, id);
        if (target.getId().equals(users.findByEmail(auth.getName()).orElseThrow().getId()))
            throw new BusinessRuleException("SELF_ACTION", "Utilisez le changement de mot de passe pour votre propre compte");

        String effectivePassword;
        String temporaryPassword = null;
        if (isBlank(newPassword)) {
            if (tempPasswordGenerator == null)
                throw new BusinessRuleException("WEAK_PASSWORD", "Aucun mot de passe fourni");
            temporaryPassword = tempPasswordGenerator.generate();
            effectivePassword = temporaryPassword;
        } else {
            List<String> errors = validatePassword(target, newPassword);
            if (!errors.isEmpty())
                throw new BusinessRuleException("WEAK_PASSWORD", errors.get(0));
            effectivePassword = newPassword;
        }

        jdbc.update("update app_user set password_hash = ?, must_change_password = true where id = ?",
                encoder.encode(effectivePassword), target.getId());
        sessions.revokeAllFor(target.getEmail());       // he must log in again with the new password
        audit.log("PASSWORD_RESET", "app_user", target.getId(),
                "{\"email\":\"" + jsonEscape(target.getEmail()) + "\"}");

        // U2: send the « changed » email after commit
        if (mailService != null && mailService.isEnabled()) {
            sendChangedEmail(target);
        }
        return temporaryPassword;
    }

    // ══════════════════ 6. UPDATE USER ROLES ══════════════════

    @Transactional
    public UserResponse updateUserRoles(Authentication auth, UUID id, List<String> roleCodes,
                                        String overrideReason) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        AppUser target = editable(auth, id);

        if (roleCodes == null || roleCodes.isEmpty())
            throw new BusinessRuleException("NO_ROLE", "Sélectionnez au moins un rôle");
        Set<String> codes = new LinkedHashSet<>(roleCodes);
        Map<String, UUID> roleIds = roleIds(codes, me.getOrgId());
        if (roleIds.size() != codes.size())
            throw new BusinessRuleException("UNKNOWN_ROLE", "Un des rôles sélectionnés n'existe pas");

        // toxic pair without an override reason → 409 {code: TOXIC_PAIR} via GlobalExceptionHandler
        List<ToxicPairService.RolePairConflict> conflicts = toxicPairs.conflictingPairs(codes);
        if (!conflicts.isEmpty() && isBlank(overrideReason))
            throw new BusinessRuleException("TOXIC_PAIR", conflicts.get(0).reason());

        jdbc.update("delete from user_role where user_id = ?", target.getId());
        for (String code : codes) {
            jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                    target.getId(), roleIds.get(code), me.getId());
        }

        if (!conflicts.isEmpty()) {
            jdbc.update("delete from toxic_pair_override where user_id = ?", target.getId());
            for (ToxicPairService.RolePairConflict c : conflicts) {
                jdbc.update("""
                        insert into toxic_pair_override (org_id, user_id, role_a, role_b, reason, granted_by)
                        values (?, ?, ?, ?, ?, ?)
                        """, me.getOrgId(), target.getId(), c.roleA(), c.roleB(), overrideReason, me.getId());
            }
            audit.log("TOXIC_PAIR_OVERRIDDEN", "app_user", target.getId(),
                    "{\"reason\":\"" + jsonEscape(overrideReason) + "\"}");
        }

        sessions.revokeAllFor(target.getEmail());       // his new rights apply on the next login only
        audit.log("USER_ROLES_UPDATED", "app_user", target.getId(),
                "{\"roles\":\"" + jsonEscape(String.join(",", codes)) + "\"}");
        return toUserResponse(target, new ArrayList<>(codes), labelsOf(roleIds, codes));
    }

    // ══════════════════ 7. UPDATE USER (NAME / PASSWORD) ══════════════════

    @Transactional
    public UserResponse updateUser(Authentication auth, UUID id, String fullName, String password) {
        AppUser target = editable(auth, id);
        if (isBlank(fullName))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le nom est obligatoire");

        if (!isBlank(password)) {
            List<String> errors = validatePassword(target, password);
            if (!errors.isEmpty())
                throw new BusinessRuleException("WEAK_PASSWORD", errors.get(0));
        }

        jdbc.update("update app_user set full_name = ? where id = ?", fullName.trim(), target.getId());
        if (!isBlank(password)) {
            jdbc.update("update app_user set password_hash = ?, must_change_password = true where id = ?",
                    encoder.encode(password), target.getId());
            sessions.revokeAllFor(target.getEmail());   // he must log in again with the new password
        }
        audit.log("USER_UPDATED", "app_user", target.getId(),
                "{\"email\":\"" + jsonEscape(target.getEmail()) + "\"}");

        return toUserResponse(target, roleCodesOf(target.getId()), roleLabelsOf(target.getId()));
    }

    // ══════════════════ 8. MY PROFILE ══════════════════

    /**
     * Update my own profile (fullName, phone, jobTitle). Email cannot be changed.
     * Audits before → after values.
     */
    @Transactional
    public Map<String, Object> updateMyProfile(Authentication auth, String fullName, String phone, String jobTitle) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        String before = "{\"fullName\":\"" + jsonEscape(me.getFullName()) + "\",\"phone\":\"" + jsonEscape(me.getPhone()) + "\",\"jobTitle\":\"" + jsonEscape(me.getJobTitle()) + "\"}";

        if (fullName != null) {
            String t = fullName.trim();
            if (t.length() < 2 || t.length() > 120)
                throw new BusinessRuleException("INVALID_NAME", "Le nom doit faire entre 2 et 120 caractères");
            me.setFullName(t);
        }
        if (phone != null) {
            String t = phone.trim();
            if (!t.isEmpty() && !t.matches("[+0-9 ]{8,20}"))
                throw new BusinessRuleException("INVALID_PHONE", "Téléphone invalide (8 à 20 chiffres, espaces ou +)");
            me.setPhone(t.isEmpty() ? null : t);
        }
        if (jobTitle != null) {
            String t = jobTitle.trim();
            if (t.length() > 80)
                throw new BusinessRuleException("INVALID_JOB_TITLE", "La fonction ne peut pas dépasser 80 caractères");
            me.setJobTitle(t.isEmpty() ? null : t);
        }

        jdbc.update("update app_user set full_name = ?, phone = ?, job_title = ? where id = ?",
                me.getFullName(), me.getPhone(), me.getJobTitle(), me.getId());

        String after = "{\"fullName\":\"" + jsonEscape(me.getFullName()) + "\",\"phone\":\"" + jsonEscape(me.getPhone()) + "\",\"jobTitle\":\"" + jsonEscape(me.getJobTitle()) + "\"}";
        audit.log("PROFILE_UPDATED", "app_user", me.getId(),
                "{\"before\":" + before + ",\"after\":" + after + "}");

        return myProfileMap(me);
    }

    /** GET /api/me/profile response as a map. */
    public Map<String, Object> myProfile(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        return myProfileMap(me);
    }

    /** My own user id (for the absence self-endpoint). */
    public java.util.UUID findMyId(Authentication auth) {
        return users.findByEmail(auth.getName()).orElseThrow().getId();
    }

    /** My own email (for the revoke-others endpoint). */
    public String findMyEmail(Authentication auth) {
        return users.findByEmail(auth.getName()).orElseThrow().getEmail();
    }

    private Map<String, Object> myProfileMap(AppUser me) {
        // roles + projects in two queries
        List<Map<String, Object>> roles = jdbc.queryForList("""
                select r.code, r.label from user_role ur
                join role r on r.id = ur.role_id
                where ur.user_id = ? order by r.code
                """, me.getId());
        List<Map<String, Object>> projects = jdbc.queryForList("""
                select p.id, p.name, pm.project_role
                from project_member pm
                join project p on p.id = pm.project_id
                where pm.user_id = ? and p.org_id = ?
                order by p.name
                """, me.getId(), me.getOrgId());

        return Map.of(
                "fullName", me.getFullName() == null ? "" : me.getFullName(),
                "email", me.getEmail(),
                "phone", me.getPhone() == null ? "" : me.getPhone(),
                "jobTitle", me.getJobTitle() == null ? "" : me.getJobTitle(),
                "lastLoginAt", me.getLastLoginAt() == null ? "" : me.getLastLoginAt().toString(),
                "passwordChangedAt", me.getPasswordChangedAt() == null ? "" : me.getPasswordChangedAt().toString(),
                "roles", roles,
                "projects", projects,
                "absentUntil", me.getAbsentUntil() == null ? "" : me.getAbsentUntil().toString()
        );
    }

    // ══════════════════ 9. ADMIN SEND RESET LINK / RESEND INVITATION (U2) ══════════════════

    /**
     * Admin-triggered RESET email. If mail is disabled → 400. Else creates a RESET
     * token and sends the email after commit.
     */
    @Transactional
    public void sendResetLink(Authentication auth, UUID id) {
        if (mailService == null || !mailService.isEnabled()) {
            throw new BusinessRuleException("MAIL_DISABLED", "L'envoi d'emails n'est pas configuré");
        }
        AppUser target = editable(auth, id);
        String rawToken = passwordTokens.create(target, "RESET", null);
        String link = passwordTokens.resetLink(rawToken);
        String html = tn.hydra.auth.PasswordResetController.loadTemplate("templates/mail/reset.html")
                .replace("{{firstName}}", firstNameOf(target))
                .replace("{{link}}", link);
        mailService.sendAfterCommit(target.getEmail(), "Réinitialiser votre mot de passe HYDRA", html);
        audit.log("RESET_LINK_SENT", "app_user", target.getId(),
                "{\"email\":\"" + jsonEscape(target.getEmail()) + "\"}");
    }

    /**
     * Resend the INVITE email for a user still in PENDING_FIRST_LOGIN
     * (must_change_password=true AND first_login_at is null).
     */
    @Transactional
    public void resendInvitation(Authentication auth, UUID id) {
        if (mailService == null || !mailService.isEnabled()) {
            throw new BusinessRuleException("MAIL_DISABLED", "L'envoi d'emails n'est pas configuré");
        }
        AppUser target = editable(auth, id);
        if (!Boolean.TRUE.equals(target.getMustChangePassword()) || target.getFirstLoginAt() != null) {
            throw new BusinessRuleException("NOT_PENDING", "Cet utilisateur a déjà activé son compte");
        }
        String rawToken = passwordTokens.create(target, "INVITE", null);
        String link = passwordTokens.resetLink(rawToken);
        String companyName = jdbc.queryForObject("select name from organization where id = ?",
                String.class, target.getOrgId());
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        String html = tn.hydra.auth.PasswordResetController.loadTemplate("templates/mail/invite.html")
                .replace("{{firstName}}", firstNameOf(target))
                .replace("{{companyName}}", companyName == null ? "" : companyName)
                .replace("{{invitedByName}}", me.getFullName() == null ? "" : me.getFullName())
                .replace("{{link}}", link);
        mailService.sendAfterCommit(target.getEmail(), "Votre accès à HYDRA — " + (companyName == null ? "" : companyName), html);
        audit.log("INVITATION_RESENT", "app_user", target.getId(),
                "{\"email\":\"" + jsonEscape(target.getEmail()) + "\"}");
    }

    // ── email helpers ──────────────────────────────────────────────────

    /** Load the « changed » template, fill it, and send after commit. */
    private void sendChangedEmail(AppUser user) {
        String html = tn.hydra.auth.PasswordResetController.loadTemplate("templates/mail/changed.html")
                .replace("{{firstName}}", firstNameOf(user))
                .replace("{{dateTime}}", nowTunisFormatted());
        mailService.sendAfterCommit(user.getEmail(), "Votre mot de passe HYDRA a été modifié", html);
    }

    private static String firstNameOf(AppUser u) {
        if (u == null || u.getFullName() == null || u.getFullName().isBlank()) return "";
        return u.getFullName().split("\\s+")[0];
    }

    private static String nowTunisFormatted() {
        return java.time.ZonedDateTime.now(java.time.ZoneId.of("Africa/Tunis"))
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy à HH:mm", java.util.Locale.FRENCH));
    }

    // ══════════════════ HELPERS ══════════════════

    /**
     * The 6 user statuses, computed in priority order:
     * <ol>
     *   <li>INACTIVE — is_active = false.</li>
     *   <li>PENDING — must_change_password = true (never logged in / admin reset).</li>
     *   <li>ABSENT — absent_until >= today.</li>
     *   <li>ACTIVE — has a project membership.</li>
     *   <li>NO_PROJECT — no project membership.</li>
     *   <li>OWNER — is_owner = true (always shown last; the owner is special).</li>
     * </ol>
     */
    static String userStatus(boolean isOwner, boolean isActive, boolean mustChangePassword,
                             Instant passwordChangedAt, Instant lastLoginAt, boolean hasProject) {
        if (!isActive) return "INACTIVE";
        if (mustChangePassword) return "PENDING";
        if (isOwner) return "OWNER";
        if (!hasProject) return "NO_PROJECT";
        return "ACTIVE";
    }

    /** Validate a password with the policy; falls back to min-8 when the policy is absent (mock tests). */
    private List<String> validatePassword(AppUser user, String candidate) {
        if (passwordPolicy != null) {
            return passwordPolicy.validate(user, candidate);
        }
        // mock-test fallback: min 8 chars
        List<String> errors = new ArrayList<>();
        if (candidate == null || candidate.length() < 8)
            errors.add("Le mot de passe doit faire au moins 8 caractères");
        return errors;
    }

    /** Set the must-change-password flag in the current HTTP session (used by the filter). */
    private void setSessionFlag(String email, boolean value) {
        if (!(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs)) {
            return;
        }
        jakarta.servlet.http.HttpSession session =
                servletAttrs.getRequest().getSession(false);
        if (session != null) {
            session.setAttribute(tn.hydra.security.PasswordChangeRequiredFilter.SESSION_FLAG, value);
        }
    }

    /** Build a UserResponse from an AppUser + role lists (no temp password). */
    private UserResponse toUserResponse(AppUser target, List<String> roleCodes, List<String> roleLabels) {
        return new UserResponse(target.getId(), target.getEmail(), target.getFullName(),
                Boolean.TRUE.equals(target.getIsOwner()), Boolean.TRUE.equals(target.getIsActive()),
                Boolean.TRUE.equals(target.getMustChangePassword()),
                target.getPhone(), target.getJobTitle(),
                target.getLastLoginAt(), target.getPasswordChangedAt(),
                userStatus(Boolean.TRUE.equals(target.getIsOwner()), Boolean.TRUE.equals(target.getIsActive()),
                        Boolean.TRUE.equals(target.getMustChangePassword()), target.getPasswordChangedAt(),
                        target.getLastLoginAt(), false),
                0, List.of(), roleCodes, roleLabels, null, null);
    }

    private static Instant toInstant(Object v) {
        if (v == null) return null;
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (v instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return null;
    }

    /** Id of the session serving this request, or null outside a web request. */
    private String currentSessionId() {
        if (!(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes()
                instanceof org.springframework.web.context.request.ServletRequestAttributes servletAttrs)) {
            return null;
        }
        jakarta.servlet.http.HttpSession session =
                servletAttrs.getRequest().getSession(false);
        return session == null ? null : session.getId();
    }

    /** Target must exist, be in the caller's org and be neither the caller nor the owner. */
    private AppUser editable(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        AppUser target = users.findById(id)
                .orElseThrow(() -> new BusinessRuleException("USER_NOT_FOUND", "Utilisateur introuvable"));
        if (!me.getOrgId().equals(target.getOrgId()))
            throw new BusinessRuleException("USER_NOT_FOUND", "Utilisateur introuvable");
        if (target.getId().equals(me.getId()))
            throw new BusinessRuleException("SELF_ACTION", "Cette action n'est pas possible sur votre propre compte");
        if (Boolean.TRUE.equals(target.getIsOwner()))
            throw new BusinessRuleException("OWNER_PROTECTED", "Le compte gérant ne peut pas être modifié");
        return target;
    }

    /** Role codes assigned to a user, system and custom alike, ordered by code. */
    private List<String> roleCodesOf(UUID userId) {
        return jdbc.queryForList("""
                select r.code from user_role ur
                join role r on r.id = ur.role_id
                where ur.user_id = ?
                order by r.code
                """, String.class, userId);
    }

    /** Labels matching {@link #roleCodesOf(UUID)} row for row. */
    private List<String> roleLabelsOf(UUID userId) {
        return jdbc.queryForList("""
                select r.label from user_role ur
                join role r on r.id = ur.role_id
                where ur.user_id = ?
                order by r.code
                """, String.class, userId);
    }

    private Map<String, UUID> roleIds(Set<String> codes, UUID orgId) {
        Map<String, UUID> result = new LinkedHashMap<>();
        for (String code : codes) {
            List<UUID> ids = jdbc.queryForList(
                    "select id from role where code = ? and (org_id is null or org_id = ?)",
                    UUID.class, code, orgId);
            if (!ids.isEmpty()) result.put(code, ids.get(0));
        }
        return result;
    }

    private List<String> labelsOf(Map<String, UUID> roleIds, Set<String> codes) {
        List<String> labels = new ArrayList<>();
        for (String code : codes) {
            List<String> found = jdbc.queryForList(
                    "select label from role where id = ?", String.class, roleIds.get(code));
            labels.add(found.isEmpty() ? code : found.get(0));
        }
        return labels;
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ══════════════════ ABSENCE ══════════════════

    /**
     * Set or clear a user's absence (absentUntil = null ends it).
     * Allowed for the org owner, or the user for himself. Same org only.
     * Date must not be in the past. Audits USER_ABSENCE_SET.
     */
    @Transactional
    public void setAbsence(Authentication auth, UUID id, java.time.LocalDate absentUntil) {
        AppUser me = users.findByEmail(auth.getName())
                .orElseThrow(() -> new BusinessRuleException("UNKNOWN_USER", "Utilisateur inconnu"));
        // Owner or self only.
        if (!Boolean.TRUE.equals(me.getIsOwner()) && !me.getId().equals(id)) {
            throw new BusinessRuleException("NOT_ALLOWED",
                    "Vous ne pouvez modifier que votre propre absence");
        }
        // Same org only.
        AppUser target = users.findById(id)
                .orElseThrow(() -> new BusinessRuleException("USER_NOT_FOUND", "Utilisateur introuvable"));
        if (!Objects.equals(me.getOrgId(), target.getOrgId())) {
            throw new BusinessRuleException("USER_NOT_FOUND", "Utilisateur introuvable");
        }
        // Date must not be in the past (null = end absence).
        if (absentUntil != null && absentUntil.isBefore(java.time.LocalDate.now())) {
            throw new BusinessRuleException("ABSENCE_DATE_PAST",
                    "La date d'absence ne peut pas être dans le passé");
        }
        jdbc.update("update app_user set absent_until = ? where id = ?", absentUntil, id);
        audit.log("USER_ABSENCE_SET", "app_user", id,
                "{\"absentUntil\":" + (absentUntil != null ? "\"" + absentUntil + "\"" : "null") + "}");
    }

    /** Mutable builder for UserResponse (avoids 16-arg constructor calls in the loop). */
    private static class UserResponseBuilder {
        UUID id; String email; String fullName;
        boolean isOwner, isActive, mustChangePassword;
        String phone, jobTitle; Instant lastLoginAt, passwordChangedAt;
        int projectCount; List<String> projectNames;
        List<String> roleCodes, roleLabels;
    }
}
