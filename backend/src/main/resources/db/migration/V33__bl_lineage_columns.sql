-- ════════════════════════════════════════════════════════════════════════
--  V33 — Bon de Livraison (BL) tables + stock_move lineage columns — Part 2.2
--  ─────────────────────────────────────────────────────────────────────────
--  Additive only.
--
--  1. stock_move gets 2 NULLABLE columns (bc_line_id + bl_line_id) — the V31 §3
--     reservation is now realised. The append-only trigger is UNAFFECTED:
--     it fires on UPDATE/DELETE row ops, not on ALTER TABLE, and the moves.save()
--     path always issues INSERT (markNew() → isNew=true).
--
--  2. bon_livraison (header) + bon_livraison_line — mirror purchase_order
--     pattern from V32. RLS off (service-layer enforces org isolation).
--
--  3. No DB FKs on bc_line_id / bl_line_id (matches the document_id /
--     cash_movement_id convention — app-validated, no DB FK).
-- ════════════════════════════════════════════════════════════════════════

-- ── 1. stock_move lineage columns (the Part 2 seam) ──────────────────────
ALTER TABLE stock_move ADD COLUMN IF NOT EXISTS bc_line_id uuid;
ALTER TABLE stock_move ADD COLUMN IF NOT EXISTS bl_line_id  uuid;

CREATE INDEX IF NOT EXISTS idx_sm_bc_line
    ON stock_move (bc_line_id) WHERE bc_line_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sm_bl_line
    ON stock_move (bl_line_id)  WHERE bl_line_id  IS NOT NULL;

COMMENT ON COLUMN stock_move.bc_line_id IS
    'FK→purchase_order_line(id). Set when an IN move closes a BC line (Part 2.2). NULL for direct receives.';
COMMENT ON COLUMN stock_move.bl_line_id IS
    'FK→bon_livraison_line(id). Set when an IN move is sourced from a BL (Part 2.2). NULL for moves without a BL.';

-- ── 2. bon_livraison (header) ────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS bon_livraison (
    id                  uuid primary key default gen_random_uuid(),
    org_id              uuid not null references organization(id),
    project_id          uuid not null references project(id),
    bc_id               uuid references purchase_order(id),    -- nullable: DIRECT receive (documented)
    supplier_party_id   uuid references party(id),
    status              text not null default 'DRAFT'
                        check (status in ('DRAFT','POSTED','CANCELLED')),
    reference           text,                                  -- human-readable "BL-2026-001"
    received_at         timestamptz,                           -- set when POSTED (the stock-IN moment)
    location_id         uuid not null,                         -- where goods arrive (SITE or CENTRAL)
    document_id         uuid,                                  -- optional BL PDF (app-validated, no DB FK)
    notes               text,
    created_by          uuid not null references app_user(id),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);
CREATE INDEX IF NOT EXISTS idx_bl_org_project  ON bon_livraison (org_id, project_id);
CREATE INDEX IF NOT EXISTS idx_bl_org_bc       ON bon_livraison (org_id, bc_id) WHERE bc_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bl_org_status   ON bon_livraison (org_id, status);
CREATE INDEX IF NOT EXISTS idx_bl_org_received ON bon_livraison (org_id, received_at desc)
    WHERE received_at IS NOT NULL;

-- ── 3. bon_livraison_line ────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS bon_livraison_line (
    id                  uuid primary key default gen_random_uuid(),
    org_id              uuid not null references organization(id),
    bon_livraison_id    uuid not null references bon_livraison(id) on delete cascade,
    bc_line_id          uuid references purchase_order_line(id),  -- nullable: DIRECT receive
    item_id             uuid not null references stock_item(id),
    qty_received        numeric(18,3) not null check (qty_received > 0),
    uom                 text not null,
    notes               text,
    created_by          uuid not null references app_user(id),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);
CREATE INDEX IF NOT EXISTS idx_bll_bl_id     ON bon_livraison_line (bon_livraison_id);
CREATE INDEX IF NOT EXISTS idx_bll_bc_line   ON bon_livraison_line (bc_line_id) WHERE bc_line_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_bll_org_item  ON bon_livraison_line (org_id, item_id);

-- ── RLS off (matches stock_move / purchase_order convention) ────────────
ALTER TABLE bon_livraison       DISABLE ROW LEVEL SECURITY;
ALTER TABLE bon_livraison_line  DISABLE ROW LEVEL SECURITY;

COMMENT ON TABLE bon_livraison IS
    'Bon de Livraison (BL). DRAFT → POSTED (fires N stock IN moves in one transaction). CANCELLED only from DRAFT. Part 2.2.';
COMMENT ON TABLE bon_livraison_line IS
    'BL line item. qty_received > 0. bc_line_id nullable for DIRECT receive (no BC). Hard cap: SUM(qty_received) per bc_line ≤ qty_ordered.';
