package tn.hydra.purchase.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;
import tn.hydra.common.id.AssignedUuid;

import java.time.Instant;
import java.util.UUID;

/**
 * Bon de Livraison (BL) header.
 *
 * <p>Represents a goods receipt. When POSTED, fires one stock_move IN per line
 * (in a single transaction) — stamping {@code stock_move.bc_line_id} + {@code bl_line_id}.
 *
 * <p>{@code bcId} is nullable to allow DIRECT receive (no BC) — documented but
 * the primary product path is BL from a CONFIRMED BC.
 *
 * <p>{@code locationId} is where goods physically arrive (SITE or CENTRAL).
 * The service validates it the same way as {@code stock_move.toLocationId}.
 */
@Entity
@Table(name = "bon_livraison")
@Getter
@Setter
@NoArgsConstructor
public class BonLivraison implements Persistable<UUID> {

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

    /** Optional BC this BL closes. Null = DIRECT receive (documented). */
    @Column(name = "bc_id")
    private UUID bcId;

    @Column(name = "supplier_party_id")
    private UUID supplierPartyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private BonLivraisonStatus status;

    @Column(name = "reference")
    private String reference;

    /** Set when POSTED — the stock-IN moment. */
    @Column(name = "received_at")
    private Instant receivedAt;

    /** Where goods arrive (SITE or CENTRAL). Validated by the service. */
    @Column(name = "location_id", nullable = false)
    private UUID locationId;

    /** Optional BL PDF (app-validated, no DB FK). */
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
