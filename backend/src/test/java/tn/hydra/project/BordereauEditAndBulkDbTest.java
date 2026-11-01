package tn.hydra.project;

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
import tn.hydra.common.BusinessRuleException;

import java.sql.Array;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ============================================================================
 *  BordereauEditAndBulkDbTest — duplicate check after an edit, and the bulk
 *  ouvrage assignment endpoint (feature/bordereau-ouvrages).
 * ============================================================================
 *
 *  @SpringBootTest against hydra_test; @Transactional + @Rollback keep the DB
 *  clean. Each test provisions its own org / owner / chantier / DRAFT version.
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class BordereauEditAndBulkDbTest {

    @Autowired private BordereauService bordereau;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID ownerId;
    private UUID projectId;
    private UUID versionId;
    private Authentication auth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        String email = "bordereau.edit." + ownerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Bordereau Edit Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, ownerId, orgId, email, "hash", "Bordereau Owner");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "Chantier " + projectId, ownerId);
        versionId = insertVersion(orgId, projectId, 1);

        auth = new UsernamePasswordAuthenticationToken(email, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private UUID insertVersion(UUID org, UUID project, int no) {
        return jdbc.queryForObject("""
                insert into bordereau_version (org_id, project_id, version_no, label, status)
                values (?, ?, ?, ?, 'DRAFT') returning id
                """, UUID.class, org, project, no, "Version " + no);
    }

    private UUID insertLine(UUID org, UUID version, String articleNo, String designation) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into bordereau_line (id, org_id, version_id, section_path, article_no, sort_order,
                                            designation, unit_raw, unit, quantity, unit_price)
                values (?, ?, ?, ?, ?, 0, ?, 'ml', 'ML', 10, 5)
                """, id, org, version, new String[]{"Lot 1", "I Fourniture"}, articleNo, designation);
        return id;
    }

    private List<String> warnings(UUID lineId) throws Exception {
        Array arr = jdbc.queryForObject("select import_warnings from bordereau_line where id = ?", Array.class, lineId);
        return arr == null ? List.of() : Arrays.asList((String[]) arr.getArray());
    }

    @Test
    void editingAnArticleNumberIntoADuplicate_warnsBothLines() throws Exception {
        UUID a = insertLine(orgId, versionId, "1", "Conduite PEHD PN10 DE 110");
        UUID b = insertLine(orgId, versionId, "2", "Conduite PEHD PN10 DE 160");

        bordereau.updateLine(auth, projectId, versionId, b, new BordereauService.UpdateLineRequest(
                "1", null, null, null, null, null, null, null, null, null, null));

        String expected = "Numéro d'article dupliqué dans cette section : « 1 ».";
        assertThat(warnings(a)).contains(expected);
        assertThat(warnings(b)).contains(expected);

        // Editing it back removes the warning from both lines.
        bordereau.updateLine(auth, projectId, versionId, b, new BordereauService.UpdateLineRequest(
                "3", null, null, null, null, null, null, null, null, null, null));
        assertThat(warnings(a)).doesNotContain(expected);
        assertThat(warnings(b)).doesNotContain(expected);
    }

    @Test
    void editingOnlyTheQuantity_keepsTheExistingSpecs() {
        // Designation without extractable specs, so only the stored specs are in play.
        UUID a = insertLine(orgId, versionId, "1", "Béton de propreté");
        jdbc.update("update bordereau_line set specs = '{\"capacite_m3\": 250}'::jsonb where id = ?", a);

        bordereau.updateLine(auth, projectId, versionId, a, new BordereauService.UpdateLineRequest(
                null, null, null, null, new java.math.BigDecimal("20"), null, null, null, null, null, null));

        assertThat(jdbc.queryForObject("select quantity from bordereau_line where id = ?",
                java.math.BigDecimal.class, a)).isEqualByComparingTo("20");
        assertThat(jdbc.queryForObject("select specs = '{\"capacite_m3\": 250}'::jsonb from bordereau_line where id = ?",
                Boolean.class, a)).isTrue();
    }

    @Test
    void editingTheDesignation_keepsCapaciteM3_andAddsTheNewKeys() {
        UUID a = insertLine(orgId, versionId, "1", "Béton de propreté");
        jdbc.update("update bordereau_line set specs = '{\"capacite_m3\": 250}'::jsonb where id = ?", a);

        bordereau.updateLine(auth, projectId, versionId, a, new BordereauService.UpdateLineRequest(
                null, "Conduite PEHD PN10 DE 110", null, null, null, null, null, null, null, null, null));

        var specs = jdbc.queryForMap("""
                select specs->>'capacite_m3' as cap, specs->>'de_mm' as de, specs->>'pn_bar' as pn,
                       specs->>'materiau' as mat
                from bordereau_line where id = ?
                """, a);
        assertThat(specs.get("cap")).isEqualTo("250");
        assertThat(specs.get("de")).isEqualTo("110");
        assertThat(specs.get("pn")).isEqualTo("10");
        assertThat(specs.get("mat")).isEqualTo("PEHD");
    }

    @Test
    void aPairingWarning_isKeptAfterTheQuantityIsEdited() throws Exception {
        UUID a = insertLine(orgId, versionId, "1", "Fourniture conduite PEHD PN10 DE 110");
        jdbc.update("update bordereau_line set import_warnings = ? where id = ?",
                new String[]{"Plusieurs poses possibles, aucun lien créé."}, a);

        bordereau.updateLine(auth, projectId, versionId, a, new BordereauService.UpdateLineRequest(
                null, null, null, null, new java.math.BigDecimal("20"), null, null, null, null, null, null));

        assertThat(warnings(a)).containsExactly("Plusieurs poses possibles, aucun lien créé.");
    }

    @Test
    void editingUnitRaw_recomputesUnitAndTheUnknownUnitWarning() throws Exception {
        UUID a = insertLine(orgId, versionId, "1", "Conduite PEHD PN10 DE 110");

        bordereau.updateLine(auth, projectId, versionId, a, new BordereauService.UpdateLineRequest(
                null, null, "xyz", null, null, null, null, null, null, null, null));
        assertThat(warnings(a)).contains("Unité inconnue : « xyz ».");
        assertThat(jdbc.queryForObject("select unit from bordereau_line where id = ?", String.class, a)).isNull();

        bordereau.updateLine(auth, projectId, versionId, a, new BordereauService.UpdateLineRequest(
                null, null, "ml", null, null, null, null, null, null, null, null));
        assertThat(warnings(a)).noneMatch(w -> w.startsWith("Unité inconnue"));
        assertThat(jdbc.queryForObject("select unit from bordereau_line where id = ?", String.class, a)).isEqualTo("ML");
    }

    @Test
    void assignOuvrage_setsTheOuvrageOnAllSelectedLines() {
        UUID a = insertLine(orgId, versionId, "1", "Conduite PEHD PN10 DE 110");
        UUID b = insertLine(orgId, versionId, "2", "Conduite PEHD PN10 DE 160");
        UUID ouvrageId = jdbc.queryForObject(
                "insert into ouvrage (org_id, project_id, type, name) values (?, ?, 'CONDUITE', 'Conduite') returning id",
                UUID.class, orgId, projectId);

        List<BordereauService.LineResponse> out = bordereau.assignOuvrage(auth, projectId, versionId,
                new BordereauService.AssignOuvrageRequest(List.of(a, b), ouvrageId));

        assertThat(out).extracting(BordereauService.LineResponse::ouvrageId).containsOnly(ouvrageId);
    }

    @Test
    void assignOuvrage_refusesLinesOfAnotherVersion_andChangesNothing() {
        UUID mine = insertLine(orgId, versionId, "1", "Conduite PEHD PN10 DE 110");
        UUID otherVersion = insertVersion(orgId, projectId, 2);
        UUID foreign = insertLine(orgId, otherVersion, "1", "Conduite PEHD PN10 DE 110");
        UUID ouvrageId = jdbc.queryForObject(
                "insert into ouvrage (org_id, project_id, type, name) values (?, ?, 'CONDUITE', 'Conduite') returning id",
                UUID.class, orgId, projectId);

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> bordereau.assignOuvrage(auth, projectId, versionId,
                        new BordereauService.AssignOuvrageRequest(List.of(mine, foreign), ouvrageId)));
        assertThat(ex.getCode()).isEqualTo("LINE_NOT_FOUND");
        assertThat(jdbc.queryForObject("select ouvrage_id from bordereau_line where id = ?", UUID.class, mine)).isNull();
    }

    @Test
    void assignOuvrage_refusesLinesOfAnotherOrg() {
        UUID mine = insertLine(orgId, versionId, "1", "Conduite PEHD PN10 DE 110");

        UUID otherOrg = UUID.randomUUID();
        UUID otherOwner = UUID.randomUUID();
        UUID otherProject = UUID.randomUUID();
        jdbc.update("insert into organization (id, name) values (?, ?)", otherOrg, "Other Org " + otherOrg);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, otherOwner, otherOrg, "other." + otherOwner + "@hydra.test", "hash", "Other Owner");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                otherProject, otherOrg, "Other " + otherProject, otherOwner);
        UUID foreign = insertLine(otherOrg, insertVersion(otherOrg, otherProject, 1), "1", "Conduite");

        BusinessRuleException ex = assertThrows(BusinessRuleException.class,
                () -> bordereau.assignOuvrage(auth, projectId, versionId,
                        new BordereauService.AssignOuvrageRequest(List.of(mine, foreign), null)));
        assertThat(ex.getCode()).isEqualTo("LINE_NOT_FOUND");
    }
}
