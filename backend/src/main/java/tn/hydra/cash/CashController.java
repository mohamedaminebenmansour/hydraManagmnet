package tn.hydra.cash;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Cash REST endpoints — pots + movements + balance.
 *
 * Base paths:
 *   /api/cash/pots/...        — pots (caisse)
 *   /api/cash/movements/...   — movements (money events)
 */
@RestController
@RequestMapping("/api/cash")
public class CashController {

    private final CashService svc;

    public CashController(CashService svc) {
        this.svc = svc;
    }

    // ══════════════════════════════════════════════════════════════
    //  POTS
    // ══════════════════════════════════════════════════════════════

    /** GET /api/cash/pots — list all pots visible to caller (COMPANY + accessible PROJECT). */
    @GetMapping("/pots")
    public ResponseEntity<List<CashService.PotResponse>> listPots(Authentication auth) {
        return ResponseEntity.ok(svc.listPots(auth));
    }

    /** POST /api/cash/pots/ensure-company — idempotent ensure of "Caisse centrale". */
    @PostMapping("/pots/ensure-company")
    public ResponseEntity<CashService.PotResponse> ensureCompanyPot(Authentication auth) {
        return ResponseEntity.ok(svc.ensureCompanyPot(auth));
    }

    /** POST /api/cash/pots/ensure-project/{projectId} — idempotent ensure of project pot. */
    @PostMapping("/pots/ensure-project/{projectId}")
    public ResponseEntity<CashService.PotResponse> ensureProjectPot(
            @PathVariable UUID projectId, Authentication auth) {
        return ResponseEntity.ok(svc.ensureProjectPot(auth, projectId));
    }

    /** GET /api/cash/pots/{id}/balance — computed balance for the pot. */
    @GetMapping("/pots/{id}/balance")
    public ResponseEntity<CashService.BalanceResponse> balance(
            @PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok(svc.balance(auth, id));
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
    public ResponseEntity<CashService.MovementResponse> createMovement(
            @RequestBody CashService.CreateMovementRequest req,
            Authentication auth) {
        CashService.MovementResponse created = svc.createMovement(auth, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** GET /api/cash/movements/{id} */
    @GetMapping("/movements/{id}")
    public ResponseEntity<CashService.MovementResponse> getMovement(
            @PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok(svc.getMovement(auth, id));
    }

    /**
     * PUT /api/cash/movements/{id} — update DRAFT only.
     * Body: same as create minus processInstanceId.
     */
    @PutMapping("/movements/{id}")
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

    /** POST /api/cash/movements/{id}/post — DRAFT → POSTED (immutable). */
    @PostMapping("/movements/{id}/post")
    public ResponseEntity<CashService.PostResponse> postMovement(
            @PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok(svc.postMovement(auth, id));
    }

    /**
     * POST /api/cash/movements/{id}/void — POSTED → VOID.
     * Body: { reason: "required non-blank" }
     */
    @PostMapping("/movements/{id}/void")
    public ResponseEntity<CashService.MovementResponse> voidMovement(
            @PathVariable UUID id,
            @RequestBody CashService.VoidRequest req,
            Authentication auth) {
        return ResponseEntity.ok(svc.voidMovement(auth, id, req));
    }
}
