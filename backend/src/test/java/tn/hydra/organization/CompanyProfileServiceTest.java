package tn.hydra.organization;

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
import tn.hydra.common.BusinessRuleException;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CompanyProfileServiceTest — CO-03..CO-07, CO-12, CO-16, CO-17.
 *
 * CO-03 and CO-06 call the CONTROLLER beans (autowired) so the Spring proxy
 * applies @PreAuthorize. CO-06b/CO-06c (which require commits) live in a
 * separate non-@Transactional test class: CompanyProfileLogoStorageTest.
 */
@SpringBootTest
@Transactional
@Rollback
class CompanyProfileServiceTest {

    @Autowired private CompanyProfileService service;
    @Autowired private CompanyProfileController controller;
    @Autowired private GroupementController groupementController;
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
        ownerEmail = "co.owner." + ownerId + "@hydra.test";
        nonOwnerEmail = "co.nonowner." + nonOwnerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "CO Test Org " + orgId);
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Owner', true, true, false)
                """, ownerId, orgId, ownerEmail, encoder.encode("OwnerStrong2026!xyz"));
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)", ownerId, ownerRoleId, ownerId);

        // non-owner user (no ORG_SETTINGS — only BUREAU which has no ORG_SETTINGS permission)
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Non Owner', false, true, false)
                """, nonOwnerId, orgId, nonOwnerEmail, encoder.encode("NonOwnerPw2026!xyz"));
        UUID bureauRoleId = jdbc.queryForObject("select id from role where code = 'BUREAU'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)", nonOwnerId, bureauRoleId, ownerId);

        ownerAuth = new UsernamePasswordAuthenticationToken(ownerEmail, null,
                List.of(new SimpleGrantedAuthority("PERM_ORG_SETTINGS")));
        nonOwnerAuth = new UsernamePasswordAuthenticationToken(nonOwnerEmail, null,
                List.of(new SimpleGrantedAuthority("PERM_TICKET_READ")));
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    // ── Real 1×1 PNG bytes (67 bytes) ────────────────────────────────────
    private static final byte[] REAL_PNG = new byte[] {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
            (byte) 0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
            0x54, 0x08, (byte) 0xD7, 0x63, (byte) 0xF8, (byte) 0xCF, (byte) 0xC0, 0x00,
            0x00, 0x00, 0x03, 0x00, 0x01, 0x5B, (byte) 0x6E, 0x0A,
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            (byte) 0xAE, 0x42, 0x60, (byte) 0x82
    };

    // CO-03: user without PERM_ORG_SETTINGS → AccessDeniedException; profile row unchanged.
    // Then with the owner, the same call works. We also exercise GroupementController.create.
    @Test
    void update_withoutPermission_denied_then_ownerWorks() {
        SecurityContextHolder.getContext().setAuthentication(nonOwnerAuth);
        var body = new CompanyProfileService.UpdateRequest(
                "TN", "Hacked SARL", null, "SARL", null, null,
                null, true, "Hacked address", "1000", "Tunis", "Tunis",
                "+216 71 000 000", "hacked@hydra.test", null,
                null, null, 0);

        // Calling the controller bean through the Spring proxy applies @PreAuthorize.
        assertThrows(AccessDeniedException.class, () -> controller.update(body, nonOwnerAuth));

        // Profile row unchanged: nothing was inserted.
        Integer n = jdbc.queryForObject("select count(*) from company_profile where org_id = ?", Integer.class, orgId);
        assertThat(n).isZero();

        // Now the owner calls the same endpoint: it works and a row is created.
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);
        var saved = controller.update(body, ownerAuth);
        assertThat(saved.getStatusCode().is2xxSuccessful()).isTrue();
        var savedBody = (CompanyProfileService.CompanyProfileResponse) saved.getBody();
        assertThat(savedBody.raisonSociale()).isEqualTo("Hacked SARL");
        assertThat(savedBody.version()).isEqualTo(1);

        // Same gate on GroupementController.create
        SecurityContextHolder.getContext().setAuthentication(nonOwnerAuth);
        var grpBody = new GroupementService.GroupementInput(
                "Hacked groupement", "SOLIDAIRE", null, null, null, 0,
                List.of(new GroupementService.MemberInput("A", null, "TN", "MANDATAIRE",
                                BigDecimal.valueOf(50), null, true),
                        new GroupementService.MemberInput("B", null, "TN", "MEMBRE",
                                BigDecimal.valueOf(50), null, false)));
        assertThrows(AccessDeniedException.class, () -> groupementController.create(grpBody, nonOwnerAuth));

        Integer g = jdbc.queryForObject("select count(*) from groupement where org_id = ?", Integer.class, orgId);
        assertThat(g).isZero();
    }

    // CO-04: optimistic lock — two updates with same version
    @Test
    void update_optimisticLock_secondFails() {
        var first = service.update(ownerAuth, new CompanyProfileService.UpdateRequest(
                "TN", "Test SARL", null, "SARL", null, null,
                null, true, "Zone industrielle", "1000", "Tunis", "Tunis",
                "+216 71 000 000", "test@hydra.test", null,
                "Banque Démo", null, 0));
        assertThat(first.version()).isEqualTo(1);

        // Second update with same version 0 → STALE_PROFILE
        assertThatThrownBy(() -> service.update(ownerAuth, new CompanyProfileService.UpdateRequest(
                "TN", "Test SARL 2", null, null, null, null,
                null, true, null, null, "Sfax", null,
                null, null, null, null, null, 0)))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "STALE_PROFILE");

        // First values kept
        var current = service.get(ownerAuth);
        assertThat(current.raisonSociale()).isEqualTo("Test SARL");
        assertThat(current.ville()).isEqualTo("Tunis");
    }

    // CO-05: audit contains rib and ville only, RIB masked
    @Test
    void update_auditContainsChangedFieldsOnly_ribMasked() {
        // First create a profile
        service.update(ownerAuth, new CompanyProfileService.UpdateRequest(
                "TN", "Test SARL", null, null, null, null,
                null, true, null, null, null, null,
                null, null, null, null, null, 0));

        // Now update RIB and ville
        service.update(ownerAuth, new CompanyProfileService.UpdateRequest(
                "TN", "Test SARL", null, null, null, null,
                null, true, null, null, "Sfax", null,
                null, null, null, "Banque Démo", "07040005810111129653", 1));

        // Check audit
        String details = jdbc.queryForObject(
                "select details::text from event_log where entity_id = ? and action = 'COMPANY_PROFILE_UPDATED' order by id desc limit 1",
                String.class, orgId);
        assertThat(details).contains("ville");
        assertThat(details).contains("rib");
        // The full RIB must not appear in audit (only last 4 digits)
        assertThat(details).doesNotContain("07040005810111129653");
        assertThat(details).contains("9653");
    }

    // CO-06: logo validation through the controller (real @PreAuthorize + magic bytes + size).
    // The controller returns 400 with body code LOGO_TOO_LARGE / LOGO_TYPE; 200 for a real PNG.
    @Test
    void logo_svgRefused_textRenamedPngRefused_tooLargeRefused_realPngAccepted() {
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);

        // 1) 1 MB + 1 byte of PNG header + zeros → 400 LOGO_TOO_LARGE
        byte[] tooLarge = new byte[1_048_577];
        tooLarge[0] = (byte) 0x89; tooLarge[1] = 0x50; tooLarge[2] = 0x4E; tooLarge[3] = 0x47;
        tooLarge[4] = 0x0D; tooLarge[5] = 0x0A; tooLarge[6] = 0x1A; tooLarge[7] = 0x0A;
        var tooLargeFile = new MockMultipartFile("file", "logo.png", "image/png", tooLarge);
        var r1 = controller.uploadLogo(tooLargeFile, ownerAuth);
        assertThat(r1.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        var b1 = (java.util.Map<String, Object>) r1.getBody();
        assertThat(b1.get("code")).isEqualTo("LOGO_TOO_LARGE");

        // 2) SVG content named logo.svg → 400 LOGO_TYPE
        var svgFile = new MockMultipartFile("file", "logo.svg", "image/svg+xml",
                "<svg></svg>".getBytes());
        var r2 = controller.uploadLogo(svgFile, ownerAuth);
        assertThat(r2.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        var b2 = (java.util.Map<String, Object>) r2.getBody();
        assertThat(b2.get("code")).isEqualTo("LOGO_TYPE");

        // 3) Text content "not an image" named logo.png with content type image/png → 400 LOGO_TYPE
        var textFile = new MockMultipartFile("file", "logo.png", "image/png",
                "not an image".getBytes());
        var r3 = controller.uploadLogo(textFile, ownerAuth);
        assertThat(r3.getStatusCode().value()).isEqualTo(400);
        @SuppressWarnings("unchecked")
        var b3 = (java.util.Map<String, Object>) r3.getBody();
        assertThat(b3.get("code")).isEqualTo("LOGO_TYPE");

        // 4) Real 1×1 PNG → 200 (logo saved)
        var pngFile = new MockMultipartFile("file", "logo.png", "image/png", REAL_PNG);
        var r4 = controller.uploadLogo(pngFile, ownerAuth);
        assertThat(r4.getStatusCode().is2xxSuccessful()).isTrue();

        var profile = service.get(ownerAuth);
        assertThat(profile.hasLogo()).isTrue();
    }

    // CO-07: org isolation
    @Test
    void orgIsolation_orgBCannotSeeOrgA() {
        // Org A saves a profile
        service.update(ownerAuth, new CompanyProfileService.UpdateRequest(
                "TN", "Org A SARL", null, null, null, null,
                null, true, "Address A", "1000", "Tunis", null,
                "+216 71 111 111", "a@hydra.test", null, null, null, 0));

        // Create org B + user
        UUID orgB = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        String emailB = "co.orgB." + userB + "@hydra.test";
        jdbc.update("insert into organization (id, name) values (?, ?)", orgB, "Org B");
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Org B Owner', true, true, false)
                """, userB, orgB, emailB, encoder.encode("OrgBPw2026!xyz"));
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)", userB, ownerRoleId, userB);
        Authentication authB = new UsernamePasswordAuthenticationToken(emailB, null,
                List.of(new SimpleGrantedAuthority("PERM_ORG_SETTINGS")));

        // Org B reads its profile → should see Org B name, not Org A's
        var profileB = service.get(authB);
        assertThat(profileB.raisonSociale()).isEqualTo("Org B");
        assertThat(profileB.ville()).isNull(); // Org A's ville is "Tunis", not visible to B

        // Clean up org B
        jdbc.update("delete from user_role where user_id = ?", userB);
        jdbc.update("delete from app_user where id = ?", userB);
        jdbc.update("delete from organization where id = ?", orgB);
    }

    // CO-12: non-Tunisian country (Senegal) — MF and RIB free text
    @Test
    void paysSenegal_mfAndRibFreeText_noTunisianCheck() {
        service.update(ownerAuth, new CompanyProfileService.UpdateRequest(
                "SN", "Senegal SARL", null, null, "SN-12345", null,
                null, true, null, null, "Dakar", null,
                null, null, null, null, "SN08SN0100152000000000000000000", 0));

        var profile = service.get(ownerAuth);
        assertThat(profile.pays()).isEqualTo("SN");
        assertThat(profile.matriculeFiscal()).isEqualTo("SN-12345");
        assertThat(profile.rib()).isEqualTo("SN08SN0100152000000000000000000");
        // No Tunisian formatting applied
        assertThat(profile.mfFormate()).isNull();
        assertThat(profile.iban()).isNull();
    }

    // CO-16: saving the profile twice with the same values → only one audit row, version unchanged.
    @Test
    void saveTwice_sameValues_oneAuditRow_versionUnchanged() {
        var req = new CompanyProfileService.UpdateRequest(
                "TN", "Same SARL", null, "SARL", "1234567/A/A/M/000", null,
                new BigDecimal("50000"), true, "Zone industrielle", "1000", "Tunis", "Tunis",
                "+216 71 000 000", "test@hydra.test", null,
                "Banque Démo", "07040005810111129653", 0);

        var first = service.update(ownerAuth, req);
        assertThat(first.version()).isEqualTo(1);

        long auditAfterFirst = jdbc.queryForObject(
                "select count(*) from event_log where entity_id = ? and action = 'COMPANY_PROFILE_UPDATED'",
                Long.class, orgId);
        assertThat(auditAfterFirst).isEqualTo(1L);

        // Second save with the SAME values, using the bumped version
        var second = service.update(ownerAuth, new CompanyProfileService.UpdateRequest(
                "TN", "Same SARL", null, "SARL", "1234567/A/A/M/000", null,
                new BigDecimal("50000"), true, "Zone industrielle", "1000", "Tunis", "Tunis",
                "+216 71 000 000", "test@hydra.test", null,
                "Banque Démo", "07040005810111129653", 1));

        // Version unchanged (no bump), no new audit row.
        assertThat(second.version()).isEqualTo(1);
        long auditAfterSecond = jdbc.queryForObject(
                "select count(*) from event_log where entity_id = ? and action = 'COMPANY_PROFILE_UPDATED'",
                Long.class, orgId);
        assertThat(auditAfterSecond).isEqualTo(1L);
    }

    // CO-17: capital 50000 then 50000.000 → no audit row for capital (BigDecimal.compareTo equality).
    // The second save with compareTo-equal capital must NOT create a new audit row at all.
    @Test
    void capital_50000_then_50000_000_noAuditRowForCapital() {
        service.update(ownerAuth, new CompanyProfileService.UpdateRequest(
                "TN", "Capital SARL", null, null, null, null,
                new BigDecimal("50000"), true, null, null, null, null,
                null, null, null, null, null, 0));

        long auditAfterFirst = jdbc.queryForObject(
                "select count(*) from event_log where entity_id = ? and action = 'COMPANY_PROFILE_UPDATED'",
                Long.class, orgId);
        assertThat(auditAfterFirst).isEqualTo(1L);

        // Save with the same value as a different scale: 50000.000 == 50000 (compareTo)
        var second = service.update(ownerAuth, new CompanyProfileService.UpdateRequest(
                "TN", "Capital SARL", null, null, null, null,
                new BigDecimal("50000.000"), true, null, null, null, null,
                null, null, null, null, null, 1));

        // No new audit row was written — the count is still 1.
        long auditAfterSecond = jdbc.queryForObject(
                "select count(*) from event_log where entity_id = ? and action = 'COMPANY_PROFILE_UPDATED'",
                Long.class, orgId);
        assertThat(auditAfterSecond).isEqualTo(1L);
        // Version was NOT bumped.
        assertThat(second.version()).isEqualTo(1);
    }
}
