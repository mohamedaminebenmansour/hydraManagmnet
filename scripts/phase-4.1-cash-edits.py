"""4.1b-c: CashService + PaymentFacade edits — persist bc_id + facture_id on cash_movement.

Edits to CashService.java:
1. CreateMovementRequest: +factureId at end
2. MovementResponse: +bcId +factureId at end
3. MovementDetailResponse: +bcId +bcReference +factureId +factureReference at end
4. INSERT: +bc_id +facture_id columns + values
5. fetchMovementForRead: +bc_id +facture_id in SELECT
6. toMovementResponse: +bcId +factureId
7. toMovementDetailResponse: +bcId +bcReference +factureId +factureReference
8. getMovementDetail SELECT: +m.bc_id +m.facture_id +LEFT JOINs for references
9. listMovementsDetail SELECT: +m.bc_id +m.facture_id +LEFT JOINs
10. createMovement: IDOR validation for bcId + factureId (after project resolution)

Edits to PaymentFacade.java:
11. SubmitPaymentRequest: +factureId
12. draftReq: pass req.factureId()
"""
from pathlib import Path

# ═══ CashService.java ══════════════════════════════════════════════════
p = Path('/tmp/my-project/backend/src/main/java/tn/hydra/cash/CashService.java')
s = p.read_text(encoding='utf-8')

# 1. CreateMovementRequest: +factureId
old = "String voucherNo, String nature, String invoiceRef,\n                                         UUID bcId) {}"
new = "String voucherNo, String nature, String invoiceRef,\n                                         UUID bcId, UUID factureId) {}"
assert old in s, "CreateMovementRequest end not found"
s = s.replace(old, new, 1)

# 2. MovementResponse: +bcId +factureId
old = "String voucherNo, String nature, String invoiceRef) {}"
new = "String voucherNo, String nature, String invoiceRef,\n                                    UUID bcId, UUID factureId) {}"
assert old in s, "MovementResponse end not found"
s = s.replace(old, new, 1)

# 3. MovementDetailResponse: +bcId +bcReference +factureId +factureReference
old = "            Instant voidRequestedAt, String voidRequestReason) {}"
new = "            Instant voidRequestedAt, String voidRequestReason,\n            UUID bcId, String bcReference, UUID factureId, String factureReference) {}"
assert old in s, "MovementDetailResponse end not found"
s = s.replace(old, new, 1)

# 4. INSERT: add bc_id + facture_id
old_insert = """                   payment_mode, payment_ref, voucher_no, nature, invoice_ref,
                   status, process_instance_id, created_by, created_at)
                values (?, ?, ?, ?, 'TND', ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?,
                        ?, ?, ?, ?, ?,
                        'DRAFT', ?, ?, now())
                """, id, me.getOrgId(), normalizeType(req.movementType()), effectiveAmount,
                Date.valueOf(req.movementDate()),
                req.fromPotId(), req.toPotId(), resolvedProjectId, req.partyId(),
                blankToNull(req.label()), blankToNull(req.note()),
                claimed, assessed, varianceReason,
                paymentMode, paymentRef, voucherNo, nature, invoiceRef,
                req.processInstanceId(),
                me.getId());"""
new_insert = """                   payment_mode, payment_ref, voucher_no, nature, invoice_ref,
                   bc_id, facture_id,
                   status, process_instance_id, created_by, created_at)
                values (?, ?, ?, ?, 'TND', ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?,
                        ?, ?, ?, ?, ?,
                        ?, ?,
                        'DRAFT', ?, ?, now())
                """, id, me.getOrgId(), normalizeType(req.movementType()), effectiveAmount,
                Date.valueOf(req.movementDate()),
                req.fromPotId(), req.toPotId(), resolvedProjectId, req.partyId(),
                blankToNull(req.label()), blankToNull(req.note()),
                claimed, assessed, varianceReason,
                paymentMode, paymentRef, voucherNo, nature, invoiceRef,
                req.bcId(), req.factureId(),
                req.processInstanceId(),
                me.getId());"""
assert old_insert in s, "INSERT not found"
s = s.replace(old_insert, new_insert, 1)

# 5. fetchMovementForRead: +bc_id +facture_id in SELECT
old_fmr = """                       claimed_amount, assessed_amount, variance_reason, document_id,
                       payment_mode, payment_ref, voucher_no, nature, invoice_ref
                from cash_movement"""
new_fmr = """                       claimed_amount, assessed_amount, variance_reason, document_id,
                       payment_mode, payment_ref, voucher_no, nature, invoice_ref,
                       bc_id, facture_id
                from cash_movement"""
assert old_fmr in s, "fetchMovementForRead SELECT not found"
s = s.replace(old_fmr, new_fmr, 1)

# 6. toMovementResponse: +bcId +factureId
old_tmr = """                (String) row.get("voucher_no"),
                (String) row.get("nature"),
                (String) row.get("invoice_ref")
        );
    }

    /** Mapper for the richer response"""
new_tmr = """                (String) row.get("voucher_no"),
                (String) row.get("nature"),
                (String) row.get("invoice_ref"),
                (UUID) row.get("bc_id"),
                (UUID) row.get("facture_id")
        );
    }

    /** Mapper for the richer response"""
assert old_tmr in s, "toMovementResponse end not found"
s = s.replace(old_tmr, new_tmr, 1)

# 7. toMovementDetailResponse: +bcId +bcReference +factureId +factureReference
old_tmdr_end = """                toInstant(row.get("void_requested_at")),
                (String) row.get("void_request_reason")
        );
    }"""
new_tmdr_end = """                toInstant(row.get("void_requested_at")),
                (String) row.get("void_request_reason"),
                (UUID) row.get("bc_id"),
                (String) row.get("bc_reference"),
                (UUID) row.get("facture_id"),
                (String) row.get("facture_reference")
        );
    }"""
assert old_tmdr_end in s, "toMovementDetailResponse end not found"
s = s.replace(old_tmdr_end, new_tmdr_end, 1)

# 8. getMovementDetail SELECT: add m.bc_id + m.facture_id + LEFT JOINs
# Find the SELECT in getMovementDetail — it has "m.payment_mode, m.payment_ref, m.voucher_no, m.nature, m.invoice_ref"
# followed by "from cash_movement m" — add columns + joins
old_detail_select = """                       m.payment_mode, m.payment_ref, m.voucher_no, m.nature, m.invoice_ref
                from cash_movement m
                left join cash_pot fp  on fp.id = m.from_pot_id
                left join cash_pot tp  on tp.id = m.to_pot_id
                left join project  p   on p.id  = m.project_id
                left join party    py  on py.id  = m.party_id
                left join app_user pbu on pbu.id = m.posted_by
                left join app_user vu  on vu.id  = m.voided_by
                left join document d   on d.id  = m.document_id
                left join lateral (
                    select r.file_name from document_revision r
                    where r.document_id = d.id
                    order by r.created_at desc
                    limit 1
                ) drv on m.document_id is not null
                where m.id = ? and m.org_id = ?"""
new_detail_select = """                       m.payment_mode, m.payment_ref, m.voucher_no, m.nature, m.invoice_ref,
                       m.bc_id, bc.reference as bc_reference,
                       m.facture_id, fac.reference as facture_reference
                from cash_movement m
                left join cash_pot fp  on fp.id = m.from_pot_id
                left join cash_pot tp  on tp.id = m.to_pot_id
                left join project  p   on p.id  = m.project_id
                left join party    py  on py.id  = m.party_id
                left join app_user pbu on pbu.id = m.posted_by
                left join app_user vu  on vu.id  = m.voided_by
                left join document d   on d.id  = m.document_id
                left join lateral (
                    select r.file_name from document_revision r
                    where r.document_id = d.id
                    order by r.created_at desc
                    limit 1
                ) drv on m.document_id is not null
                left join purchase_order bc on bc.id = m.bc_id
                left join facture fac on fac.id = m.facture_id
                where m.id = ? and m.org_id = ?"""
assert old_detail_select in s, "getMovementDetail SELECT not found"
s = s.replace(old_detail_select, new_detail_select, 1)

# 9. listMovementsDetail SELECT: add m.bc_id + m.facture_id + LEFT JOINs
old_list_select = """                       m.payment_mode, m.payment_ref, m.voucher_no, m.nature, m.invoice_ref
                from cash_movement m
                left join cash_pot fp  on fp.id = m.from_pot_id
                left join cash_pot tp  on tp.id = m.to_pot_id
                left join project  p   on p.id  = m.project_id
                left join party    py  on py.id  = m.party_id
                left join app_user pbu on pbu.id = m.posted_by
                left join app_user vu  on vu.id  = m.voided_by
                left join document d   on d.id  = m.document_id
                where m.org_id = ?"""
new_list_select = """                       m.payment_mode, m.payment_ref, m.voucher_no, m.nature, m.invoice_ref,
                       m.bc_id, bc.reference as bc_reference,
                       m.facture_id, fac.reference as facture_reference
                from cash_movement m
                left join cash_pot fp  on fp.id = m.from_pot_id
                left join cash_pot tp  on tp.id = m.to_pot_id
                left join project  p   on p.id  = m.project_id
                left join party    py  on py.id  = m.party_id
                left join app_user pbu on pbu.id = m.posted_by
                left join app_user vu  on vu.id  = m.voided_by
                left join document d   on d.id  = m.document_id
                left join purchase_order bc on bc.id = m.bc_id
                left join facture fac on fac.id = m.facture_id
                where m.org_id = ?"""
assert old_list_select in s, "listMovementsDetail SELECT not found"
s = s.replace(old_list_select, new_list_select, 1)

# 10. createMovement: add IDOR validation for bcId + factureId
# Insert AFTER the existing requireProjectAccess call (around line 585)
old_proj_access = """        if (resolvedProjectId != null) {
            requireProjectAccess(me, resolvedProjectId);
        }

        UUID id = UUID.randomUUID();"""
new_proj_access = """        if (resolvedProjectId != null) {
            requireProjectAccess(me, resolvedProjectId);
        }

        // Part 4.1 — IDOR validation for commercial links (bc_id + facture_id)
        if (req.bcId() != null) {
            validateCommercialLink(req.bcId(), me.getOrgId(), resolvedProjectId,
                    "purchase_order", "PAYMENT_BC_PROJECT_MISMATCH");
        }
        if (req.factureId() != null) {
            validateCommercialLink(req.factureId(), me.getOrgId(), resolvedProjectId,
                    "facture", "PAYMENT_FACTURE_PROJECT_MISMATCH");
        }

        UUID id = UUID.randomUUID();"""
assert old_proj_access in s, "project access block not found"
s = s.replace(old_proj_access, new_proj_access, 1)

# Add the validateCommercialLink helper (before fetchMovementForRead)
old_fmr_method = "    private Map<String, Object> fetchMovementForRead(UUID id, UUID orgId) {"
new_fmr_method = """    /**
     * Part 4.1 — Validate that a commercial link (BC or facture) belongs to
     * the caller's org AND its project matches the movement's project.
     * Throws BusinessRuleException on mismatch (IDOR-safe).
     */
    private void validateCommercialLink(UUID linkId, UUID orgId, UUID movementProjectId,
                                          String tableName, String errorCode) {
        Map<String, Object> row = jdbc.queryForMap(
                "select project_id from " + tableName + " where id = ? and org_id = ?",
                linkId, orgId);
        UUID linkProjectId = (UUID) row.get("project_id");
        if (movementProjectId != null && linkProjectId != null
                && !movementProjectId.equals(linkProjectId)) {
            throw new BusinessRuleException(errorCode,
                    "Le " + tableName + " n'appartient pas au même projet que le paiement");
        }
    }

    private Map<String, Object> fetchMovementForRead(UUID id, UUID orgId) {"""
assert old_fmr_method in s, "fetchMovementForRead method not found"
s = s.replace(old_fmr_method, new_fmr_method, 1)

p.write_text(s, encoding='utf-8')
print(f"CashService.java — 10 edits applied")

# ═══ PaymentFacade.java ════════════════════════════════════════════════
p = Path('/tmp/my-project/backend/src/main/java/tn/hydra/cash/PaymentFacade.java')
s = p.read_text(encoding='utf-8')

# 11. SubmitPaymentRequest: +factureId
old_req = """            String voucherNo,
            String nature,
            String invoiceRef,
            UUID bcId
    ) {}"""
new_req = """            String voucherNo,
            String nature,
            String invoiceRef,
            UUID bcId,
            UUID factureId
    ) {}"""
assert old_req in s, "SubmitPaymentRequest end not found"
s = s.replace(old_req, new_req, 1)

# 12. draftReq: pass req.factureId()
old_draft = """                req.invoiceRef(),
                req.bcId()
        );"""
new_draft = """                req.invoiceRef(),
                req.bcId(),
                req.factureId()
        );"""
assert old_draft in s, "draftReq bcId not found"
s = s.replace(old_draft, new_draft, 1)

p.write_text(s, encoding='utf-8')
print(f"PaymentFacade.java — 2 edits applied")
