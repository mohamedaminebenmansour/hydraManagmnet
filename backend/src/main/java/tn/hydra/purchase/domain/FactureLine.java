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
 * One line of a Facture.
 *
 * <p>Editable only while the parent facture is in {@link FactureStatus#DRAFT}.
 * Once VALIDATED, lines are frozen — service throws {@code FACTURE_NOT_DRAFT}.
 *
 * <p>{@code bc_line_id} / {@code bl_line_id} provide the lineage for the
 * Part 3.2 match (facture qty vs BC ordered vs BL received).
 */
@Entity
@Table(name = "facture_line")
@Getter
@Setter
@NoArgsConstructor
public class FactureLine implements Persistable<UUID> {

    @Id
    @AssignedUuid
    private UUID id;

    @Transient
    private boolean isNew = true;

    @Override public UUID getId() { return id; }
    @Override public boolean isNew() { return isNew; }

    @PostLoad @PostPersist
    void markNotNew() { this.isNew = false; }

    public void markNew() { this.isNew = true; }

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "facture_id", nullable = false)
    private UUID factureId;

    @Column(name = "item_id")
    private UUID itemId;

    @Column(name = "bc_line_id")
    private UUID bcLineId;

    @Column(name = "bl_line_id")
    private UUID blLineId;

    @Column(name = "description", nullable = false)
    private String description;

    @Column(name = "qty", nullable = false, precision = 18, scale = 3)
    private BigDecimal qty;

    @Column(name = "uom")
    private String uom;

    @Column(name = "unit_price", precision = 18, scale = 3)
    private BigDecimal unitPrice;

    @Column(name = "line_ht", precision = 18, scale = 3)
    private BigDecimal lineHt;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
