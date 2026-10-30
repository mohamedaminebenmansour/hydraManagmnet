package tn.hydra.purchase;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.UUID;

/**
 * Facture (supplier invoice) REST API — Part 3.1.
 *
 * <p>Endpoints:
 * <pre>
 *   POST   /api/factures                     create DRAFT facture + initial lines (STOCK_MOVE)
 *   GET    /api/factures?projectId=&status=  list with lineCount (STOCK_VIEW)
 *   GET    /api/factures/{id}               detail with embedded lines (STOCK_VIEW)
 *   PATCH  /api/factures/{id}               update DRAFT only (STOCK_MOVE)
 *   POST   /api/factures/{id}/lines         add line to DRAFT (STOCK_MOVE)
 *   PATCH  /api/factures/{id}/lines/{lineId} update line in DRAFT (STOCK_MOVE)
 *   DELETE /api/factures/{id}/lines/{lineId} remove line in DRAFT (STOCK_MOVE)
 *   POST   /api/factures/{id}/validate      DRAFT→VALIDATED (STOCK_MOVE)
 *   POST   /api/factures/{id}/cancel        DRAFT/VALIDATED→CANCELLED (STOCK_MOVE)
 *   POST   /api/factures/{id}/documents     link facture PDF (STOCK_MOVE)
 * </pre>
 */
@RestController
@RequestMapping("/api/factures")
public class FactureController {

    private final FactureService service;

    public FactureController(FactureService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public FactureService.FactureResponse create(@RequestBody FactureService.CreateFactureRequest body,
                                                  Authentication auth) {
        return service.create(auth, body);
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public FactureService.FacturePage list(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size,
            Authentication auth) {
        return service.list(auth, projectId, status, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public FactureService.FactureResponse detail(@PathVariable UUID id, Authentication auth) {
        return service.detail(auth, id);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public FactureService.FactureResponse update(@PathVariable UUID id,
                                                  @RequestBody FactureService.UpdateFactureRequest body,
                                                  Authentication auth) {
        return service.update(auth, id, body);
    }

    @PostMapping("/{id}/lines")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public FactureService.FactureLineResponse addLine(@PathVariable UUID id,
                                                       @RequestBody FactureService.CreateFactureLineRequest body,
                                                       Authentication auth) {
        return service.addLine(auth, id, body);
    }

    @PatchMapping("/{id}/lines/{lineId}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public FactureService.FactureLineResponse updateLine(@PathVariable UUID id,
                                                          @PathVariable UUID lineId,
                                                          @RequestBody FactureService.UpdateFactureLineRequest body,
                                                          Authentication auth) {
        return service.updateLine(auth, id, lineId, body);
    }

    @DeleteMapping("/{id}/lines/{lineId}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public Map<String, Object> removeLine(@PathVariable UUID id, @PathVariable UUID lineId,
                                            Authentication auth) {
        service.removeLine(auth, id, lineId);
        return Map.of("deleted", true);
    }

    @PostMapping("/{id}/validate")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public FactureService.FactureResponse validate(@PathVariable UUID id, Authentication auth) {
        return service.validate(auth, id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public FactureService.FactureResponse cancel(@PathVariable UUID id,
                                                  @RequestBody(required = false) FactureService.CancelRequest body,
                                                  Authentication auth) {
        return service.cancel(auth, id, body);
    }

    @PostMapping("/{id}/documents")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public Map<String, Object> linkDocument(@PathVariable UUID id,
                                              @RequestBody FactureService.LinkDocumentRequest body,
                                              Authentication auth) {
        return service.linkDocument(auth, id, body);
    }
}
