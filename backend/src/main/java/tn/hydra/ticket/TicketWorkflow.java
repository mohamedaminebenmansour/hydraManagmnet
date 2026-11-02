package tn.hydra.ticket;

import java.util.Set;

/**
 * Allowed status transitions per ticket type.
 *
 * <p>VISA_DOCUMENT keeps the existing {@link TicketStatus#allowedTransitions()}
 * workflow (DRAFT → IN_REVIEW → APPROVED → IFC → AS_BUILT) unchanged. The three
 * new types (PROBLEME, RESERVE, DEMANDE) share one universal lifecycle:
 * <pre>
 *   OPEN → IN_PROGRESS → RESOLVED → CLOSED
 *                  ↘ CANCELLED          ↘ IN_PROGRESS (reopen)
 *   OPEN → CANCELLED
 * </pre>
 *
 * <p>Role rules (who may move a status), resolution notes, « après » photos and
 * cancel comments are enforced in a later task; this class only owns the graph
 * of allowed next statuses.
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
}
