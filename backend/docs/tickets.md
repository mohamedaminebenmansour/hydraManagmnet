# Tickets Module — Work Items + Optional Process

## Concept
- **Ticket** = "something to handle" (question, site issue, request).
- **Process** = multi-step approval when required.
- They are separate concepts. A ticket CAN have a linked process instance, but doesn't have to.

## Schema (existing, no new migration)
- `ticket` table (V1 + V6 + V7 + V13): id, title, description, status, project_id, assigned_to, priority, start_at, due_at, document_id
- Status values: `DRAFT | IN_REVIEW | APPROVED | IFC | AS_BUILT` (V13 engineering transmittal workflow)
- Process instance can link to a ticket via `subjectType=TICKET, subjectId=ticketId`

## APIs (existing)
- `GET /api/tickets?projectId=&assignedToId=&status=` — list
- `GET /api/tickets/{id}` — detail
- `POST /api/tickets` — create { title, description, projectId, assignedToId, priority, startAt, dueAt, documentId }
- `PUT /api/tickets/{id}` — update
- `POST /api/tickets/{id}/status` — change status
- `POST /api/tickets/{id}/close` — close
- `GET /api/tickets/{id}/documents` — linked documents
- `GET /api/tickets/{id}/transitions` — allowed status transitions

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
