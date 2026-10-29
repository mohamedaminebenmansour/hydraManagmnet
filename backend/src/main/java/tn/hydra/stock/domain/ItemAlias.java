package tn.hydra.stock.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;
import tn.hydra.common.id.AssignedUuid;

import java.time.Instant;
import java.util.UUID;

/**
 * Supplier-side alias for a {@link StockItem}.
 *
 * <p>The same bag of ciment is called "Ciment CJS II-32.5" by the chef de
 * chantier, "CIM/25/BIZ" by the supplier Cimentex, and "le gris 25" by the
 * chef d'équipe. Without aliases, the office creates duplicate stock_item
 * rows and the balance view fragments.
 *
 * <p>Unique on {@code (org_id, lower(alias))} — case-insensitive.
 *
 * <p>Slice 1: read-only lookup, no fuzzy match. The ItemController can use
 * this to resolve a typed alias string → item id when the chef types a
 * supplier code.
 */
@Entity
@Table(name = "item_alias")
@Getter
@Setter
@NoArgsConstructor
public class ItemAlias implements Persistable<UUID> {

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

    @Column(name = "item_id", nullable = false)
    private UUID itemId;

    @Column(name = "alias", nullable = false)
    private String alias;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "created_by")
    private UUID createdBy;
}
