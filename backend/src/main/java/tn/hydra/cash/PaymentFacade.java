package tn.hydra.cash;

import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.process.ProcessService;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

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
    private final AppUserRepository users;
    private final EventLogService audit;

    public PaymentFacade(CashService cashService,
                         ProcessService processService,
                         AppUserRepository users,
                         EventLogService audit) {
        this.cashService = cashService;
        this.processService = processService;
        this.users = users;
        this.audit = audit;
    }

    // ══════════════════════════════════════════════════════════════
    //  REQUEST / RESPONSE RECORDS
    // ══════════════════════════════════════════════════════════════

    /**
     * Body for POST /api/cash/movements/submit-payment.
     * Mirrors CreateMovementRequest but pinned to OUT (fromPotId only) —
     * the facade injects movement_type=OUT and processInstanceId=null,
     * then links the process after starting it.
     */
    public record SubmitPaymentRequest(
            UUID fromPotId,
            java.math.BigDecimal amount,
            java.time.LocalDate movementDate,
            UUID projectId,
            UUID partyId,
            String label,
            String note
    ) {}

    /**
     * Response: the new DRAFT movement + the running process instance id.
     * The frontend uses processInstanceId to deep-link to the timeline view
     * and show the "En cours d'approbation" badge next to the movement.
     */
    public record SubmitPaymentResponse(
            CashService.MovementResponse movement,
            UUID processInstanceId
    ) {}

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
                req.amount(),
                req.movementDate(),
                req.fromPotId(),
                null,                           // toPotId null for OUT
                req.projectId(),
                req.partyId(),
                req.label(),
                req.note(),
                null                            // processInstanceId set in step (c)
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

        // (b) Start the PAYMENT_APPROVAL workflow
        ProcessService.StartProcessCommand startCmd = new ProcessService.StartProcessCommand(
                projectId,
                "PAYMENT_APPROVAL",
                "CASH_MOVEMENT",                // subject_type — documented contract
                movement.id()                   // subject_id = movement id
        );
        ProcessService.StartProcessResponse started = processService.start(auth, startCmd);

        // (c) Link the movement to the process instance
        cashService.linkProcessInstance(movement.id(), started.id(), me.getOrgId());

        // (d) Audit (separate from CASH_MOVEMENT_CREATED emitted by createMovement)
        audit.log("CASH_MOVEMENT_SUBMITTED", "cash_movement", movement.id(),
                "{\"processInstance\":\"" + started.id() +
                "\",\"projectId\":\"" + projectId + "\"" +
                ",\"amount\":" + movement.amount() +
                ",\"fromPot\":\"" + movement.fromPotId() + "\"}");

        // (e) Return — refetch the movement so processInstanceId is populated
        CashService.MovementResponse linked = cashService.getMovement(auth, movement.id());
        return new SubmitPaymentResponse(linked, started.id());
    }
}
