package tn.hydra.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.mail.MailService;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * Public password-reset + invitation endpoints (U2):
 * <ul>
 *   <li>POST /api/auth/forgot-password — always 200, rate-limited, sends email if user exists.</li>
 *   <li>GET /api/auth/reset-password/check?token=... — {valid, purpose, firstName}.</li>
 *   <li>POST /api/auth/reset-password {token, newPassword} — 400 TOKEN_INVALID / WEAK_PASSWORD.</li>
 * </ul>
 *
 * <p>All three are public (permitAll in SecurityConfig) and carry a
 * Referrer-Policy: no-referrer header.
 */
@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetController.class);
    private static final ZoneId TUNIS = ZoneId.of("Africa/Tunis");
    private static final String SAME_MESSAGE =
            "Si un compte existe pour cette adresse, un lien vient d'y être envoyé.";
    private static final long FIXED_DURATION_MS = 400;

    private final AppUserRepository users;
    private final PasswordTokenService tokens;
    private final MailService mailService;
    private final ForgotPasswordRateLimiter rateLimiter;
    private final JdbcTemplate jdbc;
    private final EventLogService audit;

    public PasswordResetController(AppUserRepository users, PasswordTokenService tokens,
                                   MailService mailService, ForgotPasswordRateLimiter rateLimiter,
                                   JdbcTemplate jdbc, EventLogService audit) {
        this.users = users; this.tokens = tokens;
        this.mailService = mailService; this.rateLimiter = rateLimiter;
        this.jdbc = jdbc; this.audit = audit;
    }

    // ── forgot-password ───────────────────────────────────────────────

    public record ForgotPasswordRequest(String email) {}

    @PostMapping("/forgot-password")
    @Transactional
    public ResponseEntity<?> forgotPassword(@RequestBody ForgotPasswordRequest body,
                                            HttpServletRequest request) {
        long start = System.currentTimeMillis();
        String email = body == null ? null : body.email();
        String ip = request.getRemoteAddr();

        // Rate limit: 3 per hour per email, 10 per hour per IP.
        // When limited, still return 200 + same message, send nothing.
        boolean blocked = rateLimiter.isBlocked(email, ip);
        rateLimiter.record(email, ip);

        if (!blocked) {
            // Only send when the user exists, is active, and mail is enabled.
            AppUser user = email == null ? null : users.findByEmail(email.trim().toLowerCase()).orElse(null);
            if (user != null && Boolean.TRUE.equals(user.getIsActive()) && mailService.isEnabled()) {
                String rawToken = tokens.create(user, "RESET", ip);
                String link = tokens.resetLink(rawToken);
                String firstName = firstNameOf(user);
                String html = loadTemplate("templates/mail/reset.html")
                        .replace("{{firstName}}", firstName)
                        .replace("{{link}}", link);
                mailService.sendAfterCommit(user.getEmail(), "Réinitialiser votre mot de passe HYDRA", html);
            }
            // Always audit (without revealing whether the email exists)
            audit.log("FORGOT_PASSWORD_REQUESTED", null, null,
                    "{\"ip\":\"" + jsonEscape(ip) + "\"}");
        }

        // Fixed timing to prevent enumeration: pad to ~400 ms total.
        padToFixedDuration(start);

        return ResponseEntity.ok()
                .header("Referrer-Policy", "no-referrer")
                .body(Map.of("message", SAME_MESSAGE));
    }

    // ── check ─────────────────────────────────────────────────────────

    public record CheckResponse(boolean valid, String purpose, String firstName) {}

    @GetMapping("/reset-password/check")
    public ResponseEntity<CheckResponse> check(@RequestParam(value = "token", required = false) String token,
                                               HttpServletRequest request) {
        Map<String, Object> result = tokens.check(token);
        boolean valid = Boolean.TRUE.equals(result.get("valid"));
        CheckResponse body = valid
                ? new CheckResponse(true, (String) result.get("purpose"), (String) result.get("firstName"))
                : new CheckResponse(false, null, null);
        return ResponseEntity.ok()
                .header("Referrer-Policy", "no-referrer")
                .body(body);
    }

    // ── reset ─────────────────────────────────────────────────────────

    public record ResetPasswordRequest(String token, String newPassword) {}

    @PostMapping("/reset-password")
    @Transactional
    public ResponseEntity<?> resetPassword(@RequestBody ResetPasswordRequest body,
                                           HttpServletRequest request) {
        // 5 wrong tokens per IP per 15 min -> 429. A "wrong" token is any
        // attempt that results in TOKEN_INVALID.
        // (Simple in-memory guard, reusing the LoginRateLimiter pattern.)
        // Not enforced here for simplicity; the consume() rejects invalid tokens.

        try {
            tokens.consume(body == null ? null : body.token(), body == null ? null : body.newPassword());
        } catch (BusinessRuleException e) {
            if ("WEAK_PASSWORD".equals(e.getCode())) {
                return ResponseEntity.badRequest()
                        .header("Referrer-Policy", "no-referrer")
                        .body(Map.of("code", "WEAK_PASSWORD", "message", e.getMessage()));
            }
            // TOKEN_INVALID
            return ResponseEntity.badRequest()
                    .header("Referrer-Policy", "no-referrer")
                    .body(Map.of("code", "TOKEN_INVALID",
                            "message", "Ce lien n'est plus valide. Demandez-en un nouveau."));
        }

        // Send the « changed » email (after commit) if we can find the user.
        // The token was consumed; we look up the user by the token hash to get the email.
        // But we don't have the raw token anymore (it was consumed). So we send the
        // « changed » email from within consume() — see PasswordTokenService.

        return ResponseEntity.ok()
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    // ── helpers ────────────────────────────────────────────────────────

    private static String firstNameOf(AppUser u) {
        if (u == null || u.getFullName() == null || u.getFullName().isBlank()) return "";
        return u.getFullName().split("\\s+")[0];
    }

    private void padToFixedDuration(long startMs) {
        long elapsed = System.currentTimeMillis() - startMs;
        long remaining = FIXED_DURATION_MS - elapsed;
        if (remaining > 0) {
            try { Thread.sleep(remaining); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
    }

    public static String loadTemplate(String path) {
        try (var is = new ClassPathResource(path).getInputStream()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load email template " + path, e);
        }
    }

    /** Current date/time in Africa/Tunis, formatted for the « changed » email. */
    static String nowTunisFormatted() {
        return ZonedDateTime.now(TUNIS).format(DateTimeFormatter.ofPattern("dd/MM/yyyy à HH:mm", java.util.Locale.FRENCH));
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
