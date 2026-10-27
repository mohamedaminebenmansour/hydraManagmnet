# CASH_PAYMENT — Postman / curl steps

Payment flow: **submit-payment → PAYMENT_APPROVAL (sequential steps) → auto-post of the
ASSESSED amount**. Tickets never start payments; naked `POST` of OUT movements is blocked.

## 0) Auth (cookie session + CSRF)

Every call below uses session cookie + CSRF header:

```
# 1. get XSRF cookie
GET http://localhost:8080/api/auth/me
# 2. login
POST http://localhost:8080/api/auth/login
Content-Type: application/json
X-XSRF-TOKEN: <value of XSRF-TOKEN cookie>
{ "email": "owner@hydra.tn", "password": "HydraOwner2026!" }
# 3. refresh XSRF-TOKEN value after login (session rotates the cookie)
```

All error responses are JSON `{ "code": "...", "message": "..." }` (never empty 400/500).

## 1) Submit a payment (the single OUT entry point)

```
POST http://localhost:8080/api/cash/movements/submit-payment
X-XSRF-TOKEN: <token>
Content-Type: application/json

{
  "fromPotId":  "cccccc00-0000-0000-0000-000000000001",   // caisse chantier (PROJECT pot)
  "projectId":  "bbbbbb00-0000-0000-0000-000000000001",
  "partyId":    "dddddd00-0000-0000-0000-000000000001",   // sub / supplier
  "claimedAmount":  12500.500,        // what the party asked (facture/situation)
  "assessedAmount": 11000.000,        // what the company accepts to pay  → POSTED amount
  "varianceReason": "Travaux non conformes déduits (attachement 2)",
  "movementDate":   "2026-10-27",     // = valueDate
  "label": "Acompte situation 3 - STE BTP",
  "note": "optional"
}
```

Response `200`:

```json
{ "movement": { "id": "…", "status": "DRAFT", "amount": 11000.000,
                "claimedAmount": 12500.500, "assessedAmount": 11000.000,
                "varianceReason": "Travaux non conformes déduits (attachement 2)",
                "processInstanceId": "…" },
  "processInstanceId": "…" }
```

Rules enforced (service + DB checks `chk_cm_assessed_positive`, `chk_cm_variance_reason`):

| Input                                        | Result                                     |
|----------------------------------------------|--------------------------------------------|
| `assessedAmount <= 0`                        | 400 (montant must be positive)             |
| `claimedAmount == assessedAmount`            | OK — no reason needed                      |
| `claimedAmount != assessedAmount`, no reason | 400 `VARIANCE_REASON_REQUIRED`             |
| only `amount` (legacy short form)            | OK — amount IS the assessed value          |
| only `claimedAmount`                         | OK — assessed defaults to claimed          |

`amount` is kept **in sync with `assessedAmount`** — the caisse always posts the ASSESSED amount.

## 2) Approve (Mes tâches / sequential steps)

```
GET http://localhost:8080/api/processes/{processInstanceId}/timeline
POST http://localhost:8080/api/processes/{processInstanceId}/steps/{stepInstanceId}/complete
     body: { "comment": "OK gérant" }
POST http://localhost:8080/api/processes/{processInstanceId}/steps/{stepInstanceId}/reject
     body: { "comment": "Montant non justifié" }
```

- **COMPLETED** (last step done) → movement auto-POSTed **once**
  (audit `CASH_MOVEMENT_POSTED`, `autoPostedViaProcess:true`), pot balances updated.
- **REJECTED** → movement stays **DRAFT**, `process_instance_id` is cleared (editable +
  resubmittable), audit `CASH_MOVEMENT_REJECTED`, **never posted**.

## 3) Attach the payment proof (TASK 3 — facture / BL / situation)

The proof file becomes a **real Document in the project's Finance folder
(`06_FIN`)** via the documents pipeline — never a storage-only orphan — and is
linked on `movement.document_id` (junction `cash_movement_document` kept in
sync). Only **DRAFT** movements accept attachments (POSTED is immutable,
VOID is dead → 409 `MOVEMENT_NOT_EDITABLE`). ACL is exactly the folder upload
rules: `folderAccess(UPLOAD)` on the 06_FIN matrix (owner / project admin /
explicit grants) + project `can_upload`.

```
POST http://localhost:8080/api/cash/movements/{movementId}/attachment
X-XSRF-TOKEN: <token>
Content-Type: multipart/form-data

file    = (binary) facture.pdf          # required
title   = Facture SOTRAM situation 12   # required
docType = FACTURE                       # optional: FACTURE (default) | SITUATION | BON_LIVRAISON | AUTRE
```

Response `201` = the movement **detail** (documentId + documentName filled):

```json
{ "id": "…", "status": "DRAFT", "amount": 2500.000,
  "documentId": "0fc8f5c6-…", "documentName": "PRJ-…_FACTURE_…_Rev.0.pdf", … }
```

Errors: `400 REQUIRED_FIELDS` (no title/file), `400 INVALID_DOC_TYPE`,
`400 PROJECT_REQUIRED` (movement not tied to a project), `403` folder ACL,
`404 MOVEMENT_NOT_FOUND`, `409 MOVEMENT_NOT_EDITABLE` (not DRAFT).
Audit: `DOCUMENT_ATTACHED_TO_MOVEMENT` (visible on the movement timeline).

The document then appears both on the movement (detail + timeline) and under
the project documents in the **Finance** folder (doc module screens).

## 4) Read back

```
GET /api/cash/movements/{id}           → MovementResponse (claimed/assessed/varianceReason, documentId)
GET /api/cash/movements/{id}/detail    → + pot/party/project display names + documentId/documentName
GET /api/cash/movements/{id}/timeline  → snapshot + ordered audit events (claimed vs assessed + attachment story)
```

## 5) Forbidden paths (by design)

- `POST /api/cash/movements` with `movementType: OUT` → created DRAFT only;
  posting it via `POST /api/cash/movements/{id}/post` returns
  409 `PAYMENT_REQUIRES_APPROVAL` unless the linked process COMPLETED
  (owner-only `?force=true` escape hatch, default off).
- POSTED movements are immutable: money corrections = `VOID` with reason + new movement.

## 6) E2E scripts

- `scripts/payment-e2e.sh` (21 checks) covers: variance rule, assessed ≤ 0,
  legacy back-compat, reject-never-posts, complete-posts-once (single audit event,
  pot debited by exactly the assessed amount), JSON responses and the DB safety net.
- `scripts/task3-attachment-e2e.sh` (6 checks) covers: attach → 201 with
  documentId/documentName, document listed in the Finance (06_FIN) folder,
  409 on POSTED, 400 PROJECT_REQUIRED, 400 INVALID_DOC_TYPE,
  DOCUMENT_ATTACHED_TO_MOVEMENT on the timeline.
