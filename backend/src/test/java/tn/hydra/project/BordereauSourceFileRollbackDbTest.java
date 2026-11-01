package tn.hydra.project;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tn.hydra.audit.EventLogService;
import tn.hydra.document.service.FileStorage;
import tn.hydra.document.service.StoredFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ============================================================================
 *  BordereauSourceFileRollbackDbTest — when the bordereau part of the SECOND
 *  import fails after its file was stored, the stored object is deleted and
 *  the document keeps only the first revision (fix: JPA flush before JDBC).
 * ============================================================================
 *
 *  NOT @Transactional: the rollback cleanup runs when the import transaction
 *  really completes, so each import must commit / roll back on its own. Rows
 *  are deleted in @AfterEach (event_log is append-only and keeps its rows).
 *  The failure "after the file is stored" is the import's last step, the
 *  BORDEREAU_IMPORTED audit entry (a file without header fails BEFORE storing).
 * ============================================================================
 */
@SpringBootTest
class BordereauSourceFileRollbackDbTest {

    private static final String TEST_FILE = "src/test/resources/bordereau/HYDRA-test-bordereau-Bouslim.xlsx";

    @Autowired private BordereauImportService importService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private FileStorage storage;
    @MockitoSpyBean private EventLogService audit;

    private UUID orgId;
    private UUID ownerId;
    private UUID projectId;
    private Authentication auth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        String email = "bordereau.rollback." + ownerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Bordereau Rollback Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, ownerId, orgId, email, "hash", "Bordereau Owner");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "Rollback " + projectId, ownerId);
        jdbc.update("insert into doc_folder (project_id, parent_id, code, name, sort_order) values (?, null, '02_TECH', 'Technique', 2)",
                projectId);

        auth = new UsernamePasswordAuthenticationToken(email, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbc.update("delete from bordereau_line where version_id in (select id from bordereau_version where project_id = ?)", projectId);
        jdbc.update("delete from bordereau_version where project_id = ?", projectId);
        jdbc.update("""
                delete from document_revision where document_id in
                  (select d.id from document d join doc_folder f on f.id = d.folder_id where f.project_id = ?)
                """, projectId);
        jdbc.update("delete from document where folder_id in (select id from doc_folder where project_id = ?)", projectId);
        jdbc.update("delete from folder_access where folder_id in (select id from doc_folder where project_id = ?)", projectId);
        jdbc.update("delete from doc_folder where project_id = ? and parent_id is not null", projectId);
        jdbc.update("delete from doc_folder where project_id = ?", projectId);
        jdbc.update("delete from project where id = ?", projectId);
        jdbc.update("delete from app_user where id = ?", ownerId);
        jdbc.update("delete from organization where id = ?", orgId);
    }

    private MockMultipartFile excel() throws Exception {
        return new MockMultipartFile("file", "HYDRA-test-bordereau-Bouslim.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Files.readAllBytes(Path.of(TEST_FILE)));
    }

    @Test
    void secondImportFailingAfterTheFileIsStored_deletesTheSecondObject_andKeepsOneRevision() throws Exception {
        when(storage.save(anyString(), any(), any())).thenAnswer(inv ->
                new StoredFile(inv.getArgument(0), ((byte[]) inv.getArgument(1)).length, "sha-test"));

        importService.importExcel(auth, projectId, excel()); // commits

        // The bordereau part of the next import fails after its file is stored.
        doThrow(new RuntimeException("échec simulé après le stockage"))
                .when(audit).log(eq("BORDEREAU_IMPORTED"), any(), any(), any());
        assertThrows(RuntimeException.class, () -> importService.importExcel(auth, projectId, excel()));

        ArgumentCaptor<String> savedKeys = ArgumentCaptor.forClass(String.class);
        verify(storage, times(2)).save(savedKeys.capture(), any(), any());
        String firstKey = savedKeys.getAllValues().get(0);
        String secondKey = savedKeys.getAllValues().get(1);
        assertThat(secondKey).isNotEqualTo(firstKey);
        verify(storage).delete(secondKey);
        verify(storage, never()).delete(firstKey);

        Integer revisions = jdbc.queryForObject("""
                select count(*) from document_revision r
                join document d on d.id = r.document_id
                join doc_folder f on f.id = d.folder_id
                where f.project_id = ?
                """, Integer.class, projectId);
        assertThat(revisions).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from bordereau_version where project_id = ?", Integer.class, projectId)).isEqualTo(1);
    }
}
