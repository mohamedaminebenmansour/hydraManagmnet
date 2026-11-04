package tn.hydra.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.mail.MailService;
import tn.hydra.security.PasswordPolicy;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;
import tn.hydra.user.UserService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ============================================================================
 *  PasswordResetTest — real-database tests for U2 (forgotten password,
 *  invitation links, optional email).
 * ============================================================================
 *
 *  <p>Uses a fake {@link MailService} bean that records every message so we can
 *  assert what was sent without touching SMTP.
 * ============================================================================
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:postgresql://127.0.0.1:5432/hydra_test",
        "DB_USER=postgres",
        "DB_PASSWORD=hydra",
        "hydra.app-url=http://test.hydra.local"
})
class PasswordResetTest {

    /** A fake MailService that records every message and always reports enabled. */
    static class RecordingMailService implements MailService {
        final List<RecordedMessage> sent = new ArrayList<>();
        private final boolean enabled;

        RecordingMailService(boolean enabled) { this.enabled = enabled; }

        @Override public boolean isEnabled() { return enabled; }
        @Override public void send(String to, String subject, String html) {
            sent.add(new RecordedMessage(to, subject, html));
        }

        void reset() { sent.clear(); }
        record RecordedMessage(String to, String subject, String html) {}
    }

    @TestConfiguration
    static class TestMailConfig {
        @Bean @Primary
        MailService mailService() { return new RecordingMailService(true); }
    }

    @Autowired private PasswordResetController resetController;
    @Autowired private PasswordTokenService tokens;
    @Autowired private UserService users;
    @Autowired private AppUserRepository userRepo;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordPolicy passwordPolicy;
    @Autowired private MailService mailService; // the recording bean

    private UUID orgId;
    private UUID ownerId;
    private String ownerEmail;
    private String ownerPassword;
    private org.springframework.security.core.Authentication ownerAuth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        ownerEmail = "reset.owner." + ownerId + "@hydra.test";
        ownerPassword = "OwnerStrong2026!xyz";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Reset Test Org");
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Gérant Reset', true, true, false)
                """, ownerId, orgId, ownerEmail, encoder.encode(ownerPassword));

        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                ownerId, ownerRoleId, ownerId);

        ownerAuth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                ownerEmail, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_USER_CREATE"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_USER_UPDATE")));
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);
        recordingMail().reset();
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        // Clean up all test data (not @Transactional: the service methods commit)
        // event_log is append-only (trigger blocks DELETE) — rows persist with a random
        // org_id that no other test uses, so they're harmless orphans.
        jdbc.update("delete from password_token where org_id = ?", orgId);
        jdbc.update("delete from user_role where user_id in (select id from app_user where org_id = ?)", orgId);
        jdbc.update("delete from app_user where org_id = ?", orgId);
        jdbc.update("delete from organization where id = ?", orgId);
    }

    private RecordingMailService recordingMail() {
        return (RecordingMailService) mailService;
    }

    // ── forgot-password ────────────────────────────────────────────────

    @Test
    void forgotPassword_knownUnknownInactiveEmails_sameBodyAndStatus() {
        // create an active user
        String activeEmail = "active." + UUID.randomUUID() + "@hydra.test";
        createUserRaw(activeEmail, "Active User", true);

        // create an inactive user
        String inactiveEmail = "inactive." + UUID.randomUUID() + "@hydra.test";
        UUID inactiveId = createUserRaw(inactiveEmail, "Inactive User", true);
        jdbc.update("update app_user set is_active = false where id = ?", inactiveId);

        String unknownEmail = "nobody." + UUID.randomUUID() + "@hydra.test";

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/forgot-password");
        req.setRemoteAddr("1.2.3.4");

        // known active
        var r1 = resetController.forgotPassword(
                new PasswordResetController.ForgotPasswordRequest(activeEmail), req);
        // unknown
        var r2 = resetController.forgotPassword(
                new PasswordResetController.ForgotPasswordRequest(unknownEmail), req);
        // inactive
        var r3 = resetController.forgotPassword(
                new PasswordResetController.ForgotPasswordRequest(inactiveEmail), req);

        // All three return 200 with the same body
        assertThat(r1.getStatusCode().value()).isEqualTo(200);
        assertThat(r2.getStatusCode().value()).isEqualTo(200);
        assertThat(r3.getStatusCode().value()).isEqualTo(200);
        String sameMsg = "Si un compte existe pour cette adresse, un lien vient d'y être envoyé.";
        assertThat(((Map<?,?>) r1.getBody()).get("message")).isEqualTo(sameMsg);
        assertThat(((Map<?,?>) r2.getBody()).get("message")).isEqualTo(sameMsg);
        assertThat(((Map<?,?>) r3.getBody()).get("message")).isEqualTo(sameMsg);

        // Mail only for the known active one
        var sent = recordingMail().sent;
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).to()).isEqualTo(activeEmail);
    }

    @Test
    void fourthRequestInOneHourForSameEmail_same200NoFourthMail() {
        String email = "rate." + UUID.randomUUID() + "@hydra.test";
        createUserRaw(email, "Rate User", true);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/forgot-password");
        req.setRemoteAddr("5.6.7.8");

        // 3 requests should each send a mail
        for (int i = 0; i < 3; i++) {
            recordingMail().reset();
            var r = resetController.forgotPassword(
                    new PasswordResetController.ForgotPasswordRequest(email), req);
            assertThat(r.getStatusCode().value()).isEqualTo(200);
            assertThat(recordingMail().sent).hasSize(1);
        }

        // 4th request: same 200 but no mail
        recordingMail().reset();
        var r4 = resetController.forgotPassword(
                new PasswordResetController.ForgotPasswordRequest(email), req);
        assertThat(r4.getStatusCode().value()).isEqualTo(200);
        assertThat(((Map<?,?>) r4.getBody()).get("message"))
                .isEqualTo("Si un compte existe pour cette adresse, un lien vient d'y être envoyé.");
        assertThat(recordingMail().sent).isEmpty();
    }

    // ── token stored only as hash ──────────────────────────────────────

    @Test
    void tokenStoredOnlyAsHash_rawTokenNeverInDbOrEventLog() {
        String email = "hash." + UUID.randomUUID() + "@hydra.test";
        AppUser u = userRepo.findByEmail(email).orElse(null);
        if (u == null) {
            createUserRaw(email, "Hash User", true);
            u = userRepo.findByEmail(email).orElseThrow();
        }

        String rawToken = tokens.create(u, "RESET", "9.9.9.9");

        // The raw token must NOT appear anywhere in the password_token table
        List<String> allHashes = jdbc.queryForList("select token_hash from password_token where user_id = ?",
                String.class, u.getId());
        assertThat(allHashes).noneMatch(h -> h.equals(rawToken));

        // The raw token must NOT appear in the event_log
        List<String> allDetails = jdbc.queryForList(
                "select coalesce(details::text, '') from event_log", String.class);
        assertThat(allDetails).noneMatch(d -> d.contains(rawToken));
    }

    // ── reset flow ─────────────────────────────────────────────────────

    @Test
    void resetWithToken_success_reuseFails_expiredFails() {
        String email = "reset." + UUID.randomUUID() + "@hydra.test";
        createUserRaw(email, "Reset User", true);
        AppUser u = userRepo.findByEmail(email).orElseThrow();

        // create a reset token
        String rawToken = tokens.create(u, "RESET", "1.1.1.1");
        String strongPw = "correct-cheval-vitres-batterie";

        // consume -> success
        tokens.consume(rawToken, strongPw);
        assertThat(jdbc.queryForObject("select must_change_password from app_user where id = ?",
                Boolean.class, u.getId())).isFalse();

        // reuse same token -> fails
        assertThatThrownBy(() -> tokens.consume(rawToken, "another-strong-passphrase"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("lien n'est plus valide");

        // expired token -> fails
        String rawToken2 = tokens.create(u, "RESET", "2.2.2.2");
        // set expires_at in the past
        jdbc.update("update password_token set expires_at = now() - interval '1 hour' where token_hash = ?",
                sha256Hex(rawToken2));
        assertThatThrownBy(() -> tokens.consume(rawToken2, strongPw))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("lien n'est plus valide");
    }

    @Test
    void afterReset_oldSessionsRevoked_mustChangeFalse_changedMailSent_loginWorks() {
        String email = "after." + UUID.randomUUID() + "@hydra.test";
        createUserRaw(email, "After Reset", true);
        AppUser u = userRepo.findByEmail(email).orElseThrow();

        String rawToken = tokens.create(u, "RESET", "3.3.3.3");
        String strongPw = "correct-cheval-vitres-batterie";

        recordingMail().reset();
        tokens.consume(rawToken, strongPw);

        // must_change false
        assertThat(jdbc.queryForObject("select must_change_password from app_user where id = ?",
                Boolean.class, u.getId())).isFalse();
        // password_changed_at set
        assertThat(jdbc.queryForObject("select password_changed_at from app_user where id = ?",
                Object.class, u.getId())).isNotNull();
        // « changed » mail sent
        assertThat(recordingMail().sent).hasSize(1);
        assertThat(recordingMail().sent.get(0).subject()).contains("modifié");
        // login with the new password works (the hash matches)
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        String hash = jdbc.queryForObject("select password_hash from app_user where id = ?",
                String.class, u.getId());
        assertThat(encoder.matches(strongPw, hash)).isTrue();
    }

    // ── second forgot-password invalidates the first token ─────────────

    @Test
    void secondForgotPasswordInvalidatesFirstToken() {
        String email = "second." + UUID.randomUUID() + "@hydra.test";
        createUserRaw(email, "Second User", true);
        AppUser u = userRepo.findByEmail(email).orElseThrow();

        String rawToken1 = tokens.create(u, "RESET", "4.4.4.4");
        // creating a second token should mark the first as used
        String rawToken2 = tokens.create(u, "RESET", "4.4.4.4");

        // first token is now invalid
        assertThatThrownBy(() -> tokens.consume(rawToken1, "correct-cheval-vitres-batterie"))
                .isInstanceOf(BusinessRuleException.class);
        // second token works
        tokens.consume(rawToken2, "correct-cheval-vitres-batterie");
    }

    // ── invitation flow ────────────────────────────────────────────────

    @Test
    void invitation_createWithSendInvitation_inviteMailWithLink_consumeActivates() {
        String email = "invite." + UUID.randomUUID() + "@hydra.test";
        UserService.UserResponse r = users.createUser(ownerAuth, "Invited User", email,
                null, List.of("BUREAU"), null, true);

        // response has temporaryPassword=null, invitationSent=true
        assertThat(r.temporaryPassword()).isNull();
        assertThat(r.invitationSent()).isTrue();

        // invite mail sent with link starting with the app URL
        assertThat(recordingMail().sent).hasSize(1);
        var msg = recordingMail().sent.get(0);
        assertThat(msg.to()).isEqualTo(email);
        assertThat(msg.html()).contains("http://test.hydra.local/reinitialiser?token=");

        // extract the raw token from the link
        String rawToken = msg.html().substring(msg.html().indexOf("token=") + 6);
        rawToken = rawToken.replaceAll("[\"<].*", "").trim();

        // check -> valid, purpose INVITE
        Map<String, Object> check = tokens.check(rawToken);
        assertThat(check.get("valid")).isEqualTo(true);
        assertThat(check.get("purpose")).isEqualTo("INVITE");

        // consume -> ACCOUNT_ACTIVATED
        String strongPw = "correct-cheval-vitres-batterie";
        tokens.consume(rawToken, strongPw);

        // audit has ACCOUNT_ACTIVATED
        String action = jdbc.queryForObject(
                "select action from event_log where entity_id = ? and action = 'ACCOUNT_ACTIVATED' limit 1",
                String.class, r.id());
        assertThat(action).isEqualTo("ACCOUNT_ACTIVATED");
    }

    // ── check endpoint ─────────────────────────────────────────────────

    @Test
    void check_validToken_returnsFirstName_invalidToken_returnsFalse() {
        String email = "check." + UUID.randomUUID() + "@hydra.test";
        createUserRaw(email, "Check User", true);
        AppUser u = userRepo.findByEmail(email).orElseThrow();

        String rawToken = tokens.create(u, "RESET", "6.6.6.6");
        Map<String, Object> valid = tokens.check(rawToken);
        assertThat(valid.get("valid")).isEqualTo(true);
        assertThat(valid.get("firstName")).isEqualTo("Check");

        Map<String, Object> invalid = tokens.check("not-a-real-token");
        assertThat(invalid.get("valid")).isEqualTo(false);
    }

    // ── Host header never used for the link ────────────────────────────

    @Test
    void resetLinkUsesAppUrlNotHostHeader() {
        String email = "host." + UUID.randomUUID() + "@hydra.test";
        createUserRaw(email, "Host User", true);
        AppUser u = userRepo.findByEmail(email).orElseThrow();

        // create a token with a forged Host in the request
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/forgot-password");
        req.setRemoteAddr("7.7.7.7");
        req.addHeader("Host", "evil.attacker.com");

        recordingMail().reset();
        resetController.forgotPassword(new PasswordResetController.ForgotPasswordRequest(email), req);

        // the link in the email must start with the app URL, NOT the Host header
        assertThat(recordingMail().sent).hasSize(1);
        String html = recordingMail().sent.get(0).html();
        assertThat(html).contains("http://test.hydra.local/reinitialiser?token=");
        assertThat(html).doesNotContain("evil.attacker.com");
    }

    // ── helpers ────────────────────────────────────────────────────────

    private UUID createUserRaw(String email, String fullName, boolean active) {
        UUID id = UUID.randomUUID();
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, ?, false, ?, true)
                """, id, orgId, email, encoder.encode("TempPw12345!xyz"), fullName, active);
        UUID roleId = jdbc.queryForObject("select id from role where code = 'BUREAU'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                id, roleId, ownerId);
        return id;
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
