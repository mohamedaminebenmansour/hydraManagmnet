package tn.hydra.purchase.domain;

/**
 * Facture (supplier invoice) lifecycle (Part 3.1).
 *
 * <pre>
 *   DRAFT      → validate() → VALIDATED   (lines frozen; requires ≥1 line)
 *   DRAFT      → cancel()   → CANCELLED
 *   VALIDATED  → cancel()   → CANCELLED   (reason stored in audit details)
 *   CANCELLED  → terminal  (excluded from match aggregates)
 * </pre>
 */
public enum FactureStatus {
    DRAFT,
    VALIDATED,
    CANCELLED
}
