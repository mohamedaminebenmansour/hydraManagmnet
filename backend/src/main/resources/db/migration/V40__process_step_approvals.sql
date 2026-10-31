-- ============================================================================
-- V40: process_step_approval — one row per approver who has approved a step
-- whose approval_mode = 'ALL'. The step stays PENDING until every user holding
-- the step's role on the chantier has a row here; the last approval flips the
-- step to DONE (see ProcessService.completeStep).
--
-- org_id (AI-RULES 4): every new table carries org_id not null and every query
-- filters by it. The step_instance -> instance -> org chain is the source of
-- truth, but org_id is denormalised here so the per-step approval queries stay
-- single-table and org-scoped.
-- ============================================================================

CREATE TABLE IF NOT EXISTS process_step_approval (
    step_instance_id uuid        not null references process_step_instance(id) on delete cascade,
    user_id          uuid        not null references app_user(id),
    org_id           uuid        not null references organization(id),
    approved_at      timestamptz not null default now(),
    comment          text,
    primary key (step_instance_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_process_step_approval_step
    ON process_step_approval (step_instance_id);
CREATE INDEX IF NOT EXISTS idx_process_step_approval_org
    ON process_step_approval (org_id);

ALTER TABLE process_step_approval DISABLE ROW LEVEL SECURITY;

COMMENT ON TABLE process_step_approval IS
    'One row per approver who has approved an approval_mode=ALL step. The step stays PENDING until every required role-holder on the chantier has a row here.';
