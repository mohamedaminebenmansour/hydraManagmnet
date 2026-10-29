-- ============================================================================
-- V30: Virtual Stock — Slice 1 (Part 1.1)
--
-- GOAL
--   Add the *data substrate* for stock visibility across all chantier
--   (SITE) locations + the org-wide CENTRAL dépôt.
--
--   Virtual stock = sum(stock_moves) per (item, location). Nothing is ever
--   UPDATEd as a source of truth; corrections are appended as opposite moves.
--   This is the same append-only philosophy as cash_movement (V20).
--
--   Slice 1 = items + locations + moves ONLY. No BC/BL/facture entities yet,
--   no 3-way match, no valuation (FIFO/avg), no reservations, no offline cache.
--   See backend/src/main/java/tn/hydra/stock/ARCHITECTURE.md for the full
--   scope and what is intentionally deferred.
--
-- IDIOMS REUSED
--   - uuid primary key, default gen_random_uuid()         (V1, V5, V20)
--   - org_id NOT NULL references organization(id)         (V1, V20)
--   - appended_by uuid not null references app_user(id)   (V20 created_by)
--   - CHECK constraints + named via chk_* prefix         (V20)
--   - DO $$ ... EXCEPTION WHEN duplicate_object           (V20)
--   - DISABLE ROW LEVEL SECURITY to match cash tables    (V20 §5)
--     (RLS with zero policies would block all reads; the
--      app enforces org_id isolation in the service layer,
--      exactly like cash_movement.)
--   - ON CONFLICT DO NOTHING for permission/role seeds   (V14, V22)
--
-- NOT IN SCOPE (deferred — Part 1.2+)
--   - BC (purchase order) entity + lines
--   - BL (goods receipt) entity + lines
--   - Facture entity + lines
--   - 3-way match service (synchronous function — pure read)
--   - Cash ↔ stock bidirectional links (kept nullable now; wired later)
--   - Valuation engine (FIFO, weighted average)
--   - Lot / serial tracking
--   - Reservations (soft allocation)
--   - stock_item cache table (balances stay SUM(moves) for now)
--   - Offline sync (PWA — comes with the mobile BL capture slice)
-- ============================================================================


-- ============================================================================
-- 1. stock_item — minimal catalogue of materials.
--
--    Deliberately THIN per Slice 1 spec: code, name, uom, active.
--    NO family / diameter / length / weight / value_class for now — those
--    belong to a future Product master and are explicitly out of scope.
--
--    The (org_id, code) unique index uses a partial form (code IS NOT NULL)
--    so that items without a code (chef-de-chantier-created on the fly) can
--    coexist with the coded catalogue — same pattern V9 used to fix the
--    doc_folder_template NULL org_id bug.
-- ============================================================================
CREATE TABLE IF NOT EXISTS stock_item (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid not null references organization(id),
    code        text,                                  -- optional supplier/internal code
    name        text not null,
    uom         text not null default 'U',             -- "sac", "m3", "m", "kg", "L", "U"
    active      boolean not null default true,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    created_by  uuid not null references app_user(id)
);

-- One (org, code) per active item. NULL codes allowed (multiple items can
-- have code = NULL — the chef-de-chantier "I just typed a name" case).
CREATE UNIQUE INDEX IF NOT EXISTS uq_stock_item_org_code
    ON stock_item (org_id, code) WHERE code IS NOT NULL;

-- List-filtering index.
CREATE INDEX IF NOT EXISTS idx_stock_item_org_active_name
    ON stock_item (org_id, active, name);

COMMENT ON TABLE stock_item IS
    'Material catalogue (Slice 1: thin). One row per (org, code) when code is set; otherwise one row per name.';


-- ============================================================================
-- 2. item_alias — supplier-side aliases for the same stock_item.
--
--    Tunisian BTP reality: the same bag of cement is called "Ciment CJS II-32.5"
--    by the chef de chantier, "CIM/25/BIZ" by the supplier Cimentex, and
--    "le gris 25" by the chef d'équipe. Without aliases the office creates
--    duplicate stock_item rows and the balance view fragments.
--
--    Slice 1 keeps this table optional and read by the item-search helper
--    only (typed lookup). No "fuzzy match" logic in this slice.
-- ============================================================================
CREATE TABLE IF NOT EXISTS item_alias (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid not null references organization(id),
    item_id     uuid not null references stock_item(id) on delete cascade,
    alias       text not null,
    created_at  timestamptz not null default now(),
    created_by  uuid references app_user(id)
);

-- One alias text per (org, item) — but multiple aliases per item are allowed.
CREATE UNIQUE INDEX IF NOT EXISTS uq_item_alias_org_text
    ON item_alias (org_id, lower(alias));

CREATE INDEX IF NOT EXISTS idx_item_alias_item
    ON item_alias (item_id);

COMMENT ON TABLE item_alias IS
    'Supplier / chantier-side aliases for a stock_item. Slice 1: read-only lookup.';


-- ============================================================================
-- 3. stock_location — where material lives.
--
--    Two types in Slice 1:
--      CENTRAL — org-level dépôt (project_id IS NULL). Typically ONE per org.
--      SITE    — a chantier location (project_id NOT NULL).
--
--    A SITE location may be the chantier itself (the "on the ground" stock)
--    or a sub-location (the site magasin). parent_id lets us nest later,
--    but Slice 1 treats every SITE as flat (parent_id NULL).
--
--    CENTRAL visibility policy (Part 1.1 decision, documented):
--      "CENTRAL is visible to every member of any project in the org."
--      This is enforced in the service layer (StockService.listLocationsForUser)
--      by checking that the caller has at least one project_member row in the
--      org, OR is the owner, OR has the STOCK_VIEW permission directly.
--      RLS is OFF for stock_* tables (matching cash), so the check is app-side.
-- ============================================================================
CREATE TABLE IF NOT EXISTS stock_location (
    id          uuid primary key default gen_random_uuid(),
    org_id      uuid not null references organization(id),
    type        text not null check (type in ('CENTRAL','SITE')),
    project_id  uuid references project(id),           -- NULL when type = CENTRAL
    code        text,                                  -- optional code ("DEPOT-CENTRAL", "PRJ-CARTHAGE-SITE")
    name        text not null,                         -- "Dépôt central", "Chantier Carthage"
    active      boolean not null default true,
    created_at  timestamptz not null default now(),
    created_by  uuid not null references app_user(id)
);

-- Enforce type / project_id consistency at DB level (defence in depth).
ALTER TABLE stock_location DROP CONSTRAINT IF EXISTS chk_location_central_no_project;
DO $$ BEGIN
    ALTER TABLE stock_location
        ADD CONSTRAINT chk_location_central_no_project CHECK (
            (type = 'CENTRAL' AND project_id IS NULL)
         OR (type = 'SITE'    AND project_id IS NOT NULL)
        );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- One active CENTRAL location per org.
CREATE UNIQUE INDEX IF NOT EXISTS uq_stock_location_central_active
    ON stock_location (org_id) WHERE type = 'CENTRAL' AND active = true;

-- One active SITE per (org, project, code) by default. Additional site sub-locations
-- (magasin on a chantier) would need code to disambiguate — Slice 1 assumes
-- one SITE per project. Use code = NULL co-existence pattern.
CREATE UNIQUE INDEX IF NOT EXISTS uq_stock_location_site_org_project_code
    ON stock_location (org_id, project_id, code) WHERE type = 'SITE' AND active = true;

CREATE INDEX IF NOT EXISTS idx_stock_location_org_active
    ON stock_location (org_id, active);
CREATE INDEX IF NOT EXISTS idx_stock_location_project
    ON stock_location (project_id) WHERE project_id IS NOT NULL;

COMMENT ON TABLE stock_location IS
    'Where material lives. CENTRAL = org dépôt (project_id NULL); SITE = chantier (project_id NOT NULL).';


-- ============================================================================
-- 4. stock_move — APPEND-ONLY ledger. THE KEYSTONE.
--
--    Source of truth for "what material is where". Balances are ALWAYS
--    computed: balance(item, location) = SUM(signed_qty) over moves affecting
--    that location. Never UPDATE a row to fix a mistake — append an opposite
--    move (the same pattern as cash_movement VOID + new).
--
--    TYPES (signed_qty computed by the service via the convention below):
--      IN           — increase at to_location_id.            from_location_id = NULL.
--      OUT          — decrease at from_location_id.          to_location_id   = NULL.
--      TRANSFER     — decrease at from, increase at to.       both NOT NULL. (Simple A→B
--                     in one step. No in-transit state machine — that's Slice 2+.)
--      DIRECT_USE   — like OUT but consumed immediately (no magasin transit). For
--                     the carburant / bon de carburant case: cash slip → consumed.
--                     Same signing as OUT.
--      ADJUST       — inventory count variance. qty can be + (found more) or - (missing).
--                     Uses from_location_id only (the location being adjusted).
--
--    SIGNING (computed by the service — the DB does not store a signed column):
--      IN:           +qty at to_location_id
--      OUT:          -qty at from_location_id
--      TRANSFER:     -qty at from, +qty at to
--      DIRECT_USE:   -qty at from
--      ADJUST:       +qty (or -qty if qty is negative) at from_location_id
--
--    NEGATIVE STOCK POLICY (Slice 1 decision):
--      HARD BLOCK by default. The service rejects a move that would push a
--      location's balance below zero with a BusinessRuleException
--      (error code STOCK_NEGATIVE_BALANCE).
--      Override is a future Slice 1.5 concern ("soft allow + warning flag")
--      and is intentionally NOT in this migration.
--
--    OPTIONAL NULLABLE FKs (wired later, designed now):
--      document_id      — when a BL/BC/facture photo is attached.
--                         References document(id) — NOT a FK yet because
--                         the document table may have its own RLS quirks
--                         (V16 disabled RLS). The service validates the
--                         document exists in the same org before linking.
--      project_id       — denormalized from location for fast "all moves
--                         for chantier X" queries. Set to from_location.project_id
--                         for OUT/TRANSFER/DIRECT_USE/ADJUST, to_location.project_id
--                         for IN. NULL when both ends are CENTRAL.
--      party_id         — supplier / sous-traitant link. References party(id).
--      cash_movement_id — the money side, when this move was triggered by
--                         or triggers a cash event. References cash_movement(id).
--                         NULL for TRANSFER (no money moves) and ADJUST.
-- ============================================================================
CREATE TABLE IF NOT EXISTS stock_move (
    id                uuid primary key default gen_random_uuid(),
    org_id            uuid not null references organization(id),
    move_type         text not null check (move_type in ('IN','OUT','TRANSFER','ADJUST','DIRECT_USE')),
    qty               numeric(18,3) not null check (qty > 0),  -- always positive; sign comes from move_type
    uom               text not null default 'U',
    move_date         date not null default current_date,
    -- Location side
    from_location_id  uuid references stock_location(id),
    to_location_id    uuid references stock_location(id),
    -- Item side
    item_id           uuid not null references stock_item(id),
    -- Optional cross-domain links (designed now, wired in later slices)
    document_id       uuid,                                  -- -> document(id), app-validated
    project_id        uuid references project(id),           -- denormalized for fast per-chantier queries
    party_id          uuid references party(id),             -- supplier / sous-traitant
    cash_movement_id  uuid,                                  -- -> cash_movement(id), app-validated
    -- Audit
    note              text,
    created_by        uuid not null references app_user(id),
    created_at        timestamptz not null default now()
);

-- DB-level guards for type / location presence (mirrors V20 chk_movement_in_pots).
ALTER TABLE stock_move DROP CONSTRAINT IF EXISTS chk_stock_move_locations;
DO $$ BEGIN
    ALTER TABLE stock_move
        ADD CONSTRAINT chk_stock_move_locations CHECK (
            (move_type = 'IN'       AND to_location_id IS NOT NULL   AND from_location_id IS NULL)
         OR (move_type = 'OUT'      AND from_location_id IS NOT NULL AND to_location_id IS NULL)
         OR (move_type = 'TRANSFER' AND from_location_id IS NOT NULL AND to_location_id IS NOT NULL)
         OR (move_type = 'DIRECT_USE' AND from_location_id IS NOT NULL AND to_location_id IS NULL)
         OR (move_type = 'ADJUST'   AND from_location_id IS NOT NULL AND to_location_id IS NULL)
        );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- TRANSFER: from ≠ to.
ALTER TABLE stock_move DROP CONSTRAINT IF EXISTS chk_stock_move_transfer_distinct;
DO $$ BEGIN
    ALTER TABLE stock_move
        ADD CONSTRAINT chk_stock_move_transfer_distinct CHECK (
            move_type <> 'TRANSFER' OR from_location_id <> to_location_id
        );
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- Indexes — same family as cash_movement.
CREATE INDEX IF NOT EXISTS idx_sm_org_date
    ON stock_move (org_id, move_date desc);
CREATE INDEX IF NOT EXISTS idx_sm_item
    ON stock_move (item_id);
CREATE INDEX IF NOT EXISTS idx_sm_from_loc
    ON stock_move (from_location_id);
CREATE INDEX IF NOT EXISTS idx_sm_to_loc
    ON stock_move (to_location_id);
CREATE INDEX IF NOT EXISTS idx_sm_project
    ON stock_move (project_id) WHERE project_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sm_party
    ON stock_move (party_id) WHERE party_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sm_cash
    ON stock_move (cash_movement_id) WHERE cash_movement_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sm_document
    ON stock_move (document_id) WHERE document_id IS NOT NULL;

COMMENT ON TABLE stock_move IS
    'Append-only stock ledger. Balances are computed: SUM(signed qty) per (item, location). Never UPDATE in place.';


-- ============================================================================
-- 5. Append-only trigger for stock_move (mirrors event_log V1 §1).
--
--    Once a stock_move is committed, it CANNOT be edited or deleted.
--    Corrections = a new opposite move. This is the SYSCOHADA / accrual
--    pattern (same as cash_movement POSTED).
-- ============================================================================
CREATE OR REPLACE FUNCTION block_stock_move_mutation() returns trigger as $$
begin
  raise exception 'stock_move is append-only: % is not allowed', tg_op;
end;
$$ language plpgsql;

DROP TRIGGER IF EXISTS stock_move_append_only ON stock_move;
CREATE TRIGGER stock_move_append_only
  BEFORE UPDATE OR DELETE ON stock_move
  FOR EACH ROW EXECUTE FUNCTION block_stock_move_mutation();

COMMENT ON TRIGGER stock_move_append_only ON stock_move IS
    'Defence-in-depth: app layer enforces append-only; DB refuses any UPDATE/DELETE.';


-- ============================================================================
-- 6. stock_balance view — convenience projection.
--
--    Pure SUM(signed qty) grouped by (item, location). Not materialised;
--    recomputed on every read. For Slice 1 with ~thousands of moves per
--    chantier this is fast enough. If volume grows, we add a cache table
--    (stock_item_balance) updated by trigger — that's a Part 1.2 task.
--
--    The view exposes both quantity and signed contribution per move so a
--    UI can render a "Fiche de stock" (running balance).
-- ============================================================================
CREATE OR REPLACE VIEW stock_balance AS
select
    m.org_id,
    m.item_id,
    loc.id              as location_id,
    loc.type            as location_type,
    loc.project_id,
    coalesce(sum(
        case
            when m.move_type = 'IN'         then m.qty
            when m.move_type = 'OUT'        then -m.qty
            when m.move_type = 'DIRECT_USE' then -m.qty
            when m.move_type = 'TRANSFER'   then
                case
                    when m.from_location_id = loc.id then -m.qty
                    when m.to_location_id   = loc.id then  m.qty
                    else 0
                end
            when m.move_type = 'ADJUST'     then m.qty
        end
    ), 0) as balance_qty
from stock_move m
-- Cross join with locations; the CASE filters irrelevant ones to 0.
cross join stock_location loc
where (m.from_location_id = loc.id or m.to_location_id = loc.id)
group by m.org_id, m.item_id, loc.id, loc.type, loc.project_id;

COMMENT ON VIEW stock_balance IS
    'Computed per (item, location) balance. Read-only projection of stock_move.';

-- A second view: per-item totals across the whole org (the owner dashboard query).
CREATE OR REPLACE VIEW stock_balance_org AS
select
    org_id,
    item_id,
    sum(balance_qty) as total_qty
from stock_balance
group by org_id, item_id;

COMMENT ON VIEW stock_balance_org IS
    'Per-item total quantity across all locations in the org. Owner dashboard query.';


-- ============================================================================
-- 7. Disable RLS — matching the V20 §5 pattern.
--
--    RLS with zero policies blocks every read. The service layer enforces
--    org_id isolation (every query filters by caller's org_id), exactly like
--    cash_movement / cash_pot / party. See StockService.listForCaller.
-- ============================================================================
ALTER TABLE stock_item     DISABLE ROW LEVEL SECURITY;
ALTER TABLE item_alias     DISABLE ROW LEVEL SECURITY;
ALTER TABLE stock_location DISABLE ROW LEVEL SECURITY;
ALTER TABLE stock_move     DISABLE ROW LEVEL SECURITY;


-- ============================================================================
-- 8. Permissions — 3 new (matches CASH_* / FOLDER_* style).
--
--    STOCK_VIEW  : see items, locations, balances, moves (read-only)
--    STOCK_MOVE  : post a move (IN / OUT / TRANSFER / ADJUST / DIRECT_USE)
--    STOCK_ADMIN : create / edit items, locations, aliases
--
--    Permission IDs are stored raw; the Java side prefixes them with PERM_
--    at load time (HydraUserDetailsService). OWNER gets all permissions
--    implicitly via allPermissions() but we also insert rows so /api/roles
--    reflects the grant.
-- ============================================================================
insert into permission (id, description) values
 ('STOCK_VIEW', 'Voir le stock (articles, emplacements, soldes, mouvements)'),
 ('STOCK_MOVE', 'Saisir un mouvement de stock (entrée / sortie / transfert / ajustement)'),
 ('STOCK_ADMIN', 'Créer et modifier les articles, emplacements et alias stock')
on conflict (id) do nothing;


-- ============================================================================
-- 9. Role grants — additive, idempotent.
--
--    OWNER       — everything (also implicit via allPermissions())
--    FINANCE     — VIEW + MOVE  (cash + stock correlate; finance tracks both)
--    CHEF_PROJET — VIEW + MOVE  (the chef drafts moves on his chantier)
--    CONDUCTEUR  — VIEW + MOVE  (the conducteur does the gate capture)
--    ACHAT       — VIEW + MOVE  (the purchasing role initiates IN)
--    CHEF_CHANTIER — VIEW + MOVE (site lead — restricted in service layer
--                                  to his own project's locations)
--    BUREAU      — VIEW only    (oversight, no mutations)
--    QA          — VIEW only    (auditor)
--    DIRECTION   — VIEW only    (DG adjoint oversight)
--    INGENIEUR   — VIEW only    (reads what's needed for plans)
--    HSE         — VIEW only    (hazardous-material visibility)
--    DOC_CTRL    — VIEW only    (links documents to moves)
--    SOUS_TRAITANT — nothing    (stock visibility is internal; sous-traitant
--                                sees only his own factures via the cash module)
-- ============================================================================
insert into role_permission (role_id, permission_id)
select r.id, p.id
from role r cross join permission p
where r.org_id is null
  and (
    r.code = 'OWNER'  -- OWNER gets all three

    or (r.code in ('FINANCE','CHEF_PROJET','CONDUCTEUR','ACHAT','CHEF_CHANTIER')
        and p.id in ('STOCK_VIEW','STOCK_MOVE'))

    or (r.code in ('BUREAU','QA','DIRECTION','INGENIEUR','HSE','DOC_CTRL')
        and p.id = 'STOCK_VIEW')
  )
on conflict (role_id, permission_id) do nothing;


-- ============================================================================
-- 10. STOCK DOCUMENT FOLDER CONVENTION (documented, not auto-created).
--
--    Slice 1 does NOT redesign the DMS. The existing folder template (V8)
--    has 7 top-level folders per project:
--      01 ADMIN, 02 TECH, 03 EXEC, 04 QUAL, 05 ACHAT, 06 FIN, 07 INT
--
--    The convention for stock documents (BL photos, BC scans, facture PDFs):
--      put them under "05 ACHAT" with these SUBFOLDER codes:
--          05.01 BL       — Bons de livraison (delivery notes)
--          05.02 BC       — Bons de commande (purchase orders)
--          05.03 FACTURE  — Factures fournisseurs
--          05.04 AUTRE    — Attestations, bons de carburant, photos diverses
--
--    WHY under 05 ACHAT: it already exists for every project (cloned by
--    ProjectService.ensureFolders on project create), and purchase documents
--    belong naturally to the "Achats" workspace. Adding a sibling "08 STOCK"
--    would require a doc_folder_template migration + a ProjectService
--    update — deferred to Part 1.2 when we wire document uploads to moves.
--
--    Part 1.2 will:
--      1. Add a V31 migration that inserts the 4 STOCK subfolders into
--         doc_folder_template (org_id NULL = global).
--      2. Extend ProjectService.ensureFolders to clone them on new projects.
--      3. Provide a backfill SQL that creates them for existing projects
--         (idempotent ON CONFLICT DO NOTHING by (project_id, parent_id, code)).
--      4. The stock_move.document_id FK will then point at documents living
--         in those folders, and the StockController upload endpoint can use
--         the existing DocumentService.upload() with folderId resolution.
--
--    For Slice 1, the convention is purely a documentation rule: humans
--    uploading BL photos today pick the right folder; the schema is ready.
-- ============================================================================


-- End V30 — Virtual Stock Slice 1
