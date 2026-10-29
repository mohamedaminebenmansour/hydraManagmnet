package tn.hydra.stock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tn.hydra.audit.EventLogService;
import tn.hydra.common.BusinessRuleException;
import tn.hydra.exception.BadRequestException;
import tn.hydra.exception.ResourceNotFoundException;
import tn.hydra.stock.domain.ItemAlias;
import tn.hydra.stock.domain.StockItem;
import tn.hydra.stock.domain.StockLocation;
import tn.hydra.stock.domain.StockLocationType;
import tn.hydra.stock.domain.StockMove;
import tn.hydra.stock.domain.StockMoveType;
import tn.hydra.stock.repository.ItemAliasRepository;
import tn.hydra.stock.repository.StockItemRepository;
import tn.hydra.stock.repository.StockLocationRepository;
import tn.hydra.stock.repository.StockMoveRepository;
import tn.hydra.user.AppUser;
import tn.hydra.user.AppUserRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ============================================================================
 *  StockService — virtual stock: items, locations, balances, moves.
 * ============================================================================
 *
 *  DESIGN (mirrors CashService idioms — same app, same rules):
 *
 *   - Virtual stock = SUM(signed qty) per (item, location) over the
 *     append-only stock_move ledger. Balances are never stored as a
 *     mutable field (the stock_balance view + signedBalance() compute them).
 *
 *   - stock_move is APPEND-ONLY (DB trigger + service convention).
 *     Mistakes are corrected with a compensating move (see {@link #reverse}),
 *     never by editing or deleting a row.
 *
 *   - SECURITY (two layers, like cash):
 *       1. Controller @PreAuthorize — PERM_STOCK_VIEW / PERM_STOCK_MOVE /
 *          PERM_STOCK_ADMIN (+ ROLE_OWNER always passes).
 *       2. Service-layer checks here — org isolation on every query,
 *          location-access rules, document-project access.
 *
 *   - LOCATION ACCESS (Part 1.1 policy, applied consistently):
 *       SITE    → caller must be a project_member of the location's project
 *                 (or the owner). IDOR-safe: a user on project A can neither
 *                 see nor post on project B's site locations.
 *       CENTRAL → org-level dépôt, visible/actionable to any caller holding
 *                 the relevant STOCK_* permission in the org (owner, oversight
 *                 roles with STOCK_VIEW, members of any project with the move
 *                 perm). Chosen in Part 1.1 and documented in ARCHITECTURE.md §6.
 *
 *   - AUDIT: every mutation writes an event_log row (who / when / type /
 *     qty / from / to / documentId / projectId) — enough to answer
 *     "who moved what where" without touching the move rows.
 *
 *  PART 2 EXTENSION POINT (V31 §3, comment-only):
 *     postMove() is the single insertion point where BC/BL lineage
 *     (bc_line_id / bl_line_id / bordereau_article_id) will be resolved
 *     and stamped onto the move. The PostMoveRequest record will gain
 *     optional typed fields then; nothing else changes.
 *
 * ============================================================================
 */
@Service
public class StockService {

    private final JdbcTemplate jdbc;
    private final AppUserRepository users;
    private final EventLogService audit;
    private final StockItemRepository items;
    private final ItemAliasRepository aliases;
    private final StockLocationRepository locations;
    private final StockMoveRepository moves;

    public StockService(JdbcTemplate jdbc, AppUserRepository users, EventLogService audit,
                        StockItemRepository items, ItemAliasRepository aliases,
                        StockLocationRepository locations, StockMoveRepository moves) {
        this.jdbc = jdbc;
        this.users = users;
        this.audit = audit;
        this.items = items;
        this.aliases = aliases;
        this.locations = locations;
        this.moves = moves;
    }

    // ══════════════════════════════════════════════════════════════
    //  REQUEST / RESPONSE RECORDS
    // ══════════════════════════════════════════════════════════════

    public record ItemResponse(UUID id, UUID orgId, String code, String name,
                               String uom, boolean active,
                               java.time.Instant createdAt) {}

    public record AliasResponse(UUID id, UUID itemId, String alias, java.time.Instant createdAt) {}

    public record LocationResponse(UUID id, UUID orgId, String type, UUID projectId,
                                   String projectName, String code, String name,
                                   boolean active) {}

    /** One balance row: item × location × qty. */
    public record BalanceRow(UUID itemId, String itemName, String itemCode, String uom,
                             UUID locationId, String locationName, String locationType,
                             UUID projectId, BigDecimal qty) {}

    /** Company-wide (org) aggregation: per-item total across all locations. */
    public record OrgBalanceRow(UUID itemId, String itemName, String itemCode, String uom,
                                BigDecimal totalQty) {}

    public record MoveResponse(UUID id, UUID orgId, String moveType, BigDecimal qty, String uom,
                               LocalDate moveDate, UUID fromLocationId, String fromLocationName,
                               UUID toLocationId, String toLocationName, UUID itemId,
                               String itemName, UUID documentId, UUID projectId,
                               UUID partyId, UUID cashMovementId, String note,
                               UUID createdBy, String createdByName, java.time.Instant createdAt) {}

    public record MovePage(List<MoveResponse> items, long total, int page, int size) {}

    public record CreateItemRequest(String code, String name, String uom) {}
    public record UpdateItemRequest(String code, String name, String uom, Boolean active) {}
    public record CreateAliasRequest(String alias) {}
    public record CreateLocationRequest(String type, UUID projectId, String code, String name) {}

    /**
     * Post a move. Validations per type:
     *   IN          → toLocationId required; fromLocationId must be null.
     *   OUT         → fromLocationId required; toLocationId must be null.
     *   DIRECT_USE  → same shape as OUT.
     *   TRANSFER    → both required and distinct.
     *   ADJUST      → fromLocationId required; qty SIGNED (may be negative for
     *                 inventory variance; never zero).
     *
     * Optional documentId must belong to a project the caller can access.
     * Optional metadata is IGNORED in 1.2 — Part 2 extension point (see
     * class javadoc); accepted so the API contract will not break.
     */
    public record PostMoveRequest(String moveType, BigDecimal qty, String uom,
                                  LocalDate moveDate,
                                  UUID fromLocationId, UUID toLocationId,
                                  UUID itemId, UUID documentId, UUID partyId,
                                  UUID cashMovementId, String note,
                                  Map<String, Object> metadata) {}

    public record ReverseRequest(String reason) {}
    public record LinkDocumentRequest(UUID documentId) {}

    // ══════════════════════════════════════════════════════════════
    //  CALLER / SECURITY HELPERS  (CashService style)
    // ══════════════════════════════════════════════════════════════

    private AppUser currentUser(Authentication auth) {
        return users.findByEmail(auth.getName())
                .orElseThrow(() -> new IllegalStateException("Unknown user: " + auth.getName()));
    }

    private boolean hasPermission(Authentication auth, String perm) {
        String authority = "PERM_" + perm;
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(authority::equals);
    }

    /** Throw FORBIDDEN if caller lacks the perm (409 JSON, same as cash module). */
    private void requirePermission(Authentication auth, String perm, String action) {
        if (!hasPermission(auth, perm)) {
            throw new BusinessRuleException("FORBIDDEN",
                    "Permission manquante: PERM_" + perm + " — action: " + action);
        }
    }

    private boolean isProjectMember(UUID userId, UUID projectId) {
        Integer cnt = jdbc.queryForObject(
                "select count(*) from project_member where project_id = ? and user_id = ?",
                Integer.class, projectId, userId);
        return cnt != null && cnt > 0;
    }

    private boolean isMemberOfAnyProject(UUID userId, UUID orgId) {
        Integer cnt = jdbc.queryForObject("""
                select count(*) from project_member pm
                join project p on p.id = pm.project_id
                where pm.user_id = ? and p.org_id = ?
                """, Integer.class, userId, orgId);
        return cnt != null && cnt > 0;
    }

    /**
     * Location access rule (Part 1.1 policy — see class javadoc):
     *   SITE    → owner OR project member of the location's project.
     *   CENTRAL → any caller that passed the STOCK_* permission gate
     *             (owner / oversight role / project member of any project).
     *             Enforced implicitly by the controller gate + org isolation;
     *             made explicit here for defence-in-depth.
     */
    private void assertLocationAccess(Authentication auth, AppUser me, StockLocation loc, String action) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        if (loc.getType() == StockLocationType.SITE) {
            if (loc.getProjectId() == null || !isProjectMember(me.getId(), loc.getProjectId())) {
                throw new BusinessRuleException("FORBIDDEN",
                        "Accès refusé à l'emplacement chantier — action: " + action);
            }
            return;
        }
        // CENTRAL: caller must be a member of at least one project in the org.
        if (!isMemberOfAnyProject(me.getId(), me.getOrgId())) {
            throw new BusinessRuleException("FORBIDDEN",
                    "Accès refusé au dépôt central — action: " + action);
        }
    }

    /** Org-scoped fetch or 404. */
    private StockItem itemOrNotFound(UUID orgId, UUID id) {
        return items.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("ITEM_NOT_FOUND", "Article introuvable"));
    }

    private StockLocation locationOrNotFound(UUID orgId, UUID id) {
        return locations.findByIdAndOrgId(id, orgId)
                .orElseThrow(() -> new ResourceNotFoundException("LOCATION_NOT_FOUND", "Emplacement introuvable"));
    }

    // ══════════════════════════════════════════════════════════════
    //  ITEMS
    // ══════════════════════════════════════════════════════════════

    public List<ItemResponse> listItems(Authentication auth, boolean includeInactive, String q) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_VIEW", "list-items");
        List<StockItem> rows;
        if (q != null && !q.isBlank()) {
            rows = items.searchActiveByName(me.getOrgId(), q.trim());
            // Alias match: an alias hit maps to its item (dedup by id).
            List<UUID> aliasItemIds = jdbc.queryForList("""
                    select item_id from item_alias where org_id = ? and lower(alias) like ?
                    """, UUID.class, me.getOrgId(), "%" + q.trim().toLowerCase() + "%");
            for (UUID aid : aliasItemIds) {
                items.findByIdAndOrgId(aid, me.getOrgId())
                        .filter(i -> i.isActive() || includeInactive)
                        .ifPresent(i -> { if (!containsId(rows, i.getId())) rows.add(i); });
            }
        } else if (includeInactive) {
            rows = items.findActiveByOrgId(me.getOrgId()); // repo sorted; inactive appended below
            jdbc.queryForList("select id from stock_item where org_id = ? and active = false", UUID.class, me.getOrgId())
                    .forEach(id -> items.findByIdAndOrgId(id, me.getOrgId()).ifPresent(rows::add));
        } else {
            rows = items.findActiveByOrgId(me.getOrgId());
        }
        return rows.stream().map(StockService::toItemResponse).toList();
    }

    private static boolean containsId(List<StockItem> list, UUID id) {
        return list.stream().anyMatch(i -> i.getId().equals(id));
    }

    @Transactional
    public ItemResponse createItem(Authentication auth, CreateItemRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_ADMIN", "create-item");
        if (req.name() == null || req.name().isBlank()) {
            throw new BadRequestException("INVALID_NAME", "Le nom de l'article est requis");
        }
        if (req.code() != null && !req.code().isBlank()
                && items.findByOrgIdAndCodeIgnoreCase(me.getOrgId(), req.code().trim()).isPresent()) {
            throw new BusinessRuleException("ITEM_CODE_EXISTS",
                    "Un article avec ce code existe déjà: " + req.code());
        }
        StockItem item = new StockItem();
        item.setOrgId(me.getOrgId());
        item.setCode(req.code() != null && !req.code().isBlank() ? req.code().trim() : null);
        item.setName(req.name().trim());
        item.setUom(req.uom() != null && !req.uom().isBlank() ? req.uom().trim() : "U");
        item.setActive(true);
        item.markNew();
        items.save(item);
        audit.log("STOCK_ITEM_CREATED", "stock_item", item.getId(),
                "{\"name\":\"" + esc(item.getName()) + "\",\"code\":" + jsonOrNull(item.getCode()) + "}");
        return toItemResponse(item);
    }

    @Transactional
    public ItemResponse updateItem(Authentication auth, UUID id, UpdateItemRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_ADMIN", "update-item");
        StockItem item = itemOrNotFound(me.getOrgId(), id);
        if (req.name() != null && !req.name().isBlank()) item.setName(req.name().trim());
        if (req.uom() != null && !req.uom().isBlank()) item.setUom(req.uom().trim());
        if (req.code() != null) {
            String newCode = req.code().isBlank() ? null : req.code().trim();
            if (newCode != null) {
                Optional<StockItem> clash = items.findByOrgIdAndCodeIgnoreCase(me.getOrgId(), newCode);
                if (clash.isPresent() && !clash.get().getId().equals(id)) {
                    throw new BusinessRuleException("ITEM_CODE_EXISTS",
                            "Un article avec ce code existe déjà: " + newCode);
                }
            }
            item.setCode(newCode);
        }
        if (req.active() != null) item.setActive(req.active());
        items.save(item);
        audit.log("STOCK_ITEM_UPDATED", "stock_item", item.getId(),
                "{\"name\":\"" + esc(item.getName()) + "\",\"active\":" + item.isActive() + "}");
        return toItemResponse(item);
    }

    // ── aliases ─────────────────────────────────────────────────────────

    public List<AliasResponse> listAliases(Authentication auth, UUID itemId) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_VIEW", "list-aliases");
        itemOrNotFound(me.getOrgId(), itemId);
        return aliases.findByItemIdAndOrgId(itemId, me.getOrgId()).stream()
                .map(StockService::toAliasResponse).toList();
    }

    @Transactional
    public AliasResponse createAlias(Authentication auth, UUID itemId, CreateAliasRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_ADMIN", "create-alias");
        itemOrNotFound(me.getOrgId(), itemId);
        if (req.alias() == null || req.alias().isBlank()) {
            throw new BadRequestException("INVALID_ALIAS", "L'alias est requis");
        }
        String alias = req.alias().trim();
        if (aliases.findByOrgIdAndAliasIgnoreCase(me.getOrgId(), alias).isPresent()) {
            throw new BusinessRuleException("ALIAS_EXISTS", "Cet alias existe déjà: " + alias);
        }
        ItemAlias a = new ItemAlias();
        a.setOrgId(me.getOrgId());
        a.setItemId(itemId);
        a.setAlias(alias);
        a.setCreatedBy(me.getId());
        a.setCreatedAt(java.time.Instant.now());
        a.markNew();
        aliases.save(a);
        audit.log("STOCK_ITEM_ALIAS_CREATED", "stock_item", itemId,
                "{\"alias\":\"" + esc(alias) + "\"}");
        return toAliasResponse(a);
    }

    @Transactional
    public void deleteAlias(Authentication auth, UUID itemId, UUID aliasId) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_ADMIN", "delete-alias");
        itemOrNotFound(me.getOrgId(), itemId);
        ItemAlias a = aliases.findById(aliasId)
                .filter(x -> x.getOrgId().equals(me.getOrgId()) && x.getItemId().equals(itemId))
                .orElseThrow(() -> new ResourceNotFoundException("ALIAS_NOT_FOUND", "Alias introuvable"));
        aliases.delete(a);
        audit.log("STOCK_ITEM_ALIAS_DELETED", "stock_item", itemId,
                "{\"alias\":\"" + esc(a.getAlias()) + "\"}");
    }

    // ══════════════════════════════════════════════════════════════
    //  LOCATIONS
    // ══════════════════════════════════════════════════════════════

    /**
     * Locations visible to the caller.
     *   owner            → all active org locations (+ inactive when flagged)
     *   projectId filter → owner: that project's sites + CENTRAL;
     *                      member: same, but 404-style empty when not a member.
     *   member (no proj) → CENTRAL (org policy) + SITE locations of their projects.
     */
    public List<LocationResponse> listLocations(Authentication auth, UUID projectId, boolean includeInactive) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_VIEW", "list-locations");
        List<StockLocation> result = new ArrayList<>();

        if (Boolean.TRUE.equals(me.getIsOwner())) {
            if (projectId != null) {
                requireProjectVisible(me, projectId);
                result.addAll(locations.findSiteByProjectId(me.getOrgId(), projectId));
                locations.findCentralByOrgId(me.getOrgId()).ifPresent(result::add);
            } else {
                result.addAll(locations.findActiveByOrgId(me.getOrgId()));
                if (includeInactive) {
                    jdbc.queryForList(
                            "select id from stock_location where org_id = ? and active = false",
                            UUID.class, me.getOrgId())
                            .forEach(id -> locations.findByIdAndOrgId(id, me.getOrgId()).ifPresent(result::add));
                }
            }
            return result.stream().map(this::toLocationResponse).toList();
        }

        // Non-owner
        List<UUID> myProjects = myProjectIds(me);
        if (projectId != null) {
            if (!myProjects.contains(projectId)) {
                // IDOR-safe: no leak — answer as if the project has no stock locations.
                requireProjectVisible(me, projectId); // throws FORBIDDEN for non-members with context
            }
            result.addAll(locations.findSiteByProjectId(me.getOrgId(), projectId));
        } else {
            if (!myProjects.isEmpty()) {
                result.addAll(locations.findSiteForProjects(me.getOrgId(), myProjects));
            }
        }
        // CENTRAL policy: visible to any member of any project in the org.
        if (projectId == null && isMemberOfAnyProject(me.getId(), me.getOrgId())) {
            locations.findCentralByOrgId(me.getOrgId()).ifPresent(result::add);
        } else if (projectId != null) {
            // Explicit CENTRAL included when filtering by a project of mine
            // (movement CENTRAL↔SITE needs both ends visible).
            locations.findCentralByOrgId(me.getOrgId()).ifPresent(result::add);
        }
        return result.stream().map(this::toLocationResponse).toList();
    }

    private void requireProjectVisible(AppUser me, UUID projectId) {
        if (Boolean.TRUE.equals(me.getIsOwner())) return;
        if (!isProjectMember(me.getId(), projectId)) {
            throw new BusinessRuleException("FORBIDDEN", "Projet inaccessible");
        }
    }

    private List<UUID> myProjectIds(AppUser me) {
        return jdbc.queryForList("""
                select pm.project_id from project_member pm
                join project p on p.id = pm.project_id
                where pm.user_id = ? and p.org_id = ?
                """, UUID.class, me.getId(), me.getOrgId());
    }

    @Transactional
    public LocationResponse createLocation(Authentication auth, CreateLocationRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_ADMIN", "create-location");
        if (req.name() == null || req.name().isBlank()) {
            throw new BadRequestException("INVALID_NAME", "Le nom de l'emplacement est requis");
        }
        String type = req.type() == null ? "" : req.type().trim().toUpperCase();

        if ("CENTRAL".equals(type)) {
            if (req.projectId() != null) {
                throw new BadRequestException("CENTRAL_NO_PROJECT",
                        "Un dépôt central n'est pas rattaché à un projet");
            }
            if (locations.findCentralByOrgId(me.getOrgId()).isPresent()) {
                throw new BusinessRuleException("CENTRAL_EXISTS",
                        "Un dépôt central actif existe déjà pour cette organisation");
            }
        } else if ("SITE".equals(type)) {
            if (req.projectId() == null) {
                throw new BadRequestException("SITE_REQUIRES_PROJECT",
                        "Un emplacement SITE doit être rattaché à un projet");
            }
            Integer cnt = jdbc.queryForObject(
                    "select count(*) from project where id = ? and org_id = ?",
                    Integer.class, req.projectId(), me.getOrgId());
            if (cnt == null || cnt == 0) {
                throw new ResourceNotFoundException("PROJECT_NOT_FOUND",
                        "Projet introuvable dans cette organisation");
            }
        } else {
            throw new BadRequestException("INVALID_TYPE", "type doit être CENTRAL ou SITE");
        }

        StockLocation loc = new StockLocation();
        loc.setOrgId(me.getOrgId());
        loc.setType("CENTRAL".equals(type) ? StockLocationType.CENTRAL : StockLocationType.SITE);
        loc.setProjectId(req.projectId());
        loc.setCode(req.code() != null && !req.code().isBlank() ? req.code().trim() : null);
        loc.setName(req.name().trim());
        loc.setActive(true);
        loc.setCreatedBy(me.getId());
        loc.setCreatedAt(java.time.Instant.now());
        loc.markNew();
        locations.save(loc);
        audit.log("STOCK_LOCATION_CREATED", "stock_location", loc.getId(),
                "{\"type\":\"" + loc.getType() + "\",\"name\":\"" + esc(loc.getName())
                        + "\",\"projectId\":" + (loc.getProjectId() == null ? "null" : "\"" + loc.getProjectId() + "\"") + "}");
        return toLocationResponse(loc, null);
    }

    // ══════════════════════════════════════════════════════════════
    //  BALANCES
    // ══════════════════════════════════════════════════════════════

    /**
     * Virtual stock rows: (item, location, qty, uom) with filters.
     * Non-owner callers only see locations they may access: SITE rows of
     * their projects + CENTRAL (org policy). IDOR-safe: filtered in SQL.
     */
    public List<BalanceRow> balances(Authentication auth, UUID projectId,
                                     UUID locationId, UUID itemId, boolean includeZero) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_VIEW", "balances");

        List<Object> args = new ArrayList<>();
        args.add(me.getOrgId());
        StringBuilder where = new StringBuilder(" where b.org_id = ? and l.active = true and i.active = true ");
        if (projectId != null) {
            requireProjectVisible(me, projectId);
            where.append(" and b.project_id = ? ");
            args.add(projectId);
        }
        if (locationId != null) {
            StockLocation loc = locationOrNotFound(me.getOrgId(), locationId);
            assertLocationAccess(auth, me, loc, "balances");
            where.append(" and b.location_id = ? ");
            args.add(locationId);
        }
        if (itemId != null) {
            itemOrNotFound(me.getOrgId(), itemId);
            where.append(" and b.item_id = ? ");
            args.add(itemId);
        }
        if (!Boolean.TRUE.equals(me.getIsOwner())) {
            // Non-owner: only SITE locations of my projects + CENTRAL.
            where.append("""
                     and (l.type = 'CENTRAL'
                          or (l.type = 'SITE' and l.project_id in
                              (select pm.project_id from project_member pm where pm.user_id = ?)))
                     """);
            args.add(me.getId());
        }
        if (!includeZero) {
            where.append(" and b.balance_qty <> 0 ");
        }

        String sql = """
                select b.item_id, i.name as item_name, i.code as item_code, i.uom,
                       b.location_id, l.name as location_name, l.type::text as location_type,
                       b.project_id, b.balance_qty
                from stock_balance b
                join stock_item i on i.id = b.item_id
                join stock_location l on l.id = b.location_id
                """ + where + " order by i.name asc, l.name asc";

        return jdbc.queryForList(sql, args.toArray()).stream()
                .map(StockService::toBalanceRow).toList();
    }

    /** Company-wide aggregation (owner dashboard): per-item total across the org. */
    public List<OrgBalanceRow> orgSummary(Authentication auth) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_VIEW", "org-summary");
        return jdbc.queryForList("""
                select sbo.item_id, i.name as item_name, i.code as item_code, i.uom, sbo.total_qty
                from stock_balance_org sbo
                join stock_item i on i.id = sbo.item_id
                where sbo.org_id = ? and sbo.total_qty <> 0
                order by i.name asc
                """, me.getOrgId()).stream()
                .map(r -> new OrgBalanceRow(
                        (UUID) r.get("item_id"),
                        (String) r.get("item_name"),
                        (String) r.get("item_code"),
                        (String) r.get("uom"),
                        (BigDecimal) r.get("total_qty")))
                .toList();
    }

    // ══════════════════════════════════════════════════════════════
    //  MOVES — post / history / reverse / link document
    // ══════════════════════════════════════════════════════════════

    /**
     * Post a move (append-only). See {@link PostMoveRequest} for the
     * validation matrix. PART 2 EXTENSION POINT: BC/BL lineage resolution
     * (bc_line_id / bl_line_id / bordereau_article_id) stamps here when
     * Part 2 introduces the commercial documents.
     */
    @Transactional
    public MoveResponse postMove(Authentication auth, PostMoveRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "post-move");

        StockMoveType type = parseType(req.moveType());
        BigDecimal qty = req.qty();
        if (qty == null || qty.compareTo(BigDecimal.ZERO) == 0) {
            throw new BadRequestException("INVALID_QTY", "La quantité est requise et ne peut pas être zéro");
        }
        if (type != StockMoveType.ADJUST && qty.signum() < 0) {
            throw new BadRequestException("INVALID_QTY",
                    "La quantité doit être positive (seul ADJUST accepte une valeur négative)");
        }

        StockItem item = itemOrNotFound(me.getOrgId(), req.itemId());
        StockLocation from = null;
        StockLocation to = null;

        switch (type) {
            case IN -> {
                if (req.toLocationId() == null) throw new BadRequestException("LOCATION_REQUIRED",
                        "IN exige toLocationId");
                if (req.fromLocationId() != null) throw new BadRequestException("LOCATION_FORBIDDEN",
                        "IN n'accepte pas fromLocationId");
                to = locationOrNotFound(me.getOrgId(), req.toLocationId());
                assertLocationAccess(auth, me, to, "post-move IN");
            }
            case OUT, DIRECT_USE -> {
                if (req.fromLocationId() == null) throw new BadRequestException("LOCATION_REQUIRED",
                        type + " exige fromLocationId");
                if (req.toLocationId() != null) throw new BadRequestException("LOCATION_FORBIDDEN",
                        type + " n'accepte pas toLocationId");
                from = locationOrNotFound(me.getOrgId(), req.fromLocationId());
                assertLocationAccess(auth, me, from, "post-move " + type);
            }
            case TRANSFER -> {
                if (req.fromLocationId() == null || req.toLocationId() == null) {
                    throw new BadRequestException("LOCATION_REQUIRED",
                            "TRANSFER exige fromLocationId et toLocationId");
                }
                if (req.fromLocationId().equals(req.toLocationId())) {
                    throw new BadRequestException("TRANSFER_SAME_LOCATION",
                            "TRANSFER: l'emplacement source et destination doivent être différents");
                }
                from = locationOrNotFound(me.getOrgId(), req.fromLocationId());
                to = locationOrNotFound(me.getOrgId(), req.toLocationId());
                assertLocationAccess(auth, me, from, "post-move TRANSFER (from)");
                assertLocationAccess(auth, me, to, "post-move TRANSFER (to)");
            }
            case ADJUST -> {
                if (req.fromLocationId() == null) throw new BadRequestException("LOCATION_REQUIRED",
                        "ADJUST exige fromLocationId (emplacement ajusté)");
                if (req.toLocationId() != null) throw new BadRequestException("LOCATION_FORBIDDEN",
                        "ADJUST n'accepte pas toLocationId");
                from = locationOrNotFound(me.getOrgId(), req.fromLocationId());
                assertLocationAccess(auth, me, from, "post-move ADJUST");
            }
        }

        // Optional document — must exist in org and belong to an accessible project.
        if (req.documentId() != null) {
            assertDocumentAccessible(me, req.documentId());
        }

        // Negative-stock guard (hard block — Part 1.1 decision).
        if (type == StockMoveType.OUT || type == StockMoveType.DIRECT_USE) {
            requireNonNegative(from, item.getId(), qty.negate());
        } else if (type == StockMoveType.TRANSFER) {
            requireNonNegative(from, item.getId(), qty.negate());
        } else if (type == StockMoveType.ADJUST && qty.signum() < 0) {
            requireNonNegative(from, item.getId(), qty);
        }

        // Denormalized project: prefer the SITE side (the chantier concerned).
        UUID projectId = null;
        if (to != null && to.getProjectId() != null) projectId = to.getProjectId();
        else if (from != null && from.getProjectId() != null) projectId = from.getProjectId();

        StockMove m = new StockMove();
        m.setOrgId(me.getOrgId());
        m.setMoveType(type);
        m.setQty(qty);
        m.setUom(req.uom() != null && !req.uom().isBlank() ? req.uom().trim() : item.getUom());
        m.setMoveDate(req.moveDate() != null ? req.moveDate() : LocalDate.now());
        m.setFromLocationId(from != null ? from.getId() : null);
        m.setToLocationId(to != null ? to.getId() : null);
        m.setItemId(item.getId());
        m.setDocumentId(req.documentId());
        m.setProjectId(projectId);
        m.setPartyId(req.partyId());
        m.setCashMovementId(req.cashMovementId());
        m.setNote(req.note());
        m.setCreatedBy(me.getId());
        m.setCreatedAt(java.time.Instant.now());
        m.markNew();
        moves.save(m);

        audit.log("STOCK_MOVE_CREATED", "stock_move", m.getId(), moveAuditJson(m, item, from, to, me));
        return toMoveResponse(m, item, from, to, me.getId());
    }

    private StockMoveType parseType(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BadRequestException("INVALID_MOVE_TYPE", "moveType est requis");
        }
        try {
            return StockMoveType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("INVALID_MOVE_TYPE",
                    "moveType doit être IN, OUT, TRANSFER, ADJUST ou DIRECT_USE");
        }
    }

    /** Hard-block: projected balance at a location must stay >= 0. */
    private void requireNonNegative(StockLocation loc, UUID itemId, BigDecimal delta) {
        BigDecimal current = moves.signedBalance(loc.getOrgId(), itemId, loc.getId());
        BigDecimal projected = (current == null ? BigDecimal.ZERO : current).add(delta);
        if (projected.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessRuleException("STOCK_NEGATIVE_BALANCE",
                    "Stock insuffisant à l'emplacement '" + loc.getName()
                            + "' — solde actuel: " + current + ", demandé: " + delta.negate());
        }
    }

    /** Document must exist in org and belong to a project the caller can access. */
    private void assertDocumentAccessible(AppUser me, UUID documentId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select d.id, f.project_id
                from document d
                join doc_folder f on f.id = d.folder_id
                where d.id = ? and d.org_id = ? and d.deleted_at is null
                """, documentId, me.getOrgId());
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("DOCUMENT_NOT_FOUND",
                    "Document introuvable dans cette organisation");
        }
        UUID docProject = (UUID) rows.get(0).get("project_id");
        if (!Boolean.TRUE.equals(me.getIsOwner()) && !isProjectMember(me.getId(), docProject)) {
            throw new BusinessRuleException("DOCUMENT_ACCESS_DENIED",
                    "Document rattaché à un projet inaccessible");
        }
    }

    /** Move history with filters + pagination (newest first). */
    public MovePage history(Authentication auth, UUID itemId, UUID locationId,
                            UUID projectId, int page, int size) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_VIEW", "move-history");
        if (page < 0) page = 0;
        if (size <= 0 || size > 200) size = 50;

        StringBuilder where = new StringBuilder(" where m.org_id = ? ");
        List<Object> args = new ArrayList<>();
        args.add(me.getOrgId());

        if (itemId != null) { itemOrNotFound(me.getOrgId(), itemId); where.append(" and m.item_id = ? "); args.add(itemId); }
        if (projectId != null) { requireProjectVisible(me, projectId); where.append(" and m.project_id = ? "); args.add(projectId); }
        if (locationId != null) {
            StockLocation loc = locationOrNotFound(me.getOrgId(), locationId);
            assertLocationAccess(auth, me, loc, "move-history");
            where.append(" and (m.from_location_id = ? or m.to_location_id = ?) ");
            args.add(locationId);
            args.add(locationId);
        }
        if (!Boolean.TRUE.equals(me.getIsOwner())) {
            where.append("""
                     and (exists (select 1 from stock_location cl
                                  where cl.id = coalesce(m.from_location_id, m.to_location_id)
                                    and cl.type = 'CENTRAL')
                          or m.project_id in (select pm.project_id from project_member pm where pm.user_id = ?))
                     """);
            args.add(me.getId());
        }

        Long total = jdbc.queryForObject(
                "select count(*) from stock_move m" + where, Long.class, args.toArray());

        String sql = """
                select m.id, m.move_type::text as move_type, m.qty, m.uom, m.move_date,
                       m.from_location_id, fl.name as from_location_name,
                       m.to_location_id, tl.name as to_location_name,
                       m.item_id, i.name as item_name,
                       m.document_id, m.project_id, m.party_id, m.cash_movement_id,
                       m.note, m.created_by, u.full_name as created_by_name, m.created_at
                from stock_move m
                join stock_item i on i.id = m.item_id
                left join stock_location fl on fl.id = m.from_location_id
                left join stock_location tl on tl.id = m.to_location_id
                left join app_user u on u.id = m.created_by
                """ + where + " order by m.move_date desc, m.created_at desc limit ? offset ?";
        List<Object> qargs = new ArrayList<>(args);
        qargs.add(size);
        qargs.add(page * size);

        List<MoveResponse> rows = jdbc.queryForList(sql, qargs.toArray()).stream()
                .map(StockService::rowToMoveResponse).toList();
        return new MovePage(rows, total == null ? 0 : total, page, size);
    }

    /**
     * Reverse a mistaken move by creating the exact OPPOSITE move
     * (append-only — the original row is never touched):
     *   IN → OUT at the original to_location
     *   OUT / DIRECT_USE → IN at the original from_location
     *   TRANSFER → TRANSFER with swapped ends
     *   ADJUST(+q) → ADJUST(−q) ; ADJUST(−q) → ADJUST(+q)
     * The negative-stock guard applies to the reversal as well.
     */
    @Transactional
    public MoveResponse reverse(Authentication auth, UUID moveId, ReverseRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "reverse-move");

        StockMove original = moves.findById(moveId)
                .filter(m -> m.getOrgId().equals(me.getOrgId()))
                .orElseThrow(() -> new ResourceNotFoundException("MOVE_NOT_FOUND", "Mouvement introuvable"));

        StockItem item = itemOrNotFound(me.getOrgId(), original.getItemId());
        StockLocation from = original.getFromLocationId() == null ? null
                : locationOrNotFound(me.getOrgId(), original.getFromLocationId());
        StockLocation to = original.getToLocationId() == null ? null
                : locationOrNotFound(me.getOrgId(), original.getToLocationId());

        // Access both ends of the original move.
        if (from != null) assertLocationAccess(auth, me, from, "reverse-move");
        if (to != null) assertLocationAccess(auth, me, to, "reverse-move");

        String reason = req == null || req.reason() == null || req.reason().isBlank()
                ? "Correction" : req.reason().trim();
        String note = "REVERSE de " + original.getId() + " — " + reason;

        StockMove reversal = new StockMove();
        reversal.setOrgId(me.getOrgId());
        reversal.setQty(original.getQty().abs());
        reversal.setUom(original.getUom());
        reversal.setMoveDate(LocalDate.now());
        reversal.setItemId(original.getItemId());
        reversal.setNote(note);
        // Deliberately NOT copying documentId / partyId / cashMovementId:
        // the reversal is its own event; the audit row links both moves.
        switch (original.getMoveType()) {
            case IN -> {
                reversal.setMoveType(StockMoveType.OUT);
                reversal.setFromLocationId(original.getToLocationId());
                requireNonNegative(locationOrNotFound(me.getOrgId(), original.getToLocationId()),
                        item.getId(), original.getQty().negate());
            }
            case OUT, DIRECT_USE -> {
                reversal.setMoveType(StockMoveType.IN);
                reversal.setToLocationId(original.getFromLocationId());
            }
            case TRANSFER -> {
                reversal.setMoveType(StockMoveType.TRANSFER);
                reversal.setFromLocationId(original.getToLocationId());
                reversal.setToLocationId(original.getFromLocationId());
                requireNonNegative(locationOrNotFound(me.getOrgId(), original.getToLocationId()),
                        item.getId(), original.getQty().negate());
            }
            case ADJUST -> {
                reversal.setMoveType(StockMoveType.ADJUST);
                reversal.setFromLocationId(original.getFromLocationId());
                reversal.setQty(original.getQty().negate());
                if (original.getQty().signum() > 0) {
                    requireNonNegative(locationOrNotFound(me.getOrgId(), original.getFromLocationId()),
                            item.getId(), original.getQty().negate());
                }
            }
        }
        reversal.setCreatedBy(me.getId());
        reversal.setCreatedAt(java.time.Instant.now());
        reversal.markNew();
        moves.save(reversal);

        audit.log("STOCK_MOVE_REVERSED", "stock_move", reversal.getId(),
                "{\"originalMoveId\":\"" + original.getId() + "\",\"reason\":\"" + esc(reason)
                        + "\",\"originalType\":\"" + original.getMoveType() + "\",\"reversalType\":\""
                        + reversal.getMoveType() + "\",\"qty\":" + reversal.getQty() + "}");
        return toMoveResponse(reversal, item, from, to, me.getId());
    }

    /**
     * Link an EXISTING document to an EXISTING move — via the
     * stock_move_document junction (V31). The stock_move row itself is
     * never UPDATEd (append-only).
     */
    @Transactional
    public Map<String, Object> linkDocument(Authentication auth, UUID moveId, LinkDocumentRequest req) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_MOVE", "link-document");

        StockMove m = moves.findById(moveId)
                .filter(x -> x.getOrgId().equals(me.getOrgId()))
                .orElseThrow(() -> new ResourceNotFoundException("MOVE_NOT_FOUND", "Mouvement introuvable"));
        if (req.documentId() == null) {
            throw new BadRequestException("DOCUMENT_REQUIRED", "documentId est requis");
        }
        assertDocumentAccessible(me, req.documentId());

        Integer dup = jdbc.queryForObject(
                "select count(*) from stock_move_document where move_id = ? and document_id = ?",
                Integer.class, moveId, req.documentId());
        if (dup != null && dup > 0) {
            throw new BusinessRuleException("LINK_EXISTS", "Ce document est déjà lié à ce mouvement");
        }
        jdbc.update("""
                insert into stock_move_document (org_id, move_id, document_id, created_by)
                values (?, ?, ?, ?)
                """, me.getOrgId(), moveId, req.documentId(), me.getId());

        audit.log("STOCK_MOVE_DOCUMENT_LINKED", "stock_move", moveId,
                "{\"documentId\":\"" + req.documentId() + "\"}");
        return Map.of("moveId", moveId, "documentId", req.documentId(), "linked", true);
    }

    /** Documents linked to a move (junction + the primary document_id). */
    public List<Map<String, Object>> documentsOf(Authentication auth, UUID moveId) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_VIEW", "move-documents");
        StockMove m = moves.findById(moveId)
                .filter(x -> x.getOrgId().equals(me.getOrgId()))
                .orElseThrow(() -> new ResourceNotFoundException("MOVE_NOT_FOUND", "Mouvement introuvable"));
        List<Map<String, Object>> out = new ArrayList<>();
        if (m.getDocumentId() != null) {
            out.add(Map.of("documentId", m.getDocumentId(), "primary", true));
        }
        jdbc.queryForList("""
                select document_id from stock_move_document where move_id = ?
                """, UUID.class, moveId).forEach(d -> out.add(Map.of("documentId", d, "primary", false)));
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    //  STOCK DOCUMENT FOLDER HOOK (minimal, non-breaking)
    // ══════════════════════════════════════════════════════════════

    private static final Map<String, String> STOCK_FOLDER_CODES = Map.of(
            "BL", "05.01 BL",
            "BC", "05.02 BC",
            "FACTURE", "05.03 FACTURE",
            "AUTRE", "05.04 AUTRE");

    /**
     * Resolve-or-create the STOCK subfolder (under "05 ACHAT") for a project.
     * Returns the folderId the frontend passes to the EXISTING upload API —
     * no upload endpoint is modified (Part 1.2 minimal hook, non-breaking).
     *
     *   GET /api/stock/doc-folder?projectId=…&kind=BL
     *     → { "folderId": "…", "code": "05.01 BL", "created": false }
     */
    @Transactional
    public Map<String, Object> ensureStockFolder(Authentication auth, UUID projectId, String kind) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_VIEW", "doc-folder");
        requireProjectVisible(me, projectId);

        String code = STOCK_FOLDER_CODES.get(kind == null ? "" : kind.trim().toUpperCase());
        if (code == null) {
            throw new BadRequestException("INVALID_KIND", "kind doit être BL, BC, FACTURE ou AUTRE");
        }

        // Parent = the project's "05 ACHAT" top folder (V9 underscore codes; accept legacy space form).
        List<Map<String, Object>> parents = jdbc.queryForList("""
                select id from doc_folder
                where project_id = ? and parent_id is null and code in ('05_ACHAT', '05 ACHAT')
                limit 1
                """, projectId);
        if (parents.isEmpty()) {
            throw new ResourceNotFoundException("ACHAT_FOLDER_NOT_FOUND",
                    "Le dossier '05 ACHAT' est introuvable pour ce projet — exécutez ensure-folders");
        }
        UUID parentId = (UUID) parents.get(0).get("id");

        List<Map<String, Object>> existing = jdbc.queryForList("""
                select id from doc_folder
                where project_id = ? and parent_id = ? and code = ?
                limit 1
                """, projectId, parentId, code);
        if (!existing.isEmpty()) {
            return Map.of("folderId", existing.get(0).get("id"), "code", code, "created", false);
        }
        UUID newId = UUID.randomUUID();
        jdbc.update("""
                insert into doc_folder (id, project_id, parent_id, code, name, sort_order, created_at)
                values (?, ?, ?, ?, ?, 10, now())
                """, newId, projectId, parentId, code, stockFolderName(code));
        audit.log("STOCK_FOLDER_CREATED", "doc_folder", newId,
                "{\"projectId\":\"" + projectId + "\",\"code\":\"" + code + "\"}");
        return Map.of("folderId", newId, "code", code, "created", true);
    }

    private static String stockFolderName(String code) {
        return switch (code) {
            case "05.01 BL" -> "Bons de livraison (stock)";
            case "05.02 BC" -> "Bons de commande (stock)";
            case "05.03 FACTURE" -> "Factures fournisseurs (stock)";
            default -> "Autres documents stock";
        };
    }

    // ══════════════════════════════════════════════════════════════
    //  MAPPERS / UTILITIES
    // ══════════════════════════════════════════════════════════════

    private static ItemResponse toItemResponse(StockItem i) {
        return new ItemResponse(i.getId(), i.getOrgId(), i.getCode(), i.getName(),
                i.getUom(), i.isActive(), i.getCreatedAt());
    }

    private static AliasResponse toAliasResponse(ItemAlias a) {
        return new AliasResponse(a.getId(), a.getItemId(), a.getAlias(), a.getCreatedAt());
    }

    private LocationResponse toLocationResponse(StockLocation l) {
        String projectName = null;
        if (l.getProjectId() != null) {
            List<String> names = jdbc.queryForList(
                    "select name from project where id = ?", String.class, l.getProjectId());
            projectName = names.isEmpty() ? null : names.get(0);
        }
        return toLocationResponse(l, projectName);
    }

    private static LocationResponse toLocationResponse(StockLocation l, String projectName) {
        return new LocationResponse(l.getId(), l.getOrgId(), l.getType().name(), l.getProjectId(),
                projectName, l.getCode(), l.getName(), l.isActive());
    }

    private static BalanceRow toBalanceRow(Map<String, Object> r) {
        return new BalanceRow(
                (UUID) r.get("item_id"), (String) r.get("item_name"),
                (String) r.get("item_code"), (String) r.get("uom"),
                (UUID) r.get("location_id"), (String) r.get("location_name"),
                (String) r.get("location_type"), (UUID) r.get("project_id"),
                (BigDecimal) r.get("balance_qty"));
    }

    private MoveResponse toMoveResponse(StockMove m, StockItem item,
                                        StockLocation from, StockLocation to, UUID callerId) {
        String createdByName = jdbc.queryForObject(
                "select full_name from app_user where id = ?", String.class, m.getCreatedBy());
        return new MoveResponse(m.getId(), m.getOrgId(), m.getMoveType().name(), m.getQty(),
                m.getUom(), m.getMoveDate(),
                m.getFromLocationId(), from != null ? from.getName() : null,
                m.getToLocationId(), to != null ? to.getName() : null,
                m.getItemId(), item.getName(),
                m.getDocumentId(), m.getProjectId(), m.getPartyId(), m.getCashMovementId(),
                m.getNote(), m.getCreatedBy(), createdByName, m.getCreatedAt());
    }

    private static MoveResponse rowToMoveResponse(Map<String, Object> r) {
        return new MoveResponse(
                (UUID) r.get("id"), null, (String) r.get("move_type"),
                (BigDecimal) r.get("qty"), (String) r.get("uom"),
                toLocalDate(r.get("move_date")),
                (UUID) r.get("from_location_id"), (String) r.get("from_location_name"),
                (UUID) r.get("to_location_id"), (String) r.get("to_location_name"),
                (UUID) r.get("item_id"), (String) r.get("item_name"),
                (UUID) r.get("document_id"), (UUID) r.get("project_id"),
                (UUID) r.get("party_id"), (UUID) r.get("cash_movement_id"),
                (String) r.get("note"), (UUID) r.get("created_by"),
                (String) r.get("created_by_name"), toInstant(r.get("created_at")));
    }

    /** JdbcTemplate returns java.sql.Timestamp for timestamptz — normalize to Instant. */
    private static java.time.Instant toInstant(Object o) {
        if (o == null) return null;
        if (o instanceof java.time.Instant i) return i;
        if (o instanceof java.sql.Timestamp ts) return ts.toInstant();
        if (o instanceof java.time.OffsetDateTime odt) return odt.toInstant();
        if (o instanceof java.time.LocalDateTime ldt) return ldt.toInstant(java.time.ZoneOffset.UTC);
        return java.time.Instant.parse(o.toString());
    }

    private static LocalDate toLocalDate(Object o) {
        if (o == null) return null;
        if (o instanceof LocalDate d) return d;
        if (o instanceof java.sql.Date d) return d.toLocalDate();
        if (o instanceof java.time.LocalDateTime dt) return dt.toLocalDate();
        return LocalDate.parse(o.toString().substring(0, 10));
    }

    private static String moveAuditJson(StockMove m, StockItem item,
                                        StockLocation from, StockLocation to, AppUser me) {
        return "{\"moveType\":\"" + m.getMoveType()
                + "\",\"qty\":" + m.getQty()
                + ",\"uom\":\"" + esc(m.getUom())
                + "\",\"itemId\":\"" + item.getId()
                + "\",\"itemName\":\"" + esc(item.getName())
                + "\",\"fromLocationId\":" + (from == null ? "null" : "\"" + from.getId() + "\"")
                + ",\"toLocationId\":" + (to == null ? "null" : "\"" + to.getId() + "\"")
                + ",\"projectId\":" + (m.getProjectId() == null ? "null" : "\"" + m.getProjectId() + "\"")
                + ",\"documentId\":" + (m.getDocumentId() == null ? "null" : "\"" + m.getDocumentId() + "\"")
                + ",\"note\":" + (m.getNote() == null ? "null" : "\"" + esc(m.getNote()) + "\"")
                + "}";
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String jsonOrNull(String s) {
        return s == null ? "null" : "\"" + esc(s) + "\"";
    }
}
