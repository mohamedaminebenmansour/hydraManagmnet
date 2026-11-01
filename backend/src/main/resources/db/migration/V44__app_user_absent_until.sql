-- ============================================================================
-- V44: app_user.absent_until — a user is "absent" when absent_until >= today.
-- Used by resolveAssigneeByRole (fall back to the substitute) and
-- requireStepAccess (the substitute can act on a PENDING step whose assignee
-- is absent or inactive).
-- ============================================================================

ALTER TABLE app_user ADD COLUMN absent_until date;
