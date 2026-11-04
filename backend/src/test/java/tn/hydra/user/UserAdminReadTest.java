package tn.hydra.user;

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
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserAdminReadTest — tests for GET /api/users/{id}/activity and GET /api/users/summary (U4).
 */
@SpringBootTest
@Transactional
@Rollback
class UserAdminReadTest {

    @Autowired private UserService service;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID ownerId;
    private UUID targetId;
    private String ownerEmail;
    private Authentication ownerAuth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        targetId = UUID.randomUUID();
        ownerEmail = "admin.read." + ownerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Admin Read Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, 'hash', 'Owner Read', true, true, false)
                """, ownerId, orgId, ownerEmail);
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                ownerId, ownerRoleId, ownerId);

        // target user: active, no password change, logged in recently
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password, last_login_at)
                values (?, ?, ?, 'hash', 'Target User', false, true, false, now())
                """, targetId, orgId, "target." + targetId + "@hydra.test");
        UUID bureauRoleId = jdbc.queryForObject("select id from role where code = 'BUREAU'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                targetId, bureauRoleId, ownerId);

        // an audit event for the target user (actor = target)
        jdbc.update("""
                insert into event_log (org_id, user_id, action, entity_type, entity_id, details)
                values (?, ?, 'LOGIN_SUCCESS', null, null, null)
                """, orgId, targetId);
        jdbc.update("""
                insert into event_log (org_id, user_id, action, entity_type, entity_id, details)
                values (?, ?, 'PROFILE_UPDATED', 'app_user', ?, null)
                """, orgId, targetId, targetId);

        ownerAuth = new UsernamePasswordAuthenticationToken(ownerEmail, null,
                List.of(new SimpleGrantedAuthority("PERM_USER_CREATE")));
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void activity_returnsLastEventsNewestFirst_withFrenchLabels() {
        List<UserService.ActivityEntry> entries = service.activity(ownerAuth, targetId, 20);
        assertThat(entries).isNotEmpty();
        // newest first
        assertThat(entries.get(0).at()).isAfterOrEqualTo(entries.get(entries.size() - 1).at());
        // French labels
        assertThat(entries).anyMatch(e -> e.action().contains("Connexion") || e.action().contains("Mise à jour"));
        // subjectLabel for the PROFILE_UPDATED event should be the user's full name
        assertThat(entries).anyMatch(e -> "Target User".equals(e.subjectLabel()));
    }

    @Test
    void activity_respectsLimit() {
        // insert more events
        for (int i = 0; i < 5; i++) {
            jdbc.update("insert into event_log (org_id, user_id, action) values (?, ?, 'LOGOUT')",
                    orgId, targetId);
        }
        List<UserService.ActivityEntry> entries = service.activity(ownerAuth, targetId, 3);
        assertThat(entries).hasSize(3);
    }

    @Test
    void summary_countsMatch() {
        // add a pending user (must_change_password=true)
        UUID pendingId = UUID.randomUUID();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, 'hash', 'Pending User', false, true, true)
                """, pendingId, orgId, "pending." + pendingId + "@hydra.test");

        // add a dormant user (last login > 30 days ago)
        UUID dormantId = UUID.randomUUID();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password, last_login_at)
                values (?, ?, ?, 'hash', 'Dormant User', false, true, false, now() - interval '40 days')
                """, dormantId, orgId, "dormant." + dormantId + "@hydra.test");

        // add an inactive user
        UUID inactiveId = UUID.randomUUID();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, 'hash', 'Inactive User', false, false, false)
                """, inactiveId, orgId, "inactive." + inactiveId + "@hydra.test");

        UserService.UserSummary s = service.summary(ownerAuth);
        // owner (active) + target (active) = 2 active
        assertThat(s.active()).isEqualTo(2);
        assertThat(s.pendingFirstLogin()).isEqualTo(1);
        assertThat(s.dormant30()).isEqualTo(1);
        assertThat(s.inactive()).isEqualTo(1);
        assertThat(s.signedExceptions()).isZero();
    }
}
