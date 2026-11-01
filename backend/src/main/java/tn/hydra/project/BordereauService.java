package tn.hydra.project;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BordereauService — versioned price schedules per chantier.
 *
 * <p>A chantier has one ACTIVE bordereau version at a time. New versions are
 * created as DRAFT (a copy of the active one's lines), edited, then activated —
 * at which point the previous ACTIVE version is archived (read-only).
 *
 * <p>Active versions are read-only: lines can only be updated while the
 * version is DRAFT. Every change is logged via {@link EventLogService}.
 *
 * <p>Permission model mirrors {@link OuvrageService}: read = project member
 * or company-wide reader; write = PERM_PROJECT_WRITE / PROJECT_ASSIGN or
 * project ADMIN/MANAGER (same as updating a project).
 *
 * @see docs/BORDEREAU-ANALYSIS.md §7.2 for the bordereau_line column spec.
 */
@Service
public class BordereauService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final ProjectService projects;
    private final EventLogService audit;

    public BordereauService(JdbcTemplate jdbc, AppUserRepository users,
                            ProjectService projects, EventLogService audit) {
        this.jdbc = jdbc; this.users = users; this.projects = projects; this.audit = audit;
    }

    // ═══════════════════════════════════════════════════════════════
    // RESPONSE SHAPES
    // ═══════════════════════════════════════════════════════════════

    public record VersionResponse(UUID id, UUID projectId, int versionNo, String label,
                                  String reason, UUID documentId, String status,
                                  UUID createdBy, Instant createdAt) {}

    public record LineResponse(UUID id, UUID versionId, String[] sectionPath, String articleNo,
                              int sortOrder, String designation, String unitRaw, String unit,
                              BigDecimal quantity, BigDecimal unitPrice, UUID ouvrageId,
                              String workNature, UUID pairedLineId, Map<String, Object> specs,
                              String[] importWarnings, Instant createdAt) {}

    public record CreateVersionRequest(String label, String reason, UUID documentId) {}

    public record UpdateLineRequest(String articleNo, String designation, String unitRaw,
                                    String unit, BigDecimal quantity, BigDecimal unitPrice,
                                    UUID ouvrageId, String workNature, UUID pairedLineId,
                                    Map<String, Object> specs, String[] importWarnings) {}

    // ═══════════════════════════════════════════════════════════════
    // 1. LIST VERSIONS
    // ═══════════════════════════════════════════════════════════════

    public List<VersionResponse> listVersions(Authentication auth, UUID projectId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        projects.requireReadableForOuvrage(auth, me, projectId);
        return jdbc.queryForList("""
                select id, project_id, version_no, label, reason, document_id, status,
                       created_by, created_at
                from bordereau_version
                where org_id = ? and project_id = ?
                order by version_no desc
                """, me.getOrgId(), projectId).stream()
                .map(BordereauService::toVersionResponse).toList();
    }

    // ═══════════════════════════════════════════════════════════════
    // 2. GET LINES OF A VERSION
    // ═══════════════════════════════════════════════════════════════

    public List<LineResponse> listLines(Authentication auth, UUID projectId, UUID versionId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        projects.requireReadableForOuvrage(auth, me, projectId);
        assertVersionInProject(versionId, projectId, me.getOrgId());
        return jdbc.queryForList("""
                select id, version_id, section_path, article_no, sort_order, designation,
                       unit_raw, unit, quantity, unit_price, ouvrage_id, work_nature,
                       paired_line_id, specs, import_warnings, created_at
                from bordereau_line
                where org_id = ? and version_id = ?
                order by sort_order, designation
                """, me.getOrgId(), versionId).stream()
                .map(BordereauService::toLineResponse).toList();
    }

    // ═══════════════════════════════════════════════════════════════
    // 3. CREATE NEW VERSION (copy of the active one)
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public VersionResponse createVersion(Authentication auth, UUID projectId, CreateVersionRequest body) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        projects.requireManageableForOuvrage(auth, me, projectId);

        if (body.label() == null || body.label().isBlank())
            throw new BusinessRuleException("REQUIRED_FIELDS", "Le libellé de la version est obligatoire");

        // Find the current active version (to copy lines from).
        List<Map<String, Object>> active = jdbc.queryForList("""
                select id, version_no from bordereau_version
                where org_id = ? and project_id = ? and status = 'ACTIVE'
                """, me.getOrgId(), projectId);

        int newVersionNo;
        UUID sourceVersionId;
        if (active.isEmpty()) {
            // No active version yet — this is the initial version.
            newVersionNo = 1;
            sourceVersionId = null;
        } else {
            Map<String, Object> current = active.get(0);
            newVersionNo = ((Number) current.get("version_no")).intValue() + 1;
            sourceVersionId = (UUID) current.get("id");
        }

        UUID newId = jdbc.queryForObject("""
                insert into bordereau_version (org_id, project_id, version_no, label, reason,
                                                document_id, status, created_by)
                values (?, ?, ?, ?, ?, ?, 'DRAFT', ?)
                returning id
                """, UUID.class,
                me.getOrgId(), projectId, newVersionNo, body.label().trim(),
                body.reason(), body.documentId(), me.getId());

        // Copy lines from the active version (if any).
        if (sourceVersionId != null) {
            copyLines(sourceVersionId, newId, me.getOrgId());
        }

        audit.log("BORDEREAU_VERSION_CREATED", "bordereau_version", newId,
                "{\"projectId\":\"" + projectId + "\",\"versionNo\":" + newVersionNo
                        + ",\"label\":\"" + jsonEscape(body.label().trim()) + "\"}");
        return getVersionResponse(newId, me.getOrgId());
    }

    /** Copy all lines from one version to another (same org). */
    private void copyLines(UUID sourceVersionId, UUID targetVersionId, UUID orgId) {
        List<Map<String, Object>> lines = jdbc.queryForList("""
                select section_path, article_no, sort_order, designation, unit_raw, unit,
                       quantity, unit_price, ouvrage_id, work_nature, specs, import_warnings
                from bordereau_line
                where org_id = ? and version_id = ?
                order by sort_order
                """, orgId, sourceVersionId);

        for (Map<String, Object> line : lines) {
            jdbc.update("""
                    insert into bordereau_line (org_id, version_id, section_path, article_no,
                        sort_order, designation, unit_raw, unit, quantity, unit_price,
                        ouvrage_id, work_nature, specs, import_warnings)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """,
                    orgId, targetVersionId,
                    line.get("section_path"), line.get("article_no"),
                    line.get("sort_order"), line.get("designation"),
                    line.get("unit_raw"), line.get("unit"),
                    line.get("quantity"), line.get("unit_price"),
                    line.get("ouvrage_id"), line.get("work_nature"),
                    line.get("specs") != null ? line.get("specs").toString() : "{}",
                    line.get("import_warnings"));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // 4. ACTIVATE A VERSION (archives the previous one)
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public VersionResponse activateVersion(Authentication auth, UUID projectId, UUID versionId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        projects.requireManageableForOuvrage(auth, me, projectId);
        assertVersionInProject(versionId, projectId, me.getOrgId());

        // Only DRAFT versions can be activated.
        String status = getVersionStatus(versionId, me.getOrgId());
        if (!"DRAFT".equals(status))
            throw new BusinessRuleException("VERSION_NOT_DRAFT",
                    "Seule une version en brouillon peut être activée.");

        // Archive the current ACTIVE version (if any).
        jdbc.update("""
                update bordereau_version set status = 'ARCHIVED'
                where org_id = ? and project_id = ? and status = 'ACTIVE'
                """, me.getOrgId(), projectId);

        // Activate the new one.
        jdbc.update("""
                update bordereau_version set status = 'ACTIVE'
                where id = ? and org_id = ?
                """, versionId, me.getOrgId());

        audit.log("BORDEREAU_VERSION_ACTIVATED", "bordereau_version", versionId,
                "{\"projectId\":\"" + projectId + "\"}");
        return getVersionResponse(versionId, me.getOrgId());
    }

    // ═══════════════════════════════════════════════════════════════
    // 5. UPDATE LINES (only while DRAFT)
    // ═══════════════════════════════════════════════════════════════

    @Transactional
    public LineResponse updateLine(Authentication auth, UUID projectId, UUID versionId,
                                    UUID lineId, UpdateLineRequest body) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        projects.requireManageableForOuvrage(auth, me, projectId);
        assertVersionInProject(versionId, projectId, me.getOrgId());

        // Only DRAFT versions allow line edits.
        String status = getVersionStatus(versionId, me.getOrgId());
        if (!"DRAFT".equals(status))
            throw new BusinessRuleException("VERSION_NOT_DRAFT",
                    "Les lignes ne peuvent être modifiées que sur une version en brouillon.");

        // Verify the line exists in this version + org.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id from bordereau_line where id = ? and org_id = ? and version_id = ?",
                lineId, me.getOrgId(), versionId);
        if (rows.isEmpty())
            throw new BusinessRuleException("LINE_NOT_FOUND", "Ligne introuvable.");

        jdbc.update("""
                update bordereau_line set
                    article_no = coalesce(?, article_no),
                    designation = coalesce(?, designation),
                    unit_raw = coalesce(?, unit_raw),
                    unit = coalesce(?, unit),
                    quantity = coalesce(?, quantity),
                    unit_price = coalesce(?, unit_price),
                    ouvrage_id = coalesce(?, ouvrage_id),
                    work_nature = coalesce(?, work_nature),
                    paired_line_id = coalesce(?, paired_line_id),
                    specs = ?::jsonb,
                    import_warnings = coalesce(?, import_warnings)
                where id = ? and org_id = ?
                """,
                body.articleNo(), body.designation(), body.unitRaw(), body.unit(),
                body.quantity(), body.unitPrice(), body.ouvrageId(), body.workNature(),
                body.pairedLineId(),
                body.specs() != null ? mapToJson(body.specs()) : null,
                body.importWarnings(),
                lineId, me.getOrgId());

        // Re-run warnings for this line (unit normalisation + unknown unit).
        // Re-read the updated line to get the current values.
        List<Map<String, Object>> updatedRows = jdbc.queryForList(
                "select * from bordereau_line where id = ? and org_id = ?", lineId, me.getOrgId());
        if (!updatedRows.isEmpty()) {
            Map<String, Object> r = updatedRows.get(0);
            String updatedUnitRaw = (String) r.get("unit_raw");
            String updatedUnit = (String) r.get("unit");
            String updatedDesignation = (String) r.get("designation");
            List<String> newWarnings = new ArrayList<>();

            // §6 rule 5: unknown unit.
            if (updatedUnitRaw != null && !updatedUnitRaw.isBlank() && updatedUnit == null) {
                newWarnings.add("Unité inconnue : « " + updatedUnitRaw + " ».");
            }
            // §6 rule 3: unit but no quantity.
            Object updatedQty = r.get("quantity");
            if (updatedUnitRaw != null && !updatedUnitRaw.isBlank() && updatedQty == null) {
                newWarnings.add("Quantité manquante (considérée comme 0).");
            }
            // §7.3: re-extract specs from the updated designation.
            Map<String, Object> newSpecs = BordereauImportService.extractSpecs(updatedDesignation);
            if (!newSpecs.isEmpty()) {
                jdbc.update("update bordereau_line set specs = ?::jsonb where id = ? and org_id = ?",
                        mapToJson(newSpecs), lineId, me.getOrgId());
            }
            // Save the re-run warnings.
            jdbc.update("update bordereau_line set import_warnings = ? where id = ? and org_id = ?",
                    newWarnings.toArray(new String[0]), lineId, me.getOrgId());
        }

        // §6 rule 4: re-check duplicate article_no in every section of this version
        // (the edited line may have joined or left a duplicate group).
        recheckDuplicates(versionId, me.getOrgId());

        audit.log("BORDEREAU_LINE_UPDATED", "bordereau_line", lineId,
                "{\"versionId\":\"" + versionId + "\"}");
        return getLineResponse(lineId, me.getOrgId());
    }

    /**
     * Recompute the duplicate-number warning for every line of the version: a line
     * whose (section, article_no) is shared gets it, others lose it. Only lines whose
     * warnings change are updated. Other warnings are kept as they are.
     */
    private void recheckDuplicates(UUID versionId, UUID orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select id, section_path, article_no, import_warnings from bordereau_line
                where version_id = ? and org_id = ?
                """, versionId, orgId);
        Map<String, Integer> countByKey = new HashMap<>();
        Map<UUID, String> keyById = new HashMap<>();
        for (Map<String, Object> r : rows) {
            String articleNo = (String) r.get("article_no");
            if (articleNo == null || articleNo.isBlank()) continue;
            String key = BordereauImportService.sectionKey(Arrays.asList(toStringArray(r.get("section_path"))))
                    + "|" + articleNo.trim();
            keyById.put((UUID) r.get("id"), key);
            countByKey.merge(key, 1, Integer::sum);
        }
        for (Map<String, Object> r : rows) {
            UUID id = (UUID) r.get("id");
            List<String> current = Arrays.asList(toStringArray(r.get("import_warnings")));
            List<String> updated = new ArrayList<>();
            for (String w : current) {
                if (!w.startsWith(BordereauImportService.DUPLICATE_WARNING_PREFIX)) updated.add(w);
            }
            String key = keyById.get(id);
            if (key != null && countByKey.get(key) > 1) {
                updated.add(BordereauImportService.duplicateWarning(((String) r.get("article_no")).trim()));
            }
            if (!updated.equals(current)) {
                jdbc.update("update bordereau_line set import_warnings = ? where id = ? and org_id = ?",
                        updated.toArray(new String[0]), id, orgId);
            }
        }
    }

    private static String[] toStringArray(Object sqlArray) {
        if (!(sqlArray instanceof java.sql.Array arr)) return new String[0];
        try { return (String[]) arr.getArray(); } catch (Exception e) { return new String[0]; }
    }

    // ═══════════════════════════════════════════════════════════════
    // 5b. BULK OUVRAGE ASSIGNMENT (only while DRAFT)
    // ═══════════════════════════════════════════════════════════════

    public record AssignOuvrageRequest(List<UUID> lineIds, UUID ouvrageId) {}

    /**
     * Set one ouvrage (or none, ouvrageId null) on a list of lines of the same DRAFT
     * version, in one transaction. Every line id must belong to this version and org,
     * and the ouvrage to this chantier; otherwise nothing is changed. Returns the lines.
     */
    @Transactional
    public List<LineResponse> assignOuvrage(Authentication auth, UUID projectId, UUID versionId,
                                            AssignOuvrageRequest body) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        projects.requireManageableForOuvrage(auth, me, projectId);
        assertVersionInProject(versionId, projectId, me.getOrgId());
        if (!"DRAFT".equals(getVersionStatus(versionId, me.getOrgId())))
            throw new BusinessRuleException("VERSION_NOT_DRAFT",
                    "Les lignes ne peuvent être modifiées que sur une version en brouillon.");
        if (body == null || body.lineIds() == null || body.lineIds().isEmpty())
            throw new BusinessRuleException("LINES_REQUIRED", "Sélectionnez au moins une ligne.");

        if (body.ouvrageId() != null) {
            Integer cnt = jdbc.queryForObject(
                    "select count(*) from ouvrage where id = ? and org_id = ? and project_id = ?",
                    Integer.class, body.ouvrageId(), me.getOrgId(), projectId);
            if (cnt == null || cnt == 0)
                throw new BusinessRuleException("OUVRAGE_NOT_FOUND", "Ouvrage introuvable sur ce chantier.");
        }

        List<UUID> ids = body.lineIds().stream().distinct().toList();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids);
        args.add(versionId);
        args.add(me.getOrgId());
        Integer found = jdbc.queryForObject(
                "select count(*) from bordereau_line where id in (" + placeholders + ") and version_id = ? and org_id = ?",
                Integer.class, args.toArray());
        if (found == null || found != ids.size())
            throw new BusinessRuleException("LINE_NOT_FOUND",
                    "Certaines lignes n'appartiennent pas à cette version.");

        List<Object> updateArgs = new ArrayList<>();
        updateArgs.add(body.ouvrageId());
        updateArgs.addAll(args);
        jdbc.update("update bordereau_line set ouvrage_id = ? where id in (" + placeholders + ") and version_id = ? and org_id = ?",
                updateArgs.toArray());

        audit.log("BORDEREAU_LINES_OUVRAGE_ASSIGNED", "bordereau_version", versionId,
                "{\"lines\":" + ids.size() + ",\"ouvrageId\":"
                        + (body.ouvrageId() != null ? "\"" + body.ouvrageId() + "\"" : "null") + "}");
        return ids.stream().map(id -> getLineResponse(id, me.getOrgId())).toList();
    }

    // ═══════════════════════════════════════════════════════════════
    // 6. COMPARE TWO VERSIONS
    // ═══════════════════════════════════════════════════════════════

    /**
     * Compare two versions and return, per article_no:
     * <ul>
     *   <li><b>ADDED</b> — line exists in the new version but not in the old.</li>
     *   <li><b>REMOVED</b> — line exists in the old version but not in the new.</li>
     *   <li><b>QUANTITY_CHANGED</b> — quantity differs (old + new values).</li>
     *   <li><b>PRICE_CHANGED</b> — unit_price differs (old + new values).</li>
     * </ul>
     * Lines are matched by article_no within the same section_path. If a line
     * has no article_no, it's matched by sort_order + designation.
     */
    public List<Map<String, Object>> compareVersions(Authentication auth, UUID projectId,
                                                      UUID oldVersionId, UUID newVersionId) {
        AppUser me = users.findByEmail(auth.getName()).orElseThrow();
        projects.requireReadableForOuvrage(auth, me, projectId);
        assertVersionInProject(oldVersionId, projectId, me.getOrgId());
        assertVersionInProject(newVersionId, projectId, me.getOrgId());

        // Load both versions' lines.
        Map<String, Map<String, Object>> oldLines = loadLinesByKey(oldVersionId, me.getOrgId());
        Map<String, Map<String, Object>> newLines = loadLinesByKey(newVersionId, me.getOrgId());

        List<Map<String, Object>> diffs = new ArrayList<>();

        // Lines in the new version: check if added or changed.
        for (Map.Entry<String, Map<String, Object>> e : newLines.entrySet()) {
            String key = e.getKey();
            Map<String, Object> newLine = e.getValue();
            Map<String, Object> oldLine = oldLines.get(key);

            if (oldLine == null) {
                // ADDED
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("articleNo", newLine.get("article_no"));
                diff.put("designation", newLine.get("designation"));
                diff.put("change", "ADDED");
                diff.put("newQuantity", newLine.get("quantity"));
                diff.put("newUnitPrice", newLine.get("unit_price"));
                diffs.add(diff);
            } else {
                // Check quantity change.
                BigDecimal oldQty = toBd(oldLine.get("quantity"));
                BigDecimal newQty = toBd(newLine.get("quantity"));
                if (compareDecimal(oldQty, newQty) != 0) {
                    Map<String, Object> diff = new LinkedHashMap<>();
                    diff.put("articleNo", newLine.get("article_no"));
                    diff.put("designation", newLine.get("designation"));
                    diff.put("change", "QUANTITY_CHANGED");
                    diff.put("oldQuantity", oldQty);
                    diff.put("newQuantity", newQty);
                    diffs.add(diff);
                }
                // Check price change.
                BigDecimal oldPrice = toBd(oldLine.get("unit_price"));
                BigDecimal newPrice = toBd(newLine.get("unit_price"));
                if (compareDecimal(oldPrice, newPrice) != 0) {
                    Map<String, Object> diff = new LinkedHashMap<>();
                    diff.put("articleNo", newLine.get("article_no"));
                    diff.put("designation", newLine.get("designation"));
                    diff.put("change", "PRICE_CHANGED");
                    diff.put("oldUnitPrice", oldPrice);
                    diff.put("newUnitPrice", newPrice);
                    diffs.add(diff);
                }
            }
        }

        // Lines in the old version that are NOT in the new version: REMOVED.
        for (Map.Entry<String, Map<String, Object>> e : oldLines.entrySet()) {
            if (!newLines.containsKey(e.getKey())) {
                Map<String, Object> oldLine = e.getValue();
                Map<String, Object> diff = new LinkedHashMap<>();
                diff.put("articleNo", oldLine.get("article_no"));
                diff.put("designation", oldLine.get("designation"));
                diff.put("change", "REMOVED");
                diff.put("oldQuantity", oldLine.get("quantity"));
                diff.put("oldUnitPrice", oldLine.get("unit_price"));
                diffs.add(diff);
            }
        }

        return diffs;
    }

    /** Load all lines of a version, keyed by a match key (article_no or sort_order+designation). */
    private Map<String, Map<String, Object>> loadLinesByKey(UUID versionId, UUID orgId) {
        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                select article_no, sort_order, designation, quantity, unit_price
                from bordereau_line
                where org_id = ? and version_id = ?
                """, orgId, versionId)) {
            String articleNo = (String) row.get("article_no");
            String key;
            if (articleNo != null && !articleNo.isBlank()) {
                key = articleNo.trim();
            } else {
                // Fallback: sort_order + designation.
                int sort = row.get("sort_order") == null ? 0 : ((Number) row.get("sort_order")).intValue();
                String desig = (String) row.get("designation");
                key = "sort:" + sort + "|" + (desig != null ? desig.trim() : "");
            }
            map.put(key, row);
        }
        return map;
    }

    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════

    private void assertVersionInProject(UUID versionId, UUID projectId, UUID orgId) {
        List<UUID> found = jdbc.queryForList(
                "select id from bordereau_version where id = ? and org_id = ? and project_id = ?",
                UUID.class, versionId, orgId, projectId);
        if (found.isEmpty())
            throw new BusinessRuleException("VERSION_NOT_FOUND", "Version introuvable.");
    }

    private String getVersionStatus(UUID versionId, UUID orgId) {
        List<String> statuses = jdbc.queryForList(
                "select status from bordereau_version where id = ? and org_id = ?",
                String.class, versionId, orgId);
        return statuses.isEmpty() ? null : statuses.get(0);
    }

    private VersionResponse getVersionResponse(UUID versionId, UUID orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from bordereau_version where id = ? and org_id = ?", versionId, orgId);
        return rows.isEmpty() ? null : toVersionResponse(rows.get(0));
    }

    private LineResponse getLineResponse(UUID lineId, UUID orgId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from bordereau_line where id = ? and org_id = ?", lineId, orgId);
        return rows.isEmpty() ? null : toLineResponse(rows.get(0));
    }

    // ── ROW → RESPONSE ──────────────────────────────────────────────

    private static VersionResponse toVersionResponse(Map<String, Object> row) {
        return new VersionResponse(
                (UUID) row.get("id"), (UUID) row.get("project_id"),
                row.get("version_no") == null ? 0 : ((Number) row.get("version_no")).intValue(),
                (String) row.get("label"), (String) row.get("reason"),
                (UUID) row.get("document_id"), (String) row.get("status"),
                (UUID) row.get("created_by"), toInstant(row.get("created_at")));
    }

    @SuppressWarnings("unchecked")
    private static LineResponse toLineResponse(Map<String, Object> row) {
        java.sql.Array arr = (java.sql.Array) row.get("section_path");
        String[] sectionPath;
        try { sectionPath = arr != null ? (String[]) arr.getArray() : new String[0]; }
        catch (Exception e) { sectionPath = new String[0]; }

        java.sql.Array warningsArr = (java.sql.Array) row.get("import_warnings");
        String[] warnings;
        try { warnings = warningsArr != null ? (String[]) warningsArr.getArray() : new String[0]; }
        catch (Exception e) { warnings = new String[0]; }

        String specsStr = row.get("specs") != null ? row.get("specs").toString() : "{}";
        Map<String, Object> specs;
        try {
            tools.jackson.databind.ObjectMapper m = new tools.jackson.databind.ObjectMapper();
            specs = m.readValue(specsStr, Map.class);
        } catch (Exception e) { specs = Map.of(); }

        return new LineResponse(
                (UUID) row.get("id"), (UUID) row.get("version_id"),
                sectionPath, (String) row.get("article_no"),
                row.get("sort_order") == null ? 0 : ((Number) row.get("sort_order")).intValue(),
                (String) row.get("designation"),
                (String) row.get("unit_raw"), (String) row.get("unit"),
                toBd(row.get("quantity")), toBd(row.get("unit_price")),
                (UUID) row.get("ouvrage_id"), (String) row.get("work_nature"),
                (UUID) row.get("paired_line_id"), specs, warnings,
                toInstant(row.get("created_at")));
    }

    private static BigDecimal toBd(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return null;
    }

    private static int compareDecimal(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return a.compareTo(b);
    }

    private static Instant toInstant(Object v) {
        if (v instanceof Instant i) return i;
        if (v instanceof java.sql.Timestamp ts) return ts.toInstant();
        return null;
    }

    private String mapToJson(Map<String, Object> map) {
        if (map == null) return "{}";
        try {
            tools.jackson.databind.ObjectMapper m = new tools.jackson.databind.ObjectMapper();
            return m.writeValueAsString(map);
        } catch (Exception e) { return "{}"; }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
