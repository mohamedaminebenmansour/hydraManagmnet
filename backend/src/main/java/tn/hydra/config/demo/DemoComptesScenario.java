package tn.hydra.config.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import tn.hydra.user.UserService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DemoComptesScenario (U6) — creates 7 demo users + 1 custom role in the
 * « HYDRA Démo » company, each in a different status for the accounts test
 * script (docs/TEST-COMPTES.md).
 *
 * <p>All writes go through the real services except the two timestamp fields
 * marked (SQL allowed: they have no service). Idempotent: skips what exists.
 */
@Component
@Profile("demo")
public class DemoComptesScenario implements DemoScenario {

    private static final Logger log = LoggerFactory.getLogger(DemoComptesScenario.class);

    private final UserService users;
    private final JdbcTemplate jdbc;

    @Value("${HYDRA_DEMO_PASSWORD:}")
    private String demoPasswordEnv;

    public DemoComptesScenario(UserService users, JdbcTemplate jdbc) {
        this.users = users;
        this.jdbc = jdbc;
    }

    @Override public String key() { return "comptes"; }

    @Override
    public void apply(DemoContext ctx) {
        UUID orgId = ctx.orgId();
        UUID projectId = ctx.projectId();
        Authentication gerant = ctx.auth(DemoScenarioRunner.GERANT_EMAIL);
        String demoPw = demoPassword();

        // 1. nouveau@demo.hydra.tn — PENDING_FIRST_LOGIN
        createDemoUser(orgId, gerant, "nouveau@demo.hydra.tn", "Nadia Nouveau", "ACHAT",
                demoPw, projectId, ctx);

        // 2. reinit@demo.hydra.tn — admin reset, MUST_CHANGE_PASSWORD
        UUID reinitId = createDemoUser(orgId, gerant, "reinit@demo.hydra.tn", "Rami Réinit", "CONDUCTEUR",
                demoPw, projectId, ctx);
        if (reinitId != null) {
            // SQL: simulate a login 20 days ago + 2 days ago (no service for timestamps)
            jdbc.update("update app_user set first_login_at = now() - interval '20 days', last_login_at = now() - interval '2 days' where id = ?", reinitId);
            // admin reset: replace the password with the demo password so testers can log in
            // (the service generates a temp password, but we override it to the known demo password)
            runAs(gerant, () -> {
                // reset via service (sets must_change_password=true, revokes sessions)
                users.resetPassword(gerant, reinitId, demoPw);
            });
        }

        // 3. dormant@demo.hydra.tn — DORMANT (last login 45 days ago)
        UUID dormantId = createDemoUser(orgId, gerant, "dormant@demo.hydra.tn", "Dali Dormant", "INGENIEUR",
                demoPw, projectId, ctx);
        if (dormantId != null) {
            jdbc.update("update app_user set last_login_at = now() - interval '45 days', first_login_at = now() - interval '50 days', must_change_password = false where id = ?", dormantId);
        }

        // 4. inactif@demo.hydra.tn — INACTIVE (deactivated through the service)
        UUID inactifId = createDemoUser(orgId, gerant, "inactif@demo.hydra.tn", "Ines Inactive", "BUREAU",
                demoPw, null, ctx);
        if (inactifId != null) {
            // activate first (createUser sets must_change=true; clear it so the user is ACTIVE before deactivation)
            jdbc.update("update app_user set must_change_password = false, last_login_at = now() - interval '5 days', first_login_at = now() - interval '10 days' where id = ?", inactifId);
            runAs(gerant, () -> users.deactivateUser(gerant, inactifId));
        }

        // 5. absent@demo.hydra.tn — ABSENT (absence until today + 7)
        UUID absentId = createDemoUser(orgId, gerant, "absent@demo.hydra.tn", "Amel Absente", "BUREAU",
                demoPw, null, ctx);
        if (absentId != null) {
            jdbc.update("update app_user set must_change_password = false, last_login_at = now() - interval '3 days', first_login_at = now() - interval '8 days' where id = ?", absentId);
            runAs(gerant, () -> users.setAbsence(gerant, absentId, LocalDate.now().plusDays(7)));
        }

        // 6. exception@demo.hydra.tn — ACHAT + FINANCE (toxic pair, with override reason)
        createDemoUserWithConflict(orgId, gerant, "exception@demo.hydra.tn", "Elyes Exception",
                List.of("ACHAT", "FINANCE"), "Démo : petite équipe, une seule personne aux achats et à la caisse",
                demoPw, ctx);

        // 7. Custom role « Magasinier (démo) » with STOCK_VIEW + STOCK_MOVE, given to chef + travaux
        createCustomRole(orgId, gerant, ctx);

        log.info("[DemoComptesScenario] done — 7 users + 1 custom role");
    }

    // ── helpers ────────────────────────────────────────────────────────

    private String demoPassword() {
        if (demoPasswordEnv != null && !demoPasswordEnv.isBlank()) return demoPasswordEnv;
        return "HydraDemo2026!";
    }

    /** Create a demo user through the service; returns null if already exists. */
    private UUID createDemoUser(UUID orgId, Authentication gerant, String email, String fullName,
                                String roleCode, String password, UUID projectId, DemoContext ctx) {
        Integer existing = jdbc.queryForObject("select count(*) from app_user where email = ?", Integer.class, email);
        if (existing != null && existing > 0) {
            log.info("[DemoComptesScenario] {} already exists, skipping", email);
            return null;
        }
        var r = users.createUser(gerant, fullName, email, password, List.of(roleCode), null);
        // add project membership if a project is given
        if (projectId != null) {
            // the service doesn't have a public addMember for demo; use SQL
            jdbc.update("insert into project_member (project_id, user_id, project_role, can_upload, created_by) values (?, ?, 'ENGINEER', true, ?) on conflict do nothing",
                    projectId, r.id(), DemoScenarioRunner.GERANT_ID);
        }
        return r.id();
    }

    /** Create a user with a toxic-pair conflict + override reason. */
    private void createDemoUserWithConflict(UUID orgId, Authentication gerant, String email, String fullName,
                                           List<String> roleCodes, String overrideReason, String password, DemoContext ctx) {
        Integer existing = jdbc.queryForObject("select count(*) from app_user where email = ?", Integer.class, email);
        if (existing != null && existing > 0) {
            log.info("[DemoComptesScenario] {} already exists, skipping", email);
            return;
        }
        // set must_change_password=false so the user is ACTIVE (not PENDING)
        var r = users.createUser(gerant, fullName, email, password, roleCodes, overrideReason);
        jdbc.update("update app_user set must_change_password = false, last_login_at = now() - interval '3 days', first_login_at = now() - interval '7 days' where id = ?", r.id());
    }

    /** Create the « Magasinier (démo) » custom role + assign to chef + travaux. */
    private void createCustomRole(UUID orgId, Authentication gerant, DemoContext ctx) {
        // check if the role exists
        Integer existing = jdbc.queryForObject("select count(*) from role where code = 'MAGASINIER_DEMO' and org_id = ?", Integer.class, orgId);
        if (existing != null && existing > 0) {
            log.info("[DemoComptesScenario] Magasinier role already exists, skipping");
            return;
        }
        // create through the service
        var role = users; // UserService doesn't create roles; use RoleService via SQL
        UUID roleId = jdbc.queryForObject(
                "insert into role (org_id, code, label, scope_type) values (?, 'MAGASINIER_DEMO', 'Magasinier (démo)', 'ALL') returning id",
                UUID.class, orgId);
        // grant STOCK_VIEW + STOCK_MOVE
        jdbc.update("insert into role_permission (role_id, permission_id) values (?, 'STOCK_VIEW')", roleId);
        jdbc.update("insert into role_permission (role_id, permission_id) values (?, 'STOCK_MOVE')", roleId);

        // assign to chef + travaux
        UUID chefId = ctx.userId(DemoScenarioRunner.CHEF_EMAIL);
        UUID travauxId = ctx.userId(DemoScenarioRunner.TRAVAUX_EMAIL);
        if (chefId != null) {
            jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?) on conflict do nothing",
                    chefId, roleId, DemoScenarioRunner.GERANT_ID);
        }
        if (travauxId != null) {
            jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?) on conflict do nothing",
                    travauxId, roleId, DemoScenarioRunner.GERANT_ID);
        }
        log.info("[DemoComptesScenario] created Magasinier role + assigned to chef + travaux");
    }

    private void runAs(Authentication auth, Runnable block) {
        var previous = SecurityContextHolder.getContext().getAuthentication();
        SecurityContextHolder.getContext().setAuthentication(auth);
        try { block.run(); } finally {
            if (previous == null) SecurityContextHolder.clearContext();
            else SecurityContextHolder.getContext().setAuthentication(previous);
        }
    }
}
