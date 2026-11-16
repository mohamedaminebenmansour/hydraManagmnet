package tn.hydra.tenant;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import java.util.UUID;

/**
 * Puts the authenticated user's org and id into {@link TenantContext} for the rest of the request,
 * and clears it afterwards. Registered in the security chain after authentication (SecurityConfig).
 *
 * <p>The principal only carries the email, so the ids are looked up once through
 * {@code hydra_login_lookup} (SECURITY DEFINER: still works once RLS is on) and cached in the
 * HTTP session under {@link #SESSION_KEY}.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String SESSION_KEY = "HYDRA_TENANT";

    /** Session value (Spring Session JDBC serialises attributes). */
    public record SessionTenant(UUID orgId, UUID userId, String email) implements Serializable {}

    private final JdbcTemplate jdbc;

    public TenantContextFilter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            SessionTenant t = resolve(request);
            if (t != null) TenantContext.set(t.orgId(), t.userId());
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private SessionTenant resolve(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return null;
        }
        String email = auth.getName();
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute(SESSION_KEY) instanceof SessionTenant cached
                && email.equals(cached.email())) {
            return cached;
        }
        List<SessionTenant> found = jdbc.query(
                "select id, org_id from hydra_login_lookup(?)",
                (rs, i) -> new SessionTenant(rs.getObject("org_id", UUID.class),
                        rs.getObject("id", UUID.class), email),
                email);
        if (found.isEmpty()) return null;
        SessionTenant t = found.get(0);
        if (session != null) session.setAttribute(SESSION_KEY, t);
        return t;
    }
}
