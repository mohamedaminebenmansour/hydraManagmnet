package tn.hydra.role;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tn.hydra.common.BusinessRuleException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    public record CreateRoleRequest(String label, List<String> permissionCodes) {}
    public record UpdateRoleRequest(String label, List<String> permissionCodes, Boolean confirm) {}
    public record DuplicateRoleRequest(String label) {}

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_ROLE_MANAGE','PERM_ROLE_ASSIGN','PERM_USER_CREATE')")
    public List<RoleService.RoleResponse> list(Authentication auth) {
        return service.listRoles(auth);
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('PERM_ROLE_MANAGE')")
    public List<RoleService.PermissionResponse> permissions() {
        return service.listPermissions();
    }

    @GetMapping("/{id}/users")
    @PreAuthorize("hasAnyAuthority('PERM_ROLE_MANAGE','PERM_ROLE_ASSIGN','PERM_USER_CREATE')")
    public List<RoleService.RoleUserResponse> users(@PathVariable UUID id, Authentication auth) {
        return service.usersOfRole(auth, id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_ROLE_MANAGE')")
    public RoleService.RoleResponse create(@RequestBody CreateRoleRequest body, Authentication auth) {
        return service.createRole(auth, body.label(), body.permissionCodes());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_ROLE_MANAGE')")
    public ResponseEntity<?> update(@PathVariable UUID id, @RequestBody UpdateRoleRequest body,
                                           Authentication auth) {
        try {
            boolean confirm = Boolean.TRUE.equals(body.confirm());
            var result = service.updateRole(auth, id, body.label(), body.permissionCodes(), confirm);
            return ResponseEntity.ok(result);
        } catch (BusinessRuleException e) {
            if ("CONFIRM_IMPACT".equals(e.getCode())) {
                // Return 409 with the code; the frontend shows the confirm dialog.
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("code", "CONFIRM_IMPACT", "message", e.getMessage()));
            }
            throw e;
        }
    }

    @PostMapping("/{id}/duplicate")
    @PreAuthorize("hasAuthority('PERM_ROLE_MANAGE')")
    public RoleService.RoleResponse duplicate(@PathVariable UUID id, @RequestBody DuplicateRoleRequest body,
                                              Authentication auth) {
        return service.duplicateRole(auth, id, body.label());
    }
}
