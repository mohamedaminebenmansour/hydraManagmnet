package tn.hydra.stock.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.hydra.stock.domain.ItemAlias;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA repository for {@link ItemAlias}.
 *
 * <p>Slice 1: read-only lookup — used by the ItemController to resolve a
 * typed alias string (e.g. a supplier code) → item id. No fuzzy match.
 */
public interface ItemAliasRepository extends JpaRepository<ItemAlias, UUID> {

    /** All aliases for one item (the ItemDetail view's "also known as" list). */
    @Query("""
            select a from ItemAlias a
            where a.itemId = :itemId and a.orgId = :orgId
            order by a.alias asc
            """)
    List<ItemAlias> findByItemIdAndOrgId(@Param("itemId") UUID itemId, @Param("orgId") UUID orgId);

    /** Resolve a typed alias → item id (case-insensitive, exact match). */
    @Query("""
            select a from ItemAlias a
            where a.orgId = :orgId and lower(a.alias) = lower(:alias)
            """)
    Optional<ItemAlias> findByOrgIdAndAliasIgnoreCase(@Param("orgId") UUID orgId,
                                                       @Param("alias") String alias);
}
