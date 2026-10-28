package tn.hydra.cash;

/**
 * Mode de règlement d'un mouvement de caisse (vocabulaire Tunisie v1).
 *
 * <p>Architecture rule (STANDING RULE 9 — Tunisia defaults): la caisse Hydra
 * utilise les modes de règlement courants du BTP tunisien / africain. La liste
 * v1 est volontairement restreinte aux 6 cas réellement utilisés sur un
 * chantier ; des variants futurs (ex. MOBILE_MONEY pour l'Afrique de l'Ouest)
 * seront ajoutés par migration additive (ALTER de la CHECK + nouvelle valeur
 * d'enum — jamais par renommage, pour préserver la compatibilité API/DB).
 *
 * <p>Codes = valeurs stables API + DB (stockés tels quels dans la colonne
 * {@code cash_movement.payment_mode}, voir V26 migration). Labels = français
 * pour l'UI. Mirrors the {@code TicketStatus} pattern (label + lenient
 * {@code from} parser).
 *
 * <p>Vocabulaire (aligné sur la comptabilité francophone + recherche web
 * Sep 2026 — "Moyens de Règlement", Odoo petty-cash patterns) :
 * <ul>
 *   <li>{@link #ESPECE}   — Espèces (le cas majoritaire sur un chantier)</li>
 *   <li>{@link #CHEQUE}   — Chèque (fournisseurs, sous-traitants)</li>
 *   <li>{@link #VIREMENT} — Virement bancaire</li>
 *   <li>{@link #VERSEMENT} — Versement (agence / caisse interne)</li>
 *   <li>{@link #MANDAT}   — Mandat (poste / administratif)</li>
 *   <li>{@link #AUTRE}    — Autre (jachère pour cas non couverts)</li>
 * </ul>
 *
 * <p>NULL-safe: {@link #from(String)} returns {@code null} for null/blank
 * input so the DB column stays NULL for legacy rows + for callers that don't
 * specify a mode yet (the frontend "non renseigné" state).
 */
public enum PaymentMode {
    ESPECE("Espèces"),
    CHEQUE("Chèque"),
    VIREMENT("Virement"),
    VERSEMENT("Versement"),
    MANDAT("Mandat"),
    AUTRE("Autre");

    private final String label;

    PaymentMode(String label) { this.label = label; }

    /** French label for the UI (e.g. {@code "Espèces"}, {@code "Chèque"}). */
    public String label() { return label; }

    /**
     * Lenient parser. Returns {@code null} for null / blank input (so the DB
     * column stays NULL — the legacy-safe "non renseigné" state). Throws
     * {@link IllegalArgumentException} for unknown codes so the service can
     * surface a clean French 400 instead of silently coercing.
     */
    public static PaymentMode from(String code) {
        if (code == null || code.isBlank()) return null;
        return PaymentMode.valueOf(code.trim().toUpperCase());
    }
}
