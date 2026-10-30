package tn.hydra.purchase.domain;

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
 * Facture (supplier invoice) header.
 *
 * <p>Links optionally to a BC ({@code bc_id}) and/or BL ({@code bl_id}).
 * Free facture (both null) is allowed — prefer linking when available.
 *
 * <p>Supplier consistency: if both facture.supplier_party_id AND bc.supplier_party_id
 * are set and differ → service throws {@code FACTURE_SUPPLIER_MISMATCH} (409).
 *
 * <p>If both bc_id and bl_id set: BL must reference the same BC → else
 * {@code FACTURE_BL_BC_MISMATCH} (409).
 *
 * <p>Amounts (amount_ht, amount_tva, amount_ttc) are optional header totals.
 * Lines carry their own line_ht; the header can be a manual override or a
 * computed sum (computed in future match, not stored as a DB trigger).
 */
@Entity
@Table(name = "facture")
@Getter
@Setter
@NoArgsConstructor
public class Facture implements Persistable<UUID> {

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

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "supplier_party_id")
    private UUID supplierPartyId;

    @Column(name = "bc_id")
    private UUID bcId;

    @Column(name = "bl_id")
    private UUID blId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private FactureStatus status;

    @Column(name = "reference")
    private String reference;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "amount_ht", precision = 18, scale = 3)
    private BigDecimal amountHt;

    @Column(name = "amount_tva", precision = 18, scale = 3)
    private BigDecimal amountTva;

    @Column(name = "amount_ttc", precision = 18, scale = 3)
    private BigDecimal amountTtc;

    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "notes")
    private String notes;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
