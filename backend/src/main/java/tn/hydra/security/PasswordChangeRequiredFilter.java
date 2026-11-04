package tn.hydra.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Forces a password change at first login (U1):
 * if the user is authenticated and {@code must_change_password = true} and the
 * path is NOT one of the allowed paths, returns 403 JSON
 * {@code {"code":"PASSWORD_CHANGE_REQUIRED","message":"Vous devez changer votre mot de passe."}}.
 *
 * <p>The flag is stored in the HTTP session at login (key {@link #SESSION_FLAG})
 * so we do not hit the database on every request. It is updated at change-password
 * and at admin reset (a reset revokes sessions anyway).
 */
@Component
public class PasswordChangeRequiredFilter extends OncePerRequestFilter {

    /** Session attribute: Boolean true = the user must change his password. */
    public static final String SESSION_FLAG = "HYDRA_MUST_CHANGE_PASSWORD";

    /** Paths a user with must_change_password=true may still hit. */
    private static final Set<String> ALLOWED_PATHS = Set.of(
            "/api/auth/me", "/api/auth/change-password", "/api/auth/logout", "/api/auth/password-policy");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
            HttpSession session = request.getSession(false);
            Boolean mustChange = session == null ? null : (Boolean) session.getAttribute(SESSION_FLAG);
            if (Boolean.TRUE.equals(mustChange) && !isAllowed(request.getRequestURI())) {
                write403(response);
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private boolean isAllowed(String uri) {
        if (uri == null) return false;
        return ALLOWED_PATHS.stream().anyMatch(uri::equals);
    }

    private void write403(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(
                "{\"code\":\"PASSWORD_CHANGE_REQUIRED\",\"message\":\"Vous devez changer votre mot de passe.\"}");
    }
}
