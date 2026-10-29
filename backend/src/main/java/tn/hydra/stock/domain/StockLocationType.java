package tn.hydra.stock.domain;

/**
 * Type of a stock location.
 *
 * <p>Slice 1 has only two values:
 * <ul>
 *   <li>{@link #CENTRAL} — the org-wide dépôt. {@code project_id} is NULL.</li>
 *   <li>{@link #SITE} — a chantier location. {@code project_id} is NOT NULL.</li>
 * </ul>
 *
 * <p>Future slices may add VEHICLE (a camion with its own rolling stock) and
 * VIRTUAL (in-transit, lost-and-found, write-off sink) — but Slice 1 keeps
 * the model minimal.
 */
public enum StockLocationType {
    CENTRAL,
    SITE
}
