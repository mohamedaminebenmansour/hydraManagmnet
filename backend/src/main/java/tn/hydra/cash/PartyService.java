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

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
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

    /**
     * Fiche tiers — money summary for one party.
     *
     * <p>Mirrors what the owner sees when opening a sous-traitant / fournisseur
     * in the caisse UI: how much has been paid to them total (sum of POSTED
     * OUT movements), how many movements, the last payment date, and a short
     * list of recent movements (deep-linkable to the movement detail view).
     *
     * <p>Single round-trip: one COUNT + SUM + MAX query, then one paginated
     * SELECT for the recent list. No client-side N+1.
     */
    public record CashSummary(
            UUID partyId,
            String partyName,
            String partyType,
            BigDecimal totalPaid,       // sum OUT POSTED amount (what the company actually paid)
            int movementCount,          // count of OUT POSTED movements
            LocalDate lastMovementDate, // max(movement_date) among OUT POSTED
            List<CashSummaryLine> recent // last 10 POSTED OUT movements, ordered desc
    ) {}

    /** One line of the recent-movements list inside {@link CashSummary}. */
    public record CashSummaryLine(
            UUID id,
            LocalDate movementDate,
            BigDecimal amount,
            String movementType,
            String status,
            String paymentMode,
            String voucherNo,
            String label,
            UUID projectId,
            String projectName,
            UUID potId,
            String potName
    ) {}

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
    //  CASH SUMMARY — fiche tiers (TASK 2)
    //  Total paid (POSTED OUT) + count + last date + recent movements.
    //  All in 2 queries (1 aggregate + 1 list) — no client-side N+1.
    // ══════════════════════════════════════════════════════════════

    /**
     * GET /api/parties/{id}/cash-summary?projectId=optional
     *
     * <p>"Fiche tiers" — the money side of a sous-traitant / fournisseur:
     * <ul>
     *   <li>{@code totalPaid} — SUM(POSTED OUT amount) for this party
     *       (the money the company actually handed out);</li>
     *   <li>{@code movementCount} — how many POSTED OUT movements to this party;</li>
     *   <li>{@code lastMovementDate} — most recent POSTED OUT date (when did we
     *       last pay them);</li>
     *   <li>{@code recent} — last 10 POSTED OUT movements, deep-linkable to the
     *       movement detail view.</li>
     * </ul>
     *
     * <p>Optional {@code projectId} scopes the summary to a single project
     * (useful when a sous-traitant works on several chantiers).
     *
     * <p>Access: caller must own the party (party.org_id == caller.org_id).
     * Reads are gated by PERM_CASH_READ at the controller level (same as the
     * rest of the cash domain).
     */
    public CashSummary cashSummary(Authentication auth, UUID partyId, UUID projectId) {
        AppUser me = currentUser(auth);

        // (1) Aggregate query — COUNT + SUM + MAX in one round-trip
        StringBuilder aggSql = new StringBuilder("""
                select py.id   as party_id,
                       py.name as party_name,
                       py.type as party_type,
                       coalesce(sum(case when m.status = 'POSTED' and m.movement_type = 'OUT'
                                         then m.amount else 0 end), 0) as total_paid,
                       count(case when m.status = 'POSTED' and m.movement_type = 'OUT'
                                  then 1 end) as movement_count,
                       max(case when m.status = 'POSTED' and m.movement_type = 'OUT'
                                then m.movement_date end) as last_date
                from party py
                left join cash_movement m on m.party_id = py.id and m.org_id = py.org_id
                where py.id = ? and py.org_id = ?
                """);
        List<Object> aggArgs = new ArrayList<>();
        aggArgs.add(partyId);
        aggArgs.add(me.getOrgId());
        if (projectId != null) {
            aggSql.append(" and m.project_id = ?");
            aggArgs.add(projectId);
        }
        aggSql.append(" group by py.id, py.name, py.type");

        Map<String, Object> agg;
        try {
            agg = jdbc.queryForMap(aggSql.toString(), aggArgs.toArray());
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResourceNotFoundException("PARTY_NOT_FOUND", "Tiers introuvable");
        }

        BigDecimal totalPaid = agg.get("total_paid") == null ? BigDecimal.ZERO
                : (BigDecimal) agg.get("total_paid");
        // count() returns Long in PostgreSQL via JDBC; cast safely
        int movementCount = ((Number) agg.get("movement_count")).intValue();
        Object lastDateObj = agg.get("last_date");
        LocalDate lastDate = lastDateObj == null ? null
                : (lastDateObj instanceof Date d ? d.toLocalDate()
                    : LocalDate.parse(lastDateObj.toString()));

        // (2) Recent list — last 10 POSTED OUT movements, JOINed with project + pot names
        //     (single query; no per-row lookup)
        StringBuilder recentSql = new StringBuilder("""
                select m.id, m.movement_date, m.amount, m.movement_type, m.status,
                       m.payment_mode, m.voucher_no, m.label,
                       m.project_id, p.name as project_name,
                       m.from_pot_id as pot_id, fp.name as pot_name
                from cash_movement m
                left join project  p  on p.id  = m.project_id
                left join cash_pot fp on fp.id = m.from_pot_id
                where m.party_id = ? and m.org_id = ?
                  and m.movement_type = 'OUT' and m.status = 'POSTED'
                """);
        List<Object> recentArgs = new ArrayList<>();
        recentArgs.add(partyId);
        recentArgs.add(me.getOrgId());
        if (projectId != null) {
            recentSql.append(" and m.project_id = ?");
            recentArgs.add(projectId);
        }
        recentSql.append(" order by m.movement_date desc, m.created_at desc limit 10");

        List<CashSummaryLine> recent = jdbc.queryForList(recentSql.toString(), recentArgs.toArray())
                .stream()
                .map(PartyService::toCashSummaryLine)
                .toList();

        return new CashSummary(
                partyId,
                (String) agg.get("party_name"),
                (String) agg.get("party_type"),
                totalPaid,
                movementCount,
                lastDate,
                recent
        );
    }

    /** Mapper for CashSummaryLine. */
    private static CashSummaryLine toCashSummaryLine(Map<String, Object> row) {
        Object dateObj = row.get("movement_date");
        LocalDate movementDate = dateObj == null ? null
                : (dateObj instanceof Date d ? d.toLocalDate()
                    : LocalDate.parse(dateObj.toString()));
        return new CashSummaryLine(
                (UUID) row.get("id"),
                movementDate,
                (BigDecimal) row.get("amount"),
                (String) row.get("movement_type"),
                (String) row.get("status"),
                (String) row.get("payment_mode"),
                (String) row.get("voucher_no"),
                (String) row.get("label"),
                (UUID) row.get("project_id"),
                (String) row.get("project_name"),
                (UUID) row.get("pot_id"),
                (String) row.get("pot_name")
        );
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
