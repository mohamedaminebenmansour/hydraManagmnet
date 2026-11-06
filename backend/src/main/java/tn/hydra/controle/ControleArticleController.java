package tn.hydra.controle;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.organization.CompanyProfileService;

import java.util.List;
import java.util.Map;

/**
 * ControleArticleController — GET /api/controle/articles and
 * PUT /api/controle/articles/aliases (rawKey in the body: raw texts may contain « / »).
 */
@RestController
@RequestMapping("/api/controle/articles")
public class ControleArticleController {

    private final ControleArticleService service;

    public ControleArticleController(ControleArticleService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_CTL_READ')")
    public List<ControleArticleService.ArticleRow> list(Authentication auth) {
        return service.listArticles(auth);
    }

    @PutMapping("/aliases")
    @PreAuthorize("hasAuthority('PERM_CTL_JUSTIFY')")
    public ResponseEntity<?> putAlias(@RequestBody ControleArticleService.AliasBody body,
                                      Authentication auth) {
        try {
            return ResponseEntity.ok(service.putAlias(auth, body));
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
