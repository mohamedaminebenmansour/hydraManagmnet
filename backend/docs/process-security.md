# Process Security — Audit & Consistency Pass

## Threat model

| Threat | Risk | Mitigation | Status |
|--------|------|------------|--------|
| **IDOR on complete step** — user completes another user's step | HIGH | `requireStepAccess` checks: owner → assignee → role match. Throws `NOT_ALLOWED` if none match. | ✅ Already secure |
| **IDOR on reject step** | HIGH | Same `requireStepAccess` check on reject path. | ✅ Already secure |
| **Template CRUD as non-owner** | HIGH | `requireOwner(me)` in service + `@PreAuthorize("hasRole('OWNER')")` on controller. | ✅ Fixed (added @PreAuthorize) |
| **Start process on other org's project** | MEDIUM | `requireProjectAccess(me, projectId)` in `start()` checks org + project membership. | ✅ Already secure |
| **Ticket IDOR — read ticket from project you're not a member of** | MEDIUM | Added project membership check in `get()` + `list()` for non-owner users. | ✅ Fixed |
| **Ticket write as non-member** | LOW | `requireTicket(me, id)` checks org. `@PreAuthorize("hasAuthority('PERM_TICKET_WRITE')")` on controller. | ✅ Already secure |
| **Cash movement edit POSTED** | HIGH | `POSTED_IMMUTABLE` + `MOVEMENT_VOID` guards in CashService. | ✅ Already secure |
| **Cash void as finance (non-owner)** | HIGH | `@PreAuthorize("hasAuthority('PERM_CASH_VOID')")` — finance doesn't have it. | ✅ Already secure |
| **Reassign step as non-admin** | MEDIUM | `requireProjectAdminOrOwner` in service. | ✅ Already secure |
| **Approval desk read as non-member** | MEDIUM | `requireProjectAccess(me, projectId)` in `getApprovalDesk()`. | ✅ Already secure |

## What was fixed

### 1. ProcessController — added @PreAuthorize (defense-in-depth)

All template CRUD + step CRUD + project enablement + role-assignee write endpoints
now have `@PreAuthorize("hasRole('OWNER')")` at the HTTP layer. Previously these
relied solely on `requireOwner(me)` in the service layer, which is correct but
not defense-in-depth.

Endpoints protected:
- POST /api/processes/templates (create)
- PUT /api/processes/templates/{id} (update)
- DELETE /api/processes/templates/{id} (delete)
- PUT /api/processes/templates/{id}/steps/reorder
- POST /api/processes/templates/{id}/steps (add step)
- PUT /api/processes/templates/{id}/steps/{stepId} (update step)
- DELETE /api/processes/templates/{id}/steps/{stepId} (delete step)
- PUT /api/projects/{projectId}/process-templates (enable on project)
- PUT /api/projects/{projectId}/process-role-assignees (map roles)
- DELETE /api/projects/{projectId}/process-role-assignees/{roleCode} (unmap)

Runtime endpoints (start, complete, reject, cancel, reassign, read) remain open
to authenticated users — the service layer enforces the real auth (project
membership, assignee check, etc.).

### 2. TicketService — IDOR fix on get + list

**Before:** any authenticated user in the org could read any ticket by ID, even
tickets on projects they're not members of.

**After:** non-owner users can only see tickets where:
- `ticket.project_id IS NULL` (org-wide ticket), OR
- They are a `project_member` of the ticket's project.

Owner sees everything (unchanged).

Same filter applied to `list()`: results are post-filtered against the user's
accessible project IDs (one query, not per-ticket N+1).

## Audit log coverage

| Action | Audit event | Where logged |
|--------|-------------|--------------|
| Create template | PROCESS_TEMPLATE_CREATED | ProcessService.createTemplate |
| Update template | PROCESS_TEMPLATE_UPDATED | ProcessService.updateTemplate |
| Delete template | PROCESS_TEMPLATE_DELETED | ProcessService.deleteTemplate |
| Start process | PROCESS_STARTED | ProcessService.start |
| Complete step | STEP_COMPLETED (enriched: subjectType, subjectId, hasAttachment, assessedAmount) | ProcessService.completeStep |
| Reject step | STEP_REJECTED (enriched) | ProcessService.rejectStep |
| Process completed | PROCESS_COMPLETED | ProcessService.completeStep |
| Process rejected | PROCESS_REJECTED | ProcessService.rejectStep |
| Reassign step | PROCESS_STEP_REASSIGNED | ProcessService.reassignStep |
| Ticket created | TICKET_CREATED | TicketService.create |
| Ticket updated | TICKET_UPDATED | TicketService.update |
| Ticket status changed | TICKET_STATUS_CHANGED | TicketService.setStatus |
| Ticket closed | TICKET_CLOSED | TicketService.close |
| Cash movement created | CASH_MOVEMENT_CREATED | CashService.createMovement |
| Cash submitted | CASH_MOVEMENT_SUBMITTED | PaymentFacade.submitPayment |
| Cash posted | CASH_MOVEMENT_POSTED | CashService.postMovement |
| Cash voided | CASH_MOVEMENT_VOIDED (enriched: amount, type, pot, party) | CashService.voidMovement |
| Cash void requested | CASH_VOID_REQUESTED | CashService.requestVoid |
| Ticket approved by process | TICKET_APPROVED_BY_PROCESS | ProcessService.completeStep (TICKET hook) |

## Manual checklist

1. ✅ Login as non-owner (e.g. chef de projet) → try POST /api/processes/templates → 403 Forbidden
2. ✅ Login as owner → create template → 201 Created
3. ✅ Non-owner tries to complete another user's step → 403 NOT_ALLOWED
4. ✅ Non-owner tries GET /api/tickets/{id} on a ticket from a project they're not a member of → 404 (null = not found, no info leak)
5. ✅ Non-owner lists tickets → only sees tickets from their projects
6. ✅ Finance tries to void a POSTED movement → 403 (no PERM_CASH_VOID)
7. ✅ Finance tries to edit a POSTED movement → 409 POSTED_IMMUTABLE
