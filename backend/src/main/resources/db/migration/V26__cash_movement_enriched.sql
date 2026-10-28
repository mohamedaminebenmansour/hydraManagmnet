-- ============================================================================
-- V26 — enrich cash_movement for a real Tunisian/African "état de caisse".
-- ============================================================================
-- PRODUCT GOAL (STANDING RULE 8): every movement can answer the same questions
-- a Tunisian BTP chantier Excel état de caisse answers at a glance:
--
--   • how was it paid?   → payment_mode (ESPECE | CHEQUE | VIREMENT | VERSEMENT | MANDAT | AUTRE)
--   • which bon/ref?     → voucher_no (Bon N° — petty-cash voucher best practice)
--                          + payment_ref (n° chèque / ref virement / ref mandat)
--   • what nature?       → nature (ALIM_CAISSE | SOUS_TRAITANCE | MATERIEL | TRANSPORT |
--                                     MAIN_OEUVRE | LOCATION | LABO | CARBURANT | DIVERS)
--   • optional facture?  → invoice_ref (réf facture / situation du sous-traitant)
--
-- Source for the vocabularies (Task brief + web research, Sep 2026):
--   - "Moyens de Règlement" francophone accounting canon: Espèces, Chèques,
--     Traites/Letters of change, Virements bancaires (Scribd "Moyens de
--     Règlement" reference, turn0search0 / turn0search3).
--   - Petty-cash voucher best practice (UNFPA Policy and Procedures for Petty
--     Cash Management, turn0search13; LinkedIn Petty Cash Management Best
--     Practices, turn0search14): every payment must carry a Voucher Number +
--     a payment mode + a description / nature.
--   - Odoo petty-cash pattern (turn0search7, turn0search9): drawer (≈ our
--     cash_pot) + transaction (date, description, in/out amount, analytic
--     account) — draft→post approval is enough (no second ledger).
--
-- DESIGN NOTES
--   * All five columns are NULLABLE so legacy DRAFT/POSTED/VOID rows stay valid
--     without a backfill UPDATE. The frontend treats NULL as "non renseigné"
--     and lets the user fill them in on a DRAFT.
--   * CHECK constraints are NULL-safe (NULL OR in-allowed-list) — matches the
--     V24 chk_cm_variance_reason pattern (defence in depth: the service layer
--     also validates via PaymentMode / MovementNature enums).
--   * Idempotent: `add column if not exists` + drop-then-add constraint,
--     matching the V24/V25 style.
--   * Out of scope (per STANDING RULE 10): bank/cheque lifecycle, SYSCOHADA
--     accounting, retenues engine. payment_ref is a free-text reference, not a
--     cheque-status tracker.
--   * Tunisia defaults vs extensible: the CHECK whitelist is the Tunisia v1
--     set; future variants (e.g. adding "MOBILE_MONEY" for West Africa) will
--     be additive migrations that ALTER the CHECK only. Application code
--     (PaymentMode / MovementNature enums) documents the same vocab.
-- ============================================================================

alter table cash_movement
    add column if not exists payment_mode text,
    add column if not exists payment_ref text,
    add column if not exists voucher_no  text,
    add column if not exists nature      text,
    add column if not exists invoice_ref text;

-- payment_mode whitelist (NULL = not yet specified — legacy-safe)
alter table cash_movement drop constraint if exists chk_cm_payment_mode;
alter table cash_movement
    add constraint chk_cm_payment_mode check (
        payment_mode is null
        or payment_mode in ('ESPECE','CHEQUE','VIREMENT','VERSEMENT','MANDAT','AUTRE')
    );

-- nature whitelist (NULL = not yet specified — legacy-safe)
alter table cash_movement drop constraint if exists chk_cm_nature;
alter table cash_movement
    add constraint chk_cm_nature check (
        nature is null
        or nature in ('ALIM_CAISSE','SOUS_TRAITANCE','MATERIEL','TRANSPORT',
                      'MAIN_OEUVRE','LOCATION','LABO','CARBURANT','DIVERS')
    );

comment on column cash_movement.payment_mode is
    'Mode de règlement : ESPECE | CHEQUE | VIREMENT | VERSEMENT | MANDAT | AUTRE (NULL si non renseigné)';
comment on column cash_movement.payment_ref is
    'Référence du règlement : n° chèque, réf virement, réf mandat (libre, NULL si non renseigné)';
comment on column cash_movement.voucher_no is
    'Bon N° — numéro de bon de caisse (petty-cash voucher best practice)';
comment on column cash_movement.nature is
    'Nature comptable : ALIM_CAISSE | SOUS_TRAITANCE | MATERIEL | TRANSPORT | MAIN_OEUVRE | LOCATION | LABO | CARBURANT | DIVERS';
comment on column cash_movement.invoice_ref is
    'Référence facture / situation du tiers (optionnel — pour rapprochement)';
