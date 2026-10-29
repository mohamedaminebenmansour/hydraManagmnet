package tn.hydra.stock.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.hydra.stock.domain.StockMove;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * JPA repository for {@link StockMove} — the keystone.
 *
 * <p>The repository exposes typed queries for the two read paths:
 * <ul>
 *   <li><b>Moves for an item</b> (the "Fiche de stock" running balance view).</li>
 *   <li><b>Moves for a location</b> (the per-chantier movement log).</li>
 * </ul>
 *
 * <p>Balances are NOT computed here — they live in the {@code stock_balance}
 * view (see V30 migration). For Slice 1 we read the view directly via
 * JdbcTemplate in the service layer (JPA doesn't natively know about views).
 */
public interface StockMoveRepository extends JpaRepository<StockMove, UUID> {

    /** All moves for one item in the org, ordered newest first. */
    @Query("""
            select m from StockMove m
            where m.orgId = :orgId and m.itemId = :itemId
            order by m.moveDate desc, m.createdAt desc
            """)
    List<StockMove> findByOrgIdAndItemId(@Param("orgId") UUID orgId,
                                         @Param("itemId") UUID itemId);

    /** All moves involving a location (as either from or to), newest first. */
    @Query("""
            select m from StockMove m
            where m.orgId = :orgId
              and (m.fromLocationId = :locationId or m.toLocationId = :locationId)
            order by m.moveDate desc, m.createdAt desc
            """)
    List<StockMove> findByOrgIdAndLocationId(@Param("orgId") UUID orgId,
                                              @Param("locationId") UUID locationId);

    /** Moves for a project (denormalized project_id index). */
    @Query("""
            select m from StockMove m
            where m.orgId = :orgId and m.projectId = :projectId
            order by m.moveDate desc, m.createdAt desc
            """)
    List<StockMove> findByOrgIdAndProjectId(@Param("orgId") UUID orgId,
                                            @Param("projectId") UUID projectId);

    /**
     * Sum of signed qty affecting a single (item, location) — the in-JPA
     * balance check used by the negative-stock guard before posting a move.
     *
     * <p>The SQL mirrors the {@code stock_balance} view's CASE expression.
     * Kept in the repo (not in the service) so the signing logic lives in
     * one place — the type system.
     */
    @Query(value = """
            select coalesce(sum(
                case
                    when m.move_type = 'IN'         then m.qty
                    when m.move_type = 'OUT'        then -m.qty
                    when m.move_type = 'DIRECT_USE' then -m.qty
                    when m.move_type = 'TRANSFER'   then
                        case
                            when m.from_location_id = :locationId then -m.qty
                            when m.to_location_id   = :locationId then  m.qty
                            else 0
                        end
                    when m.move_type = 'ADJUST'     then m.qty
                end
            ), 0) from stock_move m
            where m.org_id = :orgId
              and m.item_id = :itemId
              and (m.from_location_id = :locationId or m.to_location_id = :locationId)
            """, nativeQuery = true)
    BigDecimal signedBalance(@Param("orgId") UUID orgId,
                              @Param("itemId") UUID itemId,
                              @Param("locationId") UUID locationId);
}
