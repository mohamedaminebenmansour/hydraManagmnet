-- ════════════════════════════════════════════════════════════════════════
--  V35 — Persist commercial links (bc_id + facture_id) on cash_movement
--  ─────────────────────────────────────────────────────────────────────────
--  Part 4.1 — schema only. Two nullable columns on cash_movement:
--    - bc_id       → purchase_order(id)   (commercial link for material-proof)
--    - facture_id  → facture(id)           (invoice traceability)
--
--  No DB FK constraints (matches document_id / cash_movement_id convention —
--  app-validated to avoid RLS quirks).
--
--  Persisted on INSERT only (create/submit path). Never UPDATEd on POSTED
--  movements (POSTED immutability enforced by service-layer status check).
--
--  Additive only. No touch to stock_move, process engine, or match rules.
-- ════════════════════════════════════════════════════════════════════════

ALTER TABLE cash_movement ADD COLUMN IF NOT EXISTS bc_id       uuid;
ALTER TABLE cash_movement ADD COLUMN IF NOT EXISTS facture_id  uuid;

CREATE INDEX IF NOT EXISTS idx_cm_org_bc
    ON cash_movement (org_id, bc_id) WHERE bc_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_cm_org_fac
    ON cash_movement (org_id, facture_id) WHERE facture_id IS NOT NULL;

COMMENT ON COLUMN cash_movement.bc_id IS
    'FK→purchase_order(id). Commercial link for material-proof warnings. Set on INSERT only. Part 4.1.';
COMMENT ON COLUMN cash_movement.facture_id IS
    'FK→facture(id). Invoice traceability. Set on INSERT only. Part 4.1.';
