-- ============================================================================
-- V51: ticket type « TACHE » (office tasks) + is_private + DELIVERABLE phase
--
-- A TACHE is an office task (« Préparer le dossier de consultation PEHD »):
--   - same lifecycle as PROBLEME / RESERVE / DEMANDE:
--     OPEN -> IN_PROGRESS -> RESOLVED -> CLOSED (+ CANCELLED).
--   - project_id is OPTIONAL (null = « Bureau », a company task, no chantier).
--   - is_private marks a task visible only to its creator and assignee
--     (and the owner). Other types never set is_private.
--   - a TACHE may receive a DELIVERABLE file (a produced document), stored as a
--     ticket_attachment with phase DELIVERABLE, filed by category.
--
-- NOTE on project_id: checked in V1__core.sql + V6__ticket_v2.sql — the column
-- was added as `uuid references project(id)` with NO `not null`, so it is
-- ALREADY nullable. No ALTER needed; this migration only documents the fact.
-- ============================================================================

-- 1. Type check: TACHE joins the four signalement types.
alter table ticket drop constraint if exists ticket_type_check;
alter table ticket add constraint ticket_type_check
    check (type in ('PROBLEME','RESERVE','DEMANDE','VISA_DOCUMENT','TACHE'));

-- 2. Status check: TACHE shares the universal lifecycle.
alter table ticket drop constraint if exists ticket_status_check;
alter table ticket add constraint ticket_status_check check (
    (type = 'VISA_DOCUMENT'
        and status in ('DRAFT','IN_REVIEW','APPROVED','IFC','AS_BUILT'))
    or
    (type in ('PROBLEME','RESERVE','DEMANDE','TACHE')
        and status in ('OPEN','IN_PROGRESS','RESOLVED','CLOSED','CANCELLED'))
);

-- 3. is_private flag — only meaningful for a TACHE (enforced in the service;
--    the column itself is just a boolean default false so every existing row
--    keeps working unchanged).
alter table ticket add column is_private boolean not null default false;

-- 4. ticket_attachment phase: DELIVERABLE joins BEFORE / AFTER / OTHER so a
--    TACHE can carry the document it produced. The original V9 inline check
--    is named `ticket_attachment_phase_check` by PostgreSQL; replace it.
alter table ticket_attachment drop constraint if exists ticket_attachment_phase_check;
alter table ticket_attachment add constraint ticket_attachment_phase_check
    check (phase in ('BEFORE','AFTER','OTHER','DELIVERABLE'));

-- 5. Index private + no-project tasks so the visibility rule is cheap.
create index if not exists idx_ticket_org_private
    on ticket (org_id, is_private);
create index if not exists idx_ticket_org_noproject
    on ticket (org_id, project_id);
