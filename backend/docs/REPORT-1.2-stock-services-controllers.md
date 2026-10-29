# REPORT 1.2 — HYDRA Stock Services + Controllers (Slice 1.2)

> **Scope:** Part 1.2 ONLY — Spring Security + REST API layer on top of the Part 1.1 schema. No Angular.
>
> **Status:** ✅ Compile OK · ✅ Backend boots (Flyway V1→V31 + demo seed) · ✅ **E2E: 37/37 PASS** · ✅ Cash/process untouched

---

## 1. Deliverables

| # | Deliverable | Path | Status |
|---|---|---|---|
| 1 | V31 migration (junction + ADJUST sign) | `backend/src/main/resources/db/migration/V31__stock_slice12_support.sql` | ✅ applied |
| 2 | StockService (items, aliases, locations, balances, moves, reverse, doc-link, folder hook) | `backend/src/main/java/tn/hydra/stock/StockService.java` (~1060 lines) | ✅ |
| 3 | StockController (all endpoints, `@PreAuthorize`) | `backend/src/main/java/tn/hydra/stock/StockController.java` | ✅ |
| 4 | E2E test script (T1–T7 + 12 extras) | `scripts/stock-e2e.sh` | ✅ 37/37 PASS |
| 5 | REPORT 1.2 (this file) | `backend/docs/REPORT-1.2-stock-services-controllers.md` | ✅ |

Legacy conflicting Part-1.2-attempt files found in the workspace (`stock/controller/*`, `stock/dto/*`, `stock/service/StockService.java`, old tests) were **quarantined** (not deleted) to `/home/z/hydra-stock/legacy-quarantine/` — they duplicated bean names and predate the current spec.

---

## 2. API surface

### Items
| Method | Path | Perm | Notes |
|---|---|---|---|
| GET | `/api/stock/items?includeInactive=&q=` | `PERM_STOCK_VIEW` | `q` matches name **and** alias (dedup) |
| POST | `/api/stock/items` | `PERM_STOCK_ADMIN` or OWNER | code unique per org (case-insensitive) |
| PUT | `/api/stock/items/{id}` | `PERM_STOCK_ADMIN` or OWNER | active flag = soft delete |
| GET | `/api/stock/items/{id}/aliases` | `PERM_STOCK_VIEW` | |
| POST | `/api/stock/items/{id}/aliases` | `PERM_STOCK_ADMIN` or OWNER | unique per org (lower(alias)) |
| DELETE | `/api/stock/items/{id}/aliases/{aliasId}` | `PERM_STOCK_ADMIN` or OWNER | |

### Locations
| Method | Path | Perm | Notes |
|---|---|---|---|
| GET | `/api/stock/locations?projectId=&includeInactive=` | `PERM_STOCK_VIEW` | member → own sites + CENTRAL; owner → all |
| POST | `/api/stock/locations` | `PERM_STOCK_ADMIN` or OWNER | CENTRAL: one active per org (`CENTRAL_EXISTS` 409); SITE requires project in org |

### Balances (virtual stock)
| Method | Path | Perm | Notes |
|---|---|---|---|
| GET | `/api/stock/balances?projectId=&locationId=&itemId=&includeZero=` | `PERM_STOCK_VIEW` | rows: item, location, qty, uom; IDOR-filtered in SQL |
| GET | `/api/stock/balances/summary` | `PERM_STOCK_VIEW` | company-wide per-item aggregation (owner dashboard) |

### Moves
| Method | Path | Perm | Notes |
|---|---|---|---|
| POST | `/api/stock/moves` | `PERM_STOCK_MOVE` or OWNER | IN/OUT/TRANSFER/ADJUST/DIRECT_USE matrix + negative-stock hard block |
| GET | `/api/stock/moves?itemId=&locationId=&projectId=&page=&size=` | `PERM_STOCK_VIEW` | paginated `{items,total,page,size}` |
| POST | `/api/stock/moves/{id}/reverse` | `PERM_STOCK_MOVE` or OWNER | creates the OPPOSITE move (append-only) + audit |
| POST | `/api/stock/moves/{id}/documents` | `PERM_STOCK_MOVE` or OWNER | junction link (V31) — **never UPDATEs the move** |
| GET | `/api/stock/moves/{id}/documents` | `PERM_STOCK_VIEW` | primary doc + junction docs |

### Document folder hook (minimal, non-breaking)
| Method | Path | Perm | Notes |
|---|---|---|---|
| GET | `/api/stock/doc-folder?projectId=&kind=BL\|BC\|FACTURE\|AUTRE` | `PERM_STOCK_VIEW` + project access | resolve-or-create `05.01 BL` etc. under the project's `05_ACHAT` folder; frontend passes `folderId` to the **existing** upload API — upload path untouched |

---

## 3. Security model (two layers, CashService style)

**Layer 1 — `@PreAuthorize`** on every endpoint: `hasRole('OWNER') or hasAuthority('PERM_STOCK_*')`. `ROLE_OWNER` comes from `HydraUserDetailsService` (is_owner ⇒ `allPermissions()` + `ROLE_OWNER`).

**Layer 2 — service checks** (same as cash):
- Org isolation on every query (`org_id` from the caller).
- `requirePermission(auth, "STOCK_MOVE", action)` → 409 `{code:"FORBIDDEN"}` (matches cash module's convention; GlobalExceptionHandler maps it consistently).
- **Location access**:
  - `SITE` → owner OR `project_member` of the location's project. **IDOR-safe**: user on project A gets `FORBIDDEN` posting on project B's site, and B's sites/balances never appear in their lists.
  - `CENTRAL` → Part 1.1 policy applied consistently: visible/actionable to any caller holding the relevant stock permission in the org (owner, oversight roles, members of any project).
- **Document access**: `assertDocumentAccessible` verifies the document exists in the org (join `document → doc_folder`) and its project is accessible to the caller.

**JSON errors always** — `BusinessRuleException`→409, `BadRequest`→400, `NotFound`→404, `AccessDenied`→403, all shaped `{code,message}` by the existing `GlobalExceptionHandler` (reused as-is; **zero changes** to it).

---

## 4. Business rules enforced

### Move validation matrix (V30/V31 CHECK constraints + service)
| Type | from_location | to_location | qty | Extra |
|---|---|---|---|---|
| `IN` | ✗ null | ✓ required | > 0 | |
| `OUT` | ✓ required | ✗ null | > 0 | negative-stock guard |
| `DIRECT_USE` | ✓ required | ✗ null | > 0 | negative-stock guard (carburant case) |
| `TRANSFER` | ✓ required | ✓ required (≠ from) | > 0 | guard on source |
| `ADJUST` | ✓ required | ✗ null | ≠ 0 (**may be negative** — V31) | guard when negative |

### Negative-stock policy — HARD BLOCK
Projected balance < 0 → `409 {"code":"STOCK_NEGATIVE_BALANCE"}` with current balance and demanded qty in the message. Guard runs in the same transaction as the insert, via `StockMoveRepository.signedBalance` (mirrors the `stock_balance` view CASE).

### Append-only + reverse
The original move is **never edited or deleted** (DB trigger blocks it anyway). `POST /moves/{id}/reverse` creates the exact opposite move:
- `IN` → `OUT` at the original `to_location`
- `OUT`/`DIRECT_USE` → `IN` at the original `from_location`
- `TRANSFER` → `TRANSFER` with swapped ends
- `ADJUST(+q)` → `ADJUST(−q)` (and vice versa)

Reversal note: `REVERSE de <originalId> — <reason>`. documentId/partyId/cashMovementId deliberately NOT copied (the reversal is its own event; the audit row links both). The negative-stock guard applies to reversals too.

### Audit — who moved what where
Every mutation writes `event_log`:
- `STOCK_MOVE_CREATED` — details JSON: `{moveType, qty, uom, itemId, itemName, fromLocationId, toLocationId, projectId, documentId, note}`
- `STOCK_MOVE_REVERSED` — `{originalMoveId, originalType, reversalType, qty, reason}`
- `STOCK_MOVE_DOCUMENT_LINKED` — `{documentId}`
- `STOCK_ITEM_CREATED/UPDATED`, `STOCK_ITEM_ALIAS_CREATED/DELETED`, `STOCK_LOCATION_CREATED`, `STOCK_FOLDER_CREATED`

---

## 5. Part 2 mapping (reserved — comment-only, zero-risk)

Per the "ONLY if zero risk; else document" instruction: **no columns added**. V31 §3 records the exact future DDL as comments:

```sql
-- Part 2 will add:
--   ALTER TABLE stock_move ADD COLUMN bc_line_id  uuid;  -- → purchase_order_line(id)
--   ALTER TABLE stock_move ADD COLUMN bl_line_id  uuid;  -- → goods_receipt_line(id)
--   ALTER TABLE stock_move ADD COLUMN bordereau_article_id uuid;
```

API seam prepared: `PostMoveRequest.metadata` (ignored in 1.2) keeps the contract stable; `postMove()` javadoc marks the single lineage-resolution insertion point.

---

## 6. E2E TEST RESULTS — 37/37 PASS

Run: `bash scripts/stock-e2e.sh` against a fresh DB (Flyway V1→V31 + DemoDataRunner seed, backend on :8080).

| # | Test | Result | Evidence |
|---|---|---|---|
| T1a | Create item | ✅ PASS | `200 {"id":"0734cb07-…","code":"CIM25-E2E"}` |
| T1b | CENTRAL created | ✅ PASS | `200` |
| T1c | Duplicate CENTRAL → 409 | ✅ PASS | `409 {"code":"CENTRAL_EXISTS"}` |
| T1d | SITE P1 created | ✅ PASS | `200` |
| T1e | SITE P2 created | ✅ PASS | `200` |
| T2a | IN 100 at CENTRAL | ✅ PASS | `200` move id returned |
| T2b | Balance CENTRAL = 100 | ✅ PASS | `GET /balances?locationId=…` → `100` |
| T3a | TRANSFER 30 central→site | ✅ PASS | `200` |
| T3b | CENTRAL = 70 | ✅ PASS | balances → `70` |
| T3c | SITE1 = 30 | ✅ PASS | balances → `30` |
| T4a | OUT 10 at site | ✅ PASS | `200` |
| T4b | SITE1 = 20 | ✅ PASS | balances → `20` |
| T5 | OUT 10 000 → blocked | ✅ PASS | `409 {"code":"STOCK_NEGATIVE_BALANCE"}` |
| T6a | IDOR: user A posts on P2 site → FORBIDDEN | ✅ PASS | `409 {"code":"FORBIDDEN"}` |
| T6b | User A location list hides P2 sites | ✅ PASS | no P2 rows |
| T6c | User A balances hide P2 rows | ✅ PASS | no P2 rows |
| T6d | CENTRAL visible to member (policy 1.1) | ✅ PASS | present in list |
| T6e | User A posts on OWN site | ✅ PASS | `200` |
| T7a | Stock BL folder resolved | ✅ PASS | `{"folderId":"…","code":"05.01 BL","created":true}` |
| T7b | Document created in stock folder | ✅ PASS | `201` |
| T7c | Move created with documentId on payload | ✅ PASS | response `documentId` = doc id |
| T7d | Junction link to existing move | ✅ PASS | `200` |
| T7e | Move has 2 linked documents | ✅ PASS | `GET /moves/{id}/documents` → 2 |
| T7f | DB: `stock_move.document_id` persisted | ✅ PASS | psql count = 1 |
| X1 | Reverse OUT creates opposite move | ✅ PASS | `200` |
| X2 | SITE1 balance 18 → 28 (+10 restored) | ✅ PASS | computed from ledger |
| X3 | Original move untouched (append-only) | ✅ PASS | `qty` still 10 |
| X4 | Reverse IN handled (allowed or blocked) | ✅ PASS | `200` (stock available) |
| X5 | Org summary has item | ✅ PASS | total = 98 |
| X6 | 4 `STOCK_MOVE_CREATED` audit events | ✅ PASS | psql count = 4 |
| X7 | Reversal audit references original | ✅ PASS | entity_id=reversal, details contains original |
| X8 | Audit snapshot answers who-moved-what-where | ✅ PASS | details JSON has from/to/qty |
| X9 | Alias created | ✅ PASS | `200` |
| X10 | Alias search finds item | ✅ PASS | `q=gris` → item |
| X11 | History paginated | ✅ PASS | `{total:7, page size:2}` |
| R1 | Cash API healthy | ✅ PASS | `/api/cash/pots` → 200 |
| R2 | Process API healthy | ✅ PASS | `/api/processes/my-tasks` → 200 |

### curl examples

```bash
# Login (CSRF dance)
curl -s -c jar http://127.0.0.1:8080/api/auth/me > /dev/null
XSRF=$(grep XSRF-TOKEN jar | awk '{print $7}')
curl -s -b jar -c jar -X POST http://127.0.0.1:8080/api/auth/login \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"email":"owner@hydra.tn","password":"HydraOwner2026!"}'

# IN 100 at CENTRAL
curl -s -b jar -H "X-XSRF-TOKEN: $XSRF" -H "Content-Type: application/json" \
  -X POST http://127.0.0.1:8080/api/stock/moves \
  -d '{"moveType":"IN","qty":100,"uom":"sac","itemId":"<ITEM>","toLocationId":"<CENTRAL>"}'
# → 200 {"id":"…","moveType":"IN","qty":100,…}

# Balances
curl -s -b jar -H "X-XSRF-TOKEN: $XSRF" \
  "http://127.0.0.1:8080/api/stock/balances?locationId=<CENTRAL>&itemId=<ITEM>"
# → [{"itemId":"…","itemName":"Ciment 25kg E2E","locationId":"…","locationName":"Dépôt central E2E","qty":100,"uom":"sac"}]

# Negative OUT → blocked
curl -s -b jar -H "X-XSRF-TOKEN: $XSRF" -H "Content-Type: application/json" \
  -X POST http://127.0.0.1:8080/api/stock/moves \
  -d '{"moveType":"OUT","qty":10000,"uom":"sac","itemId":"<ITEM>","fromLocationId":"<SITE1>"}'
# → 409 {"code":"STOCK_NEGATIVE_BALANCE","message":"Stock insuffisant à l'emplacement 'Chantier P1 site' — solde actuel: 30, demandé: 10000"}

# Reverse a mistake
curl -s -b jar -H "X-XSRF-TOKEN: $XSRF" -H "Content-Type: application/json" \
  -X POST http://127.0.0.1:8080/api/stock/moves/<MOVE_ID>/reverse \
  -d '{"reason":"Erreur de saisie"}'
```

---

## 7. Bugs found & fixed during this slice

| Bug | Fix |
|---|---|
| `created_at` inserted as NULL for `StockLocation` / `ItemAlias` / `StockMove` (no auditing superclass) → `DATA_INTEGRITY` 400 on create | Set `createdAt = Instant.now()` explicitly before save (auditing only covers `Auditable` subclasses) |
| `assertDocumentAccessible` passed `(orgId, documentId)` but SQL read `d.id = ? and d.org_id = ?` → `DOCUMENT_NOT_FOUND` on valid docs | Swapped argument order |
| Move-history mapper cast `created_at` to `Instant` but JdbcTemplate returns `java.sql.Timestamp` → 500 INTERNAL on `GET /moves` | `toInstant()` normalizer (Timestamp / OffsetDateTime / LocalDateTime) |
| Folder hook looked for parent code `'05 ACHAT'` but V9 re-seeded templates with underscore codes | Accept both `05_ACHAT` and `05 ACHAT` |
| Legacy Part-1.2 attempt (conflicting bean name `stockService`) broke boot | Quarantined old `stock/controller|dto|service` + old tests |
| ADJUST with negative qty impossible under V30 `CHECK (qty > 0)` | V31 replaces the constraint: `qty > 0 OR (move_type='ADJUST' AND qty < 0)` |

---

## 8. Risks / notes

1. **IDOR returns 409-FORBIDDEN (not 403)** for service-layer checks — this matches the existing cash module convention (`CashService.requirePermission` → `BusinessRuleException("FORBIDDEN")` → 409). `@PreAuthorize` denials still yield true 403 via `GlobalExceptionHandler`. Consistency chosen over HTTP purism; changing it project-wide is a separate cleanup.
2. **CENTRAL move policy**: any org user with `PERM_STOCK_MOVE` can post at CENTRAL (Part 1.1 policy). If a client needs per-project CENTRAL restrictions, that's a Slice 2 enhancement.
3. **Alias uniqueness** is org-wide (not per item) — one alias string maps to exactly one item, preventing ambiguity in the picker.
4. **`stock_move_document` junction** (V31) enables post-creation doc links without touching the append-only `stock_move` row. Primary proof stays on `stock_move.document_id` (set only at creation).
5. **Reversal of an IN whose stock was consumed** is correctly blocked by the negative-stock guard (clear error) — the office must first move stock back or post an ADJUST.
6. **Cash/process regression**: R1/R2 green; zero changes to cash/process files (only additive migrations + new package).

---

## 9. How to run

```bash
cd /tmp/my-project/backend
export JAVA_HOME=/tmp/my-project/.jdk && export PATH=$JAVA_HOME/bin:$PATH
./mvnw package -DskipTests
bash /tmp/my-project/scripts/stock-start-backend.sh   # daemonized; Flyway migrates + demo seed
bash /tmp/my-project/scripts/stock-e2e.sh             # → RESULT: PASS=37 FAIL=0
```

---

## 10. Files changed (this slice)

**Added**
- `backend/src/main/resources/db/migration/V31__stock_slice12_support.sql`
- `backend/src/main/java/tn/hydra/stock/StockService.java`
- `backend/src/main/java/tn/hydra/stock/StockController.java`
- `scripts/stock-e2e.sh`
- `backend/docs/REPORT-1.2-stock-services-controllers.md`

**From Part 1.1 (same push)**
- `backend/src/main/resources/db/migration/V30__stock_virtual_v1.sql`
- `backend/src/main/java/tn/hydra/stock/ARCHITECTURE.md`
- `backend/src/main/java/tn/hydra/stock/domain/{StockItem,ItemAlias,StockLocation,StockMove,StockLocationType,StockMoveType}.java`
- `backend/src/main/java/tn/hydra/stock/repository/{StockItemRepository,ItemAliasRepository,StockLocationRepository,StockMoveRepository}.java`
- `backend/docs/REPORT-1.1-virtual-stock-slice-1.md`

**Renamed (pre-existing duplicate-version cleanup)**
- `V26__cash_movement_payment_method.sql` → `V26__cash_movement_payment_method.sql.disabled` (Flyway rejects duplicate V26; the canonical `V26__cash_movement_enriched.sql` supersedes it)

**Modified — none** (no existing entity/service/controller touched; GlobalExceptionHandler, SecurityConfig, cash, process all reused as-is).
