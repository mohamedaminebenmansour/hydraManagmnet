package tn.hydra.process;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ============================================================================
 *  ApprovalRuleServiceTest — integration test against hydra_test (Step 5).
 * ============================================================================
 *
 *  Boots the full Spring context (Flyway has already applied V38, so
 *  process_template.subject_type / conditions / priority and
 *  project_process_template.amount_threshold_override exist). @Transactional +
 *  @Rollback keep the DB clean between tests.
 *
 *  Scenarios:
 *   A. Two org-scoped CASH_PAYMENT templates; the one with the BETTER priority
 *      is disabled on the chantier via a project_process_template row.
 *      Assert: the OTHER template wins, regardless of priority.
 *   B. Same two templates, no disable row -> the lowest priority wins.
 *   C. amount_threshold_override end-to-end: a template with amount_min=1000
 *      that would reject a 500 payment is made to match via a per-project
 *      override of 100, and (having the best priority) wins.
 *
 *  Note: the V38 seed leaves the system PAYMENT_APPROVAL template
 *  (subject_type = 'CASH_PAYMENT', priority = 1000, conditions = '{}') in the
 *  DB; it is always a candidate and acts as the fallback — exactly as designed.
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class ApprovalRuleServiceTest {

    @Autowired private ApprovalRuleService approvalRuleService;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID projectId;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        jdbc.update("insert into organization (id, name) values (?, ?)",
                orgId, "RuleTest Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """,
                ownerId, orgId,
                "ruletest.owner." + ownerId.toString().replace("-", "") + "@hydra.test",
                "hash", "RuleTest Owner");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "RuleTest Chantier " + projectId, ownerId);
    }

    @AfterEach
    void tearDown() {
        // @Transactional + @Rollback handles cleanup; nothing else needed.
    }

    // ── helpers ───────────────────────────────────────────────────

    /** Insert an org-scoped CASH_PAYMENT template; return its id. */
    private UUID insertCashPaymentTemplate(int priority, String conditionsJson) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into process_template
                  (id, key, name, org_id, subject_type, conditions, priority, active)
                values (?, ?, ?, ?, 'CASH_PAYMENT', ?::jsonb, ?, true)
                """,
                id,
                "CASH_PAYMENT_" + id,
                "RuleTest template " + id,
                orgId,
                conditionsJson,
                priority);
        return id;
    }

    /** Insert a project_process_template row (enable/disable + override). */
    private void setProjectTemplate(UUID templateId, boolean enabled, BigDecimal override) {
        jdbc.update("""
                insert into project_process_template
                  (id, project_id, template_id, enabled, amount_threshold_override)
                values (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), projectId, templateId, enabled, override);
    }

    private RuleMatcher.PaymentFacts facts(BigDecimal amount) {
        return new RuleMatcher.PaymentFacts(projectId, amount, "SOUS_TRAITANCE", "CHEQUE");
    }

    // ══════════════════════════════════════════════════════════════
    //  Scenario A — disabled template ignored even with better priority
    // ══════════════════════════════════════════════════════════════
    @Test
    void findRule_templateDisabledForProject_isIgnored_evenWithBetterPriority() {
        // betterA has the better (lower) priority but is disabled on the chantier.
        UUID betterA = insertCashPaymentTemplate(10, "{}");
        UUID worseB  = insertCashPaymentTemplate(100, "{}");
        setProjectTemplate(betterA, false, null); // disable A for this chantier

        Optional<UUID> picked = approvalRuleService.findRule(
                orgId, "CASH_PAYMENT", facts(new BigDecimal("500")));

        assertThat(picked).as("the non-disabled template must win").contains(worseB);
        assertThat(picked).isNotEqualTo(betterA);
    }

    // ══════════════════════════════════════════════════════════════
    //  Scenario B — lowest priority wins among enabled templates
    // ══════════════════════════════════════════════════════════════
    @Test
    void findRule_picksLowestPriorityAmongEnabledTemplates() {
        UUID betterA = insertCashPaymentTemplate(10, "{}");
        UUID worseB  = insertCashPaymentTemplate(100, "{}");
        // No project_process_template rows -> both enabled by default.

        Optional<UUID> picked = approvalRuleService.findRule(
                orgId, "CASH_PAYMENT", facts(new BigDecimal("500")));

        assertThat(picked).as("lowest priority enabled template wins").contains(betterA);
        assertThat(picked).isNotEqualTo(worseB);
    }

    // ══════════════════════════════════════════════════════════════
    //  Scenario C — amount_threshold_override end-to-end
    // ══════════════════════════════════════════════════════════════
    @Test
    void findRule_amountThresholdOverride_makesHighMinTemplateMatchAndWin() {
        // Template A requires amount_min = 1000; the 500 payment would normally
        // fail it. A per-chantier override of 100 replaces that min, so A
        // matches and (priority 10) beats B (priority 100).
        UUID a = insertCashPaymentTemplate(10, "{\"amount_min\":1000}");
        UUID b = insertCashPaymentTemplate(100, "{}");
        setProjectTemplate(a, true, new BigDecimal("100")); // enabled + override

        Optional<UUID> picked = approvalRuleService.findRule(
                orgId, "CASH_PAYMENT", facts(new BigDecimal("500")));

        assertThat(picked).as("overridden min lets A match and win").contains(a);
        assertThat(picked).isNotEqualTo(b);
    }

    @Test
    void findRule_noMatchingSubjectType_returnsEmpty() {
        // No PURCHASE_ORDER templates exist for this org.
        Optional<UUID> picked = approvalRuleService.findRule(
                orgId, "PURCHASE_ORDER", facts(new BigDecimal("500")));
        assertThat(picked).isEmpty();
    }
}
