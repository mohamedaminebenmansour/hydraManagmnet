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

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ============================================================================
 *  PaymentRuleRoutingTest — point 3/4: when FINANCE_ONLY is active, the
 *  matching rule decides which approval process a submitted payment starts.
 * ============================================================================
 *
 *  With FINANCE_ONLY active (V39 seeds it inactive; each test activates it
 *  inside its own rolled-back transaction):
 *   * 500 TND  -> FINANCE_ONLY  (priority 500, amount_max 1000 matches) -> 2 steps
 *   * 5000 TND -> PAYMENT_APPROVAL (FINANCE_ONLY amount_max 1000 rejects 5000,
 *                  fallback priority 1000 matches) -> 3 steps
 *
 *  The movement stays DRAFT (steps are not completed), so the cash post hook
 *  is never invoked — this test only asserts routing, not posting.
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class PaymentRuleRoutingTest {

    @Autowired private PaymentFacade paymentFacade;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID ownerId;
    private UUID projectId;
    private UUID partyId;
    private UUID potId;
    private String ownerEmail;
    private Authentication auth;

    private static final BigDecimal FUNDING = new BigDecimal("10000.000");

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        ownerEmail = "routing.owner." + ownerId.toString().replace("-", "") + "@hydra.test";
        projectId = UUID.randomUUID();
        partyId = UUID.randomUUID();
        potId = UUID.randomUUID();

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Routing Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, ownerId, orgId, ownerEmail, "hash", "Routing Owner");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "Routing Chantier " + projectId, ownerId);
        jdbc.update("insert into party (id, org_id, name, type, active) values (?, ?, ?, 'SUB', true)",
                partyId, orgId, "Routing Sous-traitant");
        jdbc.update("""
                insert into cash_pot (id, org_id, type, project_id, name, currency, active)
                values (?, ?, 'PROJECT', ?, ?, 'TND', true)
                """, potId, orgId, projectId, "Caisse chantier");
        // Fund so the DRAFT creation has a valid pot; not posted in this test.
        jdbc.update("""
                insert into cash_movement
                  (id, org_id, movement_type, amount, currency, movement_date,
                   to_pot_id, status, created_by)
                values (?, ?, 'IN', ?, 'TND', ?, ?, 'POSTED', ?)
                """, UUID.randomUUID(), orgId, FUNDING, Date.valueOf(LocalDate.now()), potId, ownerId);

        // Activate the FINANCE_ONLY system template for this test (rolled back).
        jdbc.update("update process_template set active = true where key = 'FINANCE_ONLY' and org_id is null");

        auth = new UsernamePasswordAuthenticationToken(ownerEmail, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private PaymentFacade.SubmitPaymentResponse submit(BigDecimal amount) {
        PaymentFacade.SubmitPaymentRequest req = new PaymentFacade.SubmitPaymentRequest(
                potId, amount, LocalDate.now(), projectId, partyId,
                "Paiement routing " + amount, null,
                null, null, null,
                "CHEQUE", null, null,
                "SOUS_TRAITANCE", null,
                null, null);
        return paymentFacade.submitPayment(auth, req);
    }

    private UUID systemTemplateId(String key) {
        return jdbc.queryForObject(
                "select id from process_template where key = ? and org_id is null",
                UUID.class, key);
    }

    private int stepCount(UUID templateId) {
        return jdbc.queryForObject(
                "select count(*) from process_template_step where template_id = ?",
                Integer.class, templateId);
    }

    @Test
    void smallPayment_routesToFinanceOnly() {
        UUID financeOnly = systemTemplateId("FINANCE_ONLY");

        PaymentFacade.SubmitPaymentResponse resp = submit(new BigDecimal("500.000"));

        UUID pickedTemplate = jdbc.queryForObject(
                "select template_id from process_instance where id = ?",
                UUID.class, resp.processInstanceId());
        assertThat(pickedTemplate).isEqualTo(financeOnly);
        assertThat(stepCount(pickedTemplate)).isEqualTo(2);
    }

    @Test
    void largePayment_routesToPaymentApproval() {
        UUID paymentApproval = systemTemplateId("PAYMENT_APPROVAL");

        PaymentFacade.SubmitPaymentResponse resp = submit(new BigDecimal("5000.000"));

        UUID pickedTemplate = jdbc.queryForObject(
                "select template_id from process_instance where id = ?",
                UUID.class, resp.processInstanceId());
        assertThat(pickedTemplate).isEqualTo(paymentApproval);
        assertThat(stepCount(pickedTemplate)).isEqualTo(3);
    }
}
