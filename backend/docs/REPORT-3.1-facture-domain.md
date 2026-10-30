# REPORT 3.1 — Facture (supplier invoice) domain + API

> **Scope:** Part 3.1 ONLY — facture header + lines + status lifecycle + BC/BL lineage + supplier consistency.
> **Status:** ✅ `mvn package` BUILD SUCCESS · ✅ 8/8 E2E PASS · ✅ V34 applied · ✅ Zero edits to stock_move/cash/process

---

## 1. Deliverables (8 new files, 0 edits)

| File | Purpose |
|---|---|
| `V34__factures.sql` | 2 tables (facture + facture_line) + 9 indexes + RLS off + CHECK on status |
| `domain/Facture.java` | JPA entity (header: project, supplier, BC/BL links, amounts, doc) |
| `domain/FactureLine.java` | JPA entity (line: item/bc_line/bl_line lineage, qty, unit_price, line_ht) |
| `domain/FactureStatus.java` | enum: DRAFT, VALIDATED, CANCELLED |
| `repository/FactureRepository.java` | org-scoped queries |
| `repository/FactureLineRepository.java` | org-scoped + countByFactureId |
| `FactureService.java` (696 lines) | Business logic + 11 DTOs + IDOR + audit + supplier/BC/BL consistency |
| `FactureController.java` (115 lines) | 10 REST endpoints under `/api/factures` |

## 2. APIs (10 endpoints)

| # | Method | Path | Perm | Notes |
|---|--------|------|------|-------|
| 1 | POST | `/api/factures` | STOCK_MOVE | Create DRAFT facture + lines |
| 2 | GET | `/api/factures?projectId=&status=` | STOCK_VIEW | List with lineCount (no N+1) |
| 3 | GET | `/api/factures/{id}` | STOCK_VIEW | Detail with embedded lines |
| 4 | PATCH | `/api/factures/{id}` | STOCK_MOVE | DRAFT only |
| 5 | POST | `/api/factures/{id}/lines` | STOCK_MOVE | Add line to DRAFT |
| 6 | PATCH | `/api/factures/{id}/lines/{lineId}` | STOCK_MOVE | Update line in DRAFT |
| 7 | DELETE | `/api/factures/{id}/lines/{lineId}` | STOCK_MOVE | Remove line in DRAFT |
| 8 | POST | `/api/factures/{id}/validate` | STOCK_MOVE | DRAFT→VALIDATED (requires ≥1 line) |
| 9 | POST | `/api/factures/{id}/cancel` | STOCK_MOVE | DRAFT/VALIDATED→CANCELLED (with reason) |
| 10 | POST | `/api/factures/{id}/documents` | STOCK_MOVE | Link facture PDF (folder kind=FACTURE) |

## 3. Business rules

- **DRAFT**: editable lines + header
- **VALIDATED**: lines frozen; included in future match aggregates
- **CANCELLED**: terminal; excluded from match
- **Free facture** (bc_id + bl_id null): allowed — prefer linking when available
- **Supplier mismatch** (facture supplier ≠ BC supplier, both set) → `409 FACTURE_SUPPLIER_MISMATCH`
- **BL/BC mismatch** (BL's bc_id ≠ facture's bc_id, both set) → `409 FACTURE_BL_BC_MISMATCH`
- **Document folder**: `GET /api/stock/doc-folder?kind=FACTURE` resolves to `05.03 FACTURE` (verified S29)

## 4. E2E results — 8/8 PASS

| # | Test | Result | Evidence |
|---|---|---|---|
| S24 | Login | ✅ | owner@hydra.tn |
| S25 | Create DRAFT facture + lines → validate → immutable | ✅ | DRAFT→VALIDATED, line PATCH blocked with `FACTURE_NOT_DRAFT` |
| S26 | Cancel VALIDATED facture | ✅ | VALIDATED→CANCELLED with reason |
| S27 | IDOR cross-project blocked | ✅ | karim P1→P2 = `FORBIDDEN` |
| S28 | List lineCount (no N+1) | ✅ | 1 facture, all have lineCount=True |
| S29 | Doc folder kind=FACTURE | ✅ | code=`05.03 FACTURE` |
| S30 | Supplier mismatch | ✅ | `FACTURE_SUPPLIER_MISMATCH` |
| S31 | Validate requires ≥1 line | ✅ | `FACTURE_NO_LINES` |

## 5. Audit events (4 types)

`STOCK_FACTURE_CREATED`, `STOCK_FACTURE_VALIDATED`, `STOCK_FACTURE_CANCELLED`, `STOCK_FACTURE_DOCUMENT_LINKED` (+ line CRUD events)

## 6. Ready for Part 3.2?

**YES** — the facture_line table carries `bc_line_id` + `bl_line_id` lineage. Part 3.2 (soft match with invoiced qty) can join `facture_line` → `purchase_order_line` → `bon_livraison_line` → `stock_move` in a single SQL to compute `qty_invoiced` alongside `qty_ordered` / `qty_received` / `qty_in_stock_moves`.
