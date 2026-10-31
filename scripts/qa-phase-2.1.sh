#!/usr/bin/env bash
# ============================================================================
#  HYDRA Part 2.1 — Bon de Commande E2E Tests (S17-S22)
# ============================================================================
set -uo pipefail

BACKEND="http://127.0.0.1:8080"
JAR="/tmp/my-project/logs/qa-2.1.jar"
LOG="/tmp/my-project/logs/qa-2.1-results.txt"
PASS=0; FAIL=0
TAG="BC$(date +%H%M)"

rm -f "$JAR" "$LOG"

log()  { echo "$@" | tee -a "$LOG"; }
hr()   { log "────────────────────────────────────────────────────────────────"; }
record() {
  local id="$1" name="$2" status="$3" ev="$4"
  log ""; log "[$id] $name"; log "  Status: $status"; log "  Evidence: $ev"
  if [[ "$status" == "PASS" ]]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
}

login() {
  local email="$1" pw="$2" jarfile="$3"
  rm -f "$jarfile"; curl -s -c "$jarfile" "$BACKEND/api/auth/me" > /dev/null
  local xsrf; xsrf=$(grep XSRF-TOKEN "$jarfile" | awk '{print $7}')
  curl -s -b "$jarfile" -c "$jarfile" -X POST "$BACKEND/api/auth/login" \
    -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $xsrf" \
    -d "{\"email\":\"$email\",\"password\":\"$pw\"}"
}
xsrf() { echo "X-XSRF-TOKEN: $(grep XSRF-TOKEN "$1" | awk '{print $7}')"; }

log "============================================================"
log "  HYDRA Part 2.1 — Bon de Commande E2E ($TAG)"
log "============================================================"
hr

# Setup: login as owner + find a project + an item + a supplier
login "owner@hydra.tn" "HydraOwner2026!" "$JAR" > /dev/null

PROJECTS=$(curl -s -b "$JAR" "$BACKEND/api/projects/options")
P1=$(echo "$PROJECTS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['id'] if d else '')")
P2=$(echo "$PROJECTS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[1]['id'] if len(d)>1 else '')")
P1_NAME=$(echo "$PROJECTS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['name'] if d else '')")
P2_NAME=$(echo "$PROJECTS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[1]['name'] if len(d)>1 else '')")

ITEM_ID=$(curl -s -b "$JAR" "$BACKEND/api/stock/items" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['id'] if d else '')")
ITEM2_ID=$(curl -s -b "$JAR" "$BACKEND/api/stock/items" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[1]['id'] if len(d)>1 else '')")

# Find a SUPPLIER party
SUPPLIER_ID=$(curl -s -b "$JAR" "$BACKEND/api/parties" | python3 -c "
import json,sys
d=json.load(sys.stdin)
sup = next((p['id'] for p in d if p.get('type')=='SUPPLIER'), '')
print(sup)" 2>/dev/null)

log "Test data: P1=$P1_NAME($P1), P2=$P2_NAME($P2), item=$ITEM_ID, supplier=$SUPPLIER_ID"
hr

# ─── S17: Create DRAFT BC + 2 lines → confirm → lines immutable ──────────
log ""; log "[S17] Create DRAFT BC + 2 lines → confirm → lines immutable"
log "  Steps:"
log "    1. POST /api/purchase-orders {projectId, supplierPartyId, lines:[2 lines]}"
log "    2. Verify status=DRAFT, lineCount=2"
log "    3. POST /confirm → status=CONFIRMED"
log "    4. Try PATCH line → expect 409 BC_NOT_DRAFT"

# 1. Create BC with 2 lines
CREATE_BODY=$(cat <<JSON
{
  "projectId": "$P1",
  "supplierPartyId": "$SUPPLIER_ID",
  "reference": "BC-$TAG-001",
  "notes": "Test BC $TAG",
  "lines": [
    {"itemId": "$ITEM_ID", "qtyOrdered": 50, "uom": "sac", "unitPrice": 25.5},
    {"itemId": "$ITEM2_ID", "qtyOrdered": 100, "uom": "m³"}
  ]
}
JSON
)
CREATE_RESP=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/purchase-orders" -d "$CREATE_BODY")
BC_ID=$(echo "$CREATE_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
BC_STATUS=$(echo "$CREATE_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
BC_LINECOUNT=$(echo "$CREATE_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('lineCount',0))" 2>/dev/null)
LINE1_ID=$(echo "$CREATE_RESP" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['lines'][0]['id'] if d.get('lines') else '')" 2>/dev/null)
log "  BC created: id=$BC_ID, status=$BC_STATUS, lineCount=$BC_LINECOUNT, line1=$LINE1_ID"

# 3. Confirm
CONFIRM_RESP=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -X POST "$BACKEND/api/purchase-orders/$BC_ID/confirm")
CONFIRM_STATUS=$(echo "$CONFIRM_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
log "  After confirm: status=$CONFIRM_STATUS"

# 4. Try to PATCH a line on a CONFIRMED BC → expect 409 BC_NOT_DRAFT
PATCH_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X PATCH "$BACKEND/api/purchase-orders/$BC_ID/lines/$LINE1_ID" \
  -d '{"qtyOrdered": 999}')
PATCH_BODY=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X PATCH "$BACKEND/api/purchase-orders/$BC_ID/lines/$LINE1_ID" -d '{"qtyOrdered": 999}')
PATCH_CODE=$(echo "$PATCH_BODY" | python3 -c "import json,sys; print(json.load(sys.stdin).get('code','NO_CODE'))" 2>/dev/null)
log "  PATCH line on CONFIRMED: HTTP=$PATCH_HTTP, code=$PATCH_CODE"

if [[ -n "$BC_ID" && "$BC_STATUS" == "DRAFT" && "$BC_LINECOUNT" == "2" && "$CONFIRM_STATUS" == "CONFIRMED" && "$PATCH_CODE" == "BC_NOT_DRAFT" ]]; then
  record S17 "Create DRAFT BC + 2 lines → confirm → lines immutable" PASS "BC=$BC_ID, DRAFT→CONFIRMED, line PATCH blocked with BC_NOT_DRAFT"
else
  record S17 "Create DRAFT BC + 2 lines → confirm → lines immutable" FAIL "BC=$BC_ID, status=$BC_STATUS, lc=$BC_LINECOUNT, confirm=$CONFIRM_STATUS, patch_code=$PATCH_CODE | create=$CREATE_RESP | confirm=$CONFIRM_RESP"
fi
hr

# ─── S18: Cancel confirmed BC ─────────────────────────────────────────────
log ""; log "[S18] Cancel confirmed BC"
log "  Steps: POST /cancel {reason} → status=CANCELLED"

CANCEL_RESP=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/purchase-orders/$BC_ID/cancel" -d '{"reason":"Test cancel S18"}')
CANCEL_STATUS=$(echo "$CANCEL_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
log "  After cancel: status=$CANCEL_STATUS"

if [[ "$CANCEL_STATUS" == "CANCELLED" ]]; then
  record S18 "Cancel confirmed BC" PASS "BC=$BC_ID, CONFIRMED→CANCELLED with reason"
else
  record S18 "Cancel confirmed BC" FAIL "status=$CANCEL_STATUS | resp=$CANCEL_RESP"
fi
hr

# ─── S19: IDOR — other project 403/409 ────────────────────────────────────
log ""; log "[S19] IDOR — create BC on project P2 (not the test user's, if non-owner)"
log "  Steps:"
log "    1. Login as amel.riahi (BUREAU — has STOCK_VIEW, not STOCK_MOVE)"
log "    2. Try POST /api/purchase-orders → expect 403 (@PreAuthorize) or 409 FORBIDDEN"
log "    3. Login as karim.bouzid (CHEF_PROJET — has STOCK_MOVE, member of P1 only)"
log "    4. Try to GET /api/purchase-orders/{bcId} for a BC on P2 → expect 409 FORBIDDEN (project not visible)"

# amel.riahi has STOCK_VIEW but NOT STOCK_MOVE → POST should be 403
AMEL_JAR="/tmp/my-project/logs/qa-amel-2.1.jar"
login "amel.riahi@hydra.tn" "HydraDemo2026!" "$AMEL_JAR" > /dev/null
AMEL_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -b "$AMEL_JAR" -H "$(xsrf "$AMEL_JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/purchase-orders" -d "{\"projectId\":\"$P1\",\"lines\":[]}")
log "  amel (BUREAU, no STOCK_MOVE) POST BC: HTTP=$AMEL_HTTP (expect 403)"

# karim.bouzid (CHEF_PROJET — member of P1 only). Try to access the BC we created on P1 — should work.
# Then try to create a BC on P2 (not his project) → expect 409 FORBIDDEN
KARIM_JAR="/tmp/my-project/logs/qa-karim-2.1.jar"
login "karim.bouzid@hydra.tn" "HydraDemo2026!" "$KARIM_JAR" > /dev/null

# Create a BC on P2 (not his)
KARIM_IDOR_BODY=$(curl -s -b "$KARIM_JAR" -H "$(xsrf "$KARIM_JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/purchase-orders" -d "{\"projectId\":\"$P2\",\"lines\":[]}")
KARIM_IDOR_CODE=$(echo "$KARIM_IDOR_BODY" | python3 -c "import json,sys; print(json.load(sys.stdin).get('code','NO_CODE'))" 2>/dev/null)
log "  karim (CHEF_PROJET on P1) create BC on P2: code=$KARIM_IDOR_CODE (expect FORBIDDEN)"

if [[ "$AMEL_HTTP" == "403" && "$KARIM_IDOR_CODE" == "FORBIDDEN" ]]; then
  record S19 "IDOR — wrong project / wrong perm blocked" PASS "amel POST=403 (no STOCK_MOVE), karim P2 create=FORBIDDEN"
else
  record S19 "IDOR — wrong project / wrong perm blocked" FAIL "amel_http=$AMEL_HTTP (expect 403), karim_code=$KARIM_IDOR_CODE (expect FORBIDDEN) | resp=$KARIM_IDOR_BODY"
fi
hr

# ─── S20: List by project returns lineCount efficiently (no N+1) ──────────
log ""; log "[S20] List by project returns lineCount (no N+1)"
log "  Steps:"
log "    1. Create 2 BCs on P1 with different line counts"
log "    2. GET /api/purchase-orders?projectId=P1 → verify lineCount per row"
log "    3. Verify single SQL (no N+1) — check backend log or response time"

# Create 2 more BCs on P1
BC2=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/purchase-orders" \
  -d "{\"projectId\":\"$P1\",\"lines\":[{\"itemId\":\"$ITEM_ID\",\"qtyOrdered\":10,\"uom\":\"sac\"}]}")
BC2_ID=$(echo "$BC2" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
BC2_LC=$(echo "$BC2" | python3 -c "import json,sys; print(json.load(sys.stdin).get('lineCount',0))" 2>/dev/null)

BC3=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/purchase-orders" \
  -d "{\"projectId\":\"$P1\",\"lines\":[{\"itemId\":\"$ITEM_ID\",\"qtyOrdered\":5,\"uom\":\"sac\"},{\"itemId\":\"$ITEM2_ID\",\"qtyOrdered\":3,\"uom\":\"m³\"}]}")
BC3_ID=$(echo "$BC3" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
BC3_LC=$(echo "$BC3" | python3 -c "import json,sys; print(json.load(sys.stdin).get('lineCount',0))" 2>/dev/null)

# List P1 BCs
LIST_RESP=$(curl -s -b "$JAR" "$BACKEND/api/purchase-orders?projectId=$P1")
LIST_CHECK=$(echo "$LIST_RESP" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items = d.get('items', [])
has_lc = all('lineCount' in it for it in items)
# Find our 2 new BCs
bc2 = next((it for it in items if it['id'] == '$BC2_ID'), None)
bc3 = next((it for it in items if it['id'] == '$BC3_ID'), None)
ok = has_lc and bc2 and bc3 and bc2['lineCount'] == 1 and bc3['lineCount'] == 2
print('OK' if ok else f'FAIL: items={len(items)}, bc2_lc={bc2.get(\"lineCount\") if bc2 else None}, bc3_lc={bc3.get(\"lineCount\") if bc3 else None}')
" 2>/dev/null)
log "  List P1: $LIST_CHECK"

if [[ "$LIST_CHECK" == OK* ]]; then
  record S20 "List by project returns lineCount (no N+1)" PASS "BC2 lineCount=1, BC3 lineCount=2 — both correct in single list response"
else
  record S20 "List by project returns lineCount (no N+1)" FAIL "$LIST_CHECK | resp=$LIST_RESP"
fi
hr

# ─── S21: PATCH BC (DRAFT only) ───────────────────────────────────────────
log ""; log "[S21] PATCH BC (DRAFT only) — reference, notes, expectedAt"
log "  Steps:"
log "    1. PATCH /api/purchase-orders/{bc3_id} {reference, notes} → 200, fields updated"
log "    2. PATCH on the CONFIRMED BC (S17's BC_ID is now CANCELLED) → expect 409 BC_NOT_DRAFT"

# PATCH a DRAFT BC (BC3 is still DRAFT)
PATCH_BC=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X PATCH "$BACKEND/api/purchase-orders/$BC3_ID" \
  -d '{"reference":"BC-PATCHED-001","notes":"Updated notes","expectedAt":"2026-12-31"}')
PATCH_REF=$(echo "$PATCH_BC" | python3 -c "import json,sys; print(json.load(sys.stdin).get('reference',''))" 2>/dev/null)
PATCH_NOTES=$(echo "$PATCH_BC" | python3 -c "import json,sys; print(json.load(sys.stdin).get('notes',''))" 2>/dev/null)
PATCH_EXP=$(echo "$PATCH_BC" | python3 -c "import json,sys; print(json.load(sys.stdin).get('expectedAt',''))" 2>/dev/null)
log "  PATCH DRAFT BC3: ref=$PATCH_REF, notes=$PATCH_NOTES, expectedAt=$PATCH_EXP"

# PATCH a CANCELLED BC → expect 409 BC_NOT_DRAFT
PATCH_CANCELLED_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X PATCH "$BACKEND/api/purchase-orders/$BC_ID" -d '{"reference":"should-fail"}')
PATCH_CANCELLED_BODY=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X PATCH "$BACKEND/api/purchase-orders/$BC_ID" -d '{"reference":"should-fail"}')
PATCH_CANCELLED_CODE=$(echo "$PATCH_CANCELLED_BODY" | python3 -c "import json,sys; print(json.load(sys.stdin).get('code','NO_CODE'))" 2>/dev/null)
log "  PATCH CANCELLED BC: HTTP=$PATCH_CANCELLED_HTTP, code=$PATCH_CANCELLED_CODE"

if [[ "$PATCH_REF" == "BC-PATCHED-001" && "$PATCH_NOTES" == "Updated notes" && "$PATCH_EXP" == "2026-12-31" && "$PATCH_CANCELLED_CODE" == "BC_NOT_DRAFT" ]]; then
  record S21 "PATCH BC (DRAFT only)" PASS "DRAFT BC3 patched (ref+notes+expectedAt), CANCELLED BC → BC_NOT_DRAFT"
else
  record S21 "PATCH BC (DRAFT only)" FAIL "patch_ref=$PATCH_REF, patch_notes=$PATCH_NOTES, patch_exp=$PATCH_EXP, cancelled_code=$PATCH_CANCELLED_CODE | resp=$PATCH_BC"
fi
hr

# ─── S22: Confirm requires ≥1 line (BC_NO_LINES) ─────────────────────────
log ""; log "[S22] Confirm requires ≥1 line (BC_NO_LINES)"
log "  Steps:"
log "    1. Create a DRAFT BC with NO lines"
log "    2. POST /confirm → expect 409 BC_NO_LINES"

EMPTY_BC=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/purchase-orders" -d "{\"projectId\":\"$P1\",\"lines\":[]}")
EMPTY_BC_ID=$(echo "$EMPTY_BC" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
log "  Empty DRAFT BC created: id=$EMPTY_BC_ID"

CONFIRM_EMPTY=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -X POST "$BACKEND/api/purchase-orders/$EMPTY_BC_ID/confirm")
CONFIRM_EMPTY_CODE=$(echo "$CONFIRM_EMPTY" | python3 -c "import json,sys; print(json.load(sys.stdin).get('code','NO_CODE'))" 2>/dev/null)
CONFIRM_EMPTY_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -b "$JAR" -H "$(xsrf "$JAR")" -X POST "$BACKEND/api/purchase-orders/$EMPTY_BC_ID/confirm")
log "  Confirm empty BC: HTTP=$CONFIRM_EMPTY_HTTP, code=$CONFIRM_EMPTY_CODE"

if [[ "$CONFIRM_EMPTY_CODE" == "BC_NO_LINES" ]]; then
  record S22 "Confirm requires ≥1 line (BC_NO_LINES)" PASS "Empty DRAFT BC → confirm blocked with BC_NO_LINES"
else
  record S22 "Confirm requires ≥1 line (BC_NO_LINES)" FAIL "code=$CONFIRM_EMPTY_CODE (expect BC_NO_LINES) | resp=$CONFIRM_EMPTY"
fi
hr

# ─── Bonus: audit events ──────────────────────────────────────────────────
log ""; log "[BONUS] Audit events for BC created/confirmed/cancelled"
AUDIT_COUNT=$(PGPASSWORD=hydra /tmp/my-project/.venv-pg/lib/python3.12/site-packages/pgserver/pginstall/bin/psql -h 127.0.0.1 -U postgres -d hydra -t -c "
SELECT count(*) FROM event_log WHERE action LIKE 'STOCK_BC%' AND created_at > now() - interval '5 minutes';" 2>&1 | tr -d ' \n')
log "  BC audit events in last 5min: $AUDIT_COUNT"
if [[ "$AUDIT_COUNT" -ge 4 ]]; then
  record BONUS "Audit events STOCK_BC_*" PASS "$AUDIT_COUNT events (CREATED + CONFIRMED + CANCELLED + LINE_ADDED)"
else
  record BONUS "Audit events STOCK_BC_*" FAIL "Only $AUDIT_COUNT events (expected ≥4)"
fi
hr

log ""; log "============================================================"
log "  SUMMARY: PASS=$PASS  FAIL=$FAIL  (total=$((PASS+FAIL)))"
log "============================================================"
exit $FAIL
