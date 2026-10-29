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
import tn.hydra.purchase.domain.PurchaseOrder;
import tn.hydra.purchase.domain.PurchaseOrderLine;
import tn.hydra.purchase.domain.PurchaseOrderStatus;
import tn.hydra.purchase.repository.PurchaseOrderLineRepository;
import tn.hydra.purchase.repository.PurchaseOrderRepository;
import tn.hydra.stock.domain.StockItem;
import tn.hydra.stock.repository.StockItemRepository;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ============================================================================
 *  PurchaseOrderService — Bon de Commande (BC) lifecycle.
 * ============================================================================
 *
 *  Mirrors {@code StockService} conventions exactly:
 *    - Two-layer security: {@code @PreAuthorize} (HTTP) + {@code requirePermission}
 *      + {@code requireProjectVisible} (service, IDOR-safe).
 *    - DTOs as static nested records.
 *    - Audit events via {@code EventLogService.log()} with hand-rolled JSON
 *      details (same {@code esc} / {@code jsonOrNull} pattern as StockService).
 *    - JSON errors: {@code BusinessRuleException}→409, {@code ResourceNotFound}
 *      →404, {@code BadRequest}→400, {@code @PreAuthorize}→403.
 *
 *  Part 2.1 scope: BC + lines + confirm/cancel + optional document link.
 *  No BL, no three-way match, no stock_move wiring (deferred to 2.2).
 * ============================================================================
 */
@Service
public class PurchaseOrderService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;
    private final PurchaseOrderRepository pos;
    private final PurchaseOrderLineRepository lines;
    private final StockItemRepository items;

    public PurchaseOrderService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit,
                                PurchaseOrderRepository pos,
                                PurchaseOrderLineRepository lines,
                                StockItemRepository items) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.pos = pos;
        this.lines = lines;
        this.items = items;
    }

    // ══════════════════════════════════════════════════════════════
    //  DTOs (static nested records — matches StockService style)
    // ══════════════════════════════════════════════════════════════

    public record BcLineResponse(UUID id, UUID purchaseOrderId, UUID itemId, String itemName,
                                  BigDecimal qtyOrdered, String uom, BigDecimal unitPrice,
                                  String notes, UUID createdBy, Instant createdAt) {}

    public record BcResponse(UUID id, UUID orgId, UUID projectId, String projectName,
                              UUID supplierPartyId, String supplierName,
                              String status, String reference, LocalDate orderedAt,
                              LocalDate expectedAt, String notes, UUID documentId,
                              UUID createdBy, Instant createdAt, Instant updatedAt,
                              Integer lineCount, List<BcLineResponse> lines) {}

    /** Compact list row — lineCount via single SQL (no N+1); lines omitted. */
    public record BcListRow(UUID id, UUID projectId, String projectName,
                            UUID supplierPartyId, String supplierName,
                            String status, String reference, LocalDate orderedAt,
                            LocalDate expectedAt, UUID createdBy, Instant createdAt,
                            Integer lineCount) {}

    public record BcPage(List<BcListRow> items, long total, int page, int size) {}

    public record CreateBcLineRequest(UUID itemId, BigDecimal qtyOrdered, String uom,
                                      BigDecimal unitPrice, String notes) {}

    public record CreateBcRequest(UUID projectId, UUID supplierPartyId, String reference,
                                   LocalDate orderedAt, LocalDate expectedAt, String notes,
                                   UUID documentId, List<CreateBcLineRequest> lines) {}

    public record UpdateBcRequest(String reference, UUID supplierPartyId, LocalDate orderedAt,
                                  LocalDate expectedAt, String notes, UUID documentId) {}

    public record AddLineRequest(UUID itemId, BigDecimal qtyOrdered, String uom,
                                 BigDecimal unitPrice, String notes) {}

    public record UpdateLineRequest(BigDecimal qtyOrdered, String uom, BigDecimal unitPrice,
                                    String notes) {}

    public record CancelRequest(String reason) {}

    public record LinkDocumentRequest(UUID documentId) {}

    // ══════════════════════════════════════════════════════════════
    //  CREATE — POST /api/purchase-orders
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public BcResponse create(Authentication auth, CreateBcRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "create-bc");
        if (req.projectId() == null) {
            throw new BadRequestException("PROJECT_REQUIRED", "projectId est requis");
        }
        requireProjectVisible(me, req.projectId());

        // Validate supplier (org-scoped party)
        String supplierName = null;
        if (req.supplierPartyId() != null) {
            supplierName = validateSupplier(req.supplierPartyId(), me.getOrgId());
        }

        // Validate document access (if provided)
        if (req.documentId() != null) {
            assertDocumentAccessible(me, req.documentId());
        }

        // Build + persist the BC header
        Instant now = Instant.now();
        PurchaseOrder po = new PurchaseOrder();
        po.markNew();
        po.setOrgId(me.getOrgId());
        po.setProjectId(req.projectId());
        po.setSupplierPartyId(req.supplierPartyId());
        po.setStatus(PurchaseOrderStatus.DRAFT);
        po.setReference(req.reference());
        po.setOrderedAt(req.orderedAt() != null ? req.orderedAt() : LocalDate.now());
        po.setExpectedAt(req.expectedAt());
        po.setNotes(req.notes());
        po.setDocumentId(req.documentId());
        po.setCreatedBy(me.getId());
        po.setCreatedAt(now);
        po.setUpdatedAt(now);
        pos.save(po);

        // Persist lines (validate item + uom)
        List<BcLineResponse> lineResps = new ArrayList<>();
        if (req.lines() != null) {
            for (CreateBcLineRequest lineReq : req.lines()) {
                lineResps.add(createLineInternal(po, lineReq, me, now));
            }
        }

        String projectName = projectName(req.projectId());
        audit.log("STOCK_BC_CREATED", "purchase_order", po.getId(),
                "{\"projectId\":\"" + req.projectId() + "\""
                + ",\"reference\":" + jsonOrNull(po.getReference())
                + ",\"supplierPartyId\":" + (po.getSupplierPartyId() == null ? "null" : "\"" + po.getSupplierPartyId() + "\"")
                + ",\"lineCount\":" + lineResps.size()
                + "}");

        return toBcResponse(po, projectName, supplierName, lineResps, lineResps.size());
    }

    // ══════════════════════════════════════════════════════════════
    //  LIST — GET /api/purchase-orders?projectId=&status=&page=&size=
    // ══════════════════════════════════════════════════════════════

    public BcPage list(Authentication auth, UUID projectId, String statusStr, int page, int size) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_VIEW", "list-bc");
        if (page < 0) page = 0;
        if (size <= 0 || size > 200) size = 50;

        // IDOR-safe project filter: owner sees all; non-owner only their projects
        if (projectId != null) {
            requireProjectVisible(me, projectId);
        }

        PurchaseOrderStatus statusFilter = parseStatus(statusStr); // null = all

        // Build the query — single SQL with subquery for lineCount (no N+1)
        StringBuilder sql = new StringBuilder("""
                select po.id, po.project_id, p.name as project_name,
                       po.supplier_party_id, party.name as supplier_name,
                       po.status::text as status, po.reference, po.ordered_at, po.expected_at,
                       po.created_by, po.created_at,
                       (select count(*) from purchase_order_line l where l.purchase_order_id = po.id) as line_count
                from purchase_order po
                join project p on p.id = po.project_id
                left join party on party.id = po.supplier_party_id
                where po.org_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(me.getOrgId());

        if (projectId != null) {
            sql.append(" and po.project_id = ? ");
            args.add(projectId);
        }
        if (statusFilter != null) {
            sql.append(" and po.status = ?::text ");
            args.add(statusFilter.name());
        }
        // Non-owner: only BCs on projects they can see
        if (!Boolean.TRUE.equals(me.getIsOwner())) {
            sql.append(" and po.project_id in (select pm.project_id from project_member pm where pm.user_id = ?) ");
            args.add(me.getId());
        }
        sql.append(" order by po.ordered_at desc, po.created_at desc limit ? offset ?");
        args.add(size);
        args.add(page * size);

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<BcListRow> items = rows.stream().map(r -> new BcListRow(
                (UUID) r.get("id"),
                (UUID) r.get("project_id"),
                (String) r.get("project_name"),
                (UUID) r.get("supplier_party_id"),
                (String) r.get("supplier_name"),
                (String) r.get("status"),
                (String) r.get("reference"),
                ((java.sql.Date) r.get("ordered_at")).toLocalDate(),
                r.get("expected_at") != null ? ((java.sql.Date) r.get("expected_at")).toLocalDate() : null,
                (UUID) r.get("created_by"),
                toInstant(r.get("created_at")),
                ((Number) r.get("line_count")).intValue()
        )).toList();

        // Total count (for pagination)
        StringBuilder countSql = new StringBuilder(
                "select count(*) from purchase_order po where po.org_id = ? ");
        List<Object> countArgs = new ArrayList<>();
        countArgs.add(me.getOrgId());
        if (projectId != null) {
            countSql.append(" and po.project_id = ? ");
            countArgs.add(projectId);
        }
        if (statusFilter != null) {
            countSql.append(" and po.status = ?::text ");
            countArgs.add(statusFilter.name());
        }
        if (!Boolean.TRUE.equals(me.getIsOwner())) {
            countSql.append(" and po.project_id in (select pm.project_id from project_member pm where pm.user_id = ?) ");
            countArgs.add(me.getId());
        }
        Long total = jdbc.queryForObject(countSql.toString(), Long.class, countArgs.toArray());
        if (total == null) total = 0L;

        return new BcPage(items, total, page, size);
    }

    // ══════════════════════════════════════════════════════════════
    //  DETAIL — GET /api/purchase-orders/{id}
    // ══════════════════════════════════════════════════════════════

    public BcResponse detail(Authentication auth, UUID bcId) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_VIEW", "bc-detail");
        PurchaseOrder po = poOrNotFound(me.getOrgId(), bcId);
        requireProjectVisible(me, po.getProjectId());
        return toBcResponse(po, projectName(po.getProjectId()), supplierName(po), loadLines(po), 0);
    }

    // ══════════════════════════════════════════════════════════════
    //  PATCH (DRAFT only) — reference / supplierPartyId / dates / notes / documentId
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public BcResponse update(Authentication auth, UUID bcId, UpdateBcRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "update-bc");
        PurchaseOrder po = poOrNotFound(me.getOrgId(), bcId);
        requireProjectVisible(me, po.getProjectId());
        assertDraft(po);

        if (req.reference() != null) po.setReference(req.reference().isBlank() ? null : req.reference());
        if (req.supplierPartyId() != null) {
            // Allow clearing by passing a sentinel? We accept any UUID; null stays null.
            validateSupplier(req.supplierPartyId(), me.getOrgId());
            po.setSupplierPartyId(req.supplierPartyId());
        }
        if (req.orderedAt() != null) po.setOrderedAt(req.orderedAt());
        po.setExpectedAt(req.expectedAt()); // nullable, can be cleared
        if (req.notes() != null) po.setNotes(req.notes().isBlank() ? null : req.notes());
        if (req.documentId() != null) {
            assertDocumentAccessible(me, req.documentId());
            po.setDocumentId(req.documentId());
        }
        po.setUpdatedAt(Instant.now());
        pos.save(po);

        audit.log("STOCK_BC_UPDATED", "purchase_order", po.getId(),
                "{\"reference\":" + jsonOrNull(po.getReference())
                + ",\"supplierPartyId\":" + (po.getSupplierPartyId() == null ? "null" : "\"" + po.getSupplierPartyId() + "\"")
                + "}");

        return toBcResponse(po, projectName(po.getProjectId()), supplierName(po), loadLines(po), 0);
    }

    // ══════════════════════════════════════════════════════════════
    //  LINE CRUD (DRAFT only)
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public BcLineResponse addLine(Authentication auth, UUID bcId, AddLineRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "add-bc-line");
        PurchaseOrder po = poOrNotFound(me.getOrgId(), bcId);
        requireProjectVisible(me, po.getProjectId());
        assertDraft(po);
        if (req.qtyOrdered() == null || req.qtyOrdered().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("BC_LINE_QTY_INVALID", "qtyOrdered doit être > 0");
        }
        if (req.uom() == null || req.uom().isBlank()) {
            throw new BadRequestException("BC_LINE_UOM_REQUIRED", "uom est requis");
        }
        BcLineResponse resp = createLineInternal(po, new CreateBcLineRequest(
                req.itemId(), req.qtyOrdered(), req.uom(), req.unitPrice(), req.notes()), me, Instant.now());

        audit.log("STOCK_BC_LINE_ADDED", "purchase_order_line", resp.id(),
                "{\"purchaseOrderId\":\"" + po.getId() + "\""
                + ",\"itemId\":\"" + req.itemId() + "\""
                + ",\"qtyOrdered\":" + req.qtyOrdered()
                + ",\"uom\":" + jsonOrNull(req.uom())
                + "}");
        return resp;
    }

    @Transactional
    public BcLineResponse updateLine(Authentication auth, UUID bcId, UUID lineId, UpdateLineRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "update-bc-line");
        PurchaseOrder po = poOrNotFound(me.getOrgId(), bcId);
        requireProjectVisible(me, po.getProjectId());
        assertDraft(po);
        PurchaseOrderLine line = lineOrNotFound(me.getOrgId(), lineId);
        if (!line.getPurchaseOrderId().equals(bcId)) {
            throw new BusinessRuleException("BC_LINE_MISMATCH",
                    "La ligne n'appartient pas à ce bon de commande");
        }
        if (req.qtyOrdered() != null) {
            if (req.qtyOrdered().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BadRequestException("BC_LINE_QTY_INVALID", "qtyOrdered doit être > 0");
            }
            line.setQtyOrdered(req.qtyOrdered());
        }
        if (req.uom() != null && !req.uom().isBlank()) line.setUom(req.uom());
        if (req.unitPrice() != null) line.setUnitPrice(req.unitPrice());
        if (req.notes() != null) line.setNotes(req.notes().isBlank() ? null : req.notes());
        line.setUpdatedAt(Instant.now());
        lines.save(line);

        audit.log("STOCK_BC_LINE_UPDATED", "purchase_order_line", line.getId(),
                "{\"qtyOrdered\":" + line.getQtyOrdered() + "}");
        return toLineResponse(line);
    }

    @Transactional
    public void removeLine(Authentication auth, UUID bcId, UUID lineId) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "remove-bc-line");
        PurchaseOrder po = poOrNotFound(me.getOrgId(), bcId);
        requireProjectVisible(me, po.getProjectId());
        assertDraft(po);
        PurchaseOrderLine line = lineOrNotFound(me.getOrgId(), lineId);
        if (!line.getPurchaseOrderId().equals(bcId)) {
            throw new BusinessRuleException("BC_LINE_MISMATCH",
                    "La ligne n'appartient pas à ce bon de commande");
        }
        lines.delete(line);
        audit.log("STOCK_BC_LINE_REMOVED", "purchase_order_line", line.getId(),
                "{\"purchaseOrderId\":\"" + po.getId() + "\"}");
    }

    // ══════════════════════════════════════════════════════════════
    //  CONFIRM / CANCEL
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public BcResponse confirm(Authentication auth, UUID bcId) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "confirm-bc");
        PurchaseOrder po = poOrNotFound(me.getOrgId(), bcId);
        requireProjectVisible(me, po.getProjectId());
        assertDraft(po);
        // Must have ≥ 1 line
        long lineCount = lines.countByPurchaseOrder(bcId, me.getOrgId());
        if (lineCount == 0) {
            throw new BusinessRuleException("BC_NO_LINES",
                    "Impossible de confirmer un bon de commande sans lignes");
        }
        po.setStatus(PurchaseOrderStatus.CONFIRMED);
        po.setUpdatedAt(Instant.now());
        pos.save(po);
        audit.log("STOCK_BC_CONFIRMED", "purchase_order", po.getId(),
                "{\"lineCount\":" + lineCount + "}");
        return toBcResponse(po, projectName(po.getProjectId()), supplierName(po), loadLines(po), (int) lineCount);
    }

    @Transactional
    public BcResponse cancel(Authentication auth, UUID bcId, CancelRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "cancel-bc");
        PurchaseOrder po = poOrNotFound(me.getOrgId(), bcId);
        requireProjectVisible(me, po.getProjectId());
        // Cancel allowed only from DRAFT or CONFIRMED
        if (po.getStatus() != PurchaseOrderStatus.DRAFT && po.getStatus() != PurchaseOrderStatus.CONFIRMED) {
            throw new BusinessRuleException("BC_INVALID_STATUS",
                    "Annulation impossible depuis le statut " + po.getStatus());
        }
        String reason = req != null && req.reason() != null ? req.reason().trim() : null;
        po.setStatus(PurchaseOrderStatus.CANCELLED);
        po.setUpdatedAt(Instant.now());
        pos.save(po);
        audit.log("STOCK_BC_CANCELLED", "purchase_order", po.getId(),
                "{\"previousStatus\":\"" + (po.getStatus() == PurchaseOrderStatus.CANCELLED ? "DRAFT/CONFIRMED" : po.getStatus()) + "\""
                + ",\"reason\":" + jsonOrNull(reason) + "}");
        return toBcResponse(po, projectName(po.getProjectId()), supplierName(po), loadLines(po), 0);
    }

    // ══════════════════════════════════════════════════════════════
    //  DOCUMENT LINK — POST /api/purchase-orders/{id}/documents
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> linkDocument(Authentication auth, UUID bcId, LinkDocumentRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "link-bc-document");
        PurchaseOrder po = poOrNotFound(me.getOrgId(), bcId);
        requireProjectVisible(me, po.getProjectId());
        if (req.documentId() == null) {
            throw new BadRequestException("DOCUMENT_REQUIRED", "documentId est requis");
        }
        assertDocumentAccessible(me, req.documentId());
        // If the BC already has a primary document, refuse (use PATCH to change it).
        if (po.getDocumentId() != null && !po.getDocumentId().equals(req.documentId())) {
            throw new BusinessRuleException("BC_DOCUMENT_ALREADY_LINKED",
                    "Ce bon de commande a déjà un document principal. Utilisez PATCH pour le changer.");
        }
        po.setDocumentId(req.documentId());
        po.setUpdatedAt(Instant.now());
        pos.save(po);
        audit.log("STOCK_BC_DOCUMENT_LINKED", "purchase_order", po.getId(),
                "{\"documentId\":\"" + req.documentId() + "\"}");
        return Map.of("linked", true, "documentId", req.documentId());
    }

    // ══════════════════════════════════════════════════════════════
    //  HELPERS (copied verbatim from StockService)
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

    private void assertDraft(PurchaseOrder po) {
        if (po.getStatus() != PurchaseOrderStatus.DRAFT) {
            throw new BusinessRuleException("BC_NOT_DRAFT",
                    "Action impossible : le bon de commande n'est plus en brouillon (statut: "
                            + po.getStatus() + ")");
        }
    }

    private PurchaseOrder poOrNotFound(UUID orgId, UUID bcId) {
        return pos.findByIdAndOrgId(bcId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("BC_NOT_FOUND",
                        "Bon de commande introuvable"));
    }

    private PurchaseOrderLine lineOrNotFound(UUID orgId, UUID lineId) {
        return lines.findByIdAndOrgId(lineId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("BC_LINE_NOT_FOUND",
                        "Ligne de bon de commande introuvable"));
    }

    private String validateSupplier(UUID supplierPartyId, UUID orgId) {
        // Org-scoped party lookup (party is org-wide, not project-scoped — V20)
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select name from party where id = ? and org_id = ? and active = true",
                supplierPartyId, orgId);
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("BC_SUPPLIER_NOT_FOUND",
                    "Fournisseur introuvable dans cette organisation");
        }
        return (String) rows.get(0).get("name");
    }

    /** Document must exist in org and belong to a project the caller can access. */
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

    private String projectName(UUID projectId) {
        try {
            return jdbc.queryForObject(
                    "select name from project where id = ?", String.class, projectId);
        } catch (Exception e) {
            return null;
        }
    }

    private String supplierName(PurchaseOrder po) {
        if (po.getSupplierPartyId() == null) return null;
        try {
            return jdbc.queryForObject(
                    "select name from party where id = ? and org_id = ?",
                    String.class, po.getSupplierPartyId(), po.getOrgId());
        } catch (Exception e) {
            return null;
        }
    }

    private List<BcLineResponse> loadLines(PurchaseOrder po) {
        return lines.findByPurchaseOrderIdAndOrgIdOrderByCreatedAt(po.getId(), po.getOrgId())
                .stream().map(this::toLineResponse).toList();
    }

    private BcLineResponse toLineResponse(PurchaseOrderLine l) {
        String itemName = null;
        Optional<StockItem> item = items.findByIdAndOrgId(l.getItemId(), l.getOrgId());
        if (item.isPresent()) itemName = item.get().getName();
        return new BcLineResponse(l.getId(), l.getPurchaseOrderId(), l.getItemId(), itemName,
                l.getQtyOrdered(), l.getUom(), l.getUnitPrice(), l.getNotes(),
                l.getCreatedBy(), l.getCreatedAt());
    }

    private BcLineResponse createLineInternal(PurchaseOrder po, CreateBcLineRequest req,
                                                AppUser me, Instant now) {
        // Validate item exists in org
        StockItem item = items.findByIdAndOrgId(req.itemId(), po.getOrgId())
                .orElseThrow(() -> new ResourceNotFoundException("BC_LINE_ITEM_NOT_FOUND",
                        "Article introuvable dans cette organisation"));
        if (req.qtyOrdered() == null || req.qtyOrdered().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("BC_LINE_QTY_INVALID", "qtyOrdered doit être > 0");
        }
        String uom = req.uom() != null && !req.uom().isBlank() ? req.uom() : item.getUom();
        PurchaseOrderLine line = new PurchaseOrderLine();
        line.markNew();
        line.setOrgId(po.getOrgId());
        line.setPurchaseOrderId(po.getId());
        line.setItemId(req.itemId());
        line.setQtyOrdered(req.qtyOrdered());
        line.setUom(uom);
        line.setUnitPrice(req.unitPrice());
        line.setNotes(req.notes());
        line.setCreatedBy(me.getId());
        line.setCreatedAt(now);
        line.setUpdatedAt(now);
        lines.save(line);
        return new BcLineResponse(line.getId(), po.getId(), req.itemId(), item.getName(),
                line.getQtyOrdered(), uom, req.unitPrice(), req.notes(), me.getId(), now);
    }

    private BcResponse toBcResponse(PurchaseOrder po, String projectName, String supplierName,
                                     List<BcLineResponse> lineList, int lineCountHint) {
        int lineCount = lineCountHint > 0 ? lineCountHint : lineList.size();
        return new BcResponse(po.getId(), po.getOrgId(), po.getProjectId(), projectName,
                po.getSupplierPartyId(), supplierName,
                po.getStatus().name(), po.getReference(), po.getOrderedAt(),
                po.getExpectedAt(), po.getNotes(), po.getDocumentId(),
                po.getCreatedBy(), po.getCreatedAt(), po.getUpdatedAt(),
                lineCount, lineList);
    }

    private PurchaseOrderStatus parseStatus(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return PurchaseOrderStatus.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("INVALID_STATUS",
                    "Statut invalide: " + s + " (valeurs: DRAFT, CONFIRMED, CANCELLED, CLOSED)");
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
