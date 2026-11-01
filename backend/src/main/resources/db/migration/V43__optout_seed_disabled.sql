-- ============================================================================
-- V43: opt-out chantier switch — preserve today's behaviour for chantiers that
-- already have project_process_template rows.
--
-- The chantier switch becomes opt-out: a rule is enabled on a chantier UNLESS a
-- project_process_template row says enabled = false (no row = enabled).
-- Under the OLD whitelist rule ("any row => only enabled=true rows are active"),
-- a chantier with rows had every un-rowed template DISABLED. To keep that
-- behaviour under the new opt-out rule (where no row = enabled), we insert
-- enabled = false rows for every active template that has no row on those
-- chantiers — but ONLY for templates visible to the chantier's company
-- (system templates with org_id IS NULL, or org-scoped templates whose org_id
-- matches the chantier's project org).
-- ============================================================================

INSERT INTO project_process_template (project_id, template_id, enabled)
SELECT DISTINCT ppt.project_id, pt.id, false
FROM project_process_template ppt
JOIN project p ON p.id = ppt.project_id
CROSS JOIN process_template pt
WHERE pt.active = true
  AND (pt.org_id IS NULL OR pt.org_id = p.org_id)
  AND NOT EXISTS (
      SELECT 1 FROM project_process_template p2
      WHERE p2.project_id = ppt.project_id AND p2.template_id = pt.id
  );
