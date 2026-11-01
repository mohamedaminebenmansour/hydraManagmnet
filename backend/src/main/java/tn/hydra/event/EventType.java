package tn.hydra.event;

/** Domain event types (domain_event.type = name()), with the French label shown in the journal. */
public enum EventType {
    PAYMENT_SUBMITTED("Paiement soumis"),
    CASH_MOVEMENT_POSTED("Mouvement de caisse comptabilisé"),
    PROCESS_STEP_COMPLETED("Étape de processus validée"),
    PURCHASE_ORDER_CREATED("Bon de commande créé"),
    DELIVERY_RECEIVED("Livraison réceptionnée"),
    INVOICE_RECORDED("Facture enregistrée"),
    BORDEREAU_IMPORTED("Bordereau importé"),
    BORDEREAU_ACTIVATED("Version de bordereau activée"),
    DOCUMENT_UPLOADED("Document téléversé");

    private final String label;

    EventType(String label) { this.label = label; }

    public String label() { return label; }

    /** Label of a stored type; the raw code if it is not (or no longer) a known type. */
    public static String labelOf(String type) {
        for (EventType t : values()) if (t.name().equals(type)) return t.label;
        return type;
    }
}
