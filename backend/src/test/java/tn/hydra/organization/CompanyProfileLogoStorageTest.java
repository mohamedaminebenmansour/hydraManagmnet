package tn.hydra.organization;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tn.hydra.document.service.FileStorage;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;

/**
 * CompanyProfileLogoStorageTest — CO-06b and CO-06c.
 *
 * This class is NOT annotated @Transactional at class level, so each test
 * commits its work. We clean up our own rows in @AfterEach (and the storage
 * root is the test-isolated ./target/test-files directory).
 *
 * CO-06b: after uploadLogo returns (committed), the old logo file is gone and
 *         the new one exists in storage.
 * CO-06c: if the DB step fails AFTER storage.save(newKey), the new file must
 *         be deleted (no orphan). We force the DB failure with a @SpyBean on
 *         JdbcTemplate that throws DataIntegrityViolationException when the
 *         company_profile UPDATE is attempted — this happens AFTER
 *         storage.save(newKey) in the uploadLogo flow.
 */
@SpringBootTest
class CompanyProfileLogoStorageTest {

    @Autowired private CompanyProfileController controller;
    @Autowired private CompanyProfileService service;
    @Autowired private FileStorage storage;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private JdbcTemplate jdbcSpy;

    private UUID orgId;
    private UUID ownerId;
    private Authentication ownerAuth;

    private static final byte[] REAL_PNG_A = pngWithLetter((byte) 'A');
    private static final byte[] REAL_PNG_B = pngWithLetter((byte) 'B');

    /** Build a small valid PNG whose IDAT data contains the given byte. */
    private static byte[] pngWithLetter(byte marker) {
        return new byte[] {
                (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
                0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
                0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4,
                (byte) 0x89, 0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41,
                0x54, 0x08, (byte) 0xD7, 0x63, (byte) 0xF8, (byte) 0xCF, marker, 0x00,
                0x00, 0x00, 0x03, 0x00, 0x01, 0x5B, (byte) 0x6E, 0x0A,
                0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
                (byte) 0xAE, 0x42, 0x60, (byte) 0x82
        };
    }

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        String ownerEmail = "co.logo." + ownerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Logo Test Org " + orgId);
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Owner', true, true, false)
                """, ownerId, orgId, ownerEmail, encoder.encode("OwnerStrong2026!xyz"));
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)", ownerId, ownerRoleId, ownerId);

        ownerAuth = new UsernamePasswordAuthenticationToken(ownerEmail, null,
                List.of(new SimpleGrantedAuthority("PERM_ORG_SETTINGS")));
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);
    }

    @AfterEach
    void cleanupWorld() {
        SecurityContextHolder.clearContext();
        // Clean up any rows + files left from this test
        try {
            List<String> keys = jdbc.queryForList(
                    "select logo_key from company_profile where org_id = ? and logo_key is not null",
                    String.class, orgId);
            for (String k : keys) {
                try { storage.delete(k); } catch (RuntimeException ignored) {}
            }
        } catch (RuntimeException ignored) {}
        jdbc.update("delete from groupement_member where org_id = ?", orgId);
        jdbc.update("delete from groupement where org_id = ?", orgId);
        jdbc.update("delete from company_profile where org_id = ?", orgId);
        jdbc.update("delete from user_role where user_id = ?", ownerId);
        jdbc.update("delete from app_user where id = ?", ownerId);
        jdbc.update("delete from organization where id = ?", orgId);
    }

    private String currentLogoKey() {
        return jdbc.queryForObject(
                "select logo_key from company_profile where org_id = ?", String.class, orgId);
    }

    // CO-06b: upload PNG A, read logo_key A; upload PNG B; after the call returns
    // (committed) storage.exists(keyA) is false and storage.exists(keyB) is true.
    @Test
    void uploadTwice_oldLogoDeleted_newLogoExists() {
        // First upload — A
        var pngA = new MockMultipartFile("file", "logoA.png", "image/png", REAL_PNG_A);
        controller.uploadLogo(pngA, ownerAuth);
        String keyA = currentLogoKey();
        assertThat(keyA).isNotNull();
        assertThat(storage.exists(keyA)).isTrue();

        // Second upload — B
        var pngB = new MockMultipartFile("file", "logoB.png", "image/png", REAL_PNG_B);
        controller.uploadLogo(pngB, ownerAuth);
        String keyB = currentLogoKey();
        assertThat(keyB).isNotNull();
        assertThat(keyB).isNotEqualTo(keyA);

        // The old key is deleted after commit, the new key is present.
        assertThat(storage.exists(keyB)).isTrue();
        assertThat(storage.exists(keyA)).as("old logo file should be deleted after commit").isFalse();
    }

    // CO-06c: make the database step fail AFTER storage.save(newKey); the new key
    // must NOT exist in storage afterwards (rollback deletes the just-saved file).
    //
    // We pre-create the profile row so the UPDATE path is taken in uploadLogo.
    // Then we @SpyBean-stub the JdbcTemplate.update that matches the company_profile
    // UPDATE to throw DataIntegrityViolationException. This happens AFTER
    // storage.save(newKey) — exactly the case the rollback hook is designed for.
    @Test
    void dbFailureAfterStorageSave_orphanDeleted() {
        // Pre-create the profile row so the UPDATE path is taken.
        jdbc.update("""
                insert into company_profile (org_id, raison_sociale, version, updated_by)
                values (?, 'Pre-existing', 1, ?)
                """, orgId, ownerId);

        // Stub the UPDATE that sets logo_key to throw a DataIntegrityViolationException.
        // The real SELECT before it succeeds (returns the pre-created row), and
        // storage.save runs for real (it's not stubbed), so we genuinely test the
        // "after storage.save, DB fails → rollback deletes new file" path.
        doThrow(new DataIntegrityViolationException("simulated DB failure for CO-06c"))
                .when(jdbcSpy)
                .update(contains("update company_profile set logo_key"),
                        any(Object[].class));

        var png = new MockMultipartFile("file", "logoFail.png", "image/png", REAL_PNG_A);

        // The upload should fail somehow (either the DataIntegrityViolationException
        // propagates, or the GlobalExceptionHandler maps it to a 400 response).
        boolean failed;
        try {
            var response = controller.uploadLogo(png, ownerAuth);
            failed = response.getStatusCode().isError();
        } catch (DataIntegrityViolationException expected) {
            failed = true;
        } catch (RuntimeException expected) {
            failed = true;
        }
        assertThat(failed).as("uploadLogo should have failed after storage.save").isTrue();

        // The just-saved file must NOT exist in storage anymore (the
        // afterCompletion(STATUS_ROLLED_BACK) hook deleted it).
        List<String> orgKeys = listStorageKeysForOrg();
        assertThat(orgKeys).as("no orphan logo file left after rollback").isEmpty();
    }

    @AfterEach
    void resetSpy() {
        // Clear any stubs set during the test so they don't leak to the next test
        // sharing the same Spring context.
        try { org.mockito.Mockito.reset(jdbcSpy); } catch (RuntimeException ignored) {}
    }

    /** List every file under the org prefix that currently exists in storage. */
    private List<String> listStorageKeysForOrg() {
        String prefix = "org/" + orgId + "/company/logo-";
        java.nio.file.Path root = java.nio.file.Paths.get(
                System.getProperty("hydra.storage.root",
                        java.util.Optional.ofNullable(System.getenv("HYDRA_STORAGE_ROOT"))
                                .orElse("./target/test-files")));
        if (!java.nio.file.Files.exists(root)) return List.of();
        java.nio.file.Path dir = root.resolve("org/" + orgId + "/company");
        if (!java.nio.file.Files.exists(dir)) return List.of();
        try (var stream = java.nio.file.Files.walk(dir)) {
            return stream.filter(java.nio.file.Files::isRegularFile)
                    .map(root::relativize)
                    .map(java.nio.file.Path::toString)
                    .map(s -> s.replace('\\', '/'))
                    .filter(s -> s.startsWith(prefix))
                    .toList();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }
}
