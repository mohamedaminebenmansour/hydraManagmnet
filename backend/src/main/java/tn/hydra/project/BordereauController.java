package tn.hydra.project;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BordereauController — REST endpoints for versioned price schedules.
 *
 * <p>All routes are nested under {@code /api/projects/{projectId}/bordereau}
 * because a bordereau always belongs to one chantier.
 *
 * <p>Reads require {@code PERM_PROJECT_READ} (or project membership).
 * Writes require {@code PERM_PROJECT_WRITE} (same as updating a project).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/bordereau")
public class BordereauController {

    private final BordereauService bordereau;
    private final BordereauImportService importService;

    public BordereauController(BordereauService bordereau, BordereauImportService importService) {
        this.bordereau = bordereau;
        this.importService = importService;
    }

    // ── VERSIONS ────────────────────────────────────────────────────

    /** GET /api/projects/{projectId}/bordereau/versions — list all versions. */
    @GetMapping("/versions")
    @PreAuthorize("hasAuthority('PERM_PROJECT_READ')")
    public List<BordereauService.VersionResponse> listVersions(@PathVariable UUID projectId,
                                                                Authentication auth) {
        return bordereau.listVersions(auth, projectId);
    }

    /** POST /api/projects/{projectId}/bordereau/import (multipart .xls/.xlsx) —
     *  import an Excel bordereau as a new DRAFT version. Returns a preview
     *  with warnings + pairs. */
    @PostMapping(value = "/import", consumes = "multipart/form-data")
    @PreAuthorize("hasAuthority('PERM_PROJECT_WRITE')")
    public BordereauImportService.ImportPreview importExcel(@PathVariable UUID projectId,
                                                             @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                                             Authentication auth) {
        return importService.importExcel(auth, projectId, file);
    }

    /** POST /api/projects/{projectId}/bordereau/versions — create a new DRAFT version
     *  as a copy of the active one. */
    @PostMapping("/versions")
    @PreAuthorize("hasAuthority('PERM_PROJECT_WRITE')")
    public BordereauService.VersionResponse createVersion(@PathVariable UUID projectId,
                                                          @RequestBody BordereauService.CreateVersionRequest body,
                                                          Authentication auth) {
        return bordereau.createVersion(auth, projectId, body);
    }

    /** POST /api/projects/{projectId}/bordereau/versions/{versionId}/activate —
     *  activate a DRAFT version (archives the previous one). */
    @PostMapping("/versions/{versionId}/activate")
    @PreAuthorize("hasAuthority('PERM_PROJECT_WRITE')")
    public BordereauService.VersionResponse activateVersion(@PathVariable UUID projectId,
                                                             @PathVariable UUID versionId,
                                                             Authentication auth) {
        return bordereau.activateVersion(auth, projectId, versionId);
    }

    // ── LINES ──────────────────────────────────────────────────────

    /** GET /api/projects/{projectId}/bordereau/versions/{versionId}/lines —
     *  list all lines of a version. */
    @GetMapping("/versions/{versionId}/lines")
    @PreAuthorize("hasAuthority('PERM_PROJECT_READ')")
    public List<BordereauService.LineResponse> listLines(@PathVariable UUID projectId,
                                                         @PathVariable UUID versionId,
                                                         Authentication auth) {
        return bordereau.listLines(auth, projectId, versionId);
    }

    /** PUT /api/projects/{projectId}/bordereau/versions/{versionId}/lines/{lineId} —
     *  update a line (only while the version is DRAFT). */
    @PutMapping("/versions/{versionId}/lines/{lineId}")
    @PreAuthorize("hasAuthority('PERM_PROJECT_WRITE')")
    public BordereauService.LineResponse updateLine(@PathVariable UUID projectId,
                                                     @PathVariable UUID versionId,
                                                     @PathVariable UUID lineId,
                                                     @RequestBody BordereauService.UpdateLineRequest body,
                                                     Authentication auth) {
        return bordereau.updateLine(auth, projectId, versionId, lineId, body);
    }

    /** PUT /api/projects/{projectId}/bordereau/versions/{versionId}/lines/ouvrage —
     *  set one ouvrage (or none) on several lines of a DRAFT version, in one transaction. */
    @PutMapping("/versions/{versionId}/lines/ouvrage")
    @PreAuthorize("hasAuthority('PERM_PROJECT_WRITE')")
    public List<BordereauService.LineResponse> assignOuvrage(@PathVariable UUID projectId,
                                                             @PathVariable UUID versionId,
                                                             @RequestBody BordereauService.AssignOuvrageRequest body,
                                                             Authentication auth) {
        return bordereau.assignOuvrage(auth, projectId, versionId, body);
    }

    // ── COMPARE ────────────────────────────────────────────────────

    /** GET /api/projects/{projectId}/bordereau/compare?old={oldId}&new={newId} —
     *  compare two versions, returns per article_no: added, removed,
     *  quantity changed, price changed (with old + new values). */
    @GetMapping("/compare")
    @PreAuthorize("hasAuthority('PERM_PROJECT_READ')")
    public List<Map<String, Object>> compareVersions(@PathVariable UUID projectId,
                                                      @RequestParam UUID old,
                                                      @RequestParam UUID neu,
                                                      Authentication auth) {
        return bordereau.compareVersions(auth, projectId, old, neu);
    }
}
