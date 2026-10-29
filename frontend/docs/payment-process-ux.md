# Payment Process UX

## Goal
When a payment is under approval, the user ALWAYS sees where it is in the process — not only in Mes tâches, but also on the movement detail page.

## What was implemented

### Movement detail — process step strip
When a movement has a `processInstanceId`:
1. The "Processus lié" section shows a link to the full workflow timeline.
2. Below it, a **process step strip** shows the visual progress:
   - Each step is a card with a colored dot (green = DONE, amber = PENDING/current, red = REJECTED, gray = SKIPPED).
   - The current step has an amber border highlight.
   - Each card shows: step name, assignee role, assignee name.
3. The strip is loaded from `GET /api/processes/{instanceId}/timeline` (one extra call, only when processInstanceId exists).

### Mes tâches — CASH_MOVEMENT only "Voir le dossier"
Already implemented (commit `007a22f`): CASH_MOVEMENT tasks show only the "Voir le dossier" button — no blind Valider/Rejeter.

### After final approve — POSTED + balance refresh
Already implemented: the approval desk navigates back to Mes tâches on success, and the movement status flips to POSTED in the DB (via `cashService.onProcessCompletedPostMovement`).

## Full test scenario

1. Owner creates payment (claimed ≠ assessed + fake PDF).
2. Movement detail shows DRAFT + "Processus lié" + step strip (step 1 PENDING).
3. Mes tâches → "Voir le dossier" → approval desk → Approuver.
4. Movement detail now shows step 1 DONE + step 2 PENDING (strip updates).
5. Mes tâches → "Voir le dossier" → Approuver (step 2).
6. Step 3 (POST) → Approuver → movement POSTED.
7. Movement detail shows POSTED + green "Comptabilisé" + "Annuler" button.
8. Caisse balance reflects the posted amount.
