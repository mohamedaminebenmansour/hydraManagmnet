-- ============================================================================
-- V50: ticket_comment — the timeline of a signalement.
--
-- Three kinds of rows:
--   SYSTEM         — one per create (« Signalement créé »), no author action.
--   STATUS_CHANGE  — one per start / resolve / verify / reopen / cancel (and
--                    per VISA_DOCUMENT status move via POST /{id}/status).
--                    from_status / to_status capture the transition; body holds
--                    the note (resolve), the comment (reopen) or the reason (cancel).
--   COMMENT        — a free-form comment by anyone who can read the ticket.
--
-- ticket_id cascades on delete (a deleted ticket takes its timeline with it).
-- ============================================================================

create table ticket_comment (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid not null,
    ticket_id   uuid not null references ticket(id) on delete cascade,
    author_id   uuid not null references app_user(id),
    kind        text not null check (kind in ('COMMENT','STATUS_CHANGE','SYSTEM')),
    body        text,
    from_status text,
    to_status   text,
    created_at  timestamptz not null default now()
);

-- Read a ticket's timeline (oldest first), scoped to the caller's org.
create index if not exists idx_ticket_comment_org_ticket_created
    on ticket_comment (org_id, ticket_id, created_at);
