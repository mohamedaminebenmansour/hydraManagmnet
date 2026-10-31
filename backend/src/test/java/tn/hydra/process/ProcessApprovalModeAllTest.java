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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ============================================================================
 *  ProcessApprovalModeAllTest — point 5: a step with approval_mode = 'ALL' is
 *  done only when every user holding the role on the chantier has approved.
 * ============================================================================
 *
 *  Setup: a chantier with TWO finance users (project_member + user_role on the
 *  seeded system FINANCE role). A 1-step template whose step is FINANCE + ALL.
 *  The owner starts the process; each finance user approves in turn:
 *   * after the first approval  -> step still PENDING, instance still RUNNING;
 *   * after the second approval -> step DONE, instance COMPLETED.
 *
 *  project_role_assignee is unique per (project, role) (V27) so it cannot hold
 *  two FINANCE users; the required-approvers set is therefore the role-holders
 *  (project_member ∩ user_role+role.code), unioned with pra — see
 *  ProcessService.completeStep.
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class ProcessApprovalModeAllTest {

    @Autowired private ProcessService processService;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID ownerId;
    private UUID projectId;
    private UUID financeRoleId;
    private UUID f1;
    private UUID f2;
    private UUID templateId;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        f1 = UUID.randomUUID();
        f2 = UUID.randomUUID();

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "ALL Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, ownerId, orgId, "all.owner." + ownerId + "@hydra.test", "hash", "ALL Owner");
        // Two finance users — emails sort alphabetically (f1 < f2) for deterministic
        // assignee resolution, but the test is order-independent.
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, false, true)
                """, f1, orgId, "all-finance-1@hydra.test", "hash", "Finance One");
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, false, true)
                """, f2, orgId, "all-finance-2@hydra.test", "hash", "Finance Two");

        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "ALL Chantier " + projectId, ownerId);

        financeRoleId = jdbc.queryForObject(
                "select id from role where code = 'FINANCE' and org_id is null", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id) values (?, ?)", f1, financeRoleId);
        jdbc.update("insert into user_role (user_id, role_id) values (?, ?)", f2, financeRoleId);
        jdbc.update("""
                insert into project_member (project_id, user_id, project_role, can_upload, created_by)
                values (?, ?, 'MANAGER', true, ?)
                """, projectId, f1, ownerId);
        jdbc.update("""
                insert into project_member (project_id, user_id, project_role, can_upload, created_by)
                values (?, ?, 'MANAGER', true, ?)
                """, projectId, f2, ownerId);

        // Org-scoped template, 1 ALL/FINANCE step. subject_type CASH_PAYMENT is
        // irrelevant here (startByTemplateId loads by id); the INSTANCE subject
        // is 'ALL_TEST' so the cash hook does not fire on completion.
        templateId = UUID.randomUUID();
        jdbc.update("""
                insert into process_template (id, key, name, org_id, subject_type, conditions, priority, active)
                values (?, ?, ?, ?, 'CASH_PAYMENT', '{}', 100, true)
                """, templateId, "ALL_TEST_" + templateId, "ALL test", orgId);
        jdbc.update("""
                insert into process_template_step
                  (template_id, step_order, step_key, name, assignee_role, requires_attachment, approval_mode)
                values (?, 1, 'APPROVE', 'Approbation', 'FINANCE', false, 'ALL')
                """, templateId);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private Authentication as(UUID userId) {
        String email = jdbc.queryForObject("select email from app_user where id = ?", String.class, userId);
        Authentication a = new UsernamePasswordAuthenticationToken(email, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(a);
        return a;
    }

    private UUID pendingStep(UUID instanceId) {
        return jdbc.queryForObject(
                "select id from process_step_instance where instance_id = ? and status = 'PENDING' " +
                        "order by step_order asc limit 1",
                UUID.class, instanceId);
    }

    @Test
    void allStep_pendingAfterFirstApproval_doneAfterSecond() {
        // Owner starts the process.
        Authentication ownerAuth = as(ownerId);
        UUID subjectId = UUID.randomUUID();
        ProcessService.StartProcessResponse started = processService.startByTemplateId(
                ownerAuth, projectId, templateId, "ALL_TEST", subjectId);
        UUID instanceId = started.id();
        UUID stepId = pendingStep(instanceId);

        // First finance user approves -> step stays PENDING.
        ProcessService.StepResult r1 = processService.completeStep(
                as(f1), instanceId, stepId, new ProcessService.CompleteStepRequest("ok 1", null));
        assertThat(r1.newStatus()).isEqualTo("PENDING");
        String stepStatus1 = jdbc.queryForObject(
                "select status from process_step_instance where id = ?", String.class, stepId);
        assertThat(stepStatus1).isEqualTo("PENDING");
        String instanceStatus1 = jdbc.queryForObject(
                "select status from process_instance where id = ?", String.class, instanceId);
        assertThat(instanceStatus1).isEqualTo("RUNNING");
        // The first approval was recorded.
        Integer approvalCount = jdbc.queryForObject(
                "select count(*) from process_step_approval where step_instance_id = ?",
                Integer.class, stepId);
        assertThat(approvalCount).isEqualTo(1);

        // Second finance user approves -> step DONE, instance COMPLETED.
        ProcessService.StepResult r2 = processService.completeStep(
                as(f2), instanceId, stepId, new ProcessService.CompleteStepRequest("ok 2", null));
        assertThat(r2.newStatus()).isEqualTo("DONE");
        String stepStatus2 = jdbc.queryForObject(
                "select status from process_step_instance where id = ?", String.class, stepId);
        assertThat(stepStatus2).isEqualTo("DONE");
        String instanceStatus2 = jdbc.queryForObject(
                "select status from process_instance where id = ?", String.class, instanceId);
        assertThat(instanceStatus2).isEqualTo("COMPLETED");
        Integer approvalCount2 = jdbc.queryForObject(
                "select count(*) from process_step_approval where step_instance_id = ?",
                Integer.class, stepId);
        assertThat(approvalCount2).isEqualTo(2);
    }
}
