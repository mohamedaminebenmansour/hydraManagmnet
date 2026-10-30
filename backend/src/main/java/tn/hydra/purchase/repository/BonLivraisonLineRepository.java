package tn.hydra.purchase.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.hydra.purchase.domain.BonLivraisonLine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BonLivraisonLineRepository extends JpaRepository<BonLivraisonLine, UUID> {

    Optional<BonLivraisonLine> findByIdAndOrgId(UUID id, UUID orgId);

    List<BonLivraisonLine> findByBonLivraisonIdAndOrgIdOrderByCreatedAt(UUID bonLivraisonId, UUID orgId);

    boolean existsByIdAndOrgId(UUID id, UUID orgId);

    /** Sum qty_received across POSTED BLs for a given bc_line_id — for the hard-cap check. */
    @Query("""
           select coalesce(sum(l.qtyReceived), 0) from BonLivraisonLine l
           where l.orgId = :orgId and l.bcLineId = :bcLineId
           and l.bonLivraisonId in (
               select b.id from BonLivraison b
               where b.orgId = :orgId and b.status = tn.hydra.purchase.domain.BonLivraisonStatus.POSTED
           )
           """)
    java.math.BigDecimal sumPostedReceivedForBcLine(@Param("orgId") UUID orgId,
                                                     @Param("bcLineId") UUID bcLineId);
}
