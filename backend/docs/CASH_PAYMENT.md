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

## 3) Read back

```
GET /api/cash/movements/{id}           → MovementResponse (claimed/assessed/varianceReason)
GET /api/cash/movements/{id}/detail    → + pot/party/project display names
GET /api/cash/movements/{id}/timeline  → snapshot + ordered audit events (claimed vs assessed story)
```

## 4) Forbidden paths (by design)

- `POST /api/cash/movements` with `movementType: OUT` → created DRAFT only;
  posting it via `POST /api/cash/movements/{id}/post` returns
  409 `PAYMENT_REQUIRES_APPROVAL` unless the linked process COMPLETED
  (owner-only `?force=true` escape hatch, default off).
- POSTED movements are immutable: money corrections = `VOID` with reason + new movement.

## 5) E2E script

`scripts/payment-e2e.sh` (21 checks) covers: variance rule, assessed ≤ 0,
legacy back-compat, reject-never-posts, complete-posts-once (single audit event,
pot debited by exactly the assessed amount), JSON responses and the DB safety net.
