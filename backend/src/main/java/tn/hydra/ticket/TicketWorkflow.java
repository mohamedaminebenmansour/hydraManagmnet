package tn.hydra.ticket;

import java.util.Set;

/**
 * Allowed status transitions per ticket type, and the target status of each
 * signalement action.
 *
 * <p>VISA_DOCUMENT keeps the existing {@link TicketStatus#allowedTransitions()}
 * workflow (DRAFT → IN_REVIEW → APPROVED → IFC → AS_BUILT), driven by
 * {@code POST /api/tickets/{id}/status}. The three signalement types
 * (PROBLEME, RESERVE, DEMANDE) share one universal lifecycle driven by named
 * actions:
 * <pre>
 *   OPEN --start--> IN_PROGRESS --resolve--> RESOLVED --verify--> CLOSED
 *                  ↘ cancel ↘                 ↘ reopen ↘
 *   OPEN --cancel--> CANCELLED                                   ↘ IN_PROGRESS
 * </pre>
 *
 * <p>Who may do what, resolution notes, « après » photos and cancel reasons are
 * enforced by {@link TicketService} (it has the auth + DB context); this class
 * only owns the graph of allowed next statuses and the action → target mapping.
 */
public final class TicketWorkflow {

    private TicketWorkflow() {}

    /** Allowed next statuses from (type, current). Empty when terminal. */
    public static Set<TicketStatus> next(TicketType type, TicketStatus current) {
        if (type == TicketType.VISA_DOCUMENT) {
            return current.allowedTransitions();
        }
        return switch (current) {
            case OPEN        -> Set.of(TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED, TicketStatus.CANCELLED);
            case IN_PROGRESS -> Set.of(TicketStatus.RESOLVED, TicketStatus.CANCELLED);
            case RESOLVED    -> Set.of(TicketStatus.CLOSED, TicketStatus.IN_PROGRESS); // verify-or-reopen
            case CLOSED      -> Set.of();
            case CANCELLED   -> Set.of();
            // a new-type ticket should never sit in a VISA status; be defensive
            default          -> Set.of();
        };
    }

    /** Initial status for a freshly created ticket of the given type. */
    public static TicketStatus initialStatus(TicketType type) {
        return type == TicketType.VISA_DOCUMENT ? TicketStatus.DRAFT : TicketStatus.OPEN;
    }

    /**
     * Target status for a signalement action (PROBLEME / RESERVE / DEMANDE only),
     * or {@code null} if the action does not apply to the current status. VISA_DOCUMENT
     * uses {@link #next(TicketType, TicketStatus)} via {@code POST /{id}/status}, not
     * these actions, so it always returns {@code null} here.
     */
    public static TicketStatus targetForAction(String action, TicketType type, TicketStatus current) {
        if (type == TicketType.VISA_DOCUMENT) return null;
        return switch (action) {
            case "START"   -> current == TicketStatus.OPEN ? TicketStatus.IN_PROGRESS : null;
            case "RESOLVE" -> (current == TicketStatus.OPEN || current == TicketStatus.IN_PROGRESS)
                              ? TicketStatus.RESOLVED : null;
            case "VERIFY"  -> current == TicketStatus.RESOLVED ? TicketStatus.CLOSED : null;
            case "REOPEN"  -> current == TicketStatus.RESOLVED ? TicketStatus.IN_PROGRESS : null;
            case "CANCEL"  -> (current == TicketStatus.OPEN || current == TicketStatus.IN_PROGRESS)
                              ? TicketStatus.CANCELLED : null;
            default -> null;
        };
    }
}
