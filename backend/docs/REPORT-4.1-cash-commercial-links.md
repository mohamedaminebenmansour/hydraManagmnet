# REPORT 4.1 — Persist commercial links (bc_id + facture_id) on cash_movement

> **Scope:** Part 4.1 ONLY — schema + write path for bc_id/facture_id on cash_movement.
> **Status:** ✅ Compile OK · ✅ V35 applied · ✅ 4/4 E2E PASS

## What changed

| File | Change |
|---|---|
| `V35__cash_commercial_links.sql` (NEW) | +bc_id +facture_id nullable columns + 2 partial indexes + comments |
| `CashService.java` | CreateMovementRequest +factureId; MovementResponse +bcId +factureId; MovementDetailResponse +bcId +bcReference +factureId +factureReference; INSERT +2 cols; fetchMovementForRead +2 cols; toMovementResponse +2 fields; toMovementDetailResponse +4 fields; getMovementDetail SELECT +JOINs; listMovementsDetail SELECT +JOINs; +validateCommercialLink IDOR helper |
| `PaymentFacade.java` | SubmitPaymentRequest +factureId; draftReq passes req.factureId() |

## E2E — 4/4 PASS

| # | Test | Result | Evidence |
|---|---|---|---|
| S42 | Submit with bcId → bc_id stored + returned | ✅ | bcId in response = BC id; DB bc_id IS NOT NULL = t |
| S43 | Cross-project bcId → 409 | ✅ | PAYMENT_BC_PROJECT_MISMATCH |
| S44 | Submit without bcId → null, works | ✅ | bcId=None, movement created |
| S45 | Detail returns bcId + bcReference | ✅ | bcId present in MovementResponse (simple) + MovementDetailResponse (with JOINs) |

## IDOR validation

`validateCommercialLink(linkId, orgId, movementProjectId, tableName, errorCode)`:
- Queries `select project_id from {tableName} where id = ? and org_id = ?`
- If movement has a project AND the link has a project AND they differ → 409
- Org isolation via `org_id = ?` filter (same as all other cash queries)
