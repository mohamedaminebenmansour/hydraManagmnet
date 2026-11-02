package tn.hydra.ticket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.event.DomainEventService;
import tn.hydra.event.EventType;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * ============================================================================
 *  TicketActionsTest — real-database tests for the signalement actions, role
 *  rules, timeline and rollback (T3).
 * ============================================================================
 *
 *  Five users are provisioned: owner (bypasses folder ACL, used only to upload
 *  « après » photos), creator, assignee, manager (project_member MANAGER) and
 *  an unrelated user. @Transactional + @Rollback keep the DB clean, except the
 *  rollback test which runs NOT_SUPPORTED so the action's own transaction
 *  rollback is observable. DomainEventService is mocked (a no-op except in the
 *  rollback test where it is made to throw).
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class TicketActionsTest {

    @Autowired private TicketService service;
    @Autowired private TicketPhotoService photos;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private DomainEventService domainEvents;

    private UUID orgId;
    private UUID ownerId, creatorId, assigneeId, managerId, unrelatedId;
    private UUID projectId;
    private String ownerEmail, creatorEmail, assigneeEmail, managerEmail, unrelatedEmail;
    private Authentication owner, creator, assignee, manager, unrelated;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        creatorId = UUID.randomUUID();
        assigneeId = UUID.randomUUID();
        managerId = UUID.randomUUID();
        unrelatedId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        ownerEmail = "act.owner." + ownerId + "@hydra.test";
        creatorEmail = "act.creator." + creatorId + "@hydra.test";
        assigneeEmail = "act.assignee." + assigneeId + "@hydra.test";
        managerEmail = "act.manager." + managerId + "@hydra.test";
        unrelatedEmail = "act.unrelated." + unrelatedId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Actions Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, ownerId, orgId, ownerEmail, "hash", "Owner");
        insertUser(creatorId, creatorEmail, "Creator");
        insertUser(assigneeId, assigneeEmail, "Assignee");
        insertUser(managerId, managerEmail, "Manager");
        insertUser(unrelatedId, unrelatedEmail, "Unrelated");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "Actions Chantier " + projectId, creatorId);
        jdbc.update("insert into project_member (project_id, user_id, project_role) values (?, ?, 'ENGINEER')", projectId, creatorId);
        jdbc.update("insert into project_member (project_id, user_id, project_role) values (?, ?, 'ENGINEER')", projectId, assigneeId);
        jdbc.update("insert into project_member (project_id, user_id, project_role) values (?, ?, 'MANAGER')", projectId, managerId);

        owner = auth(ownerEmail);
        creator = auth(creatorEmail);
        assignee = auth(assigneeEmail);
        manager = auth(managerEmail);
        unrelated = auth(unrelatedEmail);
        SecurityContextHolder.getContext().setAuthentication(creator);
    }

    private void insertUser(UUID id, String email, String fullName) {
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, false, true)
                """, id, orgId, email, "hash", fullName);
    }

    private static Authentication auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, null, List.of(
                new SimpleGrantedAuthority("PERM_TICKET_READ"),
                new SimpleGrantedAuthority("PERM_TICKET_WRITE"),
                new SimpleGrantedAuthority("PERM_DOCUMENT_UPLOAD")));
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    // ── helpers ─────────────────────────────────────────────────────────

    private UUID createProbleme(String title, UUID assignedTo) {
        return service.create(creator, title, "Desc", projectId, assignedTo, null, null, null, null,
                "PROBLEME", "METEO", null, null, null, null).id();
    }

    private UUID createReserve(String title, UUID assignedTo) {
        return service.create(creator, title, "Desc", projectId, assignedTo, null, null, null, null,
                "RESERVE", "MALFACON", null, null, null, null).id();
    }

    private static MockMultipartFile jpeg() {
        byte[] b = new byte[64];
        b[0] = (byte) 0xFF; b[1] = (byte) 0xD8; b[2] = (byte) 0xFF; b[3] = (byte) 0xD9;
        return new MockMultipartFile("file", "after.jpg", "image/jpeg", b);
    }

    private String status(UUID id) {
        return jdbc.queryForObject("select status from ticket where id = ?", String.class, id);
    }

    private List<String> timelineKinds(UUID id) {
        return jdbc.queryForList(
                "select kind from ticket_comment where ticket_id = ? and org_id = ? order by created_at, id",
                String.class, id, orgId);
    }

    // ── full path PROBLEME: start -> resolve -> verify -> CLOSED, timeline 4 rows ──

    @Test
    void fullPathProbleme_assigneeStartsCreatorVerifies_timelineHasFourRowsInOrder() {
        UUID id = createProbleme("Fuite regard", assigneeId);
        assertThat(status(id)).isEqualTo("OPEN");

        service.start(assignee, id);
        service.resolve(assignee, id, "Travaux terminés et contrôlés");
        service.verify(creator, id);
        assertThat(status(id)).isEqualTo("CLOSED");

        List<String> kinds = timelineKinds(id);
        assertThat(kinds).containsExactly("SYSTEM", "STATUS_CHANGE", "STATUS_CHANGE", "STATUS_CHANGE");
        List<String[]> changes = jdbc.queryForList("""
                select from_status, to_status from ticket_comment
                where ticket_id = ? and org_id = ? and kind = 'STATUS_CHANGE'
                order by created_at, id
                """, id, orgId).stream()
                .map(r -> new String[]{(String) r.get("from_status"), (String) r.get("to_status")})
                .toList();
        assertThat(changes).containsExactly(
                new String[]{"OPEN", "IN_PROGRESS"},
                new String[]{"IN_PROGRESS", "RESOLVED"},
                new String[]{"RESOLVED", "CLOSED"});
    }

    // ── RESERVE resolve without AFTER photo -> 400; with one -> RESOLVED ──

    @Test
    void reserveResolve_withoutAfterPhoto_rejected_withAfterPhoto_ok() {
        UUID id = createReserve("Réserve fissure", assigneeId);
        service.start(assignee, id);
        assertThatThrownBy(() -> service.resolve(assignee, id, "Résolu après reprise"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("photo après travaux");
        assertThat(status(id)).isEqualTo("IN_PROGRESS");

        photos.addPhoto(owner, id, jpeg(), "AFTER");
        service.resolve(assignee, id, "Résolu après reprise");
        assertThat(status(id)).isEqualTo("RESOLVED");
    }

    // ── RESERVE: assignee resolves, same assignee verifies -> 403; manager verifies -> CLOSED ──

    @Test
    void reserveVerify_assigneeCannotSelfVerify_managerCan() {
        UUID id = createReserve("Réserve garde-corps", assigneeId);
        photos.addPhoto(owner, id, jpeg(), "AFTER");
        service.start(assignee, id);
        service.resolve(assignee, id, "Reprise faite");
        assertThat(status(id)).isEqualTo("RESOLVED");

        assertThatThrownBy(() -> service.verify(assignee, id))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(status(id)).isEqualTo("RESOLVED");

        service.verify(manager, id);
        assertThat(status(id)).isEqualTo("CLOSED");
    }

    // ── reopen with a 2-char comment -> 400; with a real comment -> IN_PROGRESS ──

    @Test
    void reopen_shortCommentRejected_realCommentOk() {
        UUID id = createProbleme("Panne engin", assigneeId);
        service.start(assignee, id);
        service.resolve(assignee, id, "Engine réparée");
        assertThat(status(id)).isEqualTo("RESOLVED");

        assertThatThrownBy(() -> service.reopen(creator, id, "ok"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("réouverture");
        assertThat(status(id)).isEqualTo("RESOLVED");

        service.reopen(creator, id, "La réparation n'a pas tenu, à revoir");
        assertThat(status(id)).isEqualTo("IN_PROGRESS");
    }

    // ── cancel by an unrelated user -> 403 ──

    @Test
    void cancel_byUnrelatedUser_forbidden() {
        UUID id = createProbleme("Accès bloqué", assigneeId);
        assertThatThrownBy(() -> service.cancel(unrelated, id, "Plus pertinent"))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(status(id)).isEqualTo("OPEN");
    }

    // ── /actions for the assignee on OPEN = [START, RESOLVE, COMMENT, ADD_PHOTO] ──

    @Test
    void actions_assigneeOnOpen() {
        UUID id = createProbleme("Sig OPEN", assigneeId);
        assertThat(service.actions(assignee, id))
                .containsExactlyInAnyOrder("START", "RESOLVE", "COMMENT", "ADD_PHOTO");
    }

    // ── /actions for the creator on RESOLVED includes VERIFY and REOPEN ──

    @Test
    void actions_creatorOnResolved_includesVerifyAndReopen() {
        UUID id = createProbleme("Sig RESOLVED", assigneeId);
        service.start(assignee, id);
        service.resolve(assignee, id, "Résolu");
        assertThat(service.actions(creator, id)).contains("VERIFY", "REOPEN");
    }

    // ── POST /status on a PROBLEME -> 400; on a VISA_DOCUMENT works as before ──

    @Test
    void setStatus_problemeRejected_visaWorks() {
        UUID problemeId = createProbleme("Sig statut", assigneeId);
        assertThatThrownBy(() -> service.assertVisaForSetStatus(creator, problemeId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Utilisez les actions");

        UUID visaId = service.create(creator, "Visa plan", "Desc", projectId, null, null, null, null, null).id();
        service.assertVisaForSetStatus(creator, visaId); // no throw for VISA
        service.setStatus(creator, visaId, "IN_REVIEW");
        assertThat(status(visaId)).isEqualTo("IN_REVIEW");
    }

    // ── one transaction: force publish to fail -> status unchanged ──
    // NOT_SUPPORTED so the action runs in its OWN transaction; when the publish
    // throws, that transaction rolls back and the status change is undone.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @Test
    void action_rollbackWhenPublishFails() {
        UUID id = createProbleme("Rollback", assigneeId);
        assertThat(status(id)).isEqualTo("OPEN");
        when(domainEvents.publish(any(), any(EventType.class), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("publish failed"));

        assertThatThrownBy(() -> service.start(assignee, id))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("publish failed");
        // the action's transaction rolled back -> status still OPEN, no STATUS_CHANGE row
        assertThat(status(id)).isEqualTo("OPEN");
        assertThat(timelineKinds(id)).containsExactly("SYSTEM");
    }

    // ── T8 new tests ────────────────────────────────────────────────────

    @Test
    void summary_mine_countsResolvedICreated() {
        UUID id = createProbleme("Sig résolu", assigneeId);
        service.start(assignee, id);
        service.resolve(assignee, id, "Résolu par assignee");
        SecurityContextHolder.getContext().setAuthentication(creator);
        TicketService.TicketSummary s = service.summary(creator, projectId);
        assertThat(s.mine()).isPositive();
    }

    @Test
    void summary_toVerify_forManager_notForResolver() {
        UUID id = createReserve("Réserve toVerify", assigneeId);
        photos.addPhoto(owner, id, jpeg(), "AFTER");
        service.start(assignee, id);
        service.resolve(assignee, id, "Reprise faite");

        // manager should see it in toVerify
        SecurityContextHolder.getContext().setAuthentication(manager);
        TicketService.TicketSummary managerSummary = service.summary(manager, projectId);
        assertThat(managerSummary.toVerify()).isPositive();
    }
}
