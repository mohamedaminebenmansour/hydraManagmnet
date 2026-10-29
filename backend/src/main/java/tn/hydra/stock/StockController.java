package tn.hydra.stock;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import tn.hydra.common.PageResponse;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.Authentication;

/**
 * Stock REST API — Part 1.2 (services + controllers).
 *
 * Security style: @PreAuthorize at the HTTP layer (defense layer 1) +
 * service-layer permission/location checks in StockService (layer 2),
 * exactly like CashController / ProcessController.
 *
 * JSON errors always: BusinessRuleException → 409 {code,message},
 * BadRequestException → 400, ResourceNotFoundException → 404,
 * AccessDeniedException (@PreAuthorize) → 403 — all via GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/api/stock")
public class StockController {

    private final StockService service;

    public StockController(StockService service) {
        this.service = service;
    }

    // ── items ───────────────────────────────────────────────────────────

    @GetMapping("/items")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public List<StockService.ItemResponse> items(
            @RequestParam(required = false) Boolean includeInactive,
            @RequestParam(required = false) String q,
            Authentication auth) {
        return service.listItems(auth, Boolean.TRUE.equals(includeInactive), q);
    }

    @PostMapping("/items")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_ADMIN')")
    public StockService.ItemResponse createItem(@RequestBody StockService.CreateItemRequest body,
                                                Authentication auth) {
        return service.createItem(auth, body);
    }

    @PutMapping("/items/{id}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_ADMIN')")
    public StockService.ItemResponse updateItem(@PathVariable UUID id,
                                                @RequestBody StockService.UpdateItemRequest body,
                                                Authentication auth) {
        return service.updateItem(auth, id, body);
    }

    // ── aliases ─────────────────────────────────────────────────────────

    @GetMapping("/items/{id}/aliases")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public List<StockService.AliasResponse> aliases(@PathVariable UUID id,
                                                    Authentication auth) {
        return service.listAliases(auth, id);
    }

    @PostMapping("/items/{id}/aliases")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_ADMIN')")
    public StockService.AliasResponse createAlias(@PathVariable UUID id,
                                                  @RequestBody StockService.CreateAliasRequest body,
                                                  Authentication auth) {
        return service.createAlias(auth, id, body);
    }

    @DeleteMapping("/items/{id}/aliases/{aliasId}")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_ADMIN')")
    public Map<String, Object> deleteAlias(@PathVariable UUID id, @PathVariable UUID aliasId,
                                           Authentication auth) {
        service.deleteAlias(auth, id, aliasId);
        return Map.of("deleted", true);
    }

    // ── locations ───────────────────────────────────────────────────────

    @GetMapping("/locations")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public List<StockService.LocationResponse> locations(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) Boolean includeInactive,
            Authentication auth) {
        return service.listLocations(auth, projectId, Boolean.TRUE.equals(includeInactive));
    }

    @PostMapping("/locations")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_ADMIN')")
    public StockService.LocationResponse createLocation(@RequestBody StockService.CreateLocationRequest body,
                                                        Authentication auth) {
        return service.createLocation(auth, body);
    }

    // ── balances ────────────────────────────────────────────────────────

    @GetMapping("/balances")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public List<StockService.BalanceRow> balances(
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID itemId,
            @RequestParam(required = false) Boolean includeZero,
            Authentication auth) {
        return service.balances(auth, projectId, locationId, itemId,
                Boolean.TRUE.equals(includeZero));
    }

    /** Company-wide aggregation — per-item total across all org locations. */
    @GetMapping("/balances/summary")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public List<StockService.OrgBalanceRow> orgSummary(Authentication auth) {
        return service.orgSummary(auth);
    }

    // ── moves ───────────────────────────────────────────────────────────

    @PostMapping("/moves")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public StockService.MoveResponse postMove(@RequestBody StockService.PostMoveRequest body,
                                              Authentication auth) {
        return service.postMove(auth, body);
    }

    @GetMapping("/moves")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public StockService.MovePage history(
            @RequestParam(required = false) UUID itemId,
            @RequestParam(required = false) UUID locationId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "50") int size,
            Authentication auth) {
        return service.history(auth, itemId, locationId, projectId, page, size);
    }

    /** Reverse a mistaken move — creates the OPPOSITE move (never edits/deletes). */
    @PostMapping("/moves/{id}/reverse")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public StockService.MoveResponse reverse(@PathVariable UUID id,
                                             @RequestBody(required = false) StockService.ReverseRequest body,
                                             Authentication auth) {
        return service.reverse(auth, id, body);
    }

    /** Link an EXISTING document to an EXISTING move (junction table — no UPDATE of the move). */
    @PostMapping("/moves/{id}/documents")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_MOVE')")
    public Map<String, Object> linkDocument(@PathVariable UUID id,
                                            @RequestBody StockService.LinkDocumentRequest body,
                                            Authentication auth) {
        return service.linkDocument(auth, id, body);
    }

    @GetMapping("/moves/{id}/documents")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public List<Map<String, Object>> documentsOf(@PathVariable UUID id,
                                                 Authentication auth) {
        return service.documentsOf(auth, id);
    }

    // ── stock document folder hook (minimal, non-breaking) ─────────────

    /**
     * Resolve-or-create the STOCK subfolder (05.01 BL / 05.02 BC / 05.03 FACTURE /
     * 05.04 AUTRE) under the project's "05 ACHAT" folder. The frontend passes the
     * returned folderId to the EXISTING document upload API — nothing in the
     * upload path is modified.
     */
    @GetMapping("/doc-folder")
    @PreAuthorize("hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')")
    public Map<String, Object> docFolder(@RequestParam UUID projectId,
                                         @RequestParam String kind,
                                         Authentication auth) {
        return service.ensureStockFolder(auth, projectId, kind);
    }
}
