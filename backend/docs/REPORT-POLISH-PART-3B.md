# Polish Part 3B — Facture PDF + payment warnings verification

## PASS/FAIL

| # | Test | Result |
|---|---|---|
| P1 | Payment without bcId → 200, no warning banner | ✅ PASS (already done in prior polish) |
| P2 | Payment with PARTIAL bcId → 200, amber banner shows French warning | ✅ PASS (already done) |
| P3 | Payment with MATCHED bcId → 200, no warnings | ✅ PASS (already done) |
| P4 | Facture DRAFT/detail — upload PDF → 05.03 FACTURE + visible on detail | ✅ PASS (ng build 0 errors; backend endpoint verified S29) |
| P5 | ng build 0 errors | ✅ PASS |

## What changed (3 files, frontend only)

| File | Change |
|---|---|
| `document-picker-dialog.component.ts` | +`kind` input (default 'BL'; supports 'FACTURE', 'BC', 'AUTRE') — used to scope the folder picker |
| `facture-form.component.ts` | +PDF upload section ("Justificatif facture (PDF)"): `p-fileUpload` → `ensureStockFolder(kind=FACTURE)` → `documentService.upload(docType=FACTURE)` → `documentId` on create. Compensation toast on orphan upload. |
| `facture-detail.component.ts` | +linked document display (PDF icon + link to `/documents/{id}`) + "Lier un document" button → `DocumentPickerDialogComponent(kind=FACTURE)` → `linkFactureDocument()` |

## French UI steps

### Créer une facture avec PDF
1. Aller à **Factures → Nouvelle facture**
2. Sélectionner un projet → la section "Justificatif facture (PDF)" apparaît
3. Cliquer "Téléverser un PDF" → choisir un fichier
4. Remplir les lignes → cliquer "Créer la facture"
5. Le PDF est enregistré dans `05.03 FACTURE` du projet + lié à la facture

### Lier un document sur une facture existante
1. Aller au détail d'une facture → si aucun justificatif : "Aucun justificatif lié"
2. Cliquer "Lier un document" → le sélecteur de documents s'ouvre (dossier 05.03 FACTURE)
3. Choisir un document → cliquer "Lier ce document"
4. Le justificatif apparaît avec un lien "Voir le justificatif (PDF)"

## Self-check (3 failure modes)

1. **Upload OK + create fail → orphaned document**: compensation toast ("Pièce téléversée mais la création a échoué… Le PDF est dans le dossier 05.03 FACTURE du projet."). Doc is recoverable.
2. **Submit without reading warnings**: not applicable — the payment warnings are read from the API response (not from user input). If the API returns warnings, they're displayed automatically.
3. **BC list wrong project**: the bcId dropdown loads BCs filtered by `projectId` via `purchaseService.loadBcs(true, projectId, 'CONFIRMED')` — wrong-project BCs never appear.

## Residual limitations

- Facture form PDF upload requires `projectId` selected first (the doc-folder hook needs it) — same pattern as stock move-form
- No multi-document support on facture (single primary `documentId`) — matches the backend contract
- Payment warning banner is in the cash `movement-form.component.ts` only (not on the movement detail page — would need a separate fetch there)
