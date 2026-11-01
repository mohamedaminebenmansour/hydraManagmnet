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
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ============================================================================
 *  BordereauImportPairsDbTest — importExcel saves fourniture ↔ pose pairs
 *  with real line ids, both ways (feature/bordereau-import).
 * ============================================================================
 *
 *  @SpringBootTest against hydra_test; @Transactional + @Rollback keep the DB
 *  clean. Expected: 6 pairs ("Résultat attendu" sheet of the test file: each
 *  'Conduite PEHD PNx DE y' in I with 'Pose et essai conduite PEHD PNx DE y' in II).
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class BordereauImportPairsDbTest {

    private static final String TEST_FILE = "src/test/resources/bordereau/HYDRA-test-bordereau-Bouslim.xlsx";

    @Autowired private BordereauImportService importService;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID projectId;
    private Authentication auth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        String email = "bordereau.owner." + ownerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Bordereau Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, ownerId, orgId, email, "hash", "Bordereau Owner");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "Bouslim " + projectId, ownerId);

        auth = new UsernamePasswordAuthenticationToken(email, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void importExcel_savesSixPairs_bothWays_withRealLineIds() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "HYDRA-test-bordereau-Bouslim.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Files.readAllBytes(Path.of(TEST_FILE)));

        BordereauImportService.ImportPreview preview = importService.importExcel(auth, projectId, file);
        assertThat(preview.pairCount()).isEqualTo(6);

        List<Map<String, Object>> fournitures = jdbc.queryForList("""
                select id, paired_line_id, specs->>'materiau' as materiau,
                       specs->>'de_mm' as de_mm, specs->>'pn_bar' as pn_bar
                from bordereau_line
                where version_id = ? and org_id = ? and work_nature = 'FOURNITURE' and paired_line_id is not null
                """, preview.versionId(), orgId);
        assertThat(fournitures).hasSize(6);

        for (Map<String, Object> f : fournitures) {
            Map<String, Object> pose = jdbc.queryForMap("""
                    select id, paired_line_id, work_nature, specs->>'materiau' as materiau,
                           specs->>'de_mm' as de_mm, specs->>'pn_bar' as pn_bar
                    from bordereau_line where id = ? and version_id = ?
                    """, f.get("paired_line_id"), preview.versionId());
            assertThat(pose.get("work_nature")).isEqualTo("POSE");
            assertThat(pose.get("materiau")).isEqualTo(f.get("materiau"));
            assertThat(pose.get("de_mm")).isEqualTo(f.get("de_mm"));
            assertThat(pose.get("pn_bar")).isEqualTo(f.get("pn_bar"));
            // The pose line points back to the fourniture line.
            assertThat(pose.get("paired_line_id")).isEqualTo(f.get("id"));
        }

        // Exactly the 12 paired lines carry a link (6 fourniture + 6 pose).
        Integer linked = jdbc.queryForObject(
                "select count(*) from bordereau_line where version_id = ? and paired_line_id is not null",
                Integer.class, preview.versionId());
        assertThat(linked).isEqualTo(12);

        // The preview shows the same real ids as the database.
        long previewLinked = preview.lines().stream().filter(l -> l.pairedLineId() != null).count();
        assertThat(previewLinked).isEqualTo(12);
        for (Map<String, Object> f : fournitures) {
            assertThat(preview.lines().stream().map(BordereauImportService.PreviewLine::pairedLineId))
                    .contains((UUID) f.get("paired_line_id"));
        }
    }

    @Test
    void importExcel_skipsAReservoirWithNonNumericCapacity_andSucceeds() throws Exception {
        jdbc.update("""
                insert into ouvrage (org_id, project_id, type, name, characteristics)
                values (?, ?, 'RESERVOIR', 'Réservoir sur piliers', '{"capacite_m3": "250 m3"}'::jsonb)
                """, orgId, projectId);
        MockMultipartFile file = new MockMultipartFile("file", "HYDRA-test-bordereau-Bouslim.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                Files.readAllBytes(Path.of(TEST_FILE)));

        BordereauImportService.ImportPreview preview = importService.importExcel(auth, projectId, file);

        assertThat(preview.versionId()).isNotNull();
        Integer withReservoir = jdbc.queryForObject("""
                select count(*) from bordereau_line l join ouvrage o on o.id = l.ouvrage_id
                where l.version_id = ? and o.type = 'RESERVOIR'
                """, Integer.class, preview.versionId());
        assertThat(withReservoir).isZero();
    }
}
