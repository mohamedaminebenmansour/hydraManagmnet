package tn.hydra.ticket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.common.BusinessRuleException;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ============================================================================
 *  TicketTypesTest — real-database tests for the signalement types, categories
 *  and universal lifecycle introduced in T1.
 * ============================================================================
 *
 *  @SpringBootTest against hydra_test; @Transactional + @Rollback keep the DB
 *  clean. Provisions an org, an owner and an ACTIF chantier, plus a second
 *  chantier with an ouvrage (for the "ouvrage of another project" check).
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class TicketTypesTest {

    @Autowired private TicketService service;
    @Autowired private TicketController controller;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID ownerId;
    private UUID projectId;
    private UUID otherProjectId;
    private UUID otherOuvrageId;
    private String ownerEmail;
    private Authentication auth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        otherProjectId = UUID.randomUUID();
        otherOuvrageId = UUID.randomUUID();
        ownerEmail = "tickets.owner." + ownerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Tickets Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, ownerId, orgId, ownerEmail, "hash", "Tickets Owner");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "Tickets Chantier " + projectId, ownerId);
        // a second chantier holding an ouvrage, to prove a foreign ouvrage is rejected
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                otherProjectId, orgId, "Other Chantier " + otherProjectId, ownerId);
        jdbc.update("insert into ouvrage (id, org_id, project_id, type, name) values (?, ?, ?, 'OUVRAGE_ANNEXE', 'Ouvrage autre chantier')",
                otherOuvrageId, orgId, otherProjectId);

        auth = new UsernamePasswordAuthenticationToken(ownerEmail, null, List.of(
                new SimpleGrantedAuthority("PERM_TICKET_READ"),
                new SimpleGrantedAuthority("PERM_TICKET_WRITE")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** Create a signalement with the type-aware signature (defaults: no assignee, no dates). */
    private TicketService.TicketResponse create(String type, String category) {
        return service.create(auth, "Fuite regard", "Description", projectId, null, null,
                null, null, null, type, category, null, null, null, null);
    }

    // 1. create without type -> VISA_DOCUMENT + DRAFT (old behaviour)
    @Test
    void create_withoutType_defaultsToVisaDocumentAndDraft() {
        TicketService.TicketResponse t = service.create(auth, "Visa plan", "Desc", projectId,
                null, null, null, null, null, null, null, null, null, null, null);
        assertThat(t.type()).isEqualTo("VISA_DOCUMENT");
        assertThat(t.status()).isEqualTo("DRAFT");
        assertThat(t.typeLabel()).isEqualTo("Visa de document");
        assertThat(t.statusLabel()).isEqualTo("Brouillon");
        assertThat(t.overdue()).isFalse();
    }

    // 2. create PROBLEME/EQUIPE_ABSENTE -> OPEN, dueAt = today + 2
    @Test
    void create_problemeWithCategory_startsOpenWithDefaultDelay() {
        TicketService.TicketResponse t = create("PROBLEME", "EQUIPE_ABSENTE");
        assertThat(t.type()).isEqualTo("PROBLEME");
        assertThat(t.status()).isEqualTo("OPEN");
        assertThat(t.category()).isEqualTo("EQUIPE_ABSENTE");
        assertThat(t.categoryLabel()).isEqualTo("Équipe absente");
        assertThat(t.dueAt()).isEqualTo(LocalDate.now().plusDays(2));
    }

    // 3. create RESERVE without origin -> origin INTERNE, dueAt = today + 15
    @Test
    void create_reserveWithoutOrigin_defaultsToInterneAnd15Days() {
        TicketService.TicketResponse t = create("RESERVE", "MALFACON");
        assertThat(t.type()).isEqualTo("RESERVE");
        assertThat(t.status()).isEqualTo("OPEN");
        assertThat(t.origin()).isEqualTo("INTERNE");
        assertThat(t.dueAt()).isEqualTo(LocalDate.now().plusDays(15));
    }

    // 4. category of another type (PROBLEME with MALFACON) -> 400
    @Test
    void create_categoryOfAnotherType_isRejected() {
        assertThatThrownBy(() -> create("PROBLEME", "MALFACON"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Catégorie invalide");
    }

    // 5. ouvrage of another project -> 400
    @Test
    void create_ouvrageOfAnotherProject_isRejected() {
        assertThatThrownBy(() -> service.create(auth, "Problème", "Desc", projectId, null, null,
                null, null, null, "PROBLEME", "METEO", null, null, otherOuvrageId, null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Ouvrage");
    }

    // 6. OPEN -> CLOSED directly -> 400; OPEN -> IN_PROGRESS -> RESOLVED -> CLOSED -> ok
    @Test
    void lifecycle_openToClosedDirectly_rejectedButWalkingAllowed() {
        TicketService.TicketResponse t = create("PROBLEME", "METEO");
        UUID id = t.id();

        // OPEN -> CLOSED directly: not allowed
        assertThatThrownBy(() -> service.setStatus(auth, id, "CLOSED"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Transition");

        // OPEN -> IN_PROGRESS -> RESOLVED -> CLOSED: allowed
        assertThat(service.setStatus(auth, id, "IN_PROGRESS").status()).isEqualTo("IN_PROGRESS");
        TicketService.TicketResponse resolved = service.setStatus(auth, id, "RESOLVED");
        assertThat(resolved.status()).isEqualTo("RESOLVED");
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(resolved.resolvedById()).isEqualTo(ownerId);
        TicketService.TicketResponse closed = service.setStatus(auth, id, "CLOSED");
        assertThat(closed.status()).isEqualTo("CLOSED");
    }

    // 7. VISA_DOCUMENT transitions identical to before
    @Test
    void visaDocument_transitions_unchanged() {
        TicketService.TicketResponse t = service.create(auth, "Visa plan", "Desc", projectId,
                null, null, null, null, null, null, null, null, null, null, null);
        UUID id = t.id();
        assertThat(t.status()).isEqualTo("DRAFT");

        // DRAFT -> AS_BUILT directly: not allowed (same as before)
        assertThatThrownBy(() -> service.setStatus(auth, id, "AS_BUILT"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Transition");

        // DRAFT -> IN_REVIEW -> APPROVED -> IFC -> AS_BUILT: the original workflow
        assertThat(service.setStatus(auth, id, "IN_REVIEW").status()).isEqualTo("IN_REVIEW");
        assertThat(service.setStatus(auth, id, "APPROVED").status()).isEqualTo("APPROVED");
        assertThat(service.setStatus(auth, id, "IFC").status()).isEqualTo("IFC");
        TicketService.TicketResponse built = service.setStatus(auth, id, "AS_BUILT");
        assertThat(built.status()).isEqualTo("AS_BUILT");
        assertThat(built.resolvedAt()).isNotNull();
    }

    // 8. overdue true when dueAt is yesterday and status OPEN, false when CLOSED
    @Test
    void overdue_trueWhenPastDueAndOpen_falseWhenClosed() {
        TicketService.TicketResponse t = service.create(auth, "Retard", "Desc", projectId, null, null,
                null, LocalDate.now().minusDays(1), null, "PROBLEME", "METEO", null, null, null, null);
        assertThat(t.status()).isEqualTo("OPEN");
        assertThat(t.overdue()).isTrue();

        // walk to CLOSED -> overdue flips to false (CLOSED is terminal)
        service.setStatus(auth, t.id(), "IN_PROGRESS");
        service.setStatus(auth, t.id(), "RESOLVED");
        TicketService.TicketResponse closed = service.setStatus(auth, t.id(), "CLOSED");
        assertThat(closed.status()).isEqualTo("CLOSED");
        assertThat(closed.overdue()).isFalse();
    }

    // 9. GET /api/tickets/catalog returns 4 types and the right categories
    @Test
    void catalog_returnsFourTypesWithRightCategories() {
        List<TicketService.CatalogType> cat = controller.catalog();
        assertThat(cat).hasSize(4);
        assertThat(cat).extracting(TicketService.CatalogType::code)
                .containsExactlyInAnyOrder("PROBLEME", "RESERVE", "DEMANDE", "VISA_DOCUMENT");

        TicketService.CatalogType probleme = cat.stream().filter(c -> c.code().equals("PROBLEME")).findFirst().orElseThrow();
        assertThat(probleme.label()).isEqualTo("Problème chantier");
        assertThat(probleme.icon()).isEqualTo("pi-exclamation-triangle");
        assertThat(probleme.colour()).isEqualTo("#ea580c");
        assertThat(probleme.defaultDays()).isEqualTo(2);
        assertThat(probleme.requiresAfterPhoto()).isFalse();
        assertThat(probleme.categories()).extracting(c -> c.get("code"))
                .contains("EQUIPE_ABSENTE", "MATERIEL_MANQUANT");

        TicketService.CatalogType reserve = cat.stream().filter(c -> c.code().equals("RESERVE")).findFirst().orElseThrow();
        assertThat(reserve.defaultDays()).isEqualTo(15);
        assertThat(reserve.requiresAfterPhoto()).isTrue();
        assertThat(reserve.categories()).extracting(c -> c.get("code"))
                .contains("MALFACON", "NON_FACON");

        TicketService.CatalogType visa = cat.stream().filter(c -> c.code().equals("VISA_DOCUMENT")).findFirst().orElseThrow();
        assertThat(visa.defaultDays()).isNull();
        assertThat(visa.categories()).isEmpty();
        assertThat(visa.statuses()).extracting(s -> s.get("code"))
                .containsExactly("DRAFT", "IN_REVIEW", "APPROVED", "IFC", "AS_BUILT");

        TicketService.CatalogType demande = cat.stream().filter(c -> c.code().equals("DEMANDE")).findFirst().orElseThrow();
        assertThat(demande.statuses()).extracting(s -> s.get("code"))
                .containsExactly("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED", "CANCELLED");
    }
}
