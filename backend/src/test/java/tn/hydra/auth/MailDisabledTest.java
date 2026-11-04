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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.mail.MailService;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;
import tn.hydra.user.UserService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for the mail-disabled scenario (U2):
 * - /mail-status returns enabled=false
 * - create user returns a temporary password (no invitation)
 * - send-reset-link → 400 MAIL_DISABLED
 */
@SpringBootTest(properties = {
        "DB_URL=jdbc:postgresql://127.0.0.1:5432/hydra_test",
        "DB_USER=postgres",
        "DB_PASSWORD=hydra",
        "HYDRA_APP_URL=http://test.hydra.local",
        // mail NOT configured -> MailServiceDisabled wins
        "spring.mail.host=",
        "hydra.mail.from="
})
@Transactional
@Rollback
class MailDisabledTest {

    @TestConfiguration
    static class ForceDisabledMail {
        @Bean @Primary
        MailService mailService() { return new tn.hydra.mail.MailServiceDisabled(); }
    }

    @Autowired private UserService users;
    @Autowired private AppUserRepository userRepo;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MailService mailService;

    private UUID orgId;
    private UUID ownerId;
    private String ownerEmail;
    private org.springframework.security.core.Authentication ownerAuth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        ownerEmail = "disabled.owner." + ownerId + "@hydra.test";

        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Disabled Mail Org");
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Owner', true, true, false)
                """, ownerId, orgId, ownerEmail, encoder.encode("OwnerStrong2026!xyz"));
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                ownerId, ownerRoleId, ownerId);

        ownerAuth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                ownerEmail, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_USER_CREATE"),
                        new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_USER_UPDATE")));
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void mailStatus_enabledFalse() {
        assertThat(mailService.isEnabled()).isFalse();
    }

    @Test
    void createUser_sendInvitationIgnored_returnsTempPassword() {
        String email = "disabled.user." + UUID.randomUUID() + "@hydra.test";
        // sendInvitation=true but mail is disabled → should return a temp password
        UserService.UserResponse r = users.createUser(ownerAuth, "Disabled User", email,
                null, List.of("BUREAU"), null, true);

        assertThat(r.temporaryPassword()).isNotNull();
        assertThat(r.temporaryPassword().length()).isEqualTo(12);
        // invitationSent is null (not true) because mail is disabled
        assertThat(r.invitationSent()).isNotEqualTo(true);
    }

    @Test
    void sendResetLink_throwsMailDisabled() {
        // create a target user
        String email = "target." + UUID.randomUUID() + "@hydra.test";
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        UUID targetId = UUID.randomUUID();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Target', false, true, true)
                """, targetId, orgId, email, encoder.encode("temp12345!xyz"));
        UUID roleId = jdbc.queryForObject("select id from role where code = 'BUREAU'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                targetId, roleId, ownerId);

        assertThatThrownBy(() -> users.sendResetLink(ownerAuth, targetId))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("emails n'est pas configuré");
    }
}
