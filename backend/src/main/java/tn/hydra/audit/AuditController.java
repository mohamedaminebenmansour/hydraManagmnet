package tn.hydra.audit;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService service;

    public AuditController(AuditService service) {
        this.service = service;
    }

    /**
     * List audit events with optional filters:
     *   userId       — restrict to a single user (per-user history view)
     *   from / to    — date range (ISO yyyy-MM-dd; "to" is inclusive)
     *   action       — exact action match (e.g. PROCESS_STARTED)
     *   entityType   — exact entity type (e.g. project, document)
     *   page / size  — pagination (default page 0, size 50, max 200)
     */
    @GetMapping("/events")
    @PreAuthorize("hasAuthority('PERM_AUDIT_VIEW')")
    public List<AuditService.EventResponse> events(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication auth) {
        return service.events(auth, userId, from, to, action, entityType, page, size);
    }

    /**
     * One-shot filter metadata: distinct actions + entity types + total count
     * for the current org. Used to populate the audit page's filter dropdowns
     * without making N separate queries.
     */
    @GetMapping("/filters")
    @PreAuthorize("hasAuthority('PERM_AUDIT_VIEW')")
    public AuditService.FiltersResponse filters(Authentication auth) {
        return service.filters(auth);
    }

    @GetMapping("/users/{id}/summary")
    @PreAuthorize("hasAnyAuthority('PERM_AUDIT_VIEW','PERM_USER_UPDATE')")
    public AuditService.UserSummaryResponse userSummary(@PathVariable UUID id, Authentication auth) {
        return service.userSummary(auth, id);
    }
}
