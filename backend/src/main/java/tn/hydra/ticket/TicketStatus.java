package tn.hydra.ticket;

import java.util.Set;

/**
 * Statuses carried by a Ticket.
 *
 * <p>The five original values drive the document transmittal / RFI workflow
 * (DRAFT → IN_REVIEW → APPROVED → IFC → AS_BUILT) used by VISA_DOCUMENT
 * tickets. The five new values drive the universal signalement lifecycle
 * (OPEN → IN_PROGRESS → RESOLVED → CLOSED, + CANCELLED) used by PROBLEME,
 * RESERVE and DEMANDE. The allowed transitions for the new lifecycle are owned
 * by {@link TicketWorkflow}; {@link #allowedTransitions()} keeps describing
 * only the VISA_DOCUMENT graph.
 *
 * <p>The codes are stable API values; labels are French for the UI.
 */
public enum TicketStatus {
    DRAFT("Brouillon"),
    IN_REVIEW("En revue"),
    APPROVED("Approuvé"),
    IFC("Bon pour exécution"),
    AS_BUILT("As-built"),
    // Signalement lifecycle (PROBLEME / RESERVE / DEMANDE)
    OPEN("Ouvert"),
    IN_PROGRESS("En cours"),
    RESOLVED("Résolu — à vérifier"),
    CLOSED("Clos"),
    CANCELLED("Annulé");

    private final String label;

    TicketStatus(String label) { this.label = label; }

    public String label() { return label; }

    /** Allowed transitions from a given status (forward-only workflow). */
    public Set<TicketStatus> allowedTransitions() {
        return switch (this) {
            case DRAFT    -> Set.of(IN_REVIEW);
            case IN_REVIEW-> Set.of(APPROVED, DRAFT);
            case APPROVED -> Set.of(IFC, IN_REVIEW);
            case IFC      -> Set.of(AS_BUILT);
            case AS_BUILT -> Set.of();
            // new-type statuses are routed through TicketWorkflow.next(type, current);
            // allowedTransitions() is never called for them, but the switch must
            // stay exhaustive, so default to an empty set.
            default       -> Set.of();
        };
    }

    public static TicketStatus from(String code) {
        if (code == null || code.isBlank()) return DRAFT;
        return TicketStatus.valueOf(code.trim().toUpperCase());
    }
}
