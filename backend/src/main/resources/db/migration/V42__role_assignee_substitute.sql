-- ============================================================================
-- V42: project_role_assignee.substitute_user_id — a per-role replacement who
-- can act on the chantier when the main person is absent (is_active = false).
--
-- Used by ProcessService.resolveAssigneeByRole: when the main user (pra.user_id)
-- is inactive, the substitute (pra.substitute_user_id) is returned instead, so
-- the workflow keeps moving without reassigning the role.
--
-- AI-RULES 4: this is an ALTER of an existing table (not a new table); the
-- substitute is validated to be in the same org at the service layer.
-- ============================================================================

ALTER TABLE project_role_assignee ADD COLUMN substitute_user_id uuid REFERENCES app_user(id);

CREATE INDEX IF NOT EXISTS idx_project_role_assignee_substitute
    ON project_role_assignee (substitute_user_id)
    WHERE substitute_user_id IS NOT NULL;
