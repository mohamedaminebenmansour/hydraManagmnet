# HYDRA — FINAL REPORT PART 4 (persist commercial links + warnings on read + BC close + clarity)

> **Scope:** Parts 4.1 (persist bc_id/facture_id) + 4.2 (material warnings on detail) + 4.3 (BC close) + 4.4 (UI clarity + E2E)
> **Status:** ✅ Backend compile OK · ✅ ng build 0 errors · ✅ 12/12 E2E PASS across all slices

---

## 1. PASS/FAIL matrix

| # | Test | Result |
|---|---|---|
| **4.1 — Persist commercial links** | | |
| S42 | Submit with bcId → bc_id stored + returned | ✅ |
| S43 | Cross-project bcId → PAYMENT_BC_PROJECT_MISMATCH | ✅ |
| S44 | Submit without bcId → null, works | ✅ |
| S45 | Detail returns bcId + bcReference | ✅ |
| **4.2 — Warnings on read** | | |
| S46 | Submit with PARTIAL bcId → 1 warning (MISSING_BL) | ✅ |
| S47 | GET detail with bcId → materialWarnings non-empty | ✅ |
| S48 | GET detail without bcId → 0 warnings, no panel | ✅ |
| **4.3 — BC close** | | |
| C1 | MATCHED → CLOSED + audit | ✅ |
| C2 | MISSING_BL → BC_NOT_FULLY_MATCHED | ✅ |
| C3 | DRAFT → BC_INVALID_STATUS | ✅ |
| C4 | IDOR → FORBIDDEN | ✅ |
| C5 | ng build 0 errors | ✅ |
| **4.4 — UI clarity** | | |
| — | BL list/form: "Réceptions fournisseur" | ✅ |
| — | TRANSFER: "Transfert / sortie dépôt" | ✅ |
| — | Facture form: BC hint "liez BC/BL fournisseur — pas les transferts internes" | ✅ |
| — | Stock moves history: "Via BL" badge when bl_line_id set | ✅ |
| — | ng build 0 errors | ✅ |

**Total: 12/12 PASS + ng build 0 errors**

---

## 2. What changed (Part 4 total)

### Backend
- `V35__cash_commercial_links.sql` — +bc_id +facture_id on cash_movement
- `CashService.java` — INSERT/SELECT/IDOR for commercial links; +materialWarnings on detail
- `PaymentFacade.java` — delegates to PurchaseOrderService.computeMaterialWarnings (DRY)
- `PurchaseOrderService.java` — +computeMaterialWarnings (public, never throws) + close()
- `PurchaseOrderController.java` — +POST /{id}/close

### Frontend
- `movement-detail.component.ts` — amber material warnings panel + links
- `movement-form.component.ts` — bcId dropdown + submit warnings banner (from prior polish)
- `bc-detail.component.ts` — "Clôturer la commande" button + confirm dialog
- `bl-list/bl-form.component.ts` — "Réceptions fournisseur" copy
- `move-form.component.ts` — "Transfert / sortie dépôt" label
- `stock-moves.component.ts` — "Via BL" badge
- `facture-form.component.ts` — BC hint
- `purchase.service.ts` — +closeBc method
- `cash.model.ts` — +bcId +warnings on SubmitPaymentBody/Response

---

## 3. Demo script (7 minutes)

1. **0:00** — Login as owner → **Commandes** → create BC (Ciment 50 sac) → **Confirmer**
2. **0:45** — **Réceptions fournisseur** → create BL (50 sac) → **Poster** → stock IN fires
3. **1:30** — **Factures** → create facture (50 sac, link BC) → **Valider**
4. **2:00** — **Rapprochement** → see MATCHED (green: ordered=50, received=50, stock=50, invoiced=50)
5. **2:30** — BC detail → **Clôturer la commande** → confirm → status=CLOSED (purple chip)
6. **3:00** — **Trésorerie** → submit payment with bcId → 200, warnings empty (all matched)
7. **3:30** — Open movement detail → no amber panel (warnings empty)
8. **4:00** — Create new BC (no BL) → submit payment with bcId → 200, amber banner: "aucune réception (BL) enregistrée"
9. **4:30** — Open that movement detail → amber panel shows warning + "Voir commande" + "Rapprochement" links
10. **5:00** — **Stock** → **Historique** → see "Via BL" badge on BL-sourced IN moves (cyan chip)
11. **5:30** — Create a TRANSFER (central → site) → no facture messaging → works without any commercial link
12. **6:00** — Done

---

## 4. Limitations

| Limitation | When |
|---|---|
| `bcId` not persisted on cash_movement before Part 4.1 (old payments have null) | Only affects pre-4.1 data — new payments store it |
| `require-stock-match` flag read at startup | Acceptable for v1 |
| No auto-close BC (manual only) | By design — prevents surprising side effects |
| `factureId` persisted but not used in UI yet | Future: link facture on payment form |
| Payment warning banner only on submit form + detail page | Not on list page (no N+1) |

---

## 5. Part 5 ideas

1. **Bordereau de livraison interne** — separate from supplier BL; for internal transfers between dépôt and chantier with signature
2. **Sous-traitant module** — ST contracts, retention garantie, décompte ST
3. **TVA / RAS compliance** — Tunisian tax rules on facture amounts (TVA 19%/7%/13%, RAS 1.5%/3%)
4. **Multi-org** — support multiple companies in one deployment
5. **Mobile/PWA** — offline BL reception for site chefs without 4G
6. **CSV import** — bulk BC/facture import for initial data migration
7. **Dashboard** — overdue payments, stock value at cost, supplier spend analytics

---

**End of Part 4.**
