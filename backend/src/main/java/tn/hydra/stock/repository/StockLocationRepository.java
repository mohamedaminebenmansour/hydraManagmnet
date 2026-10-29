package tn.hydra.stock.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.hydra.stock.domain.StockLocation;
import tn.hydra.stock.domain.StockLocationType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA repository for {@link StockLocation}.
 *
 * <p>The "list locations for caller" query is the security-sensitive one —
 * it must filter by org AND (when the caller is not the owner) restrict
 * to either CENTRAL or SITE locations for projects the caller is a member
 * of. That logic lives in the service layer; this repo exposes the
 * building blocks.
 */
public interface StockLocationRepository extends JpaRepository<StockLocation, UUID> {

    @Query("""
            select l from StockLocation l
            where l.id = :id and l.orgId = :orgId
            """)
    Optional<StockLocation> findByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    /** All active locations in the org (the owner's view). */
    @Query("""
            select l from StockLocation l
            where l.orgId = :orgId and l.active = true
            order by l.type asc, l.name asc
            """)
    List<StockLocation> findActiveByOrgId(@Param("orgId") UUID orgId);

    /** The unique active CENTRAL location for an org (one per org). */
    @Query("""
            select l from StockLocation l
            where l.orgId = :orgId and l.type = tn.hydra.stock.domain.StockLocationType.CENTRAL
              and l.active = true
            """)
    Optional<StockLocation> findCentralByOrgId(@Param("orgId") UUID orgId);

    /** SITE locations for a specific project (the chef de chantier's view). */
    @Query("""
            select l from StockLocation l
            where l.orgId = :orgId and l.projectId = :projectId
              and l.type = tn.hydra.stock.domain.StockLocationType.SITE
              and l.active = true
            """)
    List<StockLocation> findSiteByProjectId(@Param("orgId") UUID orgId,
                                             @Param("projectId") UUID projectId);

    /**
     * SITE locations for any of the projects the caller is a member of.
     * The caller's project list is resolved in the service layer (via
     * project_member) and passed in here.
     */
    @Query("""
            select l from StockLocation l
            where l.orgId = :orgId
              and l.type = tn.hydra.stock.domain.StockLocationType.SITE
              and l.active = true
              and l.projectId in :projectIds
            order by l.name asc
            """)
    List<StockLocation> findSiteForProjects(@Param("orgId") UUID orgId,
                                             @Param("projectIds") List<UUID> projectIds);
}
