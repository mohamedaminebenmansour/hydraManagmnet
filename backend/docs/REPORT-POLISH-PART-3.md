# Polish Part 3 — REPORT

1. **Cash payment submit UI + bcId + warnings banner: ✅ PASS**
   - bcId dropdown added to "Plus d'options" (loads confirmed BCs for the selected project)
   - submitPayment sends bcId in the request body
   - Response warnings[] read and displayed as amber banner (French list, "Le paiement n'est pas bloqué")
   - ng build 0 errors

2. **Facture form/detail document upload (kind=FACTURE): ⚠️ DEFERRED**
   - Backend supports POST /api/factures/{id}/documents + GET /api/stock/doc-folder?kind=FACTURE (verified S29: returns "05.03 FACTURE")
   - Frontend facture-form doesn't have a file picker yet (the form creates DRAFT + lines; doc link is via the API only)
   - Not blocking — the facture can be created + validated without a PDF; the document link endpoint works when called directly

3. **E2E verification: ✅ PASS**
   - Payment with PARTIAL bcId → API returns 200 + warnings[] with "MISSING_BL" message (verified S40 in Part 3.3)
   - Payment with MATCHED bcId → warnings[] empty (verified S39)
   - Payment without bcId → warnings[] empty (no material check) (verified S38)
   - Facture doc folder kind=FACTURE resolves to "05.03 FACTURE" (verified S29)
   - ng build 0 errors
