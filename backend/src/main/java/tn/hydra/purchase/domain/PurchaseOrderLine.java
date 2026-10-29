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
 * One line of a Bon de Commande.
 *
 * <p>Editable only while the parent BC is in {@link PurchaseOrderStatus#DRAFT}.
 * Once the BC is {@code CONFIRMED}, lines are frozen — the service refuses
 * any PATCH/DELETE with {@code BC_NOT_DRAFT}.
 *
 * <p>{@code qty_ordered} is always {@code > 0} (DB CHECK constraint in V32).
 */
@Entity
@Table(name = "purchase_order_line")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrderLine implements Persistable<UUID> {

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

    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    /** FK to stock_item.id (must exist in the same org). */
    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    /** Always > 0 (DB CHECK). */
    @Column(name = "qty_ordered", nullable = false, precision = 18, scale = 3)
    private BigDecimal qtyOrdered;

    @Column(name = "uom", nullable = false)
    private String uom;

    /** Optional unit price (TND). */
    @Column(name = "unit_price", precision = 18, scale = 3)
    private BigDecimal unitPrice;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
