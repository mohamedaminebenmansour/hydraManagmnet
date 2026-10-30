package tn.hydra.purchase.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.hydra.purchase.domain.Facture;
import tn.hydra.purchase.domain.FactureStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FactureRepository extends JpaRepository<Facture, UUID> {

    Optional<Facture> findByIdAndOrgId(UUID id, UUID orgId);

    List<Facture> findByOrgIdAndProjectIdOrderByInvoiceDateDesc(UUID orgId, UUID projectId);

    List<Facture> findByOrgIdAndStatusOrderByInvoiceDateDesc(UUID orgId, FactureStatus status);

    List<Facture> findByOrgIdOrderByInvoiceDateDesc(UUID orgId);

    boolean existsByIdAndOrgId(UUID id, UUID orgId);
}
