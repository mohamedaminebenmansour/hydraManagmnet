package tn.hydra.purchase;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Bon de Commande (BC) REST API — Part 2.1.
 *
 * <p>Two-layer security (mirrors {@code StockController}):
 * <ul>
 *   <li>L1: {@code @PreAuthorize} on every endpoint (HTTP-layer)</li>
 *   <li>L2: {@code PurchaseOrderService.requirePermission} + {@code requireProjectVisible}
 *       (service-layer, IDOR-safe)</li>
 * </ul>
 *
 * <p>JSON errors via {@code GlobalExceptionHandler}:
 * {@code BusinessRuleException}→409, {@code ResourceNotFound}→404,
 * {@code BadRequest}→400, {@code AccessDeniedException}→403.
 *
 * <p>Endpoints:
 * <pre>
 *   POST   /api/purchase-orders                     create BC + initial lines (STOCK_MOVE)
 *   GET    /api/purchase-orders?projectId=&status=  list with lineCount (STOCK_VIEW)
 *   GET    /api/purchase-orders/{id}                detail with embedded lines (STOCK_VIEW)
 *   PATCH  /api/purchase-orders/{id}                update DRAFT only (STOCK_MOVE)
 *   POST   /api/purchase-orders/{id}/lines          add line to DRAFT (STOCK_MOVE)
 *   PATCH  /api/purchase-orders/{id}/lines/{lineId} update line in DRAFT (STOCK_MOVE)
 *   DELETE /api/purchase-orders/{id}/lines/{lineId} remove line in DRAFT (STOCK_MOVE)
 *   POST   /api/purchase-orders/{id}/confirm        DRAFT → CONFIRMED (STOCK_MOVE)
 *   POST   /api/purchase-orders/{id}/cancel         DRAFT/CONFIRMED → CANCELLED (STOCK_MOVE)
 *   POST   /api/purchase-orders/{id}/documents       link BC PDF document (STOCK_MOVE)
 * </pre>
 */
@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService service;

    public PurchaseOrderController(PurchaseOrderService service) {
        this.service = service;
    }

    // ── CREATE ───────────────────────────────────────────────────────────

    @PostMapping
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public PurchaseOrderService.BcResponse create(@RequestBody PurchaseOrderService.CreateBcRequest body,
                                                   Authentication auth) {
        return service.create(auth, body);
    }

    // ── LIST ─────────────────────────────────────────────────────────────

    @GetMapping
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public PurchaseOrderService.BcPage list(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size,
            Authentication auth) {
        return service.list(auth, projectId, status, page, size);
    }

    // ── DETAIL ───────────────────────────────────────────────────────────

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public PurchaseOrderService.BcResponse detail(@PathVariable UUID id, Authentication auth) {
        return service.detail(auth, id);
    }

    // ── UPDATE (DRAFT only) ──────────────────────────────────────────────

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public PurchaseOrderService.BcResponse update(@PathVariable UUID id,
                                                   @RequestBody PurchaseOrderService.UpdateBcRequest body,
                                                   Authentication auth) {
        return service.update(auth, id, body);
    }

    // ── LINE CRUD (DRAFT only) ───────────────────────────────────────────

    @PostMapping("/{id}/lines")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public PurchaseOrderService.BcLineResponse addLine(@PathVariable UUID id,
                                                        @RequestBody PurchaseOrderService.AddLineRequest body,
                                                        Authentication auth) {
        return service.addLine(auth, id, body);
    }

    @PatchMapping("/{id}/lines/{lineId}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public PurchaseOrderService.BcLineResponse updateLine(@PathVariable UUID id,
                                                           @PathVariable UUID lineId,
                                                           @RequestBody PurchaseOrderService.UpdateLineRequest body,
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

    // ── STATUS TRANSITIONS ───────────────────────────────────────────────

    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public PurchaseOrderService.BcResponse confirm(@PathVariable UUID id, Authentication auth) {
        return service.confirm(auth, id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public PurchaseOrderService.BcResponse cancel(@PathVariable UUID id,
                                                   @RequestBody(required = false) PurchaseOrderService.CancelRequest body,
                                                   Authentication auth) {
        return service.cancel(auth, id, body);
    }

    // ── DOCUMENT LINK ────────────────────────────────────────────────────

    @PostMapping("/{id}/documents")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public Map<String, Object> linkDocument(@PathVariable UUID id,
                                              @RequestBody PurchaseOrderService.LinkDocumentRequest body,
                                              Authentication auth) {
        return service.linkDocument(auth, id, body);
    }

    /** POST /api/purchase-orders/{id}/close — CONFIRMED → CLOSED (all lines must be MATCHED). */
    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public PurchaseOrderService.BcResponse close(@PathVariable UUID id,
                                                  @RequestParam(required = false, defaultValue = "false") boolean force,
                                                  @RequestBody(required = false) PurchaseOrderService.CloseRequest body,
                                                  Authentication auth) {
        return service.close(auth, id, force, body);
    }

    // ── MATCH (Part 2.3 — read-only soft match) ───────────────────────────

    /** Match overview across all BCs (filtered by project for non-owners). */
    @GetMapping("/match")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public PurchaseOrderService.MatchPage matchOverview(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size,
            Authentication auth) {
        return service.matchOverview(auth, projectId, status, page, size);
    }

    /** Match for a single BC — one row per BC line. */
    @GetMapping("/{id}/match")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public List<PurchaseOrderService.BcLineMatchRow> matchForBc(@PathVariable UUID id, Authentication auth) {
        return service.matchForBc(auth, id);
    }
}
