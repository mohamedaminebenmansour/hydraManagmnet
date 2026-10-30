# REPORT 4.2 — Material warnings on movement detail (read path)

> **Scope:** Part 4.2 — surface material-proof warnings when viewing a payment, without re-submitting.
> **Status:** ✅ Compile OK · ✅ ng build 0 errors · ✅ 3/3 E2E PASS

## What changed

| File | Change |
|---|---|
| `PurchaseOrderService.java` | +`public List<String> computeMaterialWarnings(auth, bcId)` — extracted from PaymentFacade, never throws, French messages |
| `PaymentFacade.java` | Submit path now delegates to `purchaseOrders.computeMaterialWarnings()` (DRY) |
| `CashService.java` | +inject PurchaseOrderService; `MovementDetailResponse` +`materialWarnings: List<String>`; `getMovementDetail()` calls `computeMaterialWarnings` when `bc_id` set; `listMovementsDetail` returns empty list (no N+1) |
| `movement-detail.component.ts` | +amber panel with French warnings + "Voir commande" + "Rapprochement" links |

## E2E — 3/3 PASS

| # | Test | Result | Evidence |
|---|---|---|---|
| S46 | Submit with PARTIAL bcId → warnings on submit | ✅ | 1 material warning (MISSING_BL) |
| S47 | GET detail with bcId → materialWarnings non-empty | ✅ | 1 warning: "Ligne « Ciment 25kg E2E » : aucune réception (BL) enregistrée" |
| S48 | GET detail without bcId → empty warnings | ✅ | 0 warnings (no panel shown) |

## Design

- `computeMaterialWarnings` is on `PurchaseOrderService` (shared by submit + read paths)
- Never throws — `matchForBc` failures are caught + logged → empty list
- Only the detail endpoint computes warnings (single BC, no N+1)
- List endpoint returns empty `materialWarnings` (no per-row match call)
- Frontend shows amber panel only when `materialWarnings.length > 0`
