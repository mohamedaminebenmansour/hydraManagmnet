-- ============================================================================
-- V31: Virtual Stock — Slice 1.2 support (services + controllers layer)
--
-- Scope (Part 1.2, additive, zero-risk):
--   1. stock_move_document junction — link EXISTING documents to a move
--      WITHOUT touching the append-only stock_move table (its trigger
--      forbids UPDATE, so the "link document to existing move" feature
--      needs a side table — same pattern as cash_movement_document V20 §4).
--   2. Allow NEGATIVE qty for ADJUST moves only (inventory count variance:
--      "found fewer than the ledger says" must be recordable). The signing
--      convention in stock_balance / signedBalance already handles a
--      negative ADJUST (it sums m.qty as-is for ADJUST).
--   3. Part 2 extension points — COMMENT-ONLY (no columns added; see §3).
--
-- Idempotent: IF NOT EXISTS / DO $$ EXCEPTION / ON CONFLICT throughout.
-- ============================================================================


-- ============================================================================
-- 1. stock_move_document — multi-proof junction (move ↔ documents)
--
--    stock_move.document_id (V30) stays the PRIMARY proof captured at move
--    creation (the BL photo at the gate). This junction holds ADDITIONAL
--    linked documents (facture scan, attestation, bon de carburant photo…)
--    attached after the move exists.
--
--    document_id is intentionally NOT a DB FK (same decision as V30):
--    the document table had RLS quirks (V16) and the service layer
--    validates org + project access before inserting.
-- ============================================================================
CREATE TABLE IF NOT EXISTS stock_move_document (
    id            uuid primary key default gen_random_uuid(),
    org_id        uuid not null references organization(id),
    move_id       uuid not null references stock_move(id) on delete cascade,
    document_id   uuid not null,
    created_by    uuid not null references app_user(id),
    created_at    timestamptz not null default now(),
    UNIQUE (move_id, document_id)
);

CREATE INDEX IF NOT EXISTS idx_smd_document ON stock_move_document (document_id);
CREATE INDEX IF NOT EXISTS idx_smd_move     ON stock_move_document (move_id);

COMMENT ON TABLE stock_move_document IS
    'Additional documents linked to a stock move after creation (multi-proof). Primary proof lives on stock_move.document_id.';

ALTER TABLE stock_move_document DISABLE ROW LEVEL SECURITY;


-- ============================================================================
-- 2. ADJUST may carry a NEGATIVE qty (inventory variance "missing units")
--
--    V30 declared qty numeric(18,3) NOT NULL CHECK (qty > 0) inline —
--    Postgres auto-named that constraint stock_move_qty_check.
--
--    New rule: qty > 0 for all types EXCEPT ADJUST, which may also be < 0
--    (never 0). The balance views / native queries already sum m.qty for
--    ADJUST, so a negative adjust correctly subtracts.
-- ============================================================================
ALTER TABLE stock_move DROP CONSTRAINT IF EXISTS stock_move_qty_check;
ALTER TABLE stock_move DROP CONSTRAINT IF EXISTS chk_stock_move_qty;
DO $$ BEGIN
    ALTER TABLE stock_move
        ADD CONSTRAINT chk_stock_move_qty CHECK (
            qty > 0 OR (move_type = 'ADJUST' AND qty < 0)
        );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

COMMENT ON CONSTRAINT chk_stock_move_qty ON stock_move IS
    'qty positive for IN/OUT/TRANSFER/DIRECT_USE; ADJUST may be negative (inventory variance) but never zero.';


-- ============================================================================
-- 3. Part 2 extension points — COMMENT ONLY (zero-risk decision)
--
--    The following columns are RESERVED for Part 2 (BC / BL / facture
--    slice) and are deliberately NOT added now, per the "ONLY if zero risk"
--    instruction — adding unused columns is zero-risk but pollutes the
--    schema before their semantics are fixed. When Part 2 lands, it adds:
--
--      ALTER TABLE stock_move ADD COLUMN bc_line_id  uuid;  -- → purchase_order_line(id)
--      ALTER TABLE stock_move ADD COLUMN bl_line_id  uuid;  -- → goods_receipt_line(id)
--      ALTER TABLE stock_move ADD COLUMN bordereau_article_id uuid; -- future article-keyed bordereau
--
--    Service-layer seams already prepared in StockService:
--      - PostMoveRequest carries optional metadata map (ignored in 1.2) so
--        the API contract will not break when Part 2 promotes real fields.
--      - StockService.postMove javadoc marks the exact insertion point for
--        BC/BL lineage resolution.
-- ============================================================================
COMMENT ON TABLE stock_move IS
    'Append-only stock ledger. Balances are computed: SUM(signed qty) per (item, location). Never UPDATE in place. Part 2 reserves bc_line_id / bl_line_id / bordereau_article_id (comment-only extension point, see V31 §3).';

-- End V31 — Virtual Stock Slice 1.2 support
