# Signalements — Reference v2 (T1–T7)

## Types

| Code | Label (FR) | Icon | Colour | Default delay | « après » photo to resolve |
|------|-----------|------|--------|---------------|----------------------------|
| PROBLEME | Problème chantier | pi-exclamation-triangle | #ea580c | 2 days | no |
| RESERVE | Réserve / non-conformité | pi-flag | #dc2626 | 15 days | yes |
| DEMANDE | Demande | pi-question-circle | #2563eb | 3 days | no |
| VISA_DOCUMENT | Visa de document | pi-file-check | #7c3aed | — | no |

### Categories
- **PROBLEME**: MATERIEL_MANQUANT, PANNE_ENGIN, EQUIPE_ABSENTE, METEO, ACCES_TERRAIN, ERREUR_PLAN, AUTRE
- **RESERVE**: MALFACON, NON_FACON, ESSAI, SECURITE, AUTRE
- **DEMANDE**: MATERIEL, INFORMATION, DECISION, AUTRE
- **VISA_DOCUMENT**: none

A RESERVE also carries an **origine**: `CLIENT` (visite client / MOA) or `INTERNE` (contrôle interne, default).

## Statuses

**PROBLEME / RESERVE / DEMANDE** — universal lifecycle:
```
OPEN → IN_PROGRESS → RESOLVED → CLOSED
  ↘ CANCELLED           ↘ IN_PROGRESS (reopen)
```

| Status | Label | Colour token | Who moves it out |
|--------|-------|-------------|------------------|
| OPEN | Ouvert | --proc-pending | Assignee or creator |
| IN_PROGRESS | En cours | --proc-running | Assignee |
| RESOLVED | Résolu — à vérifier | --proc-pending | Verifier (creator/owner/manager) |
| CLOSED | Clos | --proc-done | — |
| CANCELLED | Annulé | --proc-muted | — |

**VISA_DOCUMENT** — transmittal workflow (unchanged): DRAFT → IN_REVIEW → APPROVED → IFC → AS_BUILT.

## Rules
- **Resolve**: note ≥ 5 chars (saved in `resolution_note`). RESERVE also needs ≥ 1 AFTER photo.
- **Verify (close)**: creator, owner, or project manager (ADMIN/MANAGER). RESERVE: closer ≠ resolver.
- **Reopen / cancel**: comment ≥ 5 chars.
- **Overdue**: `due_at < today` and status not in CLOSED/CANCELLED/AS_BUILT → red « En retard » badge.
- A create call with no type defaults to VISA_DOCUMENT + DRAFT.

## Where photos are filed (FolderFilingService)
- RESERVE → `04_QUAL.RESERVES`
- PROBLEME, DEMANDE → `03_EXEC.PHOTOS`
- VISA_DOCUMENT → `02_TECH.PLANS`

Title format: « <type label> — <ticket title> — avant|après|autre ».

## Endpoints

### Catalog
`GET /api/tickets/catalog` → 4 types with labels, icons, colours, categories, statuses.
```json
[{"code":"PROBLEME","label":"Problème chantier","icon":"pi-exclamation-triangle","colour":"#ea580c","defaultDays":2,"requiresAfterPhoto":false,"categories":[{"code":"MATERIEL_MANQUANT","label":"Matériel manquant"},...],"statuses":[{"code":"OPEN","label":"Ouvert"},...]}]
```

### List (old, kept)
`GET /api/tickets?projectId=&assignedTo=&status=&priority=&type=&mine=`

### Search (paginated)
`GET /api/tickets/search?projectId&type&status&mine&overdue&q&page&size` → `{items, total, page, size}`. Items = TicketResponse + photoCount. Order: overdue first, dueAt asc, createdAt desc.

### Summary
`GET /api/tickets/summary?projectId` → `{mine, overdue, toVerify, open, inProgress, byType}`.

### Create
`POST /api/tickets` → `{title, description, projectId, assignedToId, priority, startAt, dueAt, documentId, type, category, origin, locationText, ouvrageId, responsiblePartyId}`.
```json
{"title":"Fuite regard","projectId":"...","type":"PROBLEME","category":"METEO","dueAt":"2026-11-04"}
```
Response: TicketResponse (with type, typeLabel, category, categoryLabel, origin, locationText, ouvrageId/Name, responsiblePartyId/Name, statusLabel, overdue).

### Create with photos
`POST /api/tickets/with-photos` (multipart): part "ticket" = JSON, parts "photos" = image files (all BEFORE). One transaction.

### Get one
`GET /api/tickets/{id}` → TicketResponse.

### Actions (PROBLEME/RESERVE/DEMANDE)
`GET /api/tickets/{id}/actions` → `["START","RESOLVE","COMMENT","ADD_PHOTO"]` (filtered by role + status).

`POST /api/tickets/{id}/start` — OPEN → IN_PROGRESS.
`POST /api/tickets/{id}/resolve` `{note}` — → RESOLVED. RESERVE needs ≥ 1 AFTER photo.
`POST /api/tickets/{id}/verify` — RESOLVED → CLOSED.
`POST /api/tickets/{id}/reopen` `{comment}` — RESOLVED → IN_PROGRESS.
`POST /api/tickets/{id}/cancel` `{reason}` — → CANCELLED.

Each = one transaction: status change + STATUS_CHANGE row in ticket_comment + TICKET_STATUS_CHANGED domain event.

### Status (VISA only)
`POST /api/tickets/{id}/status?status=XX` — VISA_DOCUMENT only; for other types returns 400 « Utilisez les actions du signalement ».

### Photos
`POST /api/tickets/{id}/photos` (multipart: file, phase) → PhotoResponse.
`GET /api/tickets/{id}/photos` → `[{id, documentId, phase, title, createdBy, createdAt, downloadUrl}]`.
`DELETE /api/tickets/{id}/photos/{attachmentId}` — uploader only, not CLOSED/CANCELLED.

### Comments + Timeline
`POST /api/tickets/{id}/comments` `{body}` (1-2000 chars).
`GET /api/tickets/{id}/timeline` → comments + status changes, oldest first (authorName, authorRole, fromStatusLabel, toStatusLabel).

### Documents (old, kept)
`GET /api/tickets/{id}/documents` → linked documents.
`GET /api/tickets/{id}/transitions` → allowed next statuses (VISA).

## Schema
- `ticket` (V1+V6+V7+V13+V48): type, category, origin, location_text, ouvrage_id, responsible_party_id, resolution_note, closed_at, closed_by, cancel_reason.
- `ticket_attachment` (V49): ticket_id, document_id, phase BEFORE/AFTER/OTHER, unique(ticket_id, document_id).
- `ticket_comment` (V50): author_id, kind COMMENT/STATUS_CHANGE/SYSTEM, body, from_status, to_status.

## Domain events
- `TICKET_CREATED` — on create + with-photos (subjectType TICKET).
- `TICKET_STATUS_CHANGED` — on every action + VISA status move.
