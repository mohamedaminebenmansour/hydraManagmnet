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
import org.springframework.web.multipart.MultipartFile;
import tn.hydra.common.BusinessRuleException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ============================================================================
 *  TicketPhotoTest — real-database tests for photos, create-with-photos,
 *  search and summary (T2).
 * ============================================================================
 *
 *  @SpringBootTest against hydra_test; @Transactional + @Rollback keep the DB
 *  clean and delete the stored photo files on rollback (TicketPhotoService
 *  registers a cleanup synchronization per stored file, like BordereauImport).
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class TicketPhotoTest {

    @Autowired private TicketPhotoService photoService;
    @Autowired private TicketService tickets;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID ownerId;
    private UUID otherUserId;
    private UUID projectId;
    private String ownerEmail;
    private String otherEmail;
    private Authentication auth;
    private Authentication otherAuth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        otherUserId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        ownerEmail = "photo.owner." + ownerId + "@hydra.test";
        otherEmail = "photo.other." + otherUserId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Photo Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, ownerId, orgId, ownerEmail, "hash", "Photo Owner");
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, false, true)
                """, otherUserId, orgId, otherEmail, "hash", "Photo Other");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "Photo Chantier " + projectId, ownerId);

        auth = new UsernamePasswordAuthenticationToken(ownerEmail, null, List.of(
                new SimpleGrantedAuthority("PERM_TICKET_READ"),
                new SimpleGrantedAuthority("PERM_TICKET_WRITE"),
                new SimpleGrantedAuthority("PERM_DOCUMENT_UPLOAD"),
                new SimpleGrantedAuthority("PERM_DOCUMENT_READ")));
        otherAuth = new UsernamePasswordAuthenticationToken(otherEmail, null, List.of(
                new SimpleGrantedAuthority("PERM_TICKET_READ"),
                new SimpleGrantedAuthority("PERM_TICKET_WRITE")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** A small JPEG-ish byte array (content is not validated; extension + MIME are). */
    private static MockMultipartFile jpeg(String name) {
        byte[] b = new byte[64];
        b[0] = (byte) 0xFF; b[1] = (byte) 0xD8; b[2] = (byte) 0xFF; b[3] = (byte) 0xD9;
        return new MockMultipartFile("photos", name, "image/jpeg", b);
    }

    private static MockMultipartFile textFile(String name) {
        return new MockMultipartFile("photos", name, "text/plain", "not an image".getBytes());
    }

    /** Count ticket_attachment rows for a ticket, optionally filtered by phase. */
    private int attachments(UUID ticketId, String phase) {
        String sql = phase == null
                ? "select count(*) from ticket_attachment where ticket_id = ? and org_id = ?"
                : "select count(*) from ticket_attachment where ticket_id = ? and org_id = ? and phase = ?";
        Integer n = phase == null
                ? jdbc.queryForObject(sql, Integer.class, ticketId, orgId)
                : jdbc.queryForObject(sql, Integer.class, ticketId, orgId, phase);
        return n == null ? 0 : n;
    }

    /** Count documents of a ticket filed under a given folder code. */
    private int documentsInFolder(UUID ticketId, String folderCode) {
        Integer n = jdbc.queryForObject("""
                select count(*) from document d
                join doc_folder f on f.id = d.folder_id
                where d.ticket_id = ? and d.org_id = ? and f.code = ? and d.deleted_at is null
                """, Integer.class, ticketId, orgId, folderCode);
        return n == null ? 0 : n;
    }

    private int ticketCountByTitle(String title) {
        Integer n = jdbc.queryForObject(
                "select count(*) from ticket where org_id = ? and title = ?", Integer.class, orgId, title);
        return n == null ? 0 : n;
    }

    // ── with-photos: 2 JPEGs -> ticket + 2 BEFORE attachments + 2 documents in 04_QUAL.RESERVES ──

    @Test
    void withPhotos_twoJpegs_createsTicketAndBeforeAttachmentsInReserveFolder() {
        TicketService.TicketResponse t = photoService.createWithPhotos(auth,
                "Réserve fissure", "Desc", projectId, null, null, null, null, null,
                "RESERVE", "MALFACON", null, null, null, null,
                List.of(jpeg("p1.jpg"), jpeg("p2.jpg")));

        assertThat(t.id()).isNotNull();
        assertThat(t.type()).isEqualTo("RESERVE");
        assertThat(t.status()).isEqualTo("OPEN");

        assertThat(attachments(t.id(), null)).isEqualTo(2);
        assertThat(attachments(t.id(), "BEFORE")).isEqualTo(2);
        assertThat(documentsInFolder(t.id(), "04_QUAL.RESERVES")).isEqualTo(2);
    }

    // ── with-photos: 2nd file text/plain -> 400 and NO ticket row ──

    @Test
    void withPhotos_secondFileIsText_rejectedAndNoTicketCreated() {
        String title = "Texte refusé " + UUID.randomUUID();
        assertThatThrownBy(() -> photoService.createWithPhotos(auth,
                title, "Desc", projectId, null, null, null, null, null,
                "PROBLEME", "METEO", null, null, null, null,
                List.of(jpeg("ok.jpg"), textFile("bad.txt"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("JPEG");

        // nothing was created: no ticket row, no attachment, no document
        assertThat(ticketCountByTitle(title)).isZero();
    }

    // ── 11th photo -> 400 ──

    @Test
    void withPhotos_elevenPhotos_rejected() {
        MultipartFile[] eleven = new MultipartFile[11];
        for (int i = 0; i < 11; i++) eleven[i] = jpeg("p" + i + ".jpg");
        assertThatThrownBy(() -> photoService.createWithPhotos(auth,
                "Trop de photos", "Desc", projectId, null, null, null, null, null,
                "PROBLEME", "METEO", null, null, null, null,
                List.of(eleven)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("10 photos");
    }

    // ── DELETE by another user -> 403; on a CLOSED ticket -> 400 ──

    @Test
    void deletePhoto_byAnotherUser_forbidden() {
        TicketService.TicketResponse t = photoService.createWithPhotos(auth,
                "Photo à supprimer", "Desc", projectId, null, null, null, null, null,
                "RESERVE", "MALFACON", null, null, null, null,
                List.of(jpeg("p.jpg")));
        UUID attachmentId = jdbc.queryForObject(
                "select id from ticket_attachment where ticket_id = ? and org_id = ? limit 1",
                UUID.class, t.id(), orgId);

        SecurityContextHolder.getContext().setAuthentication(otherAuth);
        assertThatThrownBy(() -> photoService.deletePhoto(otherAuth, t.id(), attachmentId))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void deletePhoto_onClosedTicket_rejected() {
        TicketService.TicketResponse t = photoService.createWithPhotos(auth,
                "Photo clos", "Desc", projectId, null, null, null, null, null,
                "PROBLEME", "METEO", null, null, null, null,
                List.of(jpeg("p.jpg")));
        UUID attachmentId = jdbc.queryForObject(
                "select id from ticket_attachment where ticket_id = ? and org_id = ? limit 1",
                UUID.class, t.id(), orgId);

        // walk to CLOSED
        tickets.setStatus(auth, t.id(), "IN_PROGRESS");
        tickets.setStatus(auth, t.id(), "RESOLVED");
        tickets.setStatus(auth, t.id(), "CLOSED");

        assertThatThrownBy(() -> photoService.deletePhoto(auth, t.id(), attachmentId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("clos");
    }

    // ── search overdue=true returns only overdue tickets, overdue first in default order ──

    @Test
    void search_overdueFilter_returnsOnlyOverdueAndOverdueFirst() {
        // overdue (yesterday, OPEN) + not overdue (tomorrow, OPEN) + overdue-but-closed
        createTicket("Retard hier", LocalDate.now().minusDays(1), null);
        createTicket("À temps demain", LocalDate.now().plusDays(1), null);
        UUID closedId = createTicket("Retard mais clos", LocalDate.now().minusDays(1), null);
        tickets.setStatus(auth, closedId, "IN_PROGRESS");
        tickets.setStatus(auth, closedId, "RESOLVED");
        tickets.setStatus(auth, closedId, "CLOSED");

        TicketService.TicketSearchPage page = tickets.search(auth, projectId, null, null, null, true, null, 0, 20);
        // only the one overdue + OPEN ticket (CLOSED is excluded by the overdue filter)
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).ticket().title()).isEqualTo("Retard hier");

        // default order (no overdue filter): overdue first, then dueAt asc
        TicketService.TicketSearchPage all = tickets.search(auth, projectId, null, null, null, null, null, 0, 20);
        assertThat(all.items()).isNotEmpty();
        assertThat(all.items().get(0).ticket().title()).isEqualTo("Retard hier");
    }

    // ── search mine=true returns my assigned tickets and the RESOLVED ones I created ──

    @Test
    void search_mineFilter_returnsAssignedToMeAndResolvedICreated() {
        // assigned to me (OPEN)
        createTicket("Mien assigné", LocalDate.now().plusDays(2), ownerId);
        // assigned to another user (OPEN) — not mine
        createTicket("Pas à moi", LocalDate.now().plusDays(2), otherUserId);
        // RESOLVED, I am the creator, assigned to another user — mine (to verify)
        UUID resolvedId = createTicket("Résolu par moi", LocalDate.now().plusDays(2), otherUserId);
        tickets.setStatus(auth, resolvedId, "IN_PROGRESS");
        tickets.setStatus(auth, resolvedId, "RESOLVED");

        TicketService.TicketSearchPage page = tickets.search(auth, projectId, null, null, true, null, null, 0, 20);
        assertThat(page.items()).hasSize(2);
        assertThat(page.items()).extracting(i -> i.ticket().title())
                .containsExactlyInAnyOrder("Mien assigné", "Résolu par moi");
    }

    // ── summary counts match after creating 3 tickets of different types ──

    @Test
    void summary_countsMatchAfterThreeTicketsOfDifferentTypes() {
        createTicketWithType("Problème A", "PROBLEME", "METEO");
        createTicketWithType("Réserve A", "RESERVE", "MALFACON");
        createTicketWithType("Demande A", "DEMANDE", "INFORMATION");

        TicketService.TicketSummary s = tickets.summary(auth, projectId);
        assertThat(s.byType()).isEqualTo(java.util.Map.of(
                "PROBLEME", 1L, "RESERVE", 1L, "DEMANDE", 1L, "VISA_DOCUMENT", 0L));
        assertThat(s.open()).isEqualTo(3);
        assertThat(s.inProgress()).isZero();
        assertThat(s.toVerify()).isZero();
    }

    // ── create helpers (type-aware, default delay applies) ──

    private UUID createTicket(String title, LocalDate dueAt, UUID assignedTo) {
        return tickets.create(auth, title, "Desc", projectId, assignedTo, null, null, dueAt, null,
                "PROBLEME", "METEO", null, null, null, null).id();
    }

    private UUID createTicketWithType(String title, String type, String category) {
        return tickets.create(auth, title, "Desc", projectId, null, null, null, null, null,
                type, category, null, null, null, null).id();
    }

    // ── T8 new tests ────────────────────────────────────────────────────

    @Test
    void chefChantier_createsReserveWithPhoto_andResolvesWithAfterPhoto() {
        UUID chefId = UUID.randomUUID();
        String chefEmail = "photo.chef." + chefId + "@hydra.test";
        jdbc.update("insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active) values (?, ?, ?, ?, ?, false, true)",
                chefId, orgId, chefEmail, "hash", "Chef Chantier");
        jdbc.update("insert into project_member (project_id, user_id, project_role) values (?, ?, 'ENGINEER')", projectId, chefId);
        Authentication chefAuth = new UsernamePasswordAuthenticationToken(chefEmail, null, List.of(
                new SimpleGrantedAuthority("PERM_TICKET_READ"), new SimpleGrantedAuthority("PERM_TICKET_WRITE"),
                new SimpleGrantedAuthority("PERM_DOCUMENT_UPLOAD")));
        SecurityContextHolder.getContext().setAuthentication(chefAuth);

        TicketService.TicketResponse t = photoService.createWithPhotos(chefAuth,
                "Réserve garde-corps", "Desc", projectId, chefId, null, null, null, null,
                "RESERVE", "MALFACON", null, null, null, null,
                List.of(jpeg("before.jpg")));
        assertThat(t.id()).isNotNull();
        assertThat(t.type()).isEqualTo("RESERVE");

        tickets.start(chefAuth, t.id());
        photoService.addPhoto(chefAuth, t.id(), jpeg("after.jpg"), "AFTER");
        tickets.resolve(chefAuth, t.id(), "Reprise faite et contrôlée");
        assertThat(jdbc.queryForObject("select status from ticket where id = ?", String.class, t.id())).isEqualTo("RESOLVED");
        SecurityContextHolder.clearContext();
    }

    @Test
    void nonMember_addPhotoAndListPhotos_forbidden() {
        UUID nmId = UUID.randomUUID();
        String nmEmail = "photo.nm." + nmId + "@hydra.test";
        jdbc.update("insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active) values (?, ?, ?, ?, ?, false, true)",
                nmId, orgId, nmEmail, "hash", "Non Member");
        Authentication nmAuth = new UsernamePasswordAuthenticationToken(nmEmail, null, List.of(
                new SimpleGrantedAuthority("PERM_TICKET_READ"), new SimpleGrantedAuthority("PERM_TICKET_WRITE")));

        UUID id = createTicket("Test", LocalDate.now().plusDays(2), null);
        assertThatThrownBy(() -> photoService.addPhoto(nmAuth, id, jpeg("p.jpg"), "BEFORE"))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> photoService.listPhotos(nmAuth, id))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void addPhoto_onClosedTicket_rejected() {
        UUID id = createTicketWithType("Sig closed", "PROBLEME", "METEO");
        tickets.start(auth, id);
        tickets.resolve(auth, id, "Résolu");
        tickets.verify(auth, id);
        assertThat(jdbc.queryForObject("select status from ticket where id = ?", String.class, id)).isEqualTo("CLOSED");
        assertThatThrownBy(() -> photoService.addPhoto(auth, id, jpeg("p.jpg"), "BEFORE"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("clos ou annulé");
    }
}
