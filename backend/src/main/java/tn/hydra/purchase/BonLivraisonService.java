package tn.hydra.purchase;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.BadRequestException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.purchase.domain.BonLivraison;
import tn.hydra.purchase.domain.BonLivraisonLine;
import tn.hydra.purchase.domain.BonLivraisonStatus;
import tn.hydra.purchase.domain.PurchaseOrder;
import tn.hydra.purchase.domain.PurchaseOrderStatus;
import tn.hydra.purchase.repository.BonLivraisonLineRepository;
import tn.hydra.purchase.repository.BonLivraisonRepository;
import tn.hydra.purchase.repository.PurchaseOrderLineRepository;
import tn.hydra.purchase.repository.PurchaseOrderRepository;
import tn.hydra.stock.StockService;
import tn.hydra.stock.domain.StockItem;
import tn.hydra.stock.domain.StockLocation;
import tn.hydra.stock.domain.StockLocationType;
import tn.hydra.stock.repository.StockItemRepository;
import tn.hydra.stock.repository.StockLocationRepository;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ============================================================================
 *  BonLivraisonService — Bon de Livraison (BL) lifecycle + stock IN lineage.
 * ============================================================================
 *
 *  The keystone is {@link #post(Authentication, UUID)} — it converts a DRAFT BL
 *  into POSTED and fires N stock_move INs (one per BL line) in a SINGLE
 *  @Transactional transaction. If any postMove fails → full rollback (no
 *  partial moves, no POSTED BL, no orphaned stock). This is the S23d guarantee.
 *
 *  Hard cap (v1): SUM(qty_received) per bc_line_id across all POSTED BLs
 *  must be ≤ the BC line's qty_ordered. Else 409 BL_OVER_RECEIVE.
 *
 *  Mirrors PurchaseOrderService conventions: DTOs as static nested records,
 *  two-layer security (requirePermission + requireProjectVisible), hand-rolled
 *  JSON audit, esc/jsonOrNull helpers.
 * ============================================================================
 */
@Service
public class BonLivraisonService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;
    private final BonLivraisonRepository bls;
    private final BonLivraisonLineRepository blLines;
    private final PurchaseOrderRepository pos;
    private final PurchaseOrderLineRepository poLines;
    private final StockItemRepository items;
    private final StockLocationRepository locations;
    private final StockService stockService;

    public BonLivraisonService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit,
                                BonLivraisonRepository bls, BonLivraisonLineRepository blLines,
                                PurchaseOrderRepository pos, PurchaseOrderLineRepository poLines,
                                StockItemRepository items, StockLocationRepository locations,
                                StockService stockService) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.bls = bls;
        this.blLines = blLines;
        this.pos = pos;
        this.poLines = poLines;
        this.items = items;
        this.locations = locations;
        this.stockService = stockService;
    }

    // ══════════════════════════════════════════════════════════════
    //  DTOs
    // ══════════════════════════════════════════════════════════════

    public record BlLineResponse(UUID id, UUID bonLivraisonId, UUID bcLineId, UUID itemId,
                                  String itemName, BigDecimal qtyReceived, String uom, String notes,
                                  UUID createdBy, Instant createdAt) {}

    public record BlResponse(UUID id, UUID orgId, UUID projectId, String projectName,
                              UUID bcId, String bcReference, UUID supplierPartyId, String supplierName,
                              String status, String reference, Instant receivedAt,
                              UUID locationId, String locationName, String locationType,
                              UUID documentId, String notes,
                              UUID createdBy, Instant createdAt, Instant updatedAt,
                              Integer lineCount, List<BlLineResponse> lines) {}

    public record BlListRow(UUID id, UUID projectId, String projectName,
                            UUID bcId, String bcReference,
                            UUID supplierPartyId, String supplierName,
                            String status, String reference, Instant receivedAt,
                            UUID locationId, String locationName,
                            UUID createdBy, Instant createdAt,
                            Integer lineCount) {}

    public record BlPage(List<BlListRow> items, long total, int page, int size) {}

    public record CreateBlLineRequest(UUID bcLineId, UUID itemId, BigDecimal qtyReceived,
                                       String uom, String notes) {}

    public record CreateBlRequest(UUID projectId, UUID bcId, UUID supplierPartyId, String reference,
                                   UUID locationId, UUID documentId, String notes,
                                   List<CreateBlLineRequest> lines) {}

    public record UpdateBlLineRequest(BigDecimal qtyReceived, String uom, String notes) {}

    public record CancelRequest(String reason) {}

    public record LinkDocumentRequest(UUID documentId) {}

    // ══════════════════════════════════════════════════════════════
    //  CREATE — POST /api/bons-livraison
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public BlResponse create(Authentication auth, CreateBlRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "create-bl");
        if (req.projectId() == null) throw new BadRequestException("PROJECT_REQUIRED", "projectId est requis");
        if (req.locationId() == null) throw new BadRequestException("LOCATION_REQUIRED", "locationId est requis (lieu de réception)");
        requireProjectVisible(me, req.projectId());

        // Validate BC (if provided) — must be CONFIRMED + same project
        String bcReference = null;
        if (req.bcId() != null) {
            PurchaseOrder po = pos.findByIdAndOrgId(req.bcId(), me.getOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("BC_NOT_FOUND", "Bon de commande introuvable"));
            if (!po.getProjectId().equals(req.projectId())) {
                throw new BusinessRuleException("BC_PROJECT_MISMATCH",
                        "Le bon de commande n'appartient pas à ce projet");
            }
            if (po.getStatus() != PurchaseOrderStatus.CONFIRMED) {
                throw new BusinessRuleException("BC_NOT_CONFIRMED",
                        "Le bon de commande doit être confirmé avant réception (statut: " + po.getStatus() + ")");
            }
            bcReference = po.getReference();
        }

        // Validate location (must exist in org + caller has access)
        StockLocation loc = locations.findByIdAndOrgId(req.locationId(), me.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("LOCATION_NOT_FOUND", "Emplacement introuvable"));
        assertLocationAccess(me, loc, "create-bl");

        // Validate supplier (org-scoped party)
        String supplierName = null;
        if (req.supplierPartyId() != null) {
            supplierName = validateSupplier(req.supplierPartyId(), me.getOrgId());
        }

        // Validate document access (if provided)
        if (req.documentId() != null) {
            assertDocumentAccessible(me, req.documentId());
        }

        Instant now = Instant.now();
        BonLivraison bl = new BonLivraison();
        bl.markNew();
        bl.setOrgId(me.getOrgId());
        bl.setProjectId(req.projectId());
        bl.setBcId(req.bcId());
        bl.setSupplierPartyId(req.supplierPartyId());
        bl.setStatus(BonLivraisonStatus.DRAFT);
        bl.setReference(req.reference());
        bl.setLocationId(req.locationId());
        bl.setDocumentId(req.documentId());
        bl.setNotes(req.notes());
        bl.setCreatedBy(me.getId());
        bl.setCreatedAt(now);
        bl.setUpdatedAt(now);
        bls.save(bl);

        List<BlLineResponse> lineResps = new ArrayList<>();
        if (req.lines() != null) {
            for (CreateBlLineRequest lineReq : req.lines()) {
                lineResps.add(createLineInternal(bl, lineReq, me, now));
            }
        }

        audit.log("STOCK_BL_CREATED", "bon_livraison", bl.getId(),
                "{\"projectId\":\"" + bl.getProjectId() + "\""
                + ",\"bcId\":" + (bl.getBcId() == null ? "null" : "\"" + bl.getBcId() + "\"")
                + ",\"locationId\":\"" + bl.getLocationId() + "\""
                + ",\"lineCount\":" + lineResps.size()
                + "}");

        return toBlResponse(bl, projectName(bl.getProjectId()), bcReference, supplierName,
                loc.getName(), loc.getType().name(), lineResps, lineResps.size());
    }

    // ══════════════════════════════════════════════════════════════
    //  LIST — GET /api/bons-livraison?projectId=&bcId=&status=
    // ══════════════════════════════════════════════════════════════

    public BlPage list(Authentication auth, UUID projectId, UUID bcId, String statusStr, int page, int size) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_VIEW", "list-bl");
        if (page < 0) page = 0;
        if (size <= 0 || size > 200) size = 50;

        if (projectId != null) requireProjectVisible(me, projectId);
        BonLivraisonStatus statusFilter = parseStatus(statusStr);

        StringBuilder sql = new StringBuilder("""
                select bl.id, bl.project_id, p.name as project_name,
                       bl.bc_id, po.reference as bc_reference,
                       bl.supplier_party_id, party.name as supplier_name,
                       bl.status::text as status, bl.reference, bl.received_at,
                       bl.location_id, loc.name as location_name,
                       bl.created_by, bl.created_at,
                       (select count(*) from bon_livraison_line l where l.bon_livraison_id = bl.id) as line_count
                from bon_livraison bl
                join project p on p.id = bl.project_id
                left join purchase_order po on po.id = bl.bc_id
                left join party on party.id = bl.supplier_party_id
                left join stock_location loc on loc.id = bl.location_id
                where bl.org_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(me.getOrgId());
        if (projectId != null) { sql.append(" and bl.project_id = ? "); args.add(projectId); }
        if (bcId != null)       { sql.append(" and bl.bc_id = ? ");       args.add(bcId); }
        if (statusFilter != null) { sql.append(" and bl.status = ?::text "); args.add(statusFilter.name()); }
        if (!Boolean.TRUE.equals(me.getIsOwner())) {
            sql.append(" and bl.project_id in (select pm.project_id from project_member pm where pm.user_id = ?) ");
            args.add(me.getId());
        }
        sql.append(" order by bl.created_at desc limit ? offset ?");
        args.add(size); args.add(page * size);

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<BlListRow> items = rows.stream().map(r -> new BlListRow(
                (UUID) r.get("id"),
                (UUID) r.get("project_id"),
                (String) r.get("project_name"),
                (UUID) r.get("bc_id"),
                (String) r.get("bc_reference"),
                (UUID) r.get("supplier_party_id"),
                (String) r.get("supplier_name"),
                (String) r.get("status"),
                (String) r.get("reference"),
                toInstant(r.get("received_at")),
                (UUID) r.get("location_id"),
                (String) r.get("location_name"),
                (UUID) r.get("created_by"),
                toInstant(r.get("created_at")),
                ((Number) r.get("line_count")).intValue()
        )).toList();

        StringBuilder csql = new StringBuilder("select count(*) from bon_livraison bl where bl.org_id = ? ");
        List<Object> cargs = new ArrayList<>(); cargs.add(me.getOrgId());
        if (projectId != null) { csql.append(" and bl.project_id = ? "); cargs.add(projectId); }
        if (bcId != null)       { csql.append(" and bl.bc_id = ? ");       cargs.add(bcId); }
        if (statusFilter != null) { csql.append(" and bl.status = ?::text "); cargs.add(statusFilter.name()); }
        if (!Boolean.TRUE.equals(me.getIsOwner())) {
            csql.append(" and bl.project_id in (select pm.project_id from project_member pm where pm.user_id = ?) ");
            cargs.add(me.getId());
        }
        Long total = jdbc.queryForObject(csql.toString(), Long.class, cargs.toArray());
        if (total == null) total = 0L;

        return new BlPage(items, total, page, size);
    }

    // ══════════════════════════════════════════════════════════════
    //  DETAIL — GET /api/bons-livraison/{id}
    // ══════════════════════════════════════════════════════════════

    public BlResponse detail(Authentication auth, UUID blId) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_VIEW", "bl-detail");
        BonLivraison bl = blOrNotFound(me.getOrgId(), blId);
        requireProjectVisible(me, bl.getProjectId());
        StockLocation loc = locations.findByIdAndOrgId(bl.getLocationId(), me.getOrgId()).orElse(null);
        String bcRef = bcReference(bl.getBcId(), me.getOrgId());
        return toBlResponse(bl, projectName(bl.getProjectId()), bcRef, supplierName(bl),
                loc != null ? loc.getName() : null,
                loc != null ? loc.getType().name() : null,
                loadLines(bl), 0);
    }

    // ══════════════════════════════════════════════════════════════
    //  LINE CRUD (DRAFT only)
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public BlLineResponse addLine(Authentication auth, UUID blId, CreateBlLineRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "add-bl-line");
        BonLivraison bl = blOrNotFound(me.getOrgId(), blId);
        requireProjectVisible(me, bl.getProjectId());
        assertDraft(bl);
        return createLineInternal(bl, req, me, Instant.now());
    }

    @Transactional
    public BlLineResponse updateLine(Authentication auth, UUID blId, UUID lineId, UpdateBlLineRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "update-bl-line");
        BonLivraison bl = blOrNotFound(me.getOrgId(), blId);
        requireProjectVisible(me, bl.getProjectId());
        assertDraft(bl);
        BonLivraisonLine line = blLineOrNotFound(me.getOrgId(), lineId);
        if (!line.getBonLivraisonId().equals(blId)) {
            throw new BusinessRuleException("BL_LINE_MISMATCH", "La ligne n'appartient pas à ce bon de livraison");
        }
        if (req.qtyReceived() != null) {
            if (req.qtyReceived().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BadRequestException("BL_LINE_QTY_INVALID", "qtyReceived doit être > 0");
            }
            // If line has a bcLineId, re-check the hard cap with the new qty
            if (line.getBcLineId() != null) {
                checkHardCap(me.getOrgId(), line.getBcLineId(), req.qtyReceived(), line.getId());
            }
            line.setQtyReceived(req.qtyReceived());
        }
        if (req.uom() != null && !req.uom().isBlank()) line.setUom(req.uom());
        if (req.notes() != null) line.setNotes(req.notes().isBlank() ? null : req.notes());
        line.setUpdatedAt(Instant.now());
        blLines.save(line);
        audit.log("STOCK_BL_LINE_UPDATED", "bon_livraison_line", line.getId(),
                "{\"qtyReceived\":" + line.getQtyReceived() + "}");
        return toLineResponse(line);
    }

    @Transactional
    public void removeLine(Authentication auth, UUID blId, UUID lineId) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "remove-bl-line");
        BonLivraison bl = blOrNotFound(me.getOrgId(), blId);
        requireProjectVisible(me, bl.getProjectId());
        assertDraft(bl);
        BonLivraisonLine line = blLineOrNotFound(me.getOrgId(), lineId);
        if (!line.getBonLivraisonId().equals(blId)) {
            throw new BusinessRuleException("BL_LINE_MISMATCH", "La ligne n'appartient pas à ce bon de livraison");
        }
        blLines.delete(line);
        audit.log("STOCK_BL_LINE_REMOVED", "bon_livraison_line", line.getId(),
                "{\"bonLivraisonId\":\"" + bl.getId() + "\"}");
    }

    // ══════════════════════════════════════════════════════════════
    //  POST — DRAFT → POSTED (the keystone: N stock INs in one transaction)
    // ══════════════════════════════════════════════════════════════

    /**
     * Convert a DRAFT BL into POSTED. Fires one stock_move IN per BL line
     * (in the SAME transaction — any failure rolls back all moves + the
     * status change). Validates:
     *   - BL is DRAFT
     *   - if bcId set: BC is CONFIRMED, same project
     *   - each line's bcLineId (if set) belongs to the BC + hard cap not exceeded
     *   - location exists + caller has access
     *
     * Then for each line calls StockService.postMove(IN, qty, uom, itemId,
     * toLocationId, documentId, bcLineId, blLineId). postMove is
     * @Transactional(REQUIRED) so it joins this transaction.
     */
    @Transactional
    public BlResponse post(Authentication auth, UUID blId) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "post-bl");
        BonLivraison bl = blOrNotFound(me.getOrgId(), blId);
        requireProjectVisible(me, bl.getProjectId());
        assertDraft(bl);

        // Validate BC (if set) — must be CONFIRMED + same project
        String bcReference = null;
        if (bl.getBcId() != null) {
            PurchaseOrder po = pos.findByIdAndOrgId(bl.getBcId(), me.getOrgId())
                    .orElseThrow(() -> new BusinessRuleException("BC_NOT_FOUND",
                            "Bon de commande lié introuvable"));
            if (!po.getProjectId().equals(bl.getProjectId())) {
                throw new BusinessRuleException("BC_PROJECT_MISMATCH",
                        "Le bon de commande n'appartient pas à ce projet");
            }
            if (po.getStatus() != PurchaseOrderStatus.CONFIRMED) {
                throw new BusinessRuleException("BC_NOT_CONFIRMED",
                        "Le bon de commande doit être confirmé avant réception");
            }
            bcReference = po.getReference();
        }

        // Validate location
        StockLocation loc = locations.findByIdAndOrgId(bl.getLocationId(), me.getOrgId())
                .orElseThrow(() -> new BusinessRuleException("LOCATION_NOT_FOUND",
                        "Emplacement de réception introuvable"));
        assertLocationAccess(me, loc, "post-bl");

        // Load lines + validate each (hard cap + item exists)
        List<BonLivraisonLine> lines = blLines.findByBonLivraisonIdAndOrgIdOrderByCreatedAt(bl.getId(), bl.getOrgId());
        if (lines.isEmpty()) {
            throw new BusinessRuleException("BL_NO_LINES", "Impossible de poster un bon de livraison sans lignes");
        }

        // Validate every line BEFORE firing any stock move (fail-fast → clean rollback)
        for (BonLivraisonLine line : lines) {
            // Item exists in org
            items.findByIdAndOrgId(line.getItemId(), bl.getOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("BC_LINE_ITEM_NOT_FOUND",
                            "Article introuvable pour la ligne " + line.getId()));
            // Hard cap (if bcLineId set)
            if (line.getBcLineId() != null) {
                checkHardCap(bl.getOrgId(), line.getBcLineId(), line.getQtyReceived(), line.getId());
                // bcLineId must belong to the BL's BC
                if (bl.getBcId() != null) {
                    UUID lineBcId = jdbc.queryForObject(
                            "select po.id from purchase_order_line pol join purchase_order po on po.id = pol.purchase_order_id where pol.id = ?",
                            UUID.class, line.getBcLineId());
                    if (lineBcId == null || !lineBcId.equals(bl.getBcId())) {
                        throw new BusinessRuleException("BL_LINE_BC_MISMATCH",
                                "La ligne " + line.getId() + " ne référence pas une ligne du bon de commande lié");
                    }
                }
            }
        }

        // ── FIRE N STOCK IN MOVES (single transaction — any failure rolls back ALL) ──
        List<StockService.MoveResponse> createdMoves = new ArrayList<>();
        for (BonLivraisonLine line : lines) {
            StockService.PostMoveRequest moveReq = new StockService.PostMoveRequest(
                    "IN",                                   // moveType
                    line.getQtyReceived(),                  // qty
                    line.getUom(),                          // uom
                    null,                                   // moveDate (server now)
                    null,                                   // fromLocationId (IN has none)
                    bl.getLocationId(),                     // toLocationId (where goods arrive)
                    line.getItemId(),                       // itemId
                    bl.getDocumentId(),                     // documentId (BL PDF, if any)
                    bl.getSupplierPartyId(),                // partyId (supplier)
                    null,                                   // cashMovementId
                    "BL " + (bl.getReference() != null ? bl.getReference() : bl.getId()),
                    line.getBcLineId(),                     // bcLineId (lineage)
                    line.getId(),                           // blLineId (lineage)
                    null                                    // metadata (ignored in 1.2, reserved)
            );
            // postMove is @Transactional(REQUIRED) → joins THIS transaction
            StockService.MoveResponse mv = stockService.postMove(auth, moveReq);
            createdMoves.add(mv);
        }

        // ── ALL MOVES SUCCEEDED → flip BL to POSTED ──
        Instant now = Instant.now();
        bl.setStatus(BonLivraisonStatus.POSTED);
        bl.setReceivedAt(now);
        bl.setUpdatedAt(now);
        bls.save(bl);

        // Audit (one event for the whole post + per-line moves already audited by postMove)
        audit.log("STOCK_BL_POSTED", "bon_livraison", bl.getId(),
                "{\"bcId\":" + (bl.getBcId() == null ? "null" : "\"" + bl.getBcId() + "\"")
                + ",\"lineCount\":" + lines.size()
                + ",\"movesCreated\":" + createdMoves.size()
                + ",\"locationId\":\"" + bl.getLocationId() + "\""
                + "}");

        List<BlLineResponse> lineResps = lines.stream().map(this::toLineResponse).toList();
        return toBlResponse(bl, projectName(bl.getProjectId()), bcReference, supplierName(bl),
                loc.getName(), loc.getType().name(), lineResps, lineResps.size());
    }

    // ══════════════════════════════════════════════════════════════
    //  CANCEL — DRAFT → CANCELLED (no stock moves fired)
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public BlResponse cancel(Authentication auth, UUID blId, CancelRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "cancel-bl");
        BonLivraison bl = blOrNotFound(me.getOrgId(), blId);
        requireProjectVisible(me, bl.getProjectId());
        // Cancel only while DRAFT (v1 decision — POSTED is terminal)
        if (bl.getStatus() != BonLivraisonStatus.DRAFT) {
            throw new BusinessRuleException("BL_INVALID_STATUS",
                    "Annulation impossible depuis le statut " + bl.getStatus() + " (DRAFT uniquement)");
        }
        String reason = req != null && req.reason() != null ? req.reason().trim() : null;
        bl.setStatus(BonLivraisonStatus.CANCELLED);
        bl.setUpdatedAt(Instant.now());
        bls.save(bl);
        audit.log("STOCK_BL_CANCELLED", "bon_livraison", bl.getId(),
                "{\"reason\":" + jsonOrNull(reason) + "}");
        return toBlResponse(bl, projectName(bl.getProjectId()), bcReference(bl.getBcId(), me.getOrgId()),
                supplierName(bl), null, null, loadLines(bl), 0);
    }

    // ══════════════════════════════════════════════════════════════
    //  DOCUMENT LINK — POST /api/bons-livraison/{id}/documents
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> linkDocument(Authentication auth, UUID blId, LinkDocumentRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "link-bl-document");
        BonLivraison bl = blOrNotFound(me.getOrgId(), blId);
        requireProjectVisible(me, bl.getProjectId());
        if (req.documentId() == null) {
            throw new BadRequestException("DOCUMENT_REQUIRED", "documentId est requis");
        }
        assertDocumentAccessible(me, req.documentId());
        if (bl.getDocumentId() != null && !bl.getDocumentId().equals(req.documentId())) {
            throw new BusinessRuleException("BL_DOCUMENT_ALREADY_LINKED",
                    "Ce BL a déjà un document principal. Utilisez PATCH pour le changer.");
        }
        bl.setDocumentId(req.documentId());
        bl.setUpdatedAt(Instant.now());
        bls.save(bl);
        audit.log("STOCK_BL_DOCUMENT_LINKED", "bon_livraison", bl.getId(),
                "{\"documentId\":\"" + req.documentId() + "\"}");
        return Map.of("linked", true, "documentId", req.documentId());
    }

    // ══════════════════════════════════════════════════════════════
    //  HELPERS (copied verbatim from PurchaseOrderService / StockService)
    // ══════════════════════════════════════════════════════════════

    private AppUser currentUser(Authentication auth) {
        return users.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Unknown user: " + auth.getName()));
    }

    private boolean hasPermission(Authentication auth, String perm) {
        String authority = "PERM_" + perm;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    private void requirePermission(Authentication auth, String perm, String action) {
        if (!hasPermission(auth, perm)) {
            throw new BusinessRuleException("FORBIDDEN",
                    "Permission manquante: PERM_" + perm + " — action: " + action);
        }
    }

    private boolean isProjectMember(UUID userId, UUID projectId) {
        Integer cnt = jdbc.queryForObject(
                "select count(*) from project_member where project_id = ? and user_id = ?",
                Integer.class, projectId, userId);
        return cnt != null && cnt > 0;
    }

    private void requireProjectVisible(AppUser me, UUID projectId) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        if (!isProjectMember(me.getId(), projectId)) {
            throw new BusinessRuleException("FORBIDDEN", "Projet inaccessible");
        }
    }

    private void assertLocationAccess(AppUser me, StockLocation loc, String action) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        if (loc.getType() == StockLocationType.SITE) {
            if (loc.getProjectId() == null || !isProjectMember(me.getId(), loc.getProjectId())) {
                throw new BusinessRuleException("FORBIDDEN",
                        "Accès refusé à l'emplacement chantier — action: " + action);
            }
        }
        // CENTRAL: same policy as StockService (any org member with STOCK_MOVE)
    }

    private void assertDraft(BonLivraison bl) {
        if (bl.getStatus() != BonLivraisonStatus.DRAFT) {
            throw new BusinessRuleException("BL_NOT_DRAFT",
                    "Action impossible : le bon de livraison n'est plus en brouillon (statut: "
                            + bl.getStatus() + ")");
        }
    }

    private BonLivraison blOrNotFound(UUID orgId, UUID blId) {
        return bls.findByIdAndOrgId(blId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("BL_NOT_FOUND",
                        "Bon de livraison introuvable"));
    }

    private BonLivraisonLine blLineOrNotFound(UUID orgId, UUID lineId) {
        return blLines.findByIdAndOrgId(lineId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("BL_LINE_NOT_FOUND",
                        "Ligne de bon de livraison introuvable"));
    }

    private String validateSupplier(UUID supplierPartyId, UUID orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select name from party where id = ? and org_id = ? and active = true",
                supplierPartyId, orgId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("BC_SUPPLIER_NOT_FOUND",
                    "Fournisseur introuvable dans cette organisation");
        }
        return (String) rows.get(0).get("name");
    }

    private void assertDocumentAccessible(AppUser me, UUID documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.id, f.project_id
                from document d
                join doc_folder f on f.id = d.folder_id
                where d.id = ? and d.org_id = ? and d.deleted_at is null
                """, documentId, me.getOrgId());
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("DOCUMENT_NOT_FOUND",
                    "Document introuvable dans cette organisation");
        }
        UUID docProject = (UUID) rows.get(0).get("project_id");
        if (!Boolean.TRUE.equals(me.getIsOwner()) && !isProjectMember(me.getId(), docProject)) {
            throw new BusinessRuleException("DOCUMENT_ACCESS_DENIED",
                    "Document rattaché à un projet inaccessible");
        }
    }

    /**
     * Hard cap: SUM(qty_received) per bc_line_id across POSTED BLs ≤ qty_ordered.
     * Throws 409 BL_OVER_RECEIVE with the remaining qty in French.
     *
     * @param excludeLineId the line being edited (its own qty shouldn't count twice)
     */
    private void checkHardCap(UUID orgId, UUID bcLineId, BigDecimal newQty, UUID excludeLineId) {
        // Get qty_ordered from the BC line
        BigDecimal qtyOrdered = jdbc.queryForObject(
                "select qty_ordered from purchase_order_line where id = ? and org_id = ?",
                BigDecimal.class, bcLineId, orgId);
        if (qtyOrdered == null) {
            throw new ResourceNotFoundException("BC_LINE_NOT_FOUND",
                    "Ligne de bon de commande introuvable pour la réception");
        }
        // Get already-received (POSTED BLs, excluding the current line being edited)
        BigDecimal alreadyReceived = blLines.sumPostedReceivedForBcLine(orgId, bcLineId);
        if (alreadyReceived == null) alreadyReceived = BigDecimal.ZERO;
        // For an edit: subtract the current line's qty (it's already counted in alreadyReceived if its BL was POSTED — but we only call checkHardCap when the BL is DRAFT, so the line's qty is NOT yet in the POSTED sum)
        // For a new line: alreadyReceived is correct as-is.

        BigDecimal totalAfter = alreadyReceived.add(newQty);
        if (totalAfter.compareTo(qtyOrdered) > 0) {
            BigDecimal remaining = qtyOrdered.subtract(alreadyReceived);
            throw new BusinessRuleException("BL_OVER_RECEIVE",
                    "Quantité reçue supérieure à la quantité commandée. " +
                    "Commandé: " + qtyOrdered + ", déjà reçu: " + alreadyReceived +
                    ", cette réception: " + newQty + ", restant possible: " + remaining);
        }
    }

    private String projectName(UUID projectId) {
        try { return jdbc.queryForObject("select name from project where id = ?", String.class, projectId); }
        catch (Exception e) { return null; }
    }

    private String bcReference(UUID bcId, UUID orgId) {
        if (bcId == null) return null;
        try { return jdbc.queryForObject("select reference from purchase_order where id = ? and org_id = ?",
                String.class, bcId, orgId); }
        catch (Exception e) { return null; }
    }

    private String supplierName(BonLivraison bl) {
        if (bl.getSupplierPartyId() == null) return null;
        try { return jdbc.queryForObject("select name from party where id = ? and org_id = ?",
                String.class, bl.getSupplierPartyId(), bl.getOrgId()); }
        catch (Exception e) { return null; }
    }

    private List<BlLineResponse> loadLines(BonLivraison bl) {
        return blLines.findByBonLivraisonIdAndOrgIdOrderByCreatedAt(bl.getId(), bl.getOrgId())
                .stream().map(this::toLineResponse).toList();
    }

    private BlLineResponse toLineResponse(BonLivraisonLine l) {
        String itemName = null;
        Optional<StockItem> item = items.findByIdAndOrgId(l.getItemId(), l.getOrgId());
        if (item.isPresent()) itemName = item.get().getName();
        return new BlLineResponse(l.getId(), l.getBonLivraisonId(), l.getBcLineId(), l.getItemId(),
                itemName, l.getQtyReceived(), l.getUom(), l.getNotes(),
                l.getCreatedBy(), l.getCreatedAt());
    }

    private BlLineResponse createLineInternal(BonLivraison bl, CreateBlLineRequest req, AppUser me, Instant now) {
        StockItem item = items.findByIdAndOrgId(req.itemId(), bl.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("BC_LINE_ITEM_NOT_FOUND",
                        "Article introuvable dans cette organisation"));
        if (req.qtyReceived() == null || req.qtyReceived().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("BL_LINE_QTY_INVALID", "qtyReceived doit être > 0");
        }
        String uom = req.uom() != null && !req.uom().isBlank() ? req.uom() : item.getUom();

        // If bcLineId provided, validate it belongs to the BL's BC + hard cap
        if (req.bcLineId() != null) {
            if (bl.getBcId() != null) {
                UUID lineBcId;
                try {
                    lineBcId = jdbc.queryForObject(
                            "select po.id from purchase_order_line pol join purchase_order po on po.id = pol.purchase_order_id where pol.id = ? and pol.org_id = ?",
                            UUID.class, req.bcLineId(), bl.getOrgId());
                } catch (Exception e) { lineBcId = null; }
                if (lineBcId == null || !lineBcId.equals(bl.getBcId())) {
                    throw new BusinessRuleException("BL_LINE_BC_MISMATCH",
                            "La ligne ne référence pas une ligne du bon de commande lié");
                }
            }
            checkHardCap(bl.getOrgId(), req.bcLineId(), req.qtyReceived(), null);
        }

        BonLivraisonLine line = new BonLivraisonLine();
        line.markNew();
        line.setOrgId(bl.getOrgId());
        line.setBonLivraisonId(bl.getId());
        line.setBcLineId(req.bcLineId());
        line.setItemId(req.itemId());
        line.setQtyReceived(req.qtyReceived());
        line.setUom(uom);
        line.setNotes(req.notes());
        line.setCreatedBy(me.getId());
        line.setCreatedAt(now);
        line.setUpdatedAt(now);
        blLines.save(line);

        audit.log("STOCK_BL_LINE_ADDED", "bon_livraison_line", line.getId(),
                "{\"bonLivraisonId\":\"" + bl.getId() + "\""
                + ",\"bcLineId\":" + (req.bcLineId() == null ? "null" : "\"" + req.bcLineId() + "\"")
                + ",\"itemId\":\"" + req.itemId() + "\""
                + ",\"qtyReceived\":" + req.qtyReceived()
                + "}");
        return new BlLineResponse(line.getId(), bl.getId(), req.bcLineId(), req.itemId(),
                item.getName(), line.getQtyReceived(), uom, req.notes(), me.getId(), now);
    }

    private BlResponse toBlResponse(BonLivraison bl, String projectName, String bcReference,
                                      String supplierName, String locationName, String locationType,
                                      List<BlLineResponse> lineList, int lineCountHint) {
        int lineCount = lineCountHint > 0 ? lineCountHint : lineList.size();
        return new BlResponse(bl.getId(), bl.getOrgId(), bl.getProjectId(), projectName,
                bl.getBcId(), bcReference, bl.getSupplierPartyId(), supplierName,
                bl.getStatus().name(), bl.getReference(), bl.getReceivedAt(),
                bl.getLocationId(), locationName, locationType,
                bl.getDocumentId(), bl.getNotes(),
                bl.getCreatedBy(), bl.getCreatedAt(), bl.getUpdatedAt(),
                lineCount, lineList);
    }

    private BonLivraisonStatus parseStatus(String s) {
        if (s == null || s.isBlank()) return null;
        try { return BonLivraisonStatus.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) {
            throw new BadRequestException("INVALID_STATUS",
                    "Statut invalide: " + s + " (valeurs: DRAFT, POSTED, CANCELLED)");
        }
    }

    private static java.time.Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof java.time.Instant i) return i;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (o instanceof java.time.LocalDateTime ldt) return ldt.toInstant(java.time.ZoneOffset.UTC);
        return java.time.Instant.parse(o.toString());
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsonOrNull(String s) {
        return s == null ? "null" : "\"" + esc(s) + "\"";
    }
}
