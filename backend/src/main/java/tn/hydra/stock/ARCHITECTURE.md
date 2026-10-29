# HYDRA — Virtual Stock (Part 1.1) Architecture

> Slice 1 = items + locations + stock_moves. No BC/BL/facture, no 3-way match,
> no valuation, no reservations, no offline cache. See "Out of scope" below.

---

## 1. What this slice delivers

A **virtual stock** substrate: every material movement is recorded as an
append-only row in `stock_move`, and the balance of any (item, location)
pair is computed as `SUM(signed qty)` over the moves affecting that location.
Nothing is ever `UPDATE`d as a source of truth — corrections are appended as
opposite moves (the same pattern as `cash_movement` POSTED → VOID).

This is the **keystone**: every future advanced feature (3-way match, chantier
margin, valuation, fiscal TVA deduction validation, parallel-approval service
tasks) hangs off the existence of these `stock_move` rows and the `BL ↔ facture`
linkage they enable.

---

## 2. Entities

| Entity              | Table           | Purpose                                                      |
|---------------------|-----------------|--------------------------------------------------------------|
| `StockItem`         | `stock_item`    | Thin material catalogue (code optional, name, uom, active).  |
| `ItemAlias`         | `item_alias`    | Supplier-side aliases for a stock_item (avoids duplicates).  |
| `StockLocation`     | `stock_location`| Where material lives. `CENTRAL` (org) or `SITE` (chantier).  |
| `StockMove`         | `stock_move`    | **Append-only ledger**. The keystone.                        |

Enums:
- `StockLocationType` — `CENTRAL | SITE` (Slice 1)
- `StockMoveType` — `IN | OUT | TRANSFER | ADJUST | DIRECT_USE`

---

## 3. Balance computation rule

```
balance(item, location) = SUM(signed_qty)
where signed_qty per move:
    IN          → +qty at to_location_id
    OUT         → -qty at from_location_id
    TRANSFER    → -qty at from_location_id
                + +qty at to_location_id
    DIRECT_USE  → -qty at from_location_id
    ADJUST      → +qty at from_location_id  (qty may be negative for "missing")
```

Two ways to read balances:
1. **`stock_balance` view** — `SELECT * FROM stock_balance WHERE item_id = ? AND location_id = ?`.
   Used by the service layer via JdbcTemplate (JPA doesn't natively know about views).
2. **`StockMoveRepository.signedBalance(orgId, itemId, locationId)`** — a native
   query that mirrors the view's CASE expression. Used by the negative-stock
   guard BEFORE posting a move, in the same transaction.

A second view, `stock_balance_org`, exposes per-item totals across all
locations in the org (the owner dashboard query).

---

## 4. Append-only guarantee

Two layers of defence:

1. **DB trigger** `stock_move_append_only` (BEFORE UPDATE OR DELETE) — raises
   an exception unconditionally. Mirrors the `event_log_append_only` trigger
   from V1.
2. **Service layer convention** — corrections are made by appending an
   opposite move. The service never exposes an `update` or `delete` method
   on `StockMove`.

`StockItem` and `StockLocation` are NOT append-only — they can be edited
(name, uom, code) or soft-deleted (`active = false`). Hard delete is blocked
by FK constraints (a `stock_move` referencing them cannot be deleted, and
the move's append-only trigger forbids deleting the move).

---

## 5. Negative-stock policy (Slice 1 decision)

**HARD BLOCK by default.** The service rejects a move that would push a
location's balance below zero with `BusinessRuleException("STOCK_NEGATIVE_BALANCE", ...)`.

Override ("soft allow + warning flag") is a **Slice 1.5** concern and is
intentionally not in this slice. Real Tunisian BTP reality: you sometimes
consume before the BL is entered (you cast the béton Monday, the BL arrives
Tuesday). When that happens under the hard-block policy, the office must
either enter the IN move first (preferred) or post an ADJUST move with a
note explaining the negative. The "warning flag" approach (allow negative
with a flag for cleanup) is the right long-term answer — but it's deferred
to keep Slice 1 thin.

---

## 6. Security model

### Permissions (3 new, mirroring `CASH_*` style)

| Permission     | Description                                                   |
|----------------|---------------------------------------------------------------|
| `STOCK_VIEW`   | See items, locations, balances, moves (read-only).           |
| `STOCK_MOVE`  | Post a move (IN / OUT / TRANSFER / ADJUST / DIRECT_USE).     |
| `STOCK_ADMIN` | Create / edit items, locations, aliases.                     |

`OWNER` gets all three implicitly (via `PermissionService.allPermissions()`).
Other system roles get additive grants — see V30 §9.

### Org isolation

RLS is **OFF** for `stock_*` tables (matching `cash_movement`, `cash_pot`,
`party` — V20 §5). RLS with zero policies blocks every read; the app
enforces org isolation in the service layer. Every query filters by the
caller's `org_id`.

### CENTRAL visibility policy (Slice 1 decision)

> "CENTRAL is visible to every member of any project in the org."

Enforced in the service layer (`StockService.listLocationsForUser`):
- The caller must have at least one `project_member` row in the org, OR
- Be the owner, OR
- Have `STOCK_VIEW` permission directly (BUREAU, QA, DIRECTION, etc. — read-only oversight roles).

This is a deliberate simplicity choice — Slice 1 doesn't model
"CENTRAL is shared between projects A and B but not C". If a client needs
that level of granularity, it becomes a Slice 2 enhancement (per-project
CENTRAL access list).

### SITE visibility

A caller sees SITE locations only for projects they're a member of
(`project_member` join) — except the owner, who sees all. This is the same
IDOR protection pattern used by `TicketService` (post-V28 fix).

---

## 7. Document folder convention (documented, not auto-created)

Slice 1 does NOT redesign the DMS. The existing folder template (V8) has
7 top-level folders per project:

```
01 ADMIN  02 TECH  03 EXEC  04 QUAL  05 ACHAT  06 FIN  07 INT
```

**Convention for stock documents** (BL photos, BC scans, facture PDFs):
put them under `05 ACHAT` with these subfolder codes:

| Subfolder code   | Contents                                            |
|------------------|-----------------------------------------------------|
| `05.01 BL`       | Bons de livraison (delivery notes)                  |
| `05.02 BC`       | Bons de commande (purchase orders)                  |
| `05.03 FACTURE`  | Factures fournisseurs                               |
| `05.04 AUTRE`    | Attestations, bons de carburant, photos diverses   |

### Why under `05 ACHAT`

- It already exists for every project (cloned by `ProjectService.ensureFolders`).
- Purchase documents belong naturally to the "Achats" workspace.
- Adding a sibling `08 STOCK` would require a `doc_folder_template` migration
  + a `ProjectService` update — **deferred to Part 1.2** when we wire
  document uploads to moves.

### Part 1.2 plan

1. Add a V31 migration that inserts the 4 STOCK subfolders into
   `doc_folder_template` (org_id NULL = global).
2. Extend `ProjectService.ensureFolders` to clone them on new projects.
3. Provide a backfill SQL that creates them for existing projects
   (idempotent `ON CONFLICT DO NOTHING` by `(project_id, parent_id, code)`).
4. `stock_move.document_id` will then point at documents living in those
   folders, and the `StockController.uploadAttachment` endpoint can use
   the existing `DocumentService.upload()` with `folderId` resolution.

For Slice 1, the convention is purely a documentation rule: humans
uploading BL photos today pick the right folder; the schema is ready.

---

## 8. Stock ↔ Cash linkage (designed now, wired later)

The bidirectional link is **nullable FK + application-enforced invariants**:

| Event                                              | Cash side? | Stock side?                            |
|----------------------------------------------------|-------------|----------------------------------------|
| Buy ciment from supplier (cash OUT, MATERIEL)     | ✅ YES      | ✅ `stock_move(IN)`                    |
| Bon de carburant (cash OUT, CARBURANT)             | ✅ YES      | ✅ `stock_move(DIRECT_USE)`            |
| Internal transfer fer Chantier A → Chantier B      | ❌ NO       | ✅ `stock_move(TRANSFER)`              |
| Salary payment (cash OUT, SALAIRE)                  | ✅ YES      | ❌ NO                                   |
| Stock adjustment (inventory count variance)        | ❌ NO       | ✅ `stock_move(ADJUST)`                |

Schema (in V30):
- `stock_move.cash_movement_id` (nullable, no DB FK — the cash_movement
  table has its own append-only trigger and quirks)
- `cash_movement.stock_move_id` — **not added in V30** (deferred to the
  slice that actually wires them, to avoid touching the cash module).

The migration from the current `cash_movement.nature` enum is **additive,
never destructive** — see the migration plan in the user-facing REPORT 1.1.

---

## 9. Out of scope (deferred)

These were deliberately NOT built in Slice 1. Each has a clear future slice:

| Feature                       | Slice            | Why deferred                                          |
|-------------------------------|------------------|-------------------------------------------------------|
| BC entity + lines             | Slice 2          | Needs the StockItem keystone first.                  |
| BL entity + lines             | Slice 2          | Same.                                                 |
| Facture entity + lines        | Slice 3          | Builds on BC + BL.                                   |
| 3-way match service           | Slice 3          | Pure synchronous function, no engine needed.         |
| Valuation (FIFO / avg)        | Slice 2 (avg only), Slice 4 (FIFO) | Avg = simple; FIFO = layer tracking. |
| Lot / serial tracking         | Slice 4          | Adds `Lot` table + `tracking_level` enum.           |
| Reservations (soft allocation)| Slice 1.5        | Concurrency + backorder complexity.                  |
| `stock_item_balance` cache    | Slice 1.5        | Premature optimization until volume justifies.       |
| Offline-first PWA             | Slice 2 / Phase 1b | Mobile BL capture. Not a v1 gate.                   |
| OCR on BL photo               | Slice 4+         | ML tuning takes months; chef can type "95" faster.  |
| WhatsApp notifications        | Phase 1c         | After core capture works.                            |
| Multi-chantier in-transit     | Slice 2          | The TRANSFER move is simple A→B for now.             |
| Cash ↔ stock bidirectional link| Slice 2 / 3     | Wired when BC/BL/facture introduce the commercial-doc layer. |
| `stock_move.document_id` DB FK | Slice 1.2       | The document table had RLS quirks (V16); kept nullable for now. |

---

## 10. Migration story (additive, never destructive)

V30 is purely **additive**:
- 4 new tables (`stock_item`, `item_alias`, `stock_location`, `stock_move`)
- 2 new views (`stock_balance`, `stock_balance_org`)
- 1 new trigger (`stock_move_append_only`)
- 3 new permissions (`STOCK_VIEW`, `STOCK_MOVE`, `STOCK_ADMIN`)
- New role_permission grants (additive only — never revokes existing)
- 0 changes to existing tables
- 0 changes to existing APIs

Future V31+ migrations will:
- Add `cash_movement.stock_move_id` (nullable) when the cash ↔ stock link is wired.
- Add the BC/BL/facture tables (Slice 2/3).
- Add the STOCK subfolders to `doc_folder_template` (Slice 1.2).

---

## 11. How to run the migration

The migration is applied automatically by Spring Boot's Flyway integration
on application startup. To run it manually:

```bash
# Standalone (skip the duplicate V26 issue with -X flag for repair)
./mvnw flyway:migrate \
  -Dflyway.url=jdbc:postgresql://127.0.0.1:5432/hydra \
  -Dflyway.user=postgres \
  -Dflyway.password=hydra \
  -Dflyway.locations=filesystem:src/main/resources/db/migration

# Via Spring Boot (preferred — handles the duplicate V26 gracefully)
DB_URL=jdbc:postgresql://127.0.0.1:5432/hydra \
DB_USER=postgres \
DB_PASSWORD=hydra \
OWNER_EMAIL=owner@hydra.tn \
OWNER_PASSWORD=HydraOwner2026! \
./mvnw spring-boot:run
```

After migration, verify:

```sql
-- Tables
\dt stock_*
\dt item_alias

-- Views
\dv stock_balance
\dv stock_balance_org

-- Trigger
\df block_stock_move_mutation

-- Permissions
select id, description from permission where id like 'STOCK%';

-- Role grants
select r.code, array_agg(rp.permission_id order by rp.permission_id) as perms
from role r
join role_permission rp on rp.role_id = r.id
where r.org_id is null
  and rp.permission_id like 'STOCK%'
group by r.code
order by r.code;
```

---

## 12. Design idioms reused from existing HYDRA code

| Idiom                                | Source                              | Reused in V30                              |
|--------------------------------------|-------------------------------------|--------------------------------------------|
| `gen_random_uuid()` PK               | V1, V5, V20                         | All 4 stock tables                         |
| `org_id NOT NULL` + tenant isolation | V1                                  | All 4 stock tables                         |
| `created_by` FK to `app_user(id)`   | V20 (`cash_movement`)               | All 4 stock tables                         |
| `chk_*` CHECK constraint naming      | V20 (`chk_movement_in_pots`)        | `chk_stock_move_locations`, `chk_location_central_no_project` |
| `DO $$ ... EXCEPTION WHEN duplicate_object` | V20                          | All CHECK constraints                      |
| `DISABLE ROW LEVEL SECURITY`         | V20 §5                              | All 4 stock tables                         |
| Append-only trigger (BEFORE UPDATE/DELETE) | V1 (`event_log`)              | `stock_move_append_only`                   |
| `ON CONFLICT DO NOTHING` for seeds   | V14, V22                            | Permission + role_permission inserts       |
| `@AssignedUuid` + `Persistable<UUID>`| `Document`, `Ticket`               | All 4 entities                             |
| `Auditable` MappedSuperclass         | `Document`                          | `StockItem` (others have lighter auditing — see code) |
| JPA `@Query` with JPQL + `@Param`    | `DocumentRepository`               | All 4 repositories                         |
| Native query with `CASE` for balance | n/a (new)                           | `StockMoveRepository.signedBalance`        |

---

## 13. Next slice preview (1.2 — Documents + folder convention)

When Slice 1 proves itself on one real chantier with BL photos attached
to `stock_move.document_id`, Slice 1.2 will:

1. Add V31 migration: insert STOCK subfolders (`05.01 BL`, etc.) into `doc_folder_template`.
2. Backfill: create them under `05 ACHAT` for all existing projects.
3. Extend `ProjectService.ensureFolders` to clone them on new projects.
4. Add `StockController.uploadAttachment(moveId, multipartFile)` — uses
   `DocumentService.upload()` with `folderId` resolution.
5. The `stock_move.document_id` becomes a real FK once we're sure the
   `document` table is stable (post-V16 RLS fix).

No entity changes — just wiring.
