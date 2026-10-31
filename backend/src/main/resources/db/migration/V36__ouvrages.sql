-- ============================================================================
-- V36: ouvrage — the structured objects of a chantier (project).
--
-- A chantier contains several ouvrages: one or more CONDUITE, RESERVOIR,
-- STATION_POMPAGE, FORAGE, OUVRAGE_ANNEXE, BRANCHEMENTS, ASSAINISSEMENT,
-- plus exactly one COMMUN (auto-created per chantier — installation, repli,
-- essais globaux, raccordement au réseau existant, sécurité).
--
-- See docs/BORDEREAU-ANALYSIS.md §5 for the allowed characteristic keys
-- per ouvrage type. characteristics is jsonb not null default '{}' so an
-- ouvrage can be created with no characteristics and filled later.
--
-- The single COMMUN ouvrage per chantier is created in code
-- (OuvrageService.ensureCommunOuvrage) rather than by a DB trigger, so the
-- audit log captures the creation event and the org_id / project_id are
-- always consistent with the chantier that was just created.
-- ============================================================================

create table if not exists ouvrage (
    id              uuid primary key default gen_random_uuid(),
    org_id          uuid not null references organization(id),
    project_id      uuid not null references project(id) on delete cascade,
    type            text not null check (type in (
                        'RESERVOIR','CONDUITE','STATION_POMPAGE','FORAGE',
                        'OUVRAGE_ANNEXE','BRANCHEMENTS','ASSAINISSEMENT',
                        'COMMUN','AUTRE')),
    code            text,
    name            text not null,
    characteristics jsonb not null default '{}'::jsonb,
    sort_order      int,
    created_by      uuid references app_user(id),
    created_at      timestamptz not null default now()
);

-- Primary access path: list all ouvrages of a chantier (filtered by org_id).
create index if not exists idx_ouvrage_org_project
    on ouvrage (org_id, project_id);

-- A chantier has at most one COMMUN ouvrage (installation, repli, essais…).
-- Partial unique index so the constraint only applies to COMMUN rows.
create unique index if not exists uq_ouvrage_commun_per_project
    on ouvrage (project_id) where type = 'COMMUN';

-- Row-level security: a user only ever sees ouvrages of their own org.
alter table ouvrage enable row level security;
