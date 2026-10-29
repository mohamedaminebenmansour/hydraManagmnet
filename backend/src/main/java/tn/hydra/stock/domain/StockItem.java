package tn.hydra.stock.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.springframework.data.domain.Persistable;
import tn.hydra.common.id.AssignedUuid;
import tn.hydra.document.domain.Auditable;

import java.time.Instant;
import java.util.UUID;

/**
 * Material catalogue entry — Slice 1 minimal model.
 *
 * <p>One row per material in the org. The {@code code} is optional
 * (chef-de-chantier-created items on the fly may have a NULL code).
 * The unique index on {@code (org_id, code) WHERE code IS NOT NULL} allows
 * multiple uncoded items to coexist.
 *
 * <p><b>Soft-delete via {@code active = false}</b> — never DELETE a row that
 * has been referenced by a stock_move (FK would block; the trigger forbids
 * deleting a move anyway). Deactivating hides the item from the picker UI
 * but preserves historical balances.
 *
 * <p>Reuses {@link Auditable} for {@code created_by} / {@code created_at} /
 * {@code updated_at}, populated by Spring Data JPA auditing
 * (see {@code AuditingConfig} + {@code AuditorAwareImpl}).
 *
 * <p>Out of scope (deferred to a future Product master):
 * family, diameter, length, weight, value_class, valuation_method, lot_required.
 */
@Entity
@Table(name = "stock_item")
@Getter
@Setter
@NoArgsConstructor
public class StockItem extends Auditable implements Persistable<UUID> {

    @Id
    @AssignedUuid
    private UUID id;

    /** New-instance flag for Spring Data — see {@link tn.hydra.document.domain.Document}. */
    @Transient
    private boolean isNew = true;

    @Override
    public UUID getId() { return id; }

    @Override
    public boolean isNew() { return isNew; }

    @PostLoad
    @PostPersist
    void markNotNew() { this.isNew = false; }

    /** Call when creating a new StockItem with a pre-assigned id before save. */
    public void markNew() { this.isNew = true; }

    /** Tenant scope (denormalized for fast org-wide queries). */
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    /** Optional supplier/internal code. NULL allowed. */
    @Column(name = "code")
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    /** Unit of measure — "sac", "m3", "m", "kg", "L", "U". Free-text in v1. */
    @Column(name = "uom", nullable = false)
    private String uom;

    /** Soft-delete flag — false hides from picker UI; rows kept for audit. */
    @Column(name = "active", nullable = false)
    private boolean active = true;
}
