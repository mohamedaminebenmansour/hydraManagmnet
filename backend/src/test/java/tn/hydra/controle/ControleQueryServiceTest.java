package tn.hydra.controle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ControleQueryServiceTest — CR-01..CR-05.
 *
 * Same test style as ControleImportServiceTest: own orgs per test, load
 * sample.json from the classpath, import it via ControleImportService first.
 */
@SpringBootTest
@Transactional
@Rollback
class ControleQueryServiceTest {

    @Autowired private ControleQueryService queryService;
    @Autowired private ControleQueryController queryController;
    @Autowired private ControleImportService importService;
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
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        nonOwnerId = UUID.randomUUID();
        ownerEmail = "cr.owner." + ownerId + "@hydra.test";
        nonOwnerEmail = "cr.nonowner." + nonOwnerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "CR Test Org " + orgId);
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Owner', true, true, false)
                """, ownerId, orgId, ownerEmail, encoder.encode("OwnerStrong2026!xyz"));
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)", ownerId, ownerRoleId, ownerId);

        // Non-owner: BUREAU role (no CTL_READ)
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
                List.of(new SimpleGrantedAuthority("PERM_TICKET_READ")));
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    /** Load sample.json from classpath and import it for my org. */
    private void importSample() throws Exception {
        try (var in = new org.springframework.core.io.ClassPathResource("controle/sample.json").getInputStream()) {
            importService.importFile(ownerAuth, in.readAllBytes());
        }
    }

    /** Load sample.json, modify it, return the bytes. */
    private byte[] sampleJsonModified(java.util.function.Consumer<JsonNode> modifier) throws Exception {
        try (var in = new org.springframework.core.io.ClassPathResource("controle/sample.json").getInputStream()) {
            JsonNode root = objectMapper.readTree(in.readAllBytes());
            modifier.accept(root);
            return objectMapper.writeValueAsBytes(root);
        }
    }

    // ── CR-01 ──────────────────────────────────────────────────────────────

    @Test
    void getData_returnsAllRows_metaAndImportedMatch() throws Exception {
        importSample();

        var data = queryService.getData(ownerAuth);

        assertThat(data.accounts()).hasSize(3);
        assertThat(data.lines()).hasSize(5);
        assertThat(data.stLines()).hasSize(3);
        assertThat(data.stAccounts()).hasSize(1);
        assertThat(data.meta()).isNotNull();
        assertThat(data.meta().lines()).isEqualTo(5);
        assertThat(data.meta().stLines()).isEqualTo(3);
        assertThat(data.imported()).isNotNull();
        assertThat(data.imported().lineCount()).isEqualTo(5);
        assertThat(data.imported().stLineCount()).isEqualTo(3);
        assertThat(data.imported().importerName()).isEqualTo("Owner");
        assertThat(data.justif()).isEmpty();
        assertThat(data.valid()).isEmpty();
    }

    // ── CR-02 ──────────────────────────────────────────────────────────────

    @Test
    void nullDateComesBackAsNull_flagsComeBackAsList() throws Exception {
        // Modify sample.json: set t0000000004 date to "" (-> null in DB)
        byte[] json = sampleJsonModified(root -> {
            var lines = (ArrayNode) root.get("lines");
            for (JsonNode ln : lines) {
                if ("t0000000004".equals(ln.get("id").asText())) {
                    ((ObjectNode) ln).put("date", "");
                    break;
                }
            }
        });
        importService.importFile(ownerAuth, json);

        var data = queryService.getData(ownerAuth);

        // The line with id 't0000000004' has date null
        var line4 = data.lines().stream()
                .filter(l -> "t0000000004".equals(l.id()))
                .findFirst().orElseThrow();
        assertThat(line4.date()).isNull();

        // All other lines have a non-null date
        var line1 = data.lines().stream()
                .filter(l -> "t0000000001".equals(l.id()))
                .findFirst().orElseThrow();
        assertThat(line1.date()).isEqualTo("2024-01-15");

        // flags come back as a list (empty list, never null)
        for (var l : data.lines()) {
            assertThat(l.flags()).isNotNull();
            assertThat(l.flags()).isInstanceOf(List.class);
        }
        // stLines flags too
        for (var s : data.stLines()) {
            assertThat(s.flags()).isNotNull();
            assertThat(s.flags()).isInstanceOf(List.class);
        }
    }

    // ── CR-03 ──────────────────────────────────────────────────────────────

    @Test
    void orgBWithoutImport_getsNullMetaEmptyLists_neverOrgARows() throws Exception {
        // Org A imports
        importSample();

        // Create org B + owner (no import)
        UUID orgB = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        String emailB = "cr.orgB." + userB + "@hydra.test";
        jdbc.update("insert into organization (id, name) values (?, ?)", orgB, "Org B");
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Org B Owner', true, true, false)
                """, userB, orgB, emailB, encoder.encode("OrgBPw2026!xyz"));
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)", userB, ownerRoleId, userB);
        Authentication authB = new UsernamePasswordAuthenticationToken(emailB, null,
                List.of(new SimpleGrantedAuthority("PERM_CTL_READ")));

        var dataB = queryService.getData(authB);

        // Org B gets null meta, null imported, and empty lists
        assertThat(dataB.meta()).isNull();
        assertThat(dataB.imported()).isNull();
        assertThat(dataB.accounts()).isEmpty();
        assertThat(dataB.stAccounts()).isEmpty();
        assertThat(dataB.lines()).isEmpty();
        assertThat(dataB.stLines()).isEmpty();
        assertThat(dataB.justif()).isEmpty();
        assertThat(dataB.valid()).isEmpty();

        // Org A's rows are NOT visible to org B — verify org A still has its data
        var dataA = queryService.getData(ownerAuth);
        assertThat(dataA.accounts()).hasSize(3);
        assertThat(dataA.lines()).hasSize(5);

        // Cleanup
        jdbc.update("delete from user_role where user_id = ?", userB);
        jdbc.update("delete from app_user where id = ?", userB);
        jdbc.update("delete from organization where id = ?", orgB);
    }

    // ── CR-04 ──────────────────────────────────────────────────────────────

    @Test
    void controllerWithoutCtlRead_accessDenied() {
        SecurityContextHolder.getContext().setAuthentication(nonOwnerAuth);
        assertThrows(AccessDeniedException.class, () -> queryController.getData(nonOwnerAuth));
    }

    // ── CR-05 ──────────────────────────────────────────────────────────────

    @Test
    void justificationInsertedBySql_appearsInJustifWithUpdatedByName() throws Exception {
        importSample();

        // Insert a justification for line_key 't0000000003' by SQL
        jdbc.update("""
                insert into ctl_justification (org_id, line_key, statut, doc_type, reference, doc_date, doc_amount, note, version, updated_by, updated_at)
                values (?, 't0000000003', 'justifie', 'Facture', 'F-01', '2024-01-25', 6500.000, 'justif test', 1, ?, now())
                """, orgId, ownerId);

        var data = queryService.getData(ownerAuth);

        assertThat(data.justif()).hasSize(1);
        var j = data.justif().get(0);
        assertThat(j.lineKey()).isEqualTo("t0000000003");
        assertThat(j.statut()).isEqualTo("justifie");
        assertThat(j.version()).isEqualTo(1);
        assertThat(j.updatedByName()).isEqualTo("Owner");
        assertThat(j.docType()).isEqualTo("Facture");
        assertThat(j.reference()).isEqualTo("F-01");
    }
}
