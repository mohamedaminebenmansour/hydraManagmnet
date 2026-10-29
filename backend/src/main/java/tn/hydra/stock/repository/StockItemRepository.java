package tn.hydra.stock.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.hydra.stock.domain.StockItem;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA repository for {@link StockItem}.
 *
 * <p>All queries are org-scoped (matching the cash module pattern). The
 * service layer is responsible for resolving the caller's org_id from
 * the security context — this repo never accepts a "global" lookup.
 */
public interface StockItemRepository extends JpaRepository<StockItem, UUID> {

    /**
     * Find an active item by id within the org (the standard "I clicked on
     * this row" lookup). Inactive items are returned too because an
     * audit/history view may want to display them.
     */
    @Query("""
            select i from StockItem i
            where i.id = :id and i.orgId = :orgId
            """)
    Optional<StockItem> findByIdAndOrgId(@Param("id") UUID id, @Param("orgId") UUID orgId);

    /** Active items in the org, ordered by name (the picker UI list). */
    @Query("""
            select i from StockItem i
            where i.orgId = :orgId and i.active = true
            order by i.name asc
            """)
    List<StockItem> findActiveByOrgId(@Param("orgId") UUID orgId);

    /** Case-insensitive code lookup within the org (used on create to enforce uniqueness). */
    @Query("""
            select i from StockItem i
            where i.orgId = :orgId and lower(i.code) = lower(:code)
            """)
    Optional<StockItem> findByOrgIdAndCodeIgnoreCase(@Param("orgId") UUID orgId,
                                                      @Param("code") String code);

    /** Search by name fragment (the picker search box). */
    @Query("""
            select i from StockItem i
            where i.orgId = :orgId and i.active = true
              and lower(i.name) like lower(concat('%', :q, '%'))
            order by i.name asc
            """)
    List<StockItem> searchActiveByName(@Param("orgId") UUID orgId, @Param("q") String q);
}
