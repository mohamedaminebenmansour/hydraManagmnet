package tn.hydra.cash;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.process.ApprovalRuleService;
import tn.hydra.process.ProcessService;
import tn.hydra.process.RuleMatcher;
import tn.hydra.purchase.PurchaseOrderService;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ============================================================================
 *  PaymentFacade — orchestrates the "submit payment" flow.
 * ============================================================================
 *
 *  Single responsibility: turn a user request "I want to pay X TND to party Y
 *  from chantier Z" into a (DRAFT movement) + (running PAYMENT_APPROVAL process)
 *  pair, atomically.
 *
 *  Dependency direction (no cycles):
 *
 *      PaymentFacade ──▶ CashService      (create the DRAFT movement)
 *      PaymentFacade ──▶ ProcessService   (start the workflow)
 *      ProcessService ──▶ CashService     (auto-post on COMPLETED — see hook)
 *      CashService    ──▶ (no ProcessService dep — stays pure)
 *
 *  REST entry: CashController POST /api/cash/movements/submit-payment
 *
 *  v1 scope: only OUT (supplier/subcontractor payments).
 *  v2 may extend to TRANSFER (central → chantier top-ups requiring approval).
 * ============================================================================
 */
@Service
public class PaymentFacade {

    private final CashService cashService;
    private final ProcessService processService;
    private final ApprovalRuleService approvalRuleService;
    private final AppUserRepository users;
    private final EventLogService audit;
    private final PurchaseOrderService purchaseOrders;

    @org.springframework.beans.factory.annotation.Value("${hydra.payments.require-stock-match:false}")
    private boolean requireStockMatch;

    public PaymentFacade(CashService cashService,
                         ProcessService processService,
                         ApprovalRuleService approvalRuleService,
                         AppUserRepository users,
                         EventLogService audit,
                         PurchaseOrderService purchaseOrders) {
        this.cashService = cashService;
        this.processService = processService;
        this.approvalRuleService = approvalRuleService;
        this.users = users;
        this.audit = audit;
        this.purchaseOrders = purchaseOrders;
    }

    // ══════════════════════════════════════════════════════════════
    //  REQUEST / RESPONSE RECORDS
    // ══════════════════════════════════════════════════════════════

    /**
     * Body for POST /api/cash/movements/submit-payment.
     * Mirrors CreateMovementRequest but pinned to OUT (fromPotId only) —
     * the facade injects movement_type=OUT and processInstanceId=null,
     * then links the process after starting it.
     *
     * Claimed vs assessed (TASK 1):
     *   - claimedAmount  = what the party asked (facture/situation);
     *   - assessedAmount = what the company accepts to pay — the amount the
     *     caisse will POST;
     *   - varianceReason REQUIRED when claimed != assessed (service-enforced,
     *     DB check chk_cm_variance_reason is the safety net);
     *   - amount stays accepted for backward compatibility with the B5 short
     *     form: when claimed/assessed are omitted, amount IS the assessed value.
     *
     * V26 enriched fields (Tunisian état de caisse — all optional):
     *   - paymentMode : ESPECE | CHEQUE | VIREMENT | VERSEMENT | MANDAT | AUTRE
     *   - paymentRef  : n° chèque / réf virement / réf mandat (free text)
     *   - voucherNo   : Bon N° (petty-cash voucher best practice)
     *   - nature      : ALIM_CAISSE | SOUS_TRAITANCE | MATERIEL | TRANSPORT |
     *                   MAIN_OEUVRE | LOCATION | LABO | CARBURANT | DIVERS
     *   - invoiceRef  : réf facture / situation du tiers (optional)
     */
    public record SubmitPaymentRequest(
            UUID fromPotId,
            java.math.BigDecimal amount,
            java.time.LocalDate movementDate,
            UUID projectId,
            UUID partyId,
            String label,
            String note,
            java.math.BigDecimal claimedAmount,
            java.math.BigDecimal assessedAmount,
            String varianceReason,
            // V26 enriched fields — Tunisian état de caisse (all optional)
            String paymentMode,
            String paymentRef,
            String voucherNo,
            String nature,
            String invoiceRef,
            UUID bcId,
            UUID factureId
    ) {}

    /**
     * Response: the new DRAFT movement + the running process instance id.
     * The frontend uses processInstanceId to deep-link to the timeline view
     * and show the "En cours d'approbation" badge next to the movement.
     */
    public record SubmitPaymentResponse(
            CashService.MovementResponse movement,
            UUID processInstanceId,
            List<String> warnings
    ) {
        /** Backward-compatible 2-field constructor (empty warnings). */
        public SubmitPaymentResponse(CashService.MovementResponse movement, UUID processInstanceId) {
            this(movement, processInstanceId, List.of());
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  SUBMIT PAYMENT
    // ══════════════════════════════════════════════════════════════

    /**
     * Orchestration:
     *   (a) create DRAFT OUT movement via CashService (processInstanceId=null)
     *   (b) start PAYMENT_APPROVAL workflow with subjectType=CASH_MOVEMENT,
     *       subjectId=movement.id
     *   (c) link: UPDATE movement.process_instance_id = instance.id
     *   (d) audit CASH_MOVEMENT_SUBMITTED
     *   (e) return movement + processInstanceId
     *
     * All in one @Transactional — if any step fails, the DRAFT movement +
     * the process instance both roll back. No orphans.
     */
    @Transactional
    public SubmitPaymentResponse submitPayment(Authentication auth, SubmitPaymentRequest req) {
        // (a) Create the DRAFT OUT movement — CashService does all validation
        //     (pot in org, project access, amount > 0, date set, type=OUT)
        CashService.CreateMovementRequest draftReq = new CashService.CreateMovementRequest(
                "OUT",                          // pinned: OUT for v1
                req.amount(),                   // legacy fallback — synced to assessed in CashService
                req.movementDate(),
                req.fromPotId(),
                null,                           // toPotId null for OUT
                req.projectId(),
                req.partyId(),
                req.label(),
                req.note(),
                null,                           // processInstanceId set in step (c)
                req.claimedAmount(),
                req.assessedAmount(),
                req.varianceReason(),
                // V26 enriched fields — Tunisian état de caisse (pass-through; CashService validates)
                req.paymentMode(),
                req.paymentRef(),
                req.voucherNo(),
                req.nature(),
                req.invoiceRef(),
                req.bcId(),
                req.factureId()
        );
        CashService.MovementResponse movement = cashService.createMovement(auth, draftReq);

        // Resolve project + user for the process start
        AppUser me = users.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Unknown user: " + auth.getName()));
        UUID projectId = movement.projectId();
        if (projectId == null) {
            // OUT movement on a PROJECT pot — projectId resolved by CashService from the pot
            // But if the user passed a COMPANY pot without projectId, we can't start a process.
            throw new tn.hydra.common.BusinessRuleException(
                    "PROJECT_REQUIRED_FOR_PAYMENT",
                    "Un paiement doit être rattaché à un projet (projectId manquant)");
        }

        // (b) Resolve the approval rule that governs this payment and start it.
        //     ApprovalRuleService + RuleMatcher pick the template by
        //     subject_type=CASH_PAYMENT + conditions (amount, nature, payment_mode)
        //     + per-chantier whitelist; startByTemplateId runs the same checks as
        //     start() but from an already-resolved id. If no rule matches, never
        //     start a process — a payment must not exist without an approval flow
        //     (the OUT movement is posted only when the process reaches COMPLETED,
        //     via the existing cash hook in completeStep — unchanged).
        RuleMatcher.PaymentFacts facts = new RuleMatcher.PaymentFacts(
                projectId, movement.amount(), movement.nature(), movement.paymentMode());
        UUID templateId = approvalRuleService.findRule(me.getOrgId(), "CASH_PAYMENT", facts)
                .orElseThrow(() -> new tn.hydra.common.BusinessRuleException(
                        "NO_APPROVAL_RULE",
                        "Aucune règle d'approbation ne s'applique à ce paiement sur ce chantier"));
        ProcessService.StartProcessResponse started = processService.startByTemplateId(
                auth, projectId, templateId, "CASH_MOVEMENT", movement.id());

        // (c) Link the movement to the process instance
        cashService.linkProcessInstance(movement.id(), started.id(), me.getOrgId());

        // (d) Audit (separate from CASH_MOVEMENT_CREATED emitted by createMovement)
        //     claimed/assessed/variance are part of the audit story: the owner
        //     must see WHAT WAS ASKED vs WHAT WAS PAID in the timeline.
        String variance = movement.varianceReason() == null ? ""
                : movement.varianceReason().replace("\\", "\\\\").replace("\"", "\\\"");
        audit.log("CASH_MOVEMENT_SUBMITTED", "cash_movement", movement.id(),
                "{\"processInstance\":\"" + started.id() +
                "\",\"projectId\":\"" + projectId + "\"" +
                ",\"amount\":" + movement.amount() +
                (movement.claimedAmount() != null ? ",\"claimedAmount\":" + movement.claimedAmount() : "") +
                (movement.assessedAmount() != null ? ",\"assessedAmount\":" + movement.assessedAmount() : "") +
                (movement.varianceReason() != null ? ",\"varianceReason\":\"" + variance + "\"" : "") +
                (movement.paymentMode()   != null ? ",\"paymentMode\":\""   + movement.paymentMode()   + "\"" : "") +
                (movement.nature()        != null ? ",\"nature\":\""          + movement.nature()        + "\"" : "") +
                (movement.voucherNo()     != null ? ",\"voucherNo\":\""       + movement.voucherNo().replace("\\", "\\\\").replace("\"", "\\\"") + "\"" : "") +
                ",\"fromPot\":\"" + movement.fromPotId() + "\"}");

        // (e) Return — refetch the movement so processInstanceId is populated
        CashService.MovementResponse linked = cashService.getMovement(auth, movement.id());

        // Part 3.3 — soft material-proof warnings (never blocks unless flag is on)
        List<String> warnings = computePaymentWarnings(auth, req, started.warnings());

        return new SubmitPaymentResponse(linked, started.id(), warnings);
    }

    // ══════════════════════════════════════════════════════════════
    //  PART 3.3 — Soft material-proof warnings
    // ══════════════════════════════════════════════════════════════

    /**
     * Compute advisory warnings for material proof when submitting a payment.
     *
     * Rules (Part 3.3):
     *   - If bcId is null → skip material checks (no warning about missing BC).
     *   - If bcId present → call PurchaseOrderService.matchForBc(auth, bcId)
     *     and for each BC line whose status != MATCHED, add a French advisory.
     *   - matchForBc failures: log + swallow (payment still 200).
     *   - If hydra.payments.require-stock-match=true AND warnings non-empty
     *     from material checks → throw 409 STOCK_MATCH_REQUIRED (hard gate).
     *
     * Returns the combined list: process-level warnings + material warnings.
     * NEVER blocks unless the flag is explicitly true.
     */
    private List<String> computePaymentWarnings(Authentication auth,
                                                  SubmitPaymentRequest req,
                                                  List<String> processWarnings) {
        List<String> warnings = new ArrayList<>(processWarnings);

        if (req.bcId() == null) {
            return warnings; // No BC linked → skip material checks (amendment #2)
        }

        List<String> materialWarnings = purchaseOrders.computeMaterialWarnings(auth, req.bcId());
        warnings.addAll(materialWarnings);

        // Hard gate (default OFF — hydra.payments.require-stock-match=false)
        if (requireStockMatch && !materialWarnings.isEmpty()) {
            throw new tn.hydra.common.BusinessRuleException("STOCK_MATCH_REQUIRED",
                    "Rapprochement stock non conforme — " + materialWarnings.size()
                            + " ligne(s) en alerte. Activez le rapprochement ou corrigez les écarts.");
        }

        return warnings;
    }
}
