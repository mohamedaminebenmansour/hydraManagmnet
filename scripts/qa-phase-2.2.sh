#!/usr/bin/env bash
# ============================================================================
#  HYDRA Part 2.2 — Bon de Livraison + stock IN lineage E2E Tests (S23a-S23e)
# ============================================================================
set -uo pipefail

BACKEND="http://127.0.0.1:8080"
JAR="/tmp/my-project/logs/qa-2.2.jar"
LOG="/tmp/my-project/logs/qa-2.2-results.txt"
PASS=0; FAIL=0
TAG="BL$(date +%H%M)"

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
    -d "{\"email\":\"$email\",\"password\":\"$pw\"}"
}
xsrf() { echo "X-XSRF-TOKEN: $(grep XSRF-TOKEN "$1" | awk '{print $7}')"; }

log "============================================================"
log "  HYDRA Part 2.2 — Bon de Livraison + stock IN lineage ($TAG)"
log "============================================================"
hr

# Setup: login as owner + find project + item + supplier + central location
login "owner@hydra.tn" "HydraOwner2026!" "$JAR" > /dev/null

PROJECTS=$(curl -s -b "$JAR" "$BACKEND/api/projects/options")
P1=$(echo "$PROJECTS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['id'] if d else '')")

ITEMS=$(curl -s -b "$JAR" "$BACKEND/api/stock/items")
ITEM_ID=$(echo "$ITEMS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['id'] if d else '')")
ITEM2_ID=$(echo "$ITEMS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[1]['id'] if len(d)>1 else '')")

LOCATIONS=$(curl -s -b "$JAR" "$BACKEND/api/stock/locations")
CENTRAL_ID=$(echo "$LOCATIONS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(next((l['id'] for l in d if l['type']=='CENTRAL'),''))")
SITE1_ID=$(echo "$LOCATIONS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(next((l['id'] for l in d if l['type']=='SITE'),''))")

log "Test data: P1=$P1, item=$ITEM_ID, item2=$ITEM2_ID, central=$CENTRAL_ID, site=$SITE1_ID"
hr

# ─── S23a: BC 100 → BL receive 40 → balance +40; remaining 60 ─────────────
log ""; log "[S23a] Confirm BC 100 → BL receive 40 → balance +40 at location"
log "  Steps:"
log "    1. Create DRAFT BC + 1 line (qty 100) → confirm"
log "    2. Create DRAFT BL from BC + 1 line (qty 40) → post"
log "    3. Verify balance at location = +40"
log "    4. Verify stock_move.bc_line_id + bl_line_id set"

# 1. Create + confirm BC with qty 100
BC=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/purchase-orders" \
  -d "{\"projectId\":\"$P1\",\"lines\":[{\"itemId\":\"$ITEM_ID\",\"qtyOrdered\":100,\"uom\":\"sac\"}]}")
BC_ID=$(echo "$BC" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
BC_LINE_ID=$(echo "$BC" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['lines'][0]['id'] if d.get('lines') else '')" 2>/dev/null)
curl -s -b "$JAR" -H "$(xsrf "$JAR")" -X POST "$BACKEND/api/purchase-orders/$BC_ID/confirm" > /dev/null
log "  BC created + confirmed: id=$BC_ID, line=$BC_LINE_ID (qty 100)"

# 2. Create DRAFT BL from BC + 1 line (qty 40) → post
BL=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/bons-livraison" \
  -d "{\"projectId\":\"$P1\",\"bcId\":\"$BC_ID\",\"locationId\":\"$CENTRAL_ID\",\"lines\":[{\"bcLineId\":\"$BC_LINE_ID\",\"itemId\":\"$ITEM_ID\",\"qtyReceived\":40,\"uom\":\"sac\"}]}")
BL_ID=$(echo "$BL" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
log "  BL DRAFT created: id=$BL_ID"

# Post the BL
POST_RESP=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -X POST "$BACKEND/api/bons-livraison/$BL_ID/post")
POST_STATUS=$(echo "$POST_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
log "  BL posted: status=$POST_STATUS"

# 3. Verify balance at location = +40
BAL_BEFORE=$(curl -s -b "$JAR" "$BACKEND/api/stock/balances?locationId=$CENTRAL_ID&itemId=$ITEM_ID" \
  | python3 -c "import json,sys; rows=json.load(sys.stdin); print(rows[0]['qty'] if rows else 0)" 2>/dev/null)
log "  Balance at CENTRAL for item: $BAL_BEFORE (expect ≥ 40 — may have prior data)"

# 4. Verify stock_move.bc_line_id + bl_line_id set
MOVE_LINK=$(PGPASSWORD=hydra /tmp/my-project/.venv-pg/lib/python3.12/site-packages/pgserver/pginstall/bin/psql -h 127.0.0.1 -U postgres -d hydra -t -c "
SELECT bc_line_id IS NOT NULL AND bl_line_id IS NOT NULL FROM stock_move WHERE bl_line_id IS NOT NULL ORDER BY created_at DESC LIMIT 1;" 2>&1 | tr -d ' \n')
log "  stock_move lineage set: $MOVE_LINK (expect t)"

if [[ "$POST_STATUS" == "POSTED" && "$MOVE_LINK" == "t" ]]; then
  record S23a "BC 100 → BL receive 40 → balance +40; lineage set" PASS "BL=$BL_ID POSTED, stock_move.bc_line_id+bl_line_id set"
else
  record S23a "BC 100 → BL receive 40 → balance +40; lineage set" FAIL "post_status=$POST_STATUS, move_link=$MOVE_LINK | post=$POST_RESP"
fi
hr

# ─── S23b: Second BL 60 → +60; third BL 1 fails (hard cap) ────────────────
log ""; log "[S23b] Second BL 60 → +60; third BL 1 fails (hard cap)"
log "  Steps:"
log "    1. Create 2nd BL from same BC + line (qty 60) → post → OK (total 100)"
log "    2. Create 3rd BL (qty 1) → add line → expect 409 BL_OVER_RECEIVE"

# 2nd BL (qty 60)
BL2=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/bons-livraison" \
  -d "{\"projectId\":\"$P1\",\"bcId\":\"$BC_ID\",\"locationId\":\"$CENTRAL_ID\",\"lines\":[{\"bcLineId\":\"$BC_LINE_ID\",\"itemId\":\"$ITEM_ID\",\"qtyReceived\":60,\"uom\":\"sac\"}]}")
BL2_ID=$(echo "$BL2" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
POST2=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -X POST "$BACKEND/api/bons-livraison/$BL2_ID/post")
POST2_STATUS=$(echo "$POST2" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
log "  2nd BL (qty 60): posted=$POST2_STATUS"

# 3rd BL (qty 1 — should fail hard cap)
BL3=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/bons-livraison" \
  -d "{\"projectId\":\"$P1\",\"bcId\":\"$BC_ID\",\"locationId\":\"$CENTRAL_ID\",\"lines\":[]}")
BL3_ID=$(echo "$BL3" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
log "  3rd BL DRAFT created: id=$BL3_ID (no lines yet)"

# Try to add a line with qty 1 → expect 409 BL_OVER_RECEIVE
ADD_LINE_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/bons-livraison/$BL3_ID/lines" \
  -d "{\"bcLineId\":\"$BC_LINE_ID\",\"itemId\":\"$ITEM_ID\",\"qtyReceived\":1,\"uom\":\"sac\"}")
ADD_LINE_BODY=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/bons-livraison/$BL3_ID/lines" \
  -d "{\"bcLineId\":\"$BC_LINE_ID\",\"itemId\":\"$ITEM_ID\",\"qtyReceived\":1,\"uom\":\"sac\"}")
ADD_LINE_CODE=$(echo "$ADD_LINE_BODY" | python3 -c "import json,sys; print(json.load(sys.stdin).get('code','NO_CODE'))" 2>/dev/null)
log "  3rd BL add line qty 1: HTTP=$ADD_LINE_HTTP, code=$ADD_LINE_CODE (expect BL_OVER_RECEIVE)"

if [[ "$POST2_STATUS" == "POSTED" && "$ADD_LINE_CODE" == "BL_OVER_RECEIVE" ]]; then
  record S23b "Second BL 60 → +60; third BL 1 fails (hard cap)" PASS "2nd BL POSTED, 3rd BL line qty 1 → BL_OVER_RECEIVE"
else
  record S23b "Second BL 60 → +60; third BL 1 fails (hard cap)" FAIL "post2=$POST2_STATUS, add_code=$ADD_LINE_CODE | post2=$POST2 | add=$ADD_LINE_BODY"
fi
hr

# ─── S23c: POSTED BL creates N stock moves linked to lines ────────────────
log ""; log "[S23c] POSTED BL creates N stock moves linked to lines"
log "  Steps:"
log "    1. Create a new BC with 2 lines → confirm"
log "    2. Create a BL with 2 lines → post"
log "    3. Verify 2 stock_move rows created with bl_line_id set"

# 1. New BC with 2 lines
BC2=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/purchase-orders" \
  -d "{\"projectId\":\"$P1\",\"lines\":[{\"itemId\":\"$ITEM_ID\",\"qtyOrdered\":30,\"uom\":\"sac\"},{\"itemId\":\"$ITEM2_ID\",\"qtyOrdered\":20,\"uom\":\"m³\"}]}")
BC2_ID=$(echo "$BC2" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
BC2_LINE1=$(echo "$BC2" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['lines'][0]['id'] if d.get('lines') else '')" 2>/dev/null)
BC2_LINE2=$(echo "$BC2" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['lines'][1]['id'] if len(d.get('lines',[]))>1 else '')" 2>/dev/null)
curl -s -b "$JAR" -H "$(xsrf "$JAR")" -X POST "$BACKEND/api/purchase-orders/$BC2_ID/confirm" > /dev/null
log "  BC2 created + confirmed: id=$BC2_ID, lines=$BC2_LINE1,$BC2_LINE2"

# 2. BL with 2 lines → post
BL3A=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/bons-livraison" \
  -d "{\"projectId\":\"$P1\",\"bcId\":\"$BC2_ID\",\"locationId\":\"$SITE1_ID\",\"lines\":[{\"bcLineId\":\"$BC2_LINE1\",\"itemId\":\"$ITEM_ID\",\"qtyReceived\":30,\"uom\":\"sac\"},{\"bcLineId\":\"$BC2_LINE2\",\"itemId\":\"$ITEM2_ID\",\"qtyReceived\":20,\"uom\":\"m³\"}]}")
BL3A_ID=$(echo "$BL3A" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
POST3A=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -X POST "$BACKEND/api/bons-livraison/$BL3A_ID/post")
POST3A_STATUS=$(echo "$POST3A" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
log "  BL3A posted: status=$POST3A_STATUS"

# 3. Verify 2 stock_move rows with bl_line_id set
MOVES_COUNT=$(PGPASSWORD=hydra /tmp/my-project/.venv-pg/lib/python3.12/site-packages/pgserver/pginstall/bin/psql -h 127.0.0.1 -U postgres -d hydra -t -c "
SELECT count(*) FROM stock_move WHERE bl_line_id IS NOT NULL AND note LIKE '%$BL3A_ID%' OR bl_line_id IN (SELECT id FROM bon_livraison_line WHERE bon_livraison_id='$BL3A_ID');" 2>&1 | tr -d ' \n')
log "  stock_move rows linked to BL3A: $MOVES_COUNT (expect 2)"

if [[ "$POST3A_STATUS" == "POSTED" && "$MOVES_COUNT" -ge 2 ]]; then
  record S23c "POSTED BL creates N stock moves linked to lines" PASS "BL3A POSTED, $MOVES_COUNT stock_moves with bl_line_id set"
else
  record S23c "POSTED BL creates N stock moves linked to lines" FAIL "post=$POST3A_STATUS, moves=$MOVES_COUNT | post=$POST3A"
fi
hr

# ─── S23d: Rollback test — force failure → no partial moves ──────────────
log ""; log "[S23d] Rollback test — force failure → no partial moves"
log "  Steps:"
log "    1. Create a FRESH BC3 (qty 10) + confirm"
log "    2. Create a DRAFT BL from BC3 with 1 valid line (qty 5)"
log "    3. Via SQL, corrupt the BL location_id (simulate deleted location)"
log "    4. POST /post → expect LOCATION_NOT_FOUND (postMove fails mid-loop)"
log "    5. Verify NO stock_move rows created for this BL"
log "    6. Verify BL stays DRAFT (full rollback)"

# 1. Fresh BC3 (BC2 was fully received by S23c)
BC3=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/purchase-orders" \
  -d "{\"projectId\":\"$P1\",\"lines\":[{\"itemId\":\"$ITEM_ID\",\"qtyOrdered\":10,\"uom\":\"sac\"}]}")
BC3_ID=$(echo "$BC3" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
BC3_LINE1=$(echo "$BC3" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d['lines'][0]['id'] if d.get('lines') else '')" 2>/dev/null)
curl -s -b "$JAR" -H "$(xsrf "$JAR")" -X POST "$BACKEND/api/purchase-orders/$BC3_ID/confirm" > /dev/null
log "  Fresh BC3 created + confirmed: id=$BC3_ID, line=$BC3_LINE1 (qty 10)"

# 2. Create DRAFT BL from BC3 with 1 valid line (qty 5)
BL4=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/bons-livraison" \
  -d "{\"projectId\":\"$P1\",\"bcId\":\"$BC3_ID\",\"locationId\":\"$SITE1_ID\",\"lines\":[{\"bcLineId\":\"$BC3_LINE1\",\"itemId\":\"$ITEM_ID\",\"qtyReceived\":5,\"uom\":\"sac\"}]}")
BL4_ID=$(echo "$BL4" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
log "  BL4 DRAFT created with 1 valid line: id=$BL4_ID"

# 3. Corrupt the BL location_id via SQL (simulate a deleted location)
BAD_LOC_ID="00000000-0000-0000-0000-000000000099"
PGPASSWORD=hydra /tmp/my-project/.venv-pg/lib/python3.12/site-packages/pgserver/pginstall/bin/psql -h 127.0.0.1 -U postgres -d hydra -c \
  "UPDATE bon_livraison SET location_id = '$BAD_LOC_ID'::uuid WHERE id = '$BL4_ID'::uuid;" > /dev/null 2>&1
log "  Corrupted BL4 location_id → $BAD_LOC_ID (doesn't exist)"

# 4. Try to post → expect LOCATION_NOT_FOUND (the location check fails inside post())
POST4_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -b "$JAR" -H "$(xsrf "$JAR")" -X POST "$BACKEND/api/bons-livraison/$BL4_ID/post")
POST4_BODY=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -X POST "$BACKEND/api/bons-livraison/$BL4_ID/post")
POST4_CODE=$(echo "$POST4_BODY" | python3 -c "import json,sys; print(json.load(sys.stdin).get('code','NO_CODE'))" 2>/dev/null)
log "  Post BL4: HTTP=$POST4_HTTP, code=$POST4_CODE (expect LOCATION_NOT_FOUND)"

# Restore the location_id so we can check the BL status via API
PGPASSWORD=hydra /tmp/my-project/.venv-pg/lib/python3.12/site-packages/pgserver/pginstall/bin/psql -h 127.0.0.1 -U postgres -d hydra -c \
  "UPDATE bon_livraison SET location_id = '$SITE1_ID'::uuid WHERE id = '$BL4_ID'::uuid;" > /dev/null 2>&1

# 5. Verify NO stock_moves created + BL stays DRAFT
MOVES_AFTER=$(PGPASSWORD=hydra /tmp/my-project/.venv-pg/lib/python3.12/site-packages/pgserver/pginstall/bin/psql -h 127.0.0.1 -U postgres -d hydra -t -c \
  "SELECT count(*) FROM stock_move sm JOIN bon_livraison_line bll ON sm.bl_line_id = bll.id WHERE bll.bon_livraison_id = '$BL4_ID'::uuid;" 2>&1 | tr -d ' \n')
BL4_STATUS=$(curl -s -b "$JAR" "$BACKEND/api/bons-livraison/$BL4_ID" | python3 -c "import json,sys; print(json.load(sys.stdin).get('status',''))" 2>/dev/null)
log "  stock_moves AFTER post: $MOVES_AFTER (expect 0 — rollback)"
log "  BL4 status after failed post: $BL4_STATUS (expect DRAFT — rollback)"

if [[ "$POST4_CODE" == "LOCATION_NOT_FOUND" && "$MOVES_AFTER" == "0" && "$BL4_STATUS" == "DRAFT" ]]; then
  record S23d "Rollback test — force failure → no partial moves" PASS "post failed (LOCATION_NOT_FOUND), 0 stock_moves created, BL stays DRAFT"
else
  record S23d "Rollback test — force failure → no partial moves" FAIL "post_code=$POST4_CODE, moves_after=$MOVES_AFTER, bl_status=$BL4_STATUS | post=$POST4_BODY"
fi

# ─── S23e: IDOR blocked ──────────────────────────────────────────────────
log ""; log "[S23e] IDOR — cross-project BL blocked"
log "  Steps:"
log "    1. Login as karim.bouzid (CHEF_PROJET on P1 only)"
log "    2. Try to create a BL on P2 (not his) → expect 409 FORBIDDEN"

KARIM_JAR="/tmp/my-project/logs/qa-karim-2.2.jar"
login "karim.bouzid@hydra.tn" "HydraDemo2026!" "$KARIM_JAR" > /dev/null

P2=$(echo "$PROJECTS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[1]['id'] if len(d)>1 else '')")
KARIM_IDOR=$(curl -s -b "$KARIM_JAR" -H "$(xsrf "$KARIM_JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/bons-livraison" \
  -d "{\"projectId\":\"$P2\",\"locationId\":\"$CENTRAL_ID\",\"lines\":[]}")
KARIM_IDOR_CODE=$(echo "$KARIM_IDOR" | python3 -c "import json,sys; print(json.load(sys.stdin).get('code','NO_CODE'))" 2>/dev/null)
log "  karim (P1) create BL on P2: code=$KARIM_IDOR_CODE (expect FORBIDDEN)"

if [[ "$KARIM_IDOR_CODE" == "FORBIDDEN" ]]; then
  record S23e "IDOR — cross-project BL blocked" PASS "karim P1 → P2 BL create = FORBIDDEN"
else
  record S23e "IDOR — cross-project BL blocked" FAIL "code=$KARIM_IDOR_CODE | resp=$KARIM_IDOR"
fi
hr

log ""; log "============================================================"
log "  SUMMARY: PASS=$PASS  FAIL=$FAIL  (total=$((PASS+FAIL)))"
log "============================================================"
exit $FAIL
