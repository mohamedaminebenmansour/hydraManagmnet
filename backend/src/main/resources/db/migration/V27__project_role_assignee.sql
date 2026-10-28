-- ============================================================================
-- V27 — project_role_assignee: per-project business role → user mapping
-- ============================================================================
-- PRODUCT GOAL (Task 1 brief): on each project, the owner maps business roles
-- used in process steps (FINANCE, OWNER, ACHAT, CHEF_CHANTIER, …) to concrete
-- users who are members of that project (or, for the OWNER role, the org
-- owner). When a process instance is started, each step gets
-- assignee_user_id from this mapping first; only when no mapping exists does
-- the engine fall back to the existing logic (OWNER → org owner; else first
-- project_member holding that business role via user_role + role.code).
--
-- This matches how Procore / Fieldwire bind workflow steps to project roles /
-- people — without building a full Procore.
--
-- DESIGN NOTES
--   * role_code is the SAME string as process_template_step.assignee_role
--     (e.g. 'FINANCE', 'OWNER', 'ACHAT', 'CHEF_CHANTIER'). No FK because the
--     codes are an open vocabulary (a future template may introduce a new
--     role without needing a DDL change here).
--   * UNIQUE (project_id, role_code) → v1 is single-user-per-role-per-project
--     (the brief is explicit: "v1: single user per role per project").
--     Multi-assignee would need a separate junction + changes to the resolve
--     path — explicitly out of scope (STANDING RULE 7).
--   * No FK from user_id → project_member because the OWNER role may map to
--     the org owner (who is NOT a project_member). The service layer enforces
--     "non-OWNER role → user must be project_member of the project".
--   * on delete cascade on project → mappings die with the project. No
--     cascade on user_id (a user being deactivated should NOT silently drop
--     their mappings — the owner should re-assign instead).
--   * Idempotent (IF NOT EXISTS pattern matching V20/V24/V25/V26 style).
-- ============================================================================

create table if not exists project_role_assignee (
    id          uuid primary key default gen_random_uuid(),
    project_id  uuid not null references project(id) on delete cascade,
    role_code   text not null,
    user_id     uuid not null references app_user(id),
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    constraint uq_project_role_assignee unique (project_id, role_code)
);

create index if not exists idx_project_role_assignee_project
    on project_role_assignee (project_id);

create index if not exists idx_project_role_assignee_user
    on project_role_assignee (user_id);

comment on table project_role_assignee is
    'Per-project mapping: business role code (FINANCE/OWNER/ACHAT/...) → preferred user. Used by ProcessService.resolveAssigneeByRole as the first lookup; falls back to existing role-based resolution when no mapping exists.';

-- Disable RLS (matching the V19 pattern for all process tables — the
-- application layer enforces org + project access).
alter table project_role_assignee disable row level security;
