package tn.hydra.ticket;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Type of a signalement (ticket). Each type carries a French label, a PrimeIcons
 * icon, a hex colour, a default due delay in days (null for VISA_DOCUMENT,
 * meaning no default), whether resolving it requires an « après » photo, and an
 * ordered map of category codes → French labels.
 *
 * <p>Codes, labels, icons, colours and delays follow the design spec exactly.
 * The frontend reads them only from {@code GET /api/tickets/catalog}.
 */
public enum TicketType {

    PROBLEME("Problème chantier", "pi-exclamation-triangle", "#ea580c", 2, false,
            cats(
                    "MATERIEL_MANQUANT", "Matériel manquant",
                    "PANNE_ENGIN", "Panne d'engin",
                    "EQUIPE_ABSENTE", "Équipe absente",
                    "METEO", "Météo",
                    "ACCES_TERRAIN", "Accès / riverains",
                    "ERREUR_PLAN", "Erreur de plan",
                    "AUTRE", "Autre")),

    RESERVE("Réserve / non-conformité", "pi-flag", "#dc2626", 15, true,
            cats(
                    "MALFACON", "Malfaçon",
                    "NON_FACON", "Non-façon",
                    "ESSAI", "Réserve pour essai",
                    "SECURITE", "Sécurité",
                    "AUTRE", "Autre")),

    DEMANDE("Demande", "pi-question-circle", "#2563eb", 3, false,
            cats(
                    "MATERIEL", "Matériel",
                    "INFORMATION", "Information",
                    "DECISION", "Décision",
                    "AUTRE", "Autre")),

    VISA_DOCUMENT("Visa de document", "pi-file-check", "#7c3aed", null, false,
            cats());

    private final String label;
    private final String icon;
    private final String colour;
    private final Integer defaultDays;          // null = no default delay
    private final boolean requiresAfterPhoto;
    private final Map<String, String> categories; // ordered code -> label

    TicketType(String label, String icon, String colour, Integer defaultDays,
               boolean requiresAfterPhoto, Map<String, String> categories) {
        this.label = label;
        this.icon = icon;
        this.colour = colour;
        this.defaultDays = defaultDays;
        this.requiresAfterPhoto = requiresAfterPhoto;
        this.categories = categories;
    }

    public String label() { return label; }
    public String icon() { return icon; }
    public String colour() { return colour; }
    public Integer defaultDays() { return defaultDays; }
    public boolean requiresAfterPhoto() { return requiresAfterPhoto; }
    public Map<String, String> categories() { return categories; }

    /** True if the category code belongs to this type. */
    public boolean hasCategory(String code) {
        return code != null && categories.containsKey(code.trim().toUpperCase());
    }

    /** French label for a category code, or null if it does not belong here. */
    public String categoryLabel(String code) {
        if (code == null) return null;
        return categories.get(code.trim().toUpperCase());
    }

    /** Resolve a code to its enum value; null/blank defaults to VISA_DOCUMENT. */
    public static TicketType from(String code) {
        if (code == null || code.isBlank()) return VISA_DOCUMENT;
        return TicketType.valueOf(code.trim().toUpperCase());
    }

    /** Build an ordered, unmodifiable category map from code/label pairs. */
    private static Map<String, String> cats(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return Collections.unmodifiableMap(m);
    }
}
