package tn.hydra.purchase.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.hydra.purchase.domain.PurchaseOrder;
import tn.hydra.purchase.domain.PurchaseOrderStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * PurchaseOrder repository — every query is org-scoped.
 * Mirrors the {@code StockItemRepository} / {@code StockMoveRepository} convention.
 */
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    Optional<PurchaseOrder> findByIdAndOrgId(UUID id, UUID orgId);

    List<PurchaseOrder> findByOrgIdAndProjectIdOrderByOrderedAtDesc(UUID orgId, UUID projectId);

    List<PurchaseOrder> findByOrgIdAndProjectIdAndStatusOrderByOrderedAtDesc(
            UUID orgId, UUID projectId, PurchaseOrderStatus status);

    List<PurchaseOrder> findByOrgIdAndStatusOrderByOrderedAtDesc(UUID orgId, PurchaseOrderStatus status);

    List<PurchaseOrder> findByOrgIdOrderByOrderedAtDesc(UUID orgId);

    boolean existsByIdAndOrgId(UUID id, UUID orgId);

    @Query("""
           select count(po) from PurchaseOrder po
           where po.orgId = :orgId and po.projectId = :projectId
           """)
    long countByOrgAndProject(@Param("orgId") UUID orgId, @Param("projectId") UUID projectId);
}
