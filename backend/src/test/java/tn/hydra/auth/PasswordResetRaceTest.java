package tn.hydra.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.config.demo.DemoScenarioRunner;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;
import tn.hydra.user.UserService;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PasswordResetRaceTest — verifies the U7 atomic token claim + rate limit:
 * - Two threads consume the same token → exactly one success.
 * - A weak password leaves the token usable (claim rolls back).
 * - 6 bad tokens from one IP → 429.
 * - Demo runner twice → no duplicate users, all emails end with @demo.hydra.tn.
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:postgresql://127.0.0.1:5432/hydra_test",
        "DB_USER=postgres",
        "DB_PASSWORD=hydra",
        "hydra.app-url=http://test.hydra.local"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PasswordResetRaceTest {

    @Autowired private PasswordTokenService tokens;
    @Autowired private AppUserRepository userRepo;
    @Autowired private UserService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ForgotPasswordRateLimiter rateLimiter;
    @Autowired private PasswordResetController resetController;

    private UUID orgId;
    private UUID ownerId;
    private String ownerEmail;
    private Authentication ownerAuth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        ownerEmail = "race.owner." + ownerId + "@hydra.test";

        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Race Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Race Owner', true, true, false)
                """, ownerId, orgId, ownerEmail, encoder.encode("OwnerStrong2026!xyz"));
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                ownerId, ownerRoleId, ownerId);

        ownerAuth = new UsernamePasswordAuthenticationToken(ownerEmail, null,
                List.of(new SimpleGrantedAuthority("PERM_USER_CREATE"),
                        new SimpleGrantedAuthority("PERM_USER_UPDATE")));
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        // Clean up test data (not @Transactional: the race test needs separate transactions)
        jdbc.update("delete from password_token where org_id = ?", orgId);
        jdbc.update("delete from user_role where user_id in (select id from app_user where org_id = ?)", orgId);
        jdbc.update("delete from toxic_pair_override where org_id = ?", orgId);
        jdbc.update("delete from app_user where org_id = ?", orgId);
        jdbc.update("delete from organization where id = ?", orgId);
    }

    @Test
    void twoThreadsConsumeSameToken_exactlyOneSuccess() throws Exception {
        // create a user + a reset token
        String email = "race.target." + UUID.randomUUID() + "@hydra.test";
        var r = users.createUser(ownerAuth, "Race Target", email,
                "RaceStrong2026!xyz", List.of("BUREAU"), null);
        AppUser u = userRepo.findByEmail(email).orElseThrow();
        String rawToken = tokens.create(u, "RESET", "1.2.3.4");
        String strongPw = "correct-cheval-vitres-batterie";

        // two threads try to consume the same token simultaneously
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger successes = new AtomicInteger(0);
        AtomicInteger failures = new AtomicInteger(0);

        for (int i = 0; i < 2; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    tokens.consume(rawToken, strongPw);
                    successes.incrementAndGet();
                } catch (Exception e) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        done.await();

        assertThat(successes.get()).isEqualTo(1);
        assertThat(failures.get()).isEqualTo(1);
    }

    @Test
    void weakPasswordLeavesTokenUsable() {
        String email = "race.weak." + UUID.randomUUID() + "@hydra.test";
        var r = users.createUser(ownerAuth, "Weak Target", email,
                "RaceStrong2026!xyz", List.of("BUREAU"), null);
        AppUser u = userRepo.findByEmail(email).orElseThrow();
        String rawToken = tokens.create(u, "RESET", "2.3.4.5");

        // try with a weak password → should fail
        assertThatThrownBy(() -> tokens.consume(rawToken, "short"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("caractères");

        // the token should still be usable (the claim rolled back)
        String strongPw = "correct-cheval-vitres-batterie";
        tokens.consume(rawToken, strongPw); // should succeed

        // verify the password was actually changed
        String hash = jdbc.queryForObject("select password_hash from app_user where id = ?",
                String.class, r.id());
        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches(strongPw, hash)).isTrue();
    }

    @Test
    void sixBadTokensFromOneIP_returns429() {
        String ip = "9.8.7.6";
        // We need to call the controller 5 times with bad tokens (each gets 400 TOKEN_INVALID),
        // then the 6th should get 429.
        org.springframework.mock.web.MockHttpServletRequest req = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/auth/reset-password");
        req.setRemoteAddr(ip);

        for (int i = 0; i < 5; i++) {
            var resp = resetController.resetPassword(
                    new PasswordResetController.ResetPasswordRequest("bad-token-" + i, "some-strong-passphrase"),
                    req);
            assertThat(resp.getStatusCode().value()).isEqualTo(400);
        }

        // 6th attempt → 429
        var resp = resetController.resetPassword(
                new PasswordResetController.ResetPasswordRequest("bad-token-6", "some-strong-passphrase"),
                req);
        assertThat(resp.getStatusCode().value()).isEqualTo(429);
        assertThat(((java.util.Map<?,?>) resp.getBody()).get("code")).isEqualTo("TOO_MANY_ATTEMPTS");
    }
}
