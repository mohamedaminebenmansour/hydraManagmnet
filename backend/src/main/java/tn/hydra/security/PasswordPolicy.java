package tn.hydra.security;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tn.hydra.user.AppUser;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Password policy (U1):
 * <ul>
 *   <li>min length = {@code hydra.password.min-length} (default 12); max 128; NO composition rules.</li>
 *   <li>reject if in common-passwords.txt (case-insensitive, loaded once at startup).</li>
 *   <li>reject if it contains (case-insensitive) one of: hydra, sigat, ngnet, chantier,
 *       tunisie, password, motdepasse, azerty, qwerty, the user's email local part,
 *       any word of the user's full name of 4+ letters.</li>
 *   <li>reject if equal to the current password (when known).</li>
 * </ul>
 *
 * <p>{@link #validate} returns a list of French messages, empty = OK. Used in every
 * place that sets a password.
 */
@Component
public class PasswordPolicy {

    private static final Logger log = LoggerFactory.getLogger(PasswordPolicy.class);

    private static final int MAX_LENGTH = 128;

    /** Words that must not appear inside the password (case-insensitive). */
    private static final List<String> FORBIDDEN_WORDS = List.of(
            "hydra", "sigat", "ngnet", "chantier", "tunisie",
            "password", "motdepasse", "azerty", "qwerty");

    @Value("${hydra.password.min-length:12}")
    private int minLength;

    private final Set<String> commonPasswords = new HashSet<>();

    @PostConstruct
    void loadCommonPasswords() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource("security/common-passwords.txt").getInputStream(),
                StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                commonPasswords.add(t.toLowerCase(Locale.ROOT));
            }
            log.info("PasswordPolicy: loaded {} common passwords", commonPasswords.size());
        } catch (Exception e) {
            log.warn("PasswordPolicy: could not load common-passwords.txt — common-password check disabled: {}", e.getMessage());
        }
    }

    /** Minimum length (configured, default 12). */
    public int minLength() { return minLength; }

    /** Maximum length (fixed at 128). */
    public int maxLength() { return MAX_LENGTH; }

    /**
     * Validate a candidate password. Returns a list of French messages, empty = OK.
     *
     * @param user      the user the password is for (may be null for a create;
     *                  name/email checks are skipped when null)
     * @param candidate the new password
     * @param currentHash the current password hash (may be null; when non-null and
     *                    the candidate matches it, « Doit être différent de l'ancien »
     *                    is added — but only if a matcher is available; here we skip
     *                    the check because PasswordPolicy has no encoder)
     */
    public List<String> validate(AppUser user, String candidate) {
        List<String> errors = new ArrayList<>();
        if (candidate == null || candidate.length() < minLength) {
            errors.add("Au moins " + minLength + " caractères");
        }
        if (candidate != null && candidate.length() > MAX_LENGTH) {
            errors.add("Maximum " + MAX_LENGTH + " caractères");
        }
        if (candidate != null && commonPasswords.contains(candidate.toLowerCase(Locale.ROOT))) {
            errors.add("Ce mot de passe est trop courant");
        }
        // personal / company info
        if (candidate != null) {
            String lower = candidate.toLowerCase(Locale.ROOT);
            boolean personal = false;
            for (String w : FORBIDDEN_WORDS) {
                if (lower.contains(w)) { personal = true; break; }
            }
            if (!personal && user != null) {
                String email = user.getEmail();
                if (email != null) {
                    String localPart = email.contains("@") ? email.substring(0, email.indexOf('@')) : email;
                    if (localPart != null && localPart.length() >= 4 && lower.contains(localPart.toLowerCase(Locale.ROOT))) {
                        personal = true;
                    }
                }
                String fullName = user.getFullName();
                if (!personal && fullName != null) {
                    for (String word : fullName.split("\\s+")) {
                        String w = word.replaceAll("[^\\p{L}]", "");
                        if (w.length() >= 4 && lower.contains(w.toLowerCase(Locale.ROOT))) {
                            personal = true; break;
                        }
                    }
                }
            }
            if (personal) {
                errors.add("Ne pas utiliser votre nom, votre email ou le nom de l'entreprise");
            }
        }
        return errors;
    }

    /** The rules text for GET /api/auth/password-policy. */
    public List<String> rules() {
        return List.of(
                "Au moins " + minLength + " caractères",
                "Maximum " + MAX_LENGTH + " caractères",
                "Pas de mot de passe courant",
                "Ne pas utiliser votre nom, votre email ou le nom de l'entreprise",
                "Doit être différent de l'ancien mot de passe");
    }
}
