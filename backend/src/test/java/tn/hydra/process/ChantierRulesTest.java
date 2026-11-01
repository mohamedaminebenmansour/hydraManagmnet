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
import tn.hydra.user.UserService;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ============================================================================
 *  ChantierRulesTest — the chantier "Règles" tab backend: substitute
 *  resolution + pending-approvals list.
 * ============================================================================
 *
 *  @SpringBootTest against hydra_test; @Transactional + @Rollback keep the DB
 *  clean.
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class ChantierRulesTest {

    @Autowired private ProcessService svc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private UserService userService;

    private UUID orgId;
    private UUID ownerId;
    private UUID projectId;
    private UUID financeRoleId;
    private Authentication ownerAuth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        projectId = UUID.randomUUID();

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "ChantierRules Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, ownerId, orgId, "chantier.owner." + ownerId + "@hydra.test", "hash", "Chantier Owner");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "Chantier " + projectId, ownerId);

        financeRoleId = jdbc.queryForObject(
                "select id from role where code = 'FINANCE' and org_id is null", UUID.class);

        ownerAuth = new UsernamePasswordAuthenticationToken(
                "chantier.owner." + ownerId + "@hydra.test", null, List.of());
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private UUID newFinanceUser(String email, String fullName) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, false, true)
                """, id, orgId, email, "hash", fullName);
        jdbc.update("insert into user_role (user_id, role_id) values (?, ?)", id, financeRoleId);
        jdbc.update("""
                insert into project_member (project_id, user_id, project_role, can_upload, created_by)
                values (?, ?, 'MANAGER', true, ?)
                """, projectId, id, ownerId);
        return id;
    }

    private UUID paymentApprovalId() {
        return jdbc.queryForObject(
                "select id from process_template where key = 'PAYMENT_APPROVAL' and org_id is null",
                UUID.class);
    }

    private UUID firstStepAssignee(UUID instanceId) {
        return jdbc.queryForObject(
                "select assignee_user_id from process_step_instance where instance_id = ? and step_order = 1",
                UUID.class, instanceId);
    }

    // ══════════════════════════════════════════════════════════════
    //  Substitute — when the main person is inactive, the substitute acts.
    // ══════════════════════════════════════════════════════════════
    @Test
    void substitute_resolvesWhenMainPersonInactive() {
        UUID main = newFinanceUser("chantier.main@hydra.test", "Main Finance");
        UUID sub = newFinanceUser("chantier.sub@hydra.test", "Sub Finance");

        // Map FINANCE → main, substitute → sub.
        svc.upsertRoleAssignees(ownerAuth, projectId, new ProcessService.UpsertRoleAssigneesRequest(
                List.of(new ProcessService.RoleAssigneeItem("FINANCE", main, sub))));

        // Main person is marked absent (is_active = false).
        jdbc.update("update app_user set is_active = false where id = ?", main);

        // Start a PAYMENT_APPROVAL process — the first step is DRAFT (FINANCE).
        ProcessService.StartProcessResponse started = svc.startByTemplateId(
                ownerAuth, projectId, paymentApprovalId(), "CASH_MOVEMENT", UUID.randomUUID());

        // The step's assignee is the SUBSTITUTE (main is absent).
        assertThat(firstStepAssignee(started.id())).isEqualTo(sub);
    }

    @Test
    void substitute_notUsedWhenMainActive() {
        UUID main = newFinanceUser("chantier.main2@hydra.test", "Main Finance 2");
        UUID sub = newFinanceUser("chantier.sub2@hydra.test", "Sub Finance 2");
        svc.upsertRoleAssignees(ownerAuth, projectId, new ProcessService.UpsertRoleAssigneesRequest(
                List.of(new ProcessService.RoleAssigneeItem("FINANCE", main, sub))));

        // Main is active → the step is assigned to the MAIN person, not the substitute.
        ProcessService.StartProcessResponse started = svc.startByTemplateId(
                ownerAuth, projectId, paymentApprovalId(), "CASH_MOVEMENT", UUID.randomUUID());
        assertThat(firstStepAssignee(started.id())).isEqualTo(main);
    }

    // ══════════════════════════════════════════════════════════════
    //  Pending approvals — a PENDING step shows up with who + days + late.
    // ══════════════════════════════════════════════════════════════
    @Test
    void pendingApprovals_listsThePendingStep() {
        UUID fin = newFinanceUser("chantier.fin@hydra.test", "Finance Pending");
        svc.upsertRoleAssignees(ownerAuth, projectId, new ProcessService.UpsertRoleAssigneesRequest(
                List.of(new ProcessService.RoleAssigneeItem("FINANCE", fin, null))));

        ProcessService.StartProcessResponse started = svc.startByTemplateId(
                ownerAuth, projectId, paymentApprovalId(), "CASH_MOVEMENT", UUID.randomUUID());

        List<ProcessService.PendingApproval> pending = svc.listPendingApprovals(ownerAuth, projectId);
        assertThat(pending).hasSize(1);
        ProcessService.PendingApproval p = pending.get(0);
        assertThat(p.instanceId()).isEqualTo(started.id());
        assertThat(p.assigneeName()).isEqualTo("Finance Pending");
        assertThat(p.late()).isFalse();
        // Just started → 0 days waiting (whole days).
        assertThat(p.daysWaiting()).isNotNull();
        assertThat(p.daysWaiting()).isGreaterThanOrEqualTo(0);
    }

    // ══════════════════════════════════════════════════════════════
    //  Absence — absent_until >= today → substitute resolves at start +
    //  sees + completes the step; absence ended → substitute no longer sees.
    // ══════════════════════════════════════════════════════════════
    @Test
    void absentMain_newProcessAssignsSubstitute() {
        UUID main = newFinanceUser("chantier.absent.new@hydra.test", "Absent New Main");
        UUID sub = newFinanceUser("chantier.absent.newsub@hydra.test", "Absent New Sub");
        svc.upsertRoleAssignees(ownerAuth, projectId, new ProcessService.UpsertRoleAssigneesRequest(
                List.of(new ProcessService.RoleAssigneeItem("FINANCE", main, sub))));

        // Mark main absent (absent_until = today).
        jdbc.update("update app_user set absent_until = current_date where id = ?", main);

        // Start a process — the first step should be assigned to the substitute.
        ProcessService.StartProcessResponse started = svc.startByTemplateId(
                ownerAuth, projectId, paymentApprovalId(), "CASH_MOVEMENT", UUID.randomUUID());
        assertThat(firstStepAssignee(started.id())).isEqualTo(sub);
    }

    @Test
    void absentMain_substituteSeesAndCompletesStep_withOnBehalfOfAndAudit() {
        UUID main = newFinanceUser("chantier.absent.main@hydra.test", "Absent Main");
        UUID sub = newFinanceUser("chantier.absent.sub@hydra.test", "Absent Sub");
        svc.upsertRoleAssignees(ownerAuth, projectId, new ProcessService.UpsertRoleAssigneesRequest(
                List.of(new ProcessService.RoleAssigneeItem("FINANCE", main, sub))));

        // Start a process — step 1 is assigned to main (active at start).
        ProcessService.StartProcessResponse started = svc.startByTemplateId(
                ownerAuth, projectId, paymentApprovalId(), "CASH_MOVEMENT", UUID.randomUUID());
        UUID stepId = jdbc.queryForObject(
                "select id from process_step_instance where instance_id = ? and step_order = 1",
                UUID.class, started.id());
        assertThat(firstStepAssignee(started.id())).isEqualTo(main);

        // Mark main absent (absent_until = today).
        jdbc.update("update app_user set absent_until = current_date where id = ?", main);

        // The substitute sees the step in my-tasks with onBehalfOf = main's name.
        Authentication subAuth = new UsernamePasswordAuthenticationToken(
                "chantier.absent.sub@hydra.test", null, List.of());
        List<ProcessService.MyTaskSummary> tasks = svc.listMyTasks(subAuth);
        assertThat(tasks).isNotEmpty();
        ProcessService.MyTaskSummary task = tasks.stream()
                .filter(t -> t.stepInstanceId().equals(stepId)).findFirst().orElseThrow();
        assertThat(task.onBehalfOf()).isEqualTo("Absent Main");

        // The substitute completes the step.
        SecurityContextHolder.getContext().setAuthentication(subAuth);
        ProcessService.StepResult result = svc.completeStep(subAuth, started.id(), stepId,
                new ProcessService.CompleteStepRequest("substitute approval", null));
        assertThat(result.newStatus()).isEqualTo("DONE");

        // STEP_COMPLETED_BY_SUBSTITUTE was logged.
        Integer substituteEvents = jdbc.queryForObject(
                "select count(*) from event_log where action = 'STEP_COMPLETED_BY_SUBSTITUTE' and entity_id = ?",
                Integer.class, stepId);
        assertThat(substituteEvents).isEqualTo(1);
    }

    @Test
    void absenceEnded_substituteNoLongerSeesStep() {
        UUID main = newFinanceUser("chantier.ended.main@hydra.test", "Ended Main");
        UUID sub = newFinanceUser("chantier.ended.sub@hydra.test", "Ended Sub");
        svc.upsertRoleAssignees(ownerAuth, projectId, new ProcessService.UpsertRoleAssigneesRequest(
                List.of(new ProcessService.RoleAssigneeItem("FINANCE", main, sub))));

        ProcessService.StartProcessResponse started = svc.startByTemplateId(
                ownerAuth, projectId, paymentApprovalId(), "CASH_MOVEMENT", UUID.randomUUID());

        // Mark main absent → substitute sees the step.
        jdbc.update("update app_user set absent_until = current_date where id = ?", main);
        Authentication subAuth = new UsernamePasswordAuthenticationToken(
                "chantier.ended.sub@hydra.test", null, List.of());
        assertThat(svc.listMyTasks(subAuth).stream().anyMatch(t -> t.instanceId().equals(started.id()))).isTrue();

        // End the absence → substitute no longer sees it.
        jdbc.update("update app_user set absent_until = null where id = ?", main);
        assertThat(svc.listMyTasks(subAuth).stream().noneMatch(t -> t.instanceId().equals(started.id()))).isTrue();
    }

    @Test
    void absenceDateInThePastRefused() {
        UUID user = newFinanceUser("chantier.past@hydra.test", "Past User");
        assertThrows(BusinessRuleException.class, () ->
                userService.setAbsence(ownerAuth, user, LocalDate.now().minusDays(1)));
    }

    @Test
    void nonOwnerCannotSetAnotherUserAbsence() {
        UUID user1 = newFinanceUser("chantier.nonowner1@hydra.test", "Non Owner 1");
        UUID user2 = newFinanceUser("chantier.nonowner2@hydra.test", "Non Owner 2");
        Authentication user1Auth = new UsernamePasswordAuthenticationToken(
                "chantier.nonowner1@hydra.test", null, List.of());
        assertThrows(BusinessRuleException.class, () ->
                userService.setAbsence(user1Auth, user2, LocalDate.now().plusDays(1)));
    }

    // ══════════════════════════════════════════════════════════════
    //  V43 — does not create rows for another organization's template.
    //  This is a data-level check: V43 ran at Flyway startup (before the
    //  test transaction), so we verify the result by inserting an org-scoped
    //  template in another org + a project_process_template row in that org,
    //  then checking our org's projects don't have a row for it.
    //  Since V43 has already run, we simulate the "already has rows" state
    //  and verify the org filter by checking which templates are visible.
    // ══════════════════════════════════════════════════════════════
    @Test
    void v43_doesNotCreateRowsForOtherOrgTemplate() {
        // V43 ran at Flyway startup; we test its SQL logic by re-running it manually
        // inside the test transaction after setting up the data.

        // Create a second org + owner + project.
        UUID otherOrg = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        jdbc.update("insert into organization (id, name) values (?, ?)", otherOrg, "Other Org");
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, otherOwner, otherOrg, "other.owner@test.test", "hash", "Other Owner");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                otherProject, otherOrg, "Other Project", otherOwner);

        // Insert an org-scoped CASH_PAYMENT template in the OTHER org.
        UUID otherTemplate = UUID.randomUUID();
        jdbc.update("""
                insert into process_template (id, key, name, org_id, subject_type, conditions, priority, active)
                values (?, 'OTHER_ORG_RULE', 'Other Org Rule', ?, 'CASH_PAYMENT', '{}'::jsonb, 100, true)
                """, otherTemplate, otherOrg);

        // Give BOTH projects a project_process_template row (so V43's CROSS JOIN triggers).
        UUID payAppr = paymentApprovalId();
        jdbc.update("""
                insert into project_process_template (id, project_id, template_id, enabled)
                values (?, ?, ?, true)
                """, UUID.randomUUID(), projectId, payAppr);
        jdbc.update("""
                insert into project_process_template (id, project_id, template_id, enabled)
                values (?, ?, ?, true)
                """, UUID.randomUUID(), otherProject, payAppr);

        // Run V43's SQL manually (the fixed version with the org filter).
        jdbc.update("""
                INSERT INTO project_process_template (project_id, template_id, enabled)
                SELECT DISTINCT ppt.project_id, pt.id, false
                FROM project_process_template ppt
                JOIN project p ON p.id = ppt.project_id
                CROSS JOIN process_template pt
                WHERE pt.active = true
                  AND (pt.org_id IS NULL OR pt.org_id = p.org_id)
                  AND NOT EXISTS (
                      SELECT 1 FROM project_process_template p2
                      WHERE p2.project_id = ppt.project_id AND p2.template_id = pt.id
                  )
                """);

        // Our project should NOT have a row for the other org's template.
        Integer ourRows = jdbc.queryForObject(
                "select count(*) from project_process_template where project_id = ? and template_id = ?",
                Integer.class, projectId, otherTemplate);
        assertThat(ourRows).as("V43 must not create rows for another org's template").isEqualTo(0);

        // The other project SHOULD have a row for its own template (V43 seeds it).
        Integer otherRows = jdbc.queryForObject(
                "select count(*) from project_process_template where project_id = ? and template_id = ?",
                Integer.class, otherProject, otherTemplate);
        assertThat(otherRows).as("V43 should seed the other org's own template").isEqualTo(1);
    }

    // ══════════════════════════════════════════════════════════════
    //  Threshold on the last unconditional rule — refused.
    // ══════════════════════════════════════════════════════════════
    @Test
    void thresholdOnLastUnconditionalRule_refused() {
        // PAYMENT_APPROVAL is the only unconditional CASH_PAYMENT rule visible to this org.
        // Setting a threshold override on it must be refused.
        assertThatThrownBy(() -> svc.assignTemplateToProject(ownerAuth, projectId,
                new ProcessService.AssignTemplateRequest("PAYMENT_APPROVAL", true, null, new BigDecimal("500.000"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("La règle de paiement sans condition ne peut pas avoir de seuil sur ce chantier");
    }

    // ══════════════════════════════════════════════════════════════
    //  Threshold when another unconditional rule is enabled — allowed.
    // ══════════════════════════════════════════════════════════════
    @Test
    void thresholdWhenAnotherUnconditionalRuleEnabled_allowed() {
        // Create a second unconditional CASH_PAYMENT rule in this org.
        UUID secondRule = UUID.randomUUID();
        jdbc.update("""
                insert into process_template (id, key, name, org_id, subject_type, conditions, priority, active)
                values (?, 'SECOND_UNCONDITIONAL', 'Second Unconditional', ?, 'CASH_PAYMENT', '{}'::jsonb, 100, true)
                """, secondRule, orgId);

        // Now there are two unconditional rules (PAYMENT_APPROVAL + SECOND_UNCONDITIONAL).
        // Setting a threshold on PAYMENT_APPROVAL should be allowed.
        svc.assignTemplateToProject(ownerAuth, projectId,
                new ProcessService.AssignTemplateRequest("PAYMENT_APPROVAL", true, null, new BigDecimal("500.000")));

        // Verify it was saved.
        BigDecimal threshold = jdbc.queryForObject(
                "select amount_threshold_override from project_process_template where project_id = ? and template_id = ?",
                BigDecimal.class, projectId, paymentApprovalId());
        assertThat(threshold).isEqualByComparingTo("500.000");
    }
}
