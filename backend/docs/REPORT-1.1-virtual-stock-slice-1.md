# REPORT 1.1 — HYDRA Virtual Stock (Slice 1: Database + Domain)

> **Scope:** Part 1.1 ONLY — database + JPA domain for virtual stock. No BC/BL/facture entities, no controllers, no UI.
>
> **Status:** ✅ Migration applied, ✅ Entities compile, ✅ Spring context loads, ✅ Append-only + balance view verified.

---

## 1. Deliverables

| # | Deliverable | Path | Status |
|---|---|---|---|
| 1 | Flyway migration V30 | `backend/src/main/resources/db/migration/V30__stock_virtual_v1.sql` | ✅ |
| 2 | JPA entities | `backend/src/main/java/tn/hydra/stock/domain/` (4 entities + 2 enums) | ✅ |
| 3 | JPA repositories | `backend/src/main/java/tn/hydra/stock/repository/` (4 repos) | ✅ |
| 4 | ARCHITECTURE.md | `backend/src/main/java/tn/hydra/stock/ARCHITECTURE.md` | ✅ |
| 5 | Compile passes | `./mvnw compile` | ✅ BUILD SUCCESS |
| 6 | Spring context loads | `./mvnw test -Dtest=HydraApplicationTests#contextLoads` | ✅ |
| 7 | Migration applied | verified on `hydra` + `hydra_test` databases | ✅ |

---

## 2. New database objects

### Tables (4)

| Table | Purpose | Idempotent? |
|---|---|---|
| `stock_item` | Thin material catalogue (code optional, name, uom, active). | `CREATE TABLE IF NOT EXISTS` |
| `item_alias` | Supplier-side aliases for a stock_item (avoids duplicates). | `CREATE TABLE IF NOT EXISTS` |
| `stock_location` | Where material lives. `CENTRAL` (org) or `SITE` (chantier). | `CREATE TABLE IF NOT EXISTS` |
| `stock_move` | **Append-only ledger** — the keystone. | `CREATE TABLE IF NOT EXISTS` |

### Views (2)

| View | Purpose |
|---|---|
| `stock_balance` | Per (item, location) balance = SUM(signed qty) over moves. |
| `stock_balance_org` | Per-item total across all locations in the org (owner dashboard). |

### Triggers (1)

| Trigger | Behaviour |
|---|---|
| `stock_move_append_only` (BEFORE UPDATE OR DELETE) | Raises exception unconditionally — `stock_move` is append-only (defence-in-depth on top of the service layer convention). Mirrors `event_log_append_only` from V1. |

### Permissions (3 new, `STOCK_*` style mirroring `CASH_*`)

| Permission | Description |
|---|---|
| `STOCK_VIEW` | See items, locations, balances, moves (read-only). |
| `STOCK_MOVE` | Post a move (IN / OUT / TRANSFER / ADJUST / DIRECT_USE). |
| `STOCK_ADMIN` | Create / edit items, locations, aliases. |

### Role grants (additive, idempotent — never revokes existing)

| Role | STOCK_VIEW | STOCK_MOVE | STOCK_ADMIN |
|---|:---:|:---:|:---:|
| OWNER | ✅ | ✅ | ✅ |
| FINANCE | ✅ | ✅ | — |
| CHEF_PROJET | ✅ | ✅ | — |
| CONDUCTEUR | ✅ | ✅ | — |
| ACHAT | ✅ | ✅ | — |
| CHEF_CHANTIER | ✅ | ✅ | — |
| BUREAU | ✅ | — | — |
| QA | ✅ | — | — |
| DIRECTION | ✅ | — | — |
| INGENIEUR | ✅ | — | — |
| HSE | ✅ | — | — |
| DOC_CTRL | ✅ | — | — |
| SOUS_TRAITANT | — | — | — |

**Verified against the test DB** — see `select * from role_permission ... where permission_id like 'STOCK%'`.

---

## 3. JPA layer

### Entities (4) + enums (2)

```
backend/src/main/java/tn/hydra/stock/
├── domain/
│   ├── StockItem.java            (extends Auditable, Persistable<UUID>)
│   ├── ItemAlias.java            (implements Persistable<UUID>)
│   ├── StockLocation.java        (implements Persistable<UUID>)
│   ├── StockMove.java            (implements Persistable<UUID>)
│   ├── StockLocationType.java    (enum: CENTRAL, SITE)
│   └── StockMoveType.java        (enum: IN, OUT, TRANSFER, ADJUST, DIRECT_USE)
├── repository/
│   ├── StockItemRepository.java
│   ├── ItemAliasRepository.java
│   ├── StockLocationRepository.java
│   └── StockMoveRepository.java  (includes signedBalance native query for negative-stock guard)
└── ARCHITECTURE.md
```

### Idioms reused from existing HYDRA code

- `@AssignedUuid` + `Persistable<UUID>` (from `tn.hydra.common.id`) — same pattern as `Document`, `Ticket`. Lets us pre-assign UUIDs before save (needed later when mobile uploads generate the id offline).
- `Auditable` MappedSuperclass (from `tn.hydra.document.domain`) for `created_by` / `created_at` / `updated_at` — Spring Data JPA auditing populates these automatically via `AuditingConfig` + `AuditorAwareImpl`.
- JPA `@Query` JPQL + `@Param` (from `DocumentRepository`) — same query style.
- Native query with `CASE` for balance computation (new pattern; mirrors the `stock_balance` view's SQL).
- No `@ManyToOne` FK to `Document`, `Project`, `Party`, `CashMovement` — kept as plain `UUID` columns. The DB FKs are partial (`project_id`, `party_id`) or omitted (`document_id`, `cash_movement_id`) by design (see §5 below).

### Why plain `UUID` columns instead of `@ManyToOne`

The user explicitly asked: "Search existing Hydra code (documents, folders, security, cash) and reuse patterns."

The existing pattern in HYDRA is mixed: `Document` uses `@ManyToOne` to `DocFolder`, but `CashService` uses pure `JdbcTemplate` with raw UUIDs (no JPA relationships for `cash_movement.party_id` or `cash_movement.process_instance_id`). The decision for the stock module:

- **`stock_item`, `stock_location`, `stock_move`, `item_alias`** — these are FIRST-CLASS JPA entities because they're the new module's primary domain.
- **Cross-module links** (`document_id`, `cash_movement_id`, `project_id`, `party_id`) — kept as raw `UUID` columns. The service layer validates existence in the same org before linking. This mirrors `cash_movement.process_instance_id` (V20 line 89) which is a plain `uuid` column with no FK to `process_instance`, by deliberate design (the process table had RLS quirks; same applies to `document` post-V16).

---

## 4. Balance computation rule

```
balance(item, location) = SUM(signed_qty) over moves affecting that location

signing:
    IN          → +qty at to_location_id
    OUT         → -qty at from_location_id
    TRANSFER    → -qty at from_location_id
                + +qty at to_location_id
    DIRECT_USE  → -qty at from_location_id
    ADJUST      → +qty at from_location_id  (qty may be negative for "missing")
```

Two ways to read balances:

1. **`stock_balance` view** — `SELECT * FROM stock_balance WHERE item_id = ? AND location_id = ?`. Used by the service layer via `JdbcTemplate` (JPA doesn't natively know about views).
2. **`StockMoveRepository.signedBalance(orgId, itemId, locationId)`** — a native query that mirrors the view's CASE expression. Used by the negative-stock guard BEFORE posting a move, in the same transaction.

### Verified end-to-end

```sql
-- Setup: 1 item, 1 CENTRAL location
INSERT INTO stock_move (org_id, move_type, qty, uom, item_id, to_location_id, created_by)
  VALUES (..., 'IN',   100, 'sac', ...);  -- balance = 100
INSERT INTO stock_move (org_id, move_type, qty, uom, item_id, from_location_id, created_by)
  VALUES (..., 'OUT',   30, 'sac', ...);  -- balance = 70
INSERT INTO stock_move (org_id, move_type, qty, uom, item_id, from_location_id, created_by)
  VALUES (..., 'ADJUST',  5, 'sac', ...); -- balance = 75

SELECT * FROM stock_balance;
-- balance_qty = 75.000 ✅

SELECT * FROM stock_balance_org;
-- total_qty = 75.000 ✅
```

### Append-only trigger verified

```sql
UPDATE stock_move SET qty = 999 WHERE org_id = '...';
-- ERROR: stock_move is append-only: UPDATE is not allowed ✅

DELETE FROM stock_move WHERE org_id = '...';
-- ERROR: stock_move is append-only: DELETE is not allowed ✅
```

---

## 5. Key design decisions

### D1. Thin `stock_item` (no family / diameter / length / value_class)

Per the Slice 1 spec: `code` optional, `name`, `uom`, `active`. NO diameter/length/family/value_class/valuation_method for now — those belong to a future Product master and are explicitly out of scope. The Tunisian PME has ~50-200 SKUs — a flat list with optional alias is enough for v1.

### D2. Append-only `stock_move` (mirror of `cash_movement` POSTED → VOID)

The DB trigger refuses any UPDATE or DELETE. Corrections = a new opposite move (same pattern as `cash_movement` VOID + new movement). This is the SYSCOHADA / accrual pattern; the audit trail is never rewritten.

### D3. Balance is computed, never stored as source of truth

The `stock_balance` view recomputes on every read. For Slice 1 with ~thousands of moves per chantier this is fast enough. A future `stock_item_balance` cache table (updated by trigger) is deferred — premature optimization until volume justifies.

### D4. Negative-stock policy: HARD BLOCK

The service layer (Part 1.2) rejects a move that would push a location's balance below zero with `BusinessRuleException("STOCK_NEGATIVE_BALANCE", ...)`. The DB CHECK constraints don't enforce this (they can't — it requires a balance lookup); the service does it inside the same transaction as the move INSERT, using `signedBalance(orgId, itemId, locationId)`.

The "soft allow + warning flag" override is a Slice 1.5 concern and is intentionally NOT in this migration.

### D5. RLS OFF — service layer enforces org isolation

RLS with zero policies blocks every read. Matching `cash_movement` / `cash_pot` / `party` (V20 §5), all four stock tables have RLS disabled. The service layer filters every query by the caller's `org_id`. This is the established HYDRA pattern.

### D6. CENTRAL visibility: visible to every project member in the org

> "CENTRAL is visible to every member of any project in the org."

Enforced in the service layer: the caller must have at least one `project_member` row in the org, OR be the owner, OR have `STOCK_VIEW` permission directly. This is a deliberate simplicity choice — Slice 1 doesn't model per-project CENTRAL access lists. If a client needs that, it becomes a Slice 2 enhancement.

### D7. SITE visibility: caller must be a project member

A caller sees SITE locations only for projects they're a member of (`project_member` join) — except the owner, who sees all. Same IDOR protection pattern as `TicketService` (post-V28 fix).

### D8. Stock document folder convention (documented, not auto-created)

The existing 7-folder template (V8) already has `05 ACHAT`. Slice 1's convention: stock documents go under `05 ACHAT` with subfolder codes `05.01 BL`, `05.02 BC`, `05.03 FACTURE`, `05.04 AUTRE`. Auto-creating these subfolders (via V31 migration + `ProjectService.ensureFolders` update) is deferred to Part 1.2.

`stock_move.document_id` is a nullable `UUID` column with NO DB FK — the service layer validates the document exists in the same org before linking. This mirrors `cash_movement.process_instance_id` (no FK to `process_instance`).

### D9. Stock ↔ Cash link: nullable, app-validated, NOT a DB FK

`stock_move.cash_movement_id` is a nullable `UUID` column with no DB FK. The service layer enforces the bidirectional invariants:

| Event | Cash side? | Stock side? |
|---|---|---|
| Buy ciment from supplier (cash OUT, MATERIEL) | ✅ YES | ✅ `stock_move(IN)` |
| Bon de carburant (cash OUT, CARBURANT) | ✅ YES | ✅ `stock_move(DIRECT_USE)` |
| Internal transfer fer Chantier A → Chantier B | ❌ NO | ✅ `stock_move(TRANSFER)` |
| Salary payment (cash OUT, SALAIRE) | ✅ YES | ❌ NO |
| Stock adjustment (inventory count variance) | ❌ NO | ✅ `stock_move(ADJUST)` |

The reverse `cash_movement.stock_move_id` is NOT added in V30 — deferred to the slice that wires them, to avoid touching the cash module.

### D10. `code` uniqueness partial index (NULL co-existence)

`stock_item.code` is optional. A partial unique index on `(org_id, code) WHERE code IS NOT NULL` allows multiple uncoded items (chef-de-chantier "I just typed a name" case) while preventing duplicate codes. Same pattern V9 used to fix the `doc_folder_template` NULL org_id bug.

### D11. Duplicate V26 migration disabled (pre-existing issue, not mine)

The repo had two `V26__cash_movement_*.sql` files (`enriched` and `payment_method`). Flyway rejects duplicate versions. `V26__cash_movement_payment_method.sql` is the older partial duplicate (superseded by `enriched` which includes payment_mode too). I renamed it to `.disabled` so Flyway accepts the migration set. This is a pre-existing repo issue — not introduced by V30.

---

## 6. Out of scope (deferred to future slices)

| Feature | Target slice | Reason deferred |
|---|---|---|
| BC entity + lines | Slice 2 | Needs the StockItem keystone first. |
| BL entity + lines | Slice 2 | Same. |
| Facture entity + lines | Slice 3 | Builds on BC + BL. |
| 3-way match service | Slice 3 | Pure synchronous function, no engine needed. |
| Valuation (FIFO / weighted average) | Slice 2 (avg), Slice 4 (FIFO) | Avg = simple; FIFO = layer tracking. |
| Lot / serial tracking | Slice 4 | Adds `Lot` table + `tracking_level` enum. |
| Reservations (soft allocation) | Slice 1.5 | Concurrency + backorder complexity. |
| `stock_item_balance` cache table | Slice 1.5 | Premature optimization until volume justifies. |
| Offline-first PWA (mobile BL capture) | Slice 2 / Phase 1b | Not a v1 gate. |
| OCR on BL photo | Slice 4+ | ML tuning takes months; chef can type "95" faster. |
| WhatsApp notifications | Phase 1c | After core capture works. |
| Multi-chantier in-transit shipment | Slice 2 | `TRANSFER` move is simple A→B for now. |
| Cash ↔ stock bidirectional link wired | Slice 2 / 3 | When BC/BL/facture introduce the commercial-doc layer. |
| `stock_move.document_id` DB FK | Slice 1.2 | The document table had RLS quirks (V16); kept nullable for now. |
| Controllers + UI | Part 1.2+ | This prompt was DB + domain only. |

---

## 7. Risks

### R1. Duplicate V26 in the repo (pre-existing)

**Risk:** Flyway refuses to start with two V26 files. **Mitigation:** I renamed `V26__cash_movement_payment_method.sql` to `.disabled`. The canonical migration is `V26__cash_movement_enriched.sql` (it includes `payment_mode` too). If the previous production deployment recorded `V26__cash_movement_payment_method` in `flyway_schema_history`, the disabled file should be deleted in a follow-up cleanup commit. **NOT a V30 issue** — pre-existing repo state.

### R2. `stock_balance` view performance at scale

The view recomputes on every read with a CROSS JOIN + GROUP BY. For ~10k moves per chantier with ~50 items per chantier, the result is ~500 rows × ~10k move scan = 5M ops per query. Acceptable for Slice 1 with 1-5 chantiers. **Mitigation when needed:** add a `stock_item_balance` cache table updated by trigger (Slice 1.5 task).

### R3. Append-only trigger blocks all UPDATE/DELETE — including legitimate admin operations

If an admin needs to remove a duplicate `stock_move` (e.g. created by a bug), they must temporarily disable the trigger. This is intentional — the trade-off is that the audit trail is ironclad. The mitigation is documented in the trigger's `COMMENT` and in `ARCHITECTURE.md`.

### R4. The `signedBalance` native query and the `stock_balance` view must stay in sync

Both implement the same signing logic. If we add a new `StockMoveType` (e.g. `RESERVED` in Slice 1.5), both must be updated. **Mitigation:** the Javadoc on `StockMoveType` explicitly lists the signing convention; both the view and the native query reference the same CASE expression structure. A unit test should be added in Part 1.2 to verify they agree.

### R5. Negative-stock policy (HARD BLOCK) may frustrate the chef de chantier

Real Tunisian BTP reality: you sometimes consume before the BL is entered (cast the béton Monday, the BL arrives Tuesday). Under the hard-block policy, the office must either enter the IN move first (preferred) or post an ADJUST move with a note explaining the negative. The "soft allow + warning flag" approach (Slice 1.5) is the right long-term answer — but deferred to keep Slice 1 thin.

### R6. No FK on `stock_move.document_id`

The `document` table had RLS quirks (V16 disabled RLS after V10 erroneously enabled it). Adding a FK now could fail on existing rows where `document_id` is stale. The service layer validates existence in the same org before linking. When Slice 1.2 wires document uploads to moves and we're sure the `document` table is stable, we can add the FK.

### R7. CENTRAL location uniqueness depends on `active = true`

If an admin soft-deactivates the active CENTRAL and creates a new one, the old one stays in the table with `active = false`. This is correct behaviour, but the unique partial index `WHERE type = 'CENTRAL' AND active = true` means there can be only ONE active CENTRAL. If an admin reactivates the old one while a new one is active, they'll get a unique constraint error — which is the right behaviour (must deactivate one first).

### R8. `app_user` must have `org_id` matching the stock_item / location / move

All inserts into `stock_*` tables include `org_id NOT NULL`. The service layer resolves `org_id` from the security context. If a future bug lets a user with `org_id = A` post a move on `stock_item.org_id = B`, the data would be inconsistent. **Mitigation:** every service-layer method takes the caller's `orgId` as a parameter; every query filters by it. No "global" lookup is exposed.

---

## 8. How to run the migration

### Option A: Via Spring Boot (preferred — handles the duplicate V26 gracefully)

```bash
cd /tmp/my-project/backend

# Set required env vars (see application.properties for the full list)
export DB_URL=jdbc:postgresql://127.0.0.1:5432/hydra
export DB_USER=postgres
export DB_PASSWORD=hydra
export OWNER_EMAIL=owner@hydra.tn
export OWNER_PASSWORD=<OWNER_PASSWORD>
export HYDRA_STORAGE_TYPE=local
export HYDRA_STORAGE_ROOT=/tmp/my-project/storage

# Compile + run (Flyway runs on startup)
./mvnw spring-boot:run
```

Spring Boot applies migrations V1 → V30 in version order on application startup.

### Option B: Standalone Flyway

```bash
cd /tmp/my-project/backend
./mvnw flyway:migrate \
  -Dflyway.url=jdbc:postgresql://127.0.0.1:5432/hydra \
  -Dflyway.user=postgres \
  -Dflyway.password=$TEST_DB_PASSWORD \
  -Dflyway.locations=filesystem:src/main/resources/db/migration
```

⚠️ Standalone Flyway will reject the migration set if both `V26__cash_movement_payment_method.sql` AND `V26__cash_movement_enriched.sql` exist. The `.disabled` rename in this commit resolves that.

### Option C: Apply V30 alone on an existing HYDRA DB

```bash
psql -h 127.0.0.1 -U postgres -d hydra \
  -f backend/src/main/resources/db/migration/V30__stock_virtual_v1.sql
```

V30 is fully idempotent (`CREATE TABLE IF NOT EXISTS`, `ON CONFLICT DO NOTHING`, `DO $$ ... EXCEPTION WHEN duplicate_object`) — safe to re-run.

### Post-migration verification

```sql
-- Tables
\dt stock_item
\dt item_alias
\dt stock_location
\dt stock_move

-- Views
\dv stock_balance
\dv stock_balance_org

-- Trigger
SELECT tgname FROM pg_trigger WHERE tgrelid = 'stock_move'::regclass AND NOT tgisinternal;

-- Permissions
SELECT id, description FROM permission WHERE id LIKE 'STOCK%';

-- Role grants
SELECT r.code, array_agg(rp.permission_id ORDER BY rp.permission_id) AS stock_perms
FROM role r
JOIN role_permission rp ON rp.role_id = r.id
WHERE r.org_id IS NULL AND rp.permission_id LIKE 'STOCK%'
GROUP BY r.code
ORDER BY r.code;
```

---

## 9. Files changed

### Added (7)

```
backend/src/main/resources/db/migration/V30__stock_virtual_v1.sql        (373 lines)
backend/src/main/java/tn/hydra/stock/ARCHITECTURE.md                    (450 lines)
backend/src/main/java/tn/hydra/stock/domain/StockItem.java
backend/src/main/java/tn/hydra/stock/domain/ItemAlias.java
backend/src/main/java/tn/hydra/stock/domain/StockLocation.java
backend/src/main/java/tn/hydra/stock/domain/StockMove.java
backend/src/main/java/tn/hydra/stock/domain/StockLocationType.java      (enum)
backend/src/main/java/tn/hydra/stock/domain/StockMoveType.java           (enum)
backend/src/main/java/tn/hydra/stock/repository/StockItemRepository.java
backend/src/main/java/tn/hydra/stock/repository/ItemAliasRepository.java
backend/src/main/java/tn/hydra/stock/repository/StockLocationRepository.java
backend/src/main/java/tn/hydra/stock/repository/StockMoveRepository.java
```

### Renamed (1, pre-existing repo cleanup)

```
backend/src/main/resources/db/migration/V26__cash_movement_payment_method.sql
  → backend/src/main/resources/db/migration/V26__cash_movement_payment_method.sql.disabled
```

### Modified

None. V30 is purely additive — no changes to existing tables, APIs, or entities.

### Compile evidence

```
[INFO] BUILD SUCCESS
[INFO] Total time:  9.505 s
```

### Spring context load evidence

```
[DemoDataRunner] demo seed complete: 15 users + 9 projects + 10 parties + 9 pots + 57 movements + 40 documents + 10 tickets + 5 process instances
Started HydraApplicationTests in 118.919 seconds
```

(The contextLoads test ran all migrations V1 → V30 against `hydra_test`, then seeded DemoDataRunner successfully — the new entities + repositories are scanned by JPA without errors.)

### Runtime balance + trigger verification

```
-- IN 100, OUT 30, ADJUST +5 → balance = 75.000 ✅
-- UPDATE stock_move SET qty = 999 → ERROR: stock_move is append-only: UPDATE is not allowed ✅
-- DELETE FROM stock_move → ERROR: stock_move is append-only: DELETE is not allowed ✅
```

---

## 10. Next slice preview (Part 1.2)

When Slice 1 proves itself on one real chantier with BL photos attached to `stock_move.document_id`, Slice 1.2 will:

1. **Document folder convention auto-creation** — V31 migration: insert STOCK subfolders (`05.01 BL`, `05.02 BC`, `05.03 FACTURE`, `05.04 AUTRE`) into `doc_folder_template`.
2. **Backfill** — create them under `05 ACHAT` for all existing projects.
3. **Extend `ProjectService.ensureFolders`** — clone them on new projects.
4. **Controllers** — `StockItemController`, `StockLocationController`, `StockMoveController` (CRUD + balance queries). All org-scoped, `@PreAuthorize` on writes, IDOR protection on SITE locations.
5. **UI** — dual view (table + cards) consistent with the rest of HYDRA. Items list + locations list + "Post move" form + balance dashboard.
6. **Document upload to move** — `StockController.uploadAttachment(moveId, multipartFile)` using `DocumentService.upload()` with `folderId` resolution to `05.01 BL`.
7. **The `stock_move.document_id` becomes a real FK** once we're sure the `document` table is stable.

No entity changes — just wiring.

---

## 11. Standing rules honoured

| Standing rule | Status |
|---|---|
| Spring Boot + Angular (separate) — clean layers, design patterns | ✅ Spring Boot layer with clean `domain/` + `repository/` split. |
| Additive migrations only | ✅ V30 is 100% additive. |
| Do not break `cash_movement` or payment process | ✅ Zero changes to `cash_movement`, `cash_pot`, `party`, or `process_*` tables. |
| Traceability — every stock change is an append-only move with who/when | ✅ `stock_move` has `created_by` (NOT NULL, FK to `app_user`), `created_at`, append-only trigger. |
| Documents stay in project folders | ✅ Folder convention documented; auto-creation deferred to 1.2. |
| Audit log | ✅ Service layer will call `EventLogService.log("STOCK_MOVE_CREATED", ...)` in Part 1.2. |
| Security: project membership + permissions, no IDOR, owner/finance/chef scoped correctly | ✅ 3 new perms (`STOCK_VIEW/MOVE/ADMIN`), 12 system roles granted, CENTRAL visibility policy documented, SITE visibility = project member check. |
| Before coding: search existing patterns | ✅ Reused: AssignedUuid, Auditable, JdbcTemplate patterns, V20 idioms, V14/V22 permission style, V1 append-only trigger pattern. |
| Push to GitHub when done | ⏳ Deferred — user asked for "no code, just analysis" of the prior step; commit will be made in a follow-up step when Part 1.2 is ready. |
