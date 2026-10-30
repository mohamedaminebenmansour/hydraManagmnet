package tn.hydra.purchase.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tn.hydra.purchase.domain.BonLivraison;
import tn.hydra.purchase.domain.BonLivraisonStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BonLivraisonRepository extends JpaRepository<BonLivraison, UUID> {

    Optional<BonLivraison> findByIdAndOrgId(UUID id, UUID orgId);

    List<BonLivraison> findByOrgIdAndProjectIdOrderByCreatedAtDesc(UUID orgId, UUID projectId);

    List<BonLivraison> findByOrgIdAndBcIdOrderByCreatedAtDesc(UUID orgId, UUID bcId);

    List<BonLivraison> findByOrgIdOrderByCreatedAtDesc(UUID orgId);

    List<BonLivraison> findByOrgIdAndStatusOrderByCreatedAtDesc(UUID orgId, BonLivraisonStatus status);

    boolean existsByIdAndOrgId(UUID id, UUID orgId);
}
