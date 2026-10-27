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
 * ============================================================================
 *  CashService — single source of truth for money in Hydra.
 * ============================================================================
 *
 *  DESIGN (Tunisian BTP reality, IntegraSys ERP pattern):
 *
 *   - cash_pot (caisse) = either COMPANY-wide ("Caisse centrale") or PROJECT-scoped
 *     ("Caisse chantier"). Default currency: TND.
 *
 *   - cash_movement = every money event. Lifecycle: DRAFT → POSTED → VOID.
 *     - DRAFT can be edited (PUT) — used when a payment is being prepared but
 *       not yet executed.
 *     - POSTED is IMMUTABLE. To correct a mistake, VOID it and create a new
 *       movement. This is the SYSCOHADA / accrual accounting pattern: the
 *       audit trail is never rewritten.
 *     - VOID is excluded from balance computation but the row is preserved
 *       for traceability.
 *
 *   - balance(pot) is ALWAYS computed as SUM(POSTED movements affecting the pot):
 *       IN: +amount to to_pot
 *       OUT: -amount from from_pot
 *       TRANSFER: -amount from from_pot, +amount to to_pot
 *     Never stored as a mutable field.
 *
 *   - Negative balance guard: when posting an OUT or TRANSFER, if the resulting
 *     from_pot balance would go negative, the post is rejected. This is the
 *     "no overdraft" safety default (Phase 1: ON).
 *
 *  SECURITY:
 *   - Anyone in the org can list pots + movements (read-only visibility for owner).
 *   - Create/update/post/void require: is_owner OR FINANCE business role OR
 *     project membership (for project-scoped pots/movements).
 *   - All org-isolated: queries always filter by caller's org_id.
 *
 * ============================================================================
 *  PHASE 2 — wiring the Process engine to auto-post:
 * ============================================================================
 *
 *  When a PAYMENT_APPROVAL process completes (the gérant clicks "Valider" on
 *  the APPROVE step), ProcessService.completeStep() should call:
 *
 *      cashService.onProcessCompletedPostMovement(movementId);
 *
 *  The movement must already exist as DRAFT with process_instance_id set to
 *  the running instance. To wire this:
 *
 *  1. In ProcessService.start(): after creating the instance, look up the
 *     pending cash_movement by `subject_id` (= the movement id) and set its
 *     process_instance_id. OR have the frontend create the movement DRAFT
 *     first, then start the process with subjectId = movementId.
 *
 *  2. In ProcessService.completeStep(): when the LAST step is completed
 *     (process → COMPLETED), call:
 *
 *         cashService.onProcessCompletedPostMovement(movementId);
 *
 *     This flips the movement DRAFT → POSTED, sets posted_at/by, and the
 *     balance is now affected. If the negative-balance guard fails, the
 *     movement stays DRAFT and the caller gets a BusinessRuleException
 *     (process completion should be rolled back).
 *
 *  3. Phase 2 might also add a `cash_movement.process_status` field to show
 *     "En cours d'approbation" badge in the UI.
 *
 *  The stub method below is the entry point — currently a no-op, marked
 *  @Transactional so Phase 2 can call it from inside ProcessService without
 *  an extra DB roundtrip wrapper.
 *
 * ============================================================================
 */
@Service
public class CashService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;

    public CashService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
    }

    // ══════════════════════════════════════════════════════════════
    //  RESPONSE / REQUEST RECORDS
    // ══════════════════════════════════════════════════════════════

    public record PotResponse(UUID id, UUID orgId, String type, UUID projectId,
                              String name, String currency, boolean active,
                              Instant createdAt) {}

    public record BalanceResponse(UUID potId, BigDecimal balance, String currency) {}

    public record MovementResponse(UUID id, UUID orgId, String movementType,
                                    BigDecimal amount, String currency, LocalDate movementDate,
                                    UUID fromPotId, UUID toPotId, UUID projectId, UUID partyId,
                                    String label, String note, String status,
                                    UUID processInstanceId, UUID createdBy, Instant createdAt,
                                    UUID postedBy, Instant postedAt,
                                    UUID voidedBy, Instant voidedAt, String voidReason) {}

    public record CreateMovementRequest(String movementType, BigDecimal amount,
                                         LocalDate movementDate, UUID fromPotId, UUID toPotId,
                                         UUID projectId, UUID partyId,
                                         String label, String note,
                                         UUID processInstanceId) {}

    public record UpdateMovementRequest(String movementType, BigDecimal amount,
                                         LocalDate movementDate, UUID fromPotId, UUID toPotId,
                                         UUID projectId, UUID partyId,
                                         String label, String note) {}

    public record PostResponse(UUID id, String status, BigDecimal fromPotBalanceAfter) {}

    public record VoidRequest(String reason) {}

    // ══════════════════════════════════════════════════════════════
    //  AUTH HELPERS
    // ══════════════════════════════════════════════════════════════

    private AppUser currentUser(Authentication auth) {
        return users.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Unknown user: " + auth.getName()));
    }

    /**
     * Require: is_owner OR FINANCE business role OR project member (for project-scoped actions).
     * The "action" param is just for the error message.
     */
    private void requireCanWrite(AppUser me, UUID projectId, String action) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        // Check business role: FINANCE, ACHAT, CHEF_PROJET, DIRECTION
        Integer cnt = jdbc.queryForObject("""
                select count(*) from user_role ur
                join role r on r.id = ur.role_id
                where ur.user_id = ? and r.code in ('FINANCE','OWNER','DIRECTION','ACHAT','CHEF_PROJET')
                """, Integer.class, me.getId());
        if (cnt != null && cnt > 0) return;
        // Project member fallback for project-scoped actions
        if (projectId != null) {
            Integer pm = jdbc.queryForObject(
                    "select count(*) from project_member where project_id = ? and user_id = ?",
                    Integer.class, projectId, me.getId());
            if (pm != null && pm > 0) return;
        }
        throw new BusinessRuleException("FORBIDDEN",
                "Action non autorisée : " + action + " nécessite OWNER/FINANCE/CHEF_PROJET ou appartenance au chantier");
    }

    /** Stricter check for POST and VOID: is_owner OR FINANCE business role. */
    private void requireCanPostOrVoid(AppUser me) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        Integer cnt = jdbc.queryForObject("""
                select count(*) from user_role ur
                join role r on r.id = ur.role_id
                where ur.user_id = ? and r.code in ('FINANCE','OWNER','DIRECTION')
                """, Integer.class, me.getId());
        if (cnt == null || cnt == 0) {
            throw new BusinessRuleException("FORBIDDEN",
                    "Comptabilisation (POST/VOID) nécessite OWNER/FINANCE/DIRECTION");
        }
    }

    private void requireProjectAccess(AppUser me, UUID projectId) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        if (projectId == null) return;
        Integer cnt = jdbc.queryForObject(
                "select count(*) from project_member where project_id = ? and user_id = ?",
                Integer.class, projectId, me.getId());
        if (cnt == null || cnt == 0) {
            throw new ResourceNotFoundException("PROJECT_NOT_FOUND",
                    "Projet introuvable ou accès refusé");
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  POTS — list / ensure-company / ensure-project / balance
    // ══════════════════════════════════════════════════════════════

    /** List pots visible to caller: COMPANY pots + PROJECT pots for projects they can access. */
    public List<PotResponse> listPots(Authentication auth) {
        AppUser me = currentUser(auth);

        // COMPANY pots — visible to anyone in the org
        List<PotResponse> result = new ArrayList<>(jdbc.queryForList("""
                select id, org_id, type, project_id, name, currency, active, created_at
                from cash_pot
                where org_id = ? and type = 'COMPANY'
                order by active desc, name asc
                """, me.getOrgId())
                .stream().map(CashService::toPotResponse).toList());

        // PROJECT pots — only for projects the caller is a member of (or owner)
        if (Boolean.TRUE.equals(me.getIsOwner())) {
            result.addAll(jdbc.queryForList("""
                    select id, org_id, type, project_id, name, currency, active, created_at
                    from cash_pot
                    where org_id = ? and type = 'PROJECT'
                    order by active desc, name asc
                    """, me.getOrgId())
                    .stream().map(CashService::toPotResponse).toList());
        } else {
            result.addAll(jdbc.queryForList("""
                    select cp.id, cp.org_id, cp.type, cp.project_id, cp.name, cp.currency, cp.active, cp.created_at
                    from cash_pot cp
                    where cp.org_id = ? and cp.type = 'PROJECT'
                      and exists (
                        select 1 from project_member pm where pm.project_id = cp.project_id and pm.user_id = ?
                      )
                    order by cp.active desc, cp.name asc
                    """, me.getOrgId(), me.getId())
                    .stream().map(CashService::toPotResponse).toList());
        }

        return result;
    }

    /**
     * Ensure the org has its "Caisse centrale" (COMPANY pot). Idempotent —
     * if it already exists (and is active), returns it.
     */
    @Transactional
    public PotResponse ensureCompanyPot(Authentication auth) {
        AppUser me = currentUser(auth);
        requireCanWrite(me, null, "ensure-company-pot");

        // Try to fetch existing active company pot
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, type, project_id, name, currency, active, created_at
                from cash_pot
                where org_id = ? and type = 'COMPANY' and active = true
                limit 1
                """, me.getOrgId());
        if (!rows.isEmpty()) {
            return toPotResponse(rows.get(0));
        }

        // Also check for an INACTIVE one to reactivate
        List<Map<String, Object>> inactive = jdbc.queryForList(
                "select id from cash_pot where org_id = ? and type = 'COMPANY' and active = false limit 1",
                me.getOrgId());
        if (!inactive.isEmpty()) {
            UUID id = (UUID) inactive.get(0).get("id");
            jdbc.update("update cash_pot set active = true where id = ?", id);
            return getPot(auth, id);
        }

        // Create a fresh one
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into cash_pot (id, org_id, type, project_id, name, currency, active, created_at)
                values (?, ?, 'COMPANY', null, 'Caisse centrale', 'TND', true, now())
                """, id, me.getOrgId());

        audit.log("CASH_POT_CREATED", "cash_pot", id,
                "{\"type\":\"COMPANY\",\"name\":\"Caisse centrale\"}");

        return getPot(auth, id);
    }

    /**
     * Ensure a PROJECT pot exists for the given project. Idempotent.
     * Name defaults to "Caisse <project name>".
     */
    @Transactional
    public PotResponse ensureProjectPot(Authentication auth, UUID projectId) {
        AppUser me = currentUser(auth);
        requireProjectAccess(me, projectId);

        // Check existing
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, type, project_id, name, currency, active, created_at
                from cash_pot
                where org_id = ? and project_id = ? and type = 'PROJECT'
                limit 1
                """, me.getOrgId(), projectId);
        if (!rows.isEmpty()) {
            // Reactivate if needed
            Map<String, Object> row = rows.get(0);
            if (!Boolean.TRUE.equals(row.get("active"))) {
                jdbc.update("update cash_pot set active = true where id = ?", row.get("id"));
            }
            return toPotResponse(row);
        }

        // Get project name for the pot name
        String projectName = jdbc.queryForObject(
                "select name from project where id = ? and org_id = ?",
                String.class, projectId, me.getOrgId());
        if (projectName == null) {
            throw new ResourceNotFoundException("PROJECT_NOT_FOUND", "Projet introuvable");
        }
        String potName = "Caisse " + projectName;

        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into cash_pot (id, org_id, type, project_id, name, currency, active, created_at)
                values (?, ?, 'PROJECT', ?, ?, 'TND', true, now())
                """, id, me.getOrgId(), projectId, potName);

        audit.log("CASH_POT_CREATED", "cash_pot", id,
                "{\"type\":\"PROJECT\",\"projectId\":\"" + projectId + "\",\"name\":\"" + jsonEscape(potName) + "\"}");

        return getPot(auth, id);
    }

    /** Get one pot — must belong to caller's org. PROJECT pots also require project access. */
    public PotResponse getPot(Authentication auth, UUID id) {
        AppUser me = currentUser(auth);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, type, project_id, name, currency, active, created_at
                from cash_pot
                where id = ? and org_id = ?
                """, id, me.getOrgId());
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("POT_NOT_FOUND", "Caisse introuvable");
        }
        PotResponse pot = toPotResponse(rows.get(0));
        if ("PROJECT".equals(pot.type()) && pot.projectId() != null) {
            requireProjectAccess(me, pot.projectId());
        }
        return pot;
    }

    /** Balance for a pot — SUM of POSTED movements. */
    public BalanceResponse balance(Authentication auth, UUID potId) {
        AppUser me = currentUser(auth);
        PotResponse pot = getPot(auth, potId); // validates access

        BigDecimal bal = computeBalance(pot.id());
        return new BalanceResponse(pot.id(), bal, pot.currency());
    }

    /**
     * Pure balance computation: SUM(POSTED IN to pot) - SUM(POSTED OUT from pot)
     * + SUM(POSTED TRANSFER to pot) - SUM(POSTED TRANSFER from pot).
     * Visible to the service only — used by post() to enforce the no-overdraft rule.
     */
    private BigDecimal computeBalance(UUID potId) {
        BigDecimal bal = jdbc.queryForObject("""
                select coalesce(sum(amount), 0) from cash_movement
                where status = 'POSTED' and to_pot_id = ? and movement_type in ('IN','TRANSFER')
                """, BigDecimal.class, potId);
        BigDecimal out = jdbc.queryForObject("""
                select coalesce(sum(amount), 0) from cash_movement
                where status = 'POSTED' and from_pot_id = ? and movement_type in ('OUT','TRANSFER')
                """, BigDecimal.class, potId);
        if (bal == null) bal = BigDecimal.ZERO;
        if (out == null) out = BigDecimal.ZERO;
        return bal.subtract(out);
    }

    // ══════════════════════════════════════════════════════════════
    //  MOVEMENTS — create DRAFT / update DRAFT only / get / list
    // ══════════════════════════════════════════════════════════════

    /**
     * Create a DRAFT movement. Validates type/pots/amount/date + caller has write access.
     * processInstanceId may be set later (Phase 2) when a workflow starts.
     */
    @Transactional
    public MovementResponse createMovement(Authentication auth, CreateMovementRequest req) {
        AppUser me = currentUser(auth);
        validateMovementInput(req.movementType(), req.amount(), req.movementDate(),
                req.fromPotId(), req.toPotId());

        // Resolve project_id: from request, or from PROJECT pot (when from/to is project pot)
        UUID resolvedProjectId = req.projectId();
        if (resolvedProjectId == null) {
            resolvedProjectId = lookupProjectFromPot(req.fromPotId(), me.getOrgId());
            if (resolvedProjectId == null) {
                resolvedProjectId = lookupProjectFromPot(req.toPotId(), me.getOrgId());
            }
        }

        requireCanWrite(me, resolvedProjectId, "create-movement");

        // Validate pot ownership (must be in caller's org)
        validatePotInOrg(req.fromPotId(), me.getOrgId());
        validatePotInOrg(req.toPotId(), me.getOrgId());
        if (resolvedProjectId != null) {
            requireProjectAccess(me, resolvedProjectId);
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into cash_movement
                  (id, org_id, movement_type, amount, currency, movement_date,
                   from_pot_id, to_pot_id, project_id, party_id, label, note,
                   status, process_instance_id, created_by, created_at)
                values (?, ?, ?, ?, 'TND', ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?, now())
                """, id, me.getOrgId(), normalizeType(req.movementType()), req.amount(),
                Date.valueOf(req.movementDate()),
                req.fromPotId(), req.toPotId(), resolvedProjectId, req.partyId(),
                blankToNull(req.label()), blankToNull(req.note()),
                req.processInstanceId(),
                me.getId());

        audit.log("CASH_MOVEMENT_CREATED", "cash_movement", id,
                "{\"type\":\"" + normalizeType(req.movementType()) +
                "\",\"amount\":" + req.amount() +
                ",\"status\":\"DRAFT\"}");

        return getMovement(auth, id);
    }

    /** Update a DRAFT movement only. POSTED/VOID → BusinessRuleException. */
    @Transactional
    public MovementResponse updateMovement(Authentication auth, UUID id, UpdateMovementRequest req) {
        AppUser me = currentUser(auth);
        Map<String, Object> row = fetchMovementForUpdate(id, me.getOrgId());
        String currentStatus = (String) row.get("status");
        if (!"DRAFT".equals(currentStatus)) {
            throw new BusinessRuleException("MOVEMENT_NOT_DRAFT",
                    "Seul un mouvement DRAFT peut être modifié (statut actuel: " + currentStatus + ")");
        }

        UUID projectId = (UUID) row.get("project_id");
        requireCanWrite(me, projectId, "update-movement");

        validateMovementInput(req.movementType(), req.amount(), req.movementDate(),
                req.fromPotId(), req.toPotId());
        validatePotInOrg(req.fromPotId(), me.getOrgId());
        validatePotInOrg(req.toPotId(), me.getOrgId());

        UUID resolvedProjectId = req.projectId() != null ? req.projectId() : projectId;
        if (resolvedProjectId != null) requireProjectAccess(me, resolvedProjectId);

        jdbc.update("""
                update cash_movement set
                  movement_type = ?, amount = ?, movement_date = ?,
                  from_pot_id = ?, to_pot_id = ?, project_id = ?, party_id = ?,
                  label = ?, note = ?
                where id = ? and org_id = ?
                """,
                normalizeType(req.movementType()), req.amount(), Date.valueOf(req.movementDate()),
                req.fromPotId(), req.toPotId(), resolvedProjectId, req.partyId(),
                blankToNull(req.label()), blankToNull(req.note()),
                id, me.getOrgId());

        return getMovement(auth, id);
    }

    /** Single movement by id — must be in caller's org + caller must have project access. */
    public MovementResponse getMovement(Authentication auth, UUID id) {
        AppUser me = currentUser(auth);
        Map<String, Object> row = fetchMovementForRead(id, me.getOrgId());
        UUID projectId = (UUID) row.get("project_id");
        if (projectId != null) requireProjectAccess(me, projectId);
        return toMovementResponse(row);
    }

    /**
     * List movements with optional filters:
     *   potId      — from_pot_id OR to_pot_id = ?
     *   partyId    — party_id = ?
     *   projectId  — project_id = ?
     *   status     — DRAFT | POSTED | VOID
     *   from / to  — movement_date range (ISO yyyy-MM-dd)
     */
    public List<MovementResponse> listMovements(
            Authentication auth, UUID potId, UUID partyId, UUID projectId,
            String status, LocalDate from, LocalDate to) {

        AppUser me = currentUser(auth);
        // If projectId filter, enforce project access
        if (projectId != null) requireProjectAccess(me, projectId);

        StringBuilder sql = new StringBuilder("""
                select id, org_id, movement_type, amount, currency, movement_date,
                       from_pot_id, to_pot_id, project_id, party_id, label, note, status,
                       process_instance_id, created_by, created_at, posted_by, posted_at,
                       voided_by, voided_at, void_reason
                from cash_movement
                where org_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(me.getOrgId());

        if (potId != null) {
            sql.append(" and (from_pot_id = ? or to_pot_id = ?)");
            args.add(potId); args.add(potId);
        }
        if (partyId != null) {
            sql.append(" and party_id = ?");
            args.add(partyId);
        }
        if (projectId != null) {
            sql.append(" and project_id = ?");
            args.add(projectId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (from != null) {
            sql.append(" and movement_date >= ?");
            args.add(Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" and movement_date <= ?");
            args.add(Date.valueOf(to));
        }
        sql.append(" order by movement_date desc, created_at desc");

        // Filter out movements on PROJECT pots the caller can't access
        // (for non-owner). Done in Java to keep SQL simple.
        return jdbc.queryForList(sql.toString(), args.toArray())
                .stream()
                .map(CashService::toMovementResponse)
                .filter(m -> {
                    if (Boolean.TRUE.equals(me.getIsOwner())) return true;
                    if (m.projectId() == null) return true;
                    Integer cnt = jdbc.queryForObject(
                            "select count(*) from project_member where project_id = ? and user_id = ?",
                            Integer.class, m.projectId(), me.getId());
                    return cnt != null && cnt > 0;
                })
                .toList();
    }

    // ══════════════════════════════════════════════════════════════
    //  POST / VOID — the immutable accounting core
    // ══════════════════════════════════════════════════════════════

    /**
     * POST a DRAFT movement → IMMUTABLE POSTED. Sets posted_at/by.
     * For OUT/TRANSFER: enforces no-negative-balance guard on from_pot.
     *
     * OUT movements require either:
     *   (a) a linked COMPLETED PAYMENT_APPROVAL process instance, OR
     *   (b) caller is owner AND ?force=true is explicitly passed.
     * This prevents "naked" OUT posting bypassing the approval workflow.
     * IN/TRANSFER are not affected (no workflow required for v1).
     */
    @Transactional
    public PostResponse postMovement(Authentication auth, UUID id, boolean force) {
        AppUser me = currentUser(auth);
        requireCanPostOrVoid(me);

        Map<String, Object> row = fetchMovementForUpdate(id, me.getOrgId());
        String status = (String) row.get("status");
        if (!"DRAFT".equals(status)) {
            throw new BusinessRuleException("MOVEMENT_NOT_DRAFT",
                    "Seul un mouvement DRAFT peut être comptabilisé (POST). Statut actuel: " + status);
        }

        String type = (String) row.get("movement_type");
        UUID fromPotId = (UUID) row.get("from_pot_id");
        UUID toPotId = (UUID) row.get("to_pot_id");
        BigDecimal amount = (BigDecimal) row.get("amount");
        UUID projectId = (UUID) row.get("project_id");
        UUID processInstanceId = (UUID) row.get("process_instance_id");

        // Naked-OUT guard: OUT must come from a completed workflow (or owner force)
        if ("OUT".equals(type)) {
            boolean hasCompletedProcess = false;
            if (processInstanceId != null) {
                Integer cnt = jdbc.queryForObject(
                        "select count(*) from process_instance where id = ? and status = 'COMPLETED'",
                        Integer.class, processInstanceId);
                hasCompletedProcess = cnt != null && cnt > 0;
            }
            if (!hasCompletedProcess && !force) {
                throw new BusinessRuleException("PAYMENT_REQUIRES_APPROVAL",
                        "Les sorties d'argent (OUT) doivent passer par le workflow PAYMENT_APPROVAL. " +
                        "Utilisez POST /api/cash/movements/submit-payment pour soumettre un paiement.");
            }
            if (force && !Boolean.TRUE.equals(me.getIsOwner())) {
                throw new BusinessRuleException("FORBIDDEN_FORCE",
                        "Le mode force nécessite OWNER");
            }
        }

        // Negative balance guard for OUT / TRANSFER
        BigDecimal fromPotBalanceAfter = null;
        if ("OUT".equals(type) || "TRANSFER".equals(type)) {
            BigDecimal before = computeBalance(fromPotId);
            BigDecimal after = before.subtract(amount);
            fromPotBalanceAfter = after;
            if (after.signum() < 0) {
                throw new BusinessRuleException("INSUFFICIENT_BALANCE",
                        "Solde insuffisant sur la caisse source (" + before + " < " + amount + " " + row.get("currency") + ")");
            }
        }

        jdbc.update("""
                update cash_movement set
                  status = 'POSTED', posted_by = ?, posted_at = now()
                where id = ? and org_id = ?
                """, me.getId(), id, me.getOrgId());

        audit.log("CASH_MOVEMENT_POSTED", "cash_movement", id,
                "{\"type\":\"" + type + "\",\"amount\":" + amount +
                (fromPotId != null ? ",\"fromPot\":\"" + fromPotId + "\"" : "") +
                (toPotId != null ? ",\"toPot\":\"" + toPotId + "\"" : "") +
                (force ? ",\"force\":true" : "") +
                "}");

        return new PostResponse(id, "POSTED", fromPotBalanceAfter);
    }

    /** Backward-compat overload — defaults force=false. */
    @Transactional
    public PostResponse postMovement(Authentication auth, UUID id) {
        return postMovement(auth, id, false);
    }

    /**
     * VOID a POSTED movement → VOID (immutable, ignored in balance).
     * Reason is required. Caller must be OWNER/FINANCE/DIRECTION.
     */
    @Transactional
    public MovementResponse voidMovement(Authentication auth, UUID id, VoidRequest req) {
        AppUser me = currentUser(auth);
        requireCanPostOrVoid(me);

        if (req == null || req.reason() == null || req.reason().isBlank()) {
            throw new BadRequestException("REASON_REQUIRED",
                    "Un motif d'annulation est obligatoire");
        }

        Map<String, Object> row = fetchMovementForUpdate(id, me.getOrgId());
        String status = (String) row.get("status");
        if (!"POSTED".equals(status)) {
            throw new BusinessRuleException("MOVEMENT_NOT_POSTED",
                    "Seul un mouvement POSTED peut être annulé (VOID). Statut actuel: " + status);
        }

        jdbc.update("""
                update cash_movement set
                  status = 'VOID', voided_by = ?, voided_at = now(), void_reason = ?
                where id = ? and org_id = ?
                """, me.getId(), req.reason().trim(), id, me.getOrgId());

        audit.log("CASH_MOVEMENT_VOIDED", "cash_movement", id,
                "{\"reason\":\"" + jsonEscape(req.reason().trim()) + "\"}");

        return getMovement(auth, id);
    }

    // ══════════════════════════════════════════════════════════════
    //  PHASE 2 HOOK — auto-post a movement when a process completes
    // ══════════════════════════════════════════════════════════════

    /**
     * PHASE 2 entry point — called by ProcessService when a PAYMENT_APPROVAL
     * workflow reaches COMPLETED status. Flips the linked DRAFT movement to POSTED.
     *
     * The movement must already exist (created via submitPayment facade) and have
     * process_instance_id set to the process instance that just completed.
     *
     * Idempotent: if the movement is already POSTED, returns "POSTED" without
     * re-running the guards (so a retry after a partial failure is safe).
     *
     * If the movement is VOID (e.g. someone manually voided meanwhile), throws
     * BusinessRuleException — the caller (ProcessService.completeStep) will
     * see this and the @Transactional rollback will undo the step completion.
     *
     * @param movementId   the cash_movement.id to post
     * @param postedBy     the AppUser.id of the approver (typically the owner)
     * @return  "POSTED" on success
     */
    @Transactional
    public String onProcessCompletedPostMovement(UUID movementId, UUID postedBy) {
        // 1. Resolve poster's org (system call, no auth context)
        AppUser poster = users.findById(postedBy)
                .orElseThrow(() -> new IllegalStateException("Unknown poster: " + postedBy));
        UUID orgId = poster.getOrgId();

        // 2. Fetch movement with row lock
        Map<String, Object> row = fetchMovementForUpdate(movementId, orgId);
        String status = (String) row.get("status");

        // 3. Idempotent: already POSTED → no-op
        if ("POSTED".equals(status)) {
            return "POSTED";
        }

        // 4. Must be DRAFT (VOID is a hard error — caller should not retry)
        if (!"DRAFT".equals(status)) {
            throw new BusinessRuleException("MOVEMENT_NOT_DRAFT",
                    "Le mouvement ne peut pas être POSTED automatiquement (statut actuel: " + status + ")");
        }

        String type = (String) row.get("movement_type");
        UUID fromPotId = (UUID) row.get("from_pot_id");
        UUID toPotId = (UUID) row.get("to_pot_id");
        BigDecimal amount = (BigDecimal) row.get("amount");
        String currency = (String) row.get("currency");

        // 5. Negative balance guard for OUT / TRANSFER (same as manual post)
        if ("OUT".equals(type) || "TRANSFER".equals(type)) {
            BigDecimal before = computeBalance(fromPotId);
            BigDecimal after = before.subtract(amount);
            if (after.signum() < 0) {
                throw new BusinessRuleException("INSUFFICIENT_BALANCE",
                        "Solde insuffisant sur la caisse source (" + before + " < " + amount + " " + currency + ")");
            }
        }

        // 6. Flip status → POSTED
        jdbc.update("""
                update cash_movement set
                  status = 'POSTED', posted_by = ?, posted_at = now()
                where id = ? and org_id = ?
                """, postedBy, movementId, orgId);

        // 7. Audit (system-triggered, no auth context — use logAs)
        audit.logAs(orgId, postedBy, "CASH_MOVEMENT_POSTED", "cash_movement", movementId,
                "{\"type\":\"" + type + "\",\"amount\":" + amount +
                (fromPotId != null ? ",\"fromPot\":\"" + fromPotId + "\"" : "") +
                (toPotId != null ? ",\"toPot\":\"" + toPotId + "\"" : "") +
                ",\"autoPostedViaProcess\":true}");

        return "POSTED";
    }

    /**
     * Link a movement to a process instance (called by PaymentFacade after
     * starting a PAYMENT_APPROVAL workflow). Internal — no auth check, the
     * caller has already validated access.
     */
    @Transactional
    public void linkProcessInstance(UUID movementId, UUID processInstanceId, UUID orgId) {
        int updated = jdbc.update("""
                update cash_movement set process_instance_id = ?
                where id = ? and org_id = ? and status = 'DRAFT'
                """, processInstanceId, movementId, orgId);
        if (updated == 0) {
            // Movement vanished, isn't DRAFT, or wrong org — caller has a bug
            throw new BusinessRuleException("MOVEMENT_NOT_LINKABLE",
                    "Impossible de lier le mouvement au process (statut ou org mismatch)");
        }
    }

    /**
     * Handle the case where a PAYMENT_APPROVAL process was REJECTED.
     * Per spec: leave movement DRAFT, clear process_instance_id (so the user
     * can edit + resubmit), audit CASH_MOVEMENT_REJECTED. No status change.
     */
    @Transactional
    public void handleProcessRejected(UUID movementId, UUID orgId, UUID rejectedBy) {
        // Clear the link — movement goes back to "editable DRAFT, no workflow running"
        jdbc.update("""
                update cash_movement set process_instance_id = null
                where id = ? and org_id = ?
                """, movementId, orgId);

        // Audit (system-triggered — rejectedBy may not be the auth user if this
        // is called from ProcessService.rejectStep which has its own auth context,
        // but the auth.log() inside ProcessService already emitted STEP_REJECTED
        // and PROCESS_REJECTED. This is the cash-side audit trail.)
        audit.logAs(orgId, rejectedBy, "CASH_MOVEMENT_REJECTED", "cash_movement", movementId,
                "{\"reason\":\"process rejected\",\"rejectedBy\":\"" + rejectedBy + "\"}");
    }

    /**
     * Public read for subject_id resolution — used by ProcessService when
     * completing a step, to look up the movement tied to a process instance.
     * Returns the movement id if subject is CASH_MOVEMENT, else null.
     */
    public UUID findMovementIdForProcess(UUID processInstanceId, UUID orgId) {
        List<UUID> rows = jdbc.queryForList("""
                select id from cash_movement
                where process_instance_id = ? and org_id = ?
                limit 1
                """, UUID.class, processInstanceId, orgId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ══════════════════════════════════════════════════════════════
    //  VALIDATION HELPERS
    // ══════════════════════════════════════════════════════════════

    private void validateMovementInput(String type, BigDecimal amount, LocalDate date,
                                        UUID fromPotId, UUID toPotId) {
        String t = normalizeType(type);
        if (amount == null || amount.signum() <= 0) {
            throw new BadRequestException("INVALID_AMOUNT",
                    "Le montant doit être positif");
        }
        if (date == null) {
            throw new BadRequestException("DATE_REQUIRED",
                    "La date du mouvement est obligatoire");
        }
        switch (t) {
            case "IN" -> {
                if (toPotId == null || fromPotId != null) {
                    throw new BadRequestException("INVALID_POTS_FOR_IN",
                            "IN: to_pot_id requis, from_pot_id doit être null");
                }
            }
            case "OUT" -> {
                if (fromPotId == null || toPotId != null) {
                    throw new BadRequestException("INVALID_POTS_FOR_OUT",
                            "OUT: from_pot_id requis, to_pot_id doit être null");
                }
            }
            case "TRANSFER" -> {
                if (fromPotId == null || toPotId == null) {
                    throw new BadRequestException("INVALID_POTS_FOR_TRANSFER",
                            "TRANSFER: from_pot_id et to_pot_id requis");
                }
                if (fromPotId.equals(toPotId)) {
                    throw new BadRequestException("SAME_POT_TRANSFER",
                            "TRANSFER: la caisse source et destination doivent être différentes");
                }
            }
            default -> throw new BadRequestException("INVALID_MOVEMENT_TYPE",
                    "Type invalide: " + type + " (IN/OUT/TRANSFER)");
        }
    }

    private void validatePotInOrg(UUID potId, UUID orgId) {
        if (potId == null) return;
        Integer cnt = jdbc.queryForObject(
                "select count(*) from cash_pot where id = ? and org_id = ?",
                Integer.class, potId, orgId);
        if (cnt == null || cnt == 0) {
            throw new BadRequestException("POT_NOT_IN_ORG",
                    "La caisse spécifiée n'appartient pas à votre organisation");
        }
    }

    private UUID lookupProjectFromPot(UUID potId, UUID orgId) {
        if (potId == null) return null;
        List<UUID> rows = jdbc.queryForList(
                "select project_id from cash_pot where id = ? and org_id = ? and project_id is not null",
                UUID.class, potId, orgId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    // ══════════════════════════════════════════════════════════════
    //  ROW FETCHERS + MAPPERS
    // ══════════════════════════════════════════════════════════════

    /** Fetch one movement row with lock (FOR UPDATE) — for post/void. */
    private Map<String, Object> fetchMovementForUpdate(UUID id, UUID orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, movement_type, amount, currency, movement_date,
                       from_pot_id, to_pot_id, project_id, party_id, label, note, status,
                       process_instance_id, created_by, created_at, posted_by, posted_at,
                       voided_by, voided_at, void_reason
                from cash_movement
                where id = ? and org_id = ?
                for update
                """, id, orgId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("MOVEMENT_NOT_FOUND",
                    "Mouvement introuvable");
        }
        return rows.get(0);
    }

    /** Fetch one movement row without lock (for reads). */
    private Map<String, Object> fetchMovementForRead(UUID id, UUID orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, movement_type, amount, currency, movement_date,
                       from_pot_id, to_pot_id, project_id, party_id, label, note, status,
                       process_instance_id, created_by, created_at, posted_by, posted_at,
                       voided_by, voided_at, void_reason
                from cash_movement
                where id = ? and org_id = ?
                """, id, orgId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("MOVEMENT_NOT_FOUND",
                    "Mouvement introuvable");
        }
        return rows.get(0);
    }

    private static PotResponse toPotResponse(Map<String, Object> row) {
        return new PotResponse(
                (UUID) row.get("id"),
                (UUID) row.get("org_id"),
                (String) row.get("type"),
                (UUID) row.get("project_id"),
                (String) row.get("name"),
                (String) row.get("currency"),
                Boolean.TRUE.equals(row.get("active")),
                toInstant(row.get("created_at"))
        );
    }

    private static MovementResponse toMovementResponse(Map<String, Object> row) {
        return new MovementResponse(
                (UUID) row.get("id"),
                (UUID) row.get("org_id"),
                (String) row.get("movement_type"),
                (BigDecimal) row.get("amount"),
                (String) row.get("currency"),
                ((Date) row.get("movement_date")).toLocalDate(),
                (UUID) row.get("from_pot_id"),
                (UUID) row.get("to_pot_id"),
                (UUID) row.get("project_id"),
                (UUID) row.get("party_id"),
                (String) row.get("label"),
                (String) row.get("note"),
                (String) row.get("status"),
                (UUID) row.get("process_instance_id"),
                (UUID) row.get("created_by"),
                toInstant(row.get("created_at")),
                (UUID) row.get("posted_by"),
                toInstant(row.get("posted_at")),
                (UUID) row.get("voided_by"),
                toInstant(row.get("voided_at")),
                (String) row.get("void_reason")
        );
    }

    // ══════════════════════════════════════════════════════════════
    //  UTIL
    // ══════════════════════════════════════════════════════════════

    private static String normalizeType(String type) {
        if (type == null) return "";
        return type.trim().toUpperCase();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant i) return i;
        if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
        return java.time.Instant.parse(value.toString());
    }
}
