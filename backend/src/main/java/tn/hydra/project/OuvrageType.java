package tn.hydra.project;

import java.util.List;
import java.util.Map;

/**
 * Ouvrage types — the structured objects a chantier is made of.
 *
 * The allowed characteristic keys and their kinds are taken verbatim from
 * docs/BORDEREAU-ANALYSIS.md §5 (the file grounded in the 8 bordereaux
 * analysed on disk). The {@link Kind} enum tells the service how to validate
 * a value submitted by the user.
 *
 * <p>CONDUITE is special: it holds a {@code troncons} array of
 * {@code {de_mm, pn_bar, longueur_m}} objects, so a single conduite ouvrage
 * can describe a network made of several diameters — exactly as the
 * bordereaux do (one fourniture line per diameter, but the site thinks per
 * network).
 *
 * <p>COMMUN has no characteristics (installation, repli, essais globaux,
 * raccordement au réseau existant, sécurité) — see BORDEREAU-ANALYSIS §5.
 */
public enum OuvrageType {
    RESERVOIR,
    CONDUITE,
    STATION_POMPAGE,
    FORAGE,
    OUVRAGE_ANNEXE,
    BRANCHEMENTS,
    ASSAINISSEMENT,
    COMMUN,
    AUTRE;

    /**
     * Kind of a characteristic value.
     * <ul>
     *   <li>{@code NUMBER_WITH_UNIT} — a non-null number; the unit is implied
     *       by the key name (e.g. {@code capacite_m3} is in m³,
     *       {@code longueur_m} is in metres, {@code pn_bar} is in bar).</li>
     *   <li>{@code INTEGER} — a non-null integer (counts, dosages, diameters).</li>
     *   <li>{@code BOOLEAN} — true/false.</li>
     *   <li>{@code CHOICE} — a string from a closed list of allowed values.</li>
     *   <li>{@code TEXT} — any non-blank string.</li>
     *   <li>{@code TRONCONS_ARRAY} — a JSON array of
     *       {@code {de_mm: integer, pn_bar: integer, longueur_m: number}}.
     *       Only CONDUITE uses this kind, on the key {@code troncons}.</li>
     * </ul>
     */
    public enum Kind {
        NUMBER_WITH_UNIT, INTEGER, BOOLEAN, CHOICE, TEXT, TRONCONS_ARRAY
    }

    /** A characteristic key spec: its kind and, when {@link Kind#CHOICE}, the allowed values. */
    public record Spec(Kind kind, List<String> choices) {
        public static Spec number() { return new Spec(Kind.NUMBER_WITH_UNIT, List.of()); }
        public static Spec integer() { return new Spec(Kind.INTEGER, List.of()); }
        public static Spec bool() { return new Spec(Kind.BOOLEAN, List.of()); }
        public static Spec text() { return new Spec(Kind.TEXT, List.of()); }
        public static Spec choice(String... values) { return new Spec(Kind.CHOICE, List.of(values)); }
        public static Spec troncons() { return new Spec(Kind.TRONCONS_ARRAY, List.of()); }
    }

    /**
     * Allowed characteristic keys for this ouvrage type, with their kind.
     * Empty map for COMMUN (no characteristics) and AUTRE (free-form).
     *
     * <p>Keys and choice values come from BORDEREAU-ANALYSIS.md §5. Do not
     * rename a key without updating the analysis document first — the names
     * are the contract between the import, the API and the UI.
     */
    public Map<String, Spec> characteristicsSpec() {
        return switch (this) {
            case CONDUITE -> Map.of(
                    "usage",             Spec.choice("ADDUCTION", "REFOULEMENT", "DISTRIBUTION"),
                    "materiau",          Spec.choice("PEHD", "FONTE", "PVC", "ACIER_GALVANISE", "AC"),
                    "de_mm",             Spec.integer(),
                    "dn_mm",             Spec.integer(),
                    "pn_bar",            Spec.choice("10", "16"),
                    "assemblage",        Spec.choice("ELECTROSOUDABLE", "BOUT_A_BOUT", "BRIDE", "JOINT_CAOUTCHOUC"),
                    "longueur_m",        Spec.number(),
                    "largeur_tranchee_m",Spec.number(),
                    // CONDUITE is the only type that holds a troncons array.
                    "troncons",          Spec.troncons());
            case RESERVOIR -> Map.of(
                    "sous_type",            Spec.choice("SEMI_ENTERRE", "SURELEVE", "SUR_PILIERS",
                                                        "BRISE_CHARGE", "BACHE_REPRISE",
                                                        "CUVE_SUR_SUPPORT", "ENTERRE"),
                    "capacite_m3",          Spec.number(),
                    "materiau",             Spec.choice("BETON_ARME", "RESINE", "MACONNERIE"),
                    "dosage_beton_kg_m3",   Spec.integer(),
                    "revetement_interieur", Spec.choice("ENDUIT_ETANCHE", "CUVELAGE",
                                                        "CARRELAGE", "ENDUIT_HYDROFUGE"),
                    "dn_arrivee",           Spec.integer(),
                    "dn_depart",            Spec.integer(),
                    "dn_trop_plein",        Spec.integer(),
                    "dn_vidange",           Spec.integer(),
                    "cloture",              Spec.bool());
            case STATION_POMPAGE -> Map.of(
                    "sous_type",       Spec.choice("STATION_REPRISE", "POMPAGE_FORAGE"),
                    "debit_m3h",       Spec.number(),
                    "hmt_m",            Spec.number(),
                    "nb_pompes",        Spec.integer(),
                    "puissance_kw",    Spec.number(),
                    "energie",         Spec.choice("RESEAU_ELECTRIQUE", "SOLAIRE"),
                    "chloration",      Spec.bool(),
                    "local_technique", Spec.bool());
            case FORAGE -> Map.of(
                    "type",                  Spec.choice("FORAGE_MECANIQUE", "FORAGE_MANUEL", "PUITS"),
                    "profondeur_m",          Spec.number(),
                    "diametre_tubage_mm",    Spec.number(),
                    "materiau_tubage",       Spec.choice("PVC", "ACIER"),
                    "debit_exploitation_m3h",Spec.number());
            case OUVRAGE_ANNEXE -> Map.of(
                    "type",       Spec.choice("VENTOUSE", "VIDANGE", "SECTIONNEMENT",
                                              "PIQUAGE", "REDUCTEUR_PRESSION",
                                              "BRISE_CHARGE_EN_LIGNE"),
                    "modele",     Spec.text(),
                    "dimensions", Spec.text(),
                    "pk",         Spec.number());
            case BRANCHEMENTS -> Map.of(
                    "nb_bornes_fontaines",          Spec.integer(),
                    "nb_potences",                  Spec.integer(),
                    "nb_branchements_publics",      Spec.integer(),
                    "nb_branchements_particuliers", Spec.integer());
            // ASSAINISSEMENT has no documented characteristics in the analysed
            // bordereaux — keep the map empty so any key is rejected, until
            // the company provides a reference bordereau for assainissement.
            case ASSAINISSEMENT -> Map.of();
            // COMMUN: installation et repli, plans d'exécution, essais globaux,
            // raccordement au réseau existant, sécurité. No characteristics
            // (BORDEREAU-ANALYSIS §5).
            case COMMUN -> Map.of();
            // AUTRE: free-form, accept any key (no schema to enforce yet).
            case AUTRE -> Map.of();
        };
    }

    /** True when this type accepts the given characteristic key. */
    public boolean acceptsKey(String key) {
        return characteristicsSpec().containsKey(key);
    }
}
