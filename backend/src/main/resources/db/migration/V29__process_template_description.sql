-- ============================================================================
-- V29 — process_template.description + step reorder support
-- ============================================================================
-- Gap 1: process_template has no description column. Owners want to document
--        what a template is for ("Paiement sous-traitant — finance puis gérant").
-- Gap 2: Step reordering — we add a reorder endpoint that updates step_order
--        for a batch of step IDs. No schema change needed (step_order already
--        exists); this migration only adds the description column.
--
-- Idempotent (V24/V25/V26/V27/V28 style).
-- ============================================================================

alter table process_template
    add column if not exists description text;

comment on column process_template.description is
    'Description humaine du modèle (ex: "Paiement sous-traitant — finance puis gérant"). NULL si non renseigné.';
