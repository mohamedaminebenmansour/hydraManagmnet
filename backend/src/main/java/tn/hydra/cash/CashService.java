package tn.hydra.cash;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.document.domain.DocType;
import tn.hydra.document.dto.DocumentResponse;
import tn.hydra.document.service.DocumentService;
import tn.hydra.exception.BadRequestException;
import tn.hydra.purchase.PurchaseOrderService;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final DocumentService documentService;
    private final PurchaseOrderService purchaseOrders;

    public CashService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit,
                       DocumentService documentService, PurchaseOrderService purchaseOrders) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.documentService = documentService;
        this.purchaseOrders = purchaseOrders;
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
                                    UUID voidedBy, Instant voidedAt, String voidReason,
                                    BigDecimal claimedAmount, BigDecimal assessedAmount,
                                    String varianceReason, UUID documentId,
                                    // V26 enriched fields — Tunisian état de caisse (all nullable)
                                    String paymentMode, String paymentRef,
                                    String voucherNo, String nature, String invoiceRef,
                                    UUID bcId, UUID factureId) {}

    /**
     * Richer movement response — includes the human-readable names of the
     * related entities so the frontend doesn't have to N+1 query pots, party,
     * project, and the postedBy/voidedBy users.
     *
     * Backend-side JOINs: from_pot.name, to_pot.name, party.name, project.name,
     * postedByUser.full_name, voidedByUser.full_name.
     */
    public record MovementDetailResponse(
            UUID id, UUID orgId, String movementType,
            BigDecimal amount, String currency, LocalDate movementDate,
            UUID fromPotId, String fromPotName,
            UUID toPotId, String toPotName,
            UUID projectId, String projectName,
            UUID partyId, String partyName, String partyType,
            String label, String note, String status,
            UUID processInstanceId, UUID createdBy, Instant createdAt,
            UUID postedBy, String postedByName, Instant postedAt,
            UUID voidedBy, String voidedByName, Instant voidedAt, String voidReason,
            BigDecimal claimedAmount, BigDecimal assessedAmount, String varianceReason,
            UUID documentId, String documentName,
            // V26 enriched fields — Tunisian état de caisse (all nullable)
            String paymentMode, String paymentRef,
            String voucherNo, String nature, String invoiceRef,
            // V28 (TASK 6) — void request flag (finance asks, owner voids)
            Instant voidRequestedAt, String voidRequestReason,
            UUID bcId, String bcReference, UUID factureId, String factureReference,
            java.util.List<String> materialWarnings) {}

    /** One event in the movement timeline. */
    public record MovementTimelineEvent(
            String action,         // CASH_MOVEMENT_CREATED | SUBMITTED | POSTED | VOIDED | REJECTED
            Instant at,            // event_log.created_at
            UUID userId,           // event_log.user_id (nullable for system-triggered)
            String userName,       // app_user.full_name (nullable)
            String details,        // event_log.details (raw JSON string for the frontend to parse)
            String source           // 'cash_movement' | 'process_instance' | 'process_step'
    ) {}

    /** Full timeline view = movement snapshot + ordered events. */
    public record MovementTimeline(
            UUID movementId,
            String movementType,
            BigDecimal amount,
            String currency,
            BigDecimal claimedAmount,
            BigDecimal assessedAmount,
            String varianceReason,
            String status,
            String fromPotName,
            String toPotName,
            String partyName,
            String projectName,
            UUID processInstanceId,
            UUID documentId, String documentName,
            // V26 enriched fields — cheap to include in the snapshot since the
            // detail fetch already SELECTs them; lets the timeline surface
            // "paid by CHEQUE / voucher 0142 / nature SOUS_TRAITANCE" at a glance.
            String paymentMode, String paymentRef,
            String voucherNo, String nature, String invoiceRef,
            List<MovementTimelineEvent> events) {}

    /**
     * Create-movement request.
     * claimed/assessed (TASK 1): when assessedAmount is provided the amount
     * column is kept IN SYNC with it (amount = assessed — the caisse posts the
     * assessed amount). claimedAmount alone defaults assessed to the same value.
     * If claimed != assessed, varianceReason is REQUIRED (service-enforced +
     * DB check chk_cm_variance_reason).
     *
     * V26 enriched fields (Tunisian état de caisse — all optional):
     *   - paymentMode : ESPECE | CHEQUE | VIREMENT | VERSEMENT | MANDAT | AUTRE
     *   - paymentRef  : n° chèque / réf virement / réf mandat (free text)
     *   - voucherNo   : Bon N° (petty-cash voucher best practice)
     *   - nature      : ALIM_CAISSE | SOUS_TRAITANCE | MATERIEL | TRANSPORT |
     *                   MAIN_OEUVRE | LOCATION | LABO | CARBURANT | DIVERS
     *   - invoiceRef  : réf facture / situation du tiers (optional)
     * NULL-safe: any combination may be omitted. The service normalizes blanks
     * to null and rejects unknown enum codes with a French 400.
     */
    public record CreateMovementRequest(String movementType, BigDecimal amount,
                                         LocalDate movementDate, UUID fromPotId, UUID toPotId,
                                         UUID projectId, UUID partyId,
                                         String label, String note,
                                         UUID processInstanceId,
                                         BigDecimal claimedAmount, BigDecimal assessedAmount,
                                         String varianceReason,
                                         String paymentMode, String paymentRef,
                                         String voucherNo, String nature, String invoiceRef,
                                         UUID bcId, UUID factureId) {}

    /**
     * Update DRAFT movement. Same shape as create minus processInstanceId
     * (the workflow link is set by PaymentFacade only). V26 enriched fields
     * are accepted here so a DRAFT can be enriched after creation.
     */
    public record UpdateMovementRequest(String movementType, BigDecimal amount,
                                         LocalDate movementDate, UUID fromPotId, UUID toPotId,
                                         UUID projectId, UUID partyId,
                                         String label, String note,
                                         // V26 enriched fields — allowed on DRAFT update only
                                         String paymentMode, String paymentRef,
                                         String voucherNo, String nature, String invoiceRef,
                                         // TASK 1 — claimed/assessed/variance allowed on DRAFT update
                                         BigDecimal claimedAmount, BigDecimal assessedAmount,
                                         String varianceReason) {}

    public record PostResponse(UUID id, String status, BigDecimal fromPotBalanceAfter) {}

    public record VoidRequest(String reason) {}

    /** TASK 6 — body for POST /api/cash/movements/{id}/request-void. */
    public record VoidRequestRequest(String reason) {}

    /**
     * StatementLine — one row of an "état de caisse" (Excel-like statement).
     *
     * <p>Single source of truth for both {@code GET /pots/{id}/statement} and
     * {@code GET /movements/export} — same column set, same order, same field
     * names so the export API is literally the statement rows serialized as
     * CSV (or JSON). Designed to match the columns a Tunisian BTP caisse
     * paper sheet shows per line:
     * <ul>
     *   <li>{@code movementDate} — Date du mouvement</li>
     *   <li>{@code voucherNo}    — Bon N° (petty-cash voucher)</li>
     *   <li>{@code partyName}    — Tiers (sous-traitant / fournisseur)</li>
     *   <li>{@code nature}       — Nature (SOUS_TRAITANCE / MATERIEL / …)</li>
     *   <li>{@code label}        — Désignation (libellé du mouvement)</li>
     *   <li>{@code movementType} — Type (IN / OUT / TRANSFER)</li>
     *   <li>{@code paymentMode}  — Mode (ESPECE / CHEQUE / VIREMENT / …)</li>
     *   <li>{@code paymentRef}   — Réf. (n° chèque / virement / mandat)</li>
     *   <li>{@code invoiceRef}   — Facture (réf facture / situation)</li>
     *   <li>{@code amount}       — Montant</li>
     *   <li>{@code status}       — Statut (Brouillon / En attente / Comptabilisé / Annulé)</li>
     *   <li>{@code projectName} — Projet</li>
     *   <li>{@code processInstanceId} — Lien vers le workflow d'approbation (nullable)</li>
     * </ul>
     *
     * <p>POSTED rows are always included; DRAFT + PENDING (movement linked to
     * an in-progress PAYMENT_APPROVAL process) rows are included with their
     * status so the caisse can see "what's still pending" alongside what's
     * already posted. The frontend renders the status as a French chip so the
     * owner can tell at a glance.
     */
    public record StatementLine(
            UUID id,
            LocalDate movementDate,
            String voucherNo,
            UUID partyId,
            String partyName,
            String nature,
            String label,
            String movementType,
            String paymentMode,
            String paymentRef,
            String invoiceRef,
            BigDecimal amount,
            String currency,
            String status,
            UUID projectId,
            String projectName,
            UUID processInstanceId
    ) {}

    // ══════════════════════════════════════════════════════════════
    //  AUTH HELPERS — proper permission checks via PERM_* authorities
    //  (seeded in V22__cash_permissions.sql). Owner auto-gets all perms
    //  via HydraUserDetailsService.allPermissions().
    // ══════════════════════════════════════════════════════════════

    private AppUser currentUser(Authentication auth) {
        return users.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Unknown user: " + auth.getName()));
    }

    /** True if the caller holds the given PERM_ authority (or is_owner). */
    private boolean hasPermission(Authentication auth, String perm) {
        String authority = "PERM_" + perm;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    /** Throw 403 BusinessRuleException if caller lacks the perm. */
    private void requirePermission(Authentication auth, String perm, String action) {
        if (!hasPermission(auth, perm)) {
            throw new BusinessRuleException("FORBIDDEN",
                    "Permission manquante: PERM_" + perm + " — action: " + action);
        }
    }

    /**
     * Require: is_owner OR has PERM_CASH_DRAFT permission.
     * (Project membership is enforced separately via requireProjectAccess.)
     */
    private void requireCanWrite(Authentication auth, AppUser me, UUID projectId, String action) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        requirePermission(auth, "CASH_DRAFT", action);
    }

    /**
     * Require: is_owner OR has PERM_CASH_POST for POST, PERM_CASH_VOID for VOID.
     * The caller disambiguates which perm to check (POST vs VOID are different actions).
     */
    private void requireCanPostOrVoid(Authentication auth, AppUser me, String perm) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        requirePermission(auth, perm, "post-or-void");
    }

    /** Project member check — for project-scoped reads + writes on PROJECT pots. */
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
        requireCanWrite(auth, me, null, "ensure-company-pot");

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

        // ── Claimed vs assessed normalization (TASK 1) ─────────────────
        // amount is the posting source of truth and stays in sync with
        // assessed_amount: the caisse always posts the ASSESSED amount.
        BigDecimal claimed = req.claimedAmount();
        BigDecimal assessed = req.assessedAmount();
        String varianceReason = blankToNull(req.varianceReason());
        if (claimed != null && assessed == null) {
            assessed = claimed;               // claimed only → no variance
        }
        BigDecimal effectiveAmount = assessed != null ? assessed : req.amount();

        validateMovementInput(req.movementType(), effectiveAmount, req.movementDate(),
                req.fromPotId(), req.toPotId());
        validateClaimedAssessed(claimed, assessed, varianceReason);
        // V26 enriched fields — normalize + validate enums (returns null for blank)
        String paymentMode = validatePaymentMode(req.paymentMode());
        String nature      = validateNature(req.nature());
        String paymentRef  = blankToNull(req.paymentRef());
        String voucherNo   = blankToNull(req.voucherNo());
        String invoiceRef  = blankToNull(req.invoiceRef());

        // Resolve project_id: from request, or from PROJECT pot (when from/to is project pot)
        UUID resolvedProjectId = req.projectId();
        if (resolvedProjectId == null) {
            resolvedProjectId = lookupProjectFromPot(req.fromPotId(), me.getOrgId());
            if (resolvedProjectId == null) {
                resolvedProjectId = lookupProjectFromPot(req.toPotId(), me.getOrgId());
            }
        }

        requireCanWrite(auth, me, resolvedProjectId, "create-movement");

        // Validate pot ownership (must be in caller's org)
        validatePotInOrg(req.fromPotId(), me.getOrgId());
        validatePotInOrg(req.toPotId(), me.getOrgId());
        if (resolvedProjectId != null) {
            requireProjectAccess(me, resolvedProjectId);
        }

        // Part 4.1 — IDOR validation for commercial links
        if (req.bcId() != null) {
            validateCommercialLink(req.bcId(), me.getOrgId(), resolvedProjectId,
                    "purchase_order", "PAYMENT_BC_PROJECT_MISMATCH");
        }
        if (req.factureId() != null) {
            validateCommercialLink(req.factureId(), me.getOrgId(), resolvedProjectId,
                    "facture", "PAYMENT_FACTURE_PROJECT_MISMATCH");
        }

        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into cash_movement
                  (id, org_id, movement_type, amount, currency, movement_date,
                   from_pot_id, to_pot_id, project_id, party_id, label, note,
                   claimed_amount, assessed_amount, variance_reason,
                   payment_mode, payment_ref, voucher_no, nature, invoice_ref,
                   bc_id, facture_id,
                   status, process_instance_id, created_by, created_at)
                values (?, ?, ?, ?, 'TND', ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?,
                        ?, ?, ?, ?, ?,
                        ?, ?,
                        'DRAFT', ?, ?, now())
                """, id, me.getOrgId(), normalizeType(req.movementType()), effectiveAmount,
                Date.valueOf(req.movementDate()),
                req.fromPotId(), req.toPotId(), resolvedProjectId, req.partyId(),
                blankToNull(req.label()), blankToNull(req.note()),
                claimed, assessed, varianceReason,
                paymentMode, paymentRef, voucherNo, nature, invoiceRef,
                req.bcId(), req.factureId(),
                req.processInstanceId(),
                me.getId());

        audit.log("CASH_MOVEMENT_CREATED", "cash_movement", id,
                "{\"type\":\"" + normalizeType(req.movementType()) +
                "\",\"amount\":" + effectiveAmount +
                (claimed != null ? ",\"claimedAmount\":" + claimed : "") +
                (assessed != null ? ",\"assessedAmount\":" + assessed : "") +
                (varianceReason != null ? ",\"varianceReason\":\"" + jsonEscape(varianceReason) + "\"" : "") +
                (paymentMode != null ? ",\"paymentMode\":\"" + paymentMode + "\"" : "") +
                (nature      != null ? ",\"nature\":\"" + nature + "\"" : "") +
                (voucherNo   != null ? ",\"voucherNo\":\"" + jsonEscape(voucherNo) + "\"" : "") +
                ",\"status\":\"DRAFT\"}");

        return getMovement(auth, id);
    }

    /** Update a DRAFT movement only. POSTED/VOID → BusinessRuleException. */
    @Transactional
    public MovementResponse updateMovement(Authentication auth, UUID id, UpdateMovementRequest req) {
        AppUser me = currentUser(auth);
        Map<String, Object> row = fetchMovementForUpdate(id, me.getOrgId());
        String currentStatus = (String) row.get("status");
        // TASK 1 hardening: POSTED is immutable; VOID is dead. Both reject with
        // a precise French error code so the frontend can show the right message.
        if ("POSTED".equals(currentStatus)) {
            throw new BusinessRuleException("POSTED_IMMUTABLE",
                    "Un mouvement comptabilisé (POSTED) ne peut pas être modifié. " +
                    "Annulez-le (VOID) puis créez un nouveau mouvement.");
        }
        if ("VOID".equals(currentStatus)) {
            throw new BusinessRuleException("MOVEMENT_VOID",
                    "Un mouvement annulé (VOID) ne peut pas être modifié. " +
                    "Créez un nouveau mouvement.");
        }
        if (!"DRAFT".equals(currentStatus)) {
            throw new BusinessRuleException("MOVEMENT_NOT_DRAFT",
                    "Seul un mouvement DRAFT peut être modifié (statut actuel: " + currentStatus + ")");
        }

        UUID projectId = (UUID) row.get("project_id");
        requireCanWrite(auth, me, projectId, "update-movement");

        validateMovementInput(req.movementType(), req.amount(), req.movementDate(),
                req.fromPotId(), req.toPotId());
        validatePotInOrg(req.fromPotId(), me.getOrgId());
        validatePotInOrg(req.toPotId(), me.getOrgId());

        UUID resolvedProjectId = req.projectId() != null ? req.projectId() : projectId;
        if (resolvedProjectId != null) requireProjectAccess(me, resolvedProjectId);

        // V26 enriched fields — only DRAFT accepts updates (already checked above).
        // paymentMode / nature: validate via enum (reject unknown codes with French 400).
        String paymentMode = validatePaymentMode(req.paymentMode());
        String nature      = validateNature(req.nature());
        String paymentRef  = blankToNull(req.paymentRef());
        String voucherNo   = blankToNull(req.voucherNo());
        String invoiceRef  = blankToNull(req.invoiceRef());

        // TASK 1 — claimed/assessed/variance allowed on DRAFT update (so the
        // caisse can correct a wrong facture amount before posting). Same
        // normalization + validation as createMovement.
        BigDecimal claimed = req.claimedAmount();
        BigDecimal assessed = req.assessedAmount();
        String varianceReason = blankToNull(req.varianceReason());
        if (claimed != null && assessed == null) {
            assessed = claimed;
        }
        BigDecimal effectiveAmount = assessed != null ? assessed : req.amount();
        validateClaimedAssessed(claimed, assessed, varianceReason);
        // Re-run validateMovementInput with the effective amount so OUT/IN/TRANSFER
        // pot rules + positivity still hold after the claimed/assessed normalization.
        validateMovementInput(req.movementType(), effectiveAmount, req.movementDate(),
                req.fromPotId(), req.toPotId());

        jdbc.update("""
                update cash_movement set
                  movement_type = ?, amount = ?, movement_date = ?,
                  from_pot_id = ?, to_pot_id = ?, project_id = ?, party_id = ?,
                  label = ?, note = ?,
                  claimed_amount = ?, assessed_amount = ?, variance_reason = ?,
                  payment_mode = ?, payment_ref = ?, voucher_no = ?,
                  nature = ?, invoice_ref = ?
                where id = ? and org_id = ?
                """,
                normalizeType(req.movementType()), effectiveAmount, Date.valueOf(req.movementDate()),
                req.fromPotId(), req.toPotId(), resolvedProjectId, req.partyId(),
                blankToNull(req.label()), blankToNull(req.note()),
                claimed, assessed, varianceReason,
                paymentMode, paymentRef, voucherNo, nature, invoiceRef,
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
     * Richer GET — single movement with pot names, party name, project name,
     * postedBy name, voidedBy name. One JOINed query, no client-side N+1.
     */
    public MovementDetailResponse getMovementDetail(Authentication auth, UUID id) {
        AppUser me = currentUser(auth);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select m.id, m.org_id, m.movement_type, m.amount, m.currency, m.movement_date,
                       m.from_pot_id, fp.name as from_pot_name,
                       m.to_pot_id,   tp.name as to_pot_name,
                       m.project_id,  p.name as project_name,
                       m.party_id,    py.name as party_name, py.type as party_type,
                       m.label, m.note, m.status, m.process_instance_id,
                       m.claimed_amount, m.assessed_amount, m.variance_reason,
                       m.document_id, drv.file_name as document_name,
                       m.created_by,  m.created_at,
                       m.posted_by,   pbu.full_name as posted_by_name,  m.posted_at,
                       m.voided_by,   vu.full_name as voided_by_name,   m.voided_at,
                       m.void_reason, m.void_requested_at, m.void_request_reason,
                       m.payment_mode, m.payment_ref, m.voucher_no, m.nature, m.invoice_ref,
                       m.bc_id, bc.reference as bc_reference,
                       m.facture_id, fac.reference as facture_reference
                from cash_movement m
                left join cash_pot fp  on fp.id = m.from_pot_id
                left join cash_pot tp  on tp.id = m.to_pot_id
                left join project  p   on p.id  = m.project_id
                left join party    py  on py.id  = m.party_id
                left join app_user pbu on pbu.id = m.posted_by
                left join app_user vu  on vu.id  = m.voided_by
                left join document d   on d.id  = m.document_id
                left join lateral (
                    select r.file_name from document_revision r
                    where r.document_id = d.id
                    order by r.created_at desc
                    limit 1
                ) drv on m.document_id is not null
                left join purchase_order bc on bc.id = m.bc_id
                left join facture fac on fac.id = m.facture_id
                where m.id = ? and m.org_id = ?
                """, id, me.getOrgId());
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("MOVEMENT_NOT_FOUND", "Mouvement introuvable");
        }
        Map<String, Object> row = rows.get(0);
        UUID projectId = (UUID) row.get("project_id");
        if (projectId != null) requireProjectAccess(me, projectId);
        // Part 4.2 — compute material warnings if bc_id is set
        java.util.List<String> materialWarnings = java.util.List.of();
        UUID bcId = (UUID) row.get("bc_id");
        if (bcId != null) {
            materialWarnings = purchaseOrders.computeMaterialWarnings(auth, bcId);
        }
        return toMovementDetailResponse(row, materialWarnings);
    }

    /**
     * Movement timeline — ordered audit events for this movement.
     *
     * TASK 2 — "Merge event_log + process step completions ordered by time."
     *
     * Three event sources are UNIONED (all on event_log, joined to app_user
     * for the actor name, ordered by created_at asc):
     *
     *   (1) Direct cash_movement events — CASH_MOVEMENT_CREATED,
     *       CASH_MOVEMENT_SUBMITTED, CASH_MOVEMENT_POSTED, CASH_MOVEMENT_VOIDED
     *       (entity_type='cash_movement', entity_id=movementId).
     *
     *   (2) Process-instance lifecycle events for the PAYMENT_APPROVAL workflow
     *       whose subject is this movement — PROCESS_STARTED, PROCESS_COMPLETED,
     *       PROCESS_REJECTED, PROCESS_CANCELLED
     *       (entity_type='process_instance', pi.subject_type='CASH_MOVEMENT',
     *        pi.subject_id=movementId).
     *
     *   (3) Process-step events for every step of that workflow — STEP_STARTED,
     *       STEP_COMPLETED, STEP_REJECTED — so the owner can see exactly who
     *       approved what and when (entity_type='process_step_instance', joined
     *       through process_step_instance → process_instance → subject_id).
     *
     * All three queries resolve actor name in the same JOIN, so the merge is
     * a single SQL round-trip with zero client-side N+1.
     */
    public MovementTimeline getMovementTimeline(Authentication auth, UUID movementId) {
        AppUser me = currentUser(auth);
        // Validate movement exists + caller has access (reuses fetchMovementForRead)
        Map<String, Object> movement = fetchMovementForRead(movementId, me.getOrgId());
        UUID projectId = (UUID) movement.get("project_id");
        if (projectId != null) requireProjectAccess(me, projectId);

        // Fetch the snapshot fields (pot names, party name, project name)
        MovementDetailResponse snapshot = getMovementDetail(auth, movementId);

        // Merge: direct movement events + whole-process events + step events.
        // Each branch carries its own "source" tag so the UI can render
        // cash vs process rows distinctly if desired.
        List<Map<String, Object>> eventRows = jdbc.queryForList("""
                -- (1) Direct cash_movement events
                select el.action, el.created_at, el.user_id, u.full_name, el.details,
                       'cash_movement' as source
                from event_log el
                left join app_user u on u.id = el.user_id
                where el.entity_type = 'cash_movement' and el.entity_id = ?

                union all

                -- (2) Whole-process lifecycle events for the linked workflow
                select el.action, el.created_at, el.user_id, u.full_name, el.details,
                       'process_instance' as source
                from event_log el
                left join app_user u on u.id = el.user_id
                join process_instance pi on pi.id = el.entity_id
                where el.entity_type = 'process_instance'
                  and pi.subject_type = 'CASH_MOVEMENT'
                  and pi.subject_id = ?

                union all

                -- (3) Process-step events (STEP_STARTED / STEP_COMPLETED / STEP_REJECTED)
                -- joined through process_step_instance (instance_id) → process_instance (subject)
                select el.action, el.created_at, el.user_id, u.full_name, el.details,
                       'process_step' as source
                from event_log el
                left join app_user u on u.id = el.user_id
                join process_step_instance psi on psi.id = el.entity_id
                join process_instance pi on pi.id = psi.instance_id
                where el.entity_type = 'process_step_instance'
                  and pi.subject_type = 'CASH_MOVEMENT'
                  and pi.subject_id = ?

                order by created_at asc
                """, movementId, movementId, movementId);

        List<MovementTimelineEvent> events = eventRows.stream()
                .map(r -> new MovementTimelineEvent(
                        (String) r.get("action"),
                        toInstant(r.get("created_at")),
                        (UUID) r.get("user_id"),
                        (String) r.get("full_name"),
                        r.get("details") == null ? null : r.get("details").toString(),
                        (String) r.get("source")
                ))
                .toList();

        return new MovementTimeline(
                movementId,
                snapshot.movementType(),
                snapshot.amount(),
                snapshot.currency(),
                snapshot.claimedAmount(),
                snapshot.assessedAmount(),
                snapshot.varianceReason(),
                snapshot.status(),
                snapshot.fromPotName(),
                snapshot.toPotName(),
                snapshot.partyName(),
                snapshot.projectName(),
                snapshot.processInstanceId(),
                snapshot.documentId(),
                snapshot.documentName(),
                // V26 enriched fields (snapshot from getMovementDetail above)
                snapshot.paymentMode(),
                snapshot.paymentRef(),
                snapshot.voucherNo(),
                snapshot.nature(),
                snapshot.invoiceRef(),
                events
        );
    }

    // ══════════════════════════════════════════════════════════════
    //  ATTACHMENT (TASK 3) — payment proof → Document in Finance folder
    // ══════════════════════════════════════════════════════════════

    /**
     * Attach a payment proof (facture / BL / situation) to a movement.
     *
     * TASK 3 rules:
     *   - the movement must still be DRAFT. Our ledger statuses are
     *     DRAFT / POSTED / VOID: a payment waiting for approval is a DRAFT with
     *     a RUNNING process (there is no separate PENDING status — "PENDING" in
     *     the spec maps to exactly that DRAFT+RUNNING state). POSTED is
     *     immutable and VOID is dead → 409, never attach.
     *   - the file MUST land in the project's Finance folder (06_FIN) as a real
     *     Document via {@link DocumentService#upload} — never a storage-only
     *     orphan. The folder is ensured first (created if the project somehow
     *     lacks it, reusing the V8/V9 template convention).
     *   - docType restricted to payment-proof values: FACTURE (default),
     *     SITUATION, BON_LIVRAISON, AUTRE.
     *   - ACL is exactly the folder upload rules: DocumentService.upload
     *     enforces folderAccess(UPLOAD) (06_FIN matrix: owner / project admin /
     *     explicit grants) + project can_upload — same as any upload into the
     *     Finance folder through the documents API.
     *   - movement.document_id is set AND the V20 junction
     *     cash_movement_document is kept in sync (future multi-proof support).
     *   - audit DOCUMENT_ATTACHED_TO_MOVEMENT with document id + docType.
     */
    @Transactional
    public MovementDetailResponse attachDocument(Authentication auth, UUID movementId,
                                                 MultipartFile file, String title, String docType) {
        AppUser me = currentUser(auth);

        // Lock the row: prevents a concurrent post/void between the guard and the link.
        Map<String, Object> row = fetchMovementForUpdate(movementId, me.getOrgId());
        String status = (String) row.get("status");
        if (!"DRAFT".equals(status)) {
            throw new BusinessRuleException("MOVEMENT_NOT_EDITABLE",
                    "Impossible de joindre une pièce à un mouvement " + status
                            + " — seul un mouvement en brouillon (DRAFT) est modifiable");
        }
        UUID projectId = (UUID) row.get("project_id");
        if (projectId == null) {
            throw new BadRequestException("PROJECT_REQUIRED",
                    "Le mouvement doit être rattaché à un projet pour y joindre une pièce");
        }
        requireProjectAccess(me, projectId);
        requireCanWrite(auth, me, projectId, "attach-movement-document");

        if (title == null || title.isBlank()) {
            throw new BadRequestException("REQUIRED_FIELDS", "Le titre de la pièce est obligatoire");
        }

        // Ensure the project's Finance folder (06_FIN) exists — never orphan files.
        UUID financeFolderId = ensureFinanceFolder(projectId);

        DocType type = parseAttachmentDocType(docType);

        // Real Document in the Finance folder — DocumentService.upload enforces
        // the folder upload ACL (folderAccess UPLOAD + project can_upload) and
        // stores Document + DocumentRevision atomically.
        DocumentResponse doc = documentService.upload(auth, financeFolderId, title.trim(),
                type, null, null, null, null, file);

        // Link on the movement (single FK per TASK 3) + keep the V20 junction in sync.
        jdbc.update("update cash_movement set document_id = ? where id = ?", doc.id(), movementId);
        jdbc.update("""
                insert into cash_movement_document (movement_id, document_id)
                values (?, ?)
                on conflict (movement_id, document_id) do nothing
                """, movementId, doc.id());

        audit.log("DOCUMENT_ATTACHED_TO_MOVEMENT", "cash_movement", movementId,
                "{\"documentId\":\"" + doc.id() + "\"" +
                ",\"reference\":\"" + jsonEscape(doc.reference()) + "\"" +
                ",\"docType\":\"" + type.name() + "\"" +
                ",\"title\":\"" + jsonEscape(title.trim()) + "\"" +
                ",\"amount\":" + row.get("amount") + "}");

        return getMovementDetail(auth, movementId);
    }

    /**
     * Return the project's Finance folder id, creating it if missing.
     * Preference order: exact template code '06_FIN' → any '06%' code → any
     * folder literally named 'Finance'. If none exist (very old projects), one
     * is created with the template convention (code 06_FIN, name Finance,
     * sort_order 6) so the payment proof always has a home.
     */
    private UUID ensureFinanceFolder(UUID projectId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id from doc_folder
                where project_id = ?
                  and (code = '06_FIN' or code ilike '06%' or lower(name) = 'finance')
                order by (code = '06_FIN') desc, sort_order asc, created_at asc
                limit 1
                """, projectId);
        if (!rows.isEmpty()) {
            return (UUID) rows.get(0).get("id");
        }
        // Safety net: create the Finance folder with the V8/V9 template convention.
        jdbc.queryForObject("""
                insert into doc_folder (project_id, code, name, sort_order)
                values (?, '06_FIN', 'Finance', 6)
                returning id
                """, UUID.class, projectId);
        audit.log("FINANCE_FOLDER_CREATED", "project", projectId,
                "{\"code\":\"06_FIN\",\"name\":\"Finance\"}");
        return jdbc.queryForObject("""
                select id from doc_folder
                where project_id = ? and code = '06_FIN'
                order by created_at desc limit 1
                """, UUID.class, projectId);
    }

    /** Payment-proof docTypes only. Default FACTURE. Unknown → clear French 400. */
    private static DocType parseAttachmentDocType(String raw) {
        if (raw == null || raw.isBlank()) return DocType.FACTURE;
        String t = raw.trim().toUpperCase().replace('-', '_');
        return switch (t) {
            case "FACTURE" -> DocType.FACTURE;
            case "SITUATION" -> DocType.SITUATION;
            case "BON_LIVRAISON", "BL" -> DocType.BON_LIVRAISON;
            case "AUTRE", "OTHER" -> DocType.AUTRE;
            default -> throw new BadRequestException("INVALID_DOC_TYPE",
                    "Type de pièce invalide: " + raw + " — valeurs acceptées : FACTURE, SITUATION, BON_LIVRAISON, AUTRE");
        };
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
                       voided_by, voided_at, void_reason,
                       claimed_amount, assessed_amount, variance_reason, document_id,
                       payment_mode, payment_ref, voucher_no, nature, invoice_ref
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

        // Filter out movements on PROJECT pots the caller can't access (non-owner).
        // Membership is resolved with ONE query up front — never per row (no N+1).
        Set<UUID> accessible = accessibleProjectIds(me);
        return jdbc.queryForList(sql.toString(), args.toArray())
                .stream()
                .map(CashService::toMovementResponse)
                .filter(m -> m.projectId() == null || accessible.contains(m.projectId()))
                .toList();
    }

    /**
     * Project ids the caller may see. Owner → all project ids of the org
     * (also one query). Non-owner → projects they are a member of.
     * Called ONCE per list request so the row filter stays O(1) per row.
     */
    private Set<UUID> accessibleProjectIds(AppUser me) {
        if (Boolean.TRUE.equals(me.getIsOwner())) {
            return new HashSet<>(jdbc.queryForList(
                    "select id from project where org_id = ?",
                    UUID.class, me.getOrgId()));
        }
        return new HashSet<>(jdbc.queryForList(
                "select project_id from project_member where user_id = ?",
                UUID.class, me.getId()));
    }

    /**
     * Richer list — same filters as listMovements, but returns MovementDetailResponse
     * with JOIN'd names. The frontend typically wants this version to avoid N+1
     * fetches per row.
     */
    public List<MovementDetailResponse> listMovementsDetail(
            Authentication auth, UUID potId, UUID partyId, UUID projectId,
            String status, LocalDate from, LocalDate to) {

        AppUser me = currentUser(auth);
        if (projectId != null) requireProjectAccess(me, projectId);

        StringBuilder sql = new StringBuilder("""
                select m.id, m.org_id, m.movement_type, m.amount, m.currency, m.movement_date,
                       m.from_pot_id, fp.name as from_pot_name,
                       m.to_pot_id,   tp.name as to_pot_name,
                       m.project_id,  p.name as project_name,
                       m.party_id,    py.name as party_name, py.type as party_type,
                       m.label, m.note, m.status, m.process_instance_id,
                       m.claimed_amount, m.assessed_amount, m.variance_reason,
                       m.document_id, d.title as document_name,
                       m.created_by,  m.created_at,
                       m.posted_by,   pbu.full_name as posted_by_name,  m.posted_at,
                       m.voided_by,   vu.full_name as voided_by_name,   m.voided_at,
                       m.void_reason, m.void_requested_at, m.void_request_reason,
                       m.payment_mode, m.payment_ref, m.voucher_no, m.nature, m.invoice_ref,
                       m.bc_id, bc.reference as bc_reference,
                       m.facture_id, fac.reference as facture_reference
                from cash_movement m
                left join cash_pot fp  on fp.id = m.from_pot_id
                left join cash_pot tp  on tp.id = m.to_pot_id
                left join project  p   on p.id  = m.project_id
                left join party    py  on py.id  = m.party_id
                left join app_user pbu on pbu.id = m.posted_by
                left join app_user vu  on vu.id  = m.voided_by
                left join document d   on d.id  = m.document_id
                left join purchase_order bc on bc.id = m.bc_id
                left join facture fac on fac.id = m.facture_id
                where m.org_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(me.getOrgId());

        if (potId != null) {
            sql.append(" and (m.from_pot_id = ? or m.to_pot_id = ?)");
            args.add(potId); args.add(potId);
        }
        if (partyId != null) {
            sql.append(" and m.party_id = ?");
            args.add(partyId);
        }
        if (projectId != null) {
            sql.append(" and m.project_id = ?");
            args.add(projectId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and m.status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (from != null) {
            sql.append(" and m.movement_date >= ?");
            args.add(Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" and m.movement_date <= ?");
            args.add(Date.valueOf(to));
        }
        sql.append(" order by m.movement_date desc, m.created_at desc");

        // Same no-N+1 project filter as listMovements — one membership query, then in-memory.
        Set<UUID> accessible = accessibleProjectIds(me);
        return jdbc.queryForList(sql.toString(), args.toArray())
                .stream()
                .map(row -> toMovementDetailResponse(row, java.util.List.of()))
                .filter(m -> m.projectId() == null || accessible.contains(m.projectId()))
                .toList();
    }

    // ══════════════════════════════════════════════════════════════
    //  ÉTAT DE CAISSE — statement + export (TASK 2)
    //  Single JOINed query; same column set for both statement + export.
    //  POSTED always included; DRAFT + (PENDING = DRAFT with a linked in-progress
    //  PAYMENT_APPROVAL process instance) included with their status flag.
    //  No N+1: pot names / party / project resolved in the same SELECT.
    // ══════════════════════════════════════════════════════════════

    /**
     * GET /api/cash/pots/{potId}/statement?from=&to=&projectId=
     *
     * <p>Returns the "état de caisse" for a single pot — ordered lines that
     * match what an Excel caisse sheet shows. POSTED rows always; DRAFT +
     * PENDING (DRAFT with linked in-progress process) included with their
     * status so the caisse sees pending lines alongside posted ones.
     *
     * <p>Access: caller must be able to read the pot (owner OR PERM_CASH_READ
     * + project membership if PROJECT pot). Reuses {@link #getPot} for the
     * access check so it stays consistent with the rest of the cash domain.
     */
    public List<StatementLine> getPotStatement(Authentication auth, UUID potId,
                                                LocalDate from, LocalDate to, UUID projectId) {
        AppUser me = currentUser(auth);
        // getPot validates: pot in caller's org + project access if PROJECT pot
        getPot(auth, potId);

        List<Object> args = new ArrayList<>();
        args.add(me.getOrgId());
        StringBuilder sql = new StringBuilder("""
                select m.id, m.movement_date, m.voucher_no,
                       m.party_id,    py.name  as party_name,
                       m.nature, m.label, m.movement_type,
                       m.payment_mode, m.payment_ref, m.invoice_ref,
                       m.amount, m.currency, m.status,
                       m.project_id,  p.name   as project_name,
                       m.process_instance_id
                from cash_movement m
                left join party   py on py.id  = m.party_id
                left join project p  on p.id   = m.project_id
                where m.org_id = ?
                  and (m.from_pot_id = ? or m.to_pot_id = ?)
                """);
        args.add(potId);
        args.add(potId);

        if (projectId != null) {
            sql.append(" and m.project_id = ?");
            args.add(projectId);
        }
        if (from != null) {
            sql.append(" and m.movement_date >= ?");
            args.add(Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" and m.movement_date <= ?");
            args.add(Date.valueOf(to));
        }
        // Order: chronological by movement_date, then by created_at (stable
        // tie-break so multiple lines on the same date keep their entry order).
        sql.append(" order by m.movement_date asc, m.created_at asc");

        // Filter out movements on PROJECT pots the caller can't access (one
        // membership query up front — same no-N+1 pattern as listMovements).
        Set<UUID> accessible = accessibleProjectIds(me);
        return jdbc.queryForList(sql.toString(), args.toArray())
                .stream()
                .map(CashService::toStatementLine)
                .filter(s -> s.projectId() == null || accessible.contains(s.projectId()))
                .toList();
    }

    /**
     * GET /api/cash/movements/export?from=&to=&potId=&projectId=&status=
     *
     * <p>Same shape + ordering as {@link #getPotStatement} but org-wide
     * (potId optional). Designed for the monthly cabinet/comptable export —
     * the controller serializes the rows as JSON (default) or CSV (when the
     * caller sends {@code Accept: text/csv}).
     *
     * <p>{@code status} filter accepts the raw codes: DRAFT, POSTED, VOID.
     * The frontend maps PENDING → filter on (status=DRAFT + process_instance_id
     * not null) client-side; we don't add a PENDING pseudo-status server-side
     * because it would diverge from the DB column vocabulary.
     */
    public List<StatementLine> exportMovements(Authentication auth,
                                                LocalDate from, LocalDate to,
                                                UUID potId, UUID projectId, String status) {
        AppUser me = currentUser(auth);
        if (projectId != null) requireProjectAccess(me, projectId);

        List<Object> args = new ArrayList<>();
        args.add(me.getOrgId());
        StringBuilder sql = new StringBuilder("""
                select m.id, m.movement_date, m.voucher_no,
                       m.party_id,    py.name  as party_name,
                       m.nature, m.label, m.movement_type,
                       m.payment_mode, m.payment_ref, m.invoice_ref,
                       m.amount, m.currency, m.status,
                       m.project_id,  p.name   as project_name,
                       m.process_instance_id
                from cash_movement m
                left join party   py on py.id  = m.party_id
                left join project p  on p.id   = m.project_id
                where m.org_id = ?
                """);

        if (potId != null) {
            sql.append(" and (m.from_pot_id = ? or m.to_pot_id = ?)");
            args.add(potId); args.add(potId);
        }
        if (projectId != null) {
            sql.append(" and m.project_id = ?");
            args.add(projectId);
        }
        if (status != null && !status.isBlank()) {
            sql.append(" and m.status = ?");
            args.add(status.trim().toUpperCase());
        }
        if (from != null) {
            sql.append(" and m.movement_date >= ?");
            args.add(Date.valueOf(from));
        }
        if (to != null) {
            sql.append(" and m.movement_date <= ?");
            args.add(Date.valueOf(to));
        }
        sql.append(" order by m.movement_date asc, m.created_at asc");

        Set<UUID> accessible = accessibleProjectIds(me);
        return jdbc.queryForList(sql.toString(), args.toArray())
                .stream()
                .map(CashService::toStatementLine)
                .filter(s -> s.projectId() == null || accessible.contains(s.projectId()))
                .toList();
    }

    /** Mapper for StatementLine — joins resolved as columns in the SELECT. */
    private static StatementLine toStatementLine(Map<String, Object> row) {
        Object dateObj = row.get("movement_date");
        LocalDate movementDate = dateObj == null ? null
                : (dateObj instanceof Date d ? d.toLocalDate()
                    : LocalDate.parse(dateObj.toString()));
        return new StatementLine(
                (UUID) row.get("id"),
                movementDate,
                (String) row.get("voucher_no"),
                (UUID) row.get("party_id"),
                (String) row.get("party_name"),
                (String) row.get("nature"),
                (String) row.get("label"),
                (String) row.get("movement_type"),
                (String) row.get("payment_mode"),
                (String) row.get("payment_ref"),
                (String) row.get("invoice_ref"),
                (BigDecimal) row.get("amount"),
                (String) row.get("currency"),
                (String) row.get("status"),
                (UUID) row.get("project_id"),
                (String) row.get("project_name"),
                (UUID) row.get("process_instance_id")
        );
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
        requireCanPostOrVoid(auth, me, "CASH_POST");

        Map<String, Object> row = fetchMovementForUpdate(id, me.getOrgId());
        String status = (String) row.get("status");
        // TASK 1 hardening: can't post a POSTED (already done) or VOID (dead).
        if ("POSTED".equals(status)) {
            throw new BusinessRuleException("POSTED_IMMUTABLE",
                    "Ce mouvement est déjà comptabilisé (POSTED). Il ne peut pas être re-posté.");
        }
        if ("VOID".equals(status)) {
            throw new BusinessRuleException("MOVEMENT_VOID",
                    "Un mouvement annulé (VOID) ne peut pas être comptabilisé. " +
                    "Créez un nouveau mouvement.");
        }
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
        BigDecimal claimed = (BigDecimal) row.get("claimed_amount");
        BigDecimal assessedAmt = (BigDecimal) row.get("assessed_amount");

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
                (claimed != null ? ",\"claimedAmount\":" + claimed : "") +
                (assessedAmt != null ? ",\"assessedAmount\":" + assessedAmt : "") +
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
        requireCanPostOrVoid(auth, me, "CASH_VOID");

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

        // Enriched audit (TASK 1 hardening) — include amount, movementType, pot,
        // and partyId so the timeline answers "what was voided, for how much,
        // from which caisse, to whom" without re-querying the movement.
        BigDecimal voidedAmount = (BigDecimal) row.get("amount");
        String voidedType = (String) row.get("movement_type");
        UUID fromPotId = (UUID) row.get("from_pot_id");
        UUID toPotId = (UUID) row.get("to_pot_id");
        UUID partyId = (UUID) row.get("party_id");
        UUID projectId = (UUID) row.get("project_id");
        audit.log("CASH_MOVEMENT_VOIDED", "cash_movement", id,
                "{\"reason\":\"" + jsonEscape(req.reason().trim()) + "\"" +
                ",\"voidedBy\":\"" + me.getId() + "\"" +
                ",\"amount\":" + voidedAmount +
                ",\"movementType\":\"" + voidedType + "\"" +
                (fromPotId != null ? ",\"fromPotId\":\"" + fromPotId + "\"" : "") +
                (toPotId != null ? ",\"toPotId\":\"" + toPotId + "\"" : "") +
                (partyId != null ? ",\"partyId\":\"" + partyId + "\"" : "") +
                (projectId != null ? ",\"projectId\":\"" + projectId + "\"" : "") +
                "}");

        return getMovement(auth, id);
    }

    // ══════════════════════════════════════════════════════════════
    //  TASK 6 — REQUEST VOID (finance asks, owner voids)
    // ══════════════════════════════════════════════════════════════

    /**
     * POST /api/cash/movements/{id}/request-void
     *
     * <p>Finance (who can POST but NOT VOID) can request a void. This:
     * <ul>
     *   <li>Sets void_requested_at + void_request_reason on the movement;</li>
     *   <li>Audits CASH_VOID_REQUESTED with the reason + who requested;</li>
     *   <li>Does NOT void the movement — the owner still calls the existing
     *       void endpoint with their own reason.</li>
     * </ul>
     *
     * <p>Only works on POSTED movements (can't request void on a DRAFT or
     * already-VOID movement).
     *
     * <p>Permission: anyone with CASH_DRAFT can request (finance has it).
     * The owner sees the request on the movement detail and decides.
     */
    @Transactional
    public MovementResponse requestVoid(Authentication auth, UUID id, VoidRequestRequest req) {
        AppUser me = currentUser(auth);
        if (req == null || req.reason() == null || req.reason().isBlank()) {
            throw new BadRequestException("REASON_REQUIRED",
                    "Un motif de demande d'annulation est obligatoire");
        }

        Map<String, Object> row = fetchMovementForUpdate(id, me.getOrgId());
        String status = (String) row.get("status");
        if (!"POSTED".equals(status)) {
            throw new BusinessRuleException("MOVEMENT_NOT_POSTED",
                    "Une demande d'annulation ne peut être faite que sur un mouvement POSTED. Statut actuel: " + status);
        }

        // Already requested? Allow re-request (updates the reason + timestamp).
        jdbc.update("""
                update cash_movement set
                  void_requested_at = now(),
                  void_request_reason = ?
                where id = ? and org_id = ?
                """, req.reason().trim(), id, me.getOrgId());

        audit.log("CASH_VOID_REQUESTED", "cash_movement", id,
                "{\"reason\":\"" + jsonEscape(req.reason().trim()) + "\"" +
                ",\"requestedBy\":\"" + me.getId() + "\"" +
                ",\"movementStatus\":\"POSTED\"}");

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
        BigDecimal claimed = (BigDecimal) row.get("claimed_amount");
        BigDecimal assessedAmt = (BigDecimal) row.get("assessed_amount");

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
                (claimed != null ? ",\"claimedAmount\":" + claimed : "") +
                (assessedAmt != null ? ",\"assessedAmount\":" + assessedAmt : "") +
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

    /**
     * Claimed vs assessed rules (TASK 1):
     *   - assessed, when present, must be strictly positive;
     *   - claimed, when present, must be strictly positive;
     *   - claimed present AND assessed != claimed  →  variance_reason REQUIRED.
     *     (Mirrors DB constraint chk_cm_variance_reason — service gives the
     *     friendly French 400, the DB is the safety net.)
     */
    private void validateClaimedAssessed(BigDecimal claimed, BigDecimal assessed, String varianceReason) {
        if (assessed != null && assessed.signum() <= 0) {
            throw new BadRequestException("INVALID_ASSESSED_AMOUNT",
                    "Le montant évalué doit être positif");
        }
        if (claimed != null && claimed.signum() <= 0) {
            throw new BadRequestException("INVALID_CLAIMED_AMOUNT",
                    "Le montant réclamé doit être positif");
        }
        if (claimed != null && assessed != null && claimed.compareTo(assessed) != 0
                && (varianceReason == null || varianceReason.isBlank())) {
            throw new BadRequestException("VARIANCE_REASON_REQUIRED",
                    "Un motif d'écart est obligatoire quand le montant évalué " +
                    "diffère du montant réclamé");
        }
    }

    /**
     * V26 — validate payment_mode against the Tunisia v1 whitelist.
     * Returns the normalized uppercase code, or {@code null} for blank input
     * (so the DB column stays NULL — legacy-safe). Throws a French 400 for
     * unknown codes; the DB CHECK (chk_cm_payment_mode) is the safety net.
     */
    private String validatePaymentMode(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return PaymentMode.from(raw).name();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("INVALID_PAYMENT_MODE",
                    "Mode de règlement invalide: " + raw +
                    " — valeurs acceptées : ESPECE, CHEQUE, VIREMENT, VERSEMENT, MANDAT, AUTRE");
        }
    }

    /**
     * V26 — validate nature against the Tunisia v1 whitelist.
     * Same contract as {@link #validatePaymentMode}: null for blank, French 400
     * for unknown. DB CHECK (chk_cm_nature) is the safety net.
     */
    private String validateNature(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return MovementNature.from(raw).name();
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("INVALID_NATURE",
                    "Nature invalide: " + raw +
                    " — valeurs acceptées : ALIM_CAISSE, SOUS_TRAITANCE, MATERIEL, TRANSPORT, " +
                    "MAIN_OEUVRE, LOCATION, LABO, CARBURANT, DIVERS");
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

    /** Fetch one movement row with lock (FOR UPDATE) — for post/void/attach. */
    private Map<String, Object> fetchMovementForUpdate(UUID id, UUID orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, movement_type, amount, currency, movement_date,
                       from_pot_id, to_pot_id, project_id, party_id, label, note, status,
                       process_instance_id, created_by, created_at, posted_by, posted_at,
                       voided_by, voided_at, void_reason,
                       claimed_amount, assessed_amount, variance_reason, document_id,
                       payment_mode, payment_ref, voucher_no, nature, invoice_ref,
                       bc_id, facture_id
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
    /** Part 4.1 — Validate commercial link belongs to org + project matches. */
    private void validateCommercialLink(UUID linkId, UUID orgId, UUID movementProjectId,
                                          String tableName, String errorCode) {
        Map<String, Object> row = jdbc.queryForMap(
                "select project_id from " + tableName + " where id = ? and org_id = ?",
                linkId, orgId);
        UUID linkProjectId = (UUID) row.get("project_id");
        if (movementProjectId != null && linkProjectId != null
                && !movementProjectId.equals(linkProjectId)) {
            throw new BusinessRuleException(errorCode,
                    "Le " + tableName + " n'appartient pas au même projet que le paiement");
        }
    }

    private Map<String, Object> fetchMovementForRead(UUID id, UUID orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, org_id, movement_type, amount, currency, movement_date,
                       from_pot_id, to_pot_id, project_id, party_id, label, note, status,
                       process_instance_id, created_by, created_at, posted_by, posted_at,
                       voided_by, voided_at, void_reason,
                       claimed_amount, assessed_amount, variance_reason, document_id,
                       payment_mode, payment_ref, voucher_no, nature, invoice_ref,
                       bc_id, facture_id
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
                (String) row.get("void_reason"),
                (BigDecimal) row.get("claimed_amount"),
                (BigDecimal) row.get("assessed_amount"),
                (String) row.get("variance_reason"),
                (UUID) row.get("document_id"),
                // V26 enriched fields
                (String) row.get("payment_mode"),
                (String) row.get("payment_ref"),
                (String) row.get("voucher_no"),
                (String) row.get("nature"),
                (String) row.get("invoice_ref"),
                (UUID) row.get("bc_id"),
                (UUID) row.get("facture_id")
        );
    }

    /** Mapper for the richer response — uses JOIN'd columns (from_pot_name, etc.). */
    private static MovementDetailResponse toMovementDetailResponse(Map<String, Object> row, java.util.List<String> materialWarnings) {
        Object movementDateObj = row.get("movement_date");
        LocalDate movementDate = movementDateObj == null ? null
                : (movementDateObj instanceof Date d ? d.toLocalDate()
                    : LocalDate.parse(movementDateObj.toString()));
        return new MovementDetailResponse(
                (UUID) row.get("id"),
                (UUID) row.get("org_id"),
                (String) row.get("movement_type"),
                (BigDecimal) row.get("amount"),
                (String) row.get("currency"),
                movementDate,
                (UUID) row.get("from_pot_id"),
                (String) row.get("from_pot_name"),
                (UUID) row.get("to_pot_id"),
                (String) row.get("to_pot_name"),
                (UUID) row.get("project_id"),
                (String) row.get("project_name"),
                (UUID) row.get("party_id"),
                (String) row.get("party_name"),
                (String) row.get("party_type"),
                (String) row.get("label"),
                (String) row.get("note"),
                (String) row.get("status"),
                (UUID) row.get("process_instance_id"),
                (UUID) row.get("created_by"),
                toInstant(row.get("created_at")),
                (UUID) row.get("posted_by"),
                (String) row.get("posted_by_name"),
                toInstant(row.get("posted_at")),
                (UUID) row.get("voided_by"),
                (String) row.get("voided_by_name"),
                toInstant(row.get("voided_at")),
                (String) row.get("void_reason"),
                (BigDecimal) row.get("claimed_amount"),
                (BigDecimal) row.get("assessed_amount"),
                (String) row.get("variance_reason"),
                (UUID) row.get("document_id"),
                (String) row.get("document_name"),
                // V26 enriched fields
                (String) row.get("payment_mode"),
                (String) row.get("payment_ref"),
                (String) row.get("voucher_no"),
                (String) row.get("nature"),
                (String) row.get("invoice_ref"),
                // V28 (TASK 6) — void request flag
                toInstant(row.get("void_requested_at")),
                (String) row.get("void_request_reason"),
                (UUID) row.get("bc_id"),
                (String) row.get("bc_reference"),
                (UUID) row.get("facture_id"),
                (String) row.get("facture_reference"),
                materialWarnings != null ? materialWarnings : java.util.List.of()
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
