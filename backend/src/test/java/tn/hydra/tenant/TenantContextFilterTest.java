package tn.hydra.tenant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Core 1a: an authenticated request runs with its user's org/user in TenantContext; cleared afterwards. */
@SpringBootTest
class TenantContextFilterTest {

    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void authenticatedRequest_setsOrgAndUser_thenClears() throws Exception {
        UUID org = jdbc.queryForObject("insert into organization (name) values ('Tenant test') returning id", UUID.class);
        String email = "tenant." + UUID.randomUUID() + "@hydra.test";
        UUID userId = jdbc.queryForObject("""
                insert into app_user (org_id, email, password_hash, full_name)
                values (?, ?, 'x', 'Test tenant') returning id
                """, UUID.class, org, email);
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(email, null, List.of()));

        TenantContextFilter filter = new TenantContextFilter(jdbc);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.getSession(true);
        AtomicReference<TenantContext.Tenant> seen = new AtomicReference<>();
        AtomicReference<String> dbOrg = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            seen.set(TenantContext.current());
            dbOrg.set(jdbc.queryForObject("select current_setting('app.org_id', true)", String.class));
        });

        assertThat(seen.get()).isEqualTo(new TenantContext.Tenant(org, userId));
        assertThat(dbOrg.get()).isEqualTo(org.toString());
        assertThat(TenantContext.current()).isNull();
        // cached in the session for the next requests
        assertThat(request.getSession().getAttribute(TenantContextFilter.SESSION_KEY))
                .isEqualTo(new TenantContextFilter.SessionTenant(org, userId, email));
    }

    @Test
    void anonymousRequest_hasNoTenant() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        AtomicReference<TenantContext.Tenant> seen = new AtomicReference<>(new TenantContext.Tenant(null, null));

        new TenantContextFilter(jdbc).doFilter(new MockHttpServletRequest("GET", "/api/auth/login"),
                new MockHttpServletResponse(), (req, res) -> seen.set(TenantContext.current()));

        assertThat(seen.get()).isNull();
        assertThat(TenantContext.current()).isNull();
    }
}
