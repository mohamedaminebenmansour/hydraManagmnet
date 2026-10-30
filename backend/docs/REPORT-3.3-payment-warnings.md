# REPORT 3.3 — Soft payment warnings (material proof)

> **Scope:** Part 3.3 ONLY — advisory warnings when paying without material proof.
> **Status:** ✅ Compile OK · ✅ 4/4 E2E PASS · ✅ Default non-blocking · ✅ No schema change · ✅ Existing payment E2E unbroken

---

## 1. What changed (3 files, additive only)

| File | Change |
|---|---|
| `PaymentFacade.java` | +`bcId` on `SubmitPaymentRequest`; +`List<String> warnings` on `SubmitPaymentResponse` (backward-compat constructor); inject `PurchaseOrderService`; +`computePaymentWarnings()` helper; `@Value` flag |
| `CashService.java` | +`bcId` on `CreateMovementRequest` (pass-through, NOT persisted) |
| `application.properties` | +`hydra.payments.require-stock-match=${HYDRA_PAYMENTS_REQUIRE_STOCK_MATCH:false}` |

## 2. Warning logic

```
if bcId == null → skip material checks (no warning)
if bcId present → call matchForBc(auth, bcId)
  for each BC line where status != MATCHED:
    MISSING_BL       → "Ligne « {item} » : aucune réception (BL) enregistrée."
    MISSING_FACTURE  → "Ligne « {item} » : reçu sans facture validée."
    OVER_INVOICE     → "Ligne « {item} » : facturation supérieure à la commande."
    PARTIAL_RECEIVE  → "Ligne « {item} » : réception partielle ({received}/{ordered})."
    PARTIAL_INVOICE  → "Ligne « {item} » : facturation partielle ({invoiced}/{ordered})."
    QTY_MISMATCH     → "Ligne « {item} » : écart stock ({stock}/{received})."

if requireStockMatch=true AND materialWarnings non-empty → 409 STOCK_MATCH_REQUIRED
else → 200 + warnings array in response
```

## 3. E2E results — 4/4 PASS

| # | Scenario | Result | Evidence |
|---|---|---|---|
| S38 | Payment without bcId | ✅ | HTTP 200, warnings=0 (no material check) |
| S39 | Payment with MATCHED bcId | ✅ | HTTP 200, material_warnings=0 (all MATCHED) |
| S40 | Payment with PARTIAL bcId (no BL) | ✅ | HTTP 200, material_warnings=1 (MISSING_BL) |
| S41 | Flag default false | ✅ | Payment 200, warnings field present in response |

## 4. Regression note

Existing payment E2E from Part 1/2: `submit-payment` endpoint returns an extra `warnings` field (List<String>, defaults to empty list via backward-compat constructor). Old callers that don't read `warnings` are unaffected. No existing test broken — the response shape is additive.

## 5. Limitations

- `bcId` is NOT persisted on `cash_movement` (no schema change in 3.3) — it's used only for in-memory warning computation. To recompute warnings on the movement detail page later, add `bc_id` column in a future migration.
- The `require-stock-match` flag is read at startup (Spring `@Value`) — toggling requires a restart. Acceptable for v1.
- `matchForBc` failures are logged + swallowed (never block the payment).
