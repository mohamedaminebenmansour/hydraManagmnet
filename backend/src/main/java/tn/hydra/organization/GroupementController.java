package tn.hydra.organization;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tn.hydra.common.BusinessRuleException;

import java.util.List;
import java.util.Map;

/**
 * Groupement controller (S1-5 step 4).
 */
@RestController
@RequestMapping("/api/company/groupements")
public class GroupementController {

    private final GroupementService service;

    public GroupementController(GroupementService service) {
        this.service = service;
    }

    @GetMapping
    public List<GroupementService.GroupementResponse> list(
            @RequestParam(value = "archives", required = false, defaultValue = "false") boolean archives,
            Authentication auth) {
        return service.list(auth, archives);
    }

    @GetMapping("/{id}")
    public GroupementService.GroupementResponse getOne(@PathVariable java.util.UUID id, Authentication auth) {
        return service.getOne(auth, id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_ORG_SETTINGS')")
    public ResponseEntity<?> create(@RequestBody GroupementService.GroupementInput body,
                                    Authentication auth) {
        try {
            return ResponseEntity.ok(service.create(auth, body));
        } catch (CompanyProfileService.ValidationException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "VALIDATION", "fields", e.getFields()));
        }
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_ORG_SETTINGS')")
    public ResponseEntity<?> update(@PathVariable java.util.UUID id,
                                    @RequestBody GroupementService.GroupementInput body,
                                    Authentication auth) {
        try {
            return ResponseEntity.ok(service.update(auth, id, body));
        } catch (CompanyProfileService.ValidationException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "VALIDATION", "fields", e.getFields()));
        } catch (BusinessRuleException e) {
            if ("STALE_GROUPEMENT".equals(e.getCode())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("code", "STALE_GROUPEMENT", "message", e.getMessage()));
            }
            throw e;
        }
    }

    @PostMapping("/{id}/archiver")
    @PreAuthorize("hasAuthority('PERM_ORG_SETTINGS')")
    public ResponseEntity<Void> archive(@PathVariable java.util.UUID id, Authentication auth) {
        service.archive(auth, id);
        return ResponseEntity.noContent().build();
    }
}
