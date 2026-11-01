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
import tn.hydra.common.BusinessRuleException;
import tn.hydra.process.ProcessService;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ============================================================================
 *  CaisseInsuffisanteTest — a payment must never make a caisse go below zero
 *  (fix/no-negative-caisse).
 * ============================================================================
 *
 *  @SpringBootTest against hydra_test; @Transactional + @Rollback keep the DB
 *  clean. Each test provisions its own org / owner / chantier / party and a
 *  project pot funded with FUNDING (POSTED IN movement).
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class CaisseInsuffisanteTest {

    @Autowired private PaymentFacade paymentFacade;
    @Autowired private CashService cashService;
    @Autowired private ProcessService processService;
    @Autowired private JdbcTemplate jdbc;

    private static final BigDecimal FUNDING = new BigDecimal("1000.000");

    private UUID orgId;
    private UUID ownerId;
    private UUID projectId;
    private UUID partyId;
    private UUID potId;
    private Authentication auth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        String ownerEmail = "caisse.owner." + ownerId.toString().replace("-", "") + "@hydra.test";
        projectId = UUID.randomUUID();
        partyId = UUID.randomUUID();
        potId = UUID.randomUUID();

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Caisse Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, ownerId, orgId, ownerEmail, "hash", "Caisse Owner");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "Caisse Chantier " + projectId, ownerId);
        jdbc.update("insert into party (id, org_id, name, type, active) values (?, ?, ?, 'SUB', true)",
                partyId, orgId, "Caisse Sous-traitant");
        jdbc.update("""
                insert into cash_pot (id, org_id, type, project_id, name, currency, active)
                values (?, ?, 'PROJECT', ?, ?, 'TND', true)
                """, potId, orgId, projectId, "Caisse chantier");
        jdbc.update("""
                insert into cash_movement
                  (id, org_id, movement_type, amount, currency, movement_date,
                   to_pot_id, status, created_by)
                values (?, ?, 'IN', ?, 'TND', ?, ?, 'POSTED', ?)
                """, UUID.randomUUID(), orgId, FUNDING, Date.valueOf(LocalDate.now()), potId, ownerId);

        auth = new UsernamePasswordAuthenticationToken(ownerEmail, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private PaymentFacade.SubmitPaymentRequest payment(BigDecimal amount) {
        return new PaymentFacade.SubmitPaymentRequest(
                potId, amount, LocalDate.now(), projectId, partyId,
                "Paiement sous-traitant", null,
                null, null, null,
                "CHEQUE", null, null,
                "SOUS_TRAITANCE", null,
                null, null);
    }

    /** Complete every PENDING step of the instance (DRAFT → APPROVE → POST). */
    private void approveAll(UUID instanceId) {
        for (int i = 1; i <= 3; i++) {
            UUID pendingStepId = jdbc.queryForObject(
                    "select id from process_step_instance where instance_id = ? and status = 'PENDING' " +
                            "order by step_order asc limit 1",
                    UUID.class, instanceId);
            processService.completeStep(auth, instanceId, pendingStepId,
                    new ProcessService.CompleteStepRequest("étape " + i, null));
        }
    }

    @Test
    void paymentLargerThanBalance_isRefusedWithCaisseInsuffisante_andBalanceUnchanged() {
        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> paymentFacade.submitPayment(auth, payment(new BigDecimal("63000.000"))));

        assertThat(ex.getCode()).isEqualTo("CAISSE_INSUFFISANTE");
        assertThat(ex.getMessage()).isEqualTo(
                "Solde insuffisant dans la caisse « Caisse chantier » : 1 000,000 TND disponibles, "
                        + "63 000,000 TND demandés.");
        assertThat(cashService.balance(auth, potId).balance()).isEqualByComparingTo(FUNDING);
    }

    @Test
    void twoPaymentsExceedingBalanceTogether_secondOneIsRefusedAtPosting() {
        BigDecimal amount = new BigDecimal("600.000");
        // Both submits pass the early warning: neither is POSTED yet.
        UUID first = paymentFacade.submitPayment(auth, payment(amount)).processInstanceId();
        UUID second = paymentFacade.submitPayment(auth, payment(amount)).processInstanceId();

        approveAll(first);
        assertThat(cashService.balance(auth, potId).balance())
                .isEqualByComparingTo(FUNDING.subtract(amount));

        BusinessRuleException ex = assertThrows(BusinessRuleException.class, () -> approveAll(second));
        assertThat(ex.getCode()).isEqualTo("CAISSE_INSUFFISANTE");
        assertThat(cashService.balance(auth, potId).balance())
                .isEqualByComparingTo(FUNDING.subtract(amount));
    }
}
