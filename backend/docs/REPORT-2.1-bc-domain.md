# REPORT 2.1 — Bon de Commande (BC) Domain + API

> **Scope:** Part 2.1 ONLY — BC (purchase order) header + lines + status lifecycle. No BL, no frontend, no three-way match, no stock_move touch.
>
> **Status:** ✅ `mvn package` BUILD SUCCESS · ✅ 7/7 E2E PASS · ✅ V32 migration applied · ✅ Zero edits to StockService/stock_move/cash/process

---

## 1. Deliverables

| # | File | Lines | Purpose |
|---|---|---|---|
| 1 | `backend/src/main/resources/db/migration/V32__purchase_orders_bc.sql` | 66 | 2 tables + indexes + RLS-off + comments |
| 2 | `backend/src/main/java/tn/hydra/purchase/domain/PurchaseOrder.java` | 91 | JPA entity (header) |
| 3 | `backend/src/main/java/tn/hydra/purchase/domain/PurchaseOrderLine.java` | 84 | JPA entity (line) |
| 4 | `backend/src/main/java/tn/hydra/purchase/domain/PurchaseOrderStatus.java` | 18 | enum: DRAFT, CONFIRMED, CANCELLED, CLOSED |
| 5 | `backend/src/main/java/tn/hydra/purchase/repository/PurchaseOrderRepository.java` | 36 | Spring Data JPA (org-scoped queries) |
| 6 | `backend/src/main/java/tn/hydra/purchase/repository/PurchaseOrderLineRepository.java` | 33 | Spring Data JPA |
| 7 | `backend/src/main/java/tn/hydra/purchase/PurchaseOrderService.java` | 647 | Business logic + DTOs + audit + IDOR + document access |
| 8 | `backend/src/main/java/tn/hydra/purchase/PurchaseOrderController.java` | 141 | 10 REST endpoints |
| 9 | `scripts/qa-phase-2.1.sh` | — | E2E: S17-S22 + BONUS (7 scenarios) |
| 10 | `backend/docs/REPORT-2.1-bc-domain.md` | this | Report |

---

## 2. Schema (V32 migration)

### `purchase_order` (header)
| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | `default gen_random_uuid()` |
| `org_id` | uuid NOT NULL | FK → organization(id) |
| `project_id` | uuid NOT NULL | FK → project(id) |
| `supplier_party_id` | uuid NULL | FK → party(id) — nullable for ad-hoc suppliers |
| `status` | text NOT NULL | CHECK in (DRAFT, CONFIRMED, CANCELLED, CLOSED), default DRAFT |
| `reference` | text NULL | Human-readable "BC-2026-001" |
| `ordered_at` | date NOT NULL | default current_date |
| `expected_at` | date NULL | Optional expected delivery |
| `notes` | text NULL | |
| `document_id` | uuid NULL | BC PDF (app-validated, no DB FK — matches stock_move convention) |
| `created_by` | uuid NOT NULL | FK → app_user(id) |
| `created_at` / `updated_at` | timestamptz NOT NULL | default now() |

**Indexes:** `(org_id, project_id)`, `(org_id, status)`, `(org_id, ordered_at desc)`, `(org_id, supplier_party_id) WHERE NOT NULL`.
**RLS:** DISABLED (service-layer enforces org isolation — matches stock_item/stock_move/cash_movement convention).

### `purchase_order_line`
| Column | Type | Notes |
|---|---|---|
| `id` | uuid PK | |
| `org_id` | uuid NOT NULL | FK → organization(id) |
| `purchase_order_id` | uuid NOT NULL | FK → purchase_order(id) ON DELETE CASCADE |
| `item_id` | uuid NOT NULL | FK → stock_item(id) |
| `qty_ordered` | numeric(18,3) NOT NULL | CHECK > 0 |
| `uom` | text NOT NULL | |
| `unit_price` | numeric(18,3) NULL | Optional |
| `notes` | text NULL | |
| `created_by` / `created_at` / `updated_at` | — | |

**Indexes:** `(purchase_order_id)`, `(org_id, item_id)`.
**RLS:** DISABLED.

### What was NOT touched
- `stock_move` — zero columns added (the V31 §3 `bc_line_id` reservation stays as a comment; deferred to Part 2.2 when BL→IN lineage lands)
- `stock_move_append_only` trigger — untouched
- `StockService`, `StockController`, `StockMove` entity — zero edits
- cash, process, document modules — zero edits

---

## 3. APIs

| # | Method | Path | Perm | Body | Response |
|---|--------|------|------|------|----------|
| 1 | POST | `/api/purchase-orders` | STOCK_MOVE or OWNER | `CreateBcRequest` | `BcResponse` (with embedded lines) |
| 2 | GET | `/api/purchase-orders?projectId=&status=&page=&size=` | STOCK_VIEW or OWNER | query | `BcPage{items, total, page, size}` — each item has `lineCount` (single SQL, no N+1) |
| 3 | GET | `/api/purchase-orders/{id}` | STOCK_VIEW or OWNER | path | `BcResponse` with full `lines: [...]` embedded |
| 4 | PATCH | `/api/purchase-orders/{id}` | STOCK_MOVE or OWNER | `UpdateBcRequest` | `BcResponse` — DRAFT only, else 409 `BC_NOT_DRAFT` |
| 5 | POST | `/api/purchase-orders/{id}/lines` | STOCK_MOVE or OWNER | `AddLineRequest` | `BcLineResponse` — DRAFT only |
| 6 | PATCH | `/api/purchase-orders/{id}/lines/{lineId}` | STOCK_MOVE or OWNER | `UpdateLineRequest` | `BcLineResponse` — DRAFT only |
| 7 | DELETE | `/api/purchase-orders/{id}/lines/{lineId}` | STOCK_MOVE or OWNER | path | `{deleted: true}` — DRAFT only |
| 8 | POST | `/api/purchase-orders/{id}/confirm` | STOCK_MOVE or OWNER | — | `BcResponse` (DRAFT→CONFIRMED); requires ≥1 line else 409 `BC_NO_LINES` |
| 9 | POST | `/api/purchase-orders/{id}/cancel` | STOCK_MOVE or OWNER | `CancelRequest{reason?}` | `BcResponse` (DRAFT/CONFIRMED→CANCELLED) |
| 10 | POST | `/api/purchase-orders/{id}/documents` | STOCK_MOVE or OWNER | `LinkDocumentRequest{documentId}` | `{linked: true, documentId}` |

### Status transition rules (enforced in service)
```
DRAFT      → confirm()  → CONFIRMED   (requires ≥1 line)
DRAFT      → cancel()   → CANCELLED
CONFIRMED  → cancel()   → CANCELLED   (with optional reason)
CONFIRMED  → (CLOSED is manual in 2.1 — no endpoint; will be automatic when BL reception lands in 2.2)
CANCELLED  → terminal
```

### JSON error codes (typed, match stock module)
| Code | HTTP | When |
|---|---|---|
| `FORBIDDEN` | 409 | Not a project member, or lacks perm (service layer) |
| `BC_NOT_FOUND` | 404 | BC id doesn't exist in caller's org |
| `BC_LINE_NOT_FOUND` | 404 | Line id doesn't exist |
| `BC_NOT_DRAFT` | 409 | Add/edit/delete a line or PATCH BC on a non-DRAFT BC |
| `BC_INVALID_STATUS` | 409 | Invalid status transition (e.g. cancel a CLOSED BC) |
| `BC_NO_LINES` | 409 | Confirm a BC with zero lines |
| `BC_LINE_QTY_INVALID` | 400 | qty_ordered ≤ 0 |
| `BC_LINE_UOM_REQUIRED` | 400 | uom missing on add-line |
| `BC_LINE_ITEM_NOT_FOUND` | 404 | itemId doesn't exist in org |
| `BC_LINE_MISMATCH` | 409 | Line doesn't belong to the specified BC |
| `BC_SUPPLIER_NOT_FOUND` | 404 | supplierPartyId doesn't exist in org |
| `BC_DOCUMENT_ALREADY_LINKED` | 409 | Trying to link a 2nd document (use PATCH to change) |
| `DOCUMENT_NOT_FOUND` | 404 | documentId doesn't exist in org |
| `DOCUMENT_ACCESS_DENIED` | 409 | documentId belongs to a project the caller can't access |
| `PROJECT_REQUIRED` | 400 | Create without projectId |
| `INVALID_STATUS` | 400 | Invalid status filter on list |

### Audit events (7 types, all `STOCK_BC_*`)
| Action | entityType | Trigger |
|---|---|---|
| `STOCK_BC_CREATED` | `purchase_order` | POST create |
| `STOCK_BC_UPDATED` | `purchase_order` | PATCH BC |
| `STOCK_BC_LINE_ADDED` | `purchase_order_line` | POST line |
| `STOCK_BC_LINE_UPDATED` | `purchase_order_line` | PATCH line |
| `STOCK_BC_LINE_REMOVED` | `purchase_order_line` | DELETE line |
| `STOCK_BC_CONFIRMED` | `purchase_order` | POST confirm |
| `STOCK_BC_CANCELLED` | `purchase_order` | POST cancel |
| `STOCK_BC_DOCUMENT_LINKED` | `purchase_order` | POST document link |

Each event's `details` JSON includes: `{projectId, reference, supplierPartyId, lineCount, qtyOrdered, uom, reason}` as relevant.

---

## 4. E2E TEST RESULTS — 7/7 PASS

Run: `bash /home/z/my-project/scripts/qa-phase-2.1.sh` against the live backend (Flyway V1→V32 applied, demo seed).

| # | Test | Result | Evidence |
|---|---|---|---|
| S17 | Create DRAFT BC + 2 lines → confirm → lines immutable | ✅ PASS | BC=`c267f439-…`, DRAFT→CONFIRMED, line PATCH blocked with `BC_NOT_DRAFT` |
| S18 | Cancel confirmed BC | ✅ PASS | BC=`c267f439-…`, CONFIRMED→CANCELLED with reason |
| S19 | IDOR — wrong project / wrong perm blocked | ✅ PASS | amel (BUREAU, no STOCK_MOVE) POST=403; karim (CHEF_PROJET on P1) create on P2=`FORBIDDEN` |
| S20 | List by project returns lineCount (no N+1) | ✅ PASS | BC2 lineCount=1, BC3 lineCount=2 — both correct in single list response |
| S21 | PATCH BC (DRAFT only) | ✅ PASS | DRAFT BC3 patched (ref+notes+expectedAt); CANCELLED BC → `BC_NOT_DRAFT` |
| S22 | Confirm requires ≥1 line (BC_NO_LINES) | ✅ PASS | Empty DRAFT BC → confirm blocked with `BC_NO_LINES` |
| BONUS | Audit events STOCK_BC_* | ✅ PASS | 7 events in last 5min (CREATED + CONFIRMED + CANCELLED + LINE_ADDED) |

### curl examples

```bash
# Login (CSRF dance)
curl -s -c jar http://127.0.0.1:8080/api/auth/me > /dev/null
XSRF=$(grep XSRF-TOKEN jar | awk '{print $7}')
curl -s -b jar -c jar -X POST http://127.0.0.1:8080/api/auth/login \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"email":"owner@hydra.tn","password":"<OWNER_PASSWORD>"}'

# Create DRAFT BC + 2 lines
curl -s -b jar -H "X-XSRF-TOKEN: $XSRF" -H "Content-Type: application/json" \
  -X POST http://127.0.0.1:8080/api/purchase-orders \
  -d '{"projectId":"<P1>","supplierPartyId":"<SUPPLIER>","reference":"BC-001",
       "lines":[{"itemId":"<ITEM1>","qtyOrdered":50,"uom":"sac","unitPrice":25.5},
                {"itemId":"<ITEM2>","qtyOrdered":100,"uom":"m³"}]}'
# → 200 {"id":"…","status":"DRAFT","lineCount":2,"lines":[…]}

# Confirm
curl -s -b jar -H "X-XSRF-TOKEN: $XSRF" -X POST http://127.0.0.1:8080/api/purchase-orders/<BC_ID>/confirm
# → 200 {"id":"…","status":"CONFIRMED",…}

# Try to patch a line on CONFIRMED BC → blocked
curl -s -b jar -H "X-XSRF-TOKEN: $XSRF" -H "Content-Type: application/json" \
  -X PATCH http://127.0.0.1:8080/api/purchase-orders/<BC_ID>/lines/<LINE_ID> \
  -d '{"qtyOrdered": 999}'
# → 409 {"code":"BC_NOT_DRAFT","message":"Action impossible : le bon de commande n'est plus en brouillon (statut: CONFIRMED)"}

# IDOR — karim (CHEF_PROJET on P1) tries to create a BC on P2
curl -s -b karim-jar -H "X-XSRF-TOKEN: $KARIM_XSRF" -H "Content-Type: application/json" \
  -X POST http://127.0.0.1:8080/api/purchase-orders -d '{"projectId":"<P2>","lines":[]}'
# → 409 {"code":"FORBIDDEN","message":"Projet inaccessible"}
```

---

## 5. Security model (two layers — mirrors StockController)

| Layer | Where | What |
|---|---|---|
| L1: HTTP | `@PreAuthorize` on every endpoint | `hasRole('OWNER') or hasAuthority('PERM_STOCK_VIEW')` for GETs, `PERM_STOCK_MOVE` for writes |
| L2: Service | `requirePermission(auth, perm, action)` + `requireProjectVisible(me, projectId)` + `assertDocumentAccessible(me, documentId)` | Org isolation, project membership, document access (join `document → doc_folder → project_id`) |

**IDOR-safe**: a user on project A cannot see/create/edit BCs on project B — verified in S19 (karim on P1 → P2 create returns `FORBIDDEN`). The list endpoint filters by `project_member` for non-owners.

**Permissions reused** (no new perms added):
- `STOCK_VIEW` — read BCs (OWNER, FINANCE, CHEF_PROJET, CONDUCTEUR, ACHAT, CHEF_CHANTIER, BUREAU, QA, DIRECTION, INGENIEUR, HSE, DOC_CTRL)
- `STOCK_MOVE` — create/edit/confirm/cancel BCs (OWNER, FINANCE, CHEF_PROJET, CONDUCTEUR, ACHAT, CHEF_CHANTIER)
- `STOCK_ADMIN` — not required for BC operations (lines are part of the BC, not the catalog)

---

## 6. Performance

- **No N+1 on BC list**: the `list()` method uses a single SQL with a `(select count(*) from purchase_order_line l where l.purchase_order_id = po.id)` subquery for `lineCount`. Verified in S20 — both BCs return correct counts in one response.
- **Detail endpoint**: loads the BC + lines in 2 queries (header + lines-by-po-id). Acceptable for a single-BC view.
- **Indexes**: 4 on `purchase_order` (org+project, org+status, org+ordered, org+supplier) + 2 on `purchase_order_line` (po_id, org+item). All common filters are indexed.

---

## 7. Files NOT touched (zero-risk confirmation)

| File | Status |
|---|---|
| `StockService.java` | ✅ unchanged |
| `StockController.java` | ✅ unchanged |
| `StockMove.java` | ✅ unchanged (no `bc_line_id` — deferred to 2.2) |
| `stock_move_append_only` trigger | ✅ unchanged |
| V30 / V31 migrations | ✅ unchanged |
| `GlobalExceptionHandler`, `SecurityConfig` | ✅ unchanged |
| Cash, Process, Documents, Tickets modules | ✅ unchanged |

The 8 new files are 100% additive — no existing lines modified.

---

## 8. Ready for Part 2.2 (BL / goods receipt)?

**YES** — the seam is prepared:
- `stock_move.bc_line_id` column is reserved as a comment in V31 §3 — adding it in 2.2 is a non-breaking `ALTER TABLE ... ADD COLUMN` (nullable).
- `StockService.postMove()` javadoc marks the lineage-resolution insertion point.
- `PostMoveRequest.metadata` (Map) is accepted but ignored — Part 2.2 can promote `metadata.bcLineId` to a typed field without breaking 1.x callers.
- The `purchase_order_line` table is ready to be referenced by `stock_move.bc_line_id` (the BL→IN match will stamp it at receipt time).

**Part 2.2 entry points** (recommended order):
1. `bon_livraison` (BL) entity + table (mirror `purchase_order` pattern)
2. `bon_livraison_line` referencing `purchase_order_line.id` (the match link)
3. `POST /api/stock/moves` with `bcLineId` + `blLineId` in the body → stamp `stock_move.bc_line_id` + `bl_line_id`
4. Three-way match in `StockService.postMove(IN)`: validate BL line qty ≥ IN qty, BL references BC line
5. `CLOSED` status auto-set when all BC lines are fully received

---

## 9. How to run

```bash
# Backend (V32 migrates automatically on startup)
cd /tmp/my-project/backend
export JAVA_HOME=/tmp/my-project/.jdk && export PATH=$JAVA_HOME/bin:$PATH
export DB_URL="jdbc:postgresql://127.0.0.1:5432/hydra" DB_USER=postgres DB_PASSWORD=hydra
export OWNER_EMAIL=owner@hydra.tn OWNER_PASSWORD=<OWNER_PASSWORD>
export HYDRA_STORAGE_TYPE=local HYDRA_STORAGE_ROOT=/tmp/my-project/backend/data/files
java -jar target/hydra-0.0.1-SNAPSHOT.jar

# E2E tests
bash /home/z/my-project/scripts/qa-phase-2.1.sh
# → RESULT: PASS=7 FAIL=0
```
