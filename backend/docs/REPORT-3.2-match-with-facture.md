# REPORT 3.2 — Soft match extended with qty_invoiced (facture)

> **Scope:** Extend the existing match API (Part 2.3) to include `qty_invoiced` from VALIDATED factures.
> **Status:** ✅ Compile OK · ✅ 6/6 E2E PASS · ✅ Read-only (no blocks) · ✅ No new files (extension of PurchaseOrderService only)

---

## 1. What changed

**1 file edited** (`PurchaseOrderService.java` — 6 surgical edits):

| Edit | What |
|---|---|
| 1 | `BcLineMatchRow` record + `qtyInvoiced: BigDecimal` field |
| 2 | `matchOverview` SQL + correlated subquery: `coalesce((select sum(fl.qty) from facture_line fl join facture f on f.id = fl.facture_id where fl.bc_line_id = pol.id and f.status = 'VALIDATED'), 0) as qty_invoiced` |
| 3 | `matchOverview` mapper + reads `qty_invoiced` + passes to `deriveMatchStatus` |
| 4 | `matchForBc` SQL + same subquery |
| 5 | `matchForBc` mapper + same |
| 6 | `deriveMatchStatus` rewritten — now takes 4 quantities, returns 7 statuses |

## 2. Status rules table

| Priority | Status | FR label | Condition |
|---|---|---|---|
| 1 | `MISSING_BL` | Sans réception | `qtyReceived == 0` |
| 2 | `MISSING_FACTURE` | Sans facture | `qtyReceived > 0 && qtyInvoiced == 0` |
| 3 | `OVER_INVOICE` | Excédent facture | `qtyInvoiced > qtyOrdered` (soft alert, no block) |
| 4 | `PARTIAL_RECEIVE` | Réception partielle | `qtyReceived < qtyOrdered` |
| 5 | `PARTIAL_INVOICE` | Facturation partielle | `qtyReceived >= qtyOrdered && qtyInvoiced < qtyOrdered` |
| 6 | `QTY_MISMATCH` | Écart stock | `qtyInStock < qtyReceived` (received but consumed/missing) |
| 7 | `MATCHED` | Conforme | All 4 quantities aligned (tolerance 0 for v1) |

## 3. SQL (the qty_invoiced subquery)

```sql
coalesce(
  (select sum(fl.qty)
   from facture_line fl
   join facture f on f.id = fl.facture_id
   where fl.bc_line_id = pol.id
     and f.org_id = pol.org_id
     and f.status = 'VALIDATED'
  ), 0
) as qty_invoiced
```

Excludes CANCELLED factures (only VALIDATED counted). Single correlated subquery per BC line — no N+1.

## 4. E2E results — 6/6 PASS

| # | Scenario | Result | Evidence |
|---|---|---|---|
| S32 | BC with no BL | ✅ | `MISSING_BL`, qty_invoiced=0 |
| S33 | BC+BL full, no facture | ✅ | `MISSING_FACTURE`, qty_invoiced=0 |
| S34 | Full match (ordered=received=stock=invoiced=100) | ✅ | `MATCHED` |
| S35 | Over-invoice (invoiced=150 > ordered=100) | ✅ | `OVER_INVOICE` |
| S36 | Partial receive (received=30 < ordered=100) | ✅ | `PARTIAL_RECEIVE` |
| S37 | Partial invoice (received=100, invoiced=80 < ordered=100) | ✅ | `PARTIAL_INVOICE` |

## 5. Constraints honored

- **Read-only**: no mutations, no payment hooks, no process changes
- **No N+1**: single SQL per match query (overview or per-BC)
- **Excludes CANCELLED factures**: only `status = 'VALIDATED'` counted
- **No hard blocks**: all statuses are soft (informational only)
