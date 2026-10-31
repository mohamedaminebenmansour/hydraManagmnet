"""2.2b — Wire bc_line_id + bl_line_id into StockMove + StockService (minimal additive).

Edits:
1. StockMove.java: add 2 @Column fields (bcLineId, blLineId)
2. StockService.java PostMoveRequest: add bcLineId, blLineId at the end (before metadata)
3. StockService.java MoveResponse: add bcLineId, blLineId at the end (after linkedDocCount)
4. StockService.java postMove(): stamp m.setBcLineId(req.bcLineId()); m.setBlLineId(req.blLineId());
5. StockService.java history() SQL: add m.bc_line_id, m.bl_line_id to SELECT
6. StockService.java toMoveResponse(): add bcLineId, blLineId to the new MoveResponse(...)
7. StockService.java rowToMoveResponse(): add bcLineId, blLineId to the new MoveResponse(...)
"""
from pathlib import Path

# ─── 1. StockMove.java ────────────────────────────────────────────────────
p = Path('/tmp/my-project/backend/src/main/java/tn/hydra/stock/domain/StockMove.java')
s = p.read_text(encoding='utf-8')

old = """    @Column(name = "note")
    private String note;

    @Column(name = "created_by", updatable = false, nullable = false)
    private UUID createdBy;"""
new = """    @Column(name = "note")
    private String note;

    /** FK→purchase_order_line(id). Set when an IN move closes a BC line (Part 2.2). Null otherwise. */
    @Column(name = "bc_line_id")
    private UUID bcLineId;

    /** FK→bon_livraison_line(id). Set when an IN move is sourced from a BL (Part 2.2). Null otherwise. */
    @Column(name = "bl_line_id")
    private UUID blLineId;

    @Column(name = "created_by", updatable = false, nullable = false)
    private UUID createdBy;"""
assert old in s, "StockMove note+createdBy anchor not found"
s = s.replace(old, new, 1)
p.write_text(s, encoding='utf-8')
print('OK  StockMove.java  +bcLineId +blLineId @Column fields')

# ─── 2-7. StockService.java ───────────────────────────────────────────────
p = Path('/tmp/my-project/backend/src/main/java/tn/hydra/stock/StockService.java')
s = p.read_text(encoding='utf-8')

# 2. PostMoveRequest: add bcLineId, blLineId before metadata
old = """    public record PostMoveRequest(String moveType, BigDecimal qty, String uom,
                                  LocalDate moveDate,
                                  UUID fromLocationId, UUID toLocationId,
                                  UUID itemId, UUID documentId, UUID partyId,
                                  UUID cashMovementId, String note,
                                  Map<String, Object> metadata) {}"""
new = """    public record PostMoveRequest(String moveType, BigDecimal qty, String uom,
                                  LocalDate moveDate,
                                  UUID fromLocationId, UUID toLocationId,
                                  UUID itemId, UUID documentId, UUID partyId,
                                  UUID cashMovementId, String note,
                                  UUID bcLineId, UUID blLineId,
                                  Map<String, Object> metadata) {}"""
assert old in s, "PostMoveRequest not found"
s = s.replace(old, new, 1)

# 3. MoveResponse: add bcLineId, blLineId after linkedDocCount
old = """    public record MoveResponse(UUID id, UUID orgId, String moveType, BigDecimal qty, String uom,
                               LocalDate moveDate, UUID fromLocationId, String fromLocationName,
                               UUID toLocationId, String toLocationName, UUID itemId,
                               String itemName, UUID documentId, UUID projectId,
                               UUID partyId, UUID cashMovementId, String note,
                               UUID createdBy, String createdByName, java.time.Instant createdAt,
                               Integer linkedDocCount) {}"""
new = """    public record MoveResponse(UUID id, UUID orgId, String moveType, BigDecimal qty, String uom,
                               LocalDate moveDate, UUID fromLocationId, String fromLocationName,
                               UUID toLocationId, String toLocationName, UUID itemId,
                               String itemName, UUID documentId, UUID projectId,
                               UUID partyId, UUID cashMovementId, String note,
                               UUID createdBy, String createdByName, java.time.Instant createdAt,
                               Integer linkedDocCount,
                               UUID bcLineId, UUID blLineId) {}"""
assert old in s, "MoveResponse not found"
s = s.replace(old, new, 1)

# 4. postMove(): stamp the 2 new fields before m.markNew()
old = """        m.setNote(req.note());
        m.setCreatedBy(me.getId());
        m.setCreatedAt(java.time.Instant.now());
        m.markNew();
        moves.save(m);

        audit.log("STOCK_MOVE_CREATED", "stock_move", m.getId(), moveAuditJson(m, item, from, to, me));"""
new = """        m.setNote(req.note());
        m.setBcLineId(req.bcLineId());
        m.setBlLineId(req.blLineId());
        m.setCreatedBy(me.getId());
        m.setCreatedAt(java.time.Instant.now());
        m.markNew();
        moves.save(m);

        audit.log("STOCK_MOVE_CREATED", "stock_move", m.getId(), moveAuditJson(m, item, from, to, me));"""
assert old in s, "postMove stamp block not found"
s = s.replace(old, new, 1)

# 5. history() SQL: add bc_line_id, bl_line_id to SELECT
old = """                select m.id, m.move_type::text as move_type, m.qty, m.uom, m.move_date,
                       m.from_location_id, fl.name as from_location_name,
                       m.to_location_id, tl.name as to_location_name,
                       m.item_id, i.name as item_name,
                       m.document_id, m.project_id, m.party_id, m.cash_movement_id,
                       m.note, m.created_by, u.full_name as created_by_name, m.created_at,
                       (select count(*) from stock_move_document smd where smd.move_id = m.id) as linked_doc_count"""
new = """                select m.id, m.move_type::text as move_type, m.qty, m.uom, m.move_date,
                       m.from_location_id, fl.name as from_location_name,
                       m.to_location_id, tl.name as to_location_name,
                       m.item_id, i.name as item_name,
                       m.document_id, m.project_id, m.party_id, m.cash_movement_id,
                       m.note, m.created_by, u.full_name as created_by_name, m.created_at,
                       m.bc_line_id, m.bl_line_id,
                       (select count(*) from stock_move_document smd where smd.move_id = m.id) as linked_doc_count"""
assert old in s, "history() SQL SELECT not found"
s = s.replace(old, new, 1)

# 6. toMoveResponse(): add bcLineId, blLineId to new MoveResponse(...)
old = """        return new MoveResponse(m.getId(), m.getOrgId(), m.getMoveType().name(), m.getQty(),
                m.getUom(), m.getMoveDate(),
                m.getFromLocationId(), from != null ? from.getName() : null,
                m.getToLocationId(), to != null ? to.getName() : null,
                m.getItemId(), item.getName(),
                m.getDocumentId(), m.getProjectId(), m.getPartyId(), m.getCashMovementId(),
                m.getNote(), m.getCreatedBy(), createdByName, m.getCreatedAt(),
                linkedDocCount);
    }"""
new = """        return new MoveResponse(m.getId(), m.getOrgId(), m.getMoveType().name(), m.getQty(),
                m.getUom(), m.getMoveDate(),
                m.getFromLocationId(), from != null ? from.getName() : null,
                m.getToLocationId(), to != null ? to.getName() : null,
                m.getItemId(), item.getName(),
                m.getDocumentId(), m.getProjectId(), m.getPartyId(), m.getCashMovementId(),
                m.getNote(), m.getCreatedBy(), createdByName, m.getCreatedAt(),
                linkedDocCount,
                m.getBcLineId(), m.getBlLineId());
    }"""
assert old in s, "toMoveResponse return not found"
s = s.replace(old, new, 1)

# 7. rowToMoveResponse(): add bcLineId, blLineId
old = """                (UUID) r.get("item_id"), (String) r.get("item_name"),
                (UUID) r.get("document_id"), (UUID) r.get("project_id"),
                (UUID) r.get("party_id"), (UUID) r.get("cash_movement_id"),
                (String) r.get("note"), (UUID) r.get("created_by"),
                (String) r.get("created_by_name"), toInstant(r.get("created_at")),
                linkedDocCount);
    }"""
new = """                (UUID) r.get("item_id"), (String) r.get("item_name"),
                (UUID) r.get("document_id"), (UUID) r.get("project_id"),
                (UUID) r.get("party_id"), (UUID) r.get("cash_movement_id"),
                (String) r.get("note"), (UUID) r.get("created_by"),
                (String) r.get("created_by_name"), toInstant(r.get("created_at")),
                linkedDocCount,
                (UUID) r.get("bc_line_id"), (UUID) r.get("bl_line_id"));
    }"""
assert old in s, "rowToMoveResponse return not found"
s = s.replace(old, new, 1)

p.write_text(s, encoding='utf-8')
print('OK  StockService.java  +6 edits (PostMoveRequest, MoveResponse, postMove stamp, history SQL, 2 builders)')
