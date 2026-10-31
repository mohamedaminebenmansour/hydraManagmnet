# REPORT-DOC-VIEW — Phase B (Document Preview / Download)

**Date:** 2026-10-31
**Scope:** Make uploaded documents viewable for pilot. PDF + images open in a new tab; everything else downloads with the correct filename; works from document list, document detail, facture / BL / BC / cash-movement linked-doc surfaces.

---

## 1. What was changed

### Backend (1 file)
**`backend/.../document/controller/DocumentController.java`**
- `GET /api/documents/{id}/revisions/{revId}/download` now sets `Content-Disposition` **per mime type**:
  - `inline; filename*=UTF-8''<encoded>` for `application/pdf`, `application/x-pdf`, `image/*`
  - `attachment; filename*=UTF-8''<encoded>` for everything else (xlsx, docx, dwg, msg, ...)
- Filename still comes from `revision.fileName` (project code + doc type + title + rev label), RFC 5987 encoded.
- Auth, endpoint, and storage layer are **unchanged** (`PERM_DOCUMENT_READ` OR `PERM_DOCUMENT_DOWNLOAD`, pluggable `FileStorage` local/S3).
- Added private helper `isInlinePreviewable(String contentType)`.

### Frontend — shared service (1 file)
**`frontend/.../core/services/document.service.ts`**
Added three new public methods + two exported helpers. All **shared** — every screen uses the same code path, so the JSON-error guard and popup-blocked fallback run identically everywhere.

| Member | Purpose |
|---|---|
| `OpenOutcome` (type) | `{kind:'opened'} | {kind:'downloaded';reason?:'popup-blocked'} | {kind:'error';message:string}` |
| `isPreviewable(mime)` | returns `true` for `application/pdf` / `application/x-pdf` / `image/*` |
| `triggerBrowserDownload(url, name)` | synthetic `<a download>` click |
| `toErrorOutcome(err)` | normalizes HTTP/JSON errors into `{kind:'error',message}` with French fallbacks (404 -> "Fichier introuvable.", 403 -> "Accès refusé.", 401 -> "Session expirée, veuillez vous reconnecter.") |
| `openRevision(id, revId, hintName?)` | fetches blob -> **JSON-error guard** -> if previewable `window.open(createObjectURL)` (popup-blocked -> fallback download) -> else `<a download>` |
| `openDocument(docId, hintName?)` | convenience: fetches revisions, takes latest, calls `openRevision` |
| `downloadDocument(docId, hintName?)` | convenience: same flow but forces download regardless of mime |
| `fetchBlobGuarded(id, revId, onBlob)` (private) | shared blob-fetch with the JSON-error-blob sniff: if `blob.type` is `application/json` / `text/json` / empty, reads the text, parses `{code,message}`, returns `{kind:'error',message}` — **never saves a .pdf that is actually `{"code":"DOCUMENT_NOT_FOUND",...}`** |

**Existing `downloadRevision(id, revId)` is unchanged** — backward compatible for any caller that still wants the raw blob.

### Frontend — screens (8 files)

| File | Change |
|---|---|
| `documents.component.ts` | added `openDoc(d)`, `canPreview(d)`, `onOpenOutcome(o)`; tracks latest revision per doc in a signal so the "Ouvrir" button is enabled only when previewable |
| `documents.component.html` | added **"Ouvrir"** button (`pi-external-link`, severity info) next to "Télécharger", `[disabled]` when not previewable |
| `document-detail.component.ts` | added `open(r)`, `canPreview(r)` |
| `document-detail.component.html` | added **"Ouvrir"** button per revision row, `[disabled]` when not previewable |
| `facture-detail.component.ts` | **removed** the static `<a routerLink="/documents/{id}">Voir le justificatif (PDF)</a>`; replaced with two real buttons **"Ouvrir"** + **"Télécharger"** that call `openDocument(fac().documentId)` / `downloadDocument(...)` |
| `movement-detail.component.ts` (cash) | same pattern — replaced the `<a routerLink="['/documents', proofDocumentId()]">` with **"Ouvrir"** + **"Télécharger"** buttons |
| `bl-detail.component.ts` | added a `BL numérisé` block with **Ouvrir / Télécharger** — only rendered when `bl().documentId` is present |
| `bc-detail.component.ts` | added a `BC numérisé` block with **Ouvrir / Télécharger** — only rendered when `bc().documentId` is present |

All toasts are in French. Error path: `"Ouverture impossible: <message>"`. Popup-blocked path: `"Fenêtre bloquée — Le fichier a été téléchargé à la place."` (severity info).

---

## 2. Build verification

```
$ cd /tmp/my-project/frontend && ng build
Output location: /tmp/my-project/frontend/dist/hydra-web
```

- **Errors: 0** ✅
- **Warnings: 6** — all pre-existing, none introduced by this phase:
  - 4x `NG8102` nullish-coalescing on already-typed values (`finance-hub`, `pot-list`, `project-detail`)
  - 1x `NG8103` `*ngIf` used in `process-timeline.component.ts` without `NgIf` import
  - 1x role-class `?? 'VIEWER'` in `project-detail.component.html`
- These warnings were present before Phase B; they do not block the pilot.

Two errors were caught and fixed during the build:
1. `documents.component.ts` — `isPreviewable` was wrongly imported from `document.model.ts`; moved to `document.service.ts`. ✅ fixed
2. `movement-detail.component.ts` — referenced `proofDocumentLabel()` which doesn't exist; corrected to `proofDocumentName()` (the existing method). ✅ fixed

---

## 3. Manual test plan — PASS / FAIL

The four required scenarios. Each must be run with the app served locally (`cd backend && ./mvnw spring-boot:run` + `cd frontend && npm start`), logged in as a user with `PERM_DOCUMENT_READ`.

### Setup
1. Pick a project + folder, upload **3 different files** via the existing Téléverser flow:
   - `plan-chantier.pdf` (a real PDF, e.g. a one-page plan)
   - `photo-chantier.jpg` (a JPEG, any size)
   - `decompte.xlsx` (a real Excel workbook, 2–3 rows is enough)
2. Link the PDF to a facture (via the **"Lier un document"** button on the facture detail) and the JPG to a cash movement (via the cash movement form's document picker).

### TEST 1 — Open a PDF from the document list
| Step | Action | Expected |
|---|---|---|
| 1 | Go to `/projects/{id}/folders/{id}/documents` (document list) | list shows 3 documents; the row for `plan-chantier.pdf` has an **"Ouvrir"** button (icon `pi-external-link`) **enabled** and a **"Télécharger"** button |
| 2 | Click the row's `pi-eye` "Voir" button | navigates to `/documents/{id}` (unchanged behavior — metadata page) |
| 3 | Go back, click **"Ouvrir"** on the PDF row | a **new browser tab** opens and shows the PDF inline |
| 4 | Switch back to the app tab | no error toast |

**Expected HTTP:** `GET /api/documents/{id}/revisions/{revId}/download` -> `200 OK`, `Content-Type: application/pdf`, `Content-Disposition: inline; filename*=UTF-8''<encoded>`.

**PASS / FAIL:** ☐ PASS  ☐ FAIL

### TEST 2 — Open an image from the document detail page
| Step | Action | Expected |
|---|---|---|
| 1 | Click the `pi-eye` "Voir" button on the `photo-chantier.jpg` row | navigates to `/documents/{id}` |
| 2 | In the "Historique des révisions" panel, the row for `Rev.0` shows **two** action buttons: "Ouvrir" (`pi-external-link`, enabled because mime is `image/jpeg`) and "Télécharger" (`pi-download`) |
| 3 | Click **"Ouvrir"** | a **new browser tab** opens and shows the image inline |
| 4 | Switch back to the app tab | no error toast |

**Expected HTTP:** `200 OK`, `Content-Type: image/jpeg`, `Content-Disposition: inline; filename*=UTF-8''<encoded>`.

**PASS / FAIL:** ☐ PASS  ☐ FAIL

### TEST 3 — Download an xlsx (non-previewable)
| Step | Action | Expected |
|---|---|---|
| 1 | On the document list, the row for `decompte.xlsx` has the **"Ouvrir"** button **disabled** (mime `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` is not previewable) |
| 2 | Click **"Télécharger"** on the same row | browser saves the file as `<project-code>__FACTURE__decompte__Rev.0.xlsx` (or similar — exact name is built by `FilenameBuilder`) |
| 3 | Open the downloaded file | opens cleanly in Excel / LibreOffice — no corruption, content matches what was uploaded |

**Expected HTTP:** `200 OK`, `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`, `Content-Disposition: attachment; filename*=UTF-8''<encoded>`.

**PASS / FAIL:** ☐ PASS  ☐ FAIL

### TEST 4 — JSON-error blob guard (no .pdf saved when API errors)
Simulates the case where the API returns 4xx/5xx with a JSON body but the Angular `responseType:'blob'` would normally save that JSON as a `.pdf`.

| Step | Action | Expected |
|---|---|---|
| 1 | Stop the backend (`Ctrl+C` on the Spring process) so the API is unreachable | — |
| 2 | In the running frontend, click **"Ouvrir"** on the PDF row | the new tab does **not** open; a **red toast** appears: `"Ouverture impossible: <message>"` (e.g. `Http failure response … 0 Unknown Error` or `Fichier introuvable.` if the body parses) |
| 3 | Inspect the browser's Downloads folder | **no new file was saved** — the JSON-error guard prevented the `.pdf` from being written |
| 4 | Restart the backend, then in the devtools Network panel block the next download request (or use a non-existent document id) — e.g. navigate directly to `/documents/<bogus-uuid>` and click "Télécharger" | toast: `"Téléchargement impossible: Document introuvable"` (or the API's own message), **no file saved** |

**PASS / FAIL:** ☐ PASS  ☐ FAIL

### Bonus — Popup-blocked fallback (TEST 5)
| Step | Action | Expected |
|---|---|---|
| 1 | In the browser settings, **block pop-ups** for the app origin |
| 2 | Click **"Ouvrir"** on the PDF row | no new tab opens; an **info toast**: `"Fenêtre bloquée — Le fichier a été téléchargé à la place."`; the file is saved with the correct name via `<a download>` |

**PASS / FAIL:** ☐ PASS  ☐ FAIL

### Bonus — Linked-doc surfaces (TEST 6)
| Step | Action | Expected |
|---|---|---|
| 1 | Open the facture detail linked to the PDF | shows a "Justificatif lié" block with **"Ouvrir"** + **"Télécharger"** buttons (no more `<a routerLink>` text link) |
| 2 | Click **"Ouvrir"** | new tab opens with the PDF |
| 3 | Open the cash movement detail linked to the JPG | shows "Voir la pièce justificative" with **"Ouvrir"** + **"Télécharger"** buttons |
| 4 | Click **"Télécharger"** | JPG saved with its real filename |
| 5 | Open a BL with `documentId` set | shows "BL numérisé" + Ouvrir/Télécharger |
| 6 | Open a BL **without** `documentId` | the block is hidden (graceful) |
| 7 | Open a BC with `documentId` set | shows "BC numérisé" + Ouvrir/Télécharger |

**PASS / FAIL:** ☐ PASS  ☐ FAIL

---

## 4. Test results (fill in after manual run)

| # | Test | Result | Notes |
|---|---|---|---|
| 1 | Open PDF from document list | ☐ PASS ☐ FAIL | |
| 2 | Open image from document detail | ☐ PASS ☐ FAIL | |
| 3 | Download xlsx | ☐ PASS ☐ FAIL | |
| 4 | JSON-error guard (no .pdf saved) | ☐ PASS ☐ FAIL | |
| 5 | Popup-blocked fallback | ☐ PASS ☐ FAIL | |
| 6 | Linked-doc surfaces (facture/BL/BC/cash) | ☐ PASS ☐ FAIL | |

**Pilot go / no-go:** all 4 mandatory tests (1–4) must be PASS.

---

## 5. Notes & constraints honored

| Constraint | Status |
|---|---|
| No new storage provider | ✅ unchanged `FileStorage` (local/S3) |
| No Excel in-browser editor | ✅ xlsx always downloads |
| French labels | ✅ "Ouvrir", "Télécharger", error toasts in French |
| Same auth as now | ✅ `PERM_DOCUMENT_READ` / `PERM_DOCUMENT_DOWNLOAD` |
| No backend refactor | ✅ one 5-line change in `DocumentController.download` |
| `ng build` 0 errors | ✅ verified — `dist/hydra-web/browser/` produced |
| BL/BC: only if document linkage field exists | ✅ `BonLivraison.documentId` and `PurchaseOrder.documentId` both exist (migration `V33__bl_lineage_columns.sql` / `V32__purchase_orders_bc.sql`); UI is wired and conditionally rendered |
| Popup blocked -> fallback download + toast | ✅ implemented in `openRevision` |

---

## 6. Files touched

```
backend/src/main/java/tn/hydra/document/controller/DocumentController.java
frontend/src/app/core/services/document.service.ts
frontend/src/app/features/document/documents.component.ts
frontend/src/app/features/document/documents.component.html
frontend/src/app/features/document/document-detail.component.ts
frontend/src/app/features/document/document-detail.component.html
frontend/src/app/features/stock/facture-detail.component.ts
frontend/src/app/features/stock/bl-detail.component.ts
frontend/src/app/features/stock/bc-detail.component.ts
frontend/src/app/features/cash/movement-detail.component.ts
```

10 files. The patch script is idempotent (re-runnable safely): `/home/z/my-project/scripts/doc-view-phase-b.py`.

---

## 7. Known limitations (not blocking pilot)

1. **In-app preview panel** — Phase B opens in a **new tab**, not an in-app `<iframe>`/`<embed>`. This is the simpler, more reliable choice; a preview panel can be added later if the pilot asks for it.
2. **`S3FileStorage.loadAsResource`** returns an `InputStreamResource` (single-shot, no range support). Fine for blob fetch via the API; would not support direct `<iframe src=URL>` for large PDFs — but Phase B always goes through the API, so this is not an issue.
3. **HEIC, DWG, IFC, MSG** — uploaded but not previewable; they fall through to download. Browser support for these is non-existent anyway.
4. **No "current revision id" on facture/BL/BC read models** — the frontend resolves the latest revision by calling `GET /api/documents/{id}/revisions` first, then the download. One extra round-trip per open. Acceptable for pilot; can be optimized later by adding `currentRevId` to the read models.
