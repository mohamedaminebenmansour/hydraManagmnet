package tn.hydra.config.demo;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ============================================================================
 *  DemoScenarioRunnerTest — verifies the demo pack creates the right world.
 * ============================================================================
 *
 *  <p>Runs with the {@code demo} profile active and {@code HYDRA_DEMO_PASSWORD}
 *  set, so {@link DemoScenarioRunner} fires at startup. The test then checks the
 *  seeded world, re-runs the runner for idempotency, checks the real org is
 *  untouched, and verifies a blank password creates nothing.
 *
 *  <p>{@code @DirtiesContext(AFTER_CLASS)} closes the Spring context after this
 *  test class so the demo beans don't leak into other tests. {@code @AfterAll}
 *  deletes everything the demo created so hydra_test stays clean.
 * ============================================================================
 */
@SpringBootTest(properties = {
        "HYDRA_DEMO_PASSWORD=TestDemo2026x",
        "DB_URL=jdbc:postgresql://127.0.0.1:5432/hydra_test",
        "DB_USER=postgres",
        "DB_PASSWORD=hydra"
})
@ActiveProfiles("demo")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoScenarioRunnerTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DemoScenarioRunner runner;

    private static final UUID DEMO_ORG_ID = DemoScenarioRunner.DEMO_ORG_ID;

    @Test
    void afterStartup_demoWorldIsSeeded() {
        Integer orgCount = jdbc.queryForObject("select count(*) from organization where id = ?",
                Integer.class, DEMO_ORG_ID);
        assertThat(orgCount).isEqualTo(1);

        Integer userCount = jdbc.queryForObject("select count(*) from app_user where org_id = ?",
                Integer.class, DEMO_ORG_ID);
        // 9 original demo cast + 6 DemoComptesScenario users = 15
        assertThat(userCount).isEqualTo(15);

        Integer projectCount = jdbc.queryForObject(
                "select count(*) from project where org_id = ? and code = 'DEMO-01'",
                Integer.class, DEMO_ORG_ID);
        assertThat(projectCount).isEqualTo(1);

        Integer ticketCount = jdbc.queryForObject(
                "select count(*) from ticket where org_id = ? and title like '[Démo]%'",
                Integer.class, DEMO_ORG_ID);
        assertThat(ticketCount).isEqualTo(11);

        assertThat(statusOf("[Démo] Calculer les quantités de l'attachement N°1")).isEqualTo("RESOLVED");
        assertThat(statusOf("[Démo] Déposer la caution définitive")).isEqualTo("CLOSED");
        assertThat(statusOf("[Démo] Réserver la salle de réunion")).isEqualTo("CANCELLED");
        assertThat(dueAtOf("[Démo] Envoyer le planning au CRDA")).isBeforeOrEqualTo(java.time.LocalDate.now());
        assertThat(isPrivate("[Démo] Préparer les salaires d'octobre")).isTrue();
        assertThat(statusOf("[Démo] Enduit fissuré sur la cuve")).isEqualTo("RESOLVED");
    }

    @Test
    void runSecondTime_isIdempotent() {
        runner.run();
        Integer userCount = jdbc.queryForObject("select count(*) from app_user where org_id = ?",
                Integer.class, DEMO_ORG_ID);
        // 9 original demo cast + 6 DemoComptesScenario users = 15
        assertThat(userCount).isEqualTo(15);
        Integer ticketCount = jdbc.queryForObject(
                "select count(*) from ticket where org_id = ? and title like '[Démo]%'",
                Integer.class, DEMO_ORG_ID);
        assertThat(ticketCount).isEqualTo(11);
    }

    @Test
    void realOrganisationIsUntouched() {
        UUID firstOrg = jdbc.queryForObject(
                "select id from organization where id <> ? order by created_at limit 1",
                UUID.class, DEMO_ORG_ID);
        Integer users = jdbc.queryForObject("select count(*) from app_user where org_id = ?",
                Integer.class, firstOrg);
        assertThat(users).isLessThanOrEqualTo(1);
        Integer projects = jdbc.queryForObject("select count(*) from project where org_id = ?",
                Integer.class, firstOrg);
        assertThat(projects).isZero();
        Integer tickets = jdbc.queryForObject("select count(*) from ticket where org_id = ?",
                Integer.class, firstOrg);
        assertThat(tickets).isZero();
    }

    @Test
    void allDemoEmailsEndWithDemoDomain() {
        // U7: every demo account email ends with @demo.hydra.tn
        Integer nonDemoDomain = jdbc.queryForObject(
                "select count(*) from app_user where org_id = ? and email not like '%@demo.hydra.tn'",
                Integer.class, DEMO_ORG_ID);
        assertThat(nonDemoDomain).isZero();
    }

    @Test
    void blankPassword_createsNothing() {
        Integer beforeUsers = jdbc.queryForObject("select count(*) from app_user where org_id = ?",
                Integer.class, DEMO_ORG_ID);
        Integer beforeTickets = jdbc.queryForObject(
                "select count(*) from ticket where org_id = ? and title like '[Démo]%'",
                Integer.class, DEMO_ORG_ID);
        Object savedPw = ReflectionTestUtils.getField(runner, "demoPassword");
        ReflectionTestUtils.setField(runner, "demoPassword", "");
        runner.run();
        Integer afterUsers = jdbc.queryForObject("select count(*) from app_user where org_id = ?",
                Integer.class, DEMO_ORG_ID);
        Integer afterTickets = jdbc.queryForObject(
                "select count(*) from ticket where org_id = ? and title like '[Démo]%'",
                Integer.class, DEMO_ORG_ID);
        assertThat(afterUsers).isEqualTo(beforeUsers);
        assertThat(afterTickets).isEqualTo(beforeTickets);
        ReflectionTestUtils.setField(runner, "demoPassword", savedPw);
    }

    private String statusOf(String title) {
        return jdbc.queryForObject("select status from ticket where org_id = ? and title = ?",
                String.class, DEMO_ORG_ID, title);
    }

    private java.time.LocalDate dueAtOf(String title) {
        return jdbc.queryForObject("select due_at from ticket where org_id = ? and title = ?",
                java.time.LocalDate.class, DEMO_ORG_ID, title);
    }

    private boolean isPrivate(String title) {
        return jdbc.queryForObject("select is_private from ticket where org_id = ? and title = ?",
                Boolean.class, DEMO_ORG_ID, title);
    }

    @AfterAll
    void cleanupDemoData() {
        ReflectionTestUtils.setField(runner, "demoPassword", "TestDemo2026x");
        jdbc.update("delete from ticket_attachment where org_id = ?", DEMO_ORG_ID);
        jdbc.update("delete from ticket_comment where org_id = ?", DEMO_ORG_ID);
        jdbc.update("delete from ticket where org_id = ?", DEMO_ORG_ID);
        jdbc.update("delete from document_revision where document_id in (select id from document where org_id = ?)", DEMO_ORG_ID);
        jdbc.update("delete from document where org_id = ?", DEMO_ORG_ID);
        // S1-5: delete groupement_member + groupement + company_profile before project (FK)
        jdbc.update("delete from groupement_member where org_id = ?", DEMO_ORG_ID);
        jdbc.update("delete from groupement where org_id = ?", DEMO_ORG_ID);
        jdbc.update("delete from company_profile where org_id = ?", DEMO_ORG_ID);
        jdbc.update("delete from doc_folder where project_id in (select id from project where org_id = ?)", DEMO_ORG_ID);
        jdbc.update("delete from project_member where project_id in (select id from project where org_id = ?)", DEMO_ORG_ID);
        jdbc.update("delete from ouvrage where org_id = ? and project_id in (select id from project where org_id = ?)", DEMO_ORG_ID, DEMO_ORG_ID);
        jdbc.update("delete from project where org_id = ?", DEMO_ORG_ID);
        jdbc.update("delete from user_role where user_id in (select id from app_user where org_id = ?)", DEMO_ORG_ID);
        jdbc.update("delete from toxic_pair_override where org_id = ?", DEMO_ORG_ID);
        jdbc.update("delete from role_permission where role_id in (select id from role where org_id = ?)", DEMO_ORG_ID);
        jdbc.update("delete from role where org_id = ?", DEMO_ORG_ID);
        jdbc.update("delete from app_user where org_id = ?", DEMO_ORG_ID);
        jdbc.update("delete from organization where id = ?", DEMO_ORG_ID);
    }
}
