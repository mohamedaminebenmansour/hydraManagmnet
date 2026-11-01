package tn.hydra.project;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.document.service.FileStorage;
import tn.hydra.document.service.StoredFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * ============================================================================
 *  BordereauSourceFileDbTest — the imported Excel is kept as ONE document
 *  "Bordereau des prix" in 02_TECH / "Bordereaux et DQE", one revision per
 *  import (feature/bordereau-file-in-folder).
 * ============================================================================
 *
 *  @SpringBootTest against hydra_test; @Transactional + @Rollback keep the DB
 *  clean. FileStorage is mocked: the import goes through the real
 *  DocumentService / RevisionService, only the bytes are not written.
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class BordereauSourceFileDbTest {

    private static final String TEST_FILE = "src/test/resources/bordereau/HYDRA-test-bordereau-Bouslim.xlsx";

    @Autowired private BordereauImportService importService;
    @Autowired private BordereauService bordereau;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private FileStorage storage;

    private UUID orgId;
    private UUID projectId;
    private UUID techFolderId;
    private Authentication auth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        String email = "bordereau.file." + ownerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Bordereau File Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, ownerId, orgId, email, "hash", "Bordereau Owner");
        jdbc.update("insert into project (id, org_id, name, code, status, created_by) values (?, ?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "Bouslim " + projectId, "BSL" + ownerId.toString().substring(0, 4), ownerId);
        techFolderId = jdbc.queryForObject("""
                insert into doc_folder (project_id, parent_id, code, name, sort_order)
                values (?, null, '02_TECH', 'Technique', 2) returning id
                """, UUID.class, projectId);

        auth = new UsernamePasswordAuthenticationToken(email, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private MockMultipartFile excel() throws Exception {
        return new MockMultipartFile("file", "HYDRA-test-bordereau-Bouslim.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Files.readAllBytes(Path.of(TEST_FILE)));
    }

    @Test
    void importingTwice_givesOneDocumentWithTwoRevisions_andEachVersionPointsToItsOwnRevision() throws Exception {
        when(storage.save(anyString(), any(), any())).thenAnswer(inv ->
                new StoredFile(inv.getArgument(0), ((byte[]) inv.getArgument(1)).length, "sha-test"));

        BordereauImportService.ImportPreview first = importService.importExcel(auth, projectId, excel());
        BordereauImportService.ImportPreview second = importService.importExcel(auth, projectId, excel());

        // One "Bordereaux et DQE" sub-folder under 02_TECH.
        List<UUID> subFolders = jdbc.queryForList(
                "select id from doc_folder where parent_id = ? and name = 'Bordereaux et DQE'", UUID.class, techFolderId);
        assertThat(subFolders).hasSize(1);

        // One document in it, with 2 revisions.
        List<Map<String, Object>> docs = jdbc.queryForList(
                "select id, title, doc_type from document where folder_id = ? and deleted_at is null", subFolders.get(0));
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).get("title")).isEqualTo("Bordereau des prix");
        assertThat(docs.get(0).get("doc_type")).isEqualTo("ETUDE");
        UUID docId = (UUID) docs.get(0).get("id");
        Integer revisions = jdbc.queryForObject(
                "select count(*) from document_revision where document_id = ?", Integer.class, docId);
        assertThat(revisions).isEqualTo(2);

        // Each version points to the document and to its own, existing revision.
        Map<String, Object> v1 = jdbc.queryForMap(
                "select source_document_id, source_revision from bordereau_version where id = ?", first.versionId());
        Map<String, Object> v2 = jdbc.queryForMap(
                "select source_document_id, source_revision from bordereau_version where id = ?", second.versionId());
        assertThat(v1.get("source_document_id")).isEqualTo(docId);
        assertThat(v2.get("source_document_id")).isEqualTo(docId);
        assertThat(v1.get("source_revision")).isNotEqualTo(v2.get("source_revision"));
        for (Map<String, Object> v : List.of(v1, v2)) {
            Integer exists = jdbc.queryForObject(
                    "select count(*) from document_revision where document_id = ? and rev_label = ?",
                    Integer.class, docId, v.get("source_revision"));
            assertThat(exists).isEqualTo(1);
        }

        // The API exposes the revision id used by the download link.
        List<BordereauService.VersionResponse> versions = bordereau.listVersions(auth, projectId);
        assertThat(versions).hasSize(2);
        assertThat(versions).allSatisfy(v -> assertThat(v.sourceRevisionId()).isNotNull());
        assertThat(versions.get(0).sourceRevisionId()).isNotEqualTo(versions.get(1).sourceRevisionId());
    }

    @Test
    void aStorageFailure_cancelsTheImport_noVersionNoLinesNoDocument() throws Exception {
        when(storage.save(anyString(), any(), any())).thenThrow(new RuntimeException("disque plein"));

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> importService.importExcel(auth, projectId, excel()));
        assertThat(ex.getCode()).isEqualTo("BORDEREAU_FILE_NOT_SAVED");
        assertThat(ex.getMessage()).isEqualTo(
                "Le fichier n'a pas pu être enregistré dans les documents du chantier. Import annulé.");

        assertThat(jdbc.queryForObject(
                "select count(*) from bordereau_version where project_id = ?", Integer.class, projectId)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from bordereau_line l join bordereau_version v on v.id = l.version_id
                where v.project_id = ?
                """, Integer.class, projectId)).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from document d join doc_folder f on f.id = d.folder_id
                where f.project_id = ?
                """, Integer.class, projectId)).isZero();
    }
}
