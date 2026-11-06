package tn.hydra.controle;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.organization.CompanyProfileService;

import java.util.Map;

/**
 * ControleJustificationController — PUT /api/controle/justifications/{lineKey}.
 */
@RestController
@RequestMapping("/api/controle/justifications")
public class ControleJustificationController {

    private final ControleJustificationService service;

    public ControleJustificationController(ControleJustificationService service) {
        this.service = service;
    }

    @PutMapping("/{lineKey}")
    @PreAuthorize("hasAuthority('PERM_CTL_JUSTIFY')")
    public ResponseEntity<?> putJustification(@PathVariable String lineKey,
                                              @RequestBody ControleJustificationService.JustifBody body,
                                              Authentication auth) {
        try {
            return ResponseEntity.ok(service.putJustification(auth, lineKey, body));
        } catch (CompanyProfileService.ValidationException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "VALIDATION", "fields", e.getFields()));
        } catch (ResourceNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("code", e.getCode(), "message", e.getMessage()));
        } catch (BusinessRuleException e) {
            if ("STALE".equals(e.getCode())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("code", "STALE", "message", e.getMessage()));
            }
            throw e;
        }
    }
}
