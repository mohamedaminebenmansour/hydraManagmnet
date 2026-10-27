-- ============================================================================
-- V20: Cash ledger — party + cash_pot + cash_movement (+ documents junction)
--
-- Source of truth for money in Hydra. Inspired by:
--   - Tunisian BTP "Caisse" pattern (IntegraSys ERP "Finance et caisse" module):
--     one central "Caisse centrale" per company + one "Caisse chantier" per
--     project — both tracked in TND.
--   - SYSCOHADA / francophone Africa: immutable POSTED entries, soft-delete via VOID
--   - European accrual pattern: balances are ALWAYS computed from POSTED movements,
--     never stored as a mutable field.
--
-- Phase 1 (this migration): DB + API. Phase 2 will wire ProcessService to
-- auto-post a movement when a PAYMENT_APPROVAL workflow completes.
--
-- Idempotent: safe to re-run (uses IF NOT EXISTS / DO $$ exception handlers,
-- matching the V19 pattern).
-- ============================================================================

-- ============================================================================
-- 1. party — minimal directory of sous-traitants / fournisseurs
-- ============================================================================
CREATE TABLE IF NOT EXISTS party (
    id              uuid primary key default gen_random_uuid(),
    org_id          uuid not null references organization(id),
    name            text not null,
    type             text not null check (type in ('SUB','SUPPLIER','OTHER')),
    phone           text,
    active          boolean not null default true,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

CREATE INDEX IF NOT EXISTS idx_party_org_active
    ON party (org_id, active);
CREATE INDEX IF NOT EXISTS idx_party_org_name
    ON party (org_id, name);

COMMENT ON TABLE party IS 'Directory of sous-traitants / fournisseurs per org (Phase 1: minimal).';

-- ============================================================================
-- 2. cash_pot — a "caisse". Either COMPANY-wide or PROJECT-scoped.
-- ============================================================================
CREATE TABLE IF NOT EXISTS cash_pot (
    id              uuid primary key default gen_random_uuid(),
    org_id          uuid not null references organization(id),
    type            text not null check (type in ('COMPANY','PROJECT')),
    project_id      uuid references project(id),
    name            text not null,
    currency        text not null default 'TND',
    active          boolean not null default true,
    created_at      timestamptz not null default now()
);

-- One active COMPANY pot per org (the central "Caisse centrale").
-- Idempotent: if the partial index already exists, this is a no-op.
CREATE UNIQUE INDEX IF NOT EXISTS uq_cash_pot_company_active
    ON cash_pot (org_id) WHERE type = 'COMPANY' AND active = true;

-- One active PROJECT pot per (org, project).
CREATE UNIQUE INDEX IF NOT EXISTS uq_cash_pot_project_active
    ON cash_pot (org_id, project_id) WHERE type = 'PROJECT' AND active = true;

CREATE INDEX IF NOT EXISTS idx_cash_pot_org
    ON cash_pot (org_id, active);
CREATE INDEX IF NOT EXISTS idx_cash_pot_project
    ON cash_pot (project_id) WHERE project_id IS NOT NULL;

COMMENT ON TABLE cash_pot IS
    'Cash pot = a caisse. Either COMPANY-wide (Caisse centrale) or PROJECT-scoped (Caisse chantier).';

-- ============================================================================
-- 3. cash_movement — every money event. DRAFT → POSTED, POSTED → VOID (never edited in place).
-- ============================================================================
CREATE TABLE IF NOT EXISTS cash_movement (
    id                    uuid primary key default gen_random_uuid(),
    org_id                uuid not null references organization(id),
    movement_type         text not null check (movement_type in ('IN','OUT','TRANSFER')),
    amount                numeric(18,3) not null check (amount > 0),
    currency              text not null default 'TND',
    movement_date         date not null,
    from_pot_id           uuid references cash_pot(id),
    to_pot_id             uuid references cash_pot(id),
    project_id            uuid references project(id),
    party_id              uuid references party(id),
    label                text,
    note                 text,
    status               text not null default 'DRAFT'
        check (status in ('DRAFT','POSTED','VOID')),
    process_instance_id  uuid,
    created_by           uuid not null references app_user(id),
    created_at           timestamptz not null default now(),
    posted_by            uuid references app_user(id),
    posted_at            timestamptz,
    voided_by            uuid references app_user(id),
    voided_at            timestamptz,
    void_reason          text
);

-- DB-level guards: enforce IN/OUT/TRANSFER pot presence rules.
-- These complement the service-layer validation (defence in depth).
ALTER TABLE cash_movement DROP CONSTRAINT IF EXISTS chk_movement_in_pots;
DO $$ BEGIN
    ALTER TABLE cash_movement
        ADD CONSTRAINT chk_movement_in_pots CHECK (
            (movement_type = 'IN'       AND to_pot_id IS NOT NULL     AND from_pot_id IS NULL)
         OR (movement_type = 'OUT'      AND from_pot_id IS NOT NULL   AND to_pot_id IS NULL)
         OR (movement_type = 'TRANSFER' AND from_pot_id IS NOT NULL   AND to_pot_id IS NOT NULL)
        );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- from ≠ to for TRANSFER (only meaningful when both are set)
ALTER TABLE cash_movement DROP CONSTRAINT IF EXISTS chk_movement_transfer_distinct;
DO $$ BEGIN
    ALTER TABLE cash_movement
        ADD CONSTRAINT chk_movement_transfer_distinct CHECK (
            movement_type <> 'TRANSFER' OR from_pot_id <> to_pot_id
        );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

CREATE INDEX IF NOT EXISTS idx_cm_org_status
    ON cash_movement (org_id, status);
CREATE INDEX IF NOT EXISTS idx_cm_from_pot
    ON cash_movement (from_pot_id);
CREATE INDEX IF NOT EXISTS idx_cm_to_pot
    ON cash_movement (to_pot_id);
CREATE INDEX IF NOT EXISTS idx_cm_party
    ON cash_movement (party_id);
CREATE INDEX IF NOT EXISTS idx_cm_project
    ON cash_movement (project_id);
CREATE INDEX IF NOT EXISTS idx_cm_date
    ON cash_movement (movement_date);
CREATE INDEX IF NOT EXISTS idx_cm_process
    ON cash_movement (process_instance_id) WHERE process_instance_id IS NOT NULL;

COMMENT ON TABLE cash_movement IS
    'Immutable money event. DRAFT can be edited; POSTED is immutable (use VOID + new movement).';

-- ============================================================================
-- 4. cash_movement_document — optional link to documents (BC, facture, BL, ...)
-- ============================================================================
CREATE TABLE IF NOT EXISTS cash_movement_document (
    movement_id   uuid not null references cash_movement(id) on delete cascade,
    document_id   uuid not null,  -- references document(id) if the table exists
    primary key (movement_id, document_id)
);

CREATE INDEX IF NOT EXISTS idx_cmd_document
    ON cash_movement_document (document_id);

COMMENT ON TABLE cash_movement_document IS
    'Optional link between a cash movement and the documents justifying it (BC, BL, facture, ...).';

-- ============================================================================
-- 5. Disable RLS (matching the V19 pattern for process tables)
-- ============================================================================
ALTER TABLE party                   DISABLE ROW LEVEL SECURITY;
ALTER TABLE cash_pot                DISABLE ROW LEVEL SECURITY;
ALTER TABLE cash_movement           DISABLE ROW LEVEL SECURITY;
ALTER TABLE cash_movement_document  DISABLE ROW LEVEL SECURITY;
