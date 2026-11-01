package tn.hydra.project;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ============================================================================
 *  OuvrageListDbTest — GET /api/projects/{id}/ouvrages through the real
 *  OuvrageService against hydra_test (fix/ouvrages-list-and-ux).
 * ============================================================================
 *
 *  The jsonb characteristics column comes back from JdbcTemplate as a PGobject;
 *  a (String) cast on it made list/get throw (500) for every chantier with an
 *  ouvrage. @Transactional + @Rollback keep the DB clean.
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class OuvrageListDbTest {

    @Autowired private OuvrageService ouvrages;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID ownerId;
    private UUID projectId;
    private Authentication auth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        String email = "ouvrage.list." + ownerId + "@hydra.test";

        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Ouvrage Org " + orgId);
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, true, true)
                """, ownerId, orgId, email, "hash", "Ouvrage Owner");
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                projectId, orgId, "Chantier " + projectId, ownerId);

        auth = new UsernamePasswordAuthenticationToken(email, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void list_returnsCommunAndBothReservoirs_withNumericCapacity_andGetById() {
        ouvrages.ensureCommunOuvrage(projectId, orgId, ownerId);
        OuvrageService.OuvrageResponse r250 = ouvrages.create(auth, projectId, new OuvrageService.CreateOuvrageRequest(
                "RESERVOIR", "R1", "Réservoir sur piliers", Map.of("capacite_m3", 250, "sous_type", "SUR_PILIERS"), 1));
        OuvrageService.OuvrageResponse r150 = ouvrages.create(auth, projectId, new OuvrageService.CreateOuvrageRequest(
                "RESERVOIR", "R2", "Réservoir semi-enterré", Map.of("capacite_m3", 150, "sous_type", "SEMI_ENTERRE"), 2));

        List<OuvrageService.OuvrageResponse> list = ouvrages.list(auth, projectId);

        assertThat(list).extracting(OuvrageService.OuvrageResponse::type)
                .containsExactlyInAnyOrder("COMMUN", "RESERVOIR", "RESERVOIR");
        Map<UUID, Object> capacityById = new java.util.HashMap<>();
        for (var o : list) capacityById.put(o.id(), o.characteristics().get("capacite_m3"));
        assertThat(capacityById.get(r250.id())).isInstanceOf(Number.class);
        assertThat(((Number) capacityById.get(r250.id())).doubleValue()).isEqualTo(250.0);
        assertThat(((Number) capacityById.get(r150.id())).doubleValue()).isEqualTo(150.0);

        OuvrageService.OuvrageResponse one = ouvrages.get(auth, projectId, r150.id());
        assertThat(one).isNotNull();
        assertThat(one.name()).isEqualTo("Réservoir semi-enterré");
        assertThat(((Number) one.characteristics().get("capacite_m3")).doubleValue()).isEqualTo(150.0);
    }
}
