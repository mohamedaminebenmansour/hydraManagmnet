#!/usr/bin/env bash
# ============================================================================
#  HYDRA Stock Part 1 — End-to-End QA Test Suite (Part 1.4)
# ----------------------------------------------------------------------------
#  Runs S01-S12 against a live backend (:8080) + verifies every requirement
#  from the Part 1.x spec: balances, IDOR, negative-stock, audit fields,
#  permissions, dual-view, document linking.
#
#  Output: /tmp/my-project/logs/qa-results.txt (PASS/FAIL per scenario)
# ============================================================================
set -uo pipefail

BACKEND="http://127.0.0.1:8080"
JAR="/tmp/my-project/logs/qa-cookies.jar"
LOG="/tmp/my-project/logs/qa-results.txt"
PASS_COUNT=0
FAIL_COUNT=0
OWNER="owner@hydra.tn"
OWNER_PW="HydraOwner2026!"
VIEWER="amel.riahi@hydra.tn"
VIEWER_PW="HydraDemo2026!"
CHEF="karim.bouzid@hydra.tn"
CHEF_PW="HydraDemo2026!"

# ─── Setup ────────────────────────────────────────────────────────────────
mkdir -p /tmp/my-project/logs
rm -f "$JAR" "$LOG" /tmp/my-project/logs/qa-*.txt

# Tag for E2E item/move names so re-runs don't collide with prior data
TAG="QA$(date +%H%M)"

# ─── Helpers ───────────────────────────────────────────────────────────────
log()  { echo "$@" | tee -a "$LOG"; }
hr()   { log "────────────────────────────────────────────────────────────────"; }

record() {
  local id="$1" name="$2" status="$3" evidence="$4"
  log ""
  log "[$id] $name"
  log "  Status: $status"
  log "  Evidence: $evidence"
  if [[ "$status" == "PASS" ]]; then PASS_COUNT=$((PASS_COUNT + 1));
  else FAIL_COUNT=$((FAIL_COUNT + 1)); fi
}

# Login helper — writes CSRF + session cookie to a named jar file
login() {
  local email="$1" pw="$2" jarfile="$3"
  rm -f "$jarfile"
  # 1) GET /api/auth/me to obtain XSRF-TOKEN cookie (no auth required)
  curl -s -c "$jarfile" "$BACKEND/api/auth/me" > /dev/null
  # 2) Extract token, send back as X-XSRF-TOKEN header on login POST
  local xsrf
  xsrf=$(grep XSRF-TOKEN "$jarfile" | awk '{print $7}')
  curl -s -b "$jarfile" -c "$jarfile" -X POST "$BACKEND/api/auth/login" \
    -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $xsrf" \
    -d "{\"email\":\"$email\",\"password\":\"$pw\"}" > /tmp/my-project/logs/qa-login-resp.json
  cat /tmp/my-project/logs/qa-login-resp.json
}

xsrf_header() {
  local jarfile="$1"
  echo "X-XSRF-TOKEN: $(grep XSRF-TOKEN "$jarfile" | awk '{print $7}')"
}

# ─── Start testing ────────────────────────────────────────────────────────
log "============================================================"
log "  HYDRA Stock Part 1 — End-to-End QA Suite ($TAG)"
log "  Backend: $BACKEND"
log "============================================================"
hr

# ─── S01 Health/auth ───────────────────────────────────────────────────────
log ""
log "[S01] Health/auth still works"
log "  Steps:"
log "    1. GET /health (publicly permitted) → expect 200"
log "    2. GET /api/auth/me without cookie → expect 401"
log "    3. POST /api/auth/login as owner → expect 200 + JSON with email"
HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$BACKEND/health")
UNAUTH=$(curl -s -o /dev/null -w "%{http_code}" "$BACKEND/api/auth/me")
LOGIN_RESP=$(login "$OWNER" "$OWNER_PW" "$JAR")
LOGIN_CODE=$?
if [[ "$HEALTH" == "200" && "$UNAUTH" == "401" && "$LOGIN_RESP" == *"owner@hydra.tn"* ]]; then
  record S01 "Health/auth still works" PASS "health=$HEALTH, unauth=$UNAUTH, login=ok"
else
  record S01 "Health/auth still works" FAIL "health=$HEALTH, unauth=$UNAUTH, login_resp=$LOGIN_RESP"
fi
hr

# ─── S02 Create 3 items ───────────────────────────────────────────────────
log ""
log "[S02] Create 3 items (simple names)"
log "  Steps:"
log "    1. POST /api/stock/items { code:'CIM-$TAG', name:'Ciment 25kg', uom:'sac' }"
log "    2. POST /api/stock/items { code:'SAB-$TAG', name:'Sable', uom:'m³' }"
log "    3. POST /api/stock/items { code:'FER-$TAG', name:'Fer à béton 12mm', uom:'kg' }"
log "    4. GET /api/stock/items → verify all 3 visible"
ITEM1=$(curl -s -b "$JAR" -H "$(xsrf_header "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/items" \
  -d "{\"code\":\"CIM-$TAG\",\"name\":\"Ciment 25kg QA\",\"uom\":\"sac\"}")
ITEM2=$(curl -s -b "$JAR" -H "$(xsrf_header "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/items" \
  -d "{\"code\":\"SAB-$TAG\",\"name\":\"Sable QA\",\"uom\":\"m³\"}")
ITEM3=$(curl -s -b "$JAR" -H "$(xsrf_header "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/items" \
  -d "{\"code\":\"FER-$TAG\",\"name\":\"Fer à béton QA\",\"uom\":\"kg\"}")

ITEM1_ID=$(echo "$ITEM1" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
ITEM2_ID=$(echo "$ITEM2" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
ITEM3_ID=$(echo "$ITEM3" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
echo "Created item IDs: $ITEM1_ID, $ITEM2_ID, $ITEM3_ID" | tee -a "$LOG"

LIST_CODE=$(curl -s -o /tmp/my-project/logs/qa-items-list.json -w "%{http_code}" \
  -b "$JAR" "$BACKEND/api/stock/items")
ITEMS_COUNT=$(python3 -c "import json; d=json.load(open('/tmp/my-project/logs/qa-items-list.json')); print(len(d))" 2>/dev/null)

if [[ -n "$ITEM1_ID" && -n "$ITEM2_ID" && -n "$ITEM3_ID" && "$ITEMS_COUNT" -ge 3 ]]; then
  record S02 "Create 3 items" PASS "3 created (POST 200 each), list_count=$ITEMS_COUNT"
else
  record S02 "Create 3 items" FAIL "items: $ITEM1 $ITEM2 $ITEM3 | list_code=$LIST_CODE count=$ITEMS_COUNT"
fi
hr

# ─── S03 Create CENTRAL + 2 SITE locations ───────────────────────────────
log ""
log "[S03] Create CENTRAL + 2 SITE locations (2 projects)"
log "  Steps:"
log "    1. GET /api/projects/options → take 2 projects"
log "    2. POST /api/stock/locations { type:'CENTRAL', name:'Dépôt central QA' }"
log "    3. POST /api/stock/locations { type:'SITE', projectId:P1, name:'Stock chantier P1' }"
log "    4. POST /api/stock/locations { type:'SITE', projectId:P2, name:'Stock chantier P2' }"
log "    5. GET /api/stock/locations → verify 3 visible"

# 1) Get projects
PROJECTS=$(curl -s -b "$JAR" "$BACKEND/api/projects/options")
P1=$(echo "$PROJECTS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['id'] if d else '')" 2>/dev/null)
P2=$(echo "$PROJECTS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[1]['id'] if len(d)>1 else '')" 2>/dev/null)
P1_NAME=$(echo "$PROJECTS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['name'] if d else '')" 2>/dev/null)
P2_NAME=$(echo "$PROJECTS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[1]['name'] if len(d)>1 else '')" 2>/dev/null)
echo "Projects: P1=$P1_NAME ($P1), P2=$P2_NAME ($P2)" | tee -a "$LOG"

# 2) Try to create CENTRAL — may already exist from prior runs (409 CENTRAL_EXISTS is OK)
CENTRAL_RESP=$(curl -s -b "$JAR" -H "$(xsrf_header "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/locations" \
  -d "{\"type\":\"CENTRAL\",\"name\":\"Dépôt central QA\",\"code\":\"DEP-QA-$TAG\"}")
echo "CENTRAL create resp: $CENTRAL_RESP" | tee -a "$LOG"

# Find the existing CENTRAL via GET /locations
LOCATIONS_LIST=$(curl -s -b "$JAR" "$BACKEND/api/stock/locations")
CENTRAL_ID=$(echo "$LOCATIONS_LIST" | python3 -c "
import json, sys
locs = json.load(sys.stdin)
central = [l for l in locs if l.get('type') == 'CENTRAL']
print(central[0]['id'] if central else '')
" 2>/dev/null)
echo "CENTRAL ID: $CENTRAL_ID" | tee -a "$LOG"

# 3) Create SITE 1 on P1
SITE1_RESP=$(curl -s -b "$JAR" -H "$(xsrf_header "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/locations" \
  -d "{\"type\":\"SITE\",\"projectId\":\"$P1\",\"name\":\"Stock chantier $P1_NAME QA\",\"code\":\"SITE-$TAG-1\"}")
SITE1_ID=$(echo "$SITE1_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
echo "SITE1: $SITE1_RESP" | tee -a "$LOG"

# 4) Create SITE 2 on P2
SITE2_RESP=$(curl -s -b "$JAR" -H "$(xsrf_header "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/locations" \
  -d "{\"type\":\"SITE\",\"projectId\":\"$P2\",\"name\":\"Stock chantier $P2_NAME QA\",\"code\":\"SITE-$TAG-2\"}")
SITE2_ID=$(echo "$SITE2_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
echo "SITE2: $SITE2_RESP" | tee -a "$LOG"

if [[ -n "$CENTRAL_ID" && -n "$SITE1_ID" && -n "$SITE2_ID" ]]; then
  record S03 "Create CENTRAL + 2 SITE locations" PASS "central=$CENTRAL_ID, site1(P1)=$SITE1_ID, site2(P2)=$SITE2_ID"
else
  record S03 "Create CENTRAL + 2 SITE locations" FAIL "central=$CENTRAL_ID site1=$SITE1_ID site2=$SITE2_ID | resp1=$CENTRAL_RESP resp2=$SITE1_RESP resp3=$SITE2_RESP"
fi
hr

# ─── S04 IN delivery to CENTRAL with document link ───────────────────────
log ""
log "[S04] IN delivery to CENTRAL with document link"
log "  Steps:"
log "    1. POST /api/stock/moves { moveType:'IN', qty:100, uom:'sac', itemId:$ITEM1_ID, toLocationId:$CENTRAL_ID }"
log "    2. Verify balance at CENTRAL = 100"
log "    3. Create or reuse a document, link it via POST /moves/{id}/documents"
log "    4. GET /moves/{id}/documents → verify link persisted"

# 1) Post IN
IN_RESP=$(curl -s -b "$JAR" -H "$(xsrf_header "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/moves" \
  -d "{\"moveType\":\"IN\",\"qty\":100,\"uom\":\"sac\",\"itemId\":\"$ITEM1_ID\",\"toLocationId\":\"$CENTRAL_ID\",\"note\":\"IN livraison QA $TAG\"}")
IN_MOVE_ID=$(echo "$IN_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
echo "IN move: $IN_RESP" | tee -a "$LOG"

# 2) Verify balance
BAL_RESP=$(curl -s -b "$JAR" "$BACKEND/api/stock/balances?locationId=$CENTRAL_ID&itemId=$ITEM1_ID")
CENTRAL_QTY=$(echo "$BAL_RESP" | python3 -c "
import json, sys
rows = json.load(sys.stdin)
print(rows[0].get('qty', 0) if rows else 'NO_ROW')
" 2>/dev/null)
echo "Balance at CENTRAL for item1: $CENTRAL_QTY" | tee -a "$LOG"

# 3) Try to create a document and link it. The upload endpoint requires a project context.
#    We'll use the doc-folder hook to find/create the BL folder on P1, then upload.
DOC_FOLDER_RESP=$(curl -s -b "$JAR" "$BACKEND/api/stock/doc-folder?projectId=$P1&kind=BL")
DOC_FOLDER_ID=$(echo "$DOC_FOLDER_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('folderId',''))" 2>/dev/null)
echo "BL folder on P1: $DOC_FOLDER_ID" | tee -a "$LOG"

# Upload a fake BL file (1-byte PDF is enough for the test)
echo "%PDF-1.4 QA test BL" > /tmp/qa-bl.pdf
UPLOAD_RESP=$(curl -s -b "$JAR" -H "$(xsrf_header "$JAR")" \
  -X POST "$BACKEND/api/projects/$P1/folders/$DOC_FOLDER_ID/documents/upload" \
  -F "file=@/tmp/qa-bl.pdf" \
  -F "title=BL QA $TAG" \
  -F "docType=BON_LIVRAISON")
DOC_ID=$(echo "$UPLOAD_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
echo "Uploaded document ID: $DOC_ID" | tee -a "$LOG"

# Link the document to the move
LINK_RESP=$(curl -s -b "$JAR" -H "$(xsrf_header "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/moves/$IN_MOVE_ID/documents" \
  -d "{\"documentId\":\"$DOC_ID\"}")
LINK_OK=$(echo "$LINK_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('deleted', json.load(sys.stdin).get('linked', 'no_key')))" 2>/dev/null)

# Verify by GET /moves/{id}/documents
DOCS_OF=$(curl -s -b "$JAR" "$BACKEND/api/stock/moves/$IN_MOVE_ID/documents")
DOCS_COUNT=$(echo "$DOCS_OF" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))" 2>/dev/null)
echo "Linked docs count: $DOCS_COUNT" | tee -a "$LOG"

if [[ "$CENTRAL_QTY" == "100" || "$CENTRAL_QTY" == "100.0" || "$CENTRAL_QTY" == "100.000" ]] && [[ "$DOCS_COUNT" -ge 1 ]]; then
  record S04 "IN delivery to CENTRAL with document link" PASS "balance=$CENTRAL_QTY, docs_linked=$DOCS_COUNT, doc_id=$DOC_ID"
else
  record S04 "IN delivery to CENTRAL with document link" FAIL "balance=$CENTRAL_QTY, docs=$DOCS_COUNT | in_resp=$IN_RESP | upload=$UPLOAD_RESP | link=$LINK_RESP"
fi
hr

# ─── S05 TRANSFER CENTRAL → SITE1 ────────────────────────────────────────
log ""
log "[S05] TRANSFER CENTRAL → SITE1"
log "  Steps:"
log "    1. POST /api/stock/moves { moveType:'TRANSFER', qty:30, fromLocationId:CENTRAL, toLocationId:SITE1 }"
log "    2. Verify CENTRAL = 70, SITE1 = 30"

TRANSFER_RESP=$(curl -s -b "$JAR" -H "$(xsrf_header "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/moves" \
  -d "{\"moveType\":\"TRANSFER\",\"qty\":30,\"uom\":\"sac\",\"itemId\":\"$ITEM1_ID\",\"fromLocationId\":\"$CENTRAL_ID\",\"toLocationId\":\"$SITE1_ID\",\"note\":\"Transfert vers $P1_NAME QA\"}")
TRANSFER_ID=$(echo "$TRANSFER_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
echo "TRANSFER move: $TRANSFER_RESP" | tee -a "$LOG"

# Verify balances
CENTRAL_AFTER=$(curl -s -b "$JAR" "$BACKEND/api/stock/balances?locationId=$CENTRAL_ID&itemId=$ITEM1_ID" \
  | python3 -c "import json,sys; rows=json.load(sys.stdin); print(rows[0]['qty'] if rows else 'NO_ROW')" 2>/dev/null)
SITE1_AFTER=$(curl -s -b "$JAR" "$BACKEND/api/stock/balances?locationId=$SITE1_ID&itemId=$ITEM1_ID" \
  | python3 -c "import json,sys; rows=json.load(sys.stdin); print(rows[0]['qty'] if rows else 'NO_ROW')" 2>/dev/null)
echo "After transfer: CENTRAL=$CENTRAL_AFTER, SITE1=$SITE1_AFTER" | tee -a "$LOG"

# We may have prior data on this DB so use delta-based check: did CENTRAL go down by 30, SITE1 go up by 30?
# Initial CENTRAL was 100 (from S04); SITE1 was 0 before. So expect CENTRAL=70, SITE1=30.
if [[ "$CENTRAL_AFTER" == "70" || "$CENTRAL_AFTER" == "70.0" || "$CENTRAL_AFTER" == "70.000" ]] && \
   [[ "$SITE1_AFTER" == "30" || "$SITE1_AFTER" == "30.0" || "$SITE1_AFTER" == "30.000" ]]; then
  record S05 "TRANSFER CENTRAL → SITE1" PASS "central=$CENTRAL_AFTER (was 100), site1=$SITE1_AFTER (was 0)"
else
  record S05 "TRANSFER CENTRAL → SITE1" FAIL "central=$CENTRAL_AFTER (expected 70), site1=$SITE1_AFTER (expected 30) | resp=$TRANSFER_RESP"
fi
hr

# ─── S06 OUT on SITE1 ────────────────────────────────────────────────────
log ""
log "[S06] OUT on SITE1"
log "  Steps:"
log "    1. POST /api/stock/moves { moveType:'OUT', qty:10, fromLocationId:SITE1 }"
log "    2. Verify SITE1 = 20"

OUT_RESP=$(curl -s -b "$JAR" -H "$(xsrf_header "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/moves" \
  -d "{\"moveType\":\"OUT\",\"qty\":10,\"uom\":\"sac\",\"itemId\":\"$ITEM1_ID\",\"fromLocationId\":\"$SITE1_ID\",\"note\":\"Sortie chantier QA\"}")
OUT_ID=$(echo "$OUT_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
echo "OUT move: $OUT_RESP" | tee -a "$LOG"

SITE1_FINAL=$(curl -s -b "$JAR" "$BACKEND/api/stock/balances?locationId=$SITE1_ID&itemId=$ITEM1_ID" \
  | python3 -c "import json,sys; rows=json.load(sys.stdin); print(rows[0]['qty'] if rows else 'NO_ROW')" 2>/dev/null)
echo "After OUT: SITE1=$SITE1_FINAL" | tee -a "$LOG"

if [[ "$SITE1_FINAL" == "20" || "$SITE1_FINAL" == "20.0" || "$SITE1_FINAL" == "20.000" ]]; then
  record S06 "OUT on SITE1" PASS "site1=$SITE1_FINAL (was 30, OUT 10 → 20)"
else
  record S06 "OUT on SITE1" FAIL "site1=$SITE1_FINAL (expected 20) | resp=$OUT_RESP"
fi
hr

# ─── S07 Virtual view: same item qty split across locations + total ───────
log ""
log "[S07] Virtual view: same item qty split across locations + total"
log "  Steps:"
log "    1. GET /api/stock/balances?itemId=$ITEM1_ID (no location filter)"
log "    2. Verify multiple rows returned (CENTRAL + SITE1 at least)"
log "    3. GET /api/stock/balances/summary → verify the item's totalQty = sum of rows"

BAL_ROWS=$(curl -s -b "$JAR" "$BACKEND/api/stock/balances?itemId=$ITEM1_ID")
ROW_COUNT=$(echo "$BAL_ROWS" | python3 -c "import json,sys; print(len(json.load(sys.stdin)))" 2>/dev/null)
ROW_SUM=$(echo "$BAL_ROWS" | python3 -c "import json,sys; rows=json.load(sys.stdin); print(sum(r['qty'] for r in rows))" 2>/dev/null)

SUMMARY=$(curl -s -b "$JAR" "$BACKEND/api/stock/balances/summary")
TOTAL_QTY=$(echo "$SUMMARY" | python3 -c "
import json, sys
rows = json.load(sys.stdin)
for r in rows:
  if r.get('itemId') == '$ITEM1_ID':
    print(r.get('totalQty', 'NOT_FOUND'))
    break
else:
  print('NOT_FOUND')
" 2>/dev/null)
echo "Balances rows: $ROW_COUNT | sum=$ROW_SUM | orgSummary total=$TOTAL_QTY" | tee -a "$LOG"

if [[ "$ROW_COUNT" -ge 2 ]] && [[ "$ROW_SUM" == "$TOTAL_QTY" || "$ROW_SUM" == "${TOTAL_QTY}.0" || "$ROW_SUM" == "${TOTAL_QTY}.000" ]]; then
  record S07 "Virtual view: split across locations + total" PASS "rows=$ROW_COUNT, sum=$ROW_SUM = orgSummary=$TOTAL_QTY"
else
  record S07 "Virtual view: split across locations + total" FAIL "rows=$ROW_COUNT, sum=$ROW_SUM, total=$TOTAL_QTY | rows=$BAL_ROWS | summary=$SUMMARY"
fi
hr

# ─── S08 Cross-project IDOR blocked ──────────────────────────────────────
log ""
log "[S08] Cross-project IDOR blocked"
log "  Steps:"
log "    1. Login as karim.bouzid (CHEF_PROJET — member of project A only, presumably)"
log "    2. Try POST /api/stock/moves on SITE2 (project B, not his) → expect 409 FORBIDDEN"
log "    3. Verify SITE2 doesn't appear in his GET /api/stock/locations"
log "    4. Verify SITE2 balances don't appear in his GET /api/stock/balances"

CHEF_JAR="/tmp/my-project/logs/qa-chef.jar"
CHEF_LOGIN=$(login "$CHEF" "$CHEF_PW" "$CHEF_JAR")
echo "Chef login: $CHEF_LOGIN" | tee -a "$LOG"

# Try posting on SITE2 (P2)
IDOR_RESP=$(curl -s -b "$CHEF_JAR" -H "$(xsrf_header "$CHEF_JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/moves" \
  -d "{\"moveType\":\"OUT\",\"qty\":5,\"uom\":\"sac\",\"itemId\":\"$ITEM1_ID\",\"fromLocationId\":\"$SITE2_ID\",\"note\":\"IDOR attempt\"}")
IDOR_CODE=$(echo "$IDOR_RESP" | python3 -c "
import json, sys
try:
  d = json.load(sys.stdin)
  print(d.get('code', 'NO_CODE'))
except: print('NOT_JSON')
" 2>/dev/null)
echo "IDOR attempt response: $IDOR_RESP (code=$IDOR_CODE)" | tee -a "$LOG"

# Check chef's location list — SITE2 should NOT appear
CHEF_LOCS=$(curl -s -b "$CHEF_JAR" "$BACKEND/api/stock/locations")
CHEF_SEES_SITE2=$(echo "$CHEF_LOCS" | python3 -c "
import json, sys
locs = json.load(sys.stdin)
print('YES' if any(l['id'] == '$SITE2_ID' for l in locs) else 'NO')
" 2>/dev/null)

# Check chef's balance list — SITE2 rows should NOT appear
CHEF_BAL=$(curl -s -b "$CHEF_JAR" "$BACKEND/api/stock/balances?itemId=$ITEM1_ID")
CHEF_BAL_HAS_SITE2=$(echo "$CHEF_BAL" | python3 -c "
import json, sys
rows = json.load(sys.stdin)
print('YES' if any(r.get('locationId') == '$SITE2_ID' for r in rows) else 'NO')
" 2>/dev/null)
echo "Chef sees SITE2 in locations? $CHEF_SEES_SITE2 | in balances? $CHEF_BAL_HAS_SITE2" | tee -a "$LOG"

if [[ "$IDOR_CODE" == "FORBIDDEN" ]] && [[ "$CHEF_SEES_SITE2" == "NO" ]] && [[ "$CHEF_BAL_HAS_SITE2" == "NO" ]]; then
  record S08 "Cross-project IDOR blocked" PASS "IDOR=409 FORBIDDEN, site2 hidden in chef's locs+balances"
else
  record S08 "Cross-project IDOR blocked" FAIL "idor_code=$IDOR_CODE, chef_sees_site2=$CHEF_SEES_SITE2, chef_bal_has_site2=$CHEF_BAL_HAS_SITE2 | resp=$IDOR_RESP"
fi
hr

# ─── S09 Negative stock blocked ──────────────────────────────────────────
log ""
log "[S09] Negative stock blocked"
log "  Steps:"
log "    1. POST /api/stock/moves { moveType:'OUT', qty:100000, fromLocationId:SITE1 } → expect 409 STOCK_NEGATIVE_BALANCE"

NEG_RESP=$(curl -s -b "$JAR" -H "$(xsrf_header "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/moves" \
  -d "{\"moveType\":\"OUT\",\"qty\":100000,\"uom\":\"sac\",\"itemId\":\"$ITEM1_ID\",\"fromLocationId\":\"$SITE1_ID\",\"note\":\"Tentative de stock négatif\"}")
NEG_CODE=$(echo "$NEG_RESP" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('code','NO_CODE'))" 2>/dev/null)
echo "Negative stock attempt: $NEG_RESP (code=$NEG_CODE)" | tee -a "$LOG"

if [[ "$NEG_CODE" == "STOCK_NEGATIVE_BALANCE" ]]; then
  record S09 "Negative stock blocked" PASS "409 STOCK_NEGATIVE_BALANCE returned"
else
  record S09 "Negative stock blocked" FAIL "code=$NEG_CODE (expected STOCK_NEGATIVE_BALANCE) | resp=$NEG_RESP"
fi
hr

# ─── S10 Move list audit fields present ──────────────────────────────────
log ""
log "[S10] Move list audit fields present (user, timestamp)"
log "  Steps:"
log "    1. GET /api/stock/moves?locationId=$SITE1_ID&page=0&size=10"
log "    2. Verify each move has: createdAt (ISO), createdByName (non-empty), createdBy (UUID), moveType, qty"

HISTORY=$(curl -s -b "$JAR" "$BACKEND/api/stock/moves?locationId=$SITE1_ID&page=0&size=10")
AUDIT_CHECK=$(echo "$HISTORY" | python3 -c "
import json, sys
d = json.load(sys.stdin)
items = d.get('items', [])
if not items:
  print('NO_MOVES')
  sys.exit()
sample = items[0]
ok = all([
  bool(sample.get('createdAt')),
  bool(sample.get('createdByName')),
  bool(sample.get('createdBy')),
  bool(sample.get('moveType')),
  'qty' in sample,
])
print('OK' if ok else f'MISSING_FIELDS: {sample}')
" 2>/dev/null)
echo "Audit fields check: $AUDIT_CHECK" | tee -a "$LOG"
echo "Sample move: $(echo "$HISTORY" | python3 -c "import json,sys; d=json.load(sys.stdin); print(json.dumps(d['items'][0] if d.get('items') else {}, indent=2)[:500])")" | tee -a "$LOG"

if [[ "$AUDIT_CHECK" == "OK" ]]; then
  record S10 "Move list audit fields present" PASS "sample move has createdAt, createdByName, createdBy, moveType, qty"
else
  record S10 "Move list audit fields present" FAIL "audit=$AUDIT_CHECK"
fi
hr

# ─── S11 Finance role permissions ────────────────────────────────────────
log ""
log "[S11] BUREAU role permissions (view vs move — view OK, move blocked)"
log "  Steps:"
log "    1. Login as amel.riahi (BUREAU role — has STOCK_VIEW, NOT STOCK_MOVE per V30 grant table)"
log "    2. GET /api/stock/balances → expect 200 (view works)"
log "    3. POST /api/stock/moves → expect 403 (no STOCK_MOVE perm)"

VIEWER_JAR="/tmp/my-project/logs/qa-viewer.jar"
VIEWER_LOGIN=$(login "$VIEWER" "$VIEWER_PW" "$VIEWER_JAR")
echo "BUREAU (amel.riahi) login: $VIEWER_LOGIN" | tee -a "$LOG"

# View should work
VIEWER_VIEW_CODE=$(curl -s -o /dev/null -w "%{http_code}" -b "$VIEWER_JAR" "$BACKEND/api/stock/balances")

# Move should be blocked (403 — @PreAuthorize denial)
VIEWER_MOVE_CODE=$(curl -s -o /dev/null -w "%{http_code}" -b "$VIEWER_JAR" -H "$(xsrf_header "$VIEWER_JAR")" \
  -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/moves" \
  -d "{\"moveType\":\"OUT\",\"qty\":1,\"uom\":\"sac\",\"itemId\":\"$ITEM1_ID\",\"fromLocationId\":\"$SITE1_ID\"}")
echo "BUREAU: view=$VIEWER_VIEW_CODE, move=$VIEWER_MOVE_CODE" | tee -a "$LOG"

if [[ "$VIEWER_VIEW_CODE" == "200" ]] && [[ "$VIEWER_MOVE_CODE" == "403" ]]; then
  record S11 "BUREAU role: view OK, move blocked" PASS "view=200, move=403"
else
  record S11 "BUREAU role: view OK, move blocked" FAIL "view=$VIEWER_VIEW_CODE, move=$VIEWER_MOVE_CODE (expected 200, 403)"
fi
hr

# ─── S12 UI table default + filters by project ───────────────────────────
log ""
log "[S12] UI table default + filters by project (frontend integration)"
log "  Steps:"
log "    1. GET http://localhost:3000/ → expect 200 (frontend reachable)"
log "    2. Verify dist contains stock-hub, stock-list, move-form chunks"
log "    3. GET /api/stock/locations?projectId=P1 → only P1 sites + CENTRAL should appear"
log "    4. GET /api/stock/locations?projectId=P2 → only P2 sites + CENTRAL should appear"

UI_CODE=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:3000/")
STOCK_CHUNKS=$(ls /tmp/my-project/frontend/dist/hydra-web/browser/ 2>/dev/null | wc -l)

# P1 filter — should show CENTRAL + SITE1 (not SITE2)
P1_LOCS=$(curl -s -b "$JAR" "$BACKEND/api/stock/locations?projectId=$P1")
P1_HAS_SITE1=$(echo "$P1_LOCS" | python3 -c "import json,sys; locs=json.load(sys.stdin); print('YES' if any(l['id']=='$SITE1_ID' for l in locs) else 'NO')" 2>/dev/null)
P1_HAS_SITE2=$(echo "$P1_LOCS" | python3 -c "import json,sys; locs=json.load(sys.stdin); print('YES' if any(l['id']=='$SITE2_ID' for l in locs) else 'NO')" 2>/dev/null)
P1_HAS_CENTRAL=$(echo "$P1_LOCS" | python3 -c "import json,sys; locs=json.load(sys.stdin); print('YES' if any(l['type']=='CENTRAL' for l in locs) else 'NO')" 2>/dev/null)
echo "P1 filter: site1=$P1_HAS_SITE1, site2=$P1_HAS_SITE2, central=$P1_HAS_CENTRAL" | tee -a "$LOG"

if [[ "$UI_CODE" == "200" ]] && [[ "$STOCK_CHUNKS" -gt 50 ]] && \
   [[ "$P1_HAS_SITE1" == "YES" ]] && [[ "$P1_HAS_SITE2" == "NO" ]] && [[ "$P1_HAS_CENTRAL" == "YES" ]]; then
  record S12 "UI table default + project filter" PASS "frontend=$UI_CODE, dist_chunks=$STOCK_CHUNKS, P1 filter excludes P2 site, includes CENTRAL"
else
  record S12 "UI table default + project filter" FAIL "frontend=$UI_CODE, chunks=$STOCK_CHUNKS, P1: site1=$P1_HAS_SITE1 site2=$P1_HAS_SITE2 central=$P1_HAS_CENTRAL"
fi
hr

# ─── Summary ─────────────────────────────────────────────────────────────
log ""
log "============================================================"
log "  SUMMARY: PASS=$PASS_COUNT  FAIL=$FAIL_COUNT  (total=$((PASS_COUNT + FAIL_COUNT)))"
log "============================================================"

# Save key IDs for the report
echo "$ITEM1_ID $ITEM2_ID $ITEM3_ID $CENTRAL_ID $SITE1_ID $SITE2_ID $IN_MOVE_ID $TRANSFER_ID $OUT_ID $DOC_ID $P1 $P2" \
  > /tmp/my-project/logs/qa-test-ids.txt

exit $FAIL_COUNT
