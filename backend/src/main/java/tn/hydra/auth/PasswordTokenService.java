package tn.hydra.auth;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.mail.MailService;
import tn.hydra.security.PasswordPolicy;
import tn.hydra.security.SessionRevoker;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Password reset + invitation tokens (U2).
 *
 * <p>The raw token is 32 bytes from {@link SecureRandom}, Base64URL without padding.
 * It appears ONLY in the email link — the DB stores its SHA-256 hex hash.
 * RESET tokens are valid 30 minutes, INVITE tokens 72 hours. Creating a token
 * marks the user's previous unused tokens of the same purpose as used.
 *
 * <p>{@link #consume} validates the token, applies the {@link PasswordPolicy},
 * sets the new password (must_change_password=false), revokes all sessions,
 * and audits PASSWORD_RESET_BY_TOKEN or ACCOUNT_ACTIVATED.
 */
@Service
public class PasswordTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Duration RESET_TTL = Duration.ofMinutes(30);
    private static final Duration INVITE_TTL = Duration.ofHours(72);

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final PasswordPolicy passwordPolicy;
    private final SessionRevoker sessions;
    private final EventLogService audit;
    private final MailService mailService;

    @Value("${hydra.app-url:http://localhost:4200}")
    private String appUrl;

    public PasswordTokenService(JdbcTemplate jdbc, AppUserRepository users,
                                PasswordEncoder encoder, PasswordPolicy passwordPolicy,
                                SessionRevoker sessions, EventLogService audit,
                                MailService mailService) {
        this.jdbc = jdbc; this.users = users;
        this.encoder = encoder; this.passwordPolicy = passwordPolicy;
        this.sessions = sessions; this.audit = audit;
        this.mailService = mailService;
    }

    // ── create ────────────────────────────────────────────────────────

    /**
     * Create a token for the given user+purpose. Returns the RAW token (Base64URL,
     * 32 bytes) — the caller puts it in the email link and never logs it. The DB
     * stores only the SHA-256 hash. Previous unused tokens of the same purpose
     * are marked used.
     */
    @Transactional
    public String create(AppUser user, String purpose, String createdIp) {
        // mark previous unused tokens of the same purpose as used
        jdbc.update("update password_token set used_at = now() where user_id = ? and purpose = ? and used_at is null",
                user.getId(), purpose);

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String rawToken = URL_ENCODER.encodeToString(bytes);
        String hash = sha256Hex(rawToken);

        Duration ttl = "INVITE".equals(purpose) ? INVITE_TTL : RESET_TTL;
        java.sql.Timestamp expiresAtTs = java.sql.Timestamp.from(Instant.now().plus(ttl));

        jdbc.update("""
                insert into password_token (org_id, user_id, purpose, token_hash, expires_at, created_ip)
                values (?, ?, ?, ?, ?, ?)
                """, user.getOrgId(), user.getId(), purpose, hash, expiresAtTs, createdIp);

        return rawToken;
    }

    /** Build the reset link: appUrl + "/reinitialiser?token=" + raw token. */
    public String resetLink(String rawToken) {
        return appUrl + "/reinitialiser?token=" + rawToken;
    }

    // ── check (no info leak) ───────────────────────────────────────────

    /** {valid, purpose, firstName} for the page title; invalid → {valid:false}. */
    public Map<String, Object> check(String rawToken) {
        Map<String, Object> row = findByHash(rawToken);
        if (row == null || !isValid(row)) {
            return Map.of("valid", false);
        }
        UUID userId = (UUID) row.get("user_id");
        AppUser u = users.findById(userId).orElse(null);
        String firstName = u != null && u.getFullName() != null
                ? u.getFullName().split("\\s+")[0] : "";
        return Map.of(
                "valid", true,
                "purpose", (String) row.get("purpose"),
                "firstName", firstName);
    }

    // ── consume ────────────────────────────────────────────────────────

    /**
     * Consume a token: atomically claim it FIRST, then apply {@link PasswordPolicy},
     * set the new password (must_change_password=false), revoke ALL sessions, audit.
     * One transaction. A weak password rolls the claim back (so the link stays usable).
     * Never logs the raw token.
     */
    @Transactional
    public void consume(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new BusinessRuleException("TOKEN_INVALID",
                    "Ce lien n'est plus valide. Demandez-en un nouveau.");
        }
        String hash = sha256Hex(rawToken);

        // Step 1: ATOMIC CLAIM — only one thread can win; the others get 0 rows.
        List<Map<String, Object>> claimed = jdbc.queryForList("""
                update password_token set used_at = now()
                where token_hash = ? and used_at is null and expires_at > now()
                returning id, user_id, purpose
                """, hash);

        if (claimed.isEmpty()) {
            throw new BusinessRuleException("TOKEN_INVALID",
                    "Ce lien n'est plus valide. Demandez-en un nouveau.");
        }

        Map<String, Object> row = claimed.get(0);
        UUID userId = (UUID) row.get("user_id");
        String purpose = (String) row.get("purpose");

        // Step 2: Load the user (must be active)
        AppUser user = users.findById(userId)
                .orElseThrow(() -> new BusinessRuleException("TOKEN_INVALID",
                        "Ce lien n'est plus valide. Demandez-en un nouveau."));
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new BusinessRuleException("TOKEN_INVALID",
                    "Ce lien n'est plus valide. Demandez-en un nouveau.");
        }

        // Step 3: PasswordPolicy — if this throws, the transaction rolls back
        // (the atomic claim is undone, so the token stays usable).
        List<String> errors = passwordPolicy.validate(user, newPassword);
        if (!errors.isEmpty()) {
            throw new BusinessRuleException("WEAK_PASSWORD", errors.get(0));
        }

        // Step 4: set password, must_change_password=false, password_changed_at=now()
        jdbc.update("""
                update app_user set password_hash = ?, must_change_password = false,
                                  password_changed_at = now()
                where id = ?
                """, encoder.encode(newPassword), userId);

        // Step 5: revoke ALL sessions of the user
        sessions.revokeAllFor(user.getEmail());

        // Step 6: audit
        String action = "INVITE".equals(purpose) ? "ACCOUNT_ACTIVATED" : "PASSWORD_RESET_BY_TOKEN";
        audit.log(action, "app_user", userId,
                "{\"email\":\"" + jsonEscape(user.getEmail()) + "\"}");

        // Step 7: Send the « changed » email after commit (never breaks the action)
        if (mailService.isEnabled()) {
            sendChangedEmail(user);
        }
    }

    /** Load the « changed » template, fill it, and send after commit. */
    private void sendChangedEmail(AppUser user) {
        String html = PasswordResetController.loadTemplate("templates/mail/changed.html")
                .replace("{{firstName}}", firstNameOf(user))
                .replace("{{dateTime}}", nowTunisFormatted());
        mailService.sendAfterCommit(user.getEmail(), "Votre mot de passe HYDRA a été modifié", html);
    }

    private static String firstNameOf(AppUser u) {
        if (u == null || u.getFullName() == null || u.getFullName().isBlank()) return "";
        return u.getFullName().split("\\s+")[0];
    }

    private static String nowTunisFormatted() {
        return ZonedDateTime.now(ZoneId.of("Africa/Tunis"))
                .format(DateTimeFormatter.ofPattern("dd/MM/yyyy à HH:mm", Locale.FRENCH));
    }

    // ── helpers ────────────────────────────────────────────────────────

    private Map<String, Object> findByHash(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return null;
        String hash = sha256Hex(rawToken);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, org_id, user_id, purpose, token_hash, expires_at, used_at from password_token where token_hash = ?",
                hash);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private boolean isValid(Map<String, Object> row) {
        if (row.get("used_at") != null) return false;
        Object exp = row.get("expires_at");
        if (exp == null) return false;
        Instant expiresAt = toInstant(exp);
        return expiresAt != null && expiresAt.isAfter(Instant.now());
    }

    private static Instant toInstant(Object v) {
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (v instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        return null;
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
