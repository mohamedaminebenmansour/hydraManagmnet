package tn.hydra.process;

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
import tn.hydra.exception.BadRequestException;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ============================================================================
 *  RuleBuilderTest — rule-builder API: condition validation, copy, sentence,
 *  and the /test endpoint (feature/rule-builder).
 * ============================================================================
 *
 *  @SpringBootTest against hydra_test; @Transactional + @Rollback keep the DB
 *  clean. Each test provisions its own org / owner / chantier.
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class RuleBuilderTest {

    @Autowired private ProcessService svc;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID ownerId;
    private UUID projectId;
    private Authentication ownerAuth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        projectId = UUID.randomUUID();

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "RuleBuilder Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, ownerId, orgId, "rulebuilder.owner." + ownerId + "@hydra.test", "hash", "RuleBuilder Owner");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "RuleBuilder Chantier " + projectId, ownerId);

        ownerAuth = new UsernamePasswordAuthenticationToken(
                "rulebuilder.owner." + ownerId + "@hydra.test", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Build a create request with a unique key + the given conditions/subjectType. */
    private ProcessService.CreateTemplateRequest req(Map<String, Object> conditions, String subjectType) {
        return new ProcessService.CreateTemplateRequest(
                "RULE_" + UUID.randomUUID(), "Règle de test", "desc", subjectType, conditions, 100);
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private UUID systemTemplateId(String key) {
        return jdbc.queryForObject(
                "select id from process_template where key = ? and org_id is null", UUID.class, key);
    }

    // ══════════════════════════════════════════════════════════════
    //  Point 2 — condition validation (each error)
    // ══════════════════════════════════════════════════════════════

    @Test
    void rejectsUnknownConditionKey() {
        assertThatThrownBy(() -> svc.createTemplate(ownerAuth, req(map("foo", "bar"), "CASH_PAYMENT")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Clé de condition inconnue");
    }

    @Test
    void rejectsNegativeAmount() {
        assertThatThrownBy(() -> svc.createTemplate(ownerAuth, req(map("amount_min", new BigDecimal("-100")), "CASH_PAYMENT")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ne peut pas être négatif");
    }

    @Test
    void rejectsAmountWithMoreThan3Decimals() {
        assertThatThrownBy(() -> svc.createTemplate(ownerAuth, req(map("amount_min", "100.5001"), "CASH_PAYMENT")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("au plus 3 décimales");
    }

    @Test
    void rejectsAmountMinGreaterThanAmountMax() {
        assertThatThrownBy(() -> svc.createTemplate(ownerAuth,
                req(map("amount_min", new BigDecimal("1000"), "amount_max", new BigDecimal("500")), "CASH_PAYMENT")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ne peut pas dépasser le montant maximum");
    }

    @Test
    void rejectsUnknownNature() {
        assertThatThrownBy(() -> svc.createTemplate(ownerAuth, req(map("natures", List.of("FOO")), "CASH_PAYMENT")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Nature inconnue : FOO");
    }

    @Test
    void rejectsUnknownPaymentMode() {
        assertThatThrownBy(() -> svc.createTemplate(ownerAuth, req(map("payment_modes", List.of("FOO")), "CASH_PAYMENT")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Mode de règlement inconnu : FOO");
    }

    @Test
    void rejectsProjectIdNotInOrg() {
        assertThatThrownBy(() -> svc.createTemplate(ownerAuth,
                req(map("project_ids", List.of(UUID.randomUUID().toString())), "CASH_PAYMENT")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Chantier introuvable dans votre organisation");
    }

    // ══════════════════════════════════════════════════════════════
    //  Point 3 — only CASH_PAYMENT subjectType
    // ══════════════════════════════════════════════════════════════

    @Test
    void rejectsNonCashPaymentSubjectType() {
        assertThatThrownBy(() -> svc.createTemplate(ownerAuth, req(new HashMap<>(), "PURCHASE_ORDER")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Ce type de règle n'est pas encore disponible");
    }

    // ══════════════════════════════════════════════════════════════
    //  Point 4 — copy a system template
    // ══════════════════════════════════════════════════════════════

    @Test
    void copySystemTemplate_createsOrgScopedCopyWithSameStepsAndConditions() {
        UUID src = systemTemplateId("PAYMENT_APPROVAL");
        ProcessService.TemplateDetail copy = svc.copyTemplate(ownerAuth, src);

        assertThat(copy.orgId()).isEqualTo(orgId);      // org-scoped (not system)
        assertThat(copy.active()).isFalse();            // copy starts inactive
        assertThat(copy.subjectType()).isEqualTo("CASH_PAYMENT");
        assertThat(copy.conditions()).isEmpty();        // PAYMENT_APPROVAL has {} conditions
        assertThat(copy.priority()).isEqualTo(1000);
        assertThat(copy.steps()).hasSize(3);            // PAYMENT_APPROVAL has 3 steps
        // Step structure preserved (same keys, in order).
        assertThat(copy.steps().stream().map(ProcessService.TemplateStepView::stepKey).toList())
                .containsExactly("DRAFT", "APPROVE", "POST");
    }

    // ══════════════════════════════════════════════════════════════
    //  Point 5 — sentence for 3 different rules
    // ══════════════════════════════════════════════════════════════

    private UUID createRule(Map<String, Object> conditions) {
        return svc.createTemplate(ownerAuth, req(conditions, "CASH_PAYMENT")).id();
    }

    @Test
    void sentence_amountMinWithTwoStepsDueDays() {
        UUID t = createRule(map("amount_min", new BigDecimal("5000")));
        svc.addStep(ownerAuth, t, new ProcessService.CreateStepRequest(1, "DRAFT", "Draft", "FINANCE", false, "ANY", 2));
        svc.addStep(ownerAuth, t, new ProcessService.CreateStepRequest(2, "APPROVE", "Approve", "OWNER", false, "ANY", 2));

        String s = svc.getTemplateSentence(ownerAuth, t);
        assertThat(s).contains("de plus de 5 000,000 TND")
                .contains("au Finance puis au Gérant")
                .contains("(2 jours chacun)");
    }

    @Test
    void sentence_amountMaxSingleStepNoDueDays() {
        UUID t = createRule(map("amount_max", new BigDecimal("1000")));
        svc.addStep(ownerAuth, t, new ProcessService.CreateStepRequest(1, "APPROVE", "Approve", "FINANCE", false, "ANY", null));

        String s = svc.getTemplateSentence(ownerAuth, t);
        assertThat(s).contains("jusqu'à 1 000,000 TND")
                .contains("au Finance");
        assertThat(s).doesNotContain("jours");
    }

    @Test
    void sentence_noConditionsSingleOwnerStep() {
        UUID t = createRule(new HashMap<>());
        svc.addStep(ownerAuth, t, new ProcessService.CreateStepRequest(1, "APPROVE", "Approve", "OWNER", false, "ANY", null));

        String s = svc.getTemplateSentence(ownerAuth, t);
        assertThat(s).contains("sans condition").contains("au Gérant");
    }

    // ══════════════════════════════════════════════════════════════
    //  Point 6 — /test picks FINANCE_ONLY for 500 TND (when active),
    //            PAYMENT_APPROVAL for 5 000 TND.
    // ══════════════════════════════════════════════════════════════

    @Test
    void testEndpoint_picksFinanceOnlyFor500AndPaymentApprovalFor5000() {
        UUID finOnlyId = systemTemplateId("FINANCE_ONLY");
        // Activate FINANCE_ONLY for this test (rolled back).
        jdbc.update("update process_template set active = true where key = 'FINANCE_ONLY' and org_id is null");

        ProcessService.TestRuleResponse r500 = svc.testRule(ownerAuth, finOnlyId,
                new ProcessService.TestRuleRequest(projectId, new BigDecimal("500"), "SOUS_TRAITANCE", "CHEQUE"));
        assertThat(r500.chosenTemplateKey()).isEqualTo("FINANCE_ONLY");
        assertThat(r500.reason()).contains("FINANCE_ONLY");

        ProcessService.TestRuleResponse r5000 = svc.testRule(ownerAuth, finOnlyId,
                new ProcessService.TestRuleRequest(projectId, new BigDecimal("5000"), "SOUS_TRAITANCE", "CHEQUE"));
        assertThat(r5000.chosenTemplateKey()).isEqualTo("PAYMENT_APPROVAL");
        assertThat(r5000.reason()).contains("PAYMENT_APPROVAL");
    }
}
