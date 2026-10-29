package tn.hydra.stock.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;
import tn.hydra.common.id.AssignedUuid;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Append-only stock movement — <b>the keystone</b>.
 *
 * <p>Source of truth for "what material is where". Balances are ALWAYS
 * computed: {@code balance(item, location) = SUM(signed_qty)} over the moves
 * affecting that location. Never UPDATE a row to fix a mistake — append
 * an opposite move (mirror of {@code cash_movement} POSTED → VOID).
 *
 * <p>The DB trigger {@code stock_move_append_only} refuses any UPDATE or
 * DELETE on this table — defence-in-depth on top of the service layer.
 *
 * <p>See {@link StockMoveType} for the signing convention.
 *
 * <p><b>Optional cross-domain links</b> — kept nullable in Slice 1, wired by
 * later slices:
 * <ul>
 *   <li>{@code documentId} — points to a {@code document.id}. The service
 *       validates the document exists in the same org before linking (the
 *       DB FK is intentionally omitted because the {@code document} table
 *       had RLS quirks — see V16).</li>
 *   <li>{@code projectId} — denormalized from the location for fast
 *       per-chantier queries. NULL when both ends are CENTRAL.</li>
 *   <li>{@code partyId} — supplier / sous-traitant. FK to {@code party.id}.</li>
 *   <li>{@code cashMovementId} — the money side, when this move was triggered
 *       by or triggers a cash event. NULL for TRANSFER (no money moves) and
 *       ADJUST. The DB FK is intentionally omitted (cash_movement has its
 *       own append-only trigger).</li>
 * </ul>
 *
 * <p><b>Negative-stock policy (Slice 1)</b>: HARD BLOCK. The service rejects
 * a move that would push a location's balance below zero with
 * {@code BusinessRuleException("STOCK_NEGATIVE_BALANCE", ...)}.
 */
@Entity
@Table(name = "stock_move")
@Getter
@Setter
@NoArgsConstructor
public class StockMove implements Persistable<UUID> {

    @Id
    @AssignedUuid
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Override
    public UUID getId() { return id; }

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }

    public void markNew() { this.isNew = true; }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @Column(name = "move_type", nullable = false)
    private StockMoveType moveType;

    /** Always positive. Sign is derived from {@link #moveType}. */
    @Column(name = "qty", nullable = false, precision = 18, scale = 3)
    private BigDecimal qty;

    @Column(name = "uom", nullable = false)
    private String uom;

    @Column(name = "move_date", nullable = false)
    private LocalDate moveDate;

    /** Source location — NULL for IN; NOT NULL for OUT/TRANSFER/ADJUST/DIRECT_USE. */
    @Column(name = "from_location_id")
    private UUID fromLocationId;

    /** Destination location — NULL for OUT/ADJUST/DIRECT_USE; NOT NULL for IN/TRANSFER. */
    @Column(name = "to_location_id")
    private UUID toLocationId;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    // ---- Optional cross-domain links (nullable by design) ----

    /** Linked document (BL photo, BC scan, facture PDF). App-validated. */
    @Column(name = "document_id")
    private UUID documentId;

    /** Denormalized project for fast per-chantier queries. */
    @Column(name = "project_id")
    private UUID projectId;

    /** Supplier / sous-traitant (FK to party.id). */
    @Column(name = "party_id")
    private UUID partyId;

    /** Cash-side link (intentionally not a DB FK). */
    @Column(name = "cash_movement_id")
    private UUID cashMovementId;

    @Column(name = "note")
    private String note;

    @Column(name = "created_by", updatable = false, nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
}
