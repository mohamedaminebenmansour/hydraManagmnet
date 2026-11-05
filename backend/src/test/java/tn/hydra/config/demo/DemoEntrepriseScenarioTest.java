package tn.hydra.config.demo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CO-13: run the runner twice → one profile, one groupement with two members.
 */
@SpringBootTest(properties = {
        "HYDRA_DEMO_PASSWORD=TestDemo2026x",
        "DB_URL=jdbc:postgresql://127.0.0.1:5432/hydra_test",
        "DB_USER=postgres",
        "DB_PASSWORD=hydra"
})
@ActiveProfiles("demo")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DemoEntrepriseScenarioTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DemoScenarioRunner runner;

    private static final java.util.UUID DEMO_ORG_ID = DemoScenarioRunner.DEMO_ORG_ID;

    @Test
    void demoProfileAndGroupementExist() {
        // Profile exists
        Integer profileCount = jdbc.queryForObject(
                "select count(*) from company_profile where org_id = ?", Integer.class, DEMO_ORG_ID);
        assertThat(profileCount).isEqualTo(1);

        // Profile has the right values
        String raisonSociale = jdbc.queryForObject(
                "select raison_sociale from company_profile where org_id = ?", String.class, DEMO_ORG_ID);
        assertThat(raisonSociale).isEqualTo("HYDRA Démo SARL");

        String mf = jdbc.queryForObject(
                "select matricule_fiscal from company_profile where org_id = ?", String.class, DEMO_ORG_ID);
        assertThat(mf).isEqualTo("1234567AAM000");

        // Groupement exists
        Integer grpCount = jdbc.queryForObject(
                "select count(*) from groupement where org_id = ? and nom = 'Groupement Démo AEP'",
                Integer.class, DEMO_ORG_ID);
        assertThat(grpCount).isEqualTo(1);

        // Two members
        java.util.UUID grpId = jdbc.queryForObject(
                "select id from groupement where org_id = ? and nom = 'Groupement Démo AEP'",
                java.util.UUID.class, DEMO_ORG_ID);
        Integer memberCount = jdbc.queryForObject(
                "select count(*) from groupement_member where groupement_id = ?", Integer.class, grpId);
        assertThat(memberCount).isEqualTo(2);
    }

    @Test
    void runnerTwice_noDuplicates() {
        runner.run();
        Integer profileCount = jdbc.queryForObject(
                "select count(*) from company_profile where org_id = ?", Integer.class, DEMO_ORG_ID);
        assertThat(profileCount).isEqualTo(1);

        Integer grpCount = jdbc.queryForObject(
                "select count(*) from groupement where org_id = ? and nom = 'Groupement Démo AEP'",
                Integer.class, DEMO_ORG_ID);
        assertThat(grpCount).isEqualTo(1);
    }
}
