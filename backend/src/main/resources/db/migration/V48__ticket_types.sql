-- ============================================================================
-- V48: ticket types, categories, origin, ouvrage/party links, universal lifecycle
--
-- A ticket now has a TYPE:
--   PROBLEME, RESERVE, DEMANDE -> OPEN -> IN_PROGRESS -> RESOLVED -> CLOSED
--                                  (+ CANCELLED from OPEN / IN_PROGRESS,
--                                   reopen RESOLVED -> IN_PROGRESS)
--   VISA_DOCUMENT              -> keeps DRAFT -> IN_REVIEW -> APPROVED -> IFC
--                                  -> AS_BUILT (unchanged)
--
-- New columns: type, category, origin, location_text, ouvrage_id,
-- responsible_party_id, resolution_note, closed_at, closed_by, cancel_reason.
-- The status check constraint is replaced so it depends on the type.
-- Existing rows get type = 'VISA_DOCUMENT' (column default) and keep their
-- VISA status, so old tickets and old tests work unchanged.
-- ============================================================================

alter table ticket add column type                 text        not null default 'VISA_DOCUMENT';
alter table ticket add column category             text;
alter table ticket add column origin               text;
alter table ticket add column location_text        text;
alter table ticket add column ouvrage_id           uuid references ouvrage(id) on delete set null;
alter table ticket add column responsible_party_id uuid references party(id)   on delete set null;
alter table ticket add column resolution_note      text;
alter table ticket add column closed_at            timestamptz;
alter table ticket add column closed_by            uuid;
alter table ticket add column cancel_reason        text;

-- type must be one of the four known signalement types
alter table ticket add constraint ticket_type_check
    check (type in ('PROBLEME','RESERVE','DEMANDE','VISA_DOCUMENT'));

-- origin is only meaningful for a RESERVE; CLIENT = visite client / MOA,
-- INTERNE = contrôle interne. Null for the other types.
alter table ticket add constraint ticket_origin_check
    check (origin is null or origin in ('CLIENT','INTERNE'));

-- Replace the status check so the allowed statuses depend on the type.
-- VISA_DOCUMENT keeps its transmittal statuses; the three new types share
-- the universal OPEN -> ... -> CLOSED (+ CANCELLED) lifecycle.
alter table ticket drop constraint if exists ticket_status_check;
alter table ticket add constraint ticket_status_check check (
    (type = 'VISA_DOCUMENT'
        and status in ('DRAFT','IN_REVIEW','APPROVED','IFC','AS_BUILT'))
    or
    (type in ('PROBLEME','RESERVE','DEMANDE')
        and status in ('OPEN','IN_PROGRESS','RESOLVED','CLOSED','CANCELLED'))
);

-- Access paths: per-chantier list by type/status, "what needs me" by assignee,
-- and overdue watch by due date.
create index if not exists idx_ticket_org_project_type_status
    on ticket (org_id, project_id, type, status);
create index if not exists idx_ticket_org_assignee_status
    on ticket (org_id, assigned_to, status);
create index if not exists idx_ticket_org_due
    on ticket (org_id, due_at);
