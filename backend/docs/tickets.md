# Tickets Module — Work Items + Optional Process

## Concept
- **Ticket** = "something to handle" (question, site issue, request).
- **Process** = multi-step approval when required.
- They are separate concepts. A ticket CAN have a linked process instance, but doesn't have to.

## Schema
- `ticket` table (V1 + V6 + V7 + V13 + **V48**): id, title, description, status, project_id, assigned_to, priority, start_at, due_at, document_id, plus the V48 signalement columns: `type`, `category`, `origin`, `location_text`, `ouvrage_id` (→ ouvrage), `responsible_party_id` (→ party), `resolution_note`, `closed_at`, `closed_by`, `cancel_reason`.
- `type` ∈ `PROBLEME | RESERVE | DEMANDE | VISA_DOCUMENT` (not null, default `VISA_DOCUMENT`).
- `origin` ∈ `CLIENT | INTERNE`, only meaningful for RESERVE (default `INTERNE`); null for the other types.
- Status check is **type-conditional** (V48 replaces the old `ticket_status_check`):
  - VISA_DOCUMENT → `DRAFT | IN_REVIEW | APPROVED | IFC | AS_BUILT`
  - PROBLEME / RESERVE / DEMANDE → `OPEN | IN_PROGRESS | RESOLVED | CLOSED | CANCELLED`
- Indexes (V48): `(org_id, project_id, type, status)`, `(org_id, assigned_to, status)`, `(org_id, due_at)`.
- `ticket_attachment` table (V49): id, org_id, ticket_id (→ ticket, on delete cascade), document_id (→ document), phase ∈ `BEFORE | AFTER | OTHER`, created_by, created_at, unique(ticket_id, document_id). Index `(org_id, ticket_id)`. Photos are stored as documents (via `DocumentService.upload`) and linked to the ticket here.
- `ticket_comment` table (V50): id, org_id, ticket_id (→ ticket, on delete cascade), author_id (→ app_user), kind ∈ `COMMENT | STATUS_CHANGE | SYSTEM`, body, from_status, to_status, created_at. Index `(org_id, ticket_id, created_at)`. One SYSTEM row on create (« Signalement créé »), one STATUS_CHANGE per action / VISA status move, COMMENT rows for free-form comments.
- Process instance can link to a ticket via `subjectType=TICKET, subjectId=ticketId`

## Types and lifecycle (V48)

A ticket has a **type** (default `VISA_DOCUMENT`):

| Type | Label (FR) | Icon | Colour | Default delay | « après » photo to resolve |
|------|-----------|------|--------|---------------|----------------------------|
| PROBLEME | Problème chantier | pi-exclamation-triangle | #ea580c | 2 days | no |
| RESERVE | Réserve / non-conformité | pi-flag | #dc2626 | 15 days | yes |
| DEMANDE | Demande | pi-question-circle | #2563eb | 3 days | no |
| VISA_DOCUMENT | Visa de document | pi-file-check | #7c3aed | — | no |

Each type owns an ordered list of **categories** (code → French label); see `TicketType` or `GET /api/tickets/catalog` for the full list. A RESERVE also carries an **origine**: `CLIENT` (visite client / maître d'ouvrage) or `INTERNE` (contrôle interne, default).

**Lifecycle** — PROBLEME / RESERVE / DEMANDE share one universal graph; VISA_DOCUMENT keeps the transmittal workflow:
```
OPEN → IN_PROGRESS → RESOLVED → CLOSED        (VISA: DRAFT → IN_REVIEW → APPROVED → IFC → AS_BUILT)
  ↘ CANCELLED           ↘ IN_PROGRESS (reopen)
```
Rules (T1 owns the graph; T3 owns the role/note/photo rules):
- A create call with no type defaults to `VISA_DOCUMENT` + `DRAFT`, so old clients and old tests work unchanged.
- Category is required for PROBLEME/RESERVE/DEMANDE (and must belong to the type); forbidden for VISA_DOCUMENT.
- `ouvrageId` must belong to the same chantier (project + org); `responsiblePartyId` to the same org.
- `dueAt` missing + the type has a default delay → today + default days (VISA_DOCUMENT has none).
- **Overdue** = `due_at` before today and status not in `CLOSED | CANCELLED | AS_BUILT` (shown as a red « En retard » badge beside the status, never as a status).

## APIs
- `GET /api/tickets?projectId=&assignedTo=&status=&priority=&type=&mine=` — list (`type` filters by signalement type)
- `GET /api/tickets/catalog` — reference catalogue: the 4 types with French label, icon, colour, default delay, requiresAfterPhoto, ordered categories, and the statuses valid per type. The frontend reads labels only from here.
- `GET /api/tickets/{id}` — detail (response now also carries type, typeLabel, category, categoryLabel, origin, locationText, ouvrageId/ouvrageName, responsiblePartyId/responsiblePartyName, statusLabel, overdue)
- `POST /api/tickets` — create `{ title, description, projectId, assignedToId, priority, startAt, dueAt, documentId, type, category, origin, locationText, ouvrageId, responsiblePartyId }`
- `PUT /api/tickets/{id}` — update
- `POST /api/tickets/{id}/status` — VISA_DOCUMENT only (validated through `TicketWorkflow`); for PROBLEME/RESERVE/DEMANDE returns 400 « Utilisez les actions du signalement ». Also writes a STATUS_CHANGE row.
- `POST /api/tickets/{id}/close` — close (VISA_DOCUMENT → AS_BUILT, as before)
- `GET /api/tickets/{id}/documents` — linked documents
- `GET /api/tickets/{id}/transitions` — allowed status transitions for this ticket's type
- `POST /api/tickets/with-photos` (multipart) — create a ticket + 0..10 BEFORE photos in one transaction; part "ticket" = the same JSON as POST /api/tickets, parts "photos" = image files. If one photo fails, no ticket is created.
- `POST /api/tickets/{id}/photos` (multipart: file, phase) — attach one photo (phase BEFORE/AFTER/OTHER)
- `GET /api/tickets/{id}/photos` — `[{id, documentId, phase, title, createdBy, createdAt, downloadUrl}]` (downloadUrl points at the document download endpoint)
- `DELETE /api/tickets/{id}/photos/{attachmentId}` — only the uploader, only while not CLOSED/CANCELLED
- `GET /api/tickets/search?projectId&type&status&mine&overdue&q&page&size` — paginated `{items, total, page, size}`, items = TicketResponse + photoCount. Order: overdue first, then dueAt asc, then createdAt desc.
- `GET /api/tickets/summary?projectId` — `{mine, overdue, toVerify, open, inProgress, byType}` (not-closed/not-cancelled only)
- `POST /api/tickets/{id}/start` — OPEN → IN_PROGRESS (assignee, or creator if no assignee)
- `POST /api/tickets/{id}/resolve` `{note}` — OPEN|IN_PROGRESS → RESOLVED (assignee/creator; note ≥ 5 chars saved in `resolution_note`; RESERVE needs ≥ 1 AFTER photo)
- `POST /api/tickets/{id}/verify` — RESOLVED → CLOSED (creator, owner, or project manager ADMIN/MANAGER; RESERVE: verifier ≠ resolver)
- `POST /api/tickets/{id}/reopen` `{comment}` — RESOLVED → IN_PROGRESS (creator/owner/manager; comment ≥ 5 chars)
- `POST /api/tickets/{id}/cancel` `{reason}` — OPEN|IN_PROGRESS → CANCELLED (creator/owner/manager; reason ≥ 5 chars)
- `GET /api/tickets/{id}/actions` — action codes the current user may do now (START, RESOLVE, VERIFY, REOPEN, CANCEL, COMMENT, ADD_PHOTO; VISA returns the allowed status codes)
- `POST /api/tickets/{id}/comments` `{body}` — add a comment (1..2000 chars, by anyone who can read the ticket)
- `GET /api/tickets/{id}/timeline` — comments + status changes, oldest first (authorName, authorRole, fromStatusLabel, toStatusLabel)

## Photos (V49)

Photos are stored as documents through `DocumentService.upload` (folder rights and local/S3 storage respected), filed by type:
- RESERVE → `04_QUAL.RESERVES`
- PROBLEME, DEMANDE → `03_EXEC.PHOTOS`
- VISA_DOCUMENT → `02_TECH.PLANS`

Title: « <type label> — <ticket title> — avant|après|autre ». Each photo is linked to the ticket by a `ticket_attachment` row with a phase (`BEFORE`, `AFTER`, `OTHER`). A RESERVE needs at least one « après » (AFTER) photo to resolve (enforced in T3).

Limits: `image/jpeg | image/png | image/webp` only; 5 Mo per file; 10 photos per ticket. French errors: « Seules les images JPEG, PNG ou WebP sont acceptées », « Photo trop lourde (max 5 Mo) », « 10 photos maximum par signalement ».

`createWithPhotos` is one transaction (AI-RULES 14): photos are validated up front; if one fails, no ticket is created. If a photo fails while being stored, the transaction rolls back and every file already written is deleted (same pattern as `BordereauImportService.deleteStoredFileOnRollback`).

A `TICKET_CREATED` domain event is published in `create` and `with-photos` (same transaction), `subjectType = "TICKET"`.

## Process hook for TICKET (TASK 4)
When a process instance with `subjectType=TICKET` reaches COMPLETED:
- `ProcessService.completeStep` runs a JDBC update: `ticket.status = 'APPROVED'`
- Audit: `TICKET_APPROVED_BY_PROCESS` with instanceId + approvedBy
- On REJECTED: the ticket keeps its current status (the process rejection is visible in the timeline)

## How to start a process on a ticket
```bash
POST /api/processes/start
{
  "projectId": "<project-id>",
  "templateKey": "<template-with-TICKET-subject>",
  "subjectType": "TICKET",
  "subjectId": "<ticket-id>"
}
```
The process engine accepts any `subjectType` string — `TICKET` is now handled on completion. No schema change needed.

## Frontend
- Tickets list: `/tickets` (table + cards, dual-view pattern available)
- Ticket detail: `/tickets/{id}`
- Ticket creation: inline form on the list page
- "Lancer un processus" button: calls `POST /api/processes/start` with `subjectType=TICKET`
- Process strip: if the ticket has a linked process, show the step strip (same as movement detail)

## Test scenario
1. Create a ticket on a project (title: "Demande approvisionnement ciment").
2. Assign to a user.
3. Start a process on the ticket: `POST /api/processes/start { projectId, templateKey, subjectType: "TICKET", subjectId: ticketId }`.
4. Complete all steps → ticket status automatically set to APPROVED.
5. Ticket detail shows the process strip + APPROVED status.
