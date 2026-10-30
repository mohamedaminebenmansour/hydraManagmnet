# REPORT — Tasks vs Tickets copy clarity

## Before/After strings

| Location | Before | After |
|---|---|---|
| Sidebar label | Mes tâches | **Mes validations** |
| Sidebar label | Tâches / Signalements | **Demandes** |
| MyTasks page title | Mes tâches | **Mes validations** |
| MyTasks empty state | "Aucune tâche en attente" | **"Aucune validation en attente — Les validations de paiement apparaissent ici. Pour les réceptions fournisseur : Achats → Réceptions."** |
| Tickets page title | Tâches / Signalements | **Demandes** |
| Tickets subtitle | "Problèmes et travaux assignés sur les chantiers. Les validations de paiement se traitent dans « Mes tâches »." | **"Signalements et problèmes terrain (hors stock et hors paiement)."** |

## Rationale

1. **"Mes tâches" → "Mes validations"**: eliminates the #1 confusion (two "tâches" in the sidebar). A validation = a payment approval step in a workflow. Not a generic "task".
2. **"Tâches / Signalements" → "Demandes"**: shorter, clearer. A "demande" = a field request (leak, delay, safety). Not a validation.
3. **Empty state on Mes validations**: explains what belongs there (payment validations) and where to find receptions (Achats → Réceptions). An Excel user landing on an empty page now knows where to go.
4. **Tickets subtitle**: clarifies scope — "hors stock et hors paiement" — so users don't confuse Demandes with stock receptions or payment approvals.

## ng build: 0 errors ✅

## Files changed (3, copy only)

| File | Change |
|---|---|
| `shell.component.ts` | 2 sidebar labels renamed |
| `my-tasks.component.ts` | Page title + empty state text |
| `tickets.component.html` | Page title + subtitle |
