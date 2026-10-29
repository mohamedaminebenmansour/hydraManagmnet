package tn.hydra.purchase.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;
import tn.hydra.common.id.AssignedUuid;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Bon de Commande (purchase order) header.
 *
 * <p>Belongs to a project + org. Optional supplier ({@code party.id}) and
 * optional BC PDF document (app-validated, not a DB FK — matches the
 * {@code stock_move.document_id} convention).
 *
 * <p>Status transitions are enforced in {@code PurchaseOrderService} (the
 * DB CHECK only validates the string is one of the 4 values).
 *
 * <p>{@code bc_line_id} on {@code stock_move} is NOT added in 2.1 —
 * deferred to 2.2 when the BL→IN lineage lands.
 */
@Entity
@Table(name = "purchase_order")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrder implements Persistable<UUID> {

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

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    /** Optional supplier (FK party.id). Null = ad-hoc / unnamed supplier. */
    @Column(name = "supplier_party_id")
    private UUID supplierPartyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private PurchaseOrderStatus status;

    /** Human-readable reference, e.g. "BC-2026-001". Optional. */
    @Column(name = "reference")
    private String reference;

    @Column(name = "ordered_at", nullable = false)
    private LocalDate orderedAt;

    @Column(name = "expected_at")
    private LocalDate expectedAt;

    @Column(name = "notes")
    private String notes;

    /** Optional BC PDF document (app-validated, no DB FK — same as stock_move.document_id). */
    @Column(name = "document_id")
    private UUID documentId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
