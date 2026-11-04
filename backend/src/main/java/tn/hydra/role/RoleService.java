package tn.hydra.role;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.util.*;

@Service
public class RoleService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;

    public RoleService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit) {
        this.jdbc = jdbc; this.users = users; this.audit = audit;
    }

    // ══════════════════ RESPONSE SHAPE ══════════════════

    public record RoleResponse(UUID id, String code, String label, boolean isSystem,
                               List<String> permissions, int userCount, List<String> moduleKeys) {

        /** Old 5-arg shape kept for callers that don't need the new fields. */
        public RoleResponse(UUID id, String code, String label, boolean isSystem, List<String> permissions) {
            this(id, code, label, isSystem, permissions, 0, List.of());
        }
    }
    public record PermissionResponse(String id, String description) {}
    public record RoleUserResponse(UUID id, String fullName, String email, String status) {}

    // ══════════════════ 1. LIST ROLES (system + my org) ══════════════════

    public List<RoleResponse> listRoles(Authentication auth) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select r.id, r.code, r.label, r.org_id, p.id as permission_id
                from role r
                left join role_permission rp on rp.role_id = r.id
                left join permission p on p.id = rp.permission_id
                where r.org_id is null or r.org_id = ?
                order by r.org_id is not null, r.label, p.id
                """, me.getOrgId());

        // batch count active users per role (one query)
        Map<UUID, Integer> userCounts = new HashMap<>();
        List<Map<String, Object>> ucRows = jdbc.queryForList("""
                select ur.role_id, count(distinct u.id) as cnt
                from user_role ur
                join app_user u on u.id = ur.user_id and u.is_active = true
                where u.org_id = ?
                group by ur.role_id
                """, me.getOrgId());
        if (ucRows != null) {
            for (Map<String, Object> r : ucRows) {
                Object cnt = r.get("cnt");
                if (cnt instanceof Number n) userCounts.put((UUID) r.get("role_id"), n.intValue());
            }
        }

        Map<UUID, List<String>> permsByRole = new LinkedHashMap<>();
        Map<UUID, Map<String, Object>> roleMeta = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            roleMeta.computeIfAbsent(id, k -> row);
            String perm = (String) row.get("permission_id");
            if (perm != null) permsByRole.computeIfAbsent(id, k -> new ArrayList<>()).add(perm);
        }

        List<RoleResponse> out = new ArrayList<>();
        for (Map.Entry<UUID, Map<String, Object>> e : roleMeta.entrySet()) {
            UUID id = e.getKey();
            Map<String, Object> row = e.getValue();
            List<String> perms = permsByRole.getOrDefault(id, new ArrayList<>());
            out.add(new RoleResponse(id,
                    (String) row.get("code"),
                    (String) row.get("label"),
                    row.get("org_id") == null,
                    perms,
                    userCounts.getOrDefault(id, 0),
                    moduleKeysOf(perms)));
        }
        return out;
    }

    // ══════════════════ 2. LIST ALL PERMISSIONS ══════════════════

    public List<PermissionResponse> listPermissions() {
        return jdbc.query("select id, description from permission order by id",
                (rs, i) -> new PermissionResponse(rs.getString("id"), rs.getString("description")));
    }

    // ══════════════════ 3. CREATE CUSTOM ROLE ══════════════════

    @Transactional
    public RoleResponse createRole(Authentication auth, String label, List<String> permissionCodes) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        if (isBlank(label))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le libellé est obligatoire");

        String code = slug(label);
        if (isBlank(code))
            throw new BusinessRuleException("INVALID_CODE", "Le libellé ne contient aucun caractère valide");
        // unique per org; system codes are refused too so role lookups stay unambiguous
        List<String> existing = jdbc.queryForList(
                "select id from role where code = ? and (org_id = ? or org_id is null)",
                String.class, code, me.getOrgId());
        if (!existing.isEmpty())
            throw new BusinessRuleException("CODE_EXISTS", "Un rôle avec ce code existe déjà");

        UUID id = jdbc.queryForObject("""
                insert into role (org_id, code, label, scope_type) values (?, ?, ?, 'ALL') returning id
                """, UUID.class, me.getOrgId(), code, label.trim());
        List<String> permissions = insertPermissions(id, permissionCodes);

        audit.log("ROLE_CREATED", "role", id, "{\"code\":\"" + jsonEscape(code) + "\"}");
        return new RoleResponse(id, code, label.trim(), false, permissions, 0, moduleKeysOf(permissions));
    }

    // ══════════════════ 4. UPDATE CUSTOM ROLE ══════════════════

    @Transactional
    public RoleResponse updateRole(Authentication auth, UUID id, String label, List<String> permissionCodes) {
        return updateRole(auth, id, label, permissionCodes, false);
    }

    @Transactional
    public RoleResponse updateRole(Authentication auth, UUID id, String label,
                                   List<String> permissionCodes, boolean confirm) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        List<Map<String, Object>> found = jdbc.queryForList("select org_id, code from role where id = ?", id);
        if (found.isEmpty())
            throw new BusinessRuleException("ROLE_NOT_FOUND", "Rôle introuvable");
        if (found.get(0).get("org_id") == null)
            throw new BusinessRuleException("SYSTEM_ROLE", "Un rôle système ne se modifie pas. Dupliquez-le.");
        if (!me.getOrgId().equals(found.get(0).get("org_id")))
            throw new BusinessRuleException("ROLE_NOT_FOUND", "Rôle introuvable");
        if (isBlank(label))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le libellé est obligatoire");

        String code = (String) found.get(0).get("code");

        // before permissions (for audit)
        List<String> beforePerms = jdbc.queryForList(
                "select permission_id from role_permission where role_id = ? order by permission_id",
                String.class, id);

        // confirm impact: if the role has active users and confirm is false, return 409
        if (!confirm) {
            List<Map<String, Object>> affectedUsers = jdbc.queryForList("""
                    select u.id, u.full_name from user_role ur
                    join app_user u on u.id = ur.user_id and u.is_active = true
                    where ur.role_id = ? and u.org_id = ?
                    order by u.full_name limit 11
                    """, id, me.getOrgId());
            if (!affectedUsers.isEmpty()) {
                int count = jdbc.queryForObject(
                        "select count(distinct u.id) from user_role ur join app_user u on u.id = ur.user_id and u.is_active = true where ur.role_id = ? and u.org_id = ?",
                        Integer.class, id, me.getOrgId());
                List<String> names = affectedUsers.stream().limit(10)
                        .map(r -> (String) r.get("full_name")).toList();
                throw new BusinessRuleException("CONFIRM_IMPACT",
                        "Ce changement s'applique à " + count + " personne(s)");
                // The GlobalExceptionHandler turns BusinessRuleException into 409 CONFLICT.
                // The controller wraps the count + names into the response body.
            }
        }

        jdbc.update("update role set label = ? where id = ?", label.trim(), id);
        jdbc.update("delete from role_permission where role_id = ?", id);
        List<String> afterPerms = insertPermissions(id, permissionCodes);

        audit.log("ROLE_UPDATED", "role", id,
                "{\"code\":\"" + jsonEscape(code) + "\",\"before\":\"" + jsonEscape(String.join(",", beforePerms))
                        + "\",\"after\":\"" + jsonEscape(String.join(",", afterPerms)) + "\"}");

        int userCount = 0;
        try {
            Integer uc = jdbc.queryForObject(
                    "select count(distinct u.id) from user_role ur join app_user u on u.id = ur.user_id and u.is_active = true where ur.role_id = ? and u.org_id = ?",
                    Integer.class, id, me.getOrgId());
            if (uc != null) userCount = uc;
        } catch (Exception ignored) {}
        return new RoleResponse(id, code, label.trim(), false, afterPerms,
                userCount, moduleKeysOf(afterPerms));
    }

    // ══════════════════ 4b. USERS OF A ROLE ══════════════════

    /** GET /api/roles/{id}/users → [{id, fullName, email, status}]. */
    public List<RoleUserResponse> usersOfRole(Authentication auth, UUID id) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        // verify the role belongs to the caller's org (or is a system role)
        List<Map<String, Object>> roleRows = jdbc.queryForList(
                "select org_id from role where id = ?", id);
        if (roleRows.isEmpty())
            throw new BusinessRuleException("ROLE_NOT_FOUND", "Rôle introuvable");
        UUID roleOrgId = (UUID) roleRows.get(0).get("org_id");
        if (roleOrgId != null && !roleOrgId.equals(me.getOrgId()))
            throw new BusinessRuleException("ROLE_NOT_FOUND", "Rôle introuvable");

        return jdbc.queryForList("""
                select u.id, u.full_name, u.email, u.is_active, u.must_change_password, u.is_owner
                from user_role ur
                join app_user u on u.id = ur.user_id
                where ur.role_id = ? and u.org_id = ?
                order by u.full_name
                """, id, me.getOrgId()).stream()
                .map(r -> new RoleUserResponse(
                        (UUID) r.get("id"),
                        (String) r.get("full_name"),
                        (String) r.get("email"),
                        userStatus(r)))
                .toList();
    }

    // ══════════════════ 4c. DUPLICATE A ROLE ══════════════════

    /** POST /api/roles/{id}/duplicate {label} → new custom role with the same permissions. */
    @Transactional
    public RoleResponse duplicateRole(Authentication auth, UUID id, String label) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        List<Map<String, Object>> found = jdbc.queryForList("select org_id, code from role where id = ?", id);
        if (found.isEmpty())
            throw new BusinessRuleException("ROLE_NOT_FOUND", "Rôle introuvable");
        UUID roleOrgId = (UUID) found.get(0).get("org_id");
        if (roleOrgId != null && !roleOrgId.equals(me.getOrgId()))
            throw new BusinessRuleException("ROLE_NOT_FOUND", "Rôle introuvable");
        if (isBlank(label))
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le libellé est obligatoire");

        String code = slug(label);
        List<String> existing = jdbc.queryForList(
                "select id from role where code = ? and (org_id = ? or org_id is null)",
                String.class, code, me.getOrgId());
        if (!existing.isEmpty())
            throw new BusinessRuleException("CODE_EXISTS", "Un rôle avec ce code existe déjà");

        // copy permissions from the source role
        List<String> sourcePerms = jdbc.queryForList(
                "select permission_id from role_permission where role_id = ? order by permission_id",
                String.class, id);

        UUID newId = jdbc.queryForObject("""
                insert into role (org_id, code, label, scope_type) values (?, ?, ?, 'ALL') returning id
                """, UUID.class, me.getOrgId(), code, label.trim());
        List<String> permissions = insertPermissions(newId, sourcePerms);

        audit.log("ROLE_DUPLICATED", "role", newId,
                "{\"from\":\"" + jsonEscape((String) found.get(0).get("code")) + "\",\"code\":\"" + jsonEscape(code) + "\"}");
        return new RoleResponse(newId, code, label.trim(), false, permissions,
                0, moduleKeysOf(permissions));
    }

    // ══════════════════ HELPERS ══════════════════

    /** Derive module keys from permission codes (step 1 of the spec). */
    private static List<String> moduleKeysOf(List<String> permissions) {
        Set<String> modules = new LinkedHashSet<>();
        for (String p : permissions) {
            String module = switch (p.split("_")[0]) {
                case "TICKET" -> "tickets";
                case "PROJECT" -> "projects";
                case "DOCUMENT", "FOLDER" -> "documents";
                case "CASH", "PARTY" -> "cash";
                case "STOCK", "PURCHASE" -> "stock";
                case "PROCESS" -> "process";
                case "USER", "ROLE", "AUDIT" -> "admin";
                default -> null;
            };
            if (module != null) modules.add(module);
        }
        return new ArrayList<>(modules);
    }

    /** Compute the 5-status from a user row (same logic as UserService.userStatus). */
    private static String userStatus(Map<String, Object> r) {
        boolean isOwner = Boolean.TRUE.equals(r.get("is_owner"));
        boolean isActive = Boolean.TRUE.equals(r.get("is_active"));
        boolean mustChange = Boolean.TRUE.equals(r.get("must_change_password"));
        if (!isActive) return "INACTIVE";
        if (mustChange) return "PENDING";
        if (isOwner) return "OWNER";
        return "ACTIVE";
    }

    /** Inserts one role_permission row per permission id that really exists; unknown codes are ignored. */
    private List<String> insertPermissions(UUID roleId, List<String> permissionCodes) {
        List<String> attached = new ArrayList<>();
        if (permissionCodes == null) return attached;
        for (String pc : new LinkedHashSet<>(permissionCodes)) {
            if (isBlank(pc)) continue;
            List<String> known = jdbc.queryForList("select id from permission where id = ?", String.class, pc);
            if (!known.isEmpty()) {
                jdbc.update("insert into role_permission (role_id, permission_id) values (?, ?)", roleId, pc);
                attached.add(pc);
            }
        }
        attached.sort(Comparator.naturalOrder());
        return attached;
    }

    /** "Chef de projet" → "CHEF_DE_PROJET": uppercased, every non-alphanumeric becomes _. */
    private String slug(String label) {
        return label.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
