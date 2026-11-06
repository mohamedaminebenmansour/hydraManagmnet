package tn.hydra.controle;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * ControleQueryController — GET /api/controle/data.
 * Returns the full dataset for the caller's org (read-only).
 */
@RestController
@RequestMapping("/api/controle")
public class ControleQueryController {

    private final ControleQueryService service;

    public ControleQueryController(ControleQueryService service) {
        this.service = service;
    }

    @GetMapping("/data")
    @PreAuthorize("hasAuthority('PERM_CTL_READ')")
    public ControleQueryService.CtlData getData(Authentication auth) {
        return service.getData(auth);
    }
}
