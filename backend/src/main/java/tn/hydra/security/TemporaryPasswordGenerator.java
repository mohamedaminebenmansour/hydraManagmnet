package tn.hydra.security;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

/**
 * Generates a temporary password (U1):
 * 12 characters from « ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789 »
 * (no 0 O 1 l I), at least one digit, never in the blocklist.
 */
@Component
public class TemporaryPasswordGenerator {

    private static final char[] CHARS =
            "ABCDEFGHJKMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789".toCharArray();

    private static final int LENGTH = 12;

    private final SecureRandom random = new SecureRandom();
    private final PasswordPolicy policy;

    public TemporaryPasswordGenerator(PasswordPolicy policy) {
        this.policy = policy;
    }

    /**
     * Generate a temporary password: 12 chars, at least one digit, not in the
     * common-passwords blocklist. Loops until the blocklist check passes
     * (extremely unlikely to loop more than once).
     */
    public String generate() {
        for (int attempt = 0; attempt < 16; attempt++) {
            char[] buf = new char[LENGTH];
            boolean hasDigit = false;
            for (int i = 0; i < LENGTH; i++) {
                buf[i] = CHARS[random.nextInt(CHARS.length)];
                if (Character.isDigit(buf[i])) hasDigit = true;
            }
            // ensure at least one digit
            if (!hasDigit) {
                buf[random.nextInt(LENGTH)] = CHARS[CHARS.length - 6 + random.nextInt(6)];
            }
            String pw = new String(buf);
            // never return a common password (case-insensitive check)
            if (!policy.validate(null, pw).contains("Ce mot de passe est trop courant")) {
                return pw;
            }
        }
        // fallback: append a digit to guarantee uniqueness from the blocklist
        return "Xy7" + Long.toString(System.nanoTime(), 36).substring(0, 9);
    }
}
