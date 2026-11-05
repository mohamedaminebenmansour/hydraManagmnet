package tn.hydra.organization;

import java.math.BigInteger;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tunisian identifiers: matricule fiscal (MF) and RIB validation.
 * Pure Java, no database, no Spring.
 */
public final class TunisianIds {

    private TunisianIds() {}

    // ══════════════════ MATRICULE FISCAL ══════════════════

    private static final Pattern MF_PATTERN = Pattern.compile("^(\\d{7})([A-Z])([A-Z])([A-Z])(\\d{3})$");
    private static final Set<Character> FORBIDDEN_CHECK_LETTERS = Set.of('I', 'O', 'U');
    private static final Set<Character> VALID_TVA_CODES = Set.of('A', 'P', 'B', 'D', 'N');
    private static final Set<Character> VALID_CATEGORIES = Set.of('M', 'P', 'C', 'N', 'E');

    /**
     * Normalize a matricule fiscal: uppercase, remove spaces, '/', '.', '-'.
     */
    public static String normalizeMf(String raw) {
        if (raw == null) return "";
        return raw.trim().toUpperCase()
                .replace(" ", "")
                .replace("/", "")
                .replace(".", "")
                .replace("-", "");
    }

    /**
     * Validate a matricule fiscal. Returns an error message (French) or empty if valid.
     */
    public static Optional<String> validateMf(String raw) {
        String v = normalizeMf(raw);
        var m = MF_PATTERN.matcher(v);
        if (!m.matches()) {
            return Optional.of("Format attendu : 1234567/A/B/M/000.");
        }
        char checkLetter = m.group(2).charAt(0);
        char tvaCode = m.group(3).charAt(0);
        char category = m.group(4).charAt(0);

        if (FORBIDDEN_CHECK_LETTERS.contains(checkLetter)) {
            return Optional.of("La lettre de contrôle ne peut pas être I, O ou U.");
        }
        if (!VALID_TVA_CODES.contains(tvaCode)) {
            return Optional.of("Code TVA invalide (A, P, B, D ou N).");
        }
        if (!VALID_CATEGORIES.contains(category)) {
            return Optional.of("Catégorie invalide (M, P, C, N ou E).");
        }
        return Optional.empty();
    }

    /**
     * Format a normalized MF: "1234567/A/B/M/000".
     */
    public static String formatMf(String normalized) {
        if (normalized == null || normalized.length() != 13) return normalized;
        return normalized.substring(0, 7) + "/" +
               normalized.charAt(7) + "/" +
               normalized.charAt(8) + "/" +
               normalized.charAt(9) + "/" +
               normalized.substring(10);
    }

    // ══════════════════ RIB ══════════════════

    /**
     * Normalize a RIB: remove spaces, '-', '.'.
     */
    public static String normalizeRib(String raw) {
        if (raw == null) return "";
        return raw.trim()
                .replace(" ", "")
                .replace("-", "")
                .replace(".", "");
    }

    /**
     * Validate a Tunisian RIB (20 digits).
     * Key check: expected = 97 - (first18 * 100 mod 97). Valid when expected == key.
     * Uses BigInteger, never long or double.
     */
    public static Optional<String> validateRib(String raw) {
        String v = normalizeRib(raw);
        if (!v.matches("\\d{20}")) {
            return Optional.of("Le RIB doit contenir 20 chiffres.");
        }
        BigInteger first18 = new BigInteger(v.substring(0, 18));
        int key = Integer.parseInt(v.substring(18, 20));
        // first18 * 100 mod 97
        BigInteger mod = first18.multiply(BigInteger.valueOf(100)).mod(BigInteger.valueOf(97));
        int expected = 97 - mod.intValue();
        if (expected != key) {
            return Optional.of("Clé RIB incorrecte : vérifiez le numéro.");
        }
        return Optional.empty();
    }

    /**
     * Build an IBAN from a valid Tunisian RIB.
     * A valid Tunisian RIB is always a multiple of 97 when prefixed with TN00,
     * so the IBAN check digits are always 59.
     * IBAN = "TN" + checkDigits + rib = "TN59" + rib.
     */
    public static String ibanFromRib(String rib) {
        String v = normalizeRib(rib);
        return "TN59" + v;
    }

    /**
     * Format a RIB: "07 040 0058101111296 53" (groups of 2-3-13-2).
     */
    public static String formatRib(String rib) {
        String v = normalizeRib(rib);
        if (v.length() != 20) return v;
        return v.substring(0, 2) + " " +
               v.substring(2, 5) + " " +
               v.substring(5, 18) + " " +
               v.substring(18, 20);
    }

    /**
     * Format an IBAN: groups of 4: "TN59 0704 0005 8101 1112 9653".
     */
    public static String formatIban(String iban) {
        if (iban == null || iban.length() < 4) return iban;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < iban.length(); i += 4) {
            if (i > 0) sb.append(' ');
            sb.append(iban, i, Math.min(i + 4, iban.length()));
        }
        return sb.toString();
    }

    /**
     * Mask a RIB for audit logs: only the last 4 digits visible.
     * "07040005810111129653" -> "****************9653"
     */
    public static String maskRib(String rib) {
        String v = normalizeRib(rib);
        if (v.length() < 4) return "****";
        return "****************" + v.substring(v.length() - 4);
    }
}
