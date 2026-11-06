package tn.hydra.controle;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.organization.CompanyProfileService;

import java.util.Map;

/**
 * ControleValidationController — PUT /api/controle/validations/{key}.
 */
@RestController
@RequestMapping("/api/controle/validations")
public class ControleValidationController {

    private final ControleValidationService service;

    public ControleValidationController(ControleValidationService service) {
        this.service = service;
    }

    @PutMapping("/{key}")
    @PreAuthorize("hasAuthority('PERM_CTL_JUSTIFY')")
    public ResponseEntity<?> putValidation(@PathVariable String key,
                                           @RequestBody ControleValidationService.ValidBody body,
                                           Authentication auth) {
        try {
            return ResponseEntity.ok(service.putValidation(auth, key, body));
        } catch (CompanyProfileService.ValidationException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "VALIDATION", "fields", e.getFields()));
        } catch (BusinessRuleException e) {
            if ("STALE".equals(e.getCode())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("code", "STALE", "message", e.getMessage()));
            }
            throw e;
        }
    }
}
