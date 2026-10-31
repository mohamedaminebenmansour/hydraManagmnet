package tn.hydra.project;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.security.FolderAccessService;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * OuvrageServiceTest — tests the atomic create-project-with-ouvrages flow
 * and the COMMUN-cannot-be-deleted rule.
 *
 * <p>These tests construct a real {@link OuvrageService} (not a mock) so the
 * characteristic validation runs for real. The {@link JdbcTemplate} is mocked
 * so no database is needed. The {@link ProjectService} is constructed with a
 * real OuvrageService via an {@link ApplicationContext} mock (same pattern as
 * the adapted ProjectServiceTest).
 */
class OuvrageServiceTest {

    private static final UUID ORG = UUID.randomUUID();
    private static final UUID PROJECT_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID OUVRAGE_ID = UUID.randomUUID();

    private JdbcTemplate jdbc;
    private AppUserRepository users;
    private EventLogService audit;
    private FolderAccessService folderAccess;
    private OuvrageService ouvrageService;
    private ProjectService projectService;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        users = mock(AppUserRepository.class);
        audit = mock(EventLogService.class);
        folderAccess = mock(FolderAccessService.class);

        // Real OuvrageService — so validateCharacteristics runs for real.
        // ProjectService reference is null here (lazy lookup via ctx below).
        ouvrageService = new OuvrageService(jdbc, users, null, audit);
        // Inject a ProjectService that has a real OuvrageService (via ApplicationContext).
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBean(OuvrageService.class)).thenReturn(ouvrageService);
        projectService = new ProjectService(jdbc, users, audit, folderAccess, ctx);

        auth = new UsernamePasswordAuthenticationToken("owner@hydra.tn", "pw");
        AppUser owner = new AppUser();
        owner.setId(OWNER_ID);
        owner.setOrgId(ORG);
        owner.setEmail("owner@hydra.tn");
        owner.setIsOwner(true);
        when(users.findByEmail("owner@hydra.tn")).thenReturn(Optional.of(owner));

        // stubCreate: project insert returns PROJECT_ID, no folder templates.
        when(jdbc.queryForObject(contains("insert into project"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(PROJECT_ID);
        when(jdbc.queryForList(contains("from doc_folder_template"), eq(ORG)))
                .thenReturn(List.of());
        when(jdbc.queryForList(contains("where org_id is null")))
                .thenReturn(List.of());
        when(jdbc.queryForObject(contains("count(*)"), eq(Integer.class), any()))
                .thenReturn(7);
        // get() at the end of create() returns the project row.
        when(jdbc.queryForList(contains("where p.id = ? and p.org_id = ?"),
                eq(PROJECT_ID), eq(ORG)))
                .thenReturn(List.of(projectRow(PROJECT_ID, "Chantier Test", "CT", "ACTIF")));
    }

    private Map<String, Object> projectRow(UUID id, String name, String code, String status) {
        var row = new HashMap<String, Object>();
        row.put("id", id);
        row.put("org_id", ORG);
        row.put("name", name);
        row.put("code", code);
        row.put("status", status);
        row.put("created_at", Instant.now());
        return row;
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST 1 — create project with 2 ouvrages succeeds
    // ═══════════════════════════════════════════════════════════════
    @Test
    void create_projectWithTwoOuvrages_succeeds_andInsertsBoth() {
        // stub: ensureCommunOuvrage (3 args: orgId, projectId, createdBy) +
        // the 2 requested ouvrages (8 args each). Mockito requires the exact
        // arg count, so we stub both signatures.
        when(jdbc.queryForObject(contains("insert into ouvrage"), eq(UUID.class),
                any(), any(), any()))  // COMMUN insert: 3 varargs
                .thenReturn(UUID.randomUUID());
        when(jdbc.queryForObject(contains("insert into ouvrage"), eq(UUID.class),
                any(), any(), any(), any(), any(), any(), any(), any()))  // createInTransaction: 8 varargs
                .thenReturn(UUID.randomUUID(), UUID.randomUUID());

        List<OuvrageService.CreateOuvrageRequest> ouvrages = List.of(
                new OuvrageService.CreateOuvrageRequest(
                        "RESERVOIR", null, "Réservoir 100 m³",
                        Map.of("capacite_m3", 100, "materiau", "BETON_ARME"), null),
                new OuvrageService.CreateOuvrageRequest(
                        "CONDUITE", null, "Conduite PEHD",
                        Map.of("de_mm", 110, "pn_bar", "16", "longueur_m", 1500), null));

        ProjectService.CreateProjectRequest body = new ProjectService.CreateProjectRequest(
                "Chantier Test", "CT", null, null, null, null, null,
                null, null, null, null, null, ouvrages);

        ProjectService.ProjectResponse created = projectService.create(auth, body);

        assertThat(created).isNotNull();
        assertThat(created.name()).isEqualTo("Chantier Test");
        // The COMMUN ouvrage insert (3 args) + 2 requested ouvrages (8 args each).
        verify(jdbc, times(1)).queryForObject(contains("insert into ouvrage"),
                eq(UUID.class), any(), any(), any());
        verify(jdbc, times(2)).queryForObject(contains("insert into ouvrage"),
                eq(UUID.class), any(), any(), any(), any(), any(), any(), any(), any());
        // 3 OUVRAGE_CREATED audit events (COMMUN + 2 requested).
        verify(audit, times(3)).log(eq("OUVRAGE_CREATED"), eq("ouvrage"), any(), anyString());
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST 2 — create project with an invalid characteristic key saves nothing
    // ═══════════════════════════════════════════════════════════════
    @Test
    void create_projectWithInvalidCharacteristicKey_throwsAndSavesNothing() {
        // The "ouvrage" insert is NEVER called because validation throws first.
        // (We don't stub it, so it would return null → NPE; but we expect a
        // BusinessRuleException before that point.)
        List<OuvrageService.CreateOuvrageRequest> ouvrages = List.of(
                new OuvrageService.CreateOuvrageRequest(
                        "RESERVOIR", null, "Réservoir 100 m³",
                        // "capacite" is NOT a valid key for RESERVOIR (it's "capacite_m3").
                        Map.of("capacite", 100), null));

        ProjectService.CreateProjectRequest body = new ProjectService.CreateProjectRequest(
                "Chantier Test", "CT", null, null, null, null, null,
                null, null, null, null, null, ouvrages);

        assertThatThrownBy(() -> projectService.create(auth, body))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "UNKNOWN_CHARACTERISTIC")
                .hasMessageContaining("Ouvrage n°1")
                .hasMessageContaining("réservoir")
                .hasMessageContaining("capacite");

        // Nothing saved: the ouvrage insert was never called.
        verify(jdbc, never()).queryForObject(contains("insert into ouvrage"),
                eq(UUID.class), any(), any(), any(), any(), any(), any(), any(), any());
        // The project insert WAS called (it happens before the ouvrage loop),
        // but in a real @Transactional context the exception would roll it back.
        // We can't test the rollback in a unit test (no real transaction), but
        // we verify the exception propagates — which is what triggers the rollback.
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST 3 — COMMUN ouvrage cannot be deleted
    // ═══════════════════════════════════════════════════════════════
    @Test
    void delete_communOuvrage_throwsAndDoesNothing() {
        // For the delete test, build an OuvrageService with a mock ProjectService
        // so requireManageableForOuvrage passes (the owner is authorized).
        ProjectService mockProjects = mock(ProjectService.class);
        // The mock does nothing for requireManageableForOuvrage — which means
        // "access granted" (no exception thrown).
        OuvrageService ouvrageWithMockProjects = new OuvrageService(jdbc, users, mockProjects, audit);

        // The COMMUN ouvrage exists.
        when(jdbc.queryForList(contains("select type from ouvrage"),
                eq(OUVRAGE_ID), eq(ORG), eq(PROJECT_ID)))
                .thenReturn(List.of(Map.of("type", "COMMUN")));

        assertThatThrownBy(() -> ouvrageWithMockProjects.delete(auth, PROJECT_ID, OUVRAGE_ID))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "COMMUN_AUTO")
                .hasMessageContaining("COMMUN")
                .hasMessageContaining("obligatoire");

        // The DELETE SQL was never executed.
        verify(jdbc, never()).update(contains("delete from ouvrage"),
                eq(OUVRAGE_ID), eq(ORG), eq(PROJECT_ID));
    }
}
