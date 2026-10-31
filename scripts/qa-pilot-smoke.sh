#!/usr/bin/env bash
# ============================================================================
#  HYDRA — Pilot Smoke Test (S1-S8)
#  Exercises REAL business paths end-to-end via API.
# ============================================================================
set -uo pipefail

BACKEND="${BACKEND:-http://127.0.0.1:8080}"
JAR="/tmp/my-project/logs/qa-smoke.jar"
LOG="/tmp/my-project/logs/qa-pilot-smoke-results.txt"
PASS=0; FAIL=0
TAG="S$(date +%H%M)"

rm -f "$JAR" "$LOG"

log()  { echo "$@" | tee -a "$LOG"; }
hr()   { log "────────────────────────────────────────────────────────────────"; }
record() {
  log ""; log "[$1] $2"; log "  Status: $3"; log "  Evidence: $4"
  if [[ "$3" == "PASS" ]]; then PASS=$((PASS+1)); else FAIL=$((FAIL+1)); fi
}

login() {
  local email="$1" pw="$2" jarfile="$3"
  rm -f "$jarfile"; curl -s -c "$jarfile" "$BACKEND/api/auth/me" > /dev/null
  local xsrf; xsrf=$(grep XSRF-TOKEN "$jarfile" | awk '{print $7}')
  curl -s -b "$jarfile" -c "$jarfile" -X POST "$BACKEND/api/auth/login" \
    -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $xsrf" \
    -d "{\"email\":\"$email\",\"password\":\"$pw\"}" > /dev/null
}
xsrf() { echo "X-XSRF-TOKEN: $(grep XSRF-TOKEN "$1" | awk '{print $7}')"; }
pg() { PGPASSWORD=hydra /tmp/my-project/.venv-pg/lib/python3.12/site-packages/pgserver/pginstall/bin/psql -h 127.0.0.1 -U postgres -d hydra -t -c "$1" 2>&1 | tr -d ' \n'; }

log "============================================================"
log "  HYDRA Pilot Smoke Test ($TAG)"
log "  Backend: $BACKEND"
log "============================================================"
hr

# ─── Setup ────────────────────────────────────────────────────────────────
login "owner@hydra.tn" "HydraOwner2026!" "$JAR"
X=$(xsrf "$JAR")

PROJECTS=$(curl -s -b "$JAR" "$BACKEND/api/projects/options")
P1=$(echo "$PROJECTS" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['id'])")
P2=$(echo "$PROJECTS" | python3 -c "import json,sys; print(json.load(sys.stdin)[1]['id'])")
P1_NAME=$(echo "$PROJECTS" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['name'])")
ITEMS=$(curl -s -b "$JAR" "$BACKEND/api/stock/items")
ITEM_ID=$(echo "$ITEMS" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['id'])")
ITEM_NAME=$(echo "$ITEMS" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['name'])")
LOCS=$(curl -s -b "$JAR" "$BACKEND/api/stock/locations")
SITE_ID=$(echo "$LOCS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(next(l['id'] for l in d if l['type']=='SITE'))")
POT=$(curl -s -b "$JAR" "$BACKEND/api/cash/pots?projectId=$P1" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['id'] if d else '')")

log "Setup: P1=$P1_NAME, item=$ITEM_NAME, site=$SITE_ID, pot=$POT"
hr

# ─── S1: BC confirm → BL post → stock balance increases ──────────────────
log ""; log "[S1] BC confirm → BL post → stock balance increases"

BC=$(curl -s -b "$JAR" -H "$X" -H "Content-Type: application/json" -X POST "$BACKEND/api/purchase-orders" \
  -d "{\"projectId\":\"$P1\",\"lines\":[{\"itemId\":\"$ITEM_ID\",\"qtyOrdered\":80,\"uom\":\"sac\"}]}")
BC_ID=$(echo "$BC" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])" 2>/dev/null)
BC_LINE=$(echo "$BC" | python3 -c "import json,sys; print(json.load(sys.stdin)['lines'][0]['id'])" 2>/dev/null)
curl -s -b "$JAR" -H "$X" -X POST "$BACKEND/api/purchase-orders/$BC_ID/confirm" > /dev/null

BAL_BEFORE=$(curl -s -b "$JAR" "$BACKEND/api/stock/balances?locationId=$SITE_ID&itemId=$ITEM_ID" \
  | python3 -c "import json,sys; r=json.load(sys.stdin); print(r[0]['qty'] if r else 0)" 2>/dev/null)

BL=$(curl -s -b "$JAR" -H "$X" -H "Content-Type: application/json" -X POST "$BACKEND/api/bons-livraison" \
  -d "{\"projectId\":\"$P1\",\"bcId\":\"$BC_ID\",\"locationId\":\"$SITE_ID\",\"lines\":[{\"bcLineId\":\"$BC_LINE\",\"itemId\":\"$ITEM_ID\",\"qtyReceived\":80,\"uom\":\"sac\"}]}")
BL_ID=$(echo "$BL" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])" 2>/dev/null)
curl -s -b "$JAR" -H "$X" -X POST "$BACKEND/api/bons-livraison/$BL_ID/post" > /dev/null

BAL_AFTER=$(curl -s -b "$JAR" "$BACKEND/api/stock/balances?locationId=$SITE_ID&itemId=$ITEM_ID" \
  | python3 -c "import json,sys; r=json.load(sys.stdin); print(r[0]['qty'] if r else 0)" 2>/dev/null)

DELTA=$(python3 -c "print(round($BAL_AFTER - $BAL_BEFORE, 3))")
log "  BC=$BC_ID confirmed, BL=$BL_ID posted, balance: $BAL_BEFORE → $BAL_AFTER (Δ=$DELTA)"

if python3 -c "exit(0 if $DELTA >= 80 else 1)" 2>/dev/null; then
  record S1 "BC confirm → BL post → stock +80" PASS "balance $BAL_BEFORE → $BAL_AFTER (Δ=$DELTA ≥ 80)"
else
  record S1 "BC confirm → BL post → stock +80" FAIL "Δ=$DELTA (expected ≥80) | before=$BAL_BEFORE after=$BAL_AFTER"
fi
hr

# ─── S2: Facture validate → match MATCHED ─────────────────────────────────
log ""; log "[S2] Facture validate → match status"

FAC=$(curl -s -b "$JAR" -H "$X" -H "Content-Type: application/json" -X POST "$BACKEND/api/factures" \
  -d "{\"projectId\":\"$P1\",\"bcId\":\"$BC_ID\",\"reference\":\"FAC-SMOKE-$TAG\",\"lines\":[{\"bcLineId\":\"$BC_LINE\",\"itemId\":\"$ITEM_ID\",\"description\":\"Ciment\",\"qty\":80,\"uom\":\"sac\"}]}")
FAC_ID=$(echo "$FAC" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])" 2>/dev/null)
curl -s -b "$JAR" -H "$X" -X POST "$BACKEND/api/factures/$FAC_ID/validate" > /dev/null

MATCH=$(curl -s -b "$JAR" "$BACKEND/api/purchase-orders/$BC_ID/match")
MATCH_STATUS=$(echo "$MATCH" | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['status'])" 2>/dev/null)
log "  Facture=$FAC_ID validated, match=$MATCH_STATUS"

if [[ "$MATCH_STATUS" == "MATCHED" ]]; then
  record S2 "Facture validate → match MATCHED" PASS "status=$MATCH_STATUS"
else
  record S2 "Facture validate → match MATCHED" FAIL "status=$MATCH_STATUS (expected MATCHED)"
fi
hr

# ─── S3: Payment submit WITH bcId (MATCHED) → 200, warnings empty ────────
log ""; log "[S3] Payment submit WITH bcId (MATCHED) → warnings empty"

PAY=$(curl -s -b "$JAR" -H "$X" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/cash/movements/submit-payment" \
  -d "{\"fromPotId\":\"$POT\",\"amount\":400,\"movementDate\":\"2026-10-30\",\"projectId\":\"$P1\",\"label\":\"S3 matched payment\",\"assessedAmount\":400,\"bcId\":\"$BC_ID\"}")
MV_ID=$(echo "$PAY" | python3 -c "import json,sys; print(json.load(sys.stdin)['movement']['id'])" 2>/dev/null)
MAT_WARNS=$(echo "$PAY" | python3 -c "
import json,sys
d=json.load(sys.stdin)
w=d.get('warnings',[])
print(len(w))
" 2>/dev/null)

log "  Payment=$MV_ID, material_warnings=$MAT_WARNS"

if [[ "$MAT_WARNS" == "0" ]]; then
  record S3 "Payment MATCHED bcId → 0 material warnings" PASS "warnings=$MAT_WARNS"
else
  record S3 "Payment MATCHED bcId → 0 material warnings" FAIL "material_warnings=$MAT_WARNS (expected 0)"
fi
hr

# ─── S4: Payment submit WITH bcId (no BL) → warnings + detail shows them ──
log ""; log "[S4] Payment submit WITH bcId (no BL) → warnings non-empty + detail"

BC2=$(curl -s -b "$JAR" -H "$X" -H "Content-Type: application/json" -X POST "$BACKEND/api/purchase-orders" \
  -d "{\"projectId\":\"$P1\",\"lines\":[{\"itemId\":\"$ITEM_ID\",\"qtyOrdered\":30,\"uom\":\"sac\"}]}")
BC2_ID=$(echo "$BC2" | python3 -c "import json,sys; print(json.load(sys.stdin)['id'])" 2>/dev/null)
curl -s -b "$JAR" -H "$X" -X POST "$BACKEND/api/purchase-orders/$BC2_ID/confirm" > /dev/null

PAY2=$(curl -s -b "$JAR" -H "$X" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/cash/movements/submit-payment" \
  -d "{\"fromPotId\":\"$POT\",\"amount\":150,\"movementDate\":\"2026-10-30\",\"projectId\":\"$P1\",\"label\":\"S4 no BL\",\"assessedAmount\":150,\"bcId\":\"$BC2_ID\"}")
MV2_ID=$(echo "$PAY2" | python3 -c "import json,sys; print(json.load(sys.stdin)['movement']['id'])" 2>/dev/null)
SUBMIT_WARNS=$(echo "$PAY2" | python3 -c "
import json,sys
d=json.load(sys.stdin)
w=d.get('warnings',[])
print(len(w))
" 2>/dev/null)

# GET detail → materialWarnings
DETAIL=$(curl -s -b "$JAR" "$BACKEND/api/cash/movements/$MV2_ID/detail")
DETAIL_WARNS=$(echo "$DETAIL" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len(d.get('materialWarnings',[])))" 2>/dev/null)

log "  Payment=$MV2_ID, submit_warnings=$SUBMIT_WARNS, detail_materialWarnings=$DETAIL_WARNS"

if [[ "$SUBMIT_WARNS" -ge 1 && "$DETAIL_WARNS" -ge 1 ]]; then
  record S4 "Payment no-BL bcId → warnings on submit AND detail" PASS "submit=$SUBMIT_WARNS, detail=$DETAIL_WARNS"
else
  record S4 "Payment no-BL bcId → warnings on submit AND detail" FAIL "submit=$SUBMIT_WARNS, detail=$DETAIL_WARNS"
fi
hr

# ─── S5: TRANSFER without facture still works ────────────────────────────
log ""; log "[S5] TRANSFER internal (no facture) → 200"

CENTRAL_ID=$(echo "$LOCS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(next(l['id'] for l in d if l['type']=='CENTRAL'))" 2>/dev/null)
TRANSFER=$(curl -s -b "$JAR" -H "$X" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/moves" \
  -d "{\"moveType\":\"TRANSFER\",\"qty\":5,\"uom\":\"sac\",\"itemId\":\"$ITEM_ID\",\"fromLocationId\":\"$CENTRAL_ID\",\"toLocationId\":\"$SITE_ID\"}")
T_ID=$(echo "$TRANSFER" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)

if [[ -n "$T_ID" ]]; then
  record S5 "TRANSFER internal (no facture)" PASS "move=$T_ID created"
else
  record S5 "TRANSFER internal (no facture)" FAIL "resp=$TRANSFER"
fi
hr

# ─── S6: Payment process path — submit → check processInstanceId set ──────
log ""; log "[S6] Payment process path — submit creates process instance"

PROC_ID=$(echo "$PAY" | python3 -c "import json,sys; print(json.load(sys.stdin).get('processInstanceId',''))" 2>/dev/null)
log "  Payment=$MV_ID, processInstanceId=$PROC_ID"

if [[ -n "$PROC_ID" && "$PROC_ID" != "None" ]]; then
  record S6 "Payment submit → process instance created" PASS "processInstanceId=$PROC_ID"
else
  record S6 "Payment submit → process instance created" FAIL "processInstanceId=$PROC_ID (empty)"
fi
hr

# ─── S7: IDOR — user from other project forbidden ────────────────────────
log ""; log "[S7] IDOR — cross-project forbidden"

KARIM_JAR="/tmp/my-project/logs/qa-karim-smoke.jar"
login "karim.bouzid@hydra.tn" "HydraDemo2026!" "$KARIM_JAR"
KX=$(xsrf "$KARIM_JAR")

# Karim is on P9, try to access BC on P1
IDOR=$(curl -s -b "$KARIM_JAR" -H "$KX" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/purchase-orders" \
  -d "{\"projectId\":\"$P1\",\"lines\":[]}")
IDOR_CODE=$(echo "$IDOR" | python3 -c "import json,sys; print(json.load(sys.stdin).get('code','NO_CODE'))" 2>/dev/null)

# Also try to access a movement on P1
IDOR_MV=$(curl -s -b "$KARIM_JAR" "$BACKEND/api/cash/movements/$MV_ID/detail" | python3 -c "import json,sys; d=json.load(sys.stdin); print('OK' if d.get('id') else 'BLOCKED')" 2>/dev/null)

log "  Karim (P9) → P1 BC create: $IDOR_CODE; P1 movement detail: $IDOR_MV"

if [[ "$IDOR_CODE" == "FORBIDDEN" ]]; then
  record S7 "IDOR cross-project forbidden" PASS "BC create=$IDOR_CODE"
else
  record S7 "IDOR cross-project forbidden" FAIL "code=$IDOR_CODE (expected FORBIDDEN) | resp=$IDOR"
fi
hr

# ─── S8: Doc folder resolution (BL + FACTURE) ────────────────────────────
log ""; log "[S8] Doc folder resolution — BL + FACTURE"

BL_FOLDER=$(curl -s -b "$JAR" "$BACKEND/api/stock/doc-folder?projectId=$P1&kind=BL")
BL_CODE=$(echo "$BL_FOLDER" | python3 -c "import json,sys; print(json.load(sys.stdin).get('code',''))" 2>/dev/null)
FAC_FOLDER=$(curl -s -b "$JAR" "$BACKEND/api/stock/doc-folder?projectId=$P1&kind=FACTURE")
FAC_CODE=$(echo "$FAC_FOLDER" | python3 -c "import json,sys; print(json.load(sys.stdin).get('code',''))" 2>/dev/null)

log "  BL folder: $BL_CODE, FACTURE folder: $FAC_CODE"

if [[ "$BL_CODE" == "05.01 BL" && "$FAC_CODE" == "05.03 FACTURE" ]]; then
  record S8 "Doc folder BL + FACTURE resolves" PASS "BL=$BL_CODE, FACTURE=$FAC_CODE"
else
  record S8 "Doc folder BL + FACTURE resolves" FAIL "BL=$BL_CODE, FACTURE=$FAC_CODE"
fi
hr

# ─── Summary ─────────────────────────────────────────────────────────────
log ""; log "============================================================"
log "  SUMMARY: PASS=$PASS  FAIL=$FAIL  (total=$((PASS+FAIL)))"
log "============================================================"

exit $FAIL
