package tn.hydra.cash;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.BadRequestException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * PartyService — directory of sous-traitants / fournisseurs per org.
 *
 * Phase 1: minimal CRUD. Party is referenced by cash_movement.party_id.
 * Soft-deactivate (active=false) is preferred over hard-delete once a party
 * is referenced by movements — see {@link #deleteOrDeactivate}.
 */
@Service
public class PartyService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;

    public PartyService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
    }

    // ══════════════════════════════════════════════════════════════
    //  RESPONSE / REQUEST RECORDS
    // ══════════════════════════════════════════════════════════════

    public record PartyResponse(UUID id, UUID orgId, String name, String type,
                                  String phone, boolean active,
                                  Instant createdAt, Instant updatedAt) {}

    public record CreatePartyRequest(String name, String type, String phone) {}
    public record UpdatePartyRequest(String name, String type, String phone, Boolean active) {}

    // ══════════════════════════════════════════════════════════════
    //  AUTH HELPERS — uses PERM_PARTY_MANAGE for writes.
    //  Reads are gated by PERM_CASH_READ at the controller level (@PreAuthorize).
    // ══════════════════════════════════════════════════════════════

    private AppUser currentUser(Authentication auth) {
        return users.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Unknown user: " + auth.getName()));
    }

    /** True if the caller holds the given PERM_ authority (or is_owner). */
    private boolean hasPermission(Authentication auth, String perm) {
        String authority = "PERM_" + perm;
        return auth.getAuthorities().stream()
                .map(org.springframework.security.core.GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    /**
     * Anyone with PERM_PARTY_MANAGE can write parties. The controller
     * @PreAuthorize already enforces this; we double-check here as
     * defence-in-depth (and to give a clearer BusinessRuleException vs
     * Spring's default 403 AccessDenied body).
     */
    private void requireCanManage(Authentication auth, AppUser me) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        if (!hasPermission(auth, "PARTY_MANAGE")) {
            throw new BusinessRuleException("FORBIDDEN",
                    "Permission manquante: PERM_PARTY_MANAGE — gestion des tiers");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  LIST
    // ══════════════════════════════════════════════════════════════

    /** List parties in caller's org. Optional type filter ('SUB'|'SUPPLIER'|'OTHER').
     *  By default returns only active parties; pass includeInactive=true to see all. */
    public List<PartyResponse> list(Authentication auth, String type, boolean includeInactive) {
        AppUser me = currentUser(auth);
        StringBuilder sql = new StringBuilder("""
                select id, org_id, name, type, phone, active, created_at, updated_at
                from party
                where org_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(me.getOrgId());

        if (type != null && !type.isBlank()) {
            sql.append(" and type = ?");
            args.add(type.trim());
        }
        if (!includeInactive) {
            sql.append(" and active = true");
        }
        sql.append(" order by active desc, name asc");

        return jdbc.queryForList(sql.toString(), args.toArray())
                .stream().map(PartyService::toResponse).toList();
    }

    /** Single party by id — must belong to caller's org. */
    public PartyResponse get(Authentication auth, UUID id) {
        AppUser me = currentUser(auth);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, name, type, phone, active, created_at, updated_at
                from party
                where id = ? and org_id = ?
                """, id, me.getOrgId());
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("PARTY_NOT_FOUND", "Tiers introuvable");
        }
        return toResponse(rows.get(0));
    }

    // ══════════════════════════════════════════════════════════════
    //  CREATE / UPDATE
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public PartyResponse create(Authentication auth, CreatePartyRequest req) {
        AppUser me = currentUser(auth);
        requireCanManage(auth, me);

        if (req.name() == null || req.name().isBlank()) {
            throw new BadRequestException("NAME_REQUIRED", "Le nom du tiers est obligatoire");
        }
        String type = normalizeType(req.type());

        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into party (id, org_id, name, type, phone, active, created_at, updated_at)
                values (?, ?, ?, ?, ?, true, now(), now())
                """, id, me.getOrgId(), req.name().trim(), type,
                blankToNull(req.phone()));

        audit.log("PARTY_CREATED", "party", id,
                "{\"name\":\"" + jsonEscape(req.name().trim()) +
                "\",\"type\":\"" + type + "\"}");

        return get(auth, id);
    }

    @Transactional
    public PartyResponse update(Authentication auth, UUID id, UpdatePartyRequest req) {
        AppUser me = currentUser(auth);
        requireCanManage(auth, me);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id from party where id = ? and org_id = ?", id, me.getOrgId());
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("PARTY_NOT_FOUND", "Tiers introuvable");
        }

        // Build SET clause dynamically — only update provided fields
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        if (req.name() != null && !req.name().isBlank()) {
            sets.add("name = ?"); args.add(req.name().trim());
        }
        if (req.type() != null && !req.type().isBlank()) {
            sets.add("type = ?"); args.add(normalizeType(req.type()));
        }
        if (req.phone() != null) {
            sets.add("phone = ?"); args.add(blankToNull(req.phone()));
        }
        if (req.active() != null) {
            sets.add("active = ?"); args.add(req.active());
        }
        if (sets.isEmpty()) {
            return get(auth, id); // nothing to update
        }
        sets.add("updated_at = now()");
        args.add(id);
        args.add(me.getOrgId());

        jdbc.update("update party set " + String.join(", ", sets) +
                " where id = ? and org_id = ?", args.toArray());

        return get(auth, id);
    }

    /**
     * Soft-deactivate a party (preferred). Hard-delete only if no movement references it.
     * Either way, the caller must be FINANCE/OWNER.
     */
    @Transactional
    public void deleteOrDeactivate(Authentication auth, UUID id) {
        AppUser me = currentUser(auth);
        requireCanManage(auth, me);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id from party where id = ? and org_id = ?", id, me.getOrgId());
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("PARTY_NOT_FOUND", "Tiers introuvable");
        }

        Integer refCount = jdbc.queryForObject(
                "select count(*) from cash_movement where party_id = ?",
                Integer.class, id);
        if (refCount != null && refCount > 0) {
            // Soft-deactivate to preserve movement history
            jdbc.update("update party set active = false, updated_at = now() where id = ?", id);
        } else {
            jdbc.update("delete from party where id = ?", id);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  HELPERS
    // ══════════════════════════════════════════════════════════════

    private static String normalizeType(String type) {
        if (type == null) return "OTHER";
        String t = type.trim().toUpperCase();
        return switch (t) {
            case "SUB", "SOUS_TRAITANT", "SOUS-TRAITANT" -> "SUB";
            case "SUPPLIER", "FOURNISSEUR", "SUP" -> "SUPPLIER";
            default -> "OTHER";
        };
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static PartyResponse toResponse(Map<String, Object> row) {
        return new PartyResponse(
                (UUID) row.get("id"),
                (UUID) row.get("org_id"),
                (String) row.get("name"),
                (String) row.get("type"),
                (String) row.get("phone"),
                Boolean.TRUE.equals(row.get("active")),
                toInstant(row.get("created_at")),
                toInstant(row.get("updated_at"))
        );
    }

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        return java.time.Instant.parse(value.toString());
    }
}
