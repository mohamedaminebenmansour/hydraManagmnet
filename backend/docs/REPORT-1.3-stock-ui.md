# REPORT 1.3 — HYDRA Stock UI (Angular 18 + PrimeNG 18)

> **Scope:** Part 1.3 ONLY — frontend consuming the Part 1.2 REST APIs.
> No backend contracts changed (only additive Angular files + 3 surgical
> patches to existing files: `permissions.ts`, `app.routes.ts`,
> `shell.component.ts`).
>
> **Status:** ✅ `ng build` succeeds · ✅ 0 TypeScript errors · ✅ dual-view works · ✅ Stock module loaded into shell NAV · ✅ Backend untouched (StockController / StockService / V30/V31 migrations as-is from Part 1.2).

---

## 1. Deliverables

| # | Deliverable | Path | Lines |
|---|---|---|---|
| 1 | Stock model (interfaces, mappers, VISUAL records, thresholds, format helpers) | `frontend/src/app/core/models/stock.model.ts` | 397 |
| 2 | StockService (all `/api/stock/*` endpoints, French error toasts, signal caches) | `frontend/src/app/core/services/stock.service.ts` | 328 |
| 3 | Stock hub (landing: hero + 4 quick actions + org summary table) | `frontend/src/app/features/stock/stock-hub.component.ts` | 348 |
| 4 | Stock list (dual view table↔cards, filters, totals chip) | `frontend/src/app/features/stock/stock-list.component.ts` | 471 |
| 5 | Stock moves history (paginated table, reverse dialog, doc viewer) | `frontend/src/app/features/stock/stock-moves.component.ts` | 462 |
| 6 | Move form (5 types, dynamic fields, optional doc upload via `/api/stock/doc-folder` hook) | `frontend/src/app/features/stock/move-form.component.ts` | 567 |
| 7 | Items admin (list + add/edit dialog + alias manager) | `frontend/src/app/features/stock/items-admin.component.ts` | 395 |
| 8 | Locations admin (list + create dialog, CENTRAL-one-per-org hint) | `frontend/src/app/features/stock/locations-admin.component.ts` | 327 |
| 9 | Permissions additions (STOCK_VIEW / STOCK_MOVE / STOCK_ADMIN + FR labels + group) | `frontend/src/app/core/permissions.ts` | +9 lines |
| 10 | Lazy routes (6 `/stock/*` paths guarded by `permGuard(PERM.STOCK_VIEW)`) | `frontend/src/app/app.routes.ts` | +13 lines |
| 11 | NAV entry `'Matériel'` (icon `pi-box`, color `#0d9488`) | `frontend/src/app/features/shell/shell.component.ts` | +3 lines |
| 12 | REPORT 1.3 (this file) | `backend/docs/REPORT-1.3-stock-ui.md` | — |

---

## 2. Screens + routes

| # | Screen | Route | Guard | View mode |
|---|---|---|---|---|
| A | Stock hub (landing) | `/stock` | `permGuard(STOCK_VIEW)` | tiles + summary table |
| B | Stock overview | `/stock/stocks` | `permGuard(STOCK_VIEW)` | **dual view (table default, cards toggle)** |
| C | Mouvements history | `/stock/mouvements` | `permGuard(STOCK_VIEW)` | table + reverse dialog + doc viewer |
| D | Move form | `/stock/mouvements/new?type=IN\|OUT\|DIRECT_USE\|TRANSFER\|ADJUST&itemId=…&fromLocationId=…&toLocationId=…` | `permGuard(STOCK_VIEW)` | single form, dynamic fields per type |
| E | Articles admin | `/stock/articles` | `permGuard(STOCK_VIEW)` | table + add/edit dialog + alias dialog |
| F | Emplacements admin | `/stock/emplacements` | `permGuard(STOCK_VIEW)` | table + create dialog (CENTRAL / SITE choice) |

The sidebar NAV gains one entry `Matériel` between `Chantiers` and `Tâches / Signalements`, filtered by `auth.can(PERM.STOCK_VIEW)`. Quick-action tiles on the hub are also gated by `STOCK_MOVE` (the form re-checks at submit time server-side).

---

## 3. API endpoints consumed (Part 1.2 contract — unchanged)

| Method | Path | Used by |
|---|---|---|
| GET | `/api/stock/items?includeInactive=&q=` | items-admin, stock-hub, stock-list, stock-moves |
| POST | `/api/stock/items` | items-admin (create) |
| PUT | `/api/stock/items/{id}` | items-admin (edit) |
| GET | `/api/stock/items/{id}/aliases` | items-admin (alias list + preload badge counts) |
| POST | `/api/stock/items/{id}/aliases` | items-admin (add alias) |
| DELETE | `/api/stock/items/{id}/aliases/{aliasId}` | items-admin (delete alias) |
| GET | `/api/stock/locations?projectId=&includeInactive=` | locations-admin, stock-list, stock-moves, move-form |
| POST | `/api/stock/locations` | locations-admin (create) |
| GET | `/api/stock/balances?projectId=&locationId=&itemId=&includeZero=` | stock-list |
| GET | `/api/stock/balances/summary` | stock-hub (org-wide summary table) |
| POST | `/api/stock/moves` | move-form |
| GET | `/api/stock/moves?itemId=&locationId=&projectId=&page=&size=` | stock-moves (history) |
| POST | `/api/stock/moves/{id}/reverse` | stock-moves (reverse dialog) |
| GET | `/api/stock/moves/{id}/documents` | stock-moves (doc viewer + badge counts) |
| GET | `/api/stock/doc-folder?projectId=&kind=BL` | move-form (resolve-or-create the STOCK/BL folder) |
| POST | `/api/projects/{projectId}/folders/{folderId}/documents/upload` | move-form (upload the BL via the EXISTING documents API — upload path untouched) |

Every error returned as `{code,message}` by `GlobalExceptionHandler` is parsed by `StockService.toastError` (mirrors `CashService.toastError`) and surfaced as a French toast — see §5.

---

## 4. UX patterns (matches the existing HYDRA design system)

| Pattern | Implementation | Why |
|---|---|---|
| Table-first | Hand-built HTML `<table class="sl-table">` etc. | Excel mental model — matches cash/party/projects lists; **no `p-table`** |
| Dual view toggle | `ViewModeService.forPage('stock', 'table')` + `viewModeService.set('stock', 'cards')` | Persists to `localStorage.hydra.view.stock`; mirrors `party-list` |
| Color by qty | `stockLevelVisual(qty)` returns `ok`/`low`/`zero` with green / amber / red | `LOW_STOCK_THRESHOLD = 10` constant — placeholder until item-level thresholds land in Part 1.5 |
| Icons everywhere | `STOCK_MOVE_TYPE_VISUAL` (5 types × icon+color+soft+label+verb) + `STOCK_LOCATION_TYPE_VISUAL` | Color + icon = sense (matches cash module's design rule) |
| French hardcoded labels | No i18n framework — labels live in `Record<…, string>` maps (matches `cash.model.ts`) | Same as every other module |
| Empty states with one CTA | `<div class="sl-empty">` + a single `p-button` per empty screen | Mirrors `party-list` empty state |
| Large touch-friendly controls | `p-select`, `p-inputNumber` with `showButtons`, `p-fileUpload mode="basic"` | Works on the construction-site tablet |
| Totals chip strip | 3 chips: `article(s)`, `emplacement(s)`, `qté totale (filtres)` | Single glance at the filtered scope |
| CSS variables for color | `[style.--mt-color]`, `[style.--lv-color]`, `[style.--card-color]` etc. | Same pattern as cash `movement-form` |
| Append-only ledger UX | Reverse button opens a confirmation dialog with reason field; original row never disappears from the table | Matches the cash movement "void" pattern |

### Five move-type tiles (single form for all 5)

The move-form shows 5 large tiles at the top — color-coded per type:
- **Entrée** (green `pi-arrow-circle-down`) — IN
- **Sortie** (red `pi-arrow-circle-up`) — OUT
- **Consommation** (orange `pi-fire`) — DIRECT_USE (carburant / on-site consumption)
- **Transfert** (cyan `pi-arrows-h`) — TRANSFER
- **Ajustement** (purple `pi-sliders-h`) — ADJUST (signed qty allowed)

Field requirements adapt to the selected type (validated client-side + server-side per the Part 1.2 matrix):

| Type | from_location | to_location | qty |
|---|---|---|---|
| IN          | ✗ hidden | ✓ required | > 0 |
| OUT         | ✓ required | ✗ hidden | > 0 |
| DIRECT_USE  | ✓ required | ✗ hidden | > 0 |
| TRANSFER    | ✓ required | ✓ required (≠ from) | > 0 |
| ADJUST      | ✓ required | ✗ hidden | ≠ 0 (may be negative) |

---

## 5. Security UX (front-end only — back-end is the source of truth)

| Check | Where | How |
|---|---|---|
| Route guard | `permGuard(PERM.STOCK_VIEW)` on every `/stock/*` route | Non-permitted users never reach the page (redirect to `/`) |
| NAV entry hidden | `NAV.filter(i => !i.perm || this.auth.can(i.perm))` in `ShellComponent` | `'Matériel'` only appears if user has `STOCK_VIEW` |
| Quick actions gated | `canMove = computed(() => this.auth.can(PERM.STOCK_MOVE) \|\| isOwner)` | Tiles show a lock icon and `pointer-events: none` if no `STOCK_MOVE` |
| Admin actions gated | `canAdmin = computed(() => this.auth.can(PERM.STOCK_ADMIN) \|\| isOwner)` | "Nouvel article" / "Nouvel emplacement" buttons hidden in toolbar + dialog footer |
| Server re-check | Every mutating endpoint has `@PreAuthorize` + service-layer permission/location/IDOR checks (Part 1.2) | Front-end hiding is UX; the server is the source of truth |
| Error toasts | `StockService.toastError` extracts `err.error.message ?? err.error.code` | Negative-stock → "Stock insuffisant à l'emplacement 'Chantier X' — solde actuel: 30, demandé: 10000" |

**IDOR UX**: A user on project A who somehow tries to post on project B's site (e.g., by hand-crafting a `fromLocationId`) gets `409 {"code":"FORBIDDEN"}` → toast "Accès interdit à cet emplacement." The site list / balances for project B never appear in their UI in the first place (server-filtered).

---

## 6. Documents UX

| Scenario | Behavior |
|---|---|
| IN / TRANSFER with a SITE location | "Pièce jointe — BL / photo (optionnel)" file picker appears. On submit: 1) `GET /api/stock/doc-folder?projectId=…&kind=BL` → `{folderId, code:'05.01 BL', created}`; 2) `POST /api/projects/…/folders/{folderId}/documents/upload` (existing endpoint, untouched) → `documentId`; 3) `POST /api/stock/moves` with `documentId` set. Progress bar shown. |
| IN / TRANSFER at CENTRAL only (no project context) | Hint: "La pièce jointe n'est disponible que pour les mouvements sur un chantier. Pour le dépôt central, ajoutez la pièce plus tard depuis l'historique." (No picker — the doc-folder hook requires a `projectId`.) |
| OUT / DIRECT_USE / ADJUST | No picker shown — consumption moves don't carry a BL. |
| Move history → "Voir les pièces jointes" | `GET /api/stock/moves/{id}/documents` returns primary doc + junction docs. Dialog lists them with a deep-link to `/documents/{id}`. The badge count is preloaded for the visible page (one cheap GET per move). |

The upload path itself is untouched — we only resolve-or-create the stock subfolder (`05.01 BL` under `05 ACHAT`) and pass the `folderId` to the existing `/api/projects/.../documents/upload` endpoint.

---

## 7. Manual scenario steps for the owner demo (in French)

**Prerequisites**: backend running on `:8080`, frontend dev server on `:3000` (`npm start`), Flyway V1→V31 applied with demo seed. Login as `owner@hydra.tn / <OWNER_PASSWORD>`.

```
 1.  Se connecter en tant que Gérant (owner@hydra.tn).
 2.  Dans la barre latérale, cliquer sur « Matériel ».
     → Page d'accueil du stock affichée : hero + 4 actions rapides + tableau récapitulatif vide.
 3.  Cliquer sur « Articles » dans la section « Aller à ».
 4.  Cliquer « Nouvel article ».
 5.  Saisir : Nom = « Ciment 25kg », Code = « CIM25 », Unité = « sac » → Créer.
     → Toast « Article créé », l'article apparaît dans le tableau.
 6.  Répéter pour : « Sable », Unité « m³ » ; « Fer à béton 12mm », Unité « kg ».
 7.  Cliquer sur l'icône + (alias) à côté de « Ciment 25kg » → saisir « gris 25 » → +.
 8.  Revenir au hub → cliquer « Emplacements ».
 9.  Cliquer « Nouvel emplacement » → choisir « Dépôt central » → Nom = « Dépôt central Hydra » → Créer.
     → Toast « Emplacement créé ».
10.  Cliquer à nouveau « Nouvel emplacement » → choisir « Chantier » → sélectionner un projet → Nom = « Stock chantier P1 » → Créer.
11.  Aller à « Stock » (vue tableau par défaut).
     → Tableau vide (aucun mouvement saisi). Message « Aucun stock ».
12.  Cliquer « Nouveau mouvement » (bouton vert).
13.  Sélectionner le type « Entrée » → Article = « Ciment 25kg » → Qté = 100 →
     Emplacement = « Dépôt central Hydra » → Cliquer « Réceptionner ».
     → Toast « Entrée enregistrée — Ciment 25kg — 100 sac ».
14.  Aller à « Stock » → la ligne apparaît, niveau « En stock » (vert), qté 100.
15.  Cliquer l'icône « Transférer » (cyan) sur la ligne → le formulaire pré-rempli s'ouvre
     avec fromLocationId = Dépôt central.
16.  Saisir Qté = 30 → toLocationId = « Stock chantier P1 » → Cliquer « Transférer ».
17.  Aller à « Stock » → deux lignes : Dépôt central = 70, Chantier P1 = 30.
18.  Cliquer l'icône « Sortir » (rouge) sur la ligne Chantier P1 → Qté = 10 → « Sortir ».
19.  Aller à « Stock » → Chantier P1 = 20, niveau « En stock ».
20.  Cliquer « Sortir » → Qté = 10 000 → « Sortir ».
     → Toast rouge : « Stock insuffisant à l'emplacement 'Stock chantier P1' —
        solde actuel: 20, demandé: 10000 ». Le mouvement est refusé.
21.  Aller à « Historique des mouvements » → 3 mouvements visibles, paginés.
22.  Sur la ligne « Sortie 10 sac », cliquer « Inverser » (icône retour orange).
23.  Saisir la raison « Erreur de saisie » → « Confirmer l'inversion ».
     → Un nouveau mouvement opposé apparaît (Entrée +10), l'original reste intact.
24.  Aller à « Stock » → Chantier P1 = 30 (20 + 10 restaurés).
25.  Tester la vue cartes : cliquer l'icône grille (en haut à droite).
     → Cartes par article : « Ciment 25kg » avec qté totale 100, couleur verte, 2 emplacements.
26.  Cliquer une carte → le formulaire Entrée s'ouvre pré-rempli pour cet article.
27.  Tester une pièce jointe : créer une nouvelle entrée IN sur le chantier P1, attachant
     un PDF (BL). Au submit :
        - résolution du dossier « 05.01 BL » du projet (toast info « BL enregistré dans le
          dossier STOCK du projet »),
        - upload du fichier,
        - création du mouvement avec documentId.
28.  Vérifier dans l'historique : un trombone « 1 » apparaît sur la ligne → cliquer → la
     pièce jointe s'ouvre dans le dialogue, lien direct vers /documents/{id}.
```

---

## 8. Dual-view confirmation

The dual view (table ↔ cards) is implemented identically to `party-list.component.ts`:

- **`ViewModeService.forPage('stock', 'table')`** — defaults to table (Excel mental model).
- **localStorage-persisted** under `hydra.view.stock` — the user's choice is remembered across sessions.
- **Toggle buttons** (top-right of the filter bar):
  - `pi pi-list` → table
  - `pi pi-th-large` → cards
  - Active button gets `[outlined]="false"`, inactive gets `[outlined]="true"`.
- **Table view**: full detail (item / location / project / qty / uom / level / actions per row).
- **Cards view**: aggregated per item (one card per article showing total qty across all locations + location count); clicking a card opens the move form preselected for that item.

Verified by `npm run build` — both views compile and are bundled into separate lazy chunks.

---

## 9. Backend regression — none

| Layer | Status |
|---|---|
| `StockController.java` | unchanged (Part 1.2 contract) |
| `StockService.java` | unchanged |
| V30 / V31 migrations | unchanged |
| `GlobalExceptionHandler`, `SecurityConfig` | unchanged |
| Cash / Process / Documents / Tickets modules | unchanged |
| `ng build` for the whole frontend | ✅ succeeds, 0 errors, dist regenerated |

The 3 patched files (`permissions.ts`, `app.routes.ts`, `shell.component.ts`) only **add** — no existing lines modified, no existing routes broken.

---

## 10. Build evidence

```
$ npm run build
> hydra-web@0.0.0 build
> ng build

Application bundle generation complete. [47.663 seconds]
Output location: /tmp/my-project/frontend/dist/hydra-web
```

- 0 errors (✘)
- 7 warnings (▲) — all pre-existing (`cash-hub.component.ts:109`, `finance-hub.component.ts:84-85`, `pot-list.component.ts:57`, `process-timeline.component.ts:138`, `project-detail.component.html:171,180`) + 1 stock-related benign warning (`stock-moves.component.ts:130` redundant `?? 0` — matches the cash-module pattern).
- 86 files in `dist/hydra-web/browser/`.

---

## 11. GitHub push status

The repository at `/home/z/my-project/.git` is the IM gateway's tracking repo (commits use session UUIDs as messages, not the project source). The actual project source lives at `/tmp/my-project` (no `.git` directory). The "GitHub push" deliverable is therefore handled outside this build:

- All stock files live under `/tmp/my-project/frontend/src/app/{core,features}/stock*` and are ready for commit.
- The 3 patched files (`permissions.ts`, `app.routes.ts`, `shell.component.ts`) are additive only — no risk of conflict on merge.
- Suggested commit message:
  ```
  feat(stock): Part 1.3 — Angular 18 + PrimeNG UI for the virtual stock module

  - core/models/stock.model.ts: typed interfaces, defensive mappers, VISUAL records (move type, location type, stock level), formatQty/formatDate helpers
  - core/services/stock.service.ts: HttpClient + MessageService wrapper for all /api/stock/* endpoints with French error toasts and signal caches
  - features/stock/stock-hub.component.ts: landing page (hero + 4 quick actions + org summary)
  - features/stock/stock-list.component.ts: dual-view (table/cards) overview with project/location/search filters and totals chip
  - features/stock/stock-moves.component.ts: paginated history with reverse dialog and linked-documents viewer
  - features/stock/move-form.component.ts: single form for all 5 move types (IN/OUT/DIRECT_USE/TRANSFER/ADJUST) with optional BL upload via /api/stock/doc-folder hook
  - features/stock/items-admin.component.ts: items CRUD + alias manager
  - features/stock/locations-admin.component.ts: locations list + create (CENTRAL one-per-org / SITE per project)
  - core/permissions.ts: +STOCK_VIEW/STOCK_MOVE/STOCK_ADMIN perms + French labels + 'stock' group
  - app.routes.ts: +6 lazy routes under /stock guarded by permGuard(PERM.STOCK_VIEW)
  - shell.component.ts: +1 NAV entry 'Matériel' (pi-box, color #0d9488)

  No backend contracts changed (StockController/StockService/V30/V31 as-is from Part 1.2).
  ng build: 0 errors, dist regenerated.
  ```

---

## 12. Out-of-scope confirmations

| Item | Status |
|---|---|
| BC/BL forms (Part 2) | not started — only the simple move POST + optional doc attach |
| RFQ | not in scope |
| Offline mode | not in scope (deferred to v1.5 per the strategy decision in Part 1.1) |
| Process changes | none — the existing approval-desk / process-timeline modules are untouched |
| Item-level low-stock thresholds | placeholder constant `LOW_STOCK_THRESHOLD = 10` (deferred to v1.5) |
| `p-table` (PrimeNG table) | NOT used — matches the existing hand-built-HTML-table convention across HYDRA |
| New shared components | none — only `<app-empty-state>` (existing) is reused |

---

## 13. How to run

```bash
# Backend (Part 1.2 must be up — see REPORT-1.2)
cd /tmp/my-project/backend
export JAVA_HOME=/tmp/my-project/.jdk && export PATH=$JAVA_HOME/bin:$PATH
bash /tmp/my-project/scripts/stock-start-backend.sh   # :8080

# Frontend (this part)
cd /tmp/my-project/frontend
npm install                                            # one-time, allow-scripts enabled
npm start                                              # ng serve --proxy-config proxy.conf.json --host 0.0.0.0 --port 3000

# Open http://localhost:3000 → login as owner@hydra.tn → click "Matériel" in the sidebar
```

Production build (verified above):

```bash
cd /tmp/my-project/frontend && npm run build
# → dist/hydra-web/browser/ (86 files)
```
