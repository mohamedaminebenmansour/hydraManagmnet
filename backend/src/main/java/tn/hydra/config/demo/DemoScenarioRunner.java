package tn.hydra.config.demo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tn.hydra.project.ProjectService;
import tn.hydra.security.PermissionService;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * ============================================================================
 *  DemoScenarioRunner — creates the « HYDRA Démo » company + 9 users + 1 chantier
 *  then runs every {@link DemoScenario} bean.
 * ============================================================================
 *
 *  <p>Active only when the {@code demo} profile is on AND {@code HYDRA_DEMO_PASSWORD}
 *  is set. It NEVER touches the real company (BootstrapRunner's org): it inserts
 *  its own organisation with a fixed id, its own users with fixed ids, and
 *  creates its chantier through {@link ProjectService#create} authenticated as
 *  the demo gerant. Every business write goes through the real services.
 *
 *  <p>Idempotent: organisations/users/chantier/tickets are inserted only if
 *  absent. Re-running on an already-seeded DB is a no-op.
 * ============================================================================
 */
@Component
@Profile("demo")
@Order(Ordered.LOWEST_PRECEDENCE)
public class DemoScenarioRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoScenarioRunner.class);

    /** Fixed id of the « HYDRA Démo » organisation. */
    public static final UUID DEMO_ORG_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000001");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final ProjectService projects;
    private final PermissionService permissions;
    private final List<DemoScenario> scenarios;

    @Value("${HYDRA_DEMO_PASSWORD:}")
    private String demoPassword;

    public DemoScenarioRunner(JdbcTemplate jdbc, PasswordEncoder encoder,
                              ProjectService projects, PermissionService permissions,
                              List<DemoScenario> scenarios) {
        this.jdbc = jdbc; this.encoder = encoder;
        this.projects = projects; this.permissions = permissions;
        this.scenarios = scenarios;
    }

    // ══════════════════ the demo cast (9 users) ══════════════════

    private record Cast(UUID id, String email, String fullName, String roleCode,
                        boolean isOwner, String projectRole) {}

    static final UUID GERANT_ID  = UUID.fromString("dddddddd-0000-0000-0000-000000000010");
    static final UUID ACHATS_ID  = UUID.fromString("dddddddd-0000-0000-0000-000000000011");
    static final UUID METRE_ID   = UUID.fromString("dddddddd-0000-0000-0000-000000000012");
    static final UUID FINANCE_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000013");
    static final UUID TRAVAUX_ID = UUID.fromString("dddddddd-0000-0000-0000-000000000014");
    static final UUID CHEF_ID    = UUID.fromString("dddddddd-0000-0000-0000-000000000015");
    static final UUID QUANT_ID   = UUID.fromString("dddddddd-0000-0000-0000-000000000016");
    static final UUID EXT_ID     = UUID.fromString("dddddddd-0000-0000-0000-000000000017");
    static final UUID DOC_ID     = UUID.fromString("dddddddd-0000-0000-0000-000000000018");

    static final String GERANT_EMAIL  = "gerant.demo@demo.hydra.tn";
    static final String ACHATS_EMAIL  = "achats.demo@demo.hydra.tn";
    static final String METRE_EMAIL   = "metre.demo@demo.hydra.tn";
    static final String FINANCE_EMAIL = "finance.demo@demo.hydra.tn";
    static final String TRAVAUX_EMAIL = "travaux.demo@demo.hydra.tn";
    static final String CHEF_EMAIL    = "chef.demo@demo.hydra.tn";
    static final String QUANT_EMAIL   = "quantites.demo@demo.hydra.tn";
    static final String EXT_EMAIL     = "externe.demo@demo.hydra.tn";
    static final String DOC_EMAIL     = "doc.demo@demo.hydra.tn";
    private static final List<Cast> CAST = List.of(
            new Cast(GERANT_ID,  GERANT_EMAIL,  "Gérant Démo",             "OWNER",      true,  "MANAGER"),
            new Cast(ACHATS_ID,  ACHATS_EMAIL,  "Responsable Achats",      "BUREAU",     false, "ENGINEER"),
            new Cast(METRE_ID,   METRE_EMAIL,   "Métreur",                 "INGENIEUR",  false, "ENGINEER"),
            new Cast(FINANCE_ID, FINANCE_EMAIL, "Responsable Financier",   "CHEF_PROJET",false, "ENGINEER"),
            new Cast(TRAVAUX_ID, TRAVAUX_EMAIL, "Conducteur Travaux",      "CONDUCTEUR", false, "ENGINEER"),
            new Cast(CHEF_ID,    CHEF_EMAIL,    "Chef Chantier",           "CONDUCTEUR", false, "ENGINEER"),
            new Cast(QUANT_ID,   QUANT_EMAIL,   "Responsable Quantités",   "INGENIEUR",  false, "ENGINEER"),
            new Cast(EXT_ID,     EXT_EMAIL,     "Consultant Externe",      "SOUS_TRAITANT", false, null),
            new Cast(DOC_ID,     DOC_EMAIL,     "Responsable Documentaire","DOC_CTRL",   false, "ENGINEER"));

    @Override
    public void run(String... args) {
        if (demoPassword == null || demoPassword.isBlank()) {
            log.warn("DemoScenarioRunner skipped: HYDRA_DEMO_PASSWORD is not set.");
            return;
        }
        // 1. Organisation « HYDRA Démo » (fixed id, insert only if absent).
        Integer orgExists = jdbc.queryForObject(
                "select count(*) from organization where id = ?", Integer.class, DEMO_ORG_ID);
        if (orgExists == null || orgExists == 0) {
            jdbc.update("insert into organization (id, name) values (?, ?)",
                    DEMO_ORG_ID, "HYDRA Démo");
            log.info("[DemoScenarioRunner] created organisation « HYDRA Démo » {}", DEMO_ORG_ID);
        }

        String pwHash = encoder.encode(demoPassword);

        // 2. The 9 demo users (fixed ids, insert only if absent).
        for (Cast c : CAST) {
            Integer userExists = jdbc.queryForObject(
                    "select count(*) from app_user where id = ?", Integer.class, c.id());
            if (userExists != null && userExists > 0) {
                // U7: idempotent email update — if the user exists with an old @hydra.demo email, update it
                String currentEmail = jdbc.queryForObject(
                        "select email from app_user where id = ?", String.class, c.id());
                if (currentEmail != null && !currentEmail.equals(c.email())) {
                    jdbc.update("update app_user set email = ? where id = ?", c.email(), c.id());
                    log.info("[DemoScenarioRunner] updated email for {} → {}", c.fullName(), c.email());
                }
                continue;
            }
            jdbc.update("""
                    insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                    values (?, ?, ?, ?, ?, ?, true, false)
                    """, c.id(), DEMO_ORG_ID, c.email(), pwHash, c.fullName(), c.isOwner());
            UUID roleId = jdbc.queryForObject(
                    "select id from role where code = ? and org_id is null", UUID.class, c.roleCode());
            jdbc.update("insert into user_role (user_id, role_id, assigned_by) values (?, ?, ?)",
                    c.id(), roleId, GERANT_ID);
            log.info("[DemoScenarioRunner] created user {} ({})", c.fullName(), c.roleCode());
        }

        // 3. The demo chantier (created through ProjectService as gerant).
        Authentication gerantAuth = authFor(GERANT_EMAIL);
        SecurityContextHolder.getContext().setAuthentication(gerantAuth);
        UUID projectId;
        List<UUID> existingProjects = jdbc.queryForList(
                "select id from project where org_id = ? and code = 'DEMO-01'", UUID.class, DEMO_ORG_ID);
        if (existingProjects.isEmpty()) {
            ProjectService.ProjectResponse p = projects.create(gerantAuth,
                    new ProjectService.CreateProjectRequest(
                            "Démo — Périmètre irrigué", "DEMO-01", "CRDA Démo",
                            "Périmètre irrigué", "Kairouan",
                            LocalDate.now().minusMonths(6), LocalDate.now().plusMonths(18),
                            "PI", "Périmètre irrigué — démo HYDRA",
                            null, "TND", null, null));
            projectId = p.id();
            log.info("[DemoScenarioRunner] created chantier DEMO-01 {}", projectId);
        } else {
            projectId = existingProjects.get(0);
            log.info("[DemoScenarioRunner] chantier DEMO-01 already exists {}", projectId);
        }

        // 4. Project members (skip externe; gerant already added by ProjectService.create).
        for (Cast c : CAST) {
            if (c.projectRole() == null) continue;             // externe
            if (c.id().equals(GERANT_ID)) continue;            // gerant added by create
            Integer memberExists = jdbc.queryForObject(
                    "select count(*) from project_member where project_id = ? and user_id = ?",
                    Integer.class, projectId, c.id());
            if (memberExists != null && memberExists > 0) continue;
            projects.addMember(gerantAuth, projectId, c.id(), c.projectRole(), true);
        }
        SecurityContextHolder.clearContext();

        // 5. Run every DemoScenario bean in order.
        DemoScenario.DemoContext ctx = new DemoScenario.DemoContext(
                DEMO_ORG_ID, projectId, LocalDate.now(),
                (email) -> authFor(email),
                (email) -> userIdByEmail(email));
        for (DemoScenario s : scenarios) {
            log.info("[DemoScenarioRunner] applying scenario « {} »", s.key());
            s.apply(ctx);
        }
        log.info("[DemoScenarioRunner] demo pack complete: {} scenarios", scenarios.size());
    }

    // ── helpers ───────────────────────────────────────────────────────

    /** Build an Authentication for a demo user, with the permissions of their role(s). */
    Authentication authFor(String email) {
        UUID userId = userIdByEmail(email);
        Set<String> perms = Boolean.TRUE.equals(jdbc.queryForObject(
                "select is_owner from app_user where id = ?", Boolean.class, userId))
                ? permissions.allPermissions()
                : permissions.permissionsOf(userId);
        var authorities = perms.stream().map(SimpleGrantedAuthority::new).toList();
        return new UsernamePasswordAuthenticationToken(email, null, authorities);
    }

    UUID userIdByEmail(String email) {
        return jdbc.queryForObject(
                "select id from app_user where email = ?", UUID.class, email);
    }
}
