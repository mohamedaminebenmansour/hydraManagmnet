package tn.hydra.user;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tn.hydra.common.BusinessRuleException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService service;

    public UserController(UserService service) {
        this.service = service;
    }

    public record CreateUserRequest(String fullName, String email, String password,
                                    List<String> roleCodes, String overrideReason,
                                    Boolean sendInvitation) {}
    public record ChangePasswordRequest(String oldPassword, String newPassword) {}
    public record ResetPasswordRequest(String newPassword) {}
    public record UpdateUserRequest(String fullName, String password) {}
    public record UpdateRolesRequest(List<String> roleCodes, String overrideReason) {}
    public record AbsenceRequest(LocalDate absentUntil) {}

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_USER_CREATE','PERM_USER_UPDATE','PERM_ROLE_ASSIGN')")
    public List<UserService.UserResponse> list(Authentication auth) {
        return service.listUsers(auth);
    }

    // Lightweight active-user picker so field users holding TICKET_WRITE can
    // assign tickets without needing USER_CREATE (GET /api/users stays restricted).
    @GetMapping("/options")
    @PreAuthorize("hasAnyAuthority('PERM_TICKET_WRITE','PERM_TICKET_ASSIGN','PERM_USER_CREATE','PERM_PROJECT_ASSIGN','PERM_FOLDER_MANAGE','PERM_PROJECT_WRITE')")
    public List<UserService.UserOption> options(Authentication auth) {
        return service.userOptions(auth);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_USER_UPDATE')")
    public UserService.UserResponse update(@PathVariable UUID id, @RequestBody UpdateUserRequest body,
                                           Authentication auth) {
        return service.updateUser(auth, id, body.fullName(), body.password());
    }

    @PutMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('PERM_ROLE_ASSIGN')")
    public UserService.UserResponse updateRoles(@PathVariable UUID id, @RequestBody UpdateRolesRequest body,
                                                Authentication auth) {
        return service.updateUserRoles(auth, id, body.roleCodes(), body.overrideReason());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_USER_CREATE')")
    public UserService.UserResponse create(@RequestBody CreateUserRequest body, Authentication auth) {
        return service.createUser(auth, body.fullName(), body.email(),
                body.password(), body.roleCodes(), body.overrideReason(),
                Boolean.TRUE.equals(body.sendInvitation()));
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('PERM_USER_DEACTIVATE')")
    public ResponseEntity<?> deactivate(@PathVariable UUID id, Authentication auth) {
        service.deactivateUser(auth, id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('PERM_USER_DEACTIVATE')")
    public ResponseEntity<?> reactivate(@PathVariable UUID id, Authentication auth) {
        service.reactivateUser(auth, id);
        return ResponseEntity.ok().build();
    }

    // Alias: the Angular front calls /activate, the service already knows /reactivate.
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('PERM_USER_DEACTIVATE')")
    public ResponseEntity<?> activate(@PathVariable UUID id, Authentication auth) {
        service.reactivateUser(auth, id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/reset-password")
    @PreAuthorize("hasAuthority('PERM_USER_UPDATE')")
    public ResponseEntity<?> resetPassword(@PathVariable UUID id,
                                           @RequestBody ResetPasswordRequest body,
                                           Authentication auth) {
        String tempPw = service.resetPassword(auth, id, body.newPassword());
        if (tempPw != null) {
            return ResponseEntity.ok(java.util.Map.of("temporaryPassword", tempPw));
        }
        return ResponseEntity.ok().build();
    }

    /** POST /api/users/{id}/send-reset-link — admin-triggered RESET email (U2). */
    @PostMapping("/{id}/send-reset-link")
    @PreAuthorize("hasAuthority('PERM_USER_UPDATE')")
    public ResponseEntity<?> sendResetLink(@PathVariable UUID id, Authentication auth) {
        service.sendResetLink(auth, id);
        return ResponseEntity.ok().build();
    }

    /** POST /api/users/{id}/resend-invitation — resend the INVITE email for a PENDING user (U2). */
    @PostMapping("/{id}/resend-invitation")
    @PreAuthorize("hasAuthority('PERM_USER_UPDATE')")
    public ResponseEntity<?> resendInvitation(@PathVariable UUID id, Authentication auth) {
        service.resendInvitation(auth, id);
        return ResponseEntity.ok().build();
    }

    /** PUT /api/users/{id}/absence — set or clear the absence (absentUntil = null
     * ends the absence). Allowed for the org owner, or the user for himself.
     */
    @PutMapping("/{id}/absence")
    public ResponseEntity<?> setAbsence(@PathVariable UUID id, @RequestBody AbsenceRequest body,
                                          Authentication auth) {
        service.setAbsence(auth, id, body.absentUntil());
        return ResponseEntity.ok().build();
    }

    // ── U4: admin reads ────────────────────────────────────────────────

    /** GET /api/users/{id}/activity?limit=20 — last audit events of that user. */
    @GetMapping("/{id}/activity")
    @PreAuthorize("hasAnyAuthority('PERM_USER_CREATE','PERM_USER_UPDATE')")
    public List<UserService.ActivityEntry> activity(@PathVariable UUID id,
                                                     @RequestParam(value = "limit", required = false, defaultValue = "20") int limit,
                                                     Authentication auth) {
        return service.activity(auth, id, limit);
    }

    /** GET /api/users/summary — counters for the header. */
    @GetMapping("/summary")
    @PreAuthorize("hasAnyAuthority('PERM_USER_CREATE','PERM_USER_UPDATE','PERM_ROLE_ASSIGN')")
    public UserService.UserSummary summary(Authentication auth) {
        return service.summary(auth);
    }
}
