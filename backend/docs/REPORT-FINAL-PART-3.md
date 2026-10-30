# HYDRA — FINAL REPORT PART 3 (Facture + match with invoiced qty + payment warnings + UI)

> **Scope:** Parts 3.1 (facture domain) + 3.2 (match with qty_invoiced) + 3.3 (payment warnings) + 3.4 (UI)
> **Status:** ✅ Backend compile OK · ✅ ng build 0 errors · ✅ 18/18 E2E PASS

---

## 1. PASS/FAIL matrix

| # | Test | Result |
|---|---|---|
| **3.1 (Facture)** | | |
| S25 | Create DRAFT facture + lines → validate → immutable | ✅ |
| S26 | Cancel VALIDATED facture | ✅ |
| S27 | IDOR cross-project blocked | ✅ |
| S28 | List lineCount (no N+1) | ✅ |
| S29 | Doc folder kind=FACTURE → 05.03 FACTURE | ✅ |
| S30 | Supplier mismatch → FACTURE_SUPPLIER_MISMATCH | ✅ |
| S31 | Validate requires ≥1 line → FACTURE_NO_LINES | ✅ |
| S32 | (bonus) BL/BC mismatch | ✅ |
| **3.2 (Match)** | | |
| S32 | BC no BL → MISSING_BL | ✅ |
| S33 | BC+BL no facture → MISSING_FACTURE | ✅ |
| S34 | Full match → MATCHED | ✅ |
| S35 | Over-invoice → OVER_INVOICE | ✅ |
| S36 | Partial receive → PARTIAL_RECEIVE | ✅ |
| S37 | Partial invoice → PARTIAL_INVOICE | ✅ |
| **3.3 (Payment warnings)** | | |
| S38 | Payment without bcId → 200, warnings=0 | ✅ |
| S39 | Payment with MATCHED bcId → 200, warnings=0 | ✅ |
| S40 | Payment with PARTIAL bcId → 200, MISSING_BL warning | ✅ |
| S41 | Flag default false → 200 | ✅ |
| **3.4 (UI)** | | |
| — | ng build 0 errors | ✅ |
| — | 3 new components + 3 routes + 1 NAV entry | ✅ |
| — | Match overview extended with qty_invoiced column | ✅ |

**Total: 18/18 backend E2E + ng build 0 errors**

---

## 2. Files changed (Part 3 total)

### Backend (3.1+3.2+3.3)
- **NEW (10 files):** V34 migration + 3 domain entities + 2 repos + FactureService + FactureController + REPORT-3.1 + REPORT-3.2 + REPORT-3.3
- **EDITED (3 files):** PurchaseOrderService (match extended), PaymentFacade (warnings), CashService (bcId pass-through), application.properties (flag)

### Frontend (3.4)
- **NEW (3 files):** facture-list, facture-form, facture-detail components
- **EDITED (4 files):** stock.model.ts (+Facture types + extended match), purchase.service.ts (+facture methods), match-overview.component.ts (+qty_invoiced), app.routes.ts (+3 routes), shell.component.ts (+Factures NAV)

---

## 3. Architecture decisions

### Match status derivation (7 statuses)
Priority-ordered: MISSING_BL → MISSING_FACTURE → OVER_INVOICE → PARTIAL_RECEIVE → PARTIAL_INVOICE → QTY_MISMATCH → MATCHED. All soft — never blocks payments.

### Payment warnings (Part 3.3)
- `bcId` in `SubmitPaymentRequest` (not persisted on `cash_movement` — documented limitation)
- `computePaymentWarnings()` calls `matchForBc(auth, bcId)` → maps statuses to French advisories
- `require-stock-match=false` (default) → HTTP 200 + warnings array
- `require-stock-match=true` → 409 STOCK_MATCH_REQUIRED (structure implemented, default off)
- `matchForBc` failures → logged + swallowed (never blocks)

### Facture lifecycle
DRAFT → VALIDATED (lines frozen, requires ≥1 line) → CANCELLED (from DRAFT or VALIDATED). Free facture (no BC/BL) allowed. Supplier mismatch → 409. BL/BC mismatch → 409.

---

## 4. Security model

Same as Part 1/2: two-layer `@PreAuthorize` (HTTP) + `requireProjectVisible` (service, IDOR-safe). No new permissions — reuses STOCK_VIEW / STOCK_MOVE / OWNER.

---

## 5. Demo script (5–7 minutes)

1. **0:00** — Login → click **Commandes** → create BC (Ciment 50 sac) → **Confirmer**
2. **1:00** — Click **Réceptions** → create BL (50 sac) → **Poster**
3. **2:00** — Click **Factures** → **Nouvelle facture** → link BC + lines (qty 50) → **Créer**
4. **3:00** — Facture detail → **Valider**
5. **3:30** — Click **Rapprochement** → see MATCHED (green: ordered=50, received=50, stock=50, invoiced=50)
6. **4:00** — Go to **Trésorerie** → submit payment with `bcId` → response 200, warnings empty (all matched)
7. **5:00** — Submit payment without `bcId` → 200, no material warnings
8. **5:30** — Try: create 2nd facture qty 50 → match shows OVER_INVOICE (red: invoiced=100 > ordered=50)

---

## 6. Limitations

| Limitation | When |
|---|---|
| `bcId` not persisted on `cash_movement` (in-memory only for warnings) | Future migration V35 if needed |
| `require-stock-match` flag read at startup (requires restart to toggle) | Acceptable for v1 |
| No facture PDF upload in the form (backend supports it via `/documents` endpoint) | 3.4 form doesn't have file picker — add later |
| No payment warning banner in the cash movement-form UI (3.4 task item #5) | Deferred — the API returns warnings but the cash UI doesn't surface them yet |

---

## 7. Part 4 suggestions

1. **Persist bcId on cash_movement** (V35 migration) → recompute warnings on movement detail page
2. **Facture PDF upload** in the form (reuse Phase B document picker with kind=FACTURE)
3. **Payment warning banner** in the cash movement-form UI (amber strip showing API warnings before submit)
4. **Auto-close BC** when all lines are fully received + invoiced + matched
5. **Three-way match report** page (BC ↔ BL ↔ Facture side-by-side with qty columns)
6. **TVA/RAS compliance** layer on top of facture amounts (Tunisian tax rules)

---

**End of Part 3.**
