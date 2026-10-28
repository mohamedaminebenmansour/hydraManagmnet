package tn.hydra.cash;

/**
 * Nature comptable d'un mouvement de caisse (vocabulaire Tunisie v1).
 *
 * <p>Architecture rule (STANDING RULE 9 — Tunisia defaults): sur un chantier
 * BTP tunisien, l'état de caisse Excel classe chaque mouvement dans une nature
 * standard. Hydra v1 couvre les 9 natures les plus courantes ; des natures
 * futures (ex. LOCATION_ENGINS, ASSURANCE) seront ajoutées par migration
 * additive (ALTER de la CHECK + nouvelle valeur d'enum).
 *
 * <p>Codes = valeurs stables API + DB (stockés tels quels dans la colonne
 * {@code cash_movement.nature}, voir V26 migration). Labels = français pour
 * l'UI.
 *
 * <p>Vocabulaire :
 * <ul>
 *   <li>{@link #ALIM_CAISSE}    — Alimentation caisse (versement initial / replenish)</li>
 *   <li>{@link #SOUS_TRAITANCE} — Sous-traitance (paiement ST)</li>
 *   <li>{@link #MATERIEL}       — Matériel (achat matériaux / fournitures)</li>
 *   <li>{@link #TRANSPORT}      — Transport (camionnage / frais déplacement)</li>
 *   <li>{@link #MAIN_OEUVRE}    — Main d'œuvre (salaires chantier / intérim)</li>
 *   <li>{@link #LOCATION}       — Location (engins / matériel loué)</li>
 *   <li>{@link #LABO}           — Laboratoire (essais / contrôles)</li>
 *   <li>{@link #CARBURANT}      — Carburant (gas-oil / essence)</li>
 *   <li>{@link #DIVERS}         — Divers (jachère pour cas non couverts)</li>
 * </ul>
 *
 * <p>NULL-safe: {@link #from(String)} returns {@code null} for null/blank input.
 */
public enum MovementNature {
    ALIM_CAISSE("Alimentation caisse"),
    SOUS_TRAITANCE("Sous-traitance"),
    MATERIEL("Matériel"),
    TRANSPORT("Transport"),
    MAIN_OEUVRE("Main d'œuvre"),
    LOCATION("Location"),
    LABO("Laboratoire"),
    CARBURANT("Carburant"),
    DIVERS("Divers");

    private final String label;

    MovementNature(String label) { this.label = label; }

    /** French label for the UI (e.g. {@code "Sous-traitance"}, {@code "Matériel"}). */
    public String label() { return label; }

    /**
     * Lenient parser. Returns {@code null} for null / blank input. Throws
     * {@link IllegalArgumentException} for unknown codes.
     */
    public static MovementNature from(String code) {
        if (code == null || code.isBlank()) return null;
        return MovementNature.valueOf(code.trim().toUpperCase());
    }
}
