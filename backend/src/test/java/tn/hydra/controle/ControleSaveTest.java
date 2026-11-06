package tn.hydra.controle;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.organization.CompanyProfileService;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ControleSaveTest — CS-01..CS-08.
 *
 * Tests the PUT endpoints for justifications and validations.
 * Imports the test sample.json first (same pattern as CI/CR tests).
 */
@SpringBootTest
@Transactional
@Rollback
class ControleSaveTest {

    @Autowired private ControleJustificationService justifService;
    @Autowired private ControleJustificationController justifController;
    @Autowired private ControleValidationService validService;
    @Autowired private ControleValidationController validController;
    @Autowired private ControleQueryService queryService;
    @Autowired private ControleImportService importService;
    @Autowired private JdbcTemplate jdbc;

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
        ownerEmail = "cs.owner." + ownerId + "@hydra.test";
        nonOwnerEmail = "cs.nonowner." + nonOwnerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "CS Test Org " + orgId);
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Owner', true, true, false)
                """, ownerId, orgId, ownerEmail, encoder.encode("OwnerStrong2026!xyz"));
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)", ownerId, ownerRoleId, ownerId);

        // Non-owner: BUREAU role, only CTL_READ
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

        // Import sample.json
        try (var in = new org.springframework.core.io.ClassPathResource("controle/sample.json").getInputStream()) {
            importService.importFile(ownerAuth, in.readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    private ControleJustificationService.JustifBody justifBody(String statut, int version) {
        return new ControleJustificationService.JustifBody(
                statut, "Facture", "F-01", "2024-01-25",
                new BigDecimal("6500.000"), "note test", version);
    }

    // ── CS-01 ──────────────────────────────────────────────────────────────

    @Test
    void putJustif_version0_then_version1_then_stale() {
        // version 0 -> insert, version 1
        var r1 = justifService.putJustification(ownerAuth, "t0000000002", justifBody("justifie", 0));
        assertThat(r1).isInstanceOf(ControleQueryService.CtlJustif.class);
        var j1 = (ControleQueryService.CtlJustif) r1;
        assertThat(j1.version()).isEqualTo(1);
        assertThat(j1.lineKey()).isEqualTo("t0000000002");
        assertThat(j1.statut()).isEqualTo("justifie");
        assertThat(j1.updatedByName()).isEqualTo("Owner");

        // version 1 -> update, version 2
        var r2 = justifService.putJustification(ownerAuth, "t0000000002", justifBody("partiel", 1));
        var j2 = (ControleQueryService.CtlJustif) r2;
        assertThat(j2.version()).isEqualTo(2);
        assertThat(j2.statut()).isEqualTo("partiel");

        // version 1 again -> STALE (current is 2)
        assertThatThrownBy(() -> justifService.putJustification(ownerAuth, "t0000000002", justifBody("justifie", 1)))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "STALE");
    }

    // ── CS-02 ──────────────────────────────────────────────────────────────

    @Test
    void putJustif_onStLineKey_works() {
        var r = justifService.putJustification(ownerAuth, "st-line-3", justifBody("justifie", 0));
        var j = (ControleQueryService.CtlJustif) r;
        assertThat(j.lineKey()).isEqualTo("st-line-3");
        assertThat(j.version()).isEqualTo(1);
    }

    // ── CS-03 ──────────────────────────────────────────────────────────────

    @Test
    void putJustif_unknownKey_404() {
        assertThatThrownBy(() -> justifService.putJustification(ownerAuth, "unknown-key", justifBody("justifie", 0)))
                .isInstanceOf(tn.hydra.exception.ResourceNotFoundException.class)
                .hasFieldOrPropertyWithValue("code", "LINE_NOT_FOUND");
    }

    // ── CS-04 ──────────────────────────────────────────────────────────────

    @Test
    void putJustif_statutNone_deletesRow() {
        // First create a justification
        justifService.putJustification(ownerAuth, "t0000000003", justifBody("justifie", 0));
        assertThat(queryService.getData(ownerAuth).justif()).hasSize(1);

        // Delete it with statut "none"
        var r = justifService.putJustification(ownerAuth, "t0000000003",
                new ControleJustificationService.JustifBody("none", null, null, null, null, null, 1));
        assertThat(r).isEqualTo(java.util.Map.of("deleted", true));

        // getData().justif is empty
        assertThat(queryService.getData(ownerAuth).justif()).isEmpty();
    }

    // ── CS-05 ──────────────────────────────────────────────────────────────

    @Test
    void putJustif_multipleErrors_one400With3Fields() {
        // docAmount -5, reference 121 chars, docType "Bidon"
        String longRef = "x".repeat(121);
        var body = new ControleJustificationService.JustifBody(
                "justifie", "Bidon", longRef, "2024-01-25",
                new BigDecimal("-5.000"), "note", 0);

        assertThatThrownBy(() -> justifService.putJustification(ownerAuth, "t0000000002", body))
                .isInstanceOf(CompanyProfileService.ValidationException.class)
                .satisfies(ex -> {
                    var e = (CompanyProfileService.ValidationException) ex;
                    assertThat(e.getCode()).isEqualTo("VALIDATION");
                    assertThat(e.getFields()).containsOnlyKeys("docType", "reference", "docAmount");
                });

        // Nothing saved
        assertThat(queryService.getData(ownerAuth).justif()).isEmpty();
    }

    // ── CS-06 ──────────────────────────────────────────────────────────────

    @Test
    void orgBcannotUpdateOrgA_justification() {
        // Org A creates a justification
        justifService.putJustification(ownerAuth, "t0000000002", justifBody("justifie", 0));

        // Create org B + owner
        UUID orgB = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        String emailB = "cs.orgB." + userB + "@hydra.test";
        jdbc.update("insert into organization (id, name) values (?, ?)", orgB, "Org B");
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Org B Owner', true, true, false)
                """, userB, orgB, emailB, encoder.encode("OrgBPw2026!xyz"));
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)", userB, ownerRoleId, userB);
        Authentication authB = new UsernamePasswordAuthenticationToken(emailB, null,
                List.of(new SimpleGrantedAuthority("PERM_CTL_READ"),
                        new SimpleGrantedAuthority("PERM_CTL_JUSTIFY")));

        // B tries to PUT on A's line_key with version 1 -> 404 LINE_NOT_FOUND
        // (B has no ctl_line with that key)
        assertThatThrownBy(() -> justifService.putJustification(authB, "t0000000002", justifBody("justifie", 1)))
                .isInstanceOf(tn.hydra.exception.ResourceNotFoundException.class);

        // A's row is unchanged
        var j = queryService.getData(ownerAuth).justif().stream()
                .filter(x -> "t0000000002".equals(x.lineKey())).findFirst().orElseThrow();
        assertThat(j.version()).isEqualTo(1);
        assertThat(j.statut()).isEqualTo("justifie");

        // Cleanup
        jdbc.update("delete from user_role where user_id = ?", userB);
        jdbc.update("delete from app_user where id = ?", userB);
        jdbc.update("delete from organization where id = ?", orgB);
    }

    // ── CS-07 ──────────────────────────────────────────────────────────────

    @Test
    void validation_saved_badKeyRejected_noneRejected() {
        // Save a validation with a valid key
        var r = validService.putValidation(ownerAuth, "person-sous-traitant-factice",
                new ControleValidationService.ValidBody("valide", new BigDecimal("1000.000"), "ok", 0));
        assertThat(r.version()).isEqualTo(1);
        assertThat(r.statut()).isEqualTo("valide");
        assertThat(r.agreedBalance()).isEqualByComparingTo(new BigDecimal("1000.000"));

        // Bad key "bad key!" -> 400 VALIDATION with key field
        assertThatThrownBy(() -> validService.putValidation(ownerAuth, "bad key!",
                new ControleValidationService.ValidBody("valide", null, null, 0)))
                .isInstanceOf(CompanyProfileService.ValidationException.class)
                .satisfies(ex -> {
                    var e = (CompanyProfileService.ValidationException) ex;
                    assertThat(e.getFields()).containsKey("key");
                });

        // statut "none" -> 400 VALIDATION with statut field
        assertThatThrownBy(() -> validService.putValidation(ownerAuth, "person-test",
                new ControleValidationService.ValidBody("none", null, null, 0)))
                .isInstanceOf(CompanyProfileService.ValidationException.class)
                .satisfies(ex -> {
                    var e = (CompanyProfileService.ValidationException) ex;
                    assertThat(e.getFields()).containsKey("statut");
                });
    }

    // ── CS-08 ──────────────────────────────────────────────────────────────

    @Test
    void controllerWithoutCtlJustify_accessDeniedOnBothPuts() {
        SecurityContextHolder.getContext().setAuthentication(nonOwnerAuth);
        assertThrows(AccessDeniedException.class, () -> justifController.putJustification(
                "t0000000002", justifBody("justifie", 0), nonOwnerAuth));
        assertThrows(AccessDeniedException.class, () -> validController.putValidation(
                "person-test",
                new ControleValidationService.ValidBody("valide", null, null, 0),
                nonOwnerAuth));
    }

    // ── CS-09 ──────────────────────────────────────────────────────────────

    @Test
    void putJustif_nonJustifie_withoutDocType_saved() {
        var body = new ControleJustificationService.JustifBody(
                "non-justifie", null, null, null, null, null, 0);
        var r = justifService.putJustification(ownerAuth, "t0000000003", body);
        assertThat(r).isInstanceOf(ControleQueryService.CtlJustif.class);
        var j = (ControleQueryService.CtlJustif) r;
        assertThat(j.statut()).isEqualTo("non-justifie");
        assertThat(j.docType()).isNull();
        assertThat(j.version()).isEqualTo(1);
    }

    // ── CS-10 ──────────────────────────────────────────────────────────────

    @Test
    void putJustif_justifie_withoutDocType_400() {
        var body = new ControleJustificationService.JustifBody(
                "justifie", null, null, null, null, null, 0);
        assertThatThrownBy(() -> justifService.putJustification(ownerAuth, "t0000000004", body))
                .isInstanceOf(CompanyProfileService.ValidationException.class)
                .satisfies(ex -> {
                    var e = (CompanyProfileService.ValidationException) ex;
                    assertThat(e.getFields()).containsOnlyKeys("docType");
                });
    }
}
