-- =====================================================================
-- V23 (TASK C1): optional link from a ticket to a process instance.
--
-- Tickets are simple tasks/issues (assign, status, photo) — they are NOT
-- a second workflow engine. Payments are submitted exclusively through
-- POST /api/cash/movements/submit-payment, which starts a PAYMENT_APPROVAL
-- process on subject CASH_MOVEMENT. This nullable column only allows a
-- ticket to *reference* a process instance if a future template needs it;
-- no ticket endpoint starts a process today.
-- =====================================================================

alter table ticket add column if not exists process_instance_id uuid
  references process_instance(id);

create index if not exists ticket_process_instance_idx on ticket (process_instance_id);
