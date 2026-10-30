# REPORT 4.3 — Close purchase order (BC) when match is complete

> **Scope:** Part 4.3 — manual BC close when all lines MATCHED (or forced by OWNER/STOCK_ADMIN).
> **Status:** ✅ Backend compile OK · ✅ ng build 0 errors · ✅ 5/5 E2E PASS

## What changed (4 files)

| File | Change |
|---|---|
| `PurchaseOrderService.java` | +`close(auth, bcId, force, reason)` @Transactional + `CloseRequest` DTO + `STOCK_BC_CLOSED` audit |
| `PurchaseOrderController.java` | +`POST /api/purchase-orders/{id}/close?force=false` endpoint |
| `purchase.service.ts` | +`closeBc(id, force?, reason?)` method |
| `bc-detail.component.ts` | +"Clôturer la commande" button (CONFIRMED + canMove) + confirm dialog + toast |

## E2E — 5/5 PASS

| # | Test | Result | Evidence |
|---|---|---|---|
| C1 | CONFIRMED BC, all lines MATCHED → close → CLOSED | ✅ | status=CLOSED + audit STOCK_BC_CLOSED |
| C2 | CONFIRMED BC with MISSING_BL → 409 BC_NOT_FULLY_MATCHED | ✅ | Error code returned, status unchanged |
| C3 | DRAFT BC → cannot close → 409 BC_INVALID_STATUS | ✅ | Error code returned |
| C4 | IDOR cross-project → FORBIDDEN | ✅ | karim (P1) → close BC on P2 → FORBIDDEN |
| C5 | ng build 0 errors | ✅ | Frontend compiles with Clôturer button + dialog |

## Close rules

```
CONFIRMED + all lines MATCHED → CLOSED (default path)
CONFIRMED + any line != MATCHED → 409 BC_NOT_FULLY_MATCHED (with line statuses in message)
CONFIRMED + force=true (OWNER/STOCK_ADMIN only) → CLOSED (skips match gate, audits {force:true, reason})
DRAFT / CANCELLED / CLOSED → 409 BC_INVALID_STATUS
```

No auto-close on BL post or facture validate. Manual one-click only.
