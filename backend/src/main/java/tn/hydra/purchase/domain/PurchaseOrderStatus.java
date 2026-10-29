package tn.hydra.purchase.domain;

/**
 * Bon de Commande lifecycle (Part 2.1).
 *
 * <pre>
 *   DRAFT     → confirm()  → CONFIRMED   (lines frozen)
 *   DRAFT     → cancel()   → CANCELLED
 *   CONFIRMED → cancel()   → CANCELLED   (with reason)
 *   CONFIRMED → (CLOSED is set by BL reception — Part 2.2, not in 2.1)
 *   CANCELLED → terminal
 * </pre>
 */
public enum PurchaseOrderStatus {
    DRAFT,
    CONFIRMED,
    CANCELLED,
    CLOSED
}
