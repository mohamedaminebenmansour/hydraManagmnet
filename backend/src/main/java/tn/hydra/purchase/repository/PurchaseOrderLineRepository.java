package tn.hydra.purchase.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.hydra.purchase.domain.PurchaseOrderLine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PurchaseOrderLine repository — every query is org-scoped.
 */
public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, UUID> {

    Optional<PurchaseOrderLine> findByIdAndOrgId(UUID id, UUID orgId);

    List<PurchaseOrderLine> findByPurchaseOrderIdAndOrgIdOrderByCreatedAt(UUID purchaseOrderId, UUID orgId);

    List<PurchaseOrderLine> findByOrgIdAndItemId(UUID orgId, UUID itemId);

    boolean existsByIdAndOrgId(UUID id, UUID orgId);

    boolean existsByPurchaseOrderIdAndOrgId(UUID purchaseOrderId, UUID orgId);

    @Query("""
           select count(l) from PurchaseOrderLine l
           where l.purchaseOrderId = :purchaseOrderId and l.orgId = :orgId
           """)
    long countByPurchaseOrder(@Param("purchaseOrderId") UUID purchaseOrderId,
                              @Param("orgId") UUID orgId);
}
