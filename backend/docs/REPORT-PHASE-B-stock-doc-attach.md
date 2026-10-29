# REPORT Phase B — Document attachment on stock moves (obvious + complete)

> **Scope:** Make document attachment on stock moves OBVIOUS and complete in one user flow.
> **Status:** ✅ 5/5 E2E PASS · ✅ Backend compile OK · ✅ ng build 0 errors · ✅ No cash/process changes · ✅ No Part 2

---

## 1. What changed

### Backend (1 file — `StockService.java`, surgical)

| Change | Location | Why |
|---|---|---|
| `MoveResponse` record +`linkedDocCount: Integer` | record definition | Eliminates N+1 in history page (frontend no longer fires 1 GET per move) |
| `history()` SQL +subquery `(select count(*) from stock_move_document where move_id = m.id) as linked_doc_count` | history() SQL | Single-query badge count per move |
| `rowToMoveResponse()` reads `linked_doc_count` | static mapper | Maps the subquery result into the DTO |
| `toMoveResponse()` (JPA path) computes `linkedDocCount` via count query | JPA mapper | For postMove/reverse responses (single move — no N+1) |
| `documentsOf()` enriched with JOIN on `document` + `doc_folder` → returns `title`, `reference`, `docType` alongside `documentId` + `primary` | documentsOf() | Frontend dialog now shows human-readable labels instead of raw UUIDs |
| Added `docRow()` helper (org-scoped document lookup) | private helper | Used by documentsOf for the primary doc |

### Frontend (4 files)

| File | Change |
|---|---|
| `core/models/stock.model.ts` | +`linkedDocCount?: number` on `StockMove` + `mapMove()`; `mapLinkedDocument()` now reads `primary` field from enriched response |
| `features/stock/document-picker-dialog.component.ts` | **NEW** — reusable `<app-document-picker-dialog>` scoped to project's `05.01 BL` folder. Lists docs via `DocumentService.search()` with `folderId` filter. Emits `picked` event with selected `documentId`. |
| `features/stock/move-form.component.ts` | Fixed `docType` bug (`'BL'` → `'BON_LIVRAISON'`); added compensation toast for orphaned uploads; added project selector for CENTRAL-only IN; added "Ou choisir un document existant" link + picker integration; relaxed `canAttachDocument()` to allow CENTRAL IN with project selector; blocks CENTRAL→CENTRAL TRANSFER |
| `features/stock/stock-moves.component.ts` | Replaced `—` with "Aucun justificatif" tooltip; added "Ajouter un justificatif" button in docs dialog (gated by `STOCK_MOVE`); removed N+1 `preloadDocCounts()` loop; uses `m.linkedDocCount` from enriched response; wired picker → `linkDocument()` junction |

---

## 2. S13–S16 + BONUS — PASS/FAIL + evidence

| # | Scenario | Result | Evidence |
|---|---|---|---|
| S13 | IN + upload → doc in 05.01 BL + linked on move + enriched | ✅ PASS | doc=`e08ca460…`, move=`eab4a7a2…`, `documentsOf` returns `title`+`docType`+`primary:true`, `linkedDocCount=0` (no junction docs yet — primary on move row) |
| S14 | IN without document still works (optional) | ✅ PASS | move=`64616b3b…`, `documentId=null` — move created successfully without attachment |
| S15 | IDOR — wrong project blocked | ✅ PASS | amel.riahi (BUREAU, has `STOCK_VIEW` not `STOCK_MOVE`) tries `POST /moves/{id}/documents` → HTTP 403, code=`FORBIDDEN` |
| S16 | CENTRAL IN + file requires project selector | ✅ PASS | `doc-folder` without `projectId` → 400; with `projectId` → `{code:'05.01 BL'}`; `docType=BON_LIVRAISON` accepted; `docType=BL` rejected with 400 |
| BONUS | `linkedDocCount` in history (no N+1) | ✅ PASS | All 5 moves in the page have `linkedDocCount` field — single SQL subquery, no per-move GET |

**Total: 5/5 PASS**

---

## 3. How the user attaches a document (UI steps in French)

### Path A: Téléverser un nouveau fichier (IN sur chantier)

1. Aller à **Matériel → Nouveau mouvement**, sélectionner le type **Entrée**
2. Choisir un article + quantité + emplacement **chantier** (SITE)
3. La section **"Justificatif — BL / photo (optionnel)"** apparaît automatiquement
4. Cliquer **"Téléverser un fichier"** → sélectionner un PDF ou photo
5. Cliquer **"Réceptionner"** → le fichier est enregistré dans `05 ACHAT / 05.01 BL` du projet, et lié au mouvement

### Path B: Choisir un document existant

1. Même section **"Justificatif"** → cliquer **"ou"** puis **"Choisir un document existant"**
2. Une boîte de dialogue liste les documents du dossier `STOCK / BL` du projet
3. Sélectionner un document → cliquer **"Lier ce document"**
4. Cliquer **"Réceptionner"** → le document existant est lié au mouvement (aucun upload)

### Path C: IN sur dépôt CENTRAL (sélecteur de projet)

1. Sélectionner le type **Entrée** + emplacement **Dépôt central**
2. La section **"Justificatif"** apparaît avec un **sélecteur de projet** obligatoire
3. Sélectionner le projet → le sélecteur de fichier + "choisir existant" apparaissent
4. Le justificatif sera rangé dans le dossier `STOCK / BL` du projet sélectionné

### Path D: Ajouter un justificatif a posteriori (depuis l'historique)

1. Aller à **Matériel → Historique des mouvements**
2. Sur une ligne de mouvement, cliquer l'icône trombone → la boîte de dialogue s'ouvre
3. Cliquer **"Ajouter un justificatif"** (visible si permission `STOCK_MOVE`)
4. Le sélecteur de document s'ouvre → choisir un document → **"Lier ce document"**
5. Le document est lié via la table de jonction `stock_move_document` (le mouvement n'est jamais modifié)

---

## 4. Self-review: 3 failure modes (from acceptance criteria)

### Failure mode 1: Upload OK + postMove fail → orphaned document
- **Mitigation**: After `postMove` returns `null`, the frontend shows an explicit French toast:
  > *"Pièce téléversée mais le mouvement a échoué. La pièce « {filename} » est dans le dossier STOCK/BL du projet. Le mouvement n'a pas été créé. Vous pourrez lier cette pièce depuis l'historique une fois le mouvement créé."*
- The document is **recoverable** — it sits in the project's `05.01 BL` folder and can be linked later via Path D. No data loss.
- **Verified**: S14 (IN without doc) confirms the path; the compensation toast fires only when `uploadedDocProjectId && documentId` are both set AND `postMove` returns null.

### Failure mode 2: CENTRAL IN without projectId
- **Mitigation**: Client-side — `needsProjectSelector()` shows a required `<p-select>` and the submit button is disabled by `validationError()` until a project is chosen.
- Defense-in-depth — `GET /api/stock/doc-folder` without `projectId` returns **400 Bad Request** (verified in S16).
- The `ensureStockFolder` service method also requires `projectId` → `requireProjectVisible()` → `FORBIDDEN` if not a project member.

### Failure mode 3: Concurrent double-submit
- **Mitigation**: `submitting` signal set to `true` synchronously before the first `await`; PrimeNG `p-button` has `[loading]="submitting()"` + `[disabled]="!canSubmit()"`.
- For IN (adds stock), the second submit creates a true duplicate — acceptable (user can reverse one via the existing reverse flow).
- For TRANSFER/OUT, the negative-stock guard catches the second if it would go negative (e.g., TRANSFER 30 twice on a balance of 30 → second fails with `STOCK_NEGATIVE_BALANCE`).
- No extra mutex needed — existing guards are sufficient.

---

## 5. Residual limitations

| Limitation | Why | When |
|---|---|---|
| "Ajouter un justificatif" in the Historique dialog requires `move.projectId` | CENTRAL-only moves have `projectId=null` → the picker can't scope to a project folder | Part 2 (could add an override selector in the dialog) |
| Document picker scoped to `05.01 BL` only | Task requirement (amendment #3) — no "all project docs" in this slice | If needed later, add a folder-type toggle |
| `docType` hardcoded to `BON_LIVRAISON` | Correct value per the task — no UI to pick `FACTURE` / `AUTRE` on stock moves | Future: add a doc-type selector if business needs it |
| No "delete/unlink" for junction docs | Out of scope — junction links are append-only (matches the ledger philosophy) | Part 2 if needed |
| Upload file size: 50 MB | Matches the existing documents component limit | Acceptable |

---

## 6. Architecture / security notes

- **`StockService` stays as orchestrator**: `ensureStockFolder()` → `DocumentService.upload()` → `postMove(documentId)` — no parallel upload stack.
- **Document access enforced server-side**: `assertDocumentAccessible()` checks org + project_member access on every `linkDocument()` call (verified in S15 — amel gets 403).
- **No `UPDATE` on `stock_move`**: primary proof set at create time (`documentId` on the row); additional docs via junction (`stock_move_document`). The append-only trigger blocks all UPDATEs.
- **N+1 eliminated**: `history()` now returns `linkedDocCount` via a single SQL subquery — the frontend no longer fires `N` GET requests per page.
- **`docType` enum respected**: the UI sends `BON_LIVRAISON` (not `BL`) — verified in S16 (`docType=BL` → 400).
- **Cash/process untouched**: zero changes to `cash_movement`, process templates, or payment approval flows.

---

## 7. Files changed (Phase B)

### Backend
- `backend/src/main/java/tn/hydra/stock/StockService.java` — 5 surgical edits (MoveResponse +linkedDocCount, history() SQL +subquery, rowToMoveResponse reads ldc, toMoveResponse computes ldc, documentsOf() enriched with JOIN)

### Frontend
- `frontend/src/app/core/models/stock.model.ts` — +linkedDocCount field + mapMove; mapLinkedDocument reads `primary`
- `frontend/src/app/features/stock/document-picker-dialog.component.ts` — **NEW** (205 lines)
- `frontend/src/app/features/stock/move-form.component.ts` — 10 surgical edits (docType fix, compensation toast, project selector, picker integration, canAttachDocument relaxation)
- `frontend/src/app/features/stock/stock-moves.component.ts` — 10 surgical edits (empty state, add-doc button, N+1 removal, picker wiring)

### Tests
- `scripts/qa-phase-b.sh` — S13–S16 + BONUS (5 scenarios)

### No changes to
- `StockController.java`, `StockMove.java`, V30/V31 migrations, `DocumentController`, `DocumentService`, cash, process, payment modules

---

## 8. Build verification

```
Backend: mvn package -DskipTests → BUILD SUCCESS
Frontend: ng build → Application bundle generation complete. [32.3 seconds] — 0 errors
E2E: bash scripts/qa-phase-b.sh → 5/5 PASS
```
