# Cash Immutability — POSTED + Formal Void

> STANDING RULE 7: POSTED is immutable except formal VOID by authorized role.

## Lifecycle

```
DRAFT ──POST──→ POSTED ──VOID──→ VOID
  ↑                  │
  └── edit allowed ──┘  NO edit, NO re-post
  
VOID = dead. Cannot edit, cannot post, cannot void again.
```

## Rules

### DRAFT (editable)
- Finance / chef de projet / conducteur can create + edit.
- Editable fields: amount, claimed/assessed, varianceReason, paymentMode, paymentRef,
  voucherNo, nature, invoiceRef, label, note, fromPotId, toPotId, projectId, partyId.
- Can be POSTED (IN/TRANSFER by owner/finance with CASH_POST; OUT only via completed
  PAYMENT_APPROVAL workflow or owner force).

### POSTED (immutable)
- **NO update** of any field. `PUT /api/cash/movements/{id}` → `409 POSTED_IMMUTABLE`.
  French message: "Un mouvement comptabilisé (POSTED) ne peut pas être modifié.
  Annulez-le (VOID) puis créez un nouveau mouvement."
- **NO re-post**. `POST /api/cash/movements/{id}/post` → `409 POSTED_IMMUTABLE`.
  French message: "Ce mouvement est déjà comptabilisé (POSTED). Il ne peut pas être re-posté."
- Balance is affected (POSTED movements are included in pot balance computation).
- Can be VOIDED only.

### VOID (dead)
- **NO update**. `PUT /api/cash/movements/{id}` → `409 MOVEMENT_VOID`.
  French message: "Un mouvement annulé (VOID) ne peut pas être modifié.
  Créez un nouveau mouvement."
- **NO post**. `POST /api/cash/movements/{id}/post` → `409 MOVEMENT_VOID`.
- **NO re-void**. `POST /api/cash/movements/{id}/void` → `409 MOVEMENT_NOT_POSTED`.
  French message: "Seul un mouvement POSTED peut être annulé (VOID)."
- Balance is NOT affected (VOID movements are excluded from pot balance computation).
- Correction path: VOID the POSTED movement (with reason) + create a new DRAFT
  movement with the correct data.

## Void endpoint

### `POST /api/cash/movements/{id}/void`

**Body**: `{ "reason": "required non-blank" }`

**Permission**: `PERM_CASH_VOID` (owner always has it; finance does NOT — separation
of duties: the person who posts cannot void).

**Guards**:
- `400 REASON_REQUIRED` if reason is blank.
- `409 MOVEMENT_NOT_POSTED` if status ≠ POSTED (covers DRAFT + VOID).

**Audit**: `CASH_MOVEMENT_VOIDED` with enriched JSON:
```json
{
  "reason": "Erreur de saisie — montant double",
  "voidedBy": "uuid",
  "amount": 1500.000,
  "movementType": "OUT",
  "fromPotId": "uuid",
  "partyId": "uuid",
  "projectId": "uuid"
}
```

The audit captures who (voidedBy), when (event_log.created_at), what was voided
(amount, type), from which caisse, to whom — enough to reconstruct the decision
without re-querying the movement.

## Error codes summary

| Code | HTTP | When | French message |
|------|------|------|----------------|
| `POSTED_IMMUTABLE` | 409 | Update or re-post a POSTED movement | "Un mouvement comptabilisé (POSTED) ne peut pas être modifié..." |
| `MOVEMENT_VOID` | 409 | Update or post a VOID movement | "Un mouvement annulé (VOID) ne peut pas être modifié..." |
| `MOVEMENT_NOT_DRAFT` | 409 | Post a non-DRAFT movement (fallback) | "Seul un mouvement DRAFT peut être comptabilisé..." |
| `MOVEMENT_NOT_POSTED` | 409 | Void a non-POSTED movement | "Seul un mouvement POSTED peut être annulé (VOID)..." |
| `REASON_REQUIRED` | 400 | Void without a reason | "Un motif d'annulation est obligatoire" |

## How to test

```bash
# 1. Login as owner
curl -c /tmp/c.txt http://localhost:8080/api/auth/me
XSRF=$(grep XSRF-TOKEN /tmp/c.txt | awk '{print $7}')
curl -b /tmp/c.txt -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"email":"owner@hydra.tn","password":"<OWNER_PASSWORD>"}'

# 2. Create a DRAFT movement (IN, 1000 TND)
MOVEMENT=$(curl -s -b /tmp/c.txt -X POST http://localhost:8080/api/cash/movements \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"movementType":"IN","amount":1000,"movementDate":"2026-10-28",
       "toPotId":"<company-pot-id>"}' | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")

# 3. POST it
curl -s -b /tmp/c.txt -X POST "http://localhost:8080/api/cash/movements/$MOVEMENT/post" \
  -H "X-XSRF-TOKEN: $XSRF"
# → 200, status POSTED

# 4. Try to UPDATE the POSTED movement → 409 POSTED_IMMUTABLE
curl -s -b /tmp/c.txt -X PUT "http://localhost:8080/api/cash/movements/$MOVEMENT" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"movementType":"IN","amount":2000,"movementDate":"2026-10-28","toPotId":"<pot>"}'
# → {"code":"POSTED_IMMUTABLE","message":"Un mouvement comptabilisé..."}

# 5. Try to re-POST → 409 POSTED_IMMUTABLE
curl -s -b /tmp/c.txt -X POST "http://localhost:8080/api/cash/movements/$MOVEMENT/post" \
  -H "X-XSRF-TOKEN: $XSRF"
# → {"code":"POSTED_IMMUTABLE","message":"Ce mouvement est déjà comptabilisé..."}

# 6. VOID it (reason required)
curl -s -b /tmp/c.txt -X POST "http://localhost:8080/api/cash/movements/$MOVEMENT/void" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"reason":"Erreur de saisie — montant double"}'
# → 200, status VOID

# 7. Try to VOID again → 409 MOVEMENT_NOT_POSTED
curl -s -b /tmp/c.txt -X POST "http://localhost:8080/api/cash/movements/$MOVEMENT/void" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"reason":"again"}'
# → {"code":"MOVEMENT_NOT_POSTED","message":"Seul un mouvement POSTED..."}

# 8. Try to UPDATE the VOID movement → 409 MOVEMENT_VOID
curl -s -b /tmp/c.txt -X PUT "http://localhost:8080/api/cash/movements/$MOVEMENT" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"movementType":"IN","amount":500,"movementDate":"2026-10-28","toPotId":"<pot>"}'
# → {"code":"MOVEMENT_VOID","message":"Un mouvement annulé (VOID)..."}

# 9. Verify the enriched void audit
psql -c "select details from event_log where entity_id='$MOVEMENT' and action='CASH_MOVEMENT_VOIDED'"
# → {"reason":"Erreur de saisison — montant double","voidedBy":"...","amount":1000.000,"movementType":"IN",...}
```

## Known limits

- **Finance cannot void**: by design (CASH_VOID perm = owner only in V22 seed).
  Finance can post but not void — separation of duties. If the product later wants
  finance to void too, add `CASH_VOID` to the FINANCE role in V22 and it works
  without code change (permission is checked via `requireCanPostOrVoid`).
- **No partial void**: void cancels the entire movement. If only part was wrong,
  void the full amount + create a new movement for the correct amount.
- **No void of a movement linked to a RUNNING process**: the process must be
  COMPLETED or REJECTED first. A RUNNING process means the movement is still
  DRAFT (not yet POSTED), so void is impossible (it's not POSTED yet).

## TASK 6 — Request void (finance asks, owner voids)

### Endpoint

`POST /api/cash/movements/{id}/request-void`

**Body**: `{ "reason": "required non-blank" }`

**Permission**: `PERM_CASH_DRAFT` (finance has it; owner too).

**What it does**:
- Sets `void_requested_at` + `void_request_reason` on the movement.
- Audits `CASH_VOID_REQUESTED` with `{reason, requestedBy, movementStatus}`.
- Does NOT void the movement — the owner still calls the existing void endpoint.

**Guards**:
- `400 REASON_REQUIRED` if reason is blank.
- `409 MOVEMENT_NOT_POSTED` if status ≠ POSTED.

**Movement detail response** now includes `voidRequestedAt` + `voidRequestReason`
so the owner sees the request on the movement detail page.

### Full correction flow

1. Finance creates + posts a payment (POSTED).
2. Finance discovers an error (wrong amount).
3. Finance calls `POST /api/cash/movements/{id}/request-void` with reason.
4. Owner opens movement detail → sees "Demande d'annulation" badge + reason.
5. Owner calls `POST /api/cash/movements/{id}/void` with their own reason.
6. Movement is VOID → balance updates.
7. Finance creates a new DRAFT movement with the correct amount.

### Audit actions

| Action | When | Details JSON |
|--------|------|-------------|
| `CASH_VOID_REQUESTED` | Finance requests void | `{reason, requestedBy, movementStatus}` |
| `CASH_MOVEMENT_VOIDED` | Owner voids | `{reason, voidedBy, amount, movementType, fromPotId, partyId, projectId}` |
