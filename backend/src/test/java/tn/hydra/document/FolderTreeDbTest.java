package tn.hydra.document;

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
import tn.hydra.document.service.FolderFilingService;
import tn.hydra.project.ProjectService;
import tn.hydra.security.FolderAccessService;
import tn.hydra.security.FolderAccessService.FolderAction;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ============================================================================
 *  FolderTreeDbTest — two-level folder tree (V46): template clone on create,
 *  idempotent backfill (legacy codes), FolderFilingService, ACL inheritance.
 * ============================================================================
 *
 *  @SpringBootTest against hydra_test; @Transactional + @Rollback keep the DB
 *  clean. The backfill is the V46 SQL function hydra_backfill_folder_tree().
 * ============================================================================
 */
@SpringBootTest
@Transactional
@Rollback
class FolderTreeDbTest {

    private static final List<String> MAIN_CODES = List.of(
            "01_ADMIN", "02_TECH", "03_EXEC", "04_QUAL", "05_ACHAT", "06_FIN", "07_HSE", "08_SST");

    /** Global sub-folder templates of V46: 01:5, 02:4, 03:4, 04:3, 05:3, 06:5, 08:2. */
    private static final int SUB_FOLDER_COUNT = 26;

    @Autowired private ProjectService projects;
    @Autowired private FolderFilingService filing;
    @Autowired private FolderAccessService folderAccess;
    @Autowired private JdbcTemplate jdbc;

    private UUID orgId;
    private UUID ownerId;
    private Authentication ownerAuth;

    @BeforeEach
    void provisionWorld() {
        orgId = UUID.randomUUID();
        ownerId = UUID.randomUUID();
        String email = "folders.owner." + ownerId + "@hydra.test";
        jdbc.update("insert into organization (id, name) values (?, ?)", orgId, "Folders Org " + orgId);
        insertUser(ownerId, email, true);
        ownerAuth = new UsernamePasswordAuthenticationToken(email, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(ownerAuth);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void insertUser(UUID id, String email, boolean owner) {
        jdbc.update("""
                insert into app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
                values (?, ?, ?, ?, ?, ?, true)
                """, id, orgId, email, "hash", "User " + id, owner);
    }

    private UUID insertProject() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into project (id, org_id, name, status, created_by) values (?, ?, ?, 'ACTIF', ?)",
                id, orgId, "Chantier " + id, ownerId);
        return id;
    }

    private Integer backfill() {
        return jdbc.queryForObject("select hydra_backfill_folder_tree()", Integer.class);
    }

    @Test
    void newProject_gets8MainFolders_andAllSubFoldersUnderTheRightParent() {
        UUID projectId = projects.create(ownerAuth, new ProjectService.CreateProjectRequest(
                "Chantier arbre", "ARB" + ownerId.toString().substring(0, 4), null, null, null,
                null, null, null, null, null, null, null, null)).id();

        List<Map<String, Object>> mains = jdbc.queryForList(
                "select id, code from doc_folder where project_id = ? and parent_id is null", projectId);
        assertThat(mains).extracting(m -> (String) m.get("code")).containsExactlyInAnyOrderElementsOf(MAIN_CODES);

        Map<UUID, String> mainCodeById = new HashMap<>();
        mains.forEach(m -> mainCodeById.put((UUID) m.get("id"), (String) m.get("code")));

        List<Map<String, Object>> expectedSubs = jdbc.queryForList(
                "select code, parent_code from doc_folder_template where org_id is null and parent_code is not null");
        assertThat(expectedSubs).hasSize(SUB_FOLDER_COUNT);
        List<Map<String, Object>> subs = jdbc.queryForList(
                "select code, parent_id from doc_folder where project_id = ? and parent_id is not null", projectId);
        assertThat(subs).hasSize(expectedSubs.size());
        for (Map<String, Object> e : expectedSubs) {
            Map<String, Object> s = subs.stream().filter(x -> e.get("code").equals(x.get("code"))).findFirst()
                    .orElseThrow(() -> new AssertionError("missing sub-folder " + e.get("code")));
            assertThat(mainCodeById.get((UUID) s.get("parent_id"))).isEqualTo(e.get("parent_code"));
        }
    }

    @Test
    void backfillTwice_createsNothingTheSecondTime() {
        UUID projectId = insertProject();

        assertThat(backfill()).isPositive();
        // Exact list, so a failure names the missing / extra folder.
        List<String> templateCodes = jdbc.queryForList(
                "select code from doc_folder_template where org_id is null", String.class);
        List<String> createdCodes = jdbc.queryForList(
                "select code from doc_folder where project_id = ?", String.class, projectId);
        assertThat(createdCodes).containsExactlyInAnyOrderElementsOf(templateCodes);
        Integer folders = jdbc.queryForObject("select count(*) from doc_folder where project_id = ?", Integer.class, projectId);
        assertThat(folders).isEqualTo(8 + SUB_FOLDER_COUNT);

        assertThat(backfill()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from doc_folder where project_id = ?", Integer.class, projectId))
                .isEqualTo(folders);
    }

    @Test
    void legacyMainFolderCodeWithSpace_getsItsSubFoldersUnderIt() {
        UUID projectId = insertProject();
        UUID legacyAdmin = jdbc.queryForObject("""
                insert into doc_folder (project_id, parent_id, code, name, sort_order)
                values (?, null, '01 ADMIN', 'Administratif', 1) returning id
                """, UUID.class, projectId);

        backfill();

        List<UUID> adminSubParents = jdbc.queryForList(
                "select parent_id from doc_folder where project_id = ? and code like '01\\_ADMIN.%'", UUID.class, projectId);
        assertThat(adminSubParents).hasSize(5).containsOnly(legacyAdmin);
        assertThat(jdbc.queryForObject(
                "select count(*) from doc_folder where project_id = ? and parent_id is null and left(code, 2) = '01'",
                Integer.class, projectId)).isEqualTo(1);
    }

    @Test
    void folderFor_returnsTheSameIdOnRepeatedCalls_underTheTechniqueFolder() {
        UUID projectId = insertProject();

        UUID first = filing.folderFor(projectId, "02_TECH.BORDEREAUX");
        UUID second = filing.folderFor(projectId, "02_TECH.BORDEREAUX");

        assertThat(second).isEqualTo(first);
        Map<String, Object> row = jdbc.queryForMap("""
                select f.name, p.code as parent_code from doc_folder f join doc_folder p on p.id = f.parent_id
                where f.id = ?
                """, first);
        assertThat(row.get("name")).isEqualTo("Bordereaux et DQE");
        assertThat(row.get("parent_code")).isEqualTo("02_TECH");
        assertThat(jdbc.queryForObject(
                "select count(*) from doc_folder where project_id = ? and code = '02_TECH.BORDEREAUX'",
                Integer.class, projectId)).isEqualTo(1);
    }

    @Test
    void subFolderInheritsTheParentsAccess_grantedOrNot() {
        UUID projectId = insertProject();
        UUID sub = filing.folderFor(projectId, "02_TECH.BORDEREAUX");
        UUID tech = jdbc.queryForObject("select parent_id from doc_folder where id = ?", UUID.class, sub);
        assertThat(jdbc.queryForObject("select count(*) from folder_access where folder_id = ?", Integer.class, sub))
                .isZero(); // no rows of its own: it inherits

        // 02_TECH: INGENIEUR may read + upload (default matrix row).
        jdbc.update("""
                insert into folder_access (folder_id, role_code, can_read, can_upload, can_download, can_approve, can_delete)
                values (?, 'INGENIEUR', true, true, true, false, false)
                """, tech);

        UUID engineer = UUID.randomUUID();
        insertUser(engineer, "folders.eng." + engineer + "@hydra.test", false);
        jdbc.update("insert into user_role (user_id, role_id) select ?, id from role where code = 'INGENIEUR' and org_id is null",
                engineer);
        UUID finance = UUID.randomUUID();
        insertUser(finance, "folders.fin." + finance + "@hydra.test", false);
        jdbc.update("insert into user_role (user_id, role_id) select ?, id from role where code = 'FINANCE' and org_id is null",
                finance);

        assertThat(folderAccess.canAccessFolder(engineer, sub, FolderAction.READ)).isTrue();
        assertThat(folderAccess.canAccessFolder(engineer, sub, FolderAction.UPLOAD)).isTrue();
        assertThat(folderAccess.canAccessFolder(finance, sub, FolderAction.READ)).isFalse();
        assertThat(folderAccess.canAccessFolder(finance, sub, FolderAction.UPLOAD)).isFalse();

        var engineerUser = new tn.hydra.user.AppUser();
        engineerUser.setId(engineer);
        engineerUser.setOrgId(orgId);
        engineerUser.setIsOwner(false);
        assertThat(folderAccess.readableFolderIds(engineerUser, projectId)).contains(tech, sub);
    }
}
