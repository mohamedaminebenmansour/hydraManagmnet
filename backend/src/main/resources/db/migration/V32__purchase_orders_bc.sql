-- ════════════════════════════════════════════════════════════════════════
--  V32 — Purchase Orders (Bon de Commande) — Part 2.1
--  ─────────────────────────────────────────────────────────────────────────
--  Additive only. Two new tables:
--    - purchase_order      (header: project, supplier, status, dates, doc)
--    - purchase_order_line (item, qty_ordered, uom, unit_price)
--
--  Rules (enforced in service, validated by DB CHECK for status):
--    DRAFT      → editable lines; confirm() → CONFIRMED; cancel() → CANCELLED
--    CONFIRMED  → lines frozen; cancel(reason) → CANCELLED
--    CLOSED     → terminal (no endpoint in 2.1; will be set by BL reception in 2.2)
--    CANCELLED  → terminal
--
--  DOES NOT TOUCH stock_move (bc_line_id stays as a V31 §3 reserved comment
--  until Part 2.2 wires the BL→IN lineage).
--
--  Reuses: organization, project, party (V20), stock_item (V30), app_user.
--  RLS disabled — service-layer enforces org isolation (matches stock_move).
-- ════════════════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS purchase_order (
    id                  uuid primary key default gen_random_uuid(),
    org_id              uuid not null references organization(id),
    project_id          uuid not null references project(id),
    supplier_party_id   uuid references party(id),         -- nullable: ad-hoc supplier allowed
    status              text not null default 'DRAFT'
                        check (status in ('DRAFT','CONFIRMED','CANCELLED','CLOSED')),
    reference           text,                              -- human-readable "BC-2026-001"
    ordered_at          date not null default current_date,
    expected_at         date,                               -- optional expected delivery
    notes               text,
    document_id         uuid,                              -- optional BC PDF (no DB FK; app-validated)
    created_by          uuid not null references app_user(id),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);
CREATE INDEX IF NOT EXISTS idx_po_org_project   ON purchase_order (org_id, project_id);
CREATE INDEX IF NOT EXISTS idx_po_org_status    ON purchase_order (org_id, status);
CREATE INDEX IF NOT EXISTS idx_po_org_ordered   ON purchase_order (org_id, ordered_at desc);
CREATE INDEX IF NOT EXISTS idx_po_org_supplier  ON purchase_order (org_id, supplier_party_id)
    WHERE supplier_party_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS purchase_order_line (
    id                  uuid primary key default gen_random_uuid(),
    org_id              uuid not null references organization(id),
    purchase_order_id   uuid not null references purchase_order(id) on delete cascade,
    item_id             uuid not null references stock_item(id),
    qty_ordered         numeric(18,3) not null check (qty_ordered > 0),
    uom                 text not null,
    unit_price          numeric(18,3),                    -- optional
    notes               text,
    created_by          uuid not null references app_user(id),
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);
CREATE INDEX IF NOT EXISTS idx_pol_po_id        ON purchase_order_line (purchase_order_id);
CREATE INDEX IF NOT EXISTS idx_pol_org_item      ON purchase_order_line (org_id, item_id);

-- RLS off — service-layer enforces org isolation (matches stock_item / stock_move / cash_movement)
ALTER TABLE purchase_order      DISABLE ROW LEVEL SECURITY;
ALTER TABLE purchase_order_line DISABLE ROW LEVEL SECURITY;

COMMENT ON TABLE purchase_order IS
    'Bon de Commande (purchase order). DRAFT → CONFIRMED → CLOSED (or CANCELLED). Lines editable only in DRAFT. Part 2.1.';
COMMENT ON TABLE purchase_order_line IS
    'BC line item. Frozen when parent BC leaves DRAFT. qty_ordered > 0.';
