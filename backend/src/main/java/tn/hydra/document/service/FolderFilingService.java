package tn.hydra.document.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.ResourceNotFoundException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Where a chantier files a kind of document, by STABLE folder code (V46):
 * main folder '02_TECH', sub-folder '02_TECH.BORDEREAUX'. Names are display only.
 *
 * <p>{@link #folderFor} returns the chantier's folder with that code, creating it (and
 * its main folder) from the template when missing. New folders get no folder_access
 * rows: a sub-folder inherits its parent's rules (FolderAccessService).
 */
@Service
public class FolderFilingService {

    private final JdbcTemplate jdbc;

    public FolderFilingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private record Template(String code, String name, int sortOrder, String parentCode) {}

    /**
     * The chantier's folder with {@code code}, created from the template if missing.
     * Idempotent under concurrent calls: the project row is locked (FOR NO KEY UPDATE,
     * so FK checks on it are not blocked) while looking for / creating the folders.
     */
    @Transactional
    public UUID folderFor(UUID projectId, String code) {
        List<UUID> org = jdbc.queryForList(
                "select org_id from project where id = ? for no key update", UUID.class, projectId);
        if (org.isEmpty())
            throw new ResourceNotFoundException("PROJECT_NOT_FOUND", "Projet introuvable");
        UUID orgId = org.get(0);

        Template t = template(orgId, code);
        if (t.parentCode() == null) {
            UUID main = findMain(projectId, code);
            return main != null ? main : insert(projectId, null, t);
        }

        UUID existing = jdbc.queryForList(
                "select id from doc_folder where project_id = ? and code = ? order by created_at limit 1",
                UUID.class, projectId, code).stream().findFirst().orElse(null);
        if (existing != null) return existing;

        UUID parentId = findMain(projectId, t.parentCode());
        if (parentId == null) parentId = insert(projectId, null, template(orgId, t.parentCode()));
        return insert(projectId, parentId, t);
    }

    /**
     * Main folder matching a template main code: same code, the legacy form with a space
     * ('01 ADMIN'), or the same 2-digit prefix ('07_HSE' for '07_INT'). Same rule as V46.
     */
    private UUID findMain(UUID projectId, String mainCode) {
        return jdbc.queryForList("""
                select id from doc_folder
                where project_id = ? and parent_id is null
                  and (code = ? or replace(code, ' ', '_') = ? or left(code, 2) = left(?, 2))
                order by (code = ?) desc, (replace(code, ' ', '_') = ?) desc, sort_order, created_at
                limit 1
                """, UUID.class, projectId, mainCode, mainCode, mainCode, mainCode, mainCode)
                .stream().findFirst().orElse(null);
    }

    /** Org-specific template row if the org has one for this code, else the global one. */
    private Template template(UUID orgId, String code) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select code, name, sort_order, parent_code from doc_folder_template
                where code = ? and (org_id = ? or org_id is null)
                order by (org_id is null)
                limit 1
                """, code, orgId);
        if (rows.isEmpty())
            throw new BusinessRuleException("FOLDER_TEMPLATE_NOT_FOUND",
                    "Aucun modèle de dossier pour le code « " + code + " ».");
        Map<String, Object> r = rows.get(0);
        return new Template((String) r.get("code"), (String) r.get("name"),
                r.get("sort_order") == null ? 0 : ((Number) r.get("sort_order")).intValue(),
                (String) r.get("parent_code"));
    }

    private UUID insert(UUID projectId, UUID parentId, Template t) {
        return jdbc.queryForObject("""
                insert into doc_folder (project_id, parent_id, code, name, sort_order)
                values (?, ?, ?, ?, ?) returning id
                """, UUID.class, projectId, parentId, t.code(), t.name(), t.sortOrder());
    }
}
