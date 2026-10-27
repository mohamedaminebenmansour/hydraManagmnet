package tn.hydra.cash;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Party REST endpoints — directory of sous-traitants / fournisseurs per org.
 * Base path: /api/parties
 */
@RestController
@RequestMapping("/api/parties")
public class PartyController {

    private final PartyService svc;

    public PartyController(PartyService svc) {
        this.svc = svc;
    }

    /** GET /api/parties?type=SUB&includeInactive=false */
    @GetMapping
    public ResponseEntity<List<PartyService.PartyResponse>> list(
            @RequestParam(required = false) String type,
            @RequestParam(defaultValue = "false") boolean includeInactive,
            Authentication auth) {
        return ResponseEntity.ok(svc.list(auth, type, includeInactive));
    }

    /** GET /api/parties/{id} */
    @GetMapping("/{id}")
    public ResponseEntity<PartyService.PartyResponse> get(
            @PathVariable UUID id, Authentication auth) {
        return ResponseEntity.ok(svc.get(auth, id));
    }

    /** POST /api/parties — body: { name, type, phone } */
    @PostMapping
    public ResponseEntity<PartyService.PartyResponse> create(
            @RequestBody PartyService.CreatePartyRequest req,
            Authentication auth) {
        PartyService.PartyResponse created = svc.create(auth, req);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** PUT /api/parties/{id} — body: { name?, type?, phone?, active? } */
    @PutMapping("/{id}")
    public ResponseEntity<PartyService.PartyResponse> update(
            @PathVariable UUID id,
            @RequestBody PartyService.UpdatePartyRequest req,
            Authentication auth) {
        return ResponseEntity.ok(svc.update(auth, id, req));
    }

    /** DELETE /api/parties/{id} — soft-deactivate if referenced, hard-delete otherwise. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication auth) {
        svc.deleteOrDeactivate(auth, id);
        return ResponseEntity.noContent().build();
    }
}
