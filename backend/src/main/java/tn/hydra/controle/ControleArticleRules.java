package tn.hydra.controle;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ControleArticleRules — Java port of plain(), article() and piece() in
 * tools/controle/analyse.py. analyse.py is the reference: the app must give
 * the same article and pièce on the same line.
 *
 * <p>Pure static methods, no Spring. Python semantics kept on purpose:
 * {@code re.match} = {@link Matcher#lookingAt()}, {@code \s} and {@code \d}
 * are Unicode-aware, {@code str.strip()} strips Unicode spaces (NBSP too),
 * {@code str.title()} is ported in {@link #title(String)}.
 */
public final class ControleArticleRules {

    private ControleArticleRules() {}

    private static final Pattern MARKS = Pattern.compile("\\p{Mn}");
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern DIGIT = Pattern.compile("\\d", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern MO = Pattern.compile("mo ?(rsp|rse) ?(\\d+)?", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern RSE = Pattern.compile("rse ?(\\d+)", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PERSON = Pattern.compile("[a-z]+( [a-z]+){1,2}");

    /** Lowercase, no accents, single spaces, trimmed. Used as ctl_line.raw_key. */
    public static String plain(String s) {
        String lower = (s == null ? "" : s).toLowerCase(Locale.ROOT);
        String nfd = Normalizer.normalize(lower, Normalizer.Form.NFD);
        String noMarks = MARKS.matcher(nfd).replaceAll("");
        return strip(SPACES.matcher(noMarks).replaceAll(" "));
    }

    /** Normalized article (poste of the marché) from the Excel 'Destination / ST' column. */
    public static String article(String cat, String party) {
        String raw = cat == null ? "" : cat;
        String p = plain(raw);
        if (p.isEmpty() || p.equals("-") || p.equals("ngnet") || p.equals("sigat")) {
            return "Non classé";
        }
        if (p.startsWith("st ") || p.startsWith("s.t") || p.contains("sous tra")) {
            String who = (party != null && !party.isEmpty())
                    ? party
                    : strip(raw.length() > 3 ? raw.substring(3) : "");
            return "ST " + strip(who);
        }
        if (p.contains("charge sur ngnet")) return "Charge sur marché";
        if (p.contains("charge") && p.contains("march")) return "Charge sur marché";
        Matcher m = MO.matcher(p);
        if (m.lookingAt()) {
            if (m.group(1).equals("rsp")) return "MO RSP";
            return "MO RSE " + (m.group(2) != null && !m.group(2).isEmpty() ? m.group(2) : "(à préciser)");
        }
        if (p.contains("existant")) return "Réservoir existant";
        m = RSE.matcher(p);
        if (m.lookingAt()) return "RSE " + m.group(1);
        if (p.equals("rse")) return "RSE (à préciser)";
        if (p.startsWith("rsp")) return "RSP";
        if (p.contains("pehd")) return "Conduite en PEHD";
        if (p.contains("fonte")) return "Conduite en fonte";
        if (p.contains("niche")) return "Pièces des niches";
        if (p.contains("speciale")) return "Pièces spéciales";
        if (p.startsWith("abri")) return "Abri forage";
        if (p.contains("cloture")) return "Clôture";
        if (p.contains("surpress")) return "Station de surpresseur";
        if (p.contains("caisse chantier")) return "Alimentation caisse chantier";
        if (p.startsWith("encaissement")) return "Encaissement (décomptes)";
        if (p.equals("bc")) return "BC (à préciser)";
        if (p.contains("travaux") || p.contains("ouvrage")) return "Travaux divers / GC";
        if (p.startsWith("projet")) return "Non classé";
        if (PERSON.matcher(p).matches()) {
            return "Alimentation (personne) — " + title(strip(raw));
        }
        return strip(raw);
    }

    /** Facture / Bon / Facture + bon / Mention sans numéro / Aucune pièce. */
    public static String piece(String fct, String bon) {
        String f = strip(fct == null ? "" : fct);
        String b = strip(bon == null ? "" : bon);
        boolean hasf = DIGIT.matcher(f).find();
        boolean hasb = DIGIT.matcher(b).find();
        if (hasf && hasb) return "Facture + bon";
        if (hasf) return "Facture";
        if (hasb) return "Bon";
        if (!f.isEmpty() && !f.equals("-")) return "Mention sans numéro";
        return "Aucune pièce";
    }

    // ── Python helpers ────────────────────────────────────────────────────────

    /** Python str.strip(): removes leading/trailing Unicode spaces, NBSP included. */
    static String strip(String s) {
        int start = 0, end = s.length();
        while (start < end && isPySpace(s.charAt(start))) start++;
        while (end > start && isPySpace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    private static boolean isPySpace(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c);
    }

    /** Python str.title(): first cased letter of each run upper (title) case, the rest lower. */
    static String title(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean prevCased = false;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            out.appendCodePoint(prevCased ? Character.toLowerCase(cp) : Character.toTitleCase(cp));
            prevCased = Character.isUpperCase(cp) || Character.isLowerCase(cp) || Character.isTitleCase(cp);
            i += Character.charCount(cp);
        }
        return out.toString();
    }
}
