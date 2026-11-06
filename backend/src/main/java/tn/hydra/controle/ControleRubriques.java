package tn.hydra.controle;

import java.util.List;
import java.util.Set;

/**
 * ControleRubriques — maps an article (ControleArticleRules) to the rubrique of the
 * Excel table « Récap des dépenses par projet » (its column headers), and a rubrique
 * to a famille. Pure static methods, no Spring.
 *
 * <p>An article that is not a recap column keeps its own name as rubrique
 * (outside the recap).
 */
public final class ControleRubriques {

    private ControleRubriques() {}

    /** The 27 recap headers, trimmed, in the Excel order. */
    public static final List<String> RUBRIQUES = List.of(
            "Abri Forage", "Charge sur marché", "Conduite en PEHD", "Conduite en Fonte",
            "Piéces spéciales", "piéces des niches", "RSP", "Clôture", "BC", "Local GDA",
            "RSE 500", "RSE 150 D", "RSE 100 M", "Station de Surpresseur", "Travaux GC",
            "Travaux Divers", "ST Abd Moula", "ST Med Hedi El Abed", "ST Ahmed Houimli",
            "ST Aymen Amri", "ST Hamza Msalmi", "ST Ismaiil Mansouri", "MO RSP", "MO RSE 500",
            "MO RSE 150", "MO RSE 100", "ST Haider Methneni");

    private static final Set<String> FOURNITURES = Set.of(
            "Conduite en PEHD", "Conduite en Fonte", "Piéces spéciales", "piéces des niches");
    private static final Set<String> OUVRAGES = Set.of(
            "RSP", "RSE 500", "RSE 150 D", "RSE 100 M", "Station de Surpresseur", "Abri Forage",
            "Clôture", "Local GDA", "Réservoir existant");
    private static final Set<String> TRAVAUX = Set.of("Travaux GC", "Travaux Divers", "BC");

    /** Rubrique of a line from its article (alias or rule) and its raw_key (plain(cat)). */
    public static String rubrique(String article, String rawKey) {
        String a = article == null ? "" : article;
        String k = rawKey == null ? "" : rawKey;
        if (k.equals("local gda")) return "Local GDA";
        switch (a) {
            case "Abri forage": return "Abri Forage";
            case "Conduite en fonte": return "Conduite en Fonte";
            case "Pièces spéciales": return "Piéces spéciales";
            case "Pièces des niches": return "piéces des niches";
            case "BC (à préciser)": return "BC";
            case "RSE 150": return "RSE 150 D";
            case "RSE 100": return "RSE 100 M";
            case "Station de surpresseur": return "Station de Surpresseur";
            case "ST Abd El Moula": return "ST Abd Moula";
            case "Travaux divers / GC": return k.contains("gc") ? "Travaux GC" : "Travaux Divers";
            default: return a; // a recap header stays itself; anything else is outside the recap
        }
    }

    /** Famille of a rubrique. */
    public static String famille(String rubrique) {
        String r = rubrique == null ? "" : rubrique;
        if (r.startsWith("ST ")) return "Sous-traitance";
        if (r.startsWith("MO ")) return "Main-d'œuvre";
        if (FOURNITURES.contains(r)) return "Fournitures et conduites";
        if (OUVRAGES.contains(r)) return "Ouvrages";
        if (TRAVAUX.contains(r)) return "Travaux";
        if (r.equals("Charge sur marché")) return "Charges";
        if (r.equals("Alimentation caisse chantier") || r.startsWith("Alimentation (personne) — ")
                || r.equals("Encaissement (décomptes)")) {
            return "Mouvements internes";
        }
        return "Non classé";
    }
}
