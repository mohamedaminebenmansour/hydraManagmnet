package tn.hydra.stock.domain;

/**
 * Type of a stock move — the only field that determines how {@code qty} is
 * signed against {@code from_location_id} and {@code to_location_id}.
 *
 * <p><b>Signing convention</b> (computed by the service; the DB stores
 * {@code qty > 0} always, never negative):
 * <pre>
 *   IN          → +qty at to_location_id
 *   OUT         → -qty at from_location_id
 *   TRANSFER    → -qty at from, +qty at to
 *   DIRECT_USE  → -qty at from  (consumed immediately, no magasin transit;
 *                                used for the bon-de-carburant case)
 *   ADJUST      → +qty at from_location_id when qty > 0 (found more),
 *                 -qty at from_location_id when qty < 0 (missing).
 *                 The service accepts both signs for ADJUST only;
 *                 the DB CHECK (qty > 0) is loosened by storing abs(qty)
 *                 and using a separate "direction" param. See StockService.
 * </pre>
 *
 * <p><b>Append-only</b>: every move is final once committed. Corrections
 * = a new opposite move (mirror of {@code cash_movement} POSTED → VOID).
 *
 * <p>Future move types (deferred to later slices):
 * <ul>
 *   <li>{@code RESERVED} — soft allocation (Slice 1.5)</li>
 *   <li>{@code BACKORDER} — split delivery (Slice 2)</li>
 *   <li>{@code RETURN} — supplier retour / avoir (Slice 2)</li>
 * </ul>
 */
public enum StockMoveType {
    IN,
    OUT,
    TRANSFER,
    ADJUST,
    DIRECT_USE
}
