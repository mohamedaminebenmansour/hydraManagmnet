-- ============================================================================
-- V47 — domain_event: business facts for the circuit engine (v2 step 1).
--
-- One row per business action, written in the SAME transaction as the action
-- (DomainEventService.publish). Nothing consumes them yet (processed_at stays
-- null). Different purpose from event_log (append-only audit trail), which is
-- unchanged.
-- ============================================================================

create table if not exists domain_event (
    id                 uuid primary key default gen_random_uuid(),
    org_id             uuid not null,
    type               text not null,
    subject_type       text not null,
    subject_id         uuid not null,
    project_id         uuid null,
    payload            jsonb not null default '{}',
    caused_by_event_id uuid null references domain_event(id),
    depth              int not null default 0,
    created_by         uuid null,
    created_at         timestamptz not null default now(),
    processed_at       timestamptz null
);

create index if not exists idx_domain_event_org_created on domain_event (org_id, created_at desc);
create index if not exists idx_domain_event_org_type    on domain_event (org_id, type);
create index if not exists idx_domain_event_unprocessed on domain_event (processed_at) where processed_at is null;

comment on table domain_event is 'Business events (one per action, same transaction). Not the audit log (event_log).';
comment on column domain_event.payload is 'Ids, amounts as strings and codes only; no names, no free text.';
