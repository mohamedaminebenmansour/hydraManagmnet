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
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.ResourceNotFoundException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ============================================================================
 *  TicketTaskTest — real-database tests for the TACHE type (T9 Part A).
 * ============================================================================
 *
 *  Covers: TACHE create rules (projectId optional, assignee required, isPrivate
 *  TACHE-only, forbidden fields), the central visibility rule (canRead) for
 *  private and no-project tasks, resolve+verify flow, the deliverable file
 *  endpoint, and summary.byType including TACHE.
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class TicketTaskTest {

    @Autowired private TicketService tickets;
    @Autowired private TicketPhotoService photos;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID ownerId, creatorId, assigneeId, member2Id, strangerId;
    private UUID projectId;
    private String ownerEmail, creatorEmail, assigneeEmail, member2Email, strangerEmail;
    private Authentication owner, creator, assignee, member2, stranger;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        creatorId = UUID.randomUUID();
        assigneeId = UUID.randomUUID();
        member2Id = UUID.randomUUID();
        strangerId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        ownerEmail = "task.owner." + ownerId + "@hydra.test";
        creatorEmail = "task.creator." + creatorId + "@hydra.test";
        assigneeEmail = "task.assignee." + assigneeId + "@hydra.test";
        member2Email = "task.m2." + member2Id + "@hydra.test";
        strangerEmail = "task.stranger." + strangerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Task Org " + orgId);
        insertUser(ownerId, ownerEmail, "Owner", true);
        insertUser(creatorId, creatorEmail, "Creator", false);
        insertUser(assigneeId, assigneeEmail, "Assignee", false);
        insertUser(member2Id, member2Email, "Member2", false);
        insertUser(strangerId, strangerEmail, "Stranger", false);
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "Task Chantier " + projectId, creatorId);
        jdbc.update("insert into project_member (project_id, user_id, project_role) values (?, ?, 'MANAGER')", projectId, creatorId);
        jdbc.update("insert into project_member (project_id, user_id, project_role) values (?, ?, 'ENGINEER')", projectId, assigneeId);
        jdbc.update("insert into project_member (project_id, user_id, project_role) values (?, ?, 'ENGINEER')", projectId, member2Id);

        owner = auth(ownerEmail);
        creator = auth(creatorEmail);
        assignee = auth(assigneeEmail);
        member2 = auth(member2Email);
        stranger = auth(strangerEmail);
        SecurityContextHolder.getContext().setAuthentication(creator);
    }

    private void insertUser(UUID id, String email, String name, boolean isOwner) {
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, ?, true)
                """, id, orgId, email, "hash", name, isOwner);
    }

    private static Authentication auth(String email) {
        return new UsernamePasswordAuthenticationToken(email, null, List.of(
                new SimpleGrantedAuthority("PERM_TICKET_READ"),
                new SimpleGrantedAuthority("PERM_TICKET_WRITE"),
                new SimpleGrantedAuthority("PERM_DOCUMENT_UPLOAD")));
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    // ── A3: TACHE create rules ────────────────────────────────────────

    @Test
    void tacheWithoutProjectAndAssignee_isOpenWithDueTodayPlus3() {
        TicketService.TicketResponse t = tickets.create(creator,
                "[Test] Préparer le rapport", "Desc",
                null, assigneeId, null, null, null, null,
                "TACHE", "DOCUMENT", null, null, null, null, false);
        assertThat(t.id()).isNotNull();
        assertThat(t.type()).isEqualTo("TACHE");
        assertThat(t.status()).isEqualTo("OPEN");
        assertThat(t.projectId()).isNull();
        assertThat(t.dueAt()).isEqualTo(LocalDate.now().plusDays(3)); // default delay = 3
        assertThat(t.isPrivate()).isFalse();
    }

    @Test
    void tacheWithoutAssignee_rejected400() {
        assertThatThrownBy(() -> tickets.create(creator,
                "[Test] Tâche sans assigné", "Desc",
                projectId, null, null, null, null, null,
                "TACHE", "DOCUMENT", null, null, null, null, false))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("assignée");
    }

    @Test
    void tacheWithOuvrageId_rejected400() {
        assertThatThrownBy(() -> tickets.create(creator,
                "[Test] Tâche avec ouvrage", "Desc",
                projectId, assigneeId, null, null, null, null,
                "TACHE", "DOCUMENT", null, null, UUID.randomUUID(), null, false))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ouvrage");
    }

    @Test
    void tacheWithOrigin_rejected400() {
        assertThatThrownBy(() -> tickets.create(creator,
                "[Test] Tâche avec origine", "Desc",
                projectId, assigneeId, null, null, null, null,
                "TACHE", "DOCUMENT", "CLIENT", null, null, null, false))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("origine");
    }

    @Test
    void nonTacheWithIsPrivate_rejected400() {
        assertThatThrownBy(() -> tickets.create(creator,
                "[Test] Problème privé", "Desc",
                projectId, assigneeId, null, null, null, null,
                "PROBLEME", "METEO", null, null, null, null, true))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("privée");
    }

    // ── A4: visibility ─────────────────────────────────────────────────

    @Test
    void privateTache_creatorAssigneeOwner_canGet_it404ForMember2_absentFromSearchAndSummary() {
        TicketService.TicketResponse t = tickets.create(creator,
                "[Test] Tâche privée", "Desc",
                projectId, assigneeId, null, null, null, null,
                "TACHE", "FINANCE", null, null, null, null, true);
        assertThat(t.isPrivate()).isTrue();

        // creator, assignee, owner can get it
        assertThat(tickets.get(creator, t.id())).isNotNull();
        assertThat(tickets.get(assignee, t.id())).isNotNull();
        assertThat(tickets.get(owner, t.id())).isNotNull();

        // member2 of the same project gets 404 (null) — private task not revealed
        assertThat(tickets.get(member2, t.id())).isNull();

        // absent from member2's search
        TicketService.TicketSearchPage page = tickets.search(member2, projectId, null, null, null, null, null, 0, 20);
        assertThat(page.items()).extracting(i -> i.ticket().title()).doesNotContain("[Test] Tâche privée");

        // absent from member2's summary counts
        TicketService.TicketSummary s = tickets.summary(member2, projectId);
        assertThat(s.mine()).isZero();
    }

    @Test
    void tacheWithoutProject_invisibleToStranger() {
        TicketService.TicketResponse t = tickets.create(creator,
                "[Test] Tâche bureau", "Desc",
                null, assigneeId, null, null, null, null,
                "TACHE", "DOCUMENT", null, null, null, null, false);
        // stranger is neither creator, assignee, nor owner, nor a project member (no project anyway)
        assertThat(tickets.get(stranger, t.id())).isNull();
        TicketService.TicketSearchPage page = tickets.search(stranger, null, null, null, null, null, null, 0, 50);
        assertThat(page.items()).extracting(i -> i.ticket().title()).doesNotContain("[Test] Tâche bureau");
    }

    // ── A5: resolve + verify ──────────────────────────────────────────

    @Test
    void assigneeResolvesAndCreatorVerifies_tacheGoesToClosed() {
        TicketService.TicketResponse t = tickets.create(creator,
                "[Test] Tâche à clore", "Desc",
                projectId, assigneeId, null, null, null, null,
                "TACHE", "DOCUMENT", null, null, null, null, false);
        tickets.start(assignee, t.id());
        tickets.resolve(assignee, t.id(), "Travail terminé");
        assertThat(statusOf(t.id())).isEqualTo("RESOLVED");
        tickets.verify(creator, t.id());
        assertThat(statusOf(t.id())).isEqualTo("CLOSED");
    }

    @Test
    void assigneeMayVerifyOwnTacheWhenHeIsTheCreator() {
        // assignee IS the creator here
        TicketService.TicketResponse t = tickets.create(assignee,
                "[Test] Tâche auto-vérifiable", "Desc",
                projectId, assigneeId, null, null, null, null,
                "TACHE", "DOCUMENT", null, null, null, null, false);
        tickets.start(assignee, t.id());
        tickets.resolve(assignee, t.id(), "Fait par moi-même");
        // assignee (= creator) verifies — allowed for TACHE (not RESERVE)
        tickets.verify(assignee, t.id());
        assertThat(statusOf(t.id())).isEqualTo("CLOSED");
    }

    // ── A6: deliverable file ──────────────────────────────────────────

    @Test
    void deliverable_byAssignee_createsDeliverableRowInCorrectFolder() {
        TicketService.TicketResponse t = tickets.create(creator,
                "[Test] Tâche livrable", "Desc",
                projectId, assigneeId, null, null, null, null,
                "TACHE", "DOCUMENT", null, null, null, null, false);
        MockMultipartFile pdf = new MockMultipartFile("file", "livrable.pdf", "application/pdf",
                "%PDF-1.4 test".getBytes());
        TicketPhotoService.PhotoResponse att = photos.addDeliverable(assignee, t.id(), pdf);
        assertThat(att.phase()).isEqualTo("DELIVERABLE");

        // the document is filed under 02_TECH (category DOCUMENT)
        Integer n = jdbc.queryForObject("""
                select count(*) from ticket_attachment ta
                join document d on d.id = ta.document_id
                join doc_folder f on f.id = d.folder_id
                where ta.id = ? and f.code = '02_TECH'
                """, Integer.class, att.id());
        assertThat(n).isEqualTo(1);
    }

    @Test
    void deliverable_onTaskWithoutProject_rejected400() {
        TicketService.TicketResponse t = tickets.create(creator,
                "[Test] Tâche bureau livrable", "Desc",
                null, assigneeId, null, null, null, null,
                "TACHE", "DOCUMENT", null, null, null, null, false);
        MockMultipartFile pdf = new MockMultipartFile("file", "x.pdf", "application/pdf", "x".getBytes());
        assertThatThrownBy(() -> photos.addDeliverable(assignee, t.id(), pdf))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("chantier");
    }

    @Test
    void deliverable_onClosedTask_rejected400() {
        TicketService.TicketResponse t = tickets.create(creator,
                "[Test] Tâche close livrable", "Desc",
                projectId, assigneeId, null, null, null, null,
                "TACHE", "DOCUMENT", null, null, null, null, false);
        tickets.start(assignee, t.id());
        tickets.resolve(assignee, t.id(), "Travail fait");
        tickets.verify(creator, t.id());
        assertThat(statusOf(t.id())).isEqualTo("CLOSED");
        MockMultipartFile pdf = new MockMultipartFile("file", "x.pdf", "application/pdf", "x".getBytes());
        assertThatThrownBy(() -> photos.addDeliverable(assignee, t.id(), pdf))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("clos");
    }

    @Test
    void deliverable_byUnrelatedUser_404() {
        TicketService.TicketResponse t = tickets.create(creator,
                "[Test] Tâche livrable unrelated", "Desc",
                projectId, assigneeId, null, null, null, null,
                "TACHE", "DOCUMENT", null, null, null, null, false);
        MockMultipartFile pdf = new MockMultipartFile("file", "x.pdf", "application/pdf", "x".getBytes());
        // stranger is not a project member — requireReadAccess throws AccessDeniedException
        assertThatThrownBy(() -> photos.addDeliverable(stranger, t.id(), pdf))
                .isInstanceOf(AccessDeniedException.class);
    }

    // ── summary.byType has TACHE ───────────────────────────────────────

    @Test
    void summary_byTypeHasTacheKey() {
        TicketService.TicketSummary s = tickets.summary(creator, projectId);
        assertThat(s.byType()).containsKey("TACHE");
    }

    // ── helpers ────────────────────────────────────────────────────────

    private String statusOf(UUID id) {
        return jdbc.queryForObject("select status from ticket where id = ?", String.class, id);
    }
}
