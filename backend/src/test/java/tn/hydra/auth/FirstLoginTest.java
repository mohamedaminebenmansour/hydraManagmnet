package tn.hydra.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;
import tn.hydra.user.UserService;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * FirstLoginTest — real-database test for the forced password change flow (U1):
 * admin creates a user without password → temporary password returned →
 * that user logs in → must_change_password blocks /api/projects →
 * change-password with a weak one fails → with a good one succeeds →
 /api/projects works → audit has no clear password.
 */
@SpringBootTest
@Transactional
@Rollback
class FirstLoginTest {

    @Autowired private UserService users;
    @Autowired private AppUserRepository userRepo;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AuthController authController;

    private UUID orgId;
    private UUID ownerId;
    private String ownerEmail;
    private String ownerPassword;
    private org.springframework.security.core.Authentication ownerAuth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        ownerEmail = "owner." + ownerId + "@hydra.test";
        ownerPassword = "OwnerStrong2026!xyz";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "First Login Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                values (?, ?, ?, ?, 'Gérant Test', true, true, false)
                """, ownerId, orgId, ownerEmail,
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode(ownerPassword));

        // give the owner a role so he has USER_CREATE
        UUID ownerRoleId = jdbc.queryForObject("select id from role where code = 'OWNER'", UUID.class);
        jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                ownerId, ownerRoleId, ownerId);

        ownerAuth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                ownerEmail, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_USER_CREATE")));
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);
    }

    @AfterEach
    void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void adminCreatesUserWithoutPassword_temporaryPasswordReturnedAndFlagTrue() {
        String email = "new.user." + UUID.randomUUID() + "@hydra.test";
        UserService.UserResponse r = users.createUser(ownerAuth, "New User", email,
                null, // no password -> temporary generated
                List.of("BUREAU"), null);

        assertThat(r.temporaryPassword()).isNotNull();
        assertThat(r.temporaryPassword().length()).isEqualTo(12);
        // no 0 O 1 l I
        String forbidden = "0O1lI";
        for (char c : r.temporaryPassword().toCharArray()) {
            assertThat(forbidden.indexOf(c)).as("char %c not allowed", c).isEqualTo(-1);
        }
        // must_change_password = true
        assertThat(r.mustChangePassword()).isTrue();
        // DB hash matches the temporary password
        AppUser dbUser = userRepo.findByEmail(email).orElseThrow();
        assertThat(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                .matches(r.temporaryPassword(), dbUser.getPasswordHash())).isTrue();
        // flag in DB
        Boolean flag = jdbc.queryForObject("select must_change_password from app_user where id = ?",
                Boolean.class, r.id());
        assertThat(flag).isTrue();
    }

    @Test
    void firstLogin_blocksProjectsEndpoint_allowsMe() {
        // create a user with a known temporary password
        String email = "blocked." + UUID.randomUUID() + "@hydra.test";
        UserService.UserResponse r = users.createUser(ownerAuth, "Blocked User", email,
                null, List.of("BUREAU"), null);

        // simulate a login: set the session flag
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
        req.getSession(true).setAttribute(tn.hydra.security.PasswordChangeRequiredFilter.SESSION_FLAG, true);
        var attrs = new org.springframework.web.context.request.ServletRequestAttributes(req);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(attrs);

        // authenticate as the blocked user
        SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        email, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_TICKET_READ"))));

        // simulate the filter: it checks the session flag and path
        tn.hydra.security.PasswordChangeRequiredFilter filter = new tn.hydra.security.PasswordChangeRequiredFilter();
        MockHttpServletRequest projectsReq = new MockHttpServletRequest("GET", "/api/projects");
        projectsReq.getSession(true).setAttribute(tn.hydra.security.PasswordChangeRequiredFilter.SESSION_FLAG, true);
        MockHttpServletResponse projectsResp = new MockHttpServletResponse();
        try {
            filter.doFilter(projectsReq, projectsResp, (request, response) -> {});
            assertThat(projectsResp.getStatus()).isEqualTo(403);
            assertThat(projectsResp.getContentAsString()).contains("PASSWORD_CHANGE_REQUIRED");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // /api/auth/me is allowed
        MockHttpServletRequest meReq = new MockHttpServletRequest("GET", "/api/auth/me");
        meReq.getSession(true).setAttribute(tn.hydra.security.PasswordChangeRequiredFilter.SESSION_FLAG, true);
        MockHttpServletResponse meResp = new MockHttpServletResponse();
        try {
            filter.doFilter(meReq, meResp, (request, response) -> {});
            assertThat(meResp.getStatus()).isNotEqualTo(403);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void changePassword_weakOneRejected_strongOneAccepted_flagCleared() {
        // create a user with must_change_password=true
        String email = "changer." + UUID.randomUUID() + "@hydra.test";
        UserService.UserResponse r = users.createUser(ownerAuth, "Changer", email,
                null, List.of("BUREAU"), null);

        // authenticate as the new user
        org.springframework.security.core.Authentication userAuth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        email, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_TICKET_READ")));
        SecurityContextHolder.getContext().setAuthentication(userAuth);

        // set up a session with the flag
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession(true).setAttribute(tn.hydra.security.PasswordChangeRequiredFilter.SESSION_FLAG, true);
        var attrs = new org.springframework.web.context.request.ServletRequestAttributes(req);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(attrs);

        // change-password with a weak one (too short) -> 400
        assertThatThrownBy(() -> users.changeMyPassword(userAuth, r.temporaryPassword(), "short"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("caractères");

        // change-password with a strong one -> success
        String strongPw = "correct-cheval-vitres-batterie";
        users.changeMyPassword(userAuth, r.temporaryPassword(), strongPw);

        // flag cleared in DB
        Boolean flag = jdbc.queryForObject("select must_change_password from app_user where id = ?",
                Boolean.class, r.id());
        assertThat(flag).isFalse();

        // password_changed_at set
        Object changed = jdbc.queryForObject("select password_changed_at from app_user where id = ?",
                Object.class, r.id());
        assertThat(changed).isNotNull();
    }

    @Test
    void auditHasNoClearPassword() {
        String email = "audit." + UUID.randomUUID() + "@hydra.test";
        UserService.UserResponse r = users.createUser(ownerAuth, "Audit User", email,
                null, List.of("BUREAU"), null);

        // authenticate as the new user and change password
        org.springframework.security.core.Authentication userAuth =
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        email, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("PERM_TICKET_READ")));
        SecurityContextHolder.getContext().setAuthentication(userAuth);
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.getSession(true).setAttribute(tn.hydra.security.PasswordChangeRequiredFilter.SESSION_FLAG, true);
        var attrs = new org.springframework.web.context.request.ServletRequestAttributes(req);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(attrs);

        users.changeMyPassword(userAuth, r.temporaryPassword(), "correct-cheval-vitres-batterie");

        // search the event table for any row that contains the temporary or the new password in clear
        List<String> details = jdbc.queryForList(
                "select coalesce(details::text, '') from event_log where entity_id = ?", String.class, r.id());
        assertThat(details).isNotEmpty();
        for (String d : details) {
            assertThat(d).doesNotContain(r.temporaryPassword());
            assertThat(d).doesNotContain("correct-cheval-vitres-batterie");
        }
    }
}
