package tn.hydra.cash;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Cash REST endpoints — pots + movements + balance + submit-payment.
 *
 * Permission model (PERM_* authorities — seeded in V22__cash_permissions.sql):
 *   PERM_CASH_READ   — list/get pots, balances, movements (also party reads)
 *   PERM_CASH_DRAFT  — create/update DRAFT movement
 *   PERM_CASH_SUBMIT — submit-payment (start PAYMENT_APPROVAL workflow)
 *   PERM_CASH_POST   — POST a DRAFT movement (IN, or owner force on OUT)
 *   PERM_CASH_VOID   — VOID a POSTED movement
 *   PERM_PARTY_MANAGE — create/update parties (sous-traitants / fournisseurs)
 *
 * Owner (is_owner=true) gets every PERM_* automatically via
 * HydraUserDetailsService.allPermissions().
 *
 * Base paths:
 *   /api/cash/pots/...        — pots (caisse)
 *   /api/cash/movements/...   — movements (money events)
 */
@RestController
@RequestMapping("/api/cash")
public class CashController {

    private final CashService svc;
    private final PaymentFacade paymentFacade;

    public CashController(CashService svc, PaymentFacade paymentFacade) {
        this.svc = svc;
        this.paymentFacade = paymentFacade;
    }

    // ══════════════════════════════════════════════════════════════
    //  POTS
    // ══════════════════════════════════════════════════════════════

    /** GET /api/cash/pots — list all pots visible to caller (COMPANY + accessible PROJECT). */
    @GetMapping("/pots")
    @PreAuthorize("hasAuthority('PERM_CASH_READ')")
    public ResponseEntity<List<CashService.PotResponse>> listPots(Authentication auth) {
        return ResponseEntity.ok(svc.listPots(auth));
    }

    /** POST /api/cash/pots/ensure-company — idempotent ensure of "Caisse centrale". */
    @PostMapping("/pots/ensure-company")
    @PreAuthorize("hasAuthority('PERM_CASH_DRAFT')")
    public ResponseEntity<CashService.PotResponse> ensureCompanyPot(Authentication auth) {
        return ResponseEntity.ok(svc.ensureCompanyPot(auth));
    }

    /** POST /api/cash/pots/ensure-project/{projectId} — idempotent ensure of project pot. */
    @PostMapping("/pots/ensure-project/{projectId}")
    @PreAuthorize("hasAuthority('PERM_CASH_DRAFT')")
    public ResponseEntity<CashService.PotResponse> ensureProjectPot(
            @PathVariable UUID projectId, Authentication auth) {
        return ResponseEntity.ok(svc.ensureProjectPot(auth, projectId));
    }

    /** GET /api/cash/pots/{id}/balance — computed balance for the pot. */
    @GetMapping("/pots/{id}/balance")
    @PreAuthorize("hasAuthority('PERM_CASH_READ')")
    public ResponseEntity<CashService.BalanceResponse> balance(
            @PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok(svc.balance(auth, id));
    }

    /**
     * GET /api/cash/pots/{id}/statement?from=&to=&projectId= — Excel-like
     * "état de caisse" for one pot: ordered lines (POSTED + DRAFT/PENDING),
     * each carrying date / bon / tiers / nature / désignation / mode / réf /
     * facture / montant / statut / projet.
     *
     * Filters: from / to (movement_date range, ISO yyyy-MM-dd), projectId.
     */
    @GetMapping("/pots/{id}/statement")
    @PreAuthorize("hasAuthority('PERM_CASH_READ')")
    public ResponseEntity<List<CashService.StatementLine>> potStatement(
            @PathVariable UUID id,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID projectId,
            Authentication auth) {
        return ResponseEntity.ok(svc.getPotStatement(auth, id, from, to, projectId));
    }

    // ══════════════════════════════════════════════════════════════
    //  MOVEMENTS
    // ══════════════════════════════════════════════════════════════

    /**
     * POST /api/cash/movements — create a DRAFT movement.
     * Body: { movementType, amount, movementDate, fromPotId, toPotId,
     *         projectId?, partyId?, label?, note?, processInstanceId? }
     */
    @PostMapping("/movements")
    @PreAuthorize("hasAuthority('PERM_CASH_DRAFT')")
    public ResponseEntity<CashService.MovementResponse> createMovement(
            @RequestBody CashService.CreateMovementRequest req,
            Authentication auth) {
        CashService.MovementResponse created = svc.createMovement(auth, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** GET /api/cash/movements/{id} */
    @GetMapping("/movements/{id}")
    @PreAuthorize("hasAuthority('PERM_CASH_READ')")
    public ResponseEntity<CashService.MovementResponse> getMovement(
            @PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok(svc.getMovement(auth, id));
    }

    /**
     * GET /api/cash/movements/{id}/detail — richer response with pot names,
     * party name, project name, postedBy name, voidedBy name.
     * One JOINed query — no client-side N+1.
     */
    @GetMapping("/movements/{id}/detail")
    @PreAuthorize("hasAuthority('PERM_CASH_READ')")
    public ResponseEntity<CashService.MovementDetailResponse> getMovementDetail(
            @PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok(svc.getMovementDetail(auth, id));
    }

    /**
     * GET /api/cash/movements/{id}/timeline — ordered audit events for this
     * movement + snapshot fields (pot names, party name, status, amount).
     * Returns events for: CREATED, SUBMITTED, POSTED, VOIDED, REJECTED.
     */
    @GetMapping("/movements/{id}/timeline")
    @PreAuthorize("hasAuthority('PERM_CASH_READ')")
    public ResponseEntity<CashService.MovementTimeline> getMovementTimeline(
            @PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok(svc.getMovementTimeline(auth, id));
    }

    /**
     * PUT /api/cash/movements/{id} — update DRAFT only.
     * Body: same as create minus processInstanceId.
     */
    @PutMapping("/movements/{id}")
    @PreAuthorize("hasAuthority('PERM_CASH_DRAFT')")
    public ResponseEntity<CashService.MovementResponse> updateMovement(
            @PathVariable UUID id,
            @RequestBody CashService.UpdateMovementRequest req,
            Authentication auth) {
        return ResponseEntity.ok(svc.updateMovement(auth, id, req));
    }

    /**
     * GET /api/cash/movements — list with filters.
     * Query params: potId, partyId, projectId, status, from, to (ISO yyyy-MM-dd)
     */
    @GetMapping("/movements")
    @PreAuthorize("hasAuthority('PERM_CASH_READ')")
    public ResponseEntity<List<CashService.MovementResponse>> listMovements(
            @RequestParam(required = false) UUID potId,
            @RequestParam(required = false) UUID partyId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication auth) {
        return ResponseEntity.ok(svc.listMovements(auth, potId, partyId, projectId, status, from, to));
    }

    /**
     * GET /api/cash/movements/details — same filters as /movements but returns
     * the richer MovementDetailResponse with pot names, party name, project name,
     * postedBy name, voidedBy name. Frontend should prefer this to avoid N+1.
     */
    @GetMapping("/movements/details")
    @PreAuthorize("hasAuthority('PERM_CASH_READ')")
    public ResponseEntity<List<CashService.MovementDetailResponse>> listMovementsDetail(
            @RequestParam(required = false) UUID potId,
            @RequestParam(required = false) UUID partyId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication auth) {
        return ResponseEntity.ok(svc.listMovementsDetail(auth, potId, partyId, projectId, status, from, to));
    }

    /**
     * GET /api/cash/movements/export?from=&to=&potId=&projectId=&status= —
     * monthly cabinet/comptable export.
     *
     * <p>Same shape as {@link #potStatement} (single source of truth) but
     * org-wide (potId optional) and accepts a status filter.
     *
     * <p>Content negotiation:
     * <ul>
     *   <li><b>JSON (default)</b> — array of {@link CashService.StatementLine}.</li>
     *   <li><b>CSV</b> — when the caller sends {@code Accept: text/csv}.
     *       Same columns as the statement table, with a French header row
     *       so the file opens cleanly in Excel/LibreOffice for the cabinet.
     *       UTF-8 with BOM so Excel reads accents correctly on Windows.</li>
     * </ul>
     *
     * <p>Columns (CSV): Date | Bon | Tiers | Nature | Désignation | Type |
     * Mode | Réf | Facture | Montant | Devise | Statut | Projet.
     */
    @GetMapping(value = "/movements/export")
    @PreAuthorize("hasAuthority('PERM_CASH_READ')")
    public Object exportMovements(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID potId,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) String status,
            @RequestHeader(value = HttpHeaders.ACCEPT, defaultValue = MediaType.APPLICATION_JSON_VALUE) String accept,
            HttpServletResponse response) throws IOException {

        Authentication auth = (Authentication) org.springframework.security.core.context.SecurityContextHolder
                .getContext().getAuthentication();
        List<CashService.StatementLine> rows = svc.exportMovements(auth, from, to, potId, projectId, status);

        // CSV branch — only when the caller explicitly asks for text/csv
        if (accept != null && accept.toLowerCase().contains("text/csv")) {
            response.setContentType("text/csv; charset=UTF-8");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"hydra-export-caisse.csv\"");
            // Use ONLY the writer channel (never mix getOutputStream + getWriter
            // on the same response — IllegalStateException otherwise). The UTF-8
            // BOM is written as the single unicode char \uFEFF so Excel FR/EN
            // on Windows reads accents correctly on CSV open.
            PrintWriter w = response.getWriter();
            w.write('\uFEFF');
            // Header — matches the statement table column order
            w.println("Date;Bon;Tiers;Nature;Désignation;Type;Mode;Réf;Facture;Montant;Devise;Statut;Projet");
            DateTimeFormatter iso = DateTimeFormatter.ISO_LOCAL_DATE;
            for (CashService.StatementLine r : rows) {
                w.println(String.join(";",
                        csv(r.movementDate() == null ? "" : r.movementDate().format(iso)),
                        csv(r.voucherNo()),
                        csv(r.partyName()),
                        csv(r.nature()),
                        csv(r.label()),
                        csv(r.movementType()),
                        csv(r.paymentMode()),
                        csv(r.paymentRef()),
                        csv(r.invoiceRef()),
                        r.amount() == null ? "" : r.amount().toPlainString(),
                        csv(r.currency()),
                        csv(r.status()),
                        csv(r.projectName())
                ));
            }
            w.flush();
            // Return null so Spring doesn't try to write a body on top of what
            // we already streamed. (Spring's StringHttpMessageConverter would
            // otherwise call response.getOutputStream() again → IllegalStateException.)
            return null;
        }

        // JSON branch (default)
        return ResponseEntity.ok(rows);
    }

    /** CSV-quote a single cell value (RFC 4180 style, ';'-delimited for FR Excel). */
    private static String csv(String v) {
        if (v == null) return "";
        if (v.contains(";") || v.contains("\"") || v.contains("\n") || v.contains("\r")) {
            return "\"" + v.replace("\"", "\"\"") + "\"";
        }
        return v;
    }

    /** POST /api/cash/movements/{id}/post — DRAFT → POSTED (immutable). */
    @PostMapping("/movements/{id}/post")
    @PreAuthorize("hasAuthority('PERM_CASH_POST')")
    public ResponseEntity<CashService.PostResponse> postMovement(
            @PathVariable UUID id,
            @RequestParam(required = false, defaultValue = "false") boolean force,
            Authentication auth) {
        return ResponseEntity.ok(svc.postMovement(auth, id, force));
    }

    /**
     * POST /api/cash/movements/submit-payment — orchestrates the full payment flow:
     *   (a) create DRAFT OUT movement
     *   (b) start PAYMENT_APPROVAL workflow (subject = movement)
     *   (c) link movement ← process_instance.id
     *   (d) audit CASH_MOVEMENT_SUBMITTED
     * Body: { fromPotId, amount, movementDate, projectId?, partyId?, label?, note? }
     * Returns 201 with { movement, processInstanceId }
     *
     * On COMPLETED workflow, ProcessService calls CashService.onProcessCompletedPostMovement
     * which flips the movement to POSTED (this is automatic — the frontend just needs
     * to show the running process timeline and let the approver click Valider).
     */
    @PostMapping("/movements/submit-payment")
    @PreAuthorize("hasAuthority('PERM_CASH_SUBMIT')")
    public ResponseEntity<PaymentFacade.SubmitPaymentResponse> submitPayment(
            @RequestBody PaymentFacade.SubmitPaymentRequest req,
            Authentication auth) {
        PaymentFacade.SubmitPaymentResponse resp = paymentFacade.submitPayment(auth, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }

    /**
     * POST /api/cash/movements/{id}/void — POSTED → VOID.
     * Body: { reason: "required non-blank" }
     */
    @PostMapping("/movements/{id}/void")
    @PreAuthorize("hasAuthority('PERM_CASH_VOID')")
    public ResponseEntity<CashService.MovementResponse> voidMovement(
            @PathVariable UUID id,
            @RequestBody CashService.VoidRequest req,
            Authentication auth) {
        return ResponseEntity.ok(svc.voidMovement(auth, id, req));
    }

    /**
     * POST /api/cash/movements/{id}/attachment — TASK 3: attach the payment
     * proof (facture / BL / situation) to a DRAFT movement.
     *
     * Multipart form-data:
     *   file    — required, the proof file (PDF, image, ...)
     *   title   — required, document title
     *   docType — optional: FACTURE (default) | SITUATION | BON_LIVRAISON | AUTRE
     *
     * The file becomes a real Document in the project's Finance folder (06_FIN)
     * via DocumentService.upload — same folder upload ACL as the documents API
     * (folderAccess UPLOAD matrix + project can_upload) — and is linked on
     * movement.document_id. Only DRAFT movements accept attachments (POSTED is
     * immutable, VOID is dead → 409 MOVEMENT_NOT_EDITABLE).
     */
    @PostMapping(value = "/movements/{id}/attachment", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PERM_CASH_DRAFT')")
    public ResponseEntity<CashService.MovementDetailResponse> attachDocument(
            @PathVariable UUID id,
            @RequestParam("file") MultipartFile file,
            @RequestParam String title,
            @RequestParam(required = false) String docType,
            Authentication auth) {
        CashService.MovementDetailResponse resp = svc.attachDocument(auth, id, file, title, docType);
        return ResponseEntity.status(HttpStatus.CREATED).body(resp);
    }
}
