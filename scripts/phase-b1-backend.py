"""
Phase B1 — Backend: enrich documentsOf() + add linkedDocCount to MoveResponse + history() SQL.

Surgical edits to /tmp/my-project/backend/src/main/java/tn/hydra/stock/StockService.java.

Changes:
1. Add `linkedDocCount` (Integer) field to MoveResponse record.
2. Add a subquery to history()'s SQL to compute linkedDocCount per move (no N+1).
3. Update rowToMoveResponse() to read linkedDocCount from the row.
4. Update toMoveResponse() (the JPA-based builder) to compute linkedDocCount via a single count query.
5. Enrich documentsOf() SQL to JOIN document + doc_folder → return title, reference, docType.
"""
from pathlib import Path
import re

p = Path('/tmp/my-project/backend/src/main/java/tn/hydra/stock/StockService.java')
s = p.read_text(encoding='utf-8')

# ─── Change 1: Add linkedDocCount to MoveResponse ──────────────────────────
old_record = """    public record MoveResponse(UUID id, UUID orgId, String moveType, BigDecimal qty, String uom,
                               LocalDate moveDate, UUID fromLocationId, String fromLocationName,
                               UUID toLocationId, String toLocationName, UUID itemId,
                               String itemName, UUID documentId, UUID projectId,
                               UUID partyId, UUID cashMovementId, String note,
                               UUID createdBy, String createdByName, java.time.Instant createdAt) {}"""
new_record = """    public record MoveResponse(UUID id, UUID orgId, String moveType, BigDecimal qty, String uom,
                               LocalDate moveDate, UUID fromLocationId, String fromLocationName,
                               UUID toLocationId, String toLocationName, UUID itemId,
                               String itemName, UUID documentId, UUID projectId,
                               UUID partyId, UUID cashMovementId, String note,
                               UUID createdBy, String createdByName, java.time.Instant createdAt,
                               Integer linkedDocCount) {}"""
assert old_record in s, "MoveResponse record not found"
s = s.replace(old_record, new_record, 1)

# ─── Change 2: history() SQL — add linkedDocCount subquery ─────────────────
old_sql = """        String sql = \"\"\"
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
                \"\"\" + where + " order by m.move_date desc, m.created_at desc limit ? offset ?";"""
new_sql = """        String sql = \"\"\"
                select m.id, m.move_type::text as move_type, m.qty, m.uom, m.move_date,
                       m.from_location_id, fl.name as from_location_name,
                       m.to_location_id, tl.name as to_location_name,
                       m.item_id, i.name as item_name,
                       m.document_id, m.project_id, m.party_id, m.cash_movement_id,
                       m.note, m.created_by, u.full_name as created_by_name, m.created_at,
                       (select count(*) from stock_move_document smd where smd.move_id = m.id) as linked_doc_count
                from stock_move m
                join stock_item i on i.id = m.item_id
                left join stock_location fl on fl.id = m.from_location_id
                left join stock_location tl on tl.id = m.to_location_id
                left join app_user u on u.id = m.created_by
                \"\"\" + where + " order by m.move_date desc, m.created_at desc limit ? offset ?";"""
assert old_sql in s, "history() SQL not found"
s = s.replace(old_sql, new_sql, 1)

# ─── Change 3: rowToMoveResponse — read linked_doc_count ───────────────────
old_row = """    private static MoveResponse rowToMoveResponse(Map<String, Object> r) {
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
    }"""
new_row = """    private static MoveResponse rowToMoveResponse(Map<String, Object> r) {
        Object ldc = r.get("linked_doc_count");
        Integer linkedDocCount = ldc == null ? 0 : ((Number) ldc).intValue();
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
                (String) r.get("created_by_name"), toInstant(r.get("created_at")),
                linkedDocCount);
    }"""
assert old_row in s, "rowToMoveResponse not found"
s = s.replace(old_row, new_row, 1)

# ─── Change 4: toMoveResponse (JPA builder) — compute linkedDocCount ──────
old_jpa = """    private MoveResponse toMoveResponse(StockMove m, StockItem item,
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
    }"""
new_jpa = """    private MoveResponse toMoveResponse(StockMove m, StockItem item,
                                        StockLocation from, StockLocation to, UUID callerId) {
        String createdByName = jdbc.queryForObject(
                "select full_name from app_user where id = ?", String.class, m.getCreatedBy());
        Integer linkedDocCount = jdbc.queryForObject(
                "select count(*) from stock_move_document where move_id = ?",
                Integer.class, m.getId());
        if (linkedDocCount == null) linkedDocCount = 0;
        return new MoveResponse(m.getId(), m.getOrgId(), m.getMoveType().name(), m.getQty(),
                m.getUom(), m.getMoveDate(),
                m.getFromLocationId(), from != null ? from.getName() : null,
                m.getToLocationId(), to != null ? to.getName() : null,
                m.getItemId(), item.getName(),
                m.getDocumentId(), m.getProjectId(), m.getPartyId(), m.getCashMovementId(),
                m.getNote(), m.getCreatedBy(), createdByName, m.getCreatedAt(),
                linkedDocCount);
    }"""
assert old_jpa in s, "toMoveResponse (JPA) not found"
s = s.replace(old_jpa, new_jpa, 1)

# ─── Change 5: documentsOf() — enrich SQL with JOIN ────────────────────────
old_docs = """    /** Documents linked to a move (junction + the primary document_id). */
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
        jdbc.queryForList(\"\"\"
                select document_id from stock_move_document where move_id = ?
                \"\"\", UUID.class, moveId).forEach(d -> out.add(Map.of("documentId", d, "primary", false)));
        return out;
    }"""
new_docs = """    /**
     * Documents linked to a move: the PRIMARY proof on stock_move.document_id
     * (set at creation time) + any ADDITIONAL docs linked via the junction
     * (stock_move_document). Each row carries title / reference / docType so
     * the frontend dialog can show a human-readable label instead of a raw UUID.
     */
    public List<Map<String, Object>> documentsOf(Authentication auth, UUID moveId) {
        AppUser me = currentUser(auth);
        requirePermission(auth, "STOCK_VIEW", "move-documents");
        StockMove m = moves.findById(moveId)
                .filter(x -> x.getOrgId().equals(me.getOrgId()))
                .orElseThrow(() -> new ResourceNotFoundException("MOVE_NOT_FOUND", "Mouvement introuvable"));
        List<Map<String, Object>> out = new ArrayList<>();
        if (m.getDocumentId() != null) {
            Map<String, Object> primary = docRow(me.getOrgId(), m.getDocumentId());
            if (primary != null) {
                primary.put("primary", true);
                out.add(primary);
            } else {
                // Document was deleted or moved out of org — still report the id so
                // the UI can show "document introuvable" rather than hide the link.
                Map<String, Object> stub = new java.util.HashMap<>();
                stub.put("documentId", m.getDocumentId());
                stub.put("primary", true);
                stub.put("title", null);
                stub.put("reference", null);
                stub.put("docType", null);
                out.add(stub);
            }
        }
        jdbc.queryForList(\"\"\"
                select smd.document_id as "documentId", d.title, d.reference, d.doc_type::text as "docType",
                       false as "primary"
                from stock_move_document smd
                left join document d on d.id = smd.document_id and d.org_id = ? and d.deleted_at is null
                where smd.move_id = ?
                order by smd.created_at
                \"\"\", me.getOrgId(), moveId).forEach(out::add);
        return out;
    }

    /** Fetch a single document row (org-scoped, not deleted) → title/reference/docType. */
    private Map<String, Object> docRow(UUID orgId, UUID documentId) {
        try {
            return jdbc.queryForMap(\"\"\"
                    select id as "documentId", title, reference, doc_type::text as "docType"
                    from document where id = ? and org_id = ? and deleted_at is null
                    \"\"\", documentId, orgId);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return null;
        }
    }"""
assert old_docs in s, "documentsOf not found"
s = s.replace(old_docs, new_docs, 1)

p.write_text(s, encoding='utf-8')
print(f"OK  StockService.java — 5 surgical edits applied ({len(s)} chars)")
print("    1. MoveResponse +linkedDocCount field")
print("    2. history() SQL +linked_doc_count subquery (no N+1)")
print("    3. rowToMoveResponse reads linked_doc_count")
print("    4. toMoveResponse (JPA) computes linkedDocCount")
print("    5. documentsOf() enriched with title/reference/docType via JOIN")
