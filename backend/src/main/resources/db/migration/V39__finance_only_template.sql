-- ============================================================================
-- V39: FINANCE_ONLY system template — light-touch finance approval for small
-- payments (<= 1000 TNDND), 2 steps DRAFT + POST, both FINANCE.
--
-- Inactive by default (active = false). An org turns it on by setting
-- active = true (or by adding an org-scoped template with the same key, which
-- start() prefers over the system one).
--
-- Its POST completion posts the movement exactly like PAYMENT_APPROVAL: the
-- cash hook in ProcessService.completeStep fires for ANY CASH_MOVEMENT process
-- that reaches COMPLETED, regardless of which template drove it — so no code
-- change is needed for the auto-post. This migration only seeds the template
-- and its 2 steps.
--
-- Does NOT edit V38 (AI-RULES 2). Conditions money bound uses numeric literal
-- 1000 (no money column here). approval_mode/due_days default to 'ANY'/NULL
-- (from V38) for both steps.
-- ============================================================================

-- 1. System template (org_id NULL), CASH_PAYMENT, priority 500, amount_max 1000.
INSERT INTO process_template (key, name, org_id, subject_type, conditions, priority, active)
VALUES ('FINANCE_ONLY',
        'Approbation finance (<= 1000 TND)',
        NULL,
        'CASH_PAYMENT',
        '{"amount_max": 1000}'::jsonb,
        500,
        false);

-- 2. Two FINANCE steps: DRAFT then POST.
INSERT INTO process_template_step (template_id, step_order, step_key, name, assignee_role, requires_attachment)
SELECT t.id, s.step_order, s.step_key, s.name, s.assignee_role, s.requires_attachment
FROM (VALUES
    (1, 'DRAFT', 'Create/confirm payment intent',          'FINANCE', false),
    (2, 'POST',  'Marks completed (hook for caisse post)', 'FINANCE', false)
) AS s(step_order, step_key, name, assignee_role, requires_attachment)
CROSS JOIN process_template t
WHERE t.key = 'FINANCE_ONLY' AND t.org_id IS NULL
ON CONFLICT (template_id, step_order) DO NOTHING;
