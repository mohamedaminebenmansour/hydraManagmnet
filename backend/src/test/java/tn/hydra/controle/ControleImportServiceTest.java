package tn.hydra.controle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tn.hydra.common.BusinessRuleException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ControleImportServiceTest — CI-01..CI-08.
 *
 * Own orgs per test (same pattern as {@code CompanyProfileServiceTest}):
 * each test provisions a fresh organization + owner + (for CI-07) a non-owner
 * without PERM_CTL_IMPORT, and asserts only against that org's rows.
 *
 * sample.json is a FAKE file (no real company names or amounts) loaded from
 * the classpath at backend/src/test/resources/controle/sample.json.
 */
@SpringBootTest
@Transactional
@Rollback
class ControleImportServiceTest {

    @Autowired private ControleImportService service;
    @Autowired private ControleImportController controller;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper objectMapper;

    private UUID orgId;
    private UUID ownerId;
    private UUID nonOwnerId;
    private String ownerEmail;
    private String nonOwnerEmail;
    private Authentication ownerAuth;
    private Authentication nonOwnerAuth;

    @BeforeEach
    void provisionWorld() throws Exception {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        nonOwnerId = UUID.randomUUID();
        ownerEmail = "ci.owner." + ownerId + "@hydra.test";
        nonOwnerEmail = "ci.nonowner." + nonOwnerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "CI Test Org " + orgId);
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Owner', true, true, false)
                """, ownerId, orgId, ownerEmail, encoder.encode("OwnerStrong2026!xyz"));
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)", ownerId, ownerRoleId, ownerId);

        // Non-owner: BUREAU role (no CTL_* permissions)
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Non Owner', false, true, false)
                """, nonOwnerId, orgId, nonOwnerEmail, encoder.encode("NonOwnerPw2026!xyz"));
        UUID bureauRoleId = jdbc.queryForObject("select id from role where code = 'BUREAU'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)", nonOwnerId, bureauRoleId, ownerId);

        ownerAuth = new UsernamePasswordAuthenticationToken(ownerEmail, null,
                List.of(new SimpleGrantedAuthority("PERM_CTL_READ"),
                        new SimpleGrantedAuthority("PERM_CTL_JUSTIFY"),
                        new SimpleGrantedAuthority("PERM_CTL_IMPORT")));
        nonOwnerAuth = new UsernamePasswordAuthenticationToken(nonOwnerEmail, null,
                List.of(new SimpleGrantedAuthority("PERM_CTL_READ")));
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    /** Load the FAKE sample.json from the classpath as bytes. */
    private byte[] sampleJsonBytes() throws Exception {
        try (var in = new ClassPathResource("controle/sample.json").getInputStream()) {
            return in.readAllBytes();
        }
    }

    /** Load sample.json, modify a single field by JSON path, return the new bytes. */
    private byte[] sampleJsonModified(java.util.function.Consumer<JsonNode> modifier) throws Exception {
        JsonNode root = objectMapper.readTree(sampleJsonBytes());
        modifier.accept(root);
        return objectMapper.writeValueAsBytes(root);
    }

    // ── CI-01 ──────────────────────────────────────────────────────────────

    @Test
    void importSample_tablesMatchImportResult() throws Exception {
        var result = service.importFile(ownerAuth, sampleJsonBytes());

        assertThat(result.lines()).isEqualTo(5);
        assertThat(result.stLines()).isEqualTo(3);
        assertThat(result.accounts()).isEqualTo(3);
        assertThat(result.orphanJustifications()).isZero();

        // 3 accounts
        Integer accounts = jdbc.queryForObject("select count(*) from ctl_account where org_id = ?", Integer.class, orgId);
        assertThat(accounts).isEqualTo(3);
        // 5 lines
        Integer lines = jdbc.queryForObject("select count(*) from ctl_line where org_id = ?", Integer.class, orgId);
        assertThat(lines).isEqualTo(5);
        // 1 stAccount
        Integer stAccounts = jdbc.queryForObject("select count(*) from ctl_st_account where org_id = ?", Integer.class, orgId);
        assertThat(stAccounts).isEqualTo(1);
        // 3 stLines
        Integer stLines = jdbc.queryForObject("select count(*) from ctl_st_line where org_id = ?", Integer.class, orgId);
        assertThat(stLines).isEqualTo(3);
        // 1 ctl_import row
        Integer imports = jdbc.queryForObject("select count(*) from ctl_import where org_id = ?", Integer.class, orgId);
        assertThat(imports).isEqualTo(1);

        // The line with id 't0000000005' has kind 'partenaire' and st_line_key 'st-line-1'
        String stLink = jdbc.queryForObject(
                "select st_line_key from ctl_line where org_id = ? and line_key = 't0000000005'",
                String.class, orgId);
        assertThat(stLink).isEqualTo("st-line-1");

        // amounts are stored with scale 3
        java.math.BigDecimal amountIn = jdbc.queryForObject(
                "select amount_in from ctl_line where org_id = ? and line_key = 't0000000001'",
                java.math.BigDecimal.class, orgId);
        assertThat(amountIn).isEqualByComparingTo(new java.math.BigDecimal("1000.000"));
    }

    // ── CI-02 ──────────────────────────────────────────────────────────────

    @Test
    void importTwice_noDuplicates_twoImportRows() throws Exception {
        service.importFile(ownerAuth, sampleJsonBytes());
        service.importFile(ownerAuth, sampleJsonBytes());

        // still 5 lines and 3 stLines (no duplicates)
        Integer lines = jdbc.queryForObject("select count(*) from ctl_line where org_id = ?", Integer.class, orgId);
        assertThat(lines).isEqualTo(5);
        Integer stLines = jdbc.queryForObject("select count(*) from ctl_st_line where org_id = ?", Integer.class, orgId);
        assertThat(stLines).isEqualTo(3);
        // 2 ctl_import rows
        Integer imports = jdbc.queryForObject("select count(*) from ctl_import where org_id = ?", Integer.class, orgId);
        assertThat(imports).isEqualTo(2);
    }

    // ── CI-03 ──────────────────────────────────────────────────────────────

    @Test
    void reImport_justificationsSurvive_orphansCounted() throws Exception {
        // First import
        service.importFile(ownerAuth, sampleJsonBytes());

        // Insert a justification for an existing line_key 't0000000003'
        jdbc.update("""
                insert into ctl_justification (org_id, line_key, statut, doc_type, reference, doc_amount, note, updated_by)
                values (?, 't0000000003', 'justifie', 'facture', 'F-FAKE-001', 50.000, 'justif sample', ?)
                """, orgId, ownerId);
        // Insert a justification for a key that does NOT exist in the file ('gone-key')
        jdbc.update("""
                insert into ctl_justification (org_id, line_key, statut, note, updated_by)
                values (?, 'gone-key', 'non-justifie', 'orphan sample', ?)
                """, orgId, ownerId);

        // Re-import
        var result = service.importFile(ownerAuth, sampleJsonBytes());

        // The justification for 't0000000003' is still there
        Integer stillThere = jdbc.queryForObject(
                "select count(*) from ctl_justification where org_id = ? and line_key = 't0000000003'",
                Integer.class, orgId);
        assertThat(stillThere).isEqualTo(1);

        // The justification for 'gone-key' is also still there (we never delete justifications)
        Integer orphanStillThere = jdbc.queryForObject(
                "select count(*) from ctl_justification where org_id = ? and line_key = 'gone-key'",
                Integer.class, orgId);
        assertThat(orphanStillThere).isEqualTo(1);

        // orphanJustifications == 1 (the 'gone-key' one)
        assertThat(result.orphanJustifications()).isEqualTo(1);
    }

    // ── CI-04 ──────────────────────────────────────────────────────────────

    @Test
    void metaLinesMismatch_importInvalid_previousRowsPreserved() throws Exception {
        // First, a successful import
        service.importFile(ownerAuth, sampleJsonBytes());
        assertThat(jdbc.queryForObject("select count(*) from ctl_line where org_id = ?", Integer.class, orgId))
                .isEqualTo(5);

        // Now a bad file: meta.lines = 99
        byte[] badJson = sampleJsonModified(root ->
                ((tools.jackson.databind.node.ObjectNode) root.get("meta")).put("lines", 99));

        assertThatThrownBy(() -> service.importFile(ownerAuth, badJson))
                .isInstanceOf(ControleImportService.ImportInvalidException.class)
                .satisfies(ex -> {
                    var e = (ControleImportService.ImportInvalidException) ex;
                    assertThat(e.getProblems()).anySatisfy(p -> assertThat(p).contains("meta.lines"));
                });

        // The previous import's 5 lines are still there (rollback)
        assertThat(jdbc.queryForObject("select count(*) from ctl_line where org_id = ?", Integer.class, orgId))
                .isEqualTo(5);
    }

    // ── CI-05 ──────────────────────────────────────────────────────────────

    @Test
    void lineWithUnknownAccount_importInvalidProblemNamesLineId() throws Exception {
        // Change line 't0000000003' acct to 'unknown'
        byte[] badJson = sampleJsonModified(root -> {
            var lines = (tools.jackson.databind.node.ArrayNode) root.get("lines");
            for (JsonNode ln : lines) {
                if ("t0000000003".equals(ln.get("id").asText())) {
                    ((tools.jackson.databind.node.ObjectNode) ln).put("acct", "unknown");
                    break;
                }
            }
        });

        assertThatThrownBy(() -> service.importFile(ownerAuth, badJson))
                .isInstanceOf(ControleImportService.ImportInvalidException.class)
                .satisfies(ex -> {
                    var e = (ControleImportService.ImportInvalidException) ex;
                    // The problem must name the line id 't0000000003' and the unknown acct 'unknown'
                    assertThat(e.getProblems()).anySatisfy(p -> {
                        assertThat(p).contains("t0000000003");
                        assertThat(p).contains("unknown");
                    });
                });

        // Nothing saved
        assertThat(jdbc.queryForObject("select count(*) from ctl_line where org_id = ?", Integer.class, orgId))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from ctl_import where org_id = ?", Integer.class, orgId))
                .isZero();
    }

    // ── CI-06 ──────────────────────────────────────────────────────────────

    @Test
    void orgBImports_orgARowsUnchanged() throws Exception {
        // Org A imports
        service.importFile(ownerAuth, sampleJsonBytes());
        Integer aLinesBefore = jdbc.queryForObject("select count(*) from ctl_line where org_id = ?", Integer.class, orgId);
        assertThat(aLinesBefore).isEqualTo(5);

        // Create org B + owner
        UUID orgB = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        String emailB = "ci.orgB." + userB + "@hydra.test";
        jdbc.update("insert into organization (id, name) values (?, ?)", orgB, "Org B");
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Org B Owner', true, true, false)
                """, userB, orgB, emailB, encoder.encode("OrgBPw2026!xyz"));
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)", userB, ownerRoleId, userB);
        Authentication authB = new UsernamePasswordAuthenticationToken(emailB, null,
                List.of(new SimpleGrantedAuthority("PERM_CTL_IMPORT")));

        // Org B imports the same file
        service.importFile(authB, sampleJsonBytes());

        // Org A's rows unchanged: still 5 lines, 1 ctl_import
        Integer aLinesAfter = jdbc.queryForObject("select count(*) from ctl_line where org_id = ?", Integer.class, orgId);
        assertThat(aLinesAfter).isEqualTo(5);
        Integer aImports = jdbc.queryForObject("select count(*) from ctl_import where org_id = ?", Integer.class, orgId);
        assertThat(aImports).isEqualTo(1);

        // Org B has its own 5 lines and 1 ctl_import
        Integer bLines = jdbc.queryForObject("select count(*) from ctl_line where org_id = ?", Integer.class, orgB);
        assertThat(bLines).isEqualTo(5);
        Integer bImports = jdbc.queryForObject("select count(*) from ctl_import where org_id = ?", Integer.class, orgB);
        assertThat(bImports).isEqualTo(1);

        // Cleanup
        jdbc.update("delete from ctl_justification where org_id = ?", orgB);
        jdbc.update("delete from ctl_line where org_id = ?", orgB);
        jdbc.update("delete from ctl_st_line where org_id = ?", orgB);
        jdbc.update("delete from ctl_account where org_id = ?", orgB);
        jdbc.update("delete from ctl_st_account where org_id = ?", orgB);
        jdbc.update("delete from ctl_import where org_id = ?", orgB);
        jdbc.update("delete from user_role where user_id = ?", userB);
        jdbc.update("delete from app_user where id = ?", userB);
        jdbc.update("delete from organization where id = ?", orgB);
    }

    // ── CI-07 ──────────────────────────────────────────────────────────────

    @Test
    void controllerWithoutCtlImportPermission_accessDenied() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(nonOwnerAuth);
        byte[] json = sampleJsonBytes();
        // Call the controller bean so the Spring proxy applies @PreAuthorize
        assertThrows(AccessDeniedException.class, () -> controller.importFile(
                new org.springframework.mock.web.MockMultipartFile("file", "sample.json",
                        "application/json", json),
                nonOwnerAuth));

        // Nothing saved
        assertThat(jdbc.queryForObject("select count(*) from ctl_line where org_id = ?", Integer.class, orgId))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from ctl_import where org_id = ?", Integer.class, orgId))
                .isZero();
    }

    // ── CI-08 ──────────────────────────────────────────────────────────────

    @Test
    void notJsonBytes_importFormat() {
        byte[] notJson = "ceci n'est pas du json".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> service.importFile(ownerAuth, notJson))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "IMPORT_FORMAT");

        // Nothing saved
        assertThat(jdbc.queryForObject("select count(*) from ctl_import where org_id = ?", Integer.class, orgId))
                .isZero();
    }
}
