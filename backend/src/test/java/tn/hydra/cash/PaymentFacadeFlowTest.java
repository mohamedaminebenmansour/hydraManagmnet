package tn.hydra.cash;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.process.ProcessService;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ============================================================================
 *  PaymentFacadeFlowTest — locks the CURRENT payment-approval behaviour.
 * ============================================================================
 *
 *  Goal (feature/approval-rules, Step 1): capture, as an integration test
 *  against the hydra_test database, exactly what the payment flow does today
 *  so that the V38 approval-rules migration + the rule matcher cannot regress
 *  it silently.
 *
 *  Locked contract:
 *   1. submitPayment(...) creates a DRAFT cash_movement and starts a
 *      PAYMENT_APPROVAL process with 3 steps (subject_type = CASH_MOVEMENT).
 *   2. After completing all 3 steps, the movement becomes POSTED and the
 *      source pot balance decreases by the assessed amount.
 *   3. Rejecting the APPROVE step leaves the movement DRAFT (never posted)
 *      and the pot balance untouched.
 *
 *  This test MUST stay green before and after V38. If it cannot pass on a
 *  clean main, the prior state is wrong and the feature work must stop.
 *
 *  Isolation: @Transactional + @Rollback — every insert/update performed
 *  here (and inside the @Transactional production code it drives) participates
 *  in one test transaction that is rolled back at the end of each test, so the
 *  hydra_test database is left clean. The test provisions its own org / owner
 *  user / project / party / funded project pot so it does not depend on any
 *  seed data.
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class PaymentFacadeFlowTest {

    @Autowired private PaymentFacade paymentFacade;
    @Autowired private CashService cashService;
    @Autowired private ProcessService processService;
    @Autowired private JdbcTemplate jdbc;

    /** Funding injected into the project pot (POSTED IN movement). */
    private static final BigDecimal FUNDING = new BigDecimal("1000.000");
    /** Assessed amount of the OUT payment; must be <= FUNDING to pass the
     *  no-overdraft guard when the process auto-posts at step 3. */
    private static final BigDecimal PAY_AMOUNT = new BigDecimal("300.000");

    private UUID orgId;
    private UUID ownerId;
    private UUID projectId;
    private UUID partyId;
    private UUID potId;
    private String ownerEmail;
    private Authentication auth;

    @BeforeEach
    void provisionWorld() {
        // Fresh, unique identifiers so a crashed prior run can never collide
        // on app_user.email (unique) or any partial unique index.
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        ownerEmail = "flowtest.owner." + ownerId.toString().replace("-", "") + "@hydra.test";
        projectId = UUID.randomUUID();
        partyId = UUID.randomUUID();
        potId = UUID.randomUUID();

        jdbc.update("insert into organization (id, name) values (?, ?)",
                orgId, "FlowTest Org " + orgId);

        // Org owner — bypasses requireCanWrite / requireProjectAccess /
        // requireStepAccess, so a single user can drive all 3 steps.
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """,
                ownerId, orgId, ownerEmail, "hash", "FlowTest Owner");

        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "FlowTest Chantier " + projectId, ownerId);

        jdbc.update("insert into party (id, org_id, name, type, active) values (?, ?, ?, 'SUB', true)",
                partyId, orgId, "FlowTest Sous-traitant");

        jdbc.update("""
                insert into cash_pot (id, org_id, type, project_id, name, currency, active)
                values (?, ?, 'PROJECT', ?, ?, 'TND', true)
                """,
                potId, orgId, projectId, "Caisse chantier");

        // Fund the pot with a POSTED IN movement so computeBalance(pot) = FUNDING.
        // Without this, the auto-post at step 3 would hit the INSUFFICIENT_BALANCE
        // guard (balance 0 - PAY_AMOUNT < 0) and roll the whole flow back.
        jdbc.update("""
                insert into cash_movement
                  (id, org_id, movement_type, amount, currency, movement_date,
                   to_pot_id, status, created_by)
                values (?, ?, 'IN', ?, 'TND', ?, ?, 'POSTED', ?)
                """,
                UUID.randomUUID(), orgId, FUNDING, Date.valueOf(LocalDate.now()), potId, ownerId);

        // Production code passes `auth` as a parameter, but EventLogService.log
        // reads SecurityContextHolder to attribute audit rows. Mirror a real
        // request filter so the audit trail resolves to the owner.
        auth = new UsernamePasswordAuthenticationToken(ownerEmail, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ══════════════════════════════════════════════════════════════
    //  TEST 1 — full happy path: DRAFT → 3 steps → POSTED + balance drop
    // ══════════════════════════════════════════════════════════════
    @Test
    void submitPayment_createsDraftMovementAndThreeStepFlow_thenPostingDecreasesBalance() {
        // (1) Submit
        PaymentFacade.SubmitPaymentRequest req = new PaymentFacade.SubmitPaymentRequest(
                potId, PAY_AMOUNT, LocalDate.now(), projectId, partyId,
                "Paiement sous-traitant", null,
                null, null, null,                  // claimed / assessed / variance
                "CHEQUE", null, null,              // paymentMode / paymentRef / voucherNo
                "SOUS_TRAITANCE", null,            // nature / invoiceRef
                null, null);                       // bcId / factureId
        PaymentFacade.SubmitPaymentResponse resp = paymentFacade.submitPayment(auth, req);

        CashService.MovementResponse mv = resp.movement();
        assertThat(mv.id()).isNotNull();
        assertThat(mv.status()).isEqualTo("DRAFT");
        assertThat(mv.movementType()).isEqualTo("OUT");
        assertThat(resp.processInstanceId()).isNotNull();

        // (2) The process instance is RUNNING, CASH_MOVEMENT-subject, 3 steps.
        Map<String, Object> inst = jdbc.queryForMap(
                "select status, subject_type, subject_id, template_id " +
                        "from process_instance where id = ?",
                resp.processInstanceId());
        assertThat(inst.get("status")).isEqualTo("RUNNING");
        assertThat(inst.get("subject_type")).isEqualTo("CASH_MOVEMENT");
        assertThat((UUID) inst.get("subject_id")).isEqualTo(mv.id());

        Integer templateStepCount = jdbc.queryForObject(
                "select count(*) from process_template_step where template_id = ?",
                Integer.class, inst.get("template_id"));
        assertThat(templateStepCount).isEqualTo(3);

        // Balance is still FUNDING — the OUT movement is DRAFT, not POSTED.
        BigDecimal balanceBeforePost = cashService.balance(auth, potId).balance();
        assertThat(balanceBeforePost).isEqualByComparingTo(FUNDING);

        // (3) Drive the 3 steps (DRAFT → APPROVE → POST). After each
        // completeStep the next PENDING step is created; the 3rd completion
        // flips the instance to COMPLETED and the cash hook auto-posts.
        UUID instanceId = resp.processInstanceId();
        for (int i = 1; i <= 3; i++) {
            UUID pendingStepId = jdbc.queryForObject(
                    "select id from process_step_instance " +
                            "where instance_id = ? and status = 'PENDING' " +
                            "order by step_order asc limit 1",
                    UUID.class, instanceId);
            processService.completeStep(auth, instanceId, pendingStepId,
                    new ProcessService.CompleteStepRequest("étape " + i, null));
        }

        // (4) Movement is now POSTED, instance COMPLETED, balance dropped.
        CashService.MovementResponse posted = cashService.getMovement(auth, mv.id());
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(posted.postedAt()).isNotNull();

        String instanceFinal = jdbc.queryForObject(
                "select status from process_instance where id = ?",
                String.class, instanceId);
        assertThat(instanceFinal).isEqualTo("COMPLETED");

        BigDecimal balanceAfterPost = cashService.balance(auth, potId).balance();
        assertThat(balanceAfterPost).isEqualByComparingTo(FUNDING.subtract(PAY_AMOUNT));
    }

    // ══════════════════════════════════════════════════════════════
    //  TEST 2 — rejecting the APPROVE step must NOT post the movement
    // ══════════════════════════════════════════════════════════════
    @Test
    void rejectApproveStep_leavesMovementDraftAndBalanceUnchanged() {
        PaymentFacade.SubmitPaymentRequest req = new PaymentFacade.SubmitPaymentRequest(
                potId, PAY_AMOUNT, LocalDate.now(), projectId, partyId,
                "Paiement sous-traitant", null,
                null, null, null,
                "CHEQUE", null, null,
                "SOUS_TRAITANCE", null,
                null, null);
        PaymentFacade.SubmitPaymentResponse resp = paymentFacade.submitPayment(auth, req);
        UUID instanceId = resp.processInstanceId();
        UUID movementId = resp.movement().id();

        // Complete step 1 (DRAFT) — reveals step 2 (APPROVE) as PENDING.
        UUID step1 = currentPendingStep(instanceId);
        processService.completeStep(auth, instanceId, step1,
                new ProcessService.CompleteStepRequest("draft confirmé", null));

        BigDecimal balanceBeforeReject = cashService.balance(auth, potId).balance();
        assertThat(balanceBeforeReject).isEqualByComparingTo(FUNDING); // still DRAFT

        // Reject step 2 (APPROVE).
        UUID step2 = currentPendingStep(instanceId);
        assertThat(jdbc.queryForObject(
                "select step_key from process_step_instance where id = ?",
                String.class, step2)).isEqualTo("APPROVE");
        processService.rejectStep(auth, instanceId, step2,
                new ProcessService.RejectStepRequest("refusé"));

        // Movement stays DRAFT, instance is REJECTED, balance untouched.
        String movementStatus = jdbc.queryForObject(
                "select status from cash_movement where id = ?",
                String.class, movementId);
        assertThat(movementStatus).isEqualTo("DRAFT");

        String instanceStatus = jdbc.queryForObject(
                "select status from process_instance where id = ?",
                String.class, instanceId);
        assertThat(instanceStatus).isEqualTo("REJECTED");

        BigDecimal balanceAfterReject = cashService.balance(auth, potId).balance();
        assertThat(balanceAfterReject).isEqualByComparingTo(FUNDING);
    }

    // ══════════════════════════════════════════════════════════════
    //  Helper
    // ══════════════════════════════════════════════════════════════
    private UUID currentPendingStep(UUID instanceId) {
        return jdbc.queryForObject(
                "select id from process_step_instance " +
                        "where instance_id = ? and status = 'PENDING' " +
                        "order by step_order asc limit 1",
                UUID.class, instanceId);
    }
}
