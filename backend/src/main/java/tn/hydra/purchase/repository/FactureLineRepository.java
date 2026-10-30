package tn.hydra.purchase.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import tn.hydra.purchase.domain.FactureLine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FactureLineRepository extends JpaRepository<FactureLine, UUID> {

    Optional<FactureLine> findByIdAndOrgId(UUID id, UUID orgId);

    List<FactureLine> findByFactureIdAndOrgIdOrderByCreatedAt(UUID factureId, UUID orgId);

    boolean existsByIdAndOrgId(UUID id, UUID orgId);

    long countByFactureIdAndOrgId(UUID factureId, UUID orgId);
}
