package tn.hydra.purchase.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;
import tn.hydra.common.id.AssignedUuid;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One line of a Bon de Livraison.
 *
 * <p>Editable only while the parent BL is in {@link BonLivraisonStatus#DRAFT}.
 * When the BL is POSTED, each line triggers one stock_move IN with
 * {@code bc_line_id = this.bcLineId} + {@code bl_line_id = this.id}.
 *
 * <p>Hard cap (v1): SUM(qty_received) per {@code bc_line_id} across all POSTED BLs
 * must be ≤ the BC line's {@code qty_ordered}. The service enforces this
 * (returns {@code 409 BL_OVER_RECEIVE}).
 */
@Entity
@Table(name = "bon_livraison_line")
@Getter
@Setter
@NoArgsConstructor
public class BonLivraisonLine implements Persistable<UUID> {

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

    @Column(name = "bon_livraison_id", nullable = false)
    private UUID bonLivraisonId;

    /** FK to purchase_order_line.id. Nullable for DIRECT receive (no BC). */
    @Column(name = "bc_line_id")
    private UUID bcLineId;

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    /** Always > 0 (DB CHECK). */
    @Column(name = "qty_received", nullable = false, precision = 18, scale = 3)
    private BigDecimal qtyReceived;

    @Column(name = "uom", nullable = false)
    private String uom;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
