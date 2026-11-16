package tn.hydra.tenant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import tn.hydra.auth.AuthController;
import tn.hydra.security.HydraUserDetailsService;
import tn.hydra.security.PermissionService;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Core 1a: login goes through hydra_login_lookup and behaves as before. */
@SpringBootTest
class LoginLookupTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private PasswordEncoder encoder;
    @Autowired private HydraUserDetailsService userDetails;
    @Autowired private PermissionService permissions;
    @Autowired private AuthController auth;

    private record Created(UUID org, UUID user, String email, String password) {}

    private Created newUser(boolean owner, boolean active) {
        UUID org = jdbc.queryForObject("insert into organization (name) values ('Login test') returning id", UUID.class);
        String email = "login." + UUID.randomUUID() + "@hydra.test";
        String password = "Un-mot-de-passe-long-" + UUID.randomUUID();
        UUID user = jdbc.queryForObject("""
                insert into app_user (org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, 'Test login', ?, ?) returning id
                """, UUID.class, org, email, encoder.encode(password), owner, active);
        return new Created(org, user, email, password);
    }

    @Test
    void lookupFunction_returnsTheUserRow() {
        Created c = newUser(false, true);
        Map<String, Object> row = jdbc.queryForMap("select * from hydra_login_lookup(?)", c.email());
        assertThat(row.get("id")).isEqualTo(c.user());
        assertThat(row.get("org_id")).isEqualTo(c.org());
        assertThat(row.get("email")).isEqualTo(c.email());
        assertThat(encoder.matches(c.password(), (String) row.get("password_hash"))).isTrue();
        assertThat(row.get("is_active")).isEqualTo(true);
        assertThat(row.get("must_change_password")).isEqualTo(false);
        assertThat(jdbc.queryForList("select * from hydra_login_lookup(?)", "absent@hydra.test")).isEmpty();
    }

    @Test
    void owner_getsAllPermissionsAndRoleOwner() {
        Created c = newUser(true, true);
        UserDetails d = userDetails.loadUserByUsername(c.email());
        assertThat(d.getUsername()).isEqualTo(c.email());
        assertThat(d.isEnabled()).isTrue();
        assertThat(d.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .contains("ROLE_OWNER")
                .containsAll(permissions.allPermissions());
        assertThat(TenantContext.current()).isNull();
    }

    @Test
    void inactiveNonOwner_isDisabled_withoutRoleOwner() {
        Created c = newUser(false, false);
        UserDetails d = userDetails.loadUserByUsername(c.email());
        assertThat(d.isEnabled()).isFalse();
        assertThat(d.getAuthorities()).extracting(GrantedAuthority::getAuthority).doesNotContain("ROLE_OWNER");
    }

    @Test
    void unknownEmail_throwsUsernameNotFound() {
        assertThatThrownBy(() -> userDetails.loadUserByUsername("inconnu." + UUID.randomUUID() + "@hydra.test"))
                .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void loginEndpoint_stillWorks() {
        Created c = newUser(true, true);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
        req.setRemoteAddr("198.51.100." + (int) (Math.random() * 200));
        try {
            ResponseEntity<?> ok = auth.login(new AuthController.LoginRequest(c.email(), c.password()),
                    req, new MockHttpServletResponse());
            assertThat(ok.getStatusCode()).isEqualTo(HttpStatus.OK);

            ResponseEntity<?> ko = auth.login(new AuthController.LoginRequest(c.email(), "mauvais"),
                    req, new MockHttpServletResponse());
            assertThat(ko.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
