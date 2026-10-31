-- ============================================================================
-- V41: fix project_member.project_role default.
--
-- V8 created the column with default 'MEMBER', but a later migration altered
-- the CHECK to ('ADMIN','MANAGER','ENGINEER','VIEWER') — 'MEMBER' is no longer
-- allowed, so an INSERT that omits project_role violates the check. Set the
-- default to 'VIEWER' (the least-privilege valid value) so omitting
-- project_role succeeds and yields a read-only member.
--
-- Does not edit V38–V40 (AI-RULES 2). 'VIEWER' satisfies the live CHECK.
-- ============================================================================

ALTER TABLE project_member ALTER COLUMN project_role SET DEFAULT 'VIEWER';
