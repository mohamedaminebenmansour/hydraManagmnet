-- ════════════════════════════════════════════════════════════════════════
--  V34 — Factures (supplier invoices) — Part 3.1
--  ─────────────────────────────────────────────────────────────────────────
--  Additive only. Two new tables:
--    - facture       (header: project, supplier, BC/BL links, amounts, doc)
--    - facture_line  (line: item/bc_line/bl_line lineage, qty, unit_price, line_ht)
--
--  Rules (enforced in service):
--    DRAFT      → validate() → VALIDATED (lines frozen; requires ≥1 line)
--    DRAFT      → cancel()   → CANCELLED
--    VALIDATED  → cancel()   → CANCELLED (reason stored in audit)
--    CANCELLED  → terminal (excluded from match aggregates)
--
--  Free facture (bc_id + bl_id both null) allowed — prefer linking when available.
--  Supplier mismatch (facture vs BC) → 409 FACTURE_SUPPLIER_MISMATCH.
--  BL/BC mismatch → 409 FACTURE_BL_BC_MISMATCH.
--
--  Zero touch to stock_move, cash, process.
--  RLS off (service-layer enforces org isolation — matches all other purchase tables).
-- ════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS facture (
    id                  uuid primary key default gen_random_uuid(),
    org_id              uuid not null references organization(id),
    project_id          uuid not null references project(id),
    supplier_party_id   uuid references party(id),
    bc_id               uuid references purchase_order(id),     -- nullable: free facture allowed
    bl_id               uuid references bon_livraison(id),      -- nullable
    status              text not null default 'DRAFT'
                        check (status in ('DRAFT','VALIDATED','CANCELLED')),
    reference           text,                                   -- supplier invoice number
    invoice_date        date not null default current_date,
    due_date            date,                                   -- optional
    amount_ht           numeric(18,3),                         -- header total HT (optional)
    amount_tva          numeric(18,3),                         -- TVA
    amount_ttc          numeric(18,3),                         -- TTC (computed or stored)
    document_id         uuid,                                  -- optional PDF (app-validated, no DB FK)
    notes               text,
    created_by          uuid not null references app_user(id),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);
CREATE INDEX IF NOT EXISTS idx_fac_org_project   ON facture (org_id, project_id);
CREATE INDEX IF NOT EXISTS idx_fac_org_status    ON facture (org_id, status);
CREATE INDEX IF NOT EXISTS idx_fac_org_date      ON facture (org_id, invoice_date desc);
CREATE INDEX IF NOT EXISTS idx_fac_org_bc        ON facture (org_id, bc_id) WHERE bc_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_fac_org_bl        ON facture (org_id, bl_id) WHERE bl_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_fac_org_supplier  ON facture (org_id, supplier_party_id) WHERE supplier_party_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS facture_line (
    id                  uuid primary key default gen_random_uuid(),
    org_id              uuid not null references organization(id),
    facture_id          uuid not null references facture(id) on delete cascade,
    item_id             uuid references stock_item(id),          -- nullable: free-text line
    bc_line_id          uuid references purchase_order_line(id),  -- nullable lineage
    bl_line_id          uuid references bon_livraison_line(id),   -- nullable lineage
    description         text not null,
    qty                 numeric(18,3) not null check (qty > 0),
    uom                 text,
    unit_price          numeric(18,3),
    line_ht             numeric(18,3),                           -- line HT (qty × unit_price or manual)
    notes               text,
    created_by          uuid not null references app_user(id),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);
CREATE INDEX IF NOT EXISTS idx_fl_fac_id     ON facture_line (facture_id);
CREATE INDEX IF NOT EXISTS idx_fl_bc_line    ON facture_line (bc_line_id) WHERE bc_line_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_fl_bl_line    ON facture_line (bl_line_id) WHERE bl_line_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_fl_org_item   ON facture_line (org_id, item_id);

ALTER TABLE facture       DISABLE ROW LEVEL SECURITY;
ALTER TABLE facture_line  DISABLE ROW LEVEL SECURITY;

COMMENT ON TABLE facture IS
    'Facture (supplier invoice). DRAFT → VALIDATED → CANCELLED. Free facture (no BC/BL) allowed. Part 3.1.';
COMMENT ON TABLE facture_line IS
    'Facture line. qty > 0. bc_line_id/bl_line_id optional lineage. Frozen when parent VALIDATED.';
