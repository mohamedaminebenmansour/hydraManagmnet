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
import tn.hydra.purchase.domain.Facture;
import tn.hydra.purchase.domain.FactureLine;
import tn.hydra.purchase.domain.FactureStatus;
import tn.hydra.purchase.domain.PurchaseOrder;
import tn.hydra.purchase.domain.PurchaseOrderStatus;
import tn.hydra.purchase.repository.BonLivraisonRepository;
import tn.hydra.purchase.repository.FactureLineRepository;
import tn.hydra.purchase.repository.FactureRepository;
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
 *  FactureService — Facture (supplier invoice) lifecycle.
 * ============================================================================
 *
 *  Mirrors PurchaseOrderService / BonLivraisonService conventions:
 *    - DTOs as static nested records.
 *    - Two-layer security: @PreAuthorize (HTTP) + requirePermission +
 *      requireProjectVisible (service, IDOR-safe).
 *    - Hand-rolled JSON audit (esc / jsonOrNull helpers).
 *
 *  Rules (Part 3.1):
 *    DRAFT → validate() → VALIDATED (requires ≥1 line; lines frozen)
 *    DRAFT → cancel() → CANCELLED
 *    VALIDATED → cancel() → CANCELLED (reason stored in audit)
 *    Free facture (bc_id + bl_id null) allowed.
 *    Supplier mismatch (facture vs BC, both set) → 409 FACTURE_SUPPLIER_MISMATCH.
 *    BL/BC mismatch (BL's bc_id ≠ facture's bc_id, both set) → 409 FACTURE_BL_BC_MISMATCH.
 * ============================================================================
 */
@Service
public class FactureService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;
    private final FactureRepository factures;
    private final FactureLineRepository facLines;
    private final PurchaseOrderRepository pos;
    private final BonLivraisonRepository bls;
    private final StockItemRepository items;

    public FactureService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit,
                          FactureRepository factures, FactureLineRepository facLines,
                          PurchaseOrderRepository pos, BonLivraisonRepository bls,
                          StockItemRepository items) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.factures = factures;
        this.facLines = facLines;
        this.pos = pos;
        this.bls = bls;
        this.items = items;
    }

    // ══════════════════════════════════════════════════════════════
    //  DTOs
    // ══════════════════════════════════════════════════════════════

    public record FactureLineResponse(UUID id, UUID factureId, UUID itemId, String itemName,
                                       UUID bcLineId, UUID blLineId, String description,
                                       BigDecimal qty, String uom, BigDecimal unitPrice, BigDecimal lineHt,
                                       String notes, UUID createdBy, Instant createdAt) {}

    public record FactureResponse(UUID id, UUID orgId, UUID projectId, String projectName,
                                   UUID supplierPartyId, String supplierName,
                                   UUID bcId, String bcReference, UUID blId, String blReference,
                                   String status, String reference, LocalDate invoiceDate, LocalDate dueDate,
                                   BigDecimal amountHt, BigDecimal amountTva, BigDecimal amountTtc,
                                   UUID documentId, String notes,
                                   UUID createdBy, Instant createdAt, Instant updatedAt,
                                   Integer lineCount, List<FactureLineResponse> lines) {}

    public record FactureListRow(UUID id, UUID projectId, String projectName,
                                  UUID supplierPartyId, String supplierName,
                                  UUID bcId, String bcReference, UUID blId,
                                  String status, String reference, LocalDate invoiceDate,
                                  UUID createdBy, Instant createdAt, Integer lineCount) {}

    public record FacturePage(List<FactureListRow> items, long total, int page, int size) {}

    public record CreateFactureLineRequest(UUID itemId, UUID bcLineId, UUID blLineId,
                                            String description, BigDecimal qty, String uom,
                                            BigDecimal unitPrice, BigDecimal lineHt, String notes) {}

    public record CreateFactureRequest(UUID projectId, UUID supplierPartyId, UUID bcId, UUID blId,
                                        String reference, LocalDate invoiceDate, LocalDate dueDate,
                                        BigDecimal amountHt, BigDecimal amountTva, BigDecimal amountTtc,
                                        UUID documentId, String notes,
                                        List<CreateFactureLineRequest> lines) {}

    public record UpdateFactureRequest(String reference, UUID supplierPartyId, LocalDate invoiceDate,
                                        LocalDate dueDate, BigDecimal amountHt, BigDecimal amountTva,
                                        BigDecimal amountTtc, UUID documentId, String notes) {}

    public record UpdateFactureLineRequest(BigDecimal qty, String uom, BigDecimal unitPrice,
                                            BigDecimal lineHt, String notes) {}

    public record CancelRequest(String reason) {}

    public record LinkDocumentRequest(UUID documentId) {}

    // ══════════════════════════════════════════════════════════════
    //  CREATE
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public FactureResponse create(Authentication auth, CreateFactureRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "create-facture");
        if (req.projectId() == null) throw new BadRequestException("PROJECT_REQUIRED", "projectId est requis");
        requireProjectVisible(me, req.projectId());

        // Validate BC (if set) — same project
        String bcReference = null;
        UUID bcSupplier = null;
        if (req.bcId() != null) {
            PurchaseOrder po = pos.findByIdAndOrgId(req.bcId(), me.getOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("BC_NOT_FOUND", "Bon de commande introuvable"));
            if (!po.getProjectId().equals(req.projectId())) {
                throw new BusinessRuleException("FACTURE_PROJECT_MISMATCH",
                        "Le bon de commande n'appartient pas à ce projet");
            }
            bcReference = po.getReference();
            bcSupplier = po.getSupplierPartyId();
        }

        // Validate BL (if set) — same project + if BC also set, BL must reference same BC
        String blReference = null;
        if (req.blId() != null) {
            BonLivraison bl = bls.findByIdAndOrgId(req.blId(), me.getOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("BL_NOT_FOUND", "Bon de livraison introuvable"));
            if (!bl.getProjectId().equals(req.projectId())) {
                throw new BusinessRuleException("FACTURE_PROJECT_MISMATCH",
                        "Le bon de livraison n'appartient pas à ce projet");
            }
            blReference = bl.getReference();
            // If BC also set, BL must reference the same BC
            if (req.bcId() != null && bl.getBcId() != null && !bl.getBcId().equals(req.bcId())) {
                throw new BusinessRuleException("FACTURE_BL_BC_MISMATCH",
                        "Le bon de livraison ne référence pas le même bon de commande");
            }
        }

        // Supplier consistency: if both facture supplier AND BC supplier set and differ → 409
        if (req.supplierPartyId() != null && bcSupplier != null && !req.supplierPartyId().equals(bcSupplier)) {
            throw new BusinessRuleException("FACTURE_SUPPLIER_MISMATCH",
                    "Le fournisseur de la facture ne correspond pas à celui du bon de commande");
        }

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
        Facture f = new Facture();
        f.markNew();
        f.setOrgId(me.getOrgId());
        f.setProjectId(req.projectId());
        f.setSupplierPartyId(req.supplierPartyId());
        f.setBcId(req.bcId());
        f.setBlId(req.blId());
        f.setStatus(FactureStatus.DRAFT);
        f.setReference(req.reference());
        f.setInvoiceDate(req.invoiceDate() != null ? req.invoiceDate() : LocalDate.now());
        f.setDueDate(req.dueDate());
        f.setAmountHt(req.amountHt());
        f.setAmountTva(req.amountTva());
        f.setAmountTtc(req.amountTtc());
        f.setDocumentId(req.documentId());
        f.setNotes(req.notes());
        f.setCreatedBy(me.getId());
        f.setCreatedAt(now);
        f.setUpdatedAt(now);
        factures.save(f);

        List<FactureLineResponse> lineResps = new ArrayList<>();
        if (req.lines() != null) {
            for (CreateFactureLineRequest lineReq : req.lines()) {
                lineResps.add(createLineInternal(f, lineReq, me, now));
            }
        }

        audit.log("STOCK_FACTURE_CREATED", "facture", f.getId(),
                "{\"projectId\":\"" + f.getProjectId() + "\""
                + ",\"reference\":" + jsonOrNull(f.getReference())
                + ",\"bcId\":" + (f.getBcId() == null ? "null" : "\"" + f.getBcId() + "\"")
                + ",\"blId\":" + (f.getBlId() == null ? "null" : "\"" + f.getBlId() + "\"")
                + ",\"lineCount\":" + lineResps.size()
                + "}");

        return toFactureResponse(f, projectName(f.getProjectId()), bcReference, blReference,
                supplierName, lineResps, lineResps.size());
    }

    // ══════════════════════════════════════════════════════════════
    //  LIST
    // ══════════════════════════════════════════════════════════════

    public FacturePage list(Authentication auth, UUID projectId, String statusStr, int page, int size) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_VIEW", "list-facture");
        if (page < 0) page = 0;
        if (size <= 0 || size > 200) size = 50;
        if (projectId != null) requireProjectVisible(me, projectId);

        FactureStatus statusFilter = parseStatus(statusStr);

        StringBuilder sql = new StringBuilder("""
                select f.id, f.project_id, p.name as project_name,
                       f.supplier_party_id, party.name as supplier_name,
                       f.bc_id, po.reference as bc_reference, f.bl_id,
                       f.status::text as status, f.reference, f.invoice_date,
                       f.created_by, f.created_at,
                       (select count(*) from facture_line fl where fl.facture_id = f.id) as line_count
                from facture f
                join project p on p.id = f.project_id
                left join party on party.id = f.supplier_party_id
                left join purchase_order po on po.id = f.bc_id
                where f.org_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(me.getOrgId());
        if (projectId != null) { sql.append(" and f.project_id = ? "); args.add(projectId); }
        if (statusFilter != null) { sql.append(" and f.status = ?::text "); args.add(statusFilter.name()); }
        if (!Boolean.TRUE.equals(me.getIsOwner())) {
            sql.append(" and f.project_id in (select pm.project_id from project_member pm where pm.user_id = ?) ");
            args.add(me.getId());
        }
        sql.append(" order by f.invoice_date desc, f.created_at desc limit ? offset ?");
        args.add(size); args.add(page * size);

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        List<FactureListRow> items = rows.stream().map(r -> new FactureListRow(
                (UUID) r.get("id"),
                (UUID) r.get("project_id"),
                (String) r.get("project_name"),
                (UUID) r.get("supplier_party_id"),
                (String) r.get("supplier_name"),
                (UUID) r.get("bc_id"),
                (String) r.get("bc_reference"),
                (UUID) r.get("bl_id"),
                (String) r.get("status"),
                (String) r.get("reference"),
                ((java.sql.Date) r.get("invoice_date")).toLocalDate(),
                (UUID) r.get("created_by"),
                toInstant(r.get("created_at")),
                ((Number) r.get("line_count")).intValue()
        )).toList();

        StringBuilder csql = new StringBuilder("select count(*) from facture f where f.org_id = ? ");
        List<Object> cargs = new ArrayList<>(); cargs.add(me.getOrgId());
        if (projectId != null) { csql.append(" and f.project_id = ? "); cargs.add(projectId); }
        if (statusFilter != null) { csql.append(" and f.status = ?::text "); cargs.add(statusFilter.name()); }
        if (!Boolean.TRUE.equals(me.getIsOwner())) {
            csql.append(" and f.project_id in (select pm.project_id from project_member pm where pm.user_id = ?) ");
            cargs.add(me.getId());
        }
        Long total = jdbc.queryForObject(csql.toString(), Long.class, cargs.toArray());
        if (total == null) total = 0L;

        return new FacturePage(items, total, page, size);
    }

    // ══════════════════════════════════════════════════════════════
    //  DETAIL
    // ══════════════════════════════════════════════════════════════

    public FactureResponse detail(Authentication auth, UUID factureId) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_VIEW", "facture-detail");
        Facture f = factureOrNotFound(me.getOrgId(), factureId);
        requireProjectVisible(me, f.getProjectId());
        return toFactureResponse(f, projectName(f.getProjectId()),
                bcReference(f.getBcId(), me.getOrgId()), blReference(f.getBlId(), me.getOrgId()),
                supplierName(f), loadLines(f), 0);
    }

    // ══════════════════════════════════════════════════════════════
    //  PATCH (DRAFT only)
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public FactureResponse update(Authentication auth, UUID factureId, UpdateFactureRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "update-facture");
        Facture f = factureOrNotFound(me.getOrgId(), factureId);
        requireProjectVisible(me, f.getProjectId());
        assertDraft(f);

        if (req.reference() != null) f.setReference(req.reference().isBlank() ? null : req.reference());
        if (req.supplierPartyId() != null) {
            validateSupplier(req.supplierPartyId(), me.getOrgId());
            f.setSupplierPartyId(req.supplierPartyId());
        }
        if (req.invoiceDate() != null) f.setInvoiceDate(req.invoiceDate());
        f.setDueDate(req.dueDate()); // nullable
        if (req.amountHt() != null) f.setAmountHt(req.amountHt());
        if (req.amountTva() != null) f.setAmountTva(req.amountTva());
        if (req.amountTtc() != null) f.setAmountTtc(req.amountTtc());
        if (req.notes() != null) f.setNotes(req.notes().isBlank() ? null : req.notes());
        if (req.documentId() != null) {
            assertDocumentAccessible(me, req.documentId());
            f.setDocumentId(req.documentId());
        }
        f.setUpdatedAt(Instant.now());
        factures.save(f);

        audit.log("STOCK_FACTURE_UPDATED", "facture", f.getId(),
                "{\"reference\":" + jsonOrNull(f.getReference()) + "}");

        return toFactureResponse(f, projectName(f.getProjectId()),
                bcReference(f.getBcId(), me.getOrgId()), blReference(f.getBlId(), me.getOrgId()),
                supplierName(f), loadLines(f), 0);
    }

    // ══════════════════════════════════════════════════════════════
    //  LINE CRUD (DRAFT only)
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public FactureLineResponse addLine(Authentication auth, UUID factureId, CreateFactureLineRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "add-facture-line");
        Facture f = factureOrNotFound(me.getOrgId(), factureId);
        requireProjectVisible(me, f.getProjectId());
        assertDraft(f);
        if (req.description() == null || req.description().isBlank()) {
            throw new BadRequestException("FACTURE_LINE_DESC_REQUIRED", "description est requise");
        }
        if (req.qty() == null || req.qty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("FACTURE_LINE_QTY_INVALID", "qty doit être > 0");
        }
        return createLineInternal(f, req, me, Instant.now());
    }

    @Transactional
    public FactureLineResponse updateLine(Authentication auth, UUID factureId, UUID lineId, UpdateFactureLineRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "update-facture-line");
        Facture f = factureOrNotFound(me.getOrgId(), factureId);
        requireProjectVisible(me, f.getProjectId());
        assertDraft(f);
        FactureLine line = lineOrNotFound(me.getOrgId(), lineId);
        if (!line.getFactureId().equals(factureId)) {
            throw new BusinessRuleException("FACTURE_LINE_MISMATCH", "La ligne n'appartient pas à cette facture");
        }
        if (req.qty() != null) {
            if (req.qty().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BadRequestException("FACTURE_LINE_QTY_INVALID", "qty doit être > 0");
            }
            line.setQty(req.qty());
        }
        if (req.uom() != null) line.setUom(req.uom().isBlank() ? null : req.uom());
        if (req.unitPrice() != null) line.setUnitPrice(req.unitPrice());
        if (req.lineHt() != null) line.setLineHt(req.lineHt());
        if (req.notes() != null) line.setNotes(req.notes().isBlank() ? null : req.notes());
        line.setUpdatedAt(Instant.now());
        facLines.save(line);
        audit.log("STOCK_FACTURE_LINE_UPDATED", "facture_line", line.getId(),
                "{\"qty\":" + line.getQty() + "}");
        return toLineResponse(line);
    }

    @Transactional
    public void removeLine(Authentication auth, UUID factureId, UUID lineId) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "remove-facture-line");
        Facture f = factureOrNotFound(me.getOrgId(), factureId);
        requireProjectVisible(me, f.getProjectId());
        assertDraft(f);
        FactureLine line = lineOrNotFound(me.getOrgId(), lineId);
        if (!line.getFactureId().equals(factureId)) {
            throw new BusinessRuleException("FACTURE_LINE_MISMATCH", "La ligne n'appartient pas à cette facture");
        }
        facLines.delete(line);
        audit.log("STOCK_FACTURE_LINE_REMOVED", "facture_line", line.getId(),
                "{\"factureId\":\"" + f.getId() + "\"}");
    }

    // ══════════════════════════════════════════════════════════════
    //  VALIDATE / CANCEL
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public FactureResponse validate(Authentication auth, UUID factureId) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "validate-facture");
        Facture f = factureOrNotFound(me.getOrgId(), factureId);
        requireProjectVisible(me, f.getProjectId());
        assertDraft(f);
        long lineCount = facLines.countByFactureIdAndOrgId(factureId, me.getOrgId());
        if (lineCount == 0) {
            throw new BusinessRuleException("FACTURE_NO_LINES",
                    "Impossible de valider une facture sans lignes");
        }
        f.setStatus(FactureStatus.VALIDATED);
        f.setUpdatedAt(Instant.now());
        factures.save(f);
        audit.log("STOCK_FACTURE_VALIDATED", "facture", f.getId(),
                "{\"lineCount\":" + lineCount + "}");
        return toFactureResponse(f, projectName(f.getProjectId()),
                bcReference(f.getBcId(), me.getOrgId()), blReference(f.getBlId(), me.getOrgId()),
                supplierName(f), loadLines(f), (int) lineCount);
    }

    @Transactional
    public FactureResponse cancel(Authentication auth, UUID factureId, CancelRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "cancel-facture");
        Facture f = factureOrNotFound(me.getOrgId(), factureId);
        requireProjectVisible(me, f.getProjectId());
        // Cancel allowed from DRAFT or VALIDATED
        if (f.getStatus() != FactureStatus.DRAFT && f.getStatus() != FactureStatus.VALIDATED) {
            throw new BusinessRuleException("FACTURE_INVALID_STATUS",
                    "Annulation impossible depuis le statut " + f.getStatus());
        }
        String reason = req != null && req.reason() != null ? req.reason().trim() : null;
        String previousStatus = f.getStatus().name();
        f.setStatus(FactureStatus.CANCELLED);
        f.setUpdatedAt(Instant.now());
        factures.save(f);
        audit.log("STOCK_FACTURE_CANCELLED", "facture", f.getId(),
                "{\"previousStatus\":\"" + previousStatus + "\""
                + ",\"reason\":" + jsonOrNull(reason) + "}");
        return toFactureResponse(f, projectName(f.getProjectId()),
                bcReference(f.getBcId(), me.getOrgId()), blReference(f.getBlId(), me.getOrgId()),
                supplierName(f), loadLines(f), 0);
    }

    // ══════════════════════════════════════════════════════════════
    //  DOCUMENT LINK
    // ══════════════════════════════════════════════════════════════

    @Transactional
    public Map<String, Object> linkDocument(Authentication auth, UUID factureId, LinkDocumentRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "link-facture-document");
        Facture f = factureOrNotFound(me.getOrgId(), factureId);
        requireProjectVisible(me, f.getProjectId());
        if (req.documentId() == null) {
            throw new BadRequestException("DOCUMENT_REQUIRED", "documentId est requis");
        }
        assertDocumentAccessible(me, req.documentId());
        if (f.getDocumentId() != null && !f.getDocumentId().equals(req.documentId())) {
            throw new BusinessRuleException("FACTURE_DOCUMENT_ALREADY_LINKED",
                    "Cette facture a déjà un document principal. Utilisez PATCH pour le changer.");
        }
        f.setDocumentId(req.documentId());
        f.setUpdatedAt(Instant.now());
        factures.save(f);
        audit.log("STOCK_FACTURE_DOCUMENT_LINKED", "facture", f.getId(),
                "{\"documentId\":\"" + req.documentId() + "\"}");
        return Map.of("linked", true, "documentId", req.documentId());
    }

    // ══════════════════════════════════════════════════════════════
    //  HELPERS (copied verbatim from PurchaseOrderService)
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

    private void assertDraft(Facture f) {
        if (f.getStatus() != FactureStatus.DRAFT) {
            throw new BusinessRuleException("FACTURE_NOT_DRAFT",
                    "Action impossible : la facture n'est plus en brouillon (statut: "
                            + f.getStatus() + ")");
        }
    }

    private Facture factureOrNotFound(UUID orgId, UUID factureId) {
        return factures.findByIdAndOrgId(factureId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("FACTURE_NOT_FOUND",
                        "Facture introuvable"));
    }

    private FactureLine lineOrNotFound(UUID orgId, UUID lineId) {
        return facLines.findByIdAndOrgId(lineId, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("FACTURE_LINE_NOT_FOUND",
                        "Ligne de facture introuvable"));
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

    private String blReference(UUID blId, UUID orgId) {
        if (blId == null) return null;
        try { return jdbc.queryForObject("select reference from bon_livraison where id = ? and org_id = ?",
                String.class, blId, orgId); }
        catch (Exception e) { return null; }
    }

    private String supplierName(Facture f) {
        if (f.getSupplierPartyId() == null) return null;
        try { return jdbc.queryForObject("select name from party where id = ? and org_id = ?",
                String.class, f.getSupplierPartyId(), f.getOrgId()); }
        catch (Exception e) { return null; }
    }

    private List<FactureLineResponse> loadLines(Facture f) {
        return facLines.findByFactureIdAndOrgIdOrderByCreatedAt(f.getId(), f.getOrgId())
                .stream().map(this::toLineResponse).toList();
    }

    private FactureLineResponse toLineResponse(FactureLine l) {
        String itemName = null;
        if (l.getItemId() != null) {
            Optional<StockItem> item = items.findByIdAndOrgId(l.getItemId(), l.getOrgId());
            if (item.isPresent()) itemName = item.get().getName();
        }
        return new FactureLineResponse(l.getId(), l.getFactureId(), l.getItemId(), itemName,
                l.getBcLineId(), l.getBlLineId(), l.getDescription(),
                l.getQty(), l.getUom(), l.getUnitPrice(), l.getLineHt(),
                l.getNotes(), l.getCreatedBy(), l.getCreatedAt());
    }

    private FactureLineResponse createLineInternal(Facture f, CreateFactureLineRequest req, AppUser me, Instant now) {
        if (req.qty() == null || req.qty().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("FACTURE_LINE_QTY_INVALID", "qty doit être > 0");
        }
        if (req.description() == null || req.description().isBlank()) {
            throw new BadRequestException("FACTURE_LINE_DESC_REQUIRED", "description est requise");
        }
        String itemName = null;
        if (req.itemId() != null) {
            StockItem item = items.findByIdAndOrgId(req.itemId(), f.getOrgId())
                    .orElseThrow(() -> new ResourceNotFoundException("BC_LINE_ITEM_NOT_FOUND",
                            "Article introuvable dans cette organisation"));
            itemName = item.getName();
        }
        FactureLine line = new FactureLine();
        line.markNew();
        line.setOrgId(f.getOrgId());
        line.setFactureId(f.getId());
        line.setItemId(req.itemId());
        line.setBcLineId(req.bcLineId());
        line.setBlLineId(req.blLineId());
        line.setDescription(req.description().trim());
        line.setQty(req.qty());
        line.setUom(req.uom());
        line.setUnitPrice(req.unitPrice());
        line.setLineHt(req.lineHt());
        line.setNotes(req.notes());
        line.setCreatedBy(me.getId());
        line.setCreatedAt(now);
        line.setUpdatedAt(now);
        facLines.save(line);
        return new FactureLineResponse(line.getId(), f.getId(), req.itemId(), itemName,
                req.bcLineId(), req.blLineId(), line.getDescription(),
                line.getQty(), req.uom(), req.unitPrice(), req.lineHt(),
                req.notes(), me.getId(), now);
    }

    private FactureResponse toFactureResponse(Facture f, String projectName, String bcReference,
                                                String blReference, String supplierName,
                                                List<FactureLineResponse> lineList, int lineCountHint) {
        int lineCount = lineCountHint > 0 ? lineCountHint : lineList.size();
        return new FactureResponse(f.getId(), f.getOrgId(), f.getProjectId(), projectName,
                f.getSupplierPartyId(), supplierName,
                f.getBcId(), bcReference, f.getBlId(), blReference,
                f.getStatus().name(), f.getReference(), f.getInvoiceDate(), f.getDueDate(),
                f.getAmountHt(), f.getAmountTva(), f.getAmountTtc(),
                f.getDocumentId(), f.getNotes(),
                f.getCreatedBy(), f.getCreatedAt(), f.getUpdatedAt(),
                lineCount, lineList);
    }

    private FactureStatus parseStatus(String s) {
        if (s == null || s.isBlank()) return null;
        try { return FactureStatus.valueOf(s.trim().toUpperCase()); }
        catch (IllegalArgumentException e) {
            throw new BadRequestException("INVALID_STATUS",
                    "Statut invalide: " + s + " (valeurs: DRAFT, VALIDATED, CANCELLED)");
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
