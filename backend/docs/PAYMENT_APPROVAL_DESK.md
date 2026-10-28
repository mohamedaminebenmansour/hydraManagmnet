# Payment Approval Desk

> Single-payload API for an approver to decide on a `CASH_MOVEMENT`-linked
> payment step. Built on top of the existing `ProcessService` + `CashService` —
> no second workflow engine, no second ledger.

## Endpoint

### `GET /api/process/steps/{stepInstanceId}/approval-desk`

Returns everything an approver needs to decide in one round-trip (2 SQL
queries total — 1 main JOIN + 1 step-list — no per-field N+1).

**Permission**: caller must be the step assignee, OR hold the business role
on the project, OR be org owner. Non-assignees who pass the access check
(project members) get the payload read-only (`canComplete=false`,
`canReject=false`).

**Errors**:
- `404 STEP_NOT_FOUND` — stepInstanceId doesn't exist.
- `400 SUBJECT_NOT_SUPPORTED_FOR_DESK` — the step's process subject is not
  `CASH_MOVEMENT` (the desk is purpose-built for payment approvals).
- `404 PROJECT_NOT_FOUND` — caller is not a project member and not owner.

### Response shape (`ApprovalDeskResponse`)

```json
{
  "stepInstanceId": "uuid",
  "instanceId": "uuid",
  "templateKey": "PAYMENT_APPROVAL",
  "templateName": "Approbation de paiement",
  "instanceStatus": "RUNNING",
  "currentStepOrder": 2,
  "currentStepKey": "APPROVE",
  "currentStepName": "Owner approves",
  "currentStepRole": "OWNER",
  "assigneeUserId": "uuid",
  "assigneeName": "Gérant",
  "requiresAttachment": true,

  "subjectType": "CASH_MOVEMENT",
  "subjectId": "uuid (movement id)",

  "movementId": "uuid",
  "movementLabel": "Paiement Pipetech — Tronçon 3",
  "movementDate": "2026-10-28",
  "movementType": "OUT",
  "movementStatus": "DRAFT",
  "assessedAmount": 1250.000,
  "claimedAmount": 1500.000,
  "varianceAmount": 250.000,
  "varianceReason": "Retenue de garantie 5%",
  "paymentMode": "CHEQUE",
  "paymentRef": "CHQ-2026-1042",
  "voucherNo": "BON-0142",
  "nature": "SOUS_TRAITANCE",
  "invoiceRef": "FACT-2026-018",

  "projectId": "uuid",
  "projectName": "Adduction Aïn Draham",
  "partyId": "uuid",
  "partyName": "BTP Tunisie Sud SARL",
  "partyType": "SUB",
  "fromPotId": "uuid",
  "fromPotName": "Caisse Adduction Aïn Draham",

  "documentId": "uuid or null",
  "documentName": "Facture Pipetech PIP-2026-0142.pdf or null",

  "canComplete": true,
  "canReject": true,
  "hasAttachment": true,

  "steps": [
    {
      "stepOrder": 1,
      "stepKey": "DRAFT",
      "name": "Create/confirm payment intent",
      "status": "DONE",
      "assigneeRole": "FINANCE",
      "assigneeName": "Safa Mtibaa",
      "completedAt": "2026-10-28T12:13:30Z",
      "comment": "Dossier vérifié — OK pour paiement"
    }
  ]
}
```

## Business rules

### Attachment guard (`requires_attachment`)

When a step's `requires_attachment` flag is `true`, the approver MUST have a
proof document before they can complete the step. The proof may be:

1. **A document linked on the step itself** via `process_step_document`
   (passed in `req.documentIds` on the complete call) — the approver attaches
   at decision time; OR
2. **The payment-proof document on the linked movement** (`cash_movement.document_id`,
   uploaded via `POST /api/cash/movements/{id}/attachment`) — already there
   from the payment submit flow.

Either counts. If neither is present → `400 ATTACHMENT_REQUIRED` with a French
message explaining both options.

### Complete vs Reject

- **Complete** (`POST /api/processes/{instanceId}/steps/{stepInstanceId}/complete`):
  - `comment` is optional.
  - `documentIds` is optional (unless `requires_attachment`).
  - Marks the step `DONE`, advances to the next step OR completes the process.
  - On the **last step**: triggers `CashService.onProcessCompletedPostMovement`
    which flips the linked movement to `POSTED` (immutable) + updates the pot
    balance. If the pot has insufficient balance → `INSUFFICIENT_BALANCE`
    French error, entire `@Transactional` rolls back (step stays PENDING).

- **Reject** (`POST /api/processes/{instanceId}/steps/{stepInstanceId}/reject`):
  - `comment` is **required** (non-blank) → `400 COMMENT_REQUIRED` otherwise.
  - Marks the step `REJECTED` + the instance `REJECTED`.
  - Cash hook: clears `process_instance_id` on the movement so the user can
    edit + resubmit. Movement stays `DRAFT`. Audit `CASH_MOVEMENT_REJECTED`
    is emitted by `CashService`.

### `canComplete` / `canReject` flags

True only when ALL of:
- caller is the step assignee (or owner, or holds the business role on the
  project via `canActOnStep`);
- the step is `PENDING`;
- the instance is `RUNNING`.

Read-only viewers (project members who aren't the assignee) get `false` on
both — they can see the dossier but can't act on it.

## Audit actions (TASK 3 — enriched details JSON)

Every complete/reject is audited via `EventLogService`. The `details` JSON now
carries enough to answer "who approved, with which document, on what amount"
without re-querying the movement:

### `STEP_COMPLETED`

```json
{
  "instanceId": "uuid",
  "stepKey": "DRAFT",
  "comment": "Dossier vérifié — OK pour paiement",
  "subjectType": "CASH_MOVEMENT",
  "subjectId": "uuid (movement id)",
  "hasAttachment": false,
  "assessedAmount": 150.000
}
```

### `STEP_REJECTED`

Same shape as `STEP_COMPLETED` (with the reject comment in `comment`).

### `STEP_STARTED`

```json
{
  "instanceId": "uuid",
  "stepKey": "APPROVE",
  "stepOrder": 2
}
```

### `PROCESS_COMPLETED` (last step)

```json
{ "completedBy": "uuid" }
```

### `PROCESS_REJECTED`

```json
{ "rejectedBy": "uuid", "comment": "..." }
```

### Movement timeline (already merged)

The `GET /api/cash/movements/{id}/timeline` endpoint already merges 3 event
sources (single UNION query, no N+1):
1. Direct cash_movement events (`CASH_MOVEMENT_CREATED`, `_SUBMITTED`,
   `_POSTED`, `_VOIDED`, `_REJECTED`).
2. Process-instance lifecycle events (`PROCESS_STARTED`, `_COMPLETED`,
   `_REJECTED`, `_CANCELLED`).
3. Process-step events (`STEP_STARTED`, `STEP_COMPLETED`, `STEP_REJECTED`).

So the owner sees the full story — "payment submitted → finance approved →
owner approved → cash posted" — in one timeline call.

## How to test with a fake PDF

### Scenario: create payment with variance + complete as finance + complete as owner

```bash
# 1. Login as owner (or any CASH_SUBMIT user)
curl -c /tmp/c.txt http://localhost:8080/api/auth/me
XSRF=$(grep XSRF-TOKEN /tmp/c.txt | awk '{print $7}')
curl -b /tmp/c.txt -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"email":"owner@hydra.tn","password":"HydraOwner2026!"}'

# 2. Submit a payment with claimed ≠ assessed (variance reason required)
curl -b /tmp/c.txt -X POST http://localhost:8080/api/cash/movements/submit-payment \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{
    "fromPotId": "<company-pot-id>",
    "amount": 1250.000,
    "movementDate": "2026-10-28",
    "projectId": "<project-id>",
    "partyId": "<party-id>",
    "label": "Paiement Pipetech — Tronçon 3",
    "paymentMode": "CHEQUE",
    "paymentRef": "CHQ-2026-1042",
    "voucherNo": "BON-0142",
    "nature": "SOUS_TRAITANCE",
    "claimedAmount": 1500.000,
    "varianceReason": "Retenue de garantie 5%"
  }'
# → returns { movement: {...}, processInstanceId: "<instance-id>" }

# 3. Attach a fake PDF proof (optional but recommended)
echo "%PDF-1.4 fake" > /tmp/fake.pdf
curl -b /tmp/c.txt -X POST \
  http://localhost:8080/api/cash/movements/<movement-id>/attachment \
  -F "file=@/tmp/fake.pdf" -F "title=Facture Pipetech PIP-2026-0142" \
  -H "X-XSRF-TOKEN: $XSRF"

# 4. Find the PENDING step
STEP_ID=$(psql -c "select id from process_step_instance
  where instance_id='<instance-id>' and status='PENDING' limit 1" -t)

# 5. Load the approval desk (single round-trip)
curl -b /tmp/c.txt \
  http://localhost:8080/api/process/steps/$STEP_ID/approval-desk

# 6. Approve (as finance/owner — assignee)
curl -b /tmp/c.txt -X POST \
  http://localhost:8080/api/processes/<instance-id>/steps/$STEP_ID/complete \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"comment":"Dossier vérifié — OK"}'

# 7. Verify the enriched audit
psql -c "select action, details from event_log
  where entity_id='$STEP_ID' and action='STEP_COMPLETED'
  order by created_at desc limit 1"

# 8. Verify the movement timeline merges approval events
curl -b /tmp/c.txt \
  http://localhost:8080/api/cash/movements/<movement-id>/timeline
```

### Reject path (comment required)

```bash
curl -b /tmp/c.txt -X POST \
  http://localhost:8080/api/processes/<instance-id>/steps/$STEP_ID/reject \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"comment":"Facture non conforme — revoir le tronçon 3"}'
# Without comment → 400 COMMENT_REQUIRED
```

## Known limits

- **Subject restriction**: the desk only supports `CASH_MOVEMENT`. Other
  subjects (future: achat, vacation) would need a different payload shape —
  returns `400 SUBJECT_NOT_SUPPORTED_FOR_DESK`.
- **Single assignee per step**: v1 — no parallel multi-assignee (out of
  scope per STANDING RULE 8).
- **Attachment enforcement**: `requires_attachment` checks for a document on
  the movement OR on the step; it does NOT verify the document type matches
  the step's expectation (e.g. FACTURE vs BON_LIVRAIRE). That's a future
  3-way-match feature — explicitly out of scope.
- **No threshold matrix**: amount-based approval routing (e.g. "> 10k TND
  needs owner") is not implemented — out of scope per STANDING RULE 12.

## Frontend (TASK 2)

### Route

```
/processus/etapes/:stepInstanceId/validation
```

Component: `ApprovalDeskComponent` (`frontend/src/app/features/process/approval-desk.component.ts`).

### Layout (scannable, French, mobile-friendly)

1. **Header**: Projet | Tiers | big "À payer: X TND" + movement status.
2. **Amounts band**: Demandé / À payer / Écart+motif (warning color when variance ≠ 0).
3. **Meta chips**: Mode (icon), Bon N°, Nature, Date, Caisse, Réf.
4. **Document block**: name + "Ouvrir" button (links to `/documents/{id}`);
   if no document + `requiresAttachment`: strong warning, Approuver disabled.
5. **Mini-timeline**: all steps with status (DONE/PENDING/REJECTED) + assignee
   name + completedAt + comment.
6. **Actions**: Approuver (green) | Rejeter (red outline) | Retour.
   - Read-only viewers (non-assignees) see a "Lecture seule" banner instead.
   - Reject opens a dialog with a required comment textarea.

### Mes tâches update

Each CASH_MOVEMENT task card now has a primary **"Voir le dossier"** button
(navigate to the validation screen) alongside the existing Valider / Rejeter
quick actions. Clicking the card body also opens the dossier.

### Test scenario (frontend)

1. As owner: submit a payment with claimed ≠ assessed + attach a fake PDF.
2. As finance (safa.mtibaa@hydra.tn / HydraDemo2026!): open Mes tâches →
   "Voir le dossier" → see the écart + the document → Approuver.
3. As owner: open the same dossier (next step) → see finance's approval in
   the mini-timeline → Approuver → cash POSTED.
4. Verify the movement timeline (`/tresorerie/operations/{id}`) shows both
   STEP_COMPLETED events.
5. Reject path: create another payment → open dossier → Rejeter with a
   comment → movement stays DRAFT, process REJECTED.
