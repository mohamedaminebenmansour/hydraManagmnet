package tn.hydra.controle;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tn.hydra.common.BusinessRuleException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ControleImportController — POST /api/controle/import and GET /api/controle/imports.
 *
 * <p>The current user / org is resolved through the {@link Authentication}
 * parameter (Spring Security), exactly like {@code CompanyProfileController}.
 */
@RestController
@RequestMapping("/api/controle")
public class ControleImportController {

    /** 20 MB max upload (the converted JSON can be large for a full year of cash). */
    private static final long MAX_IMPORT_BYTES = 20L * 1024 * 1024;

    private final ControleImportService service;
    private final ControleExcelReader excelReader;

    public ControleImportController(ControleImportService service, ControleExcelReader excelReader) {
        this.service = service;
        this.excelReader = excelReader;
    }

    // ── POST /api/controle/import ──────────────────────────────────────────

    @PostMapping("/import")
    @PreAuthorize("hasAuthority('PERM_CTL_IMPORT')")
    public ResponseEntity<?> importFile(@RequestParam("file") MultipartFile file,
                                        Authentication auth) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "IMPORT_FORMAT",
                    "message", "Le fichier n'est pas un export valide de convert.py."));
        }
        if (file.getSize() > MAX_IMPORT_BYTES) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "IMPORT_TOO_LARGE",
                    "message", "Le fichier dépasse 20 Mo."));
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "IMPORT_FORMAT",
                    "message", "Le fichier n'est pas un export valide de convert.py."));
        }

        try {
            // .xlsx → read like convert.py; anything else → the convert.py JSON as before
            String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
            if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
                ControleImportFile.ImportFile parsed = excelReader.read(
                        new java.io.ByteArrayInputStream(content), name,
                        java.time.LocalDate.now(), java.time.LocalDateTime.now());
                return ResponseEntity.ok(service.importParsed(auth, parsed));
            }
            return ResponseEntity.ok(service.importFile(auth, content));
        } catch (ControleImportService.ImportInvalidException e) {
            // 400 with the list of French problems
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", "IMPORT_INVALID");
            body.put("message", e.getMessage());
            body.put("problems", e.getProblems());
            return ResponseEntity.badRequest().body(body);
        } catch (BusinessRuleException e) {
            // IMPORT_FORMAT and any other IMPORT_* business error -> 400
            if (e.getCode() != null && e.getCode().startsWith("IMPORT_")) {
                return ResponseEntity.badRequest().body(Map.of(
                        "code", e.getCode(),
                        "message", e.getMessage()));
            }
            throw e;
        }
    }

    // ── GET /api/controle/imports — last 10 imports of my org ──────────────

    public record ImportHistoryRow(java.util.UUID id, java.util.UUID orgId, String sourceName,
                                    String generatedAt, java.time.OffsetDateTime importedAt,
                                    java.util.UUID importedBy, String importerName,
                                    int lineCount, int stLineCount) {}

    @GetMapping("/imports")
    @PreAuthorize("hasAuthority('PERM_CTL_READ')")
    public List<ImportHistoryRow> imports(Authentication auth) {
        return service.listImports(auth);
    }
}
