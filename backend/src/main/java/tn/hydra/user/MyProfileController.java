package tn.hydra.user;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tn.hydra.security.SessionRevoker;

import java.time.LocalDate;
import java.util.Map;

/**
 * My profile endpoints (U1): any authenticated user can read/update his own
 * profile, set his absence, and revoke his other sessions.
 */
@RestController
@RequestMapping("/api/me")
public class MyProfileController {

    private final UserService users;
    private final SessionRevoker sessions;

    public MyProfileController(UserService users, SessionRevoker sessions) {
        this.users = users;
        this.sessions = sessions;
    }

    /** GET /api/me/profile — full profile (name, email, phone, jobTitle, roles, projects, absence). */
    @GetMapping("/profile")
    public ResponseEntity<Map<String, Object>> profile(Authentication auth) {
        return ResponseEntity.ok(users.myProfile(auth));
    }

    public record UpdateProfileRequest(String fullName, String phone, String jobTitle) {}

    /** PUT /api/me/profile — update fullName/phone/jobTitle. Email cannot be changed. */
    @PutMapping("/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(@RequestBody UpdateProfileRequest body,
                                                              Authentication auth) {
        return ResponseEntity.ok(users.updateMyProfile(auth, body.fullName(), body.phone(), body.jobTitle()));
    }

    public record AbsenceRequest(LocalDate absentUntil) {}

    /** PUT /api/me/absence — set or clear my own absence (reuses the existing service rules). */
    @PutMapping("/absence")
    public ResponseEntity<?> absence(@RequestBody AbsenceRequest body, Authentication auth) {
        // self-absence: the service allows the user to set his own absence
        users.setAbsence(auth, users.findMyId(auth), body.absentUntil());
        return ResponseEntity.ok().build();
    }

    /** POST /api/me/sessions/revoke-others — revoke all my sessions except the current one. */
    @PostMapping("/sessions/revoke-others")
    public ResponseEntity<?> revokeOthers(Authentication auth,
                                           org.springframework.web.context.request.ServletRequestAttributes attrs) {
        String email = users.findMyEmail(auth);
        String currentSessionId = currentSessionId(attrs);
        if (currentSessionId != null) sessions.revokeAllExcept(email, currentSessionId);
        else sessions.revokeAllFor(email);
        return ResponseEntity.ok().build();
    }

    private String currentSessionId(org.springframework.web.context.request.ServletRequestAttributes attrs) {
        if (attrs == null) return null;
        var session = attrs.getRequest().getSession(false);
        return session == null ? null : session.getId();
    }
}
