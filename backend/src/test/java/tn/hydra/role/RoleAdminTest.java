package tn.hydra.role;

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
import tn.hydra.common.BusinessRuleException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * RoleAdminTest — tests for the U5 role admin endpoints:
 * userCount in listRoles, usersOfRole, confirm-impact 409 then confirm,
 * duplicate, system role refused.
 */
@SpringBootTest
@Transactional
@Rollback
class RoleAdminTest {

    @Autowired private RoleService service;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID ownerId;
    private String ownerEmail;
    private Authentication ownerAuth;
    private UUID customRoleId;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        ownerEmail = "role.admin." + ownerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Role Admin Org " + orgId);
        var encoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Role Admin', true, true, false)
                """, ownerId, orgId, ownerEmail, encoder.encode("OwnerStrong2026!xyz"));
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                ownerId, ownerRoleId, ownerId);

        // create a custom role with 2 active users
        customRoleId = service.createRole(
                new UsernamePasswordAuthenticationToken(ownerEmail, null,
                        List.of(new SimpleGrantedAuthority("PERM_ROLE_MANAGE"))),
                "Superviseur Test", List.of("TICKET_READ", "TICKET_WRITE", "DOCUMENT_READ")).id();

        // assign 2 users to the custom role
        for (int i = 0; i < 2; i++) {
            UUID uid = UUID.randomUUID();
            String email = "role.user" + i + "." + uid + "@hydra.test";
            jdbc.update("""
                    insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                    values (?, ?, ?, 'hash', ?, false, true, false)
                    """, uid, orgId, email, "User " + i);
            jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                    uid, customRoleId, ownerId);
        }

        ownerAuth = new UsernamePasswordAuthenticationToken(ownerEmail, null,
                List.of(new SimpleGrantedAuthority("PERM_ROLE_MANAGE")));
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void listRoles_includesUserCountAndModuleKeys() {
        List<RoleService.RoleResponse> roles = service.listRoles(ownerAuth);
        var custom = roles.stream().filter(r -> r.id().equals(customRoleId)).findFirst().orElseThrow();
        assertThat(custom.userCount()).isEqualTo(2);
        assertThat(custom.moduleKeys()).contains("tickets", "documents");
    }

    @Test
    void usersOfRole_returnsActiveUsersWithStatus() {
        List<RoleService.RoleUserResponse> users = service.usersOfRole(ownerAuth, customRoleId);
        assertThat(users).hasSize(2);
        assertThat(users).allMatch(u -> "ACTIVE".equals(u.status()) || "PENDING".equals(u.status()));
        assertThat(users).extracting(RoleService.RoleUserResponse::fullName)
                .containsExactlyInAnyOrder("User 0", "User 1");
    }

    @Test
    void updateRole_withoutConfirm_returns409() {
        assertThatThrownBy(() -> service.updateRole(ownerAuth, customRoleId, "Superviseur Renamed",
                List.of("TICKET_READ"), false))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "CONFIRM_IMPACT");
    }

    @Test
    void updateRole_withConfirm_savesAndAuditsBeforeAfter() {
        var result = service.updateRole(ownerAuth, customRoleId, "Superviseur Confirmed",
                List.of("TICKET_READ"), true);
        assertThat(result.label()).isEqualTo("Superviseur Confirmed");
        assertThat(result.permissions()).containsExactly("TICKET_READ");
        assertThat(result.userCount()).isEqualTo(2);

        // audit has before -> after
        String details = jdbc.queryForObject(
                "select details::text from event_log where entity_id = ? and action = 'ROLE_UPDATED' order by id desc limit 1",
                String.class, customRoleId);
        assertThat(details).contains("before");
        assertThat(details).contains("after");
    }

    @Test
    void updateRole_systemRole_refused() {
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        assertThatThrownBy(() -> service.updateRole(ownerAuth, ownerRoleId, "Owner",
                List.of("USER_CREATE"), true))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("système");
    }

    @Test
    void duplicateRole_createsCopyWithSamePermissions() {
        var dup = service.duplicateRole(ownerAuth, customRoleId, "Superviseur Copie");
        assertThat(dup.id()).isNotEqualTo(customRoleId);
        assertThat(dup.label()).isEqualTo("Superviseur Copie");
        assertThat(dup.isSystem()).isFalse();
        assertThat(dup.permissions()).containsExactlyInAnyOrder("TICKET_READ", "TICKET_WRITE", "DOCUMENT_READ");
        assertThat(dup.moduleKeys()).contains("tickets", "documents");
    }
}
