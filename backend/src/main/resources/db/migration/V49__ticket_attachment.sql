-- ============================================================================
-- V49: ticket_attachment — photos (and other files) attached to a signalement.
--
-- Each row links a ticket to a document stored through DocumentService.upload
-- (so folder rights and local/S3 storage are respected), with a phase:
--   BEFORE  — « avant » photo (creation / with-photos)
--   AFTER   — « après » photo (needed to resolve a RESERVE)
--   OTHER   — any other attachment
-- unique(ticket_id, document_id): a document is attached to a ticket at most once.
-- ticket_id cascades on delete (a deleted ticket takes its attachments with it).
-- ============================================================================

create table ticket_attachment (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid not null,
    ticket_id   uuid not null references ticket(id) on delete cascade,
    document_id uuid not null references document(id),
    phase       text not null check (phase in ('BEFORE','AFTER','OTHER')),
    created_by  uuid,
    created_at  timestamptz not null default now(),
    unique(ticket_id, document_id)
);

-- List a ticket's photos / count them, scoped to the caller's org.
create index if not exists idx_ticket_attachment_org_ticket
    on ticket_attachment (org_id, ticket_id);
