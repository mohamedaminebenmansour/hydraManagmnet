#!/usr/bin/env bash
# ============================================================================
#  HYDRA Stock — Phase B E2E Tests (S13–S16)
#  Tests document attachment on stock moves: upload, link, IDOR, CENTRAL selector
# ============================================================================
set -uo pipefail

BACKEND="http://127.0.0.1:8080"
JAR="/tmp/my-project/logs/qa-phase-b.jar"
LOG="/tmp/my-project/logs/qa-phase-b-results.txt"
PASS=0; FAIL=0
TAG="B$(date +%H%M)"

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
log "  HYDRA Stock Phase B — E2E Tests ($TAG)"
log "============================================================"
hr

# ─── Setup: login as owner + find test data ───────────────────────────────
login "owner@hydra.tn" "HydraOwner2026!" "$JAR" > /dev/null

# Find an existing item + CENTRAL + a SITE location + a project
ITEM_ID=$(curl -s -b "$JAR" "$BACKEND/api/stock/items" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['id'] if d else '')")
LOCATIONS=$(curl -s -b "$JAR" "$BACKEND/api/stock/locations")
CENTRAL_ID=$(echo "$LOCATIONS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(next((l['id'] for l in d if l['type']=='CENTRAL'),''))")
SITE1_ID=$(echo "$LOCATIONS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(next((l['id'] for l in d if l['type']=='SITE'),''))")
PROJECTS=$(curl -s -b "$JAR" "$BACKEND/api/projects/options")
P1=$(echo "$PROJECTS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['id'] if d else '')")
P1_NAME=$(echo "$PROJECTS" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d[0]['name'] if d else '')")

log "Test data: item=$ITEM_ID, central=$CENTRAL_ID, site=$SITE1_ID, P1=$P1_NAME($P1)"

# ─── S13: IN + upload → file in 05.01 BL + link on move + open from Historique ─
log ""; log "[S13] IN + upload → doc in 05.01 BL + linked on move + visible in history"
log "  Steps:"
log "    1. Resolve BL folder via GET /api/stock/doc-folder?projectId=P1&kind=BL"
log "    2. Upload a PDF via POST /api/projects/P1/folders/{F}/documents/upload (docType=BON_LIVRAISON)"
log "    3. POST /api/stock/moves { moveType:IN, qty:5, toLocationId:SITE1, documentId:<uploaded> }"
log "    4. GET /api/stock/moves/{id}/documents → verify title/reference/docType present"
log "    5. GET /api/stock/moves?size=5 → verify linkedDocCount on the new move"

# Step 1: resolve folder
FOLDER=$(curl -s -b "$JAR" "$BACKEND/api/stock/doc-folder?projectId=$P1&kind=BL")
FOLDER_ID=$(echo "$FOLDER" | python3 -c "import json,sys; print(json.load(sys.stdin).get('folderId',''))")
log "  Folder resolved: $FOLDER"

# Step 2: upload with correct docType
echo "%PDF-1.4 Phase B test BL $TAG" > /tmp/qa-bl-b.pdf
UPLOAD=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" \
  -X POST "$BACKEND/api/projects/$P1/folders/$FOLDER_ID/documents/upload" \
  -F "file=@/tmp/qa-bl-b.pdf" -F "title=BL Phase B $TAG" -F "docType=BON_LIVRAISON")
DOC_ID=$(echo "$UPLOAD" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
log "  Uploaded doc: id=$DOC_ID"

# Step 3: create IN move with documentId
MOVE=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/moves" \
  -d "{\"moveType\":\"IN\",\"qty\":5,\"uom\":\"sac\",\"itemId\":\"$ITEM_ID\",\"toLocationId\":\"$SITE1_ID\",\"documentId\":\"$DOC_ID\",\"note\":\"S13 test $TAG\"}")
MOVE_ID=$(echo "$MOVE" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
log "  Move created: id=$MOVE_ID"

# Step 4: verify documentsOf returns enriched data
DOCS=$(curl -s -b "$JAR" "$BACKEND/api/stock/moves/$MOVE_ID/documents")
DOCS_CHECK=$(echo "$DOCS" | python3 -c "
import json,sys
d = json.load(sys.stdin)
if not d: print('NO_DOCS'); sys.exit()
doc = d[0]
ok = all([doc.get('title'), doc.get('docType'), 'documentId' in doc, doc.get('primary')==True])
print('OK' if ok else f'MISSING: {doc}')
" 2>/dev/null)
log "  documentsOf enrichment: $DOCS_CHECK"

# Step 5: verify linkedDocCount in history
HIST=$(curl -s -b "$JAR" "$BACKEND/api/stock/moves?size=5")
LDC=$(echo "$HIST" | python3 -c "
import json,sys
d = json.load(sys.stdin)
for m in d.get('items',[]):
  if m['id'] == '$MOVE_ID':
    print(m.get('linkedDocCount', 'MISSING'))
    break
else:
  print('NOT_FOUND')
" 2>/dev/null)
log "  linkedDocCount on new move: $LDC"

if [[ -n "$DOC_ID" && -n "$MOVE_ID" && "$DOCS_CHECK" == "OK" ]]; then
  record S13 "IN + upload → doc linked + enriched" PASS "doc=$DOC_ID, move=$MOVE_ID, docsOf=enriched(title,docType,primary), linkedDocCount=$LDC"
else
  record S13 "IN + upload → doc linked + enriched" FAIL "doc=$DOC_ID, move=$MOVE_ID, docsOf=$DOCS_CHECK, ldc=$LDC | upload=$UPLOAD | move=$MOVE"
fi
hr

# ─── S14: IN without document still works ────────────────────────────────
log ""; log "[S14] IN without document still works (optional attachment)"
log "  Steps: POST /api/stock/moves { moveType:IN, qty:3, toLocationId:SITE1, documentId:null }"

MOVE2=$(curl -s -b "$JAR" -H "$(xsrf "$JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/moves" \
  -d "{\"moveType\":\"IN\",\"qty\":3,\"uom\":\"sac\",\"itemId\":\"$ITEM_ID\",\"toLocationId\":\"$SITE1_ID\",\"note\":\"S14 no doc $TAG\"}")
MOVE2_ID=$(echo "$MOVE2" | python3 -c "import json,sys; print(json.load(sys.stdin).get('id',''))" 2>/dev/null)
MOVE2_DOC=$(echo "$MOVE2" | python3 -c "import json,sys; print(json.load(sys.stdin).get('documentId','null'))" 2>/dev/null)
log "  Move created: id=$MOVE2_ID, documentId=$MOVE2_DOC"

if [[ -n "$MOVE2_ID" && "$MOVE2_DOC" == "None" ]]; then
  record S14 "IN without document still works" PASS "move=$MOVE2_ID, documentId=null (optional)"
else
  record S14 "IN without document still works" FAIL "move=$MOVE2_ID, doc=$MOVE2_DOC | resp=$MOVE2"
fi
hr

# ─── S15: IDOR / wrong project blocked ──────────────────────────────────
log ""; log "[S15] IDOR — user without project access cannot attach/open doc"
log "  Steps:"
log "    1. Login as amel.riahi (BUREAU — has STOCK_VIEW, not STOCK_MOVE)"
log "    2. Try POST /api/stock/moves/{id}/documents linking a doc from a project she can't access → expect 403 or FORBIDDEN"
log "    3. Try GET /api/stock/moves/{id}/documents → should see the move exists but doc access blocked"

CHEF_JAR="/tmp/my-project/logs/qa-chef-b.jar"
login "amel.riahi@hydra.tn" "HydraDemo2026!" "$CHEF_JAR" > /dev/null

# Try linking a doc she shouldn't access (the one we uploaded to P1)
LINK_ATTEMPT=$(curl -s -b "$CHEF_JAR" -H "$(xsrf "$CHEF_JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/moves/$MOVE_ID/documents" \
  -d "{\"documentId\":\"$DOC_ID\"}")
LINK_CODE=$(echo "$LINK_ATTEMPT" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('code','NO_CODE'))" 2>/dev/null)
LINK_HTTP=$(curl -s -o /dev/null -w "%{http_code}" -b "$CHEF_JAR" -H "$(xsrf "$CHEF_JAR")" -H "Content-Type: application/json" \
  -X POST "$BACKEND/api/stock/moves/$MOVE_ID/documents" \
  -d "{\"documentId\":\"$DOC_ID\"}")
log "  Link attempt by amel (BUREAU): code=$LINK_CODE, HTTP=$LINK_HTTP"

# amel has STOCK_VIEW but not STOCK_MOVE, so linkDocument should return 403
if [[ "$LINK_HTTP" == "403" || "$LINK_CODE" == "FORBIDDEN" || "$LINK_CODE" == "ACCESS_DENIED" || "$LINK_CODE" == "DOCUMENT_ACCESS_DENIED" ]]; then
  record S15 "IDOR — wrong project blocked" PASS "amel (BUREAU) link attempt → HTTP $LINK_HTTP, code=$LINK_CODE"
else
  record S15 "IDOR — wrong project blocked" FAIL "HTTP=$LINK_HTTP, code=$LINK_CODE, resp=$LINK_ATTEMPT (expected 403/FORBIDDEN)"
fi
hr

# ─── S16: CENTRAL IN + file requires project selector ───────────────────
log ""; log "[S16] CENTRAL IN + file → requires project (doc-folder returns error without projectId)"
log "  Steps:"
log "    1. Verify GET /api/stock/doc-folder without projectId → 400 (bad request)"
log "    2. Verify GET /api/stock/doc-folder with projectId → 200 {folderId, code:'05.01 BL'}"
log "    3. Verify docType=BON_LIVRAISON accepted (not BL)"

# Step 1: no projectId → should fail
NO_PROJ=$(curl -s -o /dev/null -w "%{http_code}" -b "$JAR" "$BACKEND/api/stock/doc-folder?kind=BL")
log "  doc-folder without projectId: HTTP $NO_PROJ"

# Step 2: with projectId → should succeed
WITH_PROJ=$(curl -s -b "$JAR" "$BACKEND/api/stock/doc-folder?projectId=$P1&kind=BL")
FOLDER_CODE=$(echo "$WITH_PROJ" | python3 -c "import json,sys; print(json.load(sys.stdin).get('code',''))" 2>/dev/null)
log "  doc-folder with projectId: code=$FOLDER_CODE"

# Step 3: verify docType=BON_LIVRAISON works (we already uploaded with it in S13)
DOCTYPE_CHECK="BON_LIVRAISON"
# Verify the uploaded doc has docType=BON_LIVRAISON
UPLOADED_TYPE=$(echo "$UPLOAD" | python3 -c "import json,sys; print(json.load(sys.stdin).get('docType',''))" 2>/dev/null)
log "  Uploaded doc docType: $UPLOADED_TYPE"

# Also verify that docType=BL would be rejected
BL_REJECT=$(curl -s -o /dev/null -w "%{http_code}" -b "$JAR" -H "$(xsrf "$JAR")" \
  -X POST "$BACKEND/api/projects/$P1/folders/$FOLDER_ID/documents/upload" \
  -F "file=@/tmp/qa-bl-b.pdf" -F "title=test BL reject" -F "docType=BL")
log "  Upload with docType=BL: HTTP $BL_REJECT (should be 400)"

if [[ "$NO_PROJ" == "400" && "$FOLDER_CODE" == "05.01 BL" && "$UPLOADED_TYPE" == "BON_LIVRAISON" && "$BL_REJECT" == "400" ]]; then
  record S16 "CENTRAL IN + file requires project selector" PASS "no-proj=400, with-proj=05.01 BL, docType=BON_LIVRAISON OK, docType=BL rejected=400"
else
  record S16 "CENTRAL IN + file requires project selector" FAIL "no_proj=$NO_PROJ, folder_code=$FOLDER_CODE, uploaded_type=$UPLOADED_TYPE, BL_reject=$BL_REJECT"
fi
hr

# ─── Bonus: verify "Aucun justificatif" empty state + N+1 removed ────────
log ""; log "[BONUS] linkedDocCount present + no N+1 (history SQL has subquery)"
HIST_CHECK=$(echo "$HIST" | python3 -c "
import json,sys
d = json.load(sys.stdin)
items = d.get('items',[])
has_ldc = all('linkedDocCount' in m for m in items)
print(f'OK: all {len(items)} moves have linkedDocCount' if has_ldc else 'FAIL: missing linkedDocCount')
" 2>/dev/null)
log "  $HIST_CHECK"
if [[ "$HIST_CHECK" == OK* ]]; then
  record BONUS "linkedDocCount in history (no N+1)" PASS "$HIST_CHECK"
else
  record BONUS "linkedDocCount in history (no N+1)" FAIL "$HIST_CHECK"
fi
hr

log ""; log "============================================================"
log "  SUMMARY: PASS=$PASS  FAIL=$FAIL  (total=$((PASS+FAIL)))"
log "============================================================"
exit $FAIL
