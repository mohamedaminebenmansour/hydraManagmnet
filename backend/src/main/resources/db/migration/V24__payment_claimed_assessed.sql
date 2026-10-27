-- ============================================================================
-- V24 — TASK 1: claimed vs assessed amounts on cash_movement
-- ============================================================================
-- PRODUCT GOAL: owner trusts "where every dinar went" — the caisse posts the
-- ASSESSED amount (what the company accepts to pay), while the CLAIMED amount
-- (what the sub/supplier asked on the facture/situation) stays visible for the
-- full timeline. When the two differ, a reason is mandatory.
--
-- Design notes:
--   * amount stays the single posting source of truth and is kept in sync with
--     assessed_amount on submit (CashService.createMovement normalization).
--   * claimed/assessed are NULL for legacy IN/TRANSFER movements and for the
--     short B5 form (amount-only) — no backfill needed, constraints below are
--     NULL-safe for existing rows.
--   * process_instance_id already exists (V20__cash_ledger.sql); the movement
--     <-> document link already exists (cash_movement_document junction).
-- ============================================================================

alter table cash_movement
    add column if not exists claimed_amount  numeric(18,3),
    add column if not exists assessed_amount numeric(18,3),
    add column if not exists variance_reason text;

-- assessed, when present, must be strictly positive (same rule as amount)
alter table cash_movement
    add constraint chk_cm_assessed_positive
    check (assessed_amount is null or assessed_amount > 0);

-- claimed present AND assessed differs → variance_reason required
-- (enforced in service too; this is the DB-level safety net)
alter table cash_movement
    add constraint chk_cm_variance_reason
    check (claimed_amount is null
           or assessed_amount is null
           or claimed_amount = assessed_amount
           or variance_reason is not null);

create index if not exists idx_cm_assessed
    on cash_movement (assessed_amount) where assessed_amount is not null;

comment on column cash_movement.claimed_amount  is 'Montant réclamé par le tiers (facture/situation). NULL si non renseigné';
comment on column cash_movement.assessed_amount is 'Montant évalué accepté par la société. amount = assessed_amount on submit';
comment on column cash_movement.variance_reason is 'Motif obligatoire quand assessed_amount <> claimed_amount';
