-- ============================================================================
-- V38: Approval rules — turn process_template into a rule source + per-project
-- overrides, so a future ApprovalRuleService can pick which template governs a
-- given payment (or other subject) instead of always PAYMENT_APPROVAL.
--
-- Scope (feature/approval-rules):
--   * process_template          -> add subject_type, conditions (jsonb), priority
--   * process_template_step     -> add approval_mode (ANY|ALL), due_days
--   * project_process_template  -> add amount_threshold_override (per-chantier)
--   * Seed the existing PAYMENT_APPROVAL system template as the fallback rule
--     (subject_type = 'CASH_PAYMENT', priority = 1000 = matches last).
--
-- Schema notes (verified against the live hydra_test schema before writing):
--   * process_template already has `active boolean not null default true` since
--     V17 — it is NOT re-added here (a second ADD COLUMN would error). The
--     handoff spec listed it; this migration corrects that against the real
--     schema. No existing migration is edited (AI-RULES 2).
--   * process_template uses `key` (not `code`) — the seed UPDATE filters on
--     `key = 'PAYMENT_APPROVAL'` and `org_id IS NULL` (the system template),
--     which is the intended fallback rule.
--
-- Money rule (AI-RULES 3): amount_threshold_override is numeric(18,3).
-- Enum rule: approval_mode is constrained by a CHECK to ANY | ALL.
-- One statement per line, every statement ends with ';'.
-- ============================================================================

-- 1. process_template — rule metadata
ALTER TABLE process_template
  ADD COLUMN subject_type text NULL,
  ADD COLUMN conditions   jsonb NOT NULL DEFAULT '{}',
  ADD COLUMN priority     int NOT NULL DEFAULT 100;

-- 2. process_template_step — multi-approver mode + due-date SLA
ALTER TABLE process_template_step
  ADD COLUMN approval_mode text NOT NULL DEFAULT 'ANY'
    CHECK (approval_mode IN ('ANY','ALL')),
  ADD COLUMN due_days      int NULL;

-- 3. project_process_template — per-chantier amount threshold override
ALTER TABLE project_process_template
  ADD COLUMN amount_threshold_override numeric(18,3) NULL;

-- 4. Seed: PAYMENT_APPROVAL becomes the fallback CASH_PAYMENT rule.
--    priority = 1000 so any org-specific CASH_PAYMENT rule with a lower
--    priority value wins; conditions = '{}' matches every payment.
UPDATE process_template
   SET subject_type = 'CASH_PAYMENT',
       conditions   = '{}'::jsonb,
       active       = true,
       priority     = 1000
 WHERE key = 'PAYMENT_APPROVAL'
   AND org_id IS NULL;
