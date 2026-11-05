package tn.hydra.organization;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import tn.hydra.common.BusinessRuleException;

import java.io.IOException;
import java.util.Map;

/**
 * Company profile controller (S1-5 step 3).
 */
@RestController
@RequestMapping("/api/company")
public class CompanyProfileController {

    private static final int MAX_LOGO_BYTES = 1_048_576; // 1 MB

    private final CompanyProfileService service;

    public CompanyProfileController(CompanyProfileService service) {
        this.service = service;
    }

    // ── GET profile ────────────────────────────────────────────────────

    @GetMapping
    public CompanyProfileService.CompanyProfileResponse get(Authentication auth) {
        return service.get(auth);
    }

    // ── PUT profile ─────────────────────────────────────────────────────

    @PutMapping
    @PreAuthorize("hasAuthority('PERM_ORG_SETTINGS')")
    public ResponseEntity<?> update(@RequestBody CompanyProfileService.UpdateRequest body, Authentication auth) {
        try {
            return ResponseEntity.ok(service.update(auth, body));
        } catch (CompanyProfileService.ValidationException e) {
            return ResponseEntity.badRequest().body(Map.of("code", "VALIDATION", "fields", e.getFields()));
        } catch (BusinessRuleException e) {
            if ("STALE_PROFILE".equals(e.getCode())) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("code", "STALE_PROFILE", "message", e.getMessage()));
            }
            throw e;
        }
    }

    // ── Logo upload ────────────────────────────────────────────────────

    @PostMapping("/logo")
    @PreAuthorize("hasAuthority('PERM_ORG_SETTINGS')")
    public ResponseEntity<?> uploadLogo(@RequestParam("file") MultipartFile file, Authentication auth) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", "LOGO_REQUIRED", "message", "Aucun fichier reçu."));
        }
        if (file.getSize() > MAX_LOGO_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("code", "LOGO_TOO_LARGE", "message", "Le logo dépasse 1 Mo."));
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("code", "LOGO_UNREADABLE", "message", "Fichier illisible."));
        }

        // Magic bytes check (never trust filename or content-type header)
        String detectedType = detectImageType(content);
        if (detectedType == null) {
            return ResponseEntity.badRequest().body(Map.of("code", "LOGO_TYPE", "message", "Format accepté : PNG ou JPEG."));
        }

        service.uploadLogo(auth, content, detectedType);
        return ResponseEntity.ok().build();
    }

    // ── Logo download ──────────────────────────────────────────────────

    @GetMapping("/logo")
    public ResponseEntity<byte[]> getLogo(Authentication auth) {
        var logo = service.getLogo(auth);
        if (logo == null || logo.content() == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(logo.contentType()))
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "private, max-age=300")
                .body(logo.content());
    }

    // ── Logo delete ────────────────────────────────────────────────────

    @DeleteMapping("/logo")
    @PreAuthorize("hasAuthority('PERM_ORG_SETTINGS')")
    public ResponseEntity<Void> deleteLogo(Authentication auth) {
        service.deleteLogo(auth);
        return ResponseEntity.noContent().build();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** Detect image type by magic bytes. Returns null for anything else. */
    static String detectImageType(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return null;
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (bytes.length >= 8 &&
                bytes[0] == (byte) 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47 &&
                bytes[4] == 0x0D && bytes[5] == 0x0A && bytes[6] == 0x1A && bytes[7] == 0x0A) {
            return "image/png";
        }
        // JPEG: FF D8 FF
        if (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
            return "image/jpeg";
        }
        return null;
    }
}
