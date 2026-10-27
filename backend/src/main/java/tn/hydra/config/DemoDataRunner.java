package tn.hydra.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import tn.hydra.audit.EventLogService;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ============================================================================
 *  DemoDataRunner — seeds comprehensive Tunisian BTP demo data
 * ============================================================================
 *
 *  Runs AFTER BootstrapRunner creates the org + owner (CommandLineRunner
 *  ordering is by @Order value; BootstrapRunner has default order so we
 *  explicitly take a lower priority to ensure the org exists first).
 *
 *  Idempotent: checks if any demo user (id prefix 'aaaaaa00-') exists.
 *  If yes, skips entirely. Otherwise, executes the full demo seed:
 *
 *    - 15 demo users across 8 system roles (password: HydraDemo2026!)
 *    - 9 realistic projects across 9 Tunisian governorates
 *    - 28 project members (chef de projet, ingénieurs, conducteurs)
 *    - 63 doc folders (9 projects × 7 standard folders)
 *    - 40 documents (contracts, plans, factures, PV réception, rapports)
 *    - 10 parties (5 sous-traitants + 5 fournisseurs)
 *    - 9 project cash pots + uses existing COMPANY pot
 *    - 57 cash movements (24 IN + 9 TRANSFER + 24 OUT, 3 DRAFT + 1 VOID)
 *    - 10 engineering tickets (DRAFT → IN_REVIEW → APPROVED → IFC → AS_BUILT)
 *    - 5 PAYMENT_APPROVAL process instances (3 RUNNING + 1 COMPLETED + 1 REJECTED)
 *
 *  All UUIDs are DETERMINISTIC (literal strings) so re-runs produce stable
 *  references across tables. Idempotent via ON CONFLICT DO NOTHING everywhere.
 *
 *  To reset: drop+recreate the hydra DB, restart the backend.
 * ============================================================================
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)  // run after BootstrapRunner (HIGHEST_PRECEDENCE) so org + owner exist
public class DemoDataRunner implements CommandLineRunner {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final EventLogService audit;

    // Demo password hash is precomputed (BCrypt rounds=10) so we don't block
    // startup re-hashing 15 users. Verified for password "HydraDemo2026!".
    private static final String DEMO_PW_HASH =
            "$2b$10$69b2.rdAJdoBUijqrQuYAuYm0FqJKV7HmlP9GK1AyLixpRveBxZ6G";

    private static final UUID FIXED_DEMO_USER_PREFIX = UUID.fromString("aaaaaa00-0000-0000-0000-000000000001");

    public DemoDataRunner(JdbcTemplate jdbc, PasswordEncoder encoder, EventLogService audit) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.audit = audit;
    }

    @Override
    public void run(String... args) {
        // 1. Check if demo data FULLY seeded — process_instance is the LAST entity
        // we insert (we expect 5 with IDs 33333333-0000-0000-0000-00000000000{1..5}).
        // If they exist, the seed is fully complete; if we crashed mid-way, ON
        // CONFLICT DO NOTHING makes the per-method inserts idempotent so re-running
        // is safe and just fills in the gaps.
        Integer existingPi = jdbc.queryForObject(
                "select count(*) from process_instance where id::text like '33333333-%'",
                Integer.class);
        if (existingPi != null && existingPi >= 5) {
            return;  // demo seed already complete
        }

        // 2. Resolve org + owner + company_pot dynamically (created by BootstrapRunner)
        List<UUID> orgIds = jdbc.queryForList(
                "select id from organization order by created_at limit 1", UUID.class);
        if (orgIds.isEmpty()) {
            // Bootstrap hasn't run yet — skip; will run on next restart
            return;
        }
        UUID orgId = orgIds.get(0);

        List<UUID> ownerIds = jdbc.queryForList(
                "select id from app_user where is_owner = true limit 1", UUID.class);
        if (ownerIds.isEmpty()) return;
        UUID ownerId = ownerIds.get(0);

        // Ensure COMPANY pot exists (BootstrapRunner doesn't create it; create here)
        UUID companyPotId = ensureCompanyPot(orgId, ownerId);

        // 3. Seed all demo data — each block is idempotent via ON CONFLICT DO NOTHING
        seedUsers(orgId);
        seedUserRoles();
        seedProjects(orgId, ownerId);
        seedProjectMembers(orgId, ownerId);
        seedDocFolders();
        seedParties(orgId);
        seedProjectPots(orgId);
        seedCashInMovements(orgId, companyPotId, ownerId);
        seedCashTransferMovements(orgId, companyPotId, ownerId);
        seedCashOutMovements(orgId, companyPotId, ownerId);
        markVoidedMovement(orgId, ownerId);
        seedTickets(orgId);
        seedDocuments(orgId);
        seedProcessInstances(orgId);
        linkProcessInstancesToMovements();

        System.out.println("[DemoDataRunner] demo seed complete: 15 users + 9 projects + 10 parties + 9 pots + 57 movements + 40 documents + 10 tickets + 5 process instances");
    }

    // ══════════════════════════════════════════════════════════════
    //  Helpers — each inserts with ON CONFLICT DO NOTHING for idempotency
    // ══════════════════════════════════════════════════════════════

    /** Parse "2024-03-10 09:00:00+00" → java.sql.Timestamp (JDBC-friendly). */
    private static java.sql.Timestamp parseIso(String s) {
        if (s == null) return null;
        String iso = s.replace(' ', 'T');
        if (iso.endsWith("+00")) iso = iso.substring(0, iso.length() - 3) + "+00:00";
        return java.sql.Timestamp.from(
                OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant());
    }

    /** Same as parseIso but with a +5h offset (used for posted_at after created_at). */
    private static java.sql.Timestamp parseIsoPlus5h(String s) {
        if (s == null) return null;
        String iso = s.replace(' ', 'T');
        if (iso.endsWith("+00")) iso = iso.substring(0, iso.length() - 3) + "+00:00";
        return java.sql.Timestamp.from(
                OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().plusSeconds(5*3600));
    }

    private UUID ensureCompanyPot(UUID orgId, UUID ownerId) {
        List<UUID> existing = jdbc.queryForList(
                "select id from cash_pot where org_id = ? and type = 'COMPANY' and active = true limit 1",
                UUID.class, orgId);
        if (!existing.isEmpty()) return existing.get(0);

        UUID potId = UUID.randomUUID();
        jdbc.update("""
                insert into cash_pot (id, org_id, type, project_id, name, currency, active, created_at)
                values (?, ?, 'COMPANY', null, 'Caisse centrale', 'TND', true, now())
                """, potId, orgId);
        audit.logAs(orgId, ownerId, "CASH_POT_CREATED", "cash_pot", potId,
                "{\"type\":\"COMPANY\",\"name\":\"Caisse centrale\"}");
        return potId;
    }

    private void seedUsers(UUID orgId) {
        Object[][] users = {
                {"aaaaaa00-0000-0000-0000-000000000001", "karim.bouzid@hydra.tn",   "Karim Bouzid"},
                {"aaaaaa00-0000-0000-0000-000000000002", "sami.trabelsi@hydra.tn",  "Sami Trabelsi"},
                {"aaaaaa00-0000-0000-0000-000000000003", "leila.gharbi@hydra.tn",   "Leila Gharbi"},
                {"aaaaaa00-0000-0000-0000-000000000004", "mona.zribi@hydra.tn",     "Mona Zribi"},
                {"aaaaaa00-0000-0000-0000-000000000005", "mohamed.sassi@hydra.tn",  "Mohamed Sassi"},
                {"aaaaaa00-0000-0000-0000-000000000006", "nizar.amri@hydra.tn",     "Nizar Amri"},
                {"aaaaaa00-0000-0000-0000-000000000007", "wassim.jouini@hydra.tn",  "Wassim Jouini"},
                {"aaaaaa00-0000-0000-0000-000000000008", "ahlem.cherif@hydra.tn",   "Ahlem Cherif"},
                {"aaaaaa00-0000-0000-0000-000000000009", "fawzi.haddad@hydra.tn",   "Fawzi Haddad"},
                {"aaaaaa00-0000-0000-0000-000000000010", "rania.mansour@hydra.tn",  "Rania Mansour"},
                {"aaaaaa00-0000-0000-0000-000000000011", "bilal.khelil@hydra.tn",   "Bilal Khelil"},
                {"aaaaaa00-0000-0000-0000-000000000012", "asma.kridis@hydra.tn",    "Asma Kridis"},
                {"aaaaaa00-0000-0000-0000-000000000013", "houssem.brahmi@hydra.tn", "Houssem Brahmi"},
                {"aaaaaa00-0000-0000-0000-000000000014", "safa.mtibaa@hydra.tn",    "Safa Mtibaa"},
                {"aaaaaa00-0000-0000-0000-000000000015", "amel.riahi@hydra.tn",     "Amel Riahi"},
        };
        for (Object[] u : users) {
            jdbc.update("""
                    insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active, must_change_password)
                    values (?, ?, ?, ?, ?, false, true, false)
                    on conflict (email) do nothing
                    """, UUID.fromString((String) u[0]), orgId, u[1], DEMO_PW_HASH, u[2]);
        }
    }

    private void seedUserRoles() {
        Object[][] roles = {
                {"karim.bouzid@hydra.tn",   "CHEF_PROJET"},
                {"karim.bouzid@hydra.tn",   "CONDUCTEUR"},
                {"sami.trabelsi@hydra.tn",  "CHEF_PROJET"},
                {"leila.gharbi@hydra.tn",   "CHEF_PROJET"},
                {"mona.zribi@hydra.tn",     "CHEF_PROJET"},
                {"mohamed.sassi@hydra.tn", "CONDUCTEUR"},
                {"nizar.amri@hydra.tn",    "CONDUCTEUR"},
                {"wassim.jouini@hydra.tn", "CONDUCTEUR"},
                {"ahlem.cherif@hydra.tn",  "INGENIEUR"},
                {"fawzi.haddad@hydra.tn",  "INGENIEUR"},
                {"rania.mansour@hydra.tn", "QA"},
                {"bilal.khelil@hydra.tn",  "CONDUCTEUR"},
                {"asma.kridis@hydra.tn",   "CONDUCTEUR"},
                {"houssem.brahmi@hydra.tn","ACHAT"},
                {"safa.mtibaa@hydra.tn",   "FINANCE"},
                {"amel.riahi@hydra.tn",    "BUREAU"},
        };
        for (Object[] r : roles) {
            jdbc.update("""
                    insert into user_role (user_id, role_id, assigned_by, assigned_at)
                    select u.id, rl.id, u.id, now()
                    from app_user u cross join role rl
                    where u.email = ? and rl.code = ?
                    and not exists (
                        select 1 from user_role ur where ur.user_id = u.id and ur.role_id = rl.id
                    )
                    """, r[0], r[1]);
        }
    }

    private void seedProjects(UUID orgId, UUID ownerId) {
        Object[][] projects = {
                {"bbbbbb00-0000-0000-0000-000000000001", "ADD-ADR-2024", "Adduction Aïn Draham", "ACTIF", "SONEDE Région Nord", "Aïn Draham, Jendouba", "Jendouba", "2024-03-01", "2025-06-30", "ADDUCTION", "Réseau d'adduction d'eau potable desservant les quartiers nord d'Aïn Draham. 12 km de conduites PEHD DN 200 et DN 300, 1 réservoir 500 m³, 3 chambres de vanne.", 1850000.000, "EXECUTION"},
                {"bbbbbb00-0000-0000-0000-000000000002", "IRR-JND-2024", "Réseau Irrigation Jendouba", "ACTIF", "CRDA Jendouba", "Jendouba Sud", "Jendouba", "2024-05-15", "2025-09-30", "IRRIGATION", "Périmètre irrigué sur 350 hectares. Réseau secondaire en PVC DN 110-250, 18 bornes d'irrigation, station de pompage 75 kW.", 920000.000, "EXECUTION"},
                {"bbbbbb00-0000-0000-0000-000000000003", "RES-KEF-2023", "Réservoir 1000m³ Le Kef", "ACTIF", "SONEDE Centre", "Le Kef Est", "Le Kef", "2023-09-01", "2024-12-15", "RESERVOIR", "Réservoir semi-enterré 1000 m³ en béton armé avec étanchéité intérieure. Chambre de vanne associée.", 1450000.000, "RECEPTION"},
                {"bbbbbb00-0000-0000-0000-000000000004", "CV-BJA-2024", "Chambre de Vanne Béja", "ACTIF", "SONEDE Nord-Ouest", "Béja Centre", "Béja", "2024-06-01", "2024-11-30", "VANNE", "Chambre de vanne 4x3 m avec 6 vannes DN 100-200, instrumentation de téléconduite et pompe de relevage.", 320000.000, "EXECUTION"},
                {"bbbbbb00-0000-0000-0000-000000000005", "FOR-SBZ-2024", "Forage Village Sidi Bouzid", "ACTIF", "CRDA Sidi Bouzid", "Sidi Bouzid Rural", "Sidi Bouzid", "2024-04-01", "2024-12-20", "FORAGE", "Forage profond 180 m, pompe immergée 22 kW, réservoir 200 m³ surélevé, 4 km de réseau de distribution rurale.", 680000.000, "EXECUTION"},
                {"bbbbbb00-0000-0000-0000-000000000006", "PMP-NBL-2024", "Station de Pompage Nabeul", "ACTIF", "SONEDE Cap Bon", "Nabeul Sud", "Nabeul", "2024-02-01", "2024-12-31", "POMPAGE", "Station de pompage 250 kW avec 3 pompes (2+1), génie civil bâtiment 8x6 m, armoire électrique, téléconduite.", 1180000.000, "EXECUTION"},
                {"bbbbbb00-0000-0000-0000-000000000007", "ASS-SFX-2023", "Assainissement Sfax Sud", "ACTIF", "ONAS Sfax", "Sfax Sud", "Sfax", "2023-06-01", "2024-10-31", "ASSAINISSEMENT", "Réseau d'assainissement collectif 8 km en PVC DN 200-400, 120 regards de visite, station de refoulement 30 kW.", 1620000.000, "RECEPTION"},
                {"bbbbbb00-0000-0000-0000-000000000008", "REH-SUS-2022", "Réhabilitation Réseau Sousse", "CLOTURE", "SONEDE Sahel", "Sousse Centre", "Sousse", "2022-09-01", "2023-08-31", "REHABILITATION", "Réhabilitation de 6 km de conduites vieillissantes en fonte DN 150, remplacement de 80 branchements.", 740000.000, "CLOSEOUT"},
                {"bbbbbb00-0000-0000-0000-000000000009", "BAS-MDN-2024", "Bassin de Rétention Médenine", "ACTIF", "Ministère de l'Agriculture", "Médenine Sud-Est", "Médenine", "2024-07-01", "2025-08-31", "BASSIN", "Bassin de rétention des eaux de crue 50 000 m³, digue en terre 8 m hauteur, évacuateur en béton.", 980000.000, "DESIGN"},
        };
        for (Object[] p : projects) {
            jdbc.update("""
                    insert into project (id, org_id, code, name, status, client_name, location, governorate,
                                         start_date, end_date, project_type, description, contract_value, currency, phase, created_by, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'TND', ?, ?, now())
                    on conflict (id) do nothing
                    """,
                    UUID.fromString((String) p[0]), orgId, p[1], p[2], p[3], p[4], p[5], p[6],
                    java.sql.Date.valueOf(p[7].toString()), java.sql.Date.valueOf(p[8].toString()),
                    p[9], p[10], new java.math.BigDecimal(p[11].toString()), p[12], ownerId);
        }
    }

    private void seedProjectMembers(UUID orgId, UUID ownerId) {
        Object[][] members = {
                {"bbbbbb00-0000-0000-0000-000000000001", "sami.trabelsi@hydra.tn",  "ADMIN"},
                {"bbbbbb00-0000-0000-0000-000000000001", "ahlem.cherif@hydra.tn",  "ENGINEER"},
                {"bbbbbb00-0000-0000-0000-000000000001", "mohamed.sassi@hydra.tn", "ENGINEER"},
                {"bbbbbb00-0000-0000-0000-000000000001", "rania.mansour@hydra.tn", "VIEWER"},
                {"bbbbbb00-0000-0000-0000-000000000002", "leila.gharbi@hydra.tn",   "ADMIN"},
                {"bbbbbb00-0000-0000-0000-000000000002", "fawzi.haddad@hydra.tn",  "ENGINEER"},
                {"bbbbbb00-0000-0000-0000-000000000002", "nizar.amri@hydra.tn",    "ENGINEER"},
                {"bbbbbb00-0000-0000-0000-000000000003", "sami.trabelsi@hydra.tn",  "ADMIN"},
                {"bbbbbb00-0000-0000-0000-000000000003", "fawzi.haddad@hydra.tn",  "ENGINEER"},
                {"bbbbbb00-0000-0000-0000-000000000003", "rania.mansour@hydra.tn", "VIEWER"},
                {"bbbbbb00-0000-0000-0000-000000000004", "mona.zribi@hydra.tn",     "ADMIN"},
                {"bbbbbb00-0000-0000-0000-000000000004", "wassim.jouini@hydra.tn", "ENGINEER"},
                {"bbbbbb00-0000-0000-0000-000000000005", "leila.gharbi@hydra.tn",   "ADMIN"},
                {"bbbbbb00-0000-0000-0000-000000000005", "ahlem.cherif@hydra.tn",  "ENGINEER"},
                {"bbbbbb00-0000-0000-0000-000000000005", "bilal.khelil@hydra.tn",  "ENGINEER"},
                {"bbbbbb00-0000-0000-0000-000000000006", "sami.trabelsi@hydra.tn",  "ADMIN"},
                {"bbbbbb00-0000-0000-0000-000000000006", "fawzi.haddad@hydra.tn",  "ENGINEER"},
                {"bbbbbb00-0000-0000-0000-000000000006", "asma.kridis@hydra.tn",   "ENGINEER"},
                {"bbbbbb00-0000-0000-0000-000000000006", "rania.mansour@hydra.tn", "VIEWER"},
                {"bbbbbb00-0000-0000-0000-000000000007", "mona.zribi@hydra.tn",     "ADMIN"},
                {"bbbbbb00-0000-0000-0000-000000000007", "ahlem.cherif@hydra.tn",  "ENGINEER"},
                {"bbbbbb00-0000-0000-0000-000000000007", "mohamed.sassi@hydra.tn", "ENGINEER"},
                {"bbbbbb00-0000-0000-0000-000000000007", "rania.mansour@hydra.tn", "VIEWER"},
                {"bbbbbb00-0000-0000-0000-000000000008", "leila.gharbi@hydra.tn",   "ADMIN"},
                {"bbbbbb00-0000-0000-0000-000000000008", "fawzi.haddad@hydra.tn",  "VIEWER"},
                {"bbbbbb00-0000-0000-0000-000000000009", "karim.bouzid@hydra.tn",   "ADMIN"},
                {"bbbbbb00-0000-0000-0000-000000000009", "ahlem.cherif@hydra.tn",  "ENGINEER"},
                {"bbbbbb00-0000-0000-0000-000000000009", "fawzi.haddad@hydra.tn",  "ENGINEER"},
        };
        for (Object[] m : members) {
            jdbc.update("""
                    insert into project_member (project_id, user_id, project_role, can_upload, created_by, created_at)
                    select p.id, u.id, ?, true, ?, now()
                    from project p cross join app_user u
                    where p.id::text = ? and u.email = ?
                    and not exists (
                        select 1 from project_member pm where pm.project_id = p.id and pm.user_id = u.id
                    )
                    """, m[2], ownerId, m[0], m[1]);
        }
    }

    private void seedDocFolders() {
        // 9 projects × 7 folders (deterministic IDs: eeeeee00-...-PPFF00000000)
        Object[][] projects = {
                {1, "bbbbbb00-0000-0000-0000-000000000001"},
                {2, "bbbbbb00-0000-0000-0000-000000000002"},
                {3, "bbbbbb00-0000-0000-0000-000000000003"},
                {4, "bbbbbb00-0000-0000-0000-000000000004"},
                {5, "bbbbbb00-0000-0000-0000-000000000005"},
                {6, "bbbbbb00-0000-0000-0000-000000000006"},
                {7, "bbbbbb00-0000-0000-0000-000000000007"},
                {8, "bbbbbb00-0000-0000-0000-000000000008"},
                {9, "bbbbbb00-0000-0000-0000-000000000009"},
        };
        for (Object[] p : projects) {
            int idx = (int) p[0];
            String projectId = (String) p[1];
            List<Map<String, Object>> folders = jdbc.queryForList(
                    "select code, name, sort_order from doc_folder_template where org_id is null order by sort_order");
            for (Map<String, Object> f : folders) {
                String code = (String) f.get("code");
                String name = (String) f.get("name");
                int sort = ((Number) f.get("sort_order")).intValue();
                String folderIdStr = String.format("eeeeee00-0000-0000-0000-%02d%02d00000000", idx, sort);
                UUID folderId = UUID.fromString(folderIdStr);
                jdbc.update("""
                        insert into doc_folder (id, project_id, parent_id, code, name, sort_order, created_at)
                        select ?, p.id, null, ?, ?, ?, now()
                        from project p where p.id::text = ?
                        and not exists (select 1 from doc_folder df where df.id = ?)
                        """, folderId, code, name, sort, projectId, folderId);
            }
        }
    }

    private void seedParties(UUID orgId) {
        Object[][] parties = {
                {"dddddd00-0000-0000-0000-000000000001", "Pipetech SARL",          "SUB",      "+216 71 902 415"},
                {"dddddd00-0000-0000-0000-000000000002", "BTP Tunisie Sud SARL",  "SUB",      "+216 74 551 230"},
                {"dddddd00-0000-0000-0000-000000000003", "Hydro-Forage SARL",      "SUB",      "+216 76 318 540"},
                {"dddddd00-0000-0000-0000-000000000004", "Soudure Métal Plus",     "SUB",      "+216 73 220 814"},
                {"dddddd00-0000-0000-0000-000000000005", "Électricité Sahel SARL", "SUB",      "+216 73 667 921"},
                {"dddddd00-0000-0000-0000-000000000006", "Bricomat SARL",           "SUPPLIER", "+216 71 945 110"},
                {"dddddd00-0000-0000-0000-000000000007", "Cimenterie Tunisienne",  "SUPPLIER", "+216 72 305 660"},
                {"dddddd00-0000-0000-0000-000000000008", "PVC Distribution SARL",  "SUPPLIER", "+216 71 805 432"},
                {"dddddd00-0000-0000-0000-000000000009", "Acier Tunis SARL",        "SUPPLIER", "+216 73 270 880"},
                {"dddddd00-0000-0000-0000-000000000010", "Pompes & Moteurs TN",    "SUPPLIER", "+216 71 889 615"},
        };
        for (Object[] p : parties) {
            jdbc.update("""
                    insert into party (id, org_id, name, type, phone, active, created_at, updated_at)
                    values (?, ?, ?, ?, ?, true, now(), now())
                    on conflict (id) do nothing
                    """, UUID.fromString((String) p[0]), orgId, p[1], p[2], p[3]);
        }
    }

    private void seedProjectPots(UUID orgId) {
        Object[][] pots = {
                {1, "bbbbbb00-0000-0000-0000-000000000001", "Adduction Aïn Draham"},
                {2, "bbbbbb00-0000-0000-0000-000000000002", "Irrigation Jendouba"},
                {3, "bbbbbb00-0000-0000-0000-000000000003", "Réservoir Le Kef"},
                {4, "bbbbbb00-0000-0000-0000-000000000004", "Chambre Vanne Béja"},
                {5, "bbbbbb00-0000-0000-0000-000000000005", "Forage Sidi Bouzid"},
                {6, "bbbbbb00-0000-0000-0000-000000000006", "Pompage Nabeul"},
                {7, "bbbbbb00-0000-0000-0000-000000000007", "Assainissement Sfax"},
                {8, "bbbbbb00-0000-0000-0000-000000000008", "Réhabilitation Sousse"},
                {9, "bbbbbb00-0000-0000-0000-000000000009", "Bassin Médenine"},
        };
        for (Object[] p : pots) {
            int idx = (int) p[0];
            String projectId = (String) p[1];
            String name = (String) p[2];
            String potIdStr = String.format("cccccc00-0000-0000-0000-0000000000%02d", idx);
            UUID potId = UUID.fromString(potIdStr);
            jdbc.update("""
                    insert into cash_pot (id, org_id, type, project_id, name, currency, active, created_at)
                    select ?, ?, 'PROJECT', p.id, ?, 'TND', true, now()
                    from project p where p.id::text = ?
                    and not exists (select 1 from cash_pot cp where cp.id = ?)
                    """, potId, orgId, "Caisse " + name, projectId, potId);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  CASH MOVEMENTS — IN / TRANSFER / OUT
    // ══════════════════════════════════════════════════════════════

    private void seedCashInMovements(UUID orgId, UUID companyPotId, UUID ownerId) {
        Object[][] ins = {
                {"11111111-0000-0000-0000-000000000001", 555000.000, "2024-03-10", "bbbbbb00-0000-0000-0000-000000000001", "Acompte 30% SONEDE Adduction Aïn Draham", "POSTED", "2024-03-10 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000002", 462500.000, "2024-05-15", "bbbbbb00-0000-0000-0000-000000000001", "Acompte 25% SONEDE Adduction Aïn Draham", "POSTED", "2024-05-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000003", 370000.000, "2024-08-20", "bbbbbb00-0000-0000-0000-000000000001", "Acompte 20% SONEDE Adduction Aïn Draham", "POSTED", "2024-08-20 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000004", 462500.000, "2024-11-15", "bbbbbb00-0000-0000-0000-000000000001", "Solde final SONEDE Adduction Aïn Draham", "POSTED", "2024-11-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000005", 276000.000, "2024-05-20", "bbbbbb00-0000-0000-0000-000000000002", "Acompte 30% CRDA Jendouba", "POSTED", "2024-05-20 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000006", 276000.000, "2024-08-10", "bbbbbb00-0000-0000-0000-000000000002", "Acompte 30% CRDA Jendouba", "POSTED", "2024-08-10 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000007", 368000.000, "2024-11-05", "bbbbbb00-0000-0000-0000-000000000002", "Solde 40% CRDA Jendouba", "DRAFT", "2024-11-05 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000008", 435000.000, "2023-09-10", "bbbbbb00-0000-0000-0000-000000000003", "Acompte 30% SONEDE Centre - Réservoir", "POSTED", "2023-09-10 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000009", 435000.000, "2024-02-15", "bbbbbb00-0000-0000-0000-000000000003", "Acompte 30% SONEDE Centre - Réservoir", "POSTED", "2024-02-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000010", 580000.000, "2024-09-01", "bbbbbb00-0000-0000-0000-000000000003", "Solde 40% SONEDE Centre - Réservoir", "POSTED", "2024-09-01 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000011", 128000.000, "2024-06-10", "bbbbbb00-0000-0000-0000-000000000004", "Acompte 40% SONEDE NO - Chambre Vanne", "POSTED", "2024-06-10 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000012", 192000.000, "2024-09-15", "bbbbbb00-0000-0000-0000-000000000004", "Solde 60% SONEDE NO - Chambre Vanne", "POSTED", "2024-09-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000013", 204000.000, "2024-04-10", "bbbbbb00-0000-0000-0000-000000000005", "Acompte 30% CRDA SBZ - Forage", "POSTED", "2024-04-10 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000014", 476000.000, "2024-10-20", "bbbbbb00-0000-0000-0000-000000000005", "Solde 70% CRDA SBZ - Forage", "POSTED", "2024-10-20 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000015", 354000.000, "2024-02-10", "bbbbbb00-0000-0000-0000-000000000006", "Acompte 30% SONEDE Cap Bon - Pompage", "POSTED", "2024-02-10 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000016", 472000.000, "2024-06-15", "bbbbbb00-0000-0000-0000-000000000006", "Acompte 40% SONEDE Cap Bon - Pompage", "POSTED", "2024-06-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000017", 354000.000, "2024-10-30", "bbbbbb00-0000-0000-0000-000000000006", "Solde 30% SONEDE Cap Bon - Pompage", "POSTED", "2024-10-30 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000018", 486000.000, "2023-06-15", "bbbbbb00-0000-0000-0000-000000000007", "Acompte 30% ONAS Sfax - Assainissement", "POSTED", "2023-06-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000019", 486000.000, "2023-12-20", "bbbbbb00-0000-0000-0000-000000000007", "Acompte 30% ONAS Sfax", "POSTED", "2023-12-20 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000020", 648000.000, "2024-08-15", "bbbbbb00-0000-0000-0000-000000000007", "Solde 40% ONAS Sfax", "POSTED", "2024-08-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000021", 222000.000, "2022-09-10", "bbbbbb00-0000-0000-0000-000000000008", "Acompte 30% SONEDE Sahel - Réhab Sousse", "POSTED", "2022-09-10 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000022", 370000.000, "2023-02-20", "bbbbbb00-0000-0000-0000-000000000008", "Acompte 50% SONEDE Sahel", "POSTED", "2023-02-20 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000023", 148000.000, "2023-09-15", "bbbbbb00-0000-0000-0000-000000000008", "Solde 20% SONEDE Sahel", "POSTED", "2023-09-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000024", 294000.000, "2024-07-15", "bbbbbb00-0000-0000-0000-000000000009", "Acompte 30% Min Agri - Bassin Médenine", "POSTED", "2024-07-15 09:00:00+00"},
        };
        for (Object[] m : ins) {
            String status = (String) m[5];
            UUID poster = "POSTED".equals(status) ? ownerId : null;
            java.sql.Timestamp postedAt = "POSTED".equals(status)
                    ? parseIsoPlus5h((String) m[6])
                    : null;
            jdbc.update("""
                    insert into cash_movement (id, org_id, movement_type, amount, currency, movement_date,
                                               from_pot_id, to_pot_id, project_id, party_id, label, note, status,
                                               created_by, created_at, posted_by, posted_at)
                    values (?, ?, 'IN', ?, 'TND', ?, null, ?, ?, null, ?, null, ?, ?, ?, ?, ?)
                    on conflict (id) do nothing
                    """,
                    UUID.fromString((String) m[0]), orgId, new java.math.BigDecimal(m[1].toString()),
                    java.sql.Date.valueOf(m[2].toString()), companyPotId, UUID.fromString((String) m[3]),
                    m[4], status, ownerId,
                    parseIso((String) m[6]),
                    poster, postedAt);
        }
    }

    private void seedCashTransferMovements(UUID orgId, UUID companyPotId, UUID ownerId) {
        Object[][] transfers = {
                {"11111111-0000-0000-0000-000000000025", 300000.000, "2024-03-15", "cccccc00-0000-0000-0000-000000000001", "bbbbbb00-0000-0000-0000-000000000001", "Transfert Caisse centrale → Adduction Aïn Draham", "POSTED", "2024-03-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000026", 200000.000, "2024-05-25", "cccccc00-0000-0000-0000-000000000002", "bbbbbb00-0000-0000-0000-000000000002", "Transfert Caisse centrale → Irrigation Jendouba", "POSTED", "2024-05-25 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000027", 250000.000, "2023-09-15", "cccccc00-0000-0000-0000-000000000003", "bbbbbb00-0000-0000-0000-000000000003", "Transfert Caisse centrale → Réservoir Le Kef", "POSTED", "2023-09-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000028", 100000.000, "2024-06-15", "cccccc00-0000-0000-0000-000000000004", "bbbbbb00-0000-0000-0000-000000000004", "Transfert Caisse centrale → Chambre Vanne Béja", "POSTED", "2024-06-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000029", 150000.000, "2024-04-15", "cccccc00-0000-0000-0000-000000000005", "bbbbbb00-0000-0000-0000-000000000005", "Transfert Caisse centrale → Forage Sidi Bouzid", "POSTED", "2024-04-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000030", 300000.000, "2024-02-15", "cccccc00-0000-0000-0000-000000000006", "bbbbbb00-0000-0000-0000-000000000006", "Transfert Caisse centrale → Pompage Nabeul", "POSTED", "2024-02-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000031", 280000.000, "2023-06-20", "cccccc00-0000-0000-0000-000000000007", "bbbbbb00-0000-0000-0000-000000000007", "Transfert Caisse centrale → Assainissement Sfax", "POSTED", "2023-06-20 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000032", 150000.000, "2022-09-15", "cccccc00-0000-0000-0000-000000000008", "bbbbbb00-0000-0000-0000-000000000008", "Transfert Caisse centrale → Réhabilitation Sousse", "POSTED", "2022-09-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000033", 100000.000, "2024-07-20", "cccccc00-0000-0000-0000-000000000009", "bbbbbb00-0000-0000-0000-000000000009", "Transfert Caisse centrale → Bassin Médenine", "DRAFT", "2024-07-20 09:00:00+00"},
        };
        for (Object[] m : transfers) {
            String status = (String) m[6];
            UUID poster = "POSTED".equals(status) ? ownerId : null;
            java.sql.Timestamp postedAt = "POSTED".equals(status)
                    ? parseIsoPlus5h((String) m[7])
                    : null;
            jdbc.update("""
                    insert into cash_movement (id, org_id, movement_type, amount, currency, movement_date,
                                               from_pot_id, to_pot_id, project_id, party_id, label, note, status,
                                               created_by, created_at, posted_by, posted_at)
                    values (?, ?, 'TRANSFER', ?, 'TND', ?, ?, ?, ?, null, ?, null, ?, ?, ?, ?, ?)
                    on conflict (id) do nothing
                    """,
                    UUID.fromString((String) m[0]), orgId, new java.math.BigDecimal(m[1].toString()),
                    java.sql.Date.valueOf(m[2].toString()), companyPotId, UUID.fromString((String) m[3]),
                    UUID.fromString((String) m[4]), m[5], status, ownerId,
                    parseIso((String) m[7]),
                    poster, postedAt);
        }
    }

    private void seedCashOutMovements(UUID orgId, UUID companyPotId, UUID ownerId) {
        Object[][] outs = {
                {"11111111-0000-0000-0000-000000000034", 145000.000, "2024-03-25", "cccccc00-0000-0000-0000-000000000001", "bbbbbb00-0000-0000-0000-000000000001", "dddddd00-0000-0000-0000-000000000001", "Paiement Pipetech SARL - fourniture PEHD DN 200/300", "POSTED", "2024-03-25 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000035", 85000.000,  "2024-05-30", "cccccc00-0000-0000-0000-000000000001", "bbbbbb00-0000-0000-0000-000000000001", "dddddd00-0000-0000-0000-000000000007", "Paiement Cimenterie Tunisienne - béton réservoir", "POSTED", "2024-05-30 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000036", 95000.000,  "2024-07-10", "cccccc00-0000-0000-0000-000000000001", "bbbbbb00-0000-0000-0000-000000000001", "dddddd00-0000-0000-0000-000000000002", "Paiement BTP Tunisie Sud - terrassement tranchées", "POSTED", "2024-07-10 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000037", 38000.000,  "2024-09-05", "cccccc00-0000-0000-0000-000000000001", "bbbbbb00-0000-0000-0000-000000000001", "dddddd00-0000-0000-0000-000000000004", "Paiement Soudure Métal Plus - bouches à clé", "POSTED", "2024-09-05 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000038", 28000.000,  "2024-10-15", "cccccc00-0000-0000-0000-000000000001", "bbbbbb00-0000-0000-0000-000000000001", "dddddd00-0000-0000-0000-000000000005", "Paiement Électricité Sahel - alimentation chambre vanne", "DRAFT", "2024-10-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000039", 110000.000, "2024-06-15", "cccccc00-0000-0000-0000-000000000002", "bbbbbb00-0000-0000-0000-000000000002", "dddddd00-0000-0000-0000-000000000008", "Paiement PVC Distribution - tuyaux PVC DN 110-250", "POSTED", "2024-06-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000040", 75000.000,  "2024-08-25", "cccccc00-0000-0000-0000-000000000002", "bbbbbb00-0000-0000-0000-000000000002", "dddddd00-0000-0000-0000-000000000002", "Paiement BTP Tunisie Sud - terrassement", "POSTED", "2024-08-25 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000041", 165000.000, "2023-10-20", "cccccc00-0000-0000-0000-000000000003", "bbbbbb00-0000-0000-0000-000000000003", "dddddd00-0000-0000-0000-000000000002", "Paiement BTP Tunisie Sud - terrassement réservoir", "POSTED", "2023-10-20 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000042", 92000.000,  "2024-01-15", "cccccc00-0000-0000-0000-000000000003", "bbbbbb00-0000-0000-0000-000000000003", "dddddd00-0000-0000-0000-000000000007", "Paiement Cimenterie - béton armé réservoir", "POSTED", "2024-01-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000043", 28000.000,  "2024-09-25", "cccccc00-0000-0000-0000-000000000003", "bbbbbb00-0000-0000-0000-000000000003", "dddddd00-0000-0000-0000-000000000004", "Paiement Soudure Métal Plus - garde-corps réservoir", "POSTED", "2024-09-25 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000044", 50000.000,  "2024-04-01", "cccccc00-0000-0000-0000-000000000003", "bbbbbb00-0000-0000-0000-000000000003", "dddddd00-0000-0000-0000-000000000009", "Paiement Acier Tunis - armatures (annulé - doublon)", "VOID", "2024-04-01 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000045", 45000.000,  "2024-07-05", "cccccc00-0000-0000-0000-000000000004", "bbbbbb00-0000-0000-0000-000000000004", "dddddd00-0000-0000-0000-000000000009", "Paiement Acier Tunis - structure métallique chambre", "POSTED", "2024-07-05 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000046", 38000.000,  "2024-09-20", "cccccc00-0000-0000-0000-000000000004", "bbbbbb00-0000-0000-0000-000000000004", "dddddd00-0000-0000-0000-000000000010", "Paiement Pompes & Moteurs TN - vannes DN 100-200", "POSTED", "2024-09-20 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000047", 185000.000, "2024-05-10", "cccccc00-0000-0000-0000-000000000005", "bbbbbb00-0000-0000-0000-000000000005", "dddddd00-0000-0000-0000-000000000003", "Paiement Hydro-Forage SARL - forage 180m + pompe", "POSTED", "2024-05-10 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000048", 65000.000,  "2024-07-15", "cccccc00-0000-0000-0000-000000000005", "bbbbbb00-0000-0000-0000-000000000005", "dddddd00-0000-0000-0000-000000000008", "Paiement PVC Distribution - réseau rural 4km", "POSTED", "2024-07-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000049", 245000.000, "2024-03-25", "cccccc00-0000-0000-0000-000000000006", "bbbbbb00-0000-0000-0000-000000000006", "dddddd00-0000-0000-0000-000000000010", "Paiement Pompes & Moteurs TN - 3 pompes 250kW", "POSTED", "2024-03-25 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000050", 92000.000,  "2024-05-20", "cccccc00-0000-0000-0000-000000000006", "bbbbbb00-0000-0000-0000-000000000006", "dddddd00-0000-0000-0000-000000000009", "Paiement Acier Tunis - charpente bâtiment", "POSTED", "2024-05-20 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000051", 68000.000,  "2024-08-10", "cccccc00-0000-0000-0000-000000000006", "bbbbbb00-0000-0000-0000-000000000006", "dddddd00-0000-0000-0000-000000000005", "Paiement Électricité Sahel - armoire + câblage", "POSTED", "2024-08-10 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000052", 130000.000, "2023-07-25", "cccccc00-0000-0000-0000-000000000007", "bbbbbb00-0000-0000-0000-000000000007", "dddddd00-0000-0000-0000-000000000008", "Paiement PVC Distribution - tuyaux DN 200-400", "POSTED", "2023-07-25 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000053", 75000.000,  "2023-11-15", "cccccc00-0000-0000-0000-000000000007", "bbbbbb00-0000-0000-0000-000000000007", "dddddd00-0000-0000-0000-000000000002", "Paiement BTP Tunisie Sud - terrassement 8km", "POSTED", "2023-11-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000054", 42000.000,  "2024-04-10", "cccccc00-0000-0000-0000-000000000007", "bbbbbb00-0000-0000-0000-000000000007", "dddddd00-0000-0000-0000-000000000010", "Paiement Pompes & Moteurs TN - station refoulement 30kW", "POSTED", "2024-04-10 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000055", 55000.000,  "2024-08-20", "cccccc00-0000-0000-0000-000000000007", "bbbbbb00-0000-0000-0000-000000000007", "dddddd00-0000-0000-0000-000000000006", "Paiement Bricomat - regards visite 120u", "DRAFT", "2024-08-20 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000056", 110000.000, "2022-10-15", "cccccc00-0000-0000-0000-000000000008", "bbbbbb00-0000-0000-0000-000000000008", "dddddd00-0000-0000-0000-000000000002", "Paiement BTP Tunisie Sud - remplacement 6km conduite", "POSTED", "2022-10-15 09:00:00+00"},
                {"11111111-0000-0000-0000-000000000057", 88000.000,  "2023-02-25", "cccccc00-0000-0000-0000-000000000008", "bbbbbb00-0000-0000-0000-000000000008", "dddddd00-0000-0000-0000-000000000006", "Paiement Bricomat - branchements 80u", "POSTED", "2023-02-25 09:00:00+00"},
        };
        for (Object[] m : outs) {
            String status = (String) m[7];
            UUID poster = "POSTED".equals(status) ? ownerId : null;
            java.sql.Timestamp postedAt = "POSTED".equals(status)
                    ? parseIsoPlus5h((String) m[8])
                    : null;
            jdbc.update("""
                    insert into cash_movement (id, org_id, movement_type, amount, currency, movement_date,
                                               from_pot_id, to_pot_id, project_id, party_id, label, note, status,
                                               created_by, created_at, posted_by, posted_at)
                    values (?, ?, 'OUT', ?, 'TND', ?, ?, null, ?, ?, ?, null, ?, ?, ?, ?, ?)
                    on conflict (id) do nothing
                    """,
                    UUID.fromString((String) m[0]), orgId, new java.math.BigDecimal(m[1].toString()),
                    java.sql.Date.valueOf(m[2].toString()), UUID.fromString((String) m[3]),
                    UUID.fromString((String) m[4]), UUID.fromString((String) m[5]), m[6], status, ownerId,
                    parseIso((String) m[8]),
                    poster, postedAt);
        }
    }

    private void markVoidedMovement(UUID orgId, UUID ownerId) {
        // Update the VOID movement (#044) with void_reason + voided_by + voided_at
        jdbc.update("""
                update cash_movement
                set void_reason = 'Doublon de paiement détecté - facture ACN-2024-0156 déjà payée le 2024-03-30. Mouvement annulé et remplacé.',
                    voided_by = ?, voided_at = ?
                where id = '11111111-0000-0000-0000-000000000044' and status = 'VOID' and void_reason is null
                """, ownerId, parseIso("2024-04-03 09:00:00+00"));
    }

    private void seedTickets(UUID orgId) {
        Object[][] tickets = {
                {"22222222-0000-0000-0000-000000000001", "Plans exécution conduite PEHD DN300", "Soumission plans exécution pour validation client. 12km à valider.", "IN_REVIEW", "sami.trabelsi@hydra.tn", "2024-03-20 14:30:00+00", null},
                {"22222222-0000-0000-0000-000000000002", "Procès-verbal réception provisoire réservoir Le Kef", "PV de réception provisoire à valider par QA.", "APPROVED", "sami.trabelsi@hydra.tn", "2024-09-01 10:00:00+00", "2024-09-10 14:00:00+00"},
                {"22222222-0000-0000-0000-000000000003", "Note de calcul - fondation station pompage", "Note de calcul béton armé pour fondation à valider.", "IN_REVIEW", "fawzi.haddad@hydra.tn", "2024-03-10 11:00:00+00", null},
                {"22222222-0000-0000-0000-000000000004", "Plan coffrage - réservoir 1000m³", "Plan coffrage + ferraillage à valider.", "APPROVED", "ahlem.cherif@hydra.tn", "2023-10-05 09:00:00+00", "2023-10-15 16:00:00+00"},
                {"22222222-0000-0000-0000-000000000005", "Rapport essai pression - réseau Sfax", "Rapport d'essai de pression à 12 bar - 8km réseau assainissement.", "IFC", "mona.zribi@hydra.tn", "2024-07-25 14:00:00+00", null},
                {"22222222-0000-0000-0000-000000000006", "Carnet de chantier - Adduction Aïn Draham", "Carnet de chantier phases 1-4 pour archivage.", "DRAFT", "mohamed.sassi@hydra.tn", "2024-10-01 15:00:00+00", null},
                {"22222222-0000-0000-0000-000000000007", "Plans IFD - chambre de vanne Béja", "Plans Issued For Design à valider client.", "IN_REVIEW", "mona.zribi@hydra.tn", "2024-06-25 10:00:00+00", null},
                {"22222222-0000-0000-0000-000000000008", "Dossier As-Built - Réhabilitation Sousse", "Dossier complet As-Built final après réception définitive.", "AS_BUILT", "leila.gharbi@hydra.tn", "2023-08-10 09:00:00+00", "2023-08-31 14:00:00+00"},
                {"22222222-0000-0000-0000-000000000009", "Étude de sol - Bassin Médenine", "Étude géotechnique pour digue 8m - rapport à valider.", "DRAFT", "ahlem.cherif@hydra.tn", "2024-08-15 14:00:00+00", null},
                {"22222222-0000-0000-0000-000000000010", "Plan de prévention HSE - Forage Sidi Bouzid", "Plan de prévention HSE pour phase forage.", "APPROVED", "leila.gharbi@hydra.tn", "2024-04-15 10:00:00+00", "2024-04-25 14:00:00+00"},
        };
        for (Object[] t : tickets) {
            String createdEmail = (String) t[4];
            String createdStr = (String) t[5];
            String resolvedStr = (String) t[6];
            java.sql.Timestamp created = parseIso(createdStr);
            java.sql.Timestamp resolved = resolvedStr == null ? null : parseIso(resolvedStr);
            // Look up the user id (for both created_by + resolved_by — same user in this demo)
            UUID createdById = jdbc.queryForObject(
                    "select id from app_user where email = ?", UUID.class, createdEmail);
            jdbc.update("""
                    insert into ticket (id, org_id, title, description, status, created_by, created_at, resolved_at, resolved_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (id) do nothing
                    """,
                    UUID.fromString((String) t[0]), orgId, t[1], t[2], t[3],
                    createdById, created, resolved, resolved == null ? null : createdById);
        }
    }

    private void seedDocuments(UUID orgId) {
        // 40 documents across all projects and folders.
        // Look up folder_id by (project_id, folder_code) via subquery.
        Object[][] docs = {
                // (id, project_id, folder_code, title, doc_type, status, created_by_email, created_at, current_rev, description)
                {"ffffff00-0000-0000-0000-000000000001", "bbbbbb00-0000-0000-0000-000000000001", "01_ADMIN", "Contrat Adduction Aïn Draham", "MARCHE", "APPROVED", "owner@hydra.tn", "2024-02-20 10:00:00+00", "v1.0", "Contrat signé avec SONEDE Région Nord."},
                {"ffffff00-0000-0000-0000-000000000002", "bbbbbb00-0000-0000-0000-000000000001", "02_TECH", "Plans exécution PEHD DN300", "PLAN", "IN_REVIEW", "sami.trabelsi@hydra.tn", "2024-03-15 14:00:00+00", "v2.1", "Plans d'exécution pour 12km de conduite PEHD."},
                {"ffffff00-0000-0000-0000-000000000003", "bbbbbb00-0000-0000-0000-000000000001", "03_EXEC", "Carnet de chantier Q1-Q2 2024", "RAPPORT", "DRAFT", "sami.trabelsi@hydra.tn", "2024-08-01 09:00:00+00", "v0.5", "Carnet de chantier phases 1-4."},
                {"ffffff00-0000-0000-0000-000000000004", "bbbbbb00-0000-0000-0000-000000000001", "05_ACHAT", "Facture Pipetech SARL PIP-2024-0142", "FACTURE", "APPROVED", "safa.mtibaa@hydra.tn", "2024-03-28 11:00:00+00", "v1.0", "Facture fourniture PEHD - 145 000 TND."},
                {"ffffff00-0000-0000-0000-000000000005", "bbbbbb00-0000-0000-0000-000000000001", "05_ACHAT", "Bon de commande BC-2024-0042", "MARCHE", "APPROVED", "sami.trabelsi@hydra.tn", "2024-03-20 09:00:00+00", "v1.0", "BC pour fourniture PEHD."},
                {"ffffff00-0000-0000-0000-000000000006", "bbbbbb00-0000-0000-0000-000000000001", "05_ACHAT", "Bon de livraison BL-2024-0098", "BON_LIVRAISON", "APPROVED", "sami.trabelsi@hydra.tn", "2024-03-25 14:00:00+00", "v1.0", "BL livraison conduites PEHD."},
                {"ffffff00-0000-0000-0000-000000000007", "bbbbbb00-0000-0000-0000-000000000001", "07_HSE", "Plan de prévention HSE", "HSE", "APPROVED", "sami.trabelsi@hydra.tn", "2024-02-25 10:00:00+00", "v1.0", "Plan HSE validé."},
                {"ffffff00-0000-0000-0000-000000000008", "bbbbbb00-0000-0000-0000-000000000001", "04_QUAL", "PV réception provisoire adduction", "PV_RECEPTION", "APPROVED", "sami.trabelsi@hydra.tn", "2024-11-10 15:00:00+00", "v1.0", "PV réception provisoire réseau."},
                {"ffffff00-0000-0000-0000-000000000009", "bbbbbb00-0000-0000-0000-000000000003", "01_ADMIN", "Contrat Réservoir 1000m³ Le Kef", "MARCHE", "APPROVED", "owner@hydra.tn", "2023-08-15 10:00:00+00", "v1.0", "Contrat SONEDE Centre - 1 450 000 TND."},
                {"ffffff00-0000-0000-0000-000000000010", "bbbbbb00-0000-0000-0000-000000000003", "02_TECH", "Plan coffrage réservoir", "PLAN", "APPROVED", "ahlem.cherif@hydra.tn", "2023-10-05 09:00:00+00", "v3.0", "Plan coffrage + ferraillage."},
                {"ffffff00-0000-0000-0000-000000000011", "bbbbbb00-0000-0000-0000-000000000003", "02_TECH", "Note de calcul béton armé", "ETUDE", "APPROVED", "fawzi.haddad@hydra.tn", "2023-09-20 11:00:00+00", "v1.0", "Note de calcul structure."},
                {"ffffff00-0000-0000-0000-000000000012", "bbbbbb00-0000-0000-0000-000000000003", "05_ACHAT", "Facture Cimenterie CIM-2024-0021", "FACTURE", "APPROVED", "safa.mtibaa@hydra.tn", "2024-01-20 09:00:00+00", "v1.0", "Facture béton armé - 92 000 TND."},
                {"ffffff00-0000-0000-0000-000000000013", "bbbbbb00-0000-0000-0000-000000000003", "04_QUAL", "PV réception provisoire réservoir", "PV_RECEPTION", "APPROVED", "sami.trabelsi@hydra.tn", "2024-09-05 14:00:00+00", "v1.0", "PV réception après essais."},
                {"ffffff00-0000-0000-0000-000000000014", "bbbbbb00-0000-0000-0000-000000000003", "07_HSE", "Rapport HSE chantier Q3-Q4 2023", "HSE", "APPROVED", "sami.trabelsi@hydra.tn", "2024-01-15 10:00:00+00", "v1.0", "Rapport HSE trimestriel."},
                {"ffffff00-0000-0000-0000-000000000015", "bbbbbb00-0000-0000-0000-000000000005", "01_ADMIN", "Contrat Forage SBZ", "MARCHE", "APPROVED", "owner@hydra.tn", "2024-03-25 10:00:00+00", "v1.0", "Contrat CRDA Sidi Bouzid - 680 000 TND."},
                {"ffffff00-0000-0000-0000-000000000016", "bbbbbb00-0000-0000-0000-000000000005", "02_TECH", "Rapport géologique forage", "RAPPORT", "APPROVED", "ahlem.cherif@hydra.tn", "2024-04-10 14:00:00+00", "v1.0", "Rapport géologique 180m."},
                {"ffffff00-0000-0000-0000-000000000017", "bbbbbb00-0000-0000-0000-000000000005", "05_ACHAT", "Facture Hydro-Forage HF-2024-0123", "FACTURE", "APPROVED", "safa.mtibaa@hydra.tn", "2024-05-15 09:00:00+00", "v1.0", "Facture forage + pompe - 185 000 TND."},
                {"ffffff00-0000-0000-0000-000000000018", "bbbbbb00-0000-0000-0000-000000000005", "07_HSE", "Plan prévention HSE forage", "HSE", "APPROVED", "leila.gharbi@hydra.tn", "2024-04-15 10:00:00+00", "v1.0", "Plan HSE forage."},
                {"ffffff00-0000-0000-0000-000000000019", "bbbbbb00-0000-0000-0000-000000000005", "04_QUAL", "PV réception définitive forage", "PV_RECEPTION", "APPROVED", "leila.gharbi@hydra.tn", "2024-10-25 14:00:00+00", "v1.0", "PV réception définitive."},
                {"ffffff00-0000-0000-0000-000000000020", "bbbbbb00-0000-0000-0000-000000000006", "01_ADMIN", "Contrat Station Pompage Nabeul", "MARCHE", "APPROVED", "owner@hydra.tn", "2024-01-25 10:00:00+00", "v1.0", "Contrat SONEDE Cap Bon - 1 180 000 TND."},
                {"ffffff00-0000-0000-0000-000000000021", "bbbbbb00-0000-0000-0000-000000000006", "02_TECH", "Plan bâtiment station pompage", "PLAN", "APPROVED", "ahlem.cherif@hydra.tn", "2024-02-10 11:00:00+00", "v2.0", "Plan bâtiment 8x6m."},
                {"ffffff00-0000-0000-0000-000000000022", "bbbbbb00-0000-0000-0000-000000000006", "05_ACHAT", "Facture Pompes & Moteurs PMP-2024-0156", "FACTURE", "APPROVED", "safa.mtibaa@hydra.tn", "2024-03-30 09:00:00+00", "v1.0", "Facture 3 pompes 250kW - 245 000 TND."},
                {"ffffff00-0000-0000-0000-000000000023", "bbbbbb00-0000-0000-0000-000000000006", "05_ACHAT", "Bon de commande BC-2024-0012", "MARCHE", "APPROVED", "sami.trabelsi@hydra.tn", "2024-03-01 10:00:00+00", "v1.0", "BC 3 pompes 250kW."},
                {"ffffff00-0000-0000-0000-000000000024", "bbbbbb00-0000-0000-0000-000000000006", "07_HSE", "Plan prévention HSE", "HSE", "APPROVED", "sami.trabelsi@hydra.tn", "2024-02-05 10:00:00+00", "v1.0", "Plan HSE validé."},
                {"ffffff00-0000-0000-0000-000000000025", "bbbbbb00-0000-0000-0000-000000000007", "01_ADMIN", "Contrat ONAS Sfax", "MARCHE", "APPROVED", "owner@hydra.tn", "2023-05-15 10:00:00+00", "v1.0", "Contrat ONAS Sfax - 1 620 000 TND."},
                {"ffffff00-0000-0000-0000-000000000026", "bbbbbb00-0000-0000-0000-000000000007", "02_TECH", "Plans exécution réseau 8km", "PLAN", "APPROVED", "ahlem.cherif@hydra.tn", "2023-06-10 14:00:00+00", "v1.5", "Plans exécution PVC DN 200-400."},
                {"ffffff00-0000-0000-0000-000000000027", "bbbbbb00-0000-0000-0000-000000000007", "05_ACHAT", "Facture PVC Distribution PVC-2023-0890", "FACTURE", "APPROVED", "safa.mtibaa@hydra.tn", "2023-08-01 09:00:00+00", "v1.0", "Facture tuyaux PVC - 130 000 TND."},
                {"ffffff00-0000-0000-0000-000000000028", "bbbbbb00-0000-0000-0000-000000000007", "05_ACHAT", "Bon de commande BC-2023-0089", "MARCHE", "APPROVED", "mona.zribi@hydra.tn", "2023-07-15 10:00:00+00", "v1.0", "BC tuyaux PVC."},
                {"ffffff00-0000-0000-0000-000000000029", "bbbbbb00-0000-0000-0000-000000000007", "04_QUAL", "Rapport essai pression réseau", "RAPPORT", "IN_REVIEW", "mona.zribi@hydra.tn", "2024-07-25 14:00:00+00", "v1.0", "Rapport essai pression 12 bar."},
                {"ffffff00-0000-0000-0000-000000000030", "bbbbbb00-0000-0000-0000-000000000007", "07_HSE", "Plan prévention HSE Sfax", "HSE", "APPROVED", "mona.zribi@hydra.tn", "2023-06-25 10:00:00+00", "v1.0", "Plan HSE urbain."},
                {"ffffff00-0000-0000-0000-000000000031", "bbbbbb00-0000-0000-0000-000000000008", "01_ADMIN", "Contrat Réhabilitation Sousse", "MARCHE", "ARCHIVED", "owner@hydra.tn", "2022-08-20 10:00:00+00", "v1.0", "Contrat SONEDE Sahel - 740 000 TND."},
                {"ffffff00-0000-0000-0000-000000000032", "bbbbbb00-0000-0000-0000-000000000008", "02_TECH", "Plans As-Built Sousse", "PLAN", "ARCHIVED", "fawzi.haddad@hydra.tn", "2023-07-15 14:00:00+00", "v1.0", "Plans As-Built finaux."},
                {"ffffff00-0000-0000-0000-000000000033", "bbbbbb00-0000-0000-0000-000000000008", "04_QUAL", "PV réception définitive Sousse", "PV_RECEPTION", "ARCHIVED", "leila.gharbi@hydra.tn", "2023-08-31 14:00:00+00", "v1.0", "PV réception définitive."},
                {"ffffff00-0000-0000-0000-000000000034", "bbbbbb00-0000-0000-0000-000000000008", "05_ACHAT", "Facture Bricomat BRC-2023-0089", "FACTURE", "ARCHIVED", "safa.mtibaa@hydra.tn", "2023-03-01 09:00:00+00", "v1.0", "Facture branchements - 88 000 TND."},
                {"ffffff00-0000-0000-0000-000000000035", "bbbbbb00-0000-0000-0000-000000000008", "07_HSE", "Rapport HSE final Sousse", "HSE", "ARCHIVED", "leila.gharbi@hydra.tn", "2023-09-01 10:00:00+00", "v1.0", "Rapport HSE final."},
                {"ffffff00-0000-0000-0000-000000000036", "bbbbbb00-0000-0000-0000-000000000009", "01_ADMIN", "Contrat Bassin Médenine", "MARCHE", "APPROVED", "owner@hydra.tn", "2024-06-20 10:00:00+00", "v1.0", "Contrat Min Agri - 980 000 TND."},
                {"ffffff00-0000-0000-0000-000000000037", "bbbbbb00-0000-0000-0000-000000000009", "02_TECH", "Étude de sol géotechnique", "RAPPORT", "IN_REVIEW", "ahlem.cherif@hydra.tn", "2024-08-15 14:00:00+00", "v1.0", "Étude géotechnique digue 8m."},
                {"ffffff00-0000-0000-0000-000000000038", "bbbbbb00-0000-0000-0000-000000000009", "02_TECH", "Plan préliminaire digue", "PLAN", "DRAFT", "fawzi.haddad@hydra.tn", "2024-07-30 11:00:00+00", "v0.5", "Plan préliminaire digue 8m."},
                {"ffffff00-0000-0000-0000-000000000039", "bbbbbb00-0000-0000-0000-000000000009", "02_TECH", "Note de calcul évacuateur", "ETUDE", "DRAFT", "fawzi.haddad@hydra.tn", "2024-08-20 10:00:00+00", "v0.3", "Note de calcul évacuateur."},
                {"ffffff00-0000-0000-0000-000000000040", "bbbbbb00-0000-0000-0000-000000000009", "07_HSE", "Plan prévention HSE Médenine", "HSE", "DRAFT", "karim.bouzid@hydra.tn", "2024-09-05 10:00:00+00", "v0.1", "Plan HSE brouillon."},
        };
        for (Object[] d : docs) {
            String createdEmail = (String) d[6];
            String createdStr = (String) d[7];
            java.sql.Timestamp created = parseIso(createdStr);
            jdbc.update("""
                    insert into document (id, org_id, folder_id, ticket_id, title, doc_type, current_rev,
                                          status, description, created_at, created_by)
                    select ?, ?, df.id, null, ?, ?, ?, ?, ?, ?, u.id
                    from doc_folder df cross join app_user u
                    where df.project_id::text = ? and df.code = ? and u.email = ?
                    and not exists (select 1 from document dc where dc.id = ?)
                    """,
                    UUID.fromString((String) d[0]), orgId, d[3], d[4], d[8], d[5], d[9],
                    created,
                    d[1], d[2], createdEmail, UUID.fromString((String) d[0]));
        }
    }

    private void seedProcessInstances(UUID orgId) {
        // Find the PAYMENT_APPROVAL template id
        List<UUID> tmplIds = jdbc.queryForList(
                "select id from process_template where key = 'PAYMENT_APPROVAL' and org_id is null limit 1",
                UUID.class);
        if (tmplIds.isEmpty()) return;
        UUID templateId = tmplIds.get(0);

        Object[][] pis = {
                {"33333333-0000-0000-0000-000000000001", "bbbbbb00-0000-0000-0000-000000000001", "11111111-0000-0000-0000-000000000038", "RUNNING", 2, "sami.trabelsi@hydra.tn", "2024-10-15 09:00:00+00", "2024-10-15 09:00:00+00"},
                {"33333333-0000-0000-0000-000000000002", "bbbbbb00-0000-0000-0000-000000000002", "11111111-0000-0000-0000-000000000007", "RUNNING", 3, "safa.mtibaa@hydra.tn", "2024-11-05 09:00:00+00", "2024-11-05 09:00:00+00"},
                {"33333333-0000-0000-0000-000000000003", "bbbbbb00-0000-0000-0000-000000000009", "11111111-0000-0000-0000-000000000033", "RUNNING", 2, "safa.mtibaa@hydra.tn", "2024-07-20 09:00:00+00", "2024-07-20 09:00:00+00"},
                {"33333333-0000-0000-0000-000000000004", "bbbbbb00-0000-0000-0000-000000000007", "11111111-0000-0000-0000-000000000055", "COMPLETED", 3, "mona.zribi@hydra.tn", "2024-08-20 09:00:00+00", "2024-08-21 14:00:00+00"},
                {"33333333-0000-0000-0000-000000000005", "bbbbbb00-0000-0000-0000-000000000003", "11111111-0000-0000-0000-000000000044", "REJECTED", 2, "sami.trabelsi@hydra.tn", "2024-04-01 09:00:00+00", "2024-04-03 09:00:00+00"},
        };
        for (Object[] p : pis) {
            String email = (String) p[5];
            String createdStr = (String) p[6];
            String updatedStr = (String) p[7];
            java.sql.Timestamp created = parseIso(createdStr);
            java.sql.Timestamp updated = parseIso(updatedStr);
            jdbc.update("""
                    insert into process_instance (id, org_id, project_id, template_id, subject_type, subject_id,
                                                  status, current_step_order, created_by, created_at, updated_at)
                    select ?, ?, ?, ?, 'CASH_MOVEMENT', ?, ?, ?, u.id, ?, ?
                    from app_user u where u.email = ?
                    and not exists (select 1 from process_instance pi where pi.id = ?)
                    """,
                    UUID.fromString((String) p[0]), orgId, UUID.fromString((String) p[1]), templateId,
                    UUID.fromString((String) p[2]), p[3], (int) p[4],
                    created, updated,
                    email, UUID.fromString((String) p[0]));
        }
    }

    private void linkProcessInstancesToMovements() {
        // Link 3 cash movements to their corresponding process instances
        jdbc.update("update cash_movement set process_instance_id = '33333333-0000-0000-0000-000000000001' where id = '11111111-0000-0000-0000-000000000038' and process_instance_id is null");
        jdbc.update("update cash_movement set process_instance_id = '33333333-0000-0000-0000-000000000004' where id = '11111111-0000-0000-0000-000000000055' and process_instance_id is null");
        jdbc.update("update cash_movement set process_instance_id = '33333333-0000-0000-0000-000000000005' where id = '11111111-0000-0000-0000-000000000044' and process_instance_id is null");
    }
}
