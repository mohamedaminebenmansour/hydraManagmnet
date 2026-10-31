-- ============================================================================
-- V37: bordereau — versioned price schedules per chantier.
--
-- A chantier has one ACTIVE bordereau version at a time. New versions are
-- created as DRAFT (a copy of the active one), edited, then activated — at
-- which point the previous ACTIVE version is archived (read-only).
--
-- bordereau_line mirrors the column list from BORDEREAU-ANALYSIS §7.2:
--   section_path, article_no, sort_order, designation, unit_raw, unit,
--   quantity, unit_price, ouvrage_id, work_nature, paired_line_id, specs,
--   import_warnings.
--
-- article_no is text and NOT unique on its own (see §2.3 of the analysis:
-- numbers like "Prix N°1" restart per série). Uniqueness is enforced in
-- application code as (version_id, section_path, article_no) — not by a
-- DB constraint, because duplicate article_no inside the same section is
-- tolerated with a warning (see §6).
-- ============================================================================

create table if not exists bordereau_version (
    id           uuid primary key default gen_random_uuid(),
    org_id       uuid not null references organization(id),
    project_id   uuid not null references project(id) on delete cascade,
    version_no   int    not null,
    label        text   not null,          -- 'Initial', 'Avenant 1', 'Avenant 2'…
    reason       text,                     -- why the avenant was created
    document_id  uuid,                     -- optional link to the signed PDF document
    status       text   not null default 'DRAFT'
                 check (status in ('DRAFT', 'ACTIVE', 'ARCHIVED')),
    created_by   uuid   references app_user(id),
    created_at   timestamptz not null default now()
);

-- One ACTIVE version per project (partial unique index).
create unique index if not exists uq_bordereau_active_per_project
    on bordereau_version (project_id) where status = 'ACTIVE';

-- Fast listing of a project's versions.
create index if not exists idx_bordereau_version_project
    on bordereau_version (org_id, project_id, version_no);

-- Row-level security: a user only ever sees bordereaux of their own org.
alter table bordereau_version enable row level security;

-- ─────────────────────────────────────────────────────────────────────────────
-- bordereau_line — one priced article inside a version.
-- ─────────────────────────────────────────────────────────────────────────----

create table if not exists bordereau_line (
    id               uuid primary key default gen_random_uuid(),
    org_id           uuid not null references organization(id),
    version_id       uuid not null references bordereau_version(id) on delete cascade,
    -- Section path as written in the source bordereau, e.g.
    --   {"Sous-lot II","B","B2.11"} or {"IV Génie civil","42 Réservoir sur piliers 250 m³"}
    section_path     text[] not null default '{}',
    -- Free-format article number: "101a", "A2.13", "Prix N°1", "1.2.7.4"…
    article_no       text,
    -- Display order inside the version (so the UI can sort without re-parsing).
    sort_order       int  not null default 0,
    designation      text not null,
    -- unit_raw = exactly as written ("mètre linéaire", "M3", "F"…);
    -- unit = normalised enum ("ML", "M3", "M2", "U", "KG", "ENS", "FORFAIT", "LOT").
    unit_raw         text,
    unit             text,
    -- Money is numeric(18,3) per AI-RULES rule 3.
    quantity         numeric(18,3),
    unit_price       numeric(18,3),
    -- Optional link to the ouvrage this line belongs to (V36).
    ouvrage_id       uuid references ouvrage(id) on delete set null,
    -- FOURNITURE, POSE, TERRASSEMENT, GENIE_CIVIL, EQUIPEMENT, ESSAIS,
    -- INSTALLATION, DIVERS (see §2.1).
    work_nature      text,
    -- Links a fourniture line to its pose line (§2.2).
    paired_line_id   uuid references bordereau_line(id) on delete set null,
    -- Extracted values: de_mm, dn_mm, pn_bar, materiau, dosage_kg_m3,
    -- capacite_m3, longueur_m (§7.3).
    specs            jsonb not null default '{}'::jsonb,
    -- Per-line import warnings (§6): missing unit, duplicate article_no, etc.
    import_warnings  text[] not null default '{}',
    created_at       timestamptz not null default now()
);

-- Primary access path: list all lines of a version (filtered by org_id).
create index if not exists idx_bordereau_line_version
    on bordereau_line (org_id, version_id, sort_order);

-- Fast lookup by article_no within a version (for the compare endpoint).
create index if not exists idx_bordereau_line_article
    on bordereau_line (version_id, article_no);

-- Row-level security.
alter table bordereau_line enable row level security;
