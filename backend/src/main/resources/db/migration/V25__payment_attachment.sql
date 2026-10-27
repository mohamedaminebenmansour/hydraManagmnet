-- ============================================================================
-- V25 — TASK 3: payment attachment → Document in the project Finance folder
-- ============================================================================
-- PRODUCT GOAL: "where every dinar went" — the payment's proof (facture / BL /
-- situation) must be a real Document, stored in the project's Finance folder
-- (06_FIN), and linked on the movement itself so the owner can open the proof
-- directly from the movement detail/timeline. Never a storage-only orphan.
--
-- Design notes:
--   * document_id is a single nullable FK on cash_movement (TASK 3 spec:
--     "linked on movement.document_id"). The V20 cash_movement_document
--     junction stays in sync for future multi-document support; the
--     attach endpoint writes both.
--   * NULL for movements without attachment (legacy rows and TRANSFER/IN).
--   * The Finance folder itself is ensured at runtime by
--     CashService.ensureFinanceFolder (code '06_FIN', name 'Finance') —
--     every project already gets one from the V8/V9 folder template; the
--     runtime ensure is the safety net for older/empty projects.
-- ============================================================================

alter table cash_movement
    add column if not exists document_id uuid;

-- FK is added separately so re-runs stay idempotent (add column if not exists
-- cannot carry the constraint inline without a name clash on re-run).
alter table cash_movement
    drop constraint if exists fk_cm_document;

alter table cash_movement
    add constraint fk_cm_document
    foreign key (document_id) references document (id);

create index if not exists idx_cm_document
    on cash_movement (document_id) where document_id is not null;

comment on column cash_movement.document_id is
    'Pièce justificative du paiement (facture / BL / situation) — Document rangé dans le dossier Finance (06_FIN) du projet';
