package tn.hydra.config.demo;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DemoComptesScenarioTest — verifies the 7 demo comptes users + custom role
 * are created by DemoScenarioRunner (which calls DemoComptesScenario).
 */
@SpringBootTest(properties = {
        "HYDRA_DEMO_PASSWORD=TestDemo2026x",
        "DB_URL=jdbc:postgresql://127.0.0.1:5432/hydra_test",
        "DB_USER=postgres",
        "DB_PASSWORD=hydra"
})
@ActiveProfiles("demo")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = "hydra.app-url=http://test.hydra.local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DemoComptesScenarioTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DemoScenarioRunner runner;

    private static final UUID DEMO_ORG_ID = DemoScenarioRunner.DEMO_ORG_ID;

    @Test
    void afterStartup_comptesUsersExistWithRightStatuses() {
        // nouveau — PENDING (must_change_password = true)
        assertThat(mustChange("nouveau@demo.hydra.tn")).isTrue();

        // reinit — MUST_CHANGE_PASSWORD after admin reset
        assertThat(mustChange("reinit@demo.hydra.tn")).isTrue();

        // dormant — last_login 45 days ago
        assertThat(isDormant("dormant@demo.hydra.tn")).isTrue();

        // inactif — INACTIVE
        assertThat(isActive("inactif@demo.hydra.tn")).isFalse();

        // absent — absent_until set
        assertThat(absentUntil("absent@demo.hydra.tn")).isNotNull();

        // exception — has ACHAT + FINANCE roles + toxic_pair_override row
        assertThat(hasRole("exception@demo.hydra.tn", "ACHAT")).isTrue();
        assertThat(hasRole("exception@demo.hydra.tn", "FINANCE")).isTrue();
        assertThat(signedExceptions()).isGreaterThan(0);
    }

    @Test
    void customRoleMagasinierExistsWithStockPermissions() {
        Integer count = jdbc.queryForObject(
                "select count(*) from role where code = 'MAGASINIER_DEMO' and org_id = ?",
                Integer.class, DEMO_ORG_ID);
        assertThat(count).isEqualTo(1);

        // has STOCK_VIEW + STOCK_MOVE
        assertThat(hasPermission("MAGASINIER_DEMO", "STOCK_VIEW")).isTrue();
        assertThat(hasPermission("MAGASINIER_DEMO", "STOCK_MOVE")).isTrue();

        // assigned to chef + travaux
        assertThat(hasRole("chef.demo@demo.hydra.tn", "MAGASINIER_DEMO")).isTrue();
        assertThat(hasRole("travaux.demo@demo.hydra.tn", "MAGASINIER_DEMO")).isTrue();
    }

    @Test
    void realOrganisationUntouched() {
        UUID firstOrg = jdbc.queryForObject(
                "select id from organization where id <> ? order by created_at limit 1",
                UUID.class, DEMO_ORG_ID);
        Integer users = jdbc.queryForObject("select count(*) from app_user where org_id = ?",
                Integer.class, firstOrg);
        assertThat(users).isLessThanOrEqualTo(1);
    }

    // ── helpers ────────────────────────────────────────────────────────

    private boolean mustChange(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select must_change_password from app_user where email = ?", Boolean.class, email));
    }

    private boolean isActive(String email) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select is_active from app_user where email = ?", Boolean.class, email));
    }

    private boolean isDormant(String email) {
        Object lastLogin = jdbc.queryForObject(
                "select last_login_at from app_user where email = ?", Object.class, email);
        if (lastLogin == null) return false;
        // dormant = last_login < 30 days ago
        Integer cnt = jdbc.queryForObject(
                "select count(*) from app_user where email = ? and last_login_at < now() - interval '30 days'",
                Integer.class, email);
        return cnt != null && cnt > 0;
    }

    private Object absentUntil(String email) {
        return jdbc.queryForObject(
                "select absent_until from app_user where email = ?", Object.class, email);
    }

    private boolean hasRole(String email, String roleCode) {
        Integer cnt = jdbc.queryForObject("""
                select count(*) from user_role ur
                join app_user u on u.id = ur.user_id
                join role r on r.id = ur.role_id
                where u.email = ? and r.code = ?
                """, Integer.class, email, roleCode);
        return cnt != null && cnt > 0;
    }

    private boolean hasPermission(String roleCode, String permId) {
        Integer cnt = jdbc.queryForObject("""
                select count(*) from role_permission rp
                join role r on r.id = rp.role_id
                where r.code = ? and r.org_id = ? and rp.permission_id = ?
                """, Integer.class, roleCode, DEMO_ORG_ID, permId);
        return cnt != null && cnt > 0;
    }

    private long signedExceptions() {
        Long n = jdbc.queryForObject(
                "select count(*) from toxic_pair_override where org_id = ?", Long.class, DEMO_ORG_ID);
        return n == null ? 0 : n;
    }

    @AfterAll
    void cleanup() {
        ReflectionTestUtils.setField(runner, "demoPassword", "TestDemo2026x");
        // clean toxic_pair_override FIRST (FK references app_user)
        jdbc.update("delete from toxic_pair_override where org_id = ? and reason like 'Démo%'", DEMO_ORG_ID);
        // clean comptes-specific demo data
        String[] emails = {"nouveau@demo.hydra.tn", "reinit@demo.hydra.tn", "dormant@demo.hydra.tn",
                "inactif@demo.hydra.tn", "absent@demo.hydra.tn", "exception@demo.hydra.tn"};
        for (String email : emails) {
            jdbc.update("delete from user_role where user_id in (select id from app_user where email = ?)", email);
            jdbc.update("delete from app_user where email = ?", email);
        }
        // clean the custom role
        jdbc.update("delete from role_permission where role_id in (select id from role where code = 'MAGASINIER_DEMO' and org_id = ?)", DEMO_ORG_ID);
        jdbc.update("delete from user_role where role_id in (select id from role where code = 'MAGASINIER_DEMO' and org_id = ?)", DEMO_ORG_ID);
        jdbc.update("delete from role where code = 'MAGASINIER_DEMO' and org_id = ?", DEMO_ORG_ID);
    }
}
