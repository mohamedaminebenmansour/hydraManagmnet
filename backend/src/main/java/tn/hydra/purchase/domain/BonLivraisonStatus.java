package tn.hydra.purchase.domain;

/**
 * Bon de Livraison (BL) lifecycle (Part 2.2).
 *
 * <pre>
 *   DRAFT     → post()    → POSTED    (fires N stock IN moves in one transaction; lines frozen)
 *   DRAFT     → cancel()  → CANCELLED (no stock moves fired)
 *   POSTED    → terminal  (no cancel in v1 — reverse via stock_move reversal instead)
 *   CANCELLED → terminal
 * </pre>
 */
public enum BonLivraisonStatus {
    DRAFT,
    POSTED,
    CANCELLED
}
