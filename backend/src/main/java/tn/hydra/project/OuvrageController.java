package tn.hydra.project;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * OuvrageController — REST endpoints for the structured objects of a chantier.
 *
 * <p>All routes are nested under {@code /api/projects/{projectId}/ouvrages}
 * because an ouvrage always belongs to exactly one chantier. Permission
 * checks match the project ones: read = PERM_PROJECT_READ (or project member),
 * write = PERM_PROJECT_WRITE / PERM_PROJECT_ASSIGN (or project ADMIN/MANAGER).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/ouvrages")
public class OuvrageController {

    private final OuvrageService ouvrages;

    public OuvrageController(OuvrageService ouvrages) {
        this.ouvrages = ouvrages;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_PROJECT_READ')")
    public List<OuvrageService.OuvrageResponse> list(@PathVariable UUID projectId, Authentication auth) {
        return ouvrages.list(auth, projectId);
    }

    @GetMapping("/{ouvrageId}")
    @PreAuthorize("hasAuthority('PERM_PROJECT_READ')")
    public ResponseEntity<OuvrageService.OuvrageResponse> get(@PathVariable UUID projectId,
                                                              @PathVariable UUID ouvrageId,
                                                              Authentication auth) {
        OuvrageService.OuvrageResponse o = ouvrages.get(auth, projectId, ouvrageId);
        return o == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(o);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_PROJECT_WRITE')")
    public OuvrageService.OuvrageResponse create(@PathVariable UUID projectId,
                                                 @RequestBody OuvrageService.CreateOuvrageRequest body,
                                                 Authentication auth) {
        return ouvrages.create(auth, projectId, body);
    }

    @PutMapping("/{ouvrageId}")
    @PreAuthorize("hasAuthority('PERM_PROJECT_WRITE')")
    public OuvrageService.OuvrageResponse update(@PathVariable UUID projectId,
                                                  @PathVariable UUID ouvrageId,
                                                  @RequestBody OuvrageService.UpdateOuvrageRequest body,
                                                  Authentication auth) {
        return ouvrages.update(auth, projectId, ouvrageId, body);
    }

    @DeleteMapping("/{ouvrageId}")
    @PreAuthorize("hasAuthority('PERM_PROJECT_WRITE')")
    public ResponseEntity<?> delete(@PathVariable UUID projectId,
                                    @PathVariable UUID ouvrageId,
                                    Authentication auth) {
        ouvrages.delete(auth, projectId, ouvrageId);
        return ResponseEntity.noContent().build();
    }
}
