package tn.hydra.stock.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;
import tn.hydra.common.id.AssignedUuid;
import tn.hydra.document.domain.Project;

import java.time.Instant;
import java.util.UUID;

/**
 * Where material lives — a "place" in the virtual stock model.
 *
 * <p>Two types in Slice 1:
 * <ul>
 *   <li>{@link StockLocationType#CENTRAL} — org-level dépôt.
 *       {@code project_id} is NULL. Typically ONE per org.</li>
 *   <li>{@link StockLocationType#SITE} — a chantier location.
 *       {@code project_id} is NOT NULL.</li>
 * </ul>
 *
 * <p><b>CENTRAL visibility policy (Slice 1 decision)</b>:
 * "CENTRAL is visible to every member of any project in the org." Enforced
 * in the service layer (the caller must have at least one project_member row
 * in the org, OR be the owner, OR have STOCK_VIEW directly).
 *
 * <p><b>Soft-delete</b> via {@code active = false}. Locations referenced by
 * a stock_move cannot be hard-deleted (the FK + the move's append-only
 * trigger forbid it).
 */
@Entity
@Table(name = "stock_location")
@Getter
@Setter
@NoArgsConstructor
public class StockLocation implements Persistable<UUID> {

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
    @Column(name = "type", nullable = false)
    private StockLocationType type;

    /**
     * Project FK — NULL when {@code type = CENTRAL}, NOT NULL when
     * {@code type = SITE}. The DB CHECK constraint enforces this.
     */
    @Column(name = "project_id")
    private UUID projectId;

    /** Optional human code — "DEPOT-CENTRAL", "PRJ-CARTHAGE-SITE". */
    @Column(name = "code")
    private String code;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;
}
