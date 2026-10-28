-- ============================================================================
-- V28 — void_requested_at + void_request_reason on cash_movement (TASK 6)
-- ============================================================================
-- PRODUCT GOAL: Finance cannot void alone (owner-only void per TASK 1). But
-- finance can REQUEST a void — which creates an audit event + sets a flag on
-- the movement so the owner sees "void requested" on the movement detail.
-- The owner then calls the existing void endpoint with a reason.
--
-- This is a minimal v1: request-void = audit event + flag columns, NOT a
-- workflow/approval. The owner is not forced to act — they see the request
-- on the movement detail and decide.
--
-- Idempotent (matches V24/V25/V26/V27 style).
-- ============================================================================

alter table cash_movement
    add column if not exists void_requested_at timestamptz,
    add column if not exists void_request_reason text;

comment on column cash_movement.void_requested_at is
    'Horodatage de la demande d''annulation par la finance (TASK 6). NULL si aucune demande. Le gérant voit ce champ et décide de voider ou non.';
comment on column cash_movement.void_request_reason is
    'Motif de la demande d''annulation par la finance. Sert d''information pour le gérant — il devra fournir son propre motif au moment du void.';
