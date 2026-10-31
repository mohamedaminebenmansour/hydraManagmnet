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
 *  MyTasksLateTest — point 6: the my-tasks response carries late = true when a
 *  PENDING step started more than due_days days ago.
 * ============================================================================
 *
 *  A step started 3 days ago:
 *   * with due_days = 2 -> late = true  (3 > 2);
 *   * with due_days = 5 -> late = false (3 < 5).
 *
 *  The owner's listMyTasks sees every PENDING step in the org (isOwner branch),
 *  so each test provisions its own template + step and back-dates started_at.
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class MyTasksLateTest {

    @Autowired private ProcessService processService;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID ownerId;
    private UUID projectId;
    private String ownerEmail;
    private Authentication ownerAuth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        ownerEmail = "late.owner." + ownerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Late Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, ownerId, orgId, ownerEmail, "hash", "Late Owner");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "Late Chantier " + projectId, ownerId);

        ownerAuth = new UsernamePasswordAuthenticationToken(ownerEmail, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Create an org-scoped 1-step template with the given due_days, start a
     *  process, back-date the step's started_at to 3 days ago, return the step
     *  instance id. */
    private UUID backDatedPendingStep(int dueDays) {
        UUID templateId = UUID.randomUUID();
        jdbc.update("""
                insert into process_template (id, key, name, org_id, subject_type, conditions, priority, active)
                values (?, ?, ?, ?, 'CASH_PAYMENT', '{}', 100, true)
                """, templateId, "LATE_TEST_" + templateId, "Late test", orgId);
        jdbc.update("""
                insert into process_template_step
                  (template_id, step_order, step_key, name, assignee_role, requires_attachment, due_days)
                values (?, 1, 'REVIEW', 'Revue', 'FINANCE', false, ?)
                """, templateId, dueDays);

        ProcessService.StartProcessResponse started = processService.startByTemplateId(
                ownerAuth, projectId, templateId, "LATE_TEST", UUID.randomUUID());
        UUID stepId = jdbc.queryForObject(
                "select id from process_step_instance where instance_id = ? and status = 'PENDING' limit 1",
                UUID.class, started.id());
        // Back-date started_at to 3 days ago.
        jdbc.update("update process_step_instance set started_at = now() - interval '3 days' where id = ?", stepId);
        return stepId;
    }

    private boolean lateFlagFor(UUID stepId) {
        return processService.listMyTasks(ownerAuth).stream()
                .filter(t -> stepId.equals(t.stepInstanceId()))
                .findFirst()
                .map(ProcessService.MyTaskSummary::late)
                .orElseThrow(() -> new AssertionError("step " + stepId + " not in my-tasks"));
    }

    @Test
    void stepStarted3DaysAgo_dueDays2_isLate() {
        UUID stepId = backDatedPendingStep(2);
        assertThat(lateFlagFor(stepId)).as("3 days > due_days 2 -> late").isTrue();
    }

    @Test
    void stepStarted3DaysAgo_dueDays5_isNotLate() {
        UUID stepId = backDatedPendingStep(5);
        assertThat(lateFlagFor(stepId)).as("3 days < due_days 5 -> not late").isFalse();
    }
}
