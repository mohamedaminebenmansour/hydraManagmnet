#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Patch ProjectService.java to add ouvrage support (helpers + ouvrageSummary
+ COMMUN auto-create). Idempotent: refuses to re-patch if already done."""
from pathlib import Path
import sys

p = Path('/tmp/my-project/backend/src/main/java/tn/hydra/project/ProjectService.java')
src = p.read_text(encoding='utf-8')

if 'ouvrageSummary' in src and 'requireReadableForOuvrage' in src and 'ensureCommunOuvrage' in src:
    print('[SKIP] ProjectService.java already patched')
    sys.exit(0)

# 1) imports
old_imports = (
    "import org.springframework.jdbc.core.JdbcTemplate;\n"
    "import org.springframework.security.core.Authentication;\n"
    "import org.springframework.stereotype.Service;\n"
    "import org.springframework.transaction.annotation.Transactional;\n"
    "import tn.hydra.audit.EventLogService;\n"
    "import tn.hydra.common.BusinessRuleException;\n"
    "import tn.hydra.exception.BadRequestException;\n"
    "import tn.hydra.security.FolderAccessService;\n"
    "import tn.hydra.user.AppUser;\n"
    "import tn.hydra.user.AppUserRepository;\n"
)
new_imports = (
    "import org.springframework.context.ApplicationContext;\n"
    "import org.springframework.jdbc.core.JdbcTemplate;\n"
    "import org.springframework.security.core.Authentication;\n"
    "import org.springframework.stereotype.Service;\n"
    "import org.springframework.transaction.annotation.Transactional;\n"
    "import tn.hydra.audit.EventLogService;\n"
    "import tn.hydra.common.BusinessRuleException;\n"
    "import tn.hydra.exception.BadRequestException;\n"
    "import tn.hydra.security.FolderAccessService;\n"
    "import tn.hydra.user.AppUser;\n"
    "import tn.hydra.user.AppUserRepository;\n"
)
assert old_imports in src, "imports block not found"
src = src.replace(old_imports, new_imports, 1)

# 2) constructor: add ApplicationContext
old_ctor = (
    "    private final JdbcTemplate jdbc;\n"
    "    private final AppUserRepository users;\n"
    "    private final EventLogService audit;\n"
    "    private final FolderAccessService folderAccess;\n"
    "\n"
    "    public ProjectService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit,\n"
    "                          FolderAccessService folderAccess) {\n"
    "        this.jdbc = jdbc; this.users = users; this.audit = audit; this.folderAccess = folderAccess;\n"
    "    }\n"
)
new_ctor = (
    "    private final JdbcTemplate jdbc;\n"
    "    private final AppUserRepository users;\n"
    "    private final EventLogService audit;\n"
    "    private final FolderAccessService folderAccess;\n"
    "    private final ApplicationContext ctx;\n"
    "\n"
    "    public ProjectService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit,\n"
    "                          FolderAccessService folderAccess, ApplicationContext ctx) {\n"
    "        this.jdbc = jdbc; this.users = users; this.audit = audit;\n"
    "        this.folderAccess = folderAccess; this.ctx = ctx;\n"
    "    }\n"
    "\n"
    "    /**\n"
    "     * Lazy lookup of {@link OuvrageService} to avoid a constructor cycle\n"
    "     * (OuvrageService also depends on ProjectService). Spring resolves the\n"
    "     * bean on first call, after both services are constructed.\n"
    "     */\n"
    "    private OuvrageService ouvrages() {\n"
    "        return ctx.getBean(OuvrageService.class);\n"
    "    }\n"
)
assert old_ctor in src, "constructor block not found"
src = src.replace(old_ctor, new_ctor, 1)

# 3) ProjectResponse: add ouvrageSummary field
old_response = (
    "    public record ProjectResponse(UUID id, String code, String name, String status,\n"
    "                                  String clientName, String location, String governorate,\n"
    "                                  LocalDate startDate, LocalDate endDate,\n"
    "                                  String projectType, String description,\n"
    "                                  BigDecimal contractValue, String currency,\n"
    "                                  Integer memberCount, Instant createdAt) {}\n"
)
new_response = (
    "    public record ProjectResponse(UUID id, String code, String name, String status,\n"
    "                                  String clientName, String location, String governorate,\n"
    "                                  LocalDate startDate, LocalDate endDate,\n"
    "                                  String projectType, String description,\n"
    "                                  BigDecimal contractValue, String currency,\n"
    "                                  Integer memberCount, String ouvrageSummary, Instant createdAt) {}\n"
)
assert old_response in src, "ProjectResponse record not found"
src = src.replace(old_response, new_response, 1)

# 4) PROJECT_COLUMNS: also select p.org_id so toResponse can compute ouvrageSummary
old_columns_block = (
    "    /** Column list shared by list/get so member_count is computed the same way everywhere. */\n"
    '    private static final String PROJECT_COLUMNS = """\n'
    "            select p.id, p.code, p.name, p.status,\n"
    "                   p.client_name, p.location, p.governorate,\n"
    "                   p.start_date, p.end_date, p.project_type, p.description,\n"
    "                   p.contract_value, p.currency, p.created_at,\n"
    "                   (select count(*) from project_member pm where pm.project_id = p.id) as member_count\n"
    "            from project p\n"
    '            """;\n'
)
new_columns_block = (
    "    /** Column list shared by list/get so member_count is computed the same way everywhere. */\n"
    '    private static final String PROJECT_COLUMNS = """\n'
    "            select p.id, p.org_id, p.code, p.name, p.status,\n"
    "                   p.client_name, p.location, p.governorate,\n"
    "                   p.start_date, p.end_date, p.project_type, p.description,\n"
    "                   p.contract_value, p.currency, p.created_at,\n"
    "                   (select count(*) from project_member pm where pm.project_id = p.id) as member_count\n"
    "            from project p\n"
    '            """;\n'
)
assert old_columns_block in src, "PROJECT_COLUMNS block not found"
src = src.replace(old_columns_block, new_columns_block, 1)

# 5) toResponse: make it non-static, look up ouvrageSummary via ouvrages()
old_to_response = (
    "    private static ProjectResponse toResponse(Map<String, Object> row) {\n"
    "        return new ProjectResponse(\n"
    "                (UUID) row.get(\"id\"),\n"
    "                (String) row.get(\"code\"),\n"
    "                (String) row.get(\"name\"),\n"
    "                (String) row.get(\"status\"),\n"
    "                (String) row.get(\"client_name\"),\n"
    "                (String) row.get(\"location\"),\n"
    "                (String) row.get(\"governorate\"),\n"
    "                toLocalDate(row.get(\"start_date\")),\n"
    "                toLocalDate(row.get(\"end_date\")),\n"
    "                (String) row.get(\"project_type\"),\n"
    "                (String) row.get(\"description\"),\n"
    "                toBigDecimal(row.get(\"contract_value\")),\n"
    "                (String) row.get(\"currency\"),\n"
    "                row.get(\"member_count\") == null ? null : ((Number) row.get(\"member_count\")).intValue(),\n"
    "                toInstant(row.get(\"created_at\")));\n"
    "    }\n"
)
new_to_response = (
    "    private ProjectResponse toResponse(Map<String, Object> row) {\n"
    "        UUID projectId = (UUID) row.get(\"id\");\n"
    "        UUID orgId = (UUID) row.get(\"org_id\");\n"
    "        String ouvrageSummary = \"\";\n"
    "        if (projectId != null && orgId != null) {\n"
    "            try { ouvrageSummary = ouvrages().ouvrageSummary(projectId, orgId); }\n"
    "            catch (Exception ignored) { /* never fail the project list because of ouvrages */ }\n"
    "        }\n"
    "        return new ProjectResponse(\n"
    "                projectId,\n"
    "                (String) row.get(\"code\"),\n"
    "                (String) row.get(\"name\"),\n"
    "                (String) row.get(\"status\"),\n"
    "                (String) row.get(\"client_name\"),\n"
    "                (String) row.get(\"location\"),\n"
    "                (String) row.get(\"governorate\"),\n"
    "                toLocalDate(row.get(\"start_date\")),\n"
    "                toLocalDate(row.get(\"end_date\")),\n"
    "                (String) row.get(\"project_type\"),\n"
    "                (String) row.get(\"description\"),\n"
    "                toBigDecimal(row.get(\"contract_value\")),\n"
    "                (String) row.get(\"currency\"),\n"
    "                row.get(\"member_count\") == null ? null : ((Number) row.get(\"member_count\")).intValue(),\n"
    "                ouvrageSummary,\n"
    "                toInstant(row.get(\"created_at\")));\n"
    "    }\n"
)
assert old_to_response in src, "toResponse method not found"
src = src.replace(old_to_response, new_to_response, 1)

# 6) create(): call ensureCommunOuvrage before returning
old_create_tail = (
    "        // f) audit with name + folder count\n"
    "        audit.log(\"PROJECT_CREATED\", \"project\", id,\n"
    "                \"{\\\"name\\\":\\\"\" + jsonEscape(body.name().trim()) +\n"
    "                        \"\\\",\\\"folders\\\":\" + folderCount + \"}\");\n"
    "\n"
    "        // g) return full response\n"
    "        return get(auth, id);\n"
)
new_create_tail = (
    "        // f) audit with name + folder count\n"
    "        audit.log(\"PROJECT_CREATED\", \"project\", id,\n"
    "                \"{\\\"name\\\":\\\"\" + jsonEscape(body.name().trim()) +\n"
    "                        \"\\\",\\\"folders\\\":\" + folderCount + \"}\");\n"
    "\n"
    "        // g) auto-create the single COMMUN ouvrage for this chantier\n"
    "        // (installation, repli, essais globaux, raccordement au réseau existant,\n"
    "        // sécurité). Idempotent — see OuvrageService.ensureCommunOuvrage.\n"
    "        ouvrages().ensureCommunOuvrage(id, me.getOrgId(), me.getId());\n"
    "\n"
    "        // h) return full response\n"
    "        return get(auth, id);\n"
)
assert old_create_tail in src, "create() tail block not found"
src = src.replace(old_create_tail, new_create_tail, 1)

# 7) add the two package-private helper methods before cloneTemplates
helper_block = (
    "    // ── OUVRAGE HELPERS ─────────────────────────────────────\n"
    "\n"
    "    /** Package-private hook for {@link OuvrageService} to check read access on a chantier. */\n"
    "    void requireReadableForOuvrage(Authentication auth, AppUser me, UUID projectId) {\n"
    "        requireReadable(auth, me, projectId);\n"
    "    }\n"
    "\n"
    "    /** Package-private hook for {@link OuvrageService} to check write access on a chantier. */\n"
    "    void requireManageableForOuvrage(Authentication auth, AppUser me, UUID projectId) {\n"
    "        requireManageable(auth, me, projectId);\n"
    "    }\n"
    "\n"
)
anchor = "    /**\n     * Clones folder templates for a project"
assert anchor in src, "cloneTemplates anchor not found"
src = src.replace(anchor, helper_block + anchor, 1)

p.write_text(src, encoding='utf-8')
print('[OK] ProjectService.java patched')
