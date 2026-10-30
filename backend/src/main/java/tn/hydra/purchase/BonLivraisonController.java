package tn.hydra.purchase;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.util.Map;
import java.util.UUID;

/**
 * Bon de Livraison (BL) REST API — Part 2.2.
 *
 * <p>Two-layer security (mirrors {@code PurchaseOrderController}):
 * <ul>
 *   <li>L1: {@code @PreAuthorize} (STOCK_VIEW read / STOCK_MOVE write / OWNER bypass)</li>
 *   <li>L2: {@code BonLivraisonService.requirePermission} + {@code requireProjectVisible}
 *       + {@code assertLocationAccess} + {@code assertDocumentAccessible} (IDOR-safe)</li>
 * </ul>
 *
 * <p>Endpoints:
 * <pre>
 *   POST   /api/bons-livraison                     create DRAFT BL (from CONFIRMED BC or DIRECT)
 *   GET    /api/bons-livraison?projectId=&bcId=    list with lineCount (no N+1)
 *   GET    /api/bons-livraison/{id}                detail with embedded lines
 *   POST   /api/bons-livraison/{id}/lines          add line to DRAFT (hard cap enforced)
 *   PATCH  /api/bons-livraison/{id}/lines/{lineId} update line in DRAFT
 *   DELETE /api/bons-livraison/{id}/lines/{lineId} remove line in DRAFT
 *   POST   /api/bons-livraison/{id}/post           DRAFT→POSTED (fires N stock INs in one transaction)
 *   POST   /api/bons-livraison/{id}/cancel         DRAFT→CANCELLED (no stock moves fired)
 *   POST   /api/bons-livraison/{id}/documents      link BL PDF document
 * </pre>
 */
@RestController
@RequestMapping("/api/bons-livraison")
public class BonLivraisonController {

    private final BonLivraisonService service;

    public BonLivraisonController(BonLivraisonService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public BonLivraisonService.BlResponse create(@RequestBody BonLivraisonService.CreateBlRequest body,
                                                  Authentication auth) {
        return service.create(auth, body);
    }

    @GetMapping
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public BonLivraisonService.BlPage list(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID bcId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size,
            Authentication auth) {
        return service.list(auth, projectId, bcId, status, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public BonLivraisonService.BlResponse detail(@PathVariable UUID id, Authentication auth) {
        return service.detail(auth, id);
    }

    @PostMapping("/{id}/lines")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public BonLivraisonService.BlLineResponse addLine(@PathVariable UUID id,
                                                        @RequestBody BonLivraisonService.CreateBlLineRequest body,
                                                        Authentication auth) {
        return service.addLine(auth, id, body);
    }

    @PatchMapping("/{id}/lines/{lineId}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public BonLivraisonService.BlLineResponse updateLine(@PathVariable UUID id,
                                                           @PathVariable UUID lineId,
                                                           @RequestBody BonLivraisonService.UpdateBlLineRequest body,
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

    /** DRAFT → POSTED. Fires N stock IN moves in a single transaction. Any failure → full rollback. */
    @PostMapping("/{id}/post")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public BonLivraisonService.BlResponse post(@PathVariable UUID id, Authentication auth) {
        return service.post(auth, id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public BonLivraisonService.BlResponse cancel(@PathVariable UUID id,
                                                  @RequestBody(required = false) BonLivraisonService.CancelRequest body,
                                                  Authentication auth) {
        return service.cancel(auth, id, body);
    }

    @PostMapping("/{id}/documents")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public Map<String, Object> linkDocument(@PathVariable UUID id,
                                              @RequestBody BonLivraisonService.LinkDocumentRequest body,
                                              Authentication auth) {
        return service.linkDocument(auth, id, body);
    }
}
