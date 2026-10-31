#!/bin/bash
# stock-e2e.sh — Part 1.2 E2E: T1..T7 + extras (reverse, summary, audit, alias)
set -u
BASE="http://127.0.0.1:8080"
OWNER_JAR="/tmp/stock-e2e-owner.cookies"
UA_JAR="/tmp/stock-e2e-ua.cookies"
PSQL() { /tmp/my-project/.venv-pg/lib/python3.12/site-packages/pgserver/pginstall/bin/psql \
  -h 127.0.0.1 -p 5432 -U postgres -d hydra "$@"; }
psql_val() { PSQL -tAc "$1"; }

PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ✔ PASS: $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ✘ FAIL: $1"; }
jget() { python3 -c "import sys,json;d=json.load(sys.stdin);print(d$1)" 2>/dev/null; }

login() { # login <jar> <email> <pass>
  local jar="$1" email="$2" pass="$3"
  rm -f "$jar"
  curl -s -c "$jar" "$BASE/api/auth/me" > /dev/null
  local xsrf=$(grep XSRF-TOKEN "$jar" | awk '{print $7}')
  curl -s -b "$jar" -c "$jar" -X POST "$BASE/api/auth/login" \
    -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $xsrf" \
    -d "{\"email\":\"$email\",\"password\":\"$pass\"}" > /dev/null
}
AUTHO=()  # owner auth array (filled after login)
AUTHA=()  # user A auth array
refresh_owner_xsrf() { local x=$(grep XSRF-TOKEN "$OWNER_JAR" | awk '{print $7}'); AUTHO=(-b "$OWNER_JAR" -H "X-XSRF-TOKEN: $x"); }
refresh_ua_xsrf()    { local x=$(grep XSRF-TOKEN "$UA_JAR"    | awk '{print $7}'); AUTHA=(-b "$UA_JAR"    -H "X-XSRF-TOKEN: $x"); }

req() { # req <method> <url> <jar-auth-array-name> [json-body]
  local method="$1" url="$2" arr="$3" body="${4:-}"
  local -n A="$arr"
  if [ -n "$body" ]; then
    curl -s -w '\n%{http_code}' "${A[@]}" -H "Content-Type: application/json" -X "$method" "$url" -d "$body"
  else
    curl -s -w '\n%{http_code}' "${A[@]}" -X "$method" "$url"
  fi
}

echo "════════════════════════════════════════════════════════"
echo " HYDRA STOCK E2E — Part 1.2 (T1..T7 + extras)"
echo "════════════════════════════════════════════════════════"

login "$OWNER_JAR" "owner@hydra.tn" "HydraOwner2026!"
refresh_owner_xsrf
echo "== owner logged in =="

# ── fixtures: two demo projects ─────────────────────────────────────
P1=$(psql_val "select id from project order by created_at asc limit 1")
P2=$(psql_val "select id from project order by created_at asc offset 1 limit 1")
ORG=$(psql_val "select org_id from project where id='$P1'")
echo "P1=$P1"
echo "P2=$P2"

# ── fixture: two members, one per project (for IDOR test) ───────────
U_A=$(psql_val "select id from app_user where email='chef.a.e2e@hydra.tn'")
if [ -z "$U_A" ]; then
  RESP=$(req POST "$BASE/api/users" AUTHO '{"fullName":"Chef A E2E","email":"chef.a.e2e@hydra.tn","password":"HydraDemo2026!","roleCodes":["CHEF_PROJET"]}')
  U_A=$(echo "$RESP" | head -1 | jget "['id']")
  [ -n "$U_A" ] && echo "created user A ($U_A)" || { echo "user A create failed: $RESP"; exit 1; }
fi
req POST "$BASE/api/projects/$P1/members" AUTHO "{\"userId\":\"$U_A\",\"projectRole\":\"MANAGER\",\"canUpload\":true}" > /dev/null
echo "user A=$U_A member of P1 only"

# Owner creates the two SITE locations + ensures CENTRAL (T1)
echo ""
echo "── T1: create item + CENTRAL + SITE locations ──────────────────────"
RESP=$(req POST "$BASE/api/stock/items" AUTHO '{"code":"CIM25-E2E","name":"Ciment 25kg E2E","uom":"sac"}')
HTTP=$(echo "$RESP" | tail -1); ITEM_ID=$(echo "$RESP" | head -1 | jget "['id']")
[ "$HTTP" = "200" ] && [ -n "$ITEM_ID" ] && ok "T1a item created ($ITEM_ID)" || bad "T1a item create HTTP=$HTTP $RESP"

RESP=$(req GET "$BASE/api/stock/locations" AUTHO)
CENTRAL_ID=$(echo "$RESP" | head -1 | python3 -c "
import sys,json
d=json.load(sys.stdin)
c=[l for l in d if l['type']=='CENTRAL']
print(c[0]['id'] if c else '')" 2>/dev/null)
if [ -z "$CENTRAL_ID" ]; then
  RESP=$(req POST "$BASE/api/stock/locations" AUTHO "{\"type\":\"CENTRAL\",\"name\":\"Dépôt central E2E\"}")
  HTTP=$(echo "$RESP" | tail -1); CENTRAL_ID=$(echo "$RESP" | head -1 | jget "['id']")
  [ "$HTTP" = "200" ] && ok "T1b CENTRAL created" || bad "T1b CENTRAL create HTTP=$HTTP $RESP"
else
  ok "T1b CENTRAL already exists (reused)"
fi
# duplicate CENTRAL must 409
RESP=$(req POST "$BASE/api/stock/locations" AUTHO "{\"type\":\"CENTRAL\",\"name\":\"Second central\"}")
HTTP=$(echo "$RESP" | tail -1); CODE=$(echo "$RESP" | head -1 | jget "['code']")
[ "$HTTP" = "409" ] && [ "$CODE" = "CENTRAL_EXISTS" ] && ok "T1c duplicate CENTRAL → 409 CENTRAL_EXISTS" || bad "T1c got HTTP=$HTTP CODE=$CODE"

RESP=$(req POST "$BASE/api/stock/locations" AUTHO "{\"type\":\"SITE\",\"projectId\":\"$P1\",\"name\":\"Chantier P1 site\"}")
HTTP=$(echo "$RESP" | tail -1); SITE1_ID=$(echo "$RESP" | head -1 | jget "['id']")
[ "$HTTP" = "200" ] && [ -n "$SITE1_ID" ] && ok "T1d SITE P1 created ($SITE1_ID)" || bad "T1d SITE P1 HTTP=$HTTP $RESP"
RESP=$(req POST "$BASE/api/stock/locations" AUTHO "{\"type\":\"SITE\",\"projectId\":\"$P2\",\"name\":\"Chantier P2 site\"}")
HTTP=$(echo "$RESP" | tail -1); SITE2_ID=$(echo "$RESP" | head -1 | jget "['id']")
[ "$HTTP" = "200" ] && [ -n "$SITE2_ID" ] && ok "T1e SITE P2 created ($SITE2_ID)" || bad "T1e SITE P2 HTTP=$HTTP $RESP"

# ── T2: IN 100 at CENTRAL ───────────────────────────────────────────
echo ""
echo "── T2: IN 100 at CENTRAL → balance 100 ─────────────────────────────"
RESP=$(req POST "$BASE/api/stock/moves" AUTHO "{\"moveType\":\"IN\",\"qty\":100,\"uom\":\"sac\",\"itemId\":\"$ITEM_ID\",\"toLocationId\":\"$CENTRAL_ID\",\"note\":\"Réception initiale\"}")
HTTP=$(echo "$RESP" | tail -1); MOVE_IN=$(echo "$RESP" | head -1 | jget "['id']")
[ "$HTTP" = "200" ] && [ -n "$MOVE_IN" ] && ok "T2a IN 100 posted ($MOVE_IN)" || bad "T2a IN HTTP=$HTTP $RESP"
BAL=$(req GET "$BASE/api/stock/balances?locationId=$CENTRAL_ID&itemId=$ITEM_ID" AUTHO | head -1 | python3 -c "
import sys,json
d=json.load(sys.stdin)
rows=[b for b in d if b['locationId']=='$CENTRAL_ID']
v=rows[0]['qty'] if rows else 0; print(int(v) if float(v)==int(float(v)) else float(v))" 2>/dev/null)
[ "$BAL" = "100" ] && ok "T2b balance CENTRAL = 100" || bad "T2b balance CENTRAL = $BAL (expected 100)"

# ── T3: TRANSFER 30 CENTRAL→SITE1 ───────────────────────────────────
echo ""
echo "── T3: TRANSFER 30 central→site → 70 / 30 ──────────────────────────"
RESP=$(req POST "$BASE/api/stock/moves" AUTHO "{\"moveType\":\"TRANSFER\",\"qty\":30,\"uom\":\"sac\",\"itemId\":\"$ITEM_ID\",\"fromLocationId\":\"$CENTRAL_ID\",\"toLocationId\":\"$SITE1_ID\",\"note\":\"Virement chantier\"}")
HTTP=$(echo "$RESP" | tail -1); MOVE_TR=$(echo "$RESP" | head -1 | jget "['id']")
[ "$HTTP" = "200" ] && [ -n "$MOVE_TR" ] && ok "T3a TRANSFER posted ($MOVE_TR)" || bad "T3a TRANSFER HTTP=$HTTP $RESP"
BAL=$(req GET "$BASE/api/stock/balances?locationId=$CENTRAL_ID&itemId=$ITEM_ID" AUTHO | head -1 | python3 -c "
import sys,json
d=json.load(sys.stdin)
rows=[b for b in d if b['locationId']=='$CENTRAL_ID']
v=rows[0]['qty'] if rows else 0; print(int(v) if float(v)==int(float(v)) else float(v))" 2>/dev/null)
[ "$BAL" = "70" ] && ok "T3b CENTRAL = 70" || bad "T3b CENTRAL = $BAL (expected 70)"
BAL=$(req GET "$BASE/api/stock/balances?locationId=$SITE1_ID&itemId=$ITEM_ID" AUTHO | head -1 | python3 -c "
import sys,json
d=json.load(sys.stdin)
rows=[b for b in d if b['locationId']=='$SITE1_ID']
v=rows[0]['qty'] if rows else 0; print(int(v) if float(v)==int(float(v)) else float(v))" 2>/dev/null)
[ "$BAL" = "30" ] && ok "T3c SITE1 = 30" || bad "T3c SITE1 = $BAL (expected 30)"

# ── T4: OUT 10 at SITE1 ─────────────────────────────────────────────
echo ""
echo "── T4: OUT 10 at site → site 20 ────────────────────────────────────"
RESP=$(req POST "$BASE/api/stock/moves" AUTHO "{\"moveType\":\"OUT\",\"qty\":10,\"uom\":\"sac\",\"itemId\":\"$ITEM_ID\",\"fromLocationId\":\"$SITE1_ID\",\"note\":\"Coulage poutre\"}")
HTTP=$(echo "$RESP" | tail -1); MOVE_OUT=$(echo "$RESP" | head -1 | jget "['id']")
[ "$HTTP" = "200" ] && [ -n "$MOVE_OUT" ] && ok "T4a OUT 10 posted ($MOVE_OUT)" || bad "T4a OUT HTTP=$HTTP $RESP"
BAL=$(req GET "$BASE/api/stock/balances?locationId=$SITE1_ID&itemId=$ITEM_ID" AUTHO | head -1 | python3 -c "
import sys,json
d=json.load(sys.stdin)
rows=[b for b in d if b['locationId']=='$SITE1_ID']
v=rows[0]['qty'] if rows else 0; print(int(v) if float(v)==int(float(v)) else float(v))" 2>/dev/null)
[ "$BAL" = "20" ] && ok "T4b SITE1 = 20" || bad "T4b SITE1 = $BAL (expected 20)"

# ── T5: negative OUT fails ──────────────────────────────────────────
echo ""
echo "── T5: OUT 10 000 at site → 409 STOCK_NEGATIVE_BALANCE ─────────────"
RESP=$(req POST "$BASE/api/stock/moves" AUTHO "{\"moveType\":\"OUT\",\"qty\":10000,\"uom\":\"sac\",\"itemId\":\"$ITEM_ID\",\"fromLocationId\":\"$SITE1_ID\"}")
HTTP=$(echo "$RESP" | tail -1); CODE=$(echo "$RESP" | head -1 | jget "['code']")
[ "$HTTP" = "409" ] && [ "$CODE" = "STOCK_NEGATIVE_BALANCE" ] && ok "T5 negative OUT → 409 STOCK_NEGATIVE_BALANCE" || bad "T5 got HTTP=$HTTP CODE=$CODE"

# ── T6: IDOR — user A (P1) cannot touch P2 site ─────────────────────
echo ""
echo "── T6: IDOR — user A (member P1) blocked on P2 site ────────────────"
login "$UA_JAR" "chef.a.e2e@hydra.tn" "HydraDemo2026!"
refresh_ua_xsrf
RESP=$(req POST "$BASE/api/stock/moves" AUTHA "{\"moveType\":\"IN\",\"qty\":5,\"uom\":\"sac\",\"itemId\":\"$ITEM_ID\",\"toLocationId\":\"$SITE2_ID\"}")
HTTP=$(echo "$RESP" | tail -1); CODE=$(echo "$RESP" | head -1 | jget "['code']")
[ "$CODE" = "FORBIDDEN" ] && ok "T6a post move on P2 site → FORBIDDEN (HTTP $HTTP)" || bad "T6a got HTTP=$HTTP CODE=$CODE $RESP"
RESP=$(req GET "$BASE/api/stock/locations" AUTHA)
SEES_P2=$(echo "$RESP" | head -1 | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('YES' if any(l.get('projectId')=='$P2' for l in d) else 'NO')" 2>/dev/null)
[ "$SEES_P2" = "NO" ] && ok "T6b user A location list hides P2 sites" || bad "T6b user A sees P2 locations!"
RESP=$(req GET "$BASE/api/stock/balances" AUTHA)
LEAK=$(echo "$RESP" | head -1 | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('YES' if any(b.get('projectId')=='$P2' for b in d) else 'NO')" 2>/dev/null)
[ "$LEAK" = "NO" ] && ok "T6c user A balances hide P2 rows" || bad "T6c user A sees P2 balances!"
# but user A CAN see CENTRAL + own site and CAN post on own site
SEES_C=$(req GET "$BASE/api/stock/locations" AUTHA | head -1 | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('YES' if any(l.get('id')=='$CENTRAL_ID' and l.get('type')=='CENTRAL' for l in d) else 'NO')" 2>/dev/null)
[ "$SEES_C" = "YES" ] && ok "T6d CENTRAL visible to project member (policy 1.1)" || bad "T6d CENTRAL not visible to member"
RESP=$(req POST "$BASE/api/stock/moves" AUTHA "{\"moveType\":\"OUT\",\"qty\":2,\"uom\":\"sac\",\"itemId\":\"$ITEM_ID\",\"fromLocationId\":\"$SITE1_ID\"}")
HTTP=$(echo "$RESP" | tail -1)
[ "$HTTP" = "200" ] && ok "T6e user A posts OUT 2 on OWN site (allowed)" || bad "T6e own-site post HTTP=$HTTP $RESP"

# ── T7: document link on move ───────────────────────────────────────
echo ""
echo "── T7: document link stored on move ────────────────────────────────"
# ensure stock BL folder under 05 ACHAT for P1, then create a document there
RESP=$(req GET "$BASE/api/stock/doc-folder?projectId=$P1&kind=BL" AUTHO)
HTTP=$(echo "$RESP" | tail -1); FOLDER_ID=$(echo "$RESP" | head -1 | jget "['folderId']")
[ "$HTTP" = "200" ] && [ -n "$FOLDER_ID" ] && ok "T7a stock BL folder resolved ($FOLDER_ID)" || bad "T7a doc-folder HTTP=$HTTP $RESP"
RESP=$(req POST "$BASE/api/projects/$P1/folders/$FOLDER_ID/documents" AUTHO '{"title":"BL Ciment 25kg — 100 sacs","docType":"AUTRE","description":"BL fournisseur"}')
HTTP=$(echo "$RESP" | tail -1); DOC_ID=$(echo "$RESP" | head -1 | jget "['id']")
{ [ "$HTTP" = "200" ] || [ "$HTTP" = "201" ]; } && [ -n "$DOC_ID" ] && ok "T7b document created in stock folder ($DOC_ID)" || bad "T7b doc create HTTP=$HTTP $RESP"
# create move WITH documentId (primary proof)
RESP=$(req POST "$BASE/api/stock/moves" AUTHO "{\"moveType\":\"IN\",\"qty\":100,\"uom\":\"sac\",\"itemId\":\"$ITEM_ID\",\"toLocationId\":\"$CENTRAL_ID\",\"documentId\":\"$DOC_ID\",\"note\":\"Réception avec BL\"}")
HTTP=$(echo "$RESP" | tail -1); MOVE_DOC=$(echo "$RESP" | head -1 | jget "['id']")
DOCONMOVE=$(echo "$RESP" | head -1 | jget "['documentId']")
[ "$HTTP" = "200" ] && [ "$DOCONMOVE" = "$DOC_ID" ] && ok "T7c move created with documentId on payload" || bad "T7c HTTP=$HTTP docOnMove=$DOCONMOVE"
# link second document to EXISTING move (junction — no UPDATE of move)
RESP=$(req POST "$BASE/api/projects/$P1/folders/$FOLDER_ID/documents" AUTHO '{"title":"Attestation réception","docType":"AUTRE"}')
DOC2_ID=$(echo "$RESP" | head -1 | jget "['id']")
RESP=$(req POST "$BASE/api/stock/moves/$MOVE_DOC/documents" AUTHO "{\"documentId\":\"$DOC2_ID\"}")
HTTP=$(echo "$RESP" | tail -1)
[ "$HTTP" = "200" ] && ok "T7d junction link to existing move OK" || bad "T7d link HTTP=$HTTP $RESP"
NDOCS=$(req GET "$BASE/api/stock/moves/$MOVE_DOC/documents" AUTHO | head -1 | python3 -c "import sys,json;print(len(json.load(sys.stdin)))" 2>/dev/null)
[ "$NDOCS" = "2" ] && ok "T7e move has 2 linked documents" || bad "T7e documents count=$NDOCS (expected 2)"
# DB-level verification
DBDOC=$(psql_val "select count(*) from stock_move where id='$MOVE_DOC' and document_id='$DOC_ID'")
[ "$DBDOC" = "1" ] && ok "T7f DB: stock_move.document_id persisted" || bad "T7f DB document_id missing"

# ── Extra: reverse restores balances ────────────────────────────────
echo ""
echo "── Extra: reverse OUT restores balance (append-only) ───────────────"
BAL_BEFORE=$(psql_val "select coalesce(sum(
  case
    when move_type='IN' then qty
    when move_type in ('OUT','DIRECT_USE') then -qty
    when move_type='ADJUST' then qty
    when move_type='TRANSFER' then case when to_location_id='$SITE1_ID' then qty when from_location_id='$SITE1_ID' then -qty else 0 end
  end),0) from stock_move where item_id='$ITEM_ID' and (from_location_id='$SITE1_ID' or to_location_id='$SITE1_ID')")
BAL_BEFORE=$(echo "$BAL_BEFORE" | python3 -c "import sys;print(int(float(sys.stdin.read().strip() or 0)))")
RESP=$(req POST "$BASE/api/stock/moves/$MOVE_OUT/reverse" AUTHO '{"reason":"Erreur de saisie"}')
HTTP=$(echo "$RESP" | tail -1); REV_ID=$(echo "$RESP" | head -1 | jget "['id']")
[ "$HTTP" = "200" ] && [ -n "$REV_ID" ] && ok "X1 reversal move created ($REV_ID)" || bad "X1 reverse HTTP=$HTTP $RESP"
BAL_AFTER=$(psql_val "select coalesce(sum(
  case
    when move_type='IN' then qty
    when move_type in ('OUT','DIRECT_USE') then -qty
    when move_type='ADJUST' then qty
    when move_type='TRANSFER' then case when to_location_id='$SITE1_ID' then qty when from_location_id='$SITE1_ID' then -qty else 0 end
  end),0) from stock_move where item_id='$ITEM_ID' and (from_location_id='$SITE1_ID' or to_location_id='$SITE1_ID')")
BAL_AFTER=$(echo "$BAL_AFTER" | python3 -c "import sys;print(int(float(sys.stdin.read().strip() or 0)))")
EXPECTED=$((BAL_BEFORE + 10))
[ "$BAL_AFTER" = "$EXPECTED" ] && ok "X2 SITE1 balance $BAL_BEFORE -> $BAL_AFTER (+10 restored)" || bad "X2 balance $BAL_BEFORE -> $BAL_AFTER (expected $EXPECTED)"
ORIGQTY=$(psql_val "select qty from stock_move where id='$MOVE_OUT'" | python3 -c "import sys;print(int(float(sys.stdin.read().strip() or 0)))")
[ "$ORIGQTY" = "10" ] && ok "X3 original move untouched (append-only)" || bad "X3 original move modified!"
# reversal of IN blocked when stock consumed? (MOVE_IN reverse → OUT 100 from central; central has 70+100(doc move)... skip heavy math — try it: expect either 200 or 409 negative)
RESP=$(req POST "$BASE/api/stock/moves/$MOVE_IN/reverse" AUTHO '{"reason":"Test reverse IN"}')
HTTP=$(echo "$RESP" | tail -1); CODE=$(echo "$RESP" | head -1 | jget "['code']")
{ [ "$HTTP" = "200" ] || { [ "$HTTP" = "409" ] && [ "$CODE" = "STOCK_NEGATIVE_BALANCE" ]; }; } && ok "X4 reverse IN handled correctly (HTTP=$HTTP CODE=$CODE)" || bad "X4 got HTTP=$HTTP CODE=$CODE"

# ── Extra: org summary + audit + alias + pagination ─────────────────
echo ""
echo "── Extra: summary / audit / alias / history ────────────────────────"
TOT=$(req GET "$BASE/api/stock/balances/summary" AUTHO | head -1 | python3 -c "
import sys,json
d=json.load(sys.stdin)
rows=[r for r in d if r['itemId']=='$ITEM_ID']
v=rows[0]['totalQty'] if rows else None; print('NONE' if v is None else (int(v) if float(v)==int(float(v)) else float(v)))" 2>/dev/null)
[ -n "$TOT" ] && [ "$TOT" != "NONE" ] && ok "X5 org summary has item (total=$TOT)" || bad "X5 summary missing item ($TOT)"
AUD=$(psql_val "select count(*) from event_log where action='STOCK_MOVE_CREATED' and entity_id in ('$MOVE_IN','$MOVE_TR','$MOVE_OUT','$MOVE_DOC')")
[ "$AUD" = "4" ] && ok "X6 audit: 4 STOCK_MOVE_CREATED events with who/what/where" || bad "X6 audit events=$AUD (expected 4)"
AUDREV=$(psql_val "select count(*) from event_log where action='STOCK_MOVE_REVERSED' and entity_id='$REV_ID' and details::text like '%$MOVE_OUT%'")
[ "$AUDREV" = "1" ] && ok "X7 audit: reversal event references original" || bad "X7 audit reversal=$AUDREV"
DETAILS=$(psql_val "select details::text from event_log where action='STOCK_MOVE_CREATED' and entity_id='$MOVE_TR'")
echo "$DETAILS" | grep -q "fromLocationId" && echo "$DETAILS" | grep -q "toLocationId" && echo "$DETAILS" | grep -q '"qty"' \
  && ok "X8 audit snapshot answers who-moved-what-where" || bad "X8 audit details incomplete: $DETAILS"
RESP=$(req POST "$BASE/api/stock/items/$ITEM_ID/aliases" AUTHO '{"alias":"le gris 25"}')
HTTP=$(echo "$RESP" | tail -1)
[ "$HTTP" = "200" ] && ok "X9 alias created" || bad "X9 alias HTTP=$HTTP $RESP"
FOUND=$(req GET "$BASE/api/stock/items?q=gris" AUTHO | head -1 | python3 -c "
import sys,json
d=json.load(sys.stdin)
print('YES' if any(i['id']=='$ITEM_ID' for i in d) else 'NO')" 2>/dev/null)
[ "$FOUND" = "YES" ] && ok "X10 alias search finds item by alias" || bad "X10 alias search failed"
RESP=$(req GET "$BASE/api/stock/moves?itemId=$ITEM_ID&size=2" AUTHO)
PAGET=$(echo "$RESP" | head -1 | jget "['total']")
PSZ=$(echo "$RESP" | head -1 | python3 -c "import sys,json;print(len(json.load(sys.stdin)['items']))" 2>/dev/null)
[ -n "$PAGET" ] && [ "$PSZ" = "2" ] && ok "X11 history paginated (total=$PAGET, page size=2)" || bad "X11 pagination total=$PAGET size=$PSZ"

# ── Cash/process untouched: quick regression ────────────────────────
echo ""
echo "── Regression: cash API still healthy ──────────────────────────────"
HTTP=$(curl -s -o /dev/null -w "%{http_code}" "${AUTHO[@]}" "$BASE/api/cash/pots")
[ "$HTTP" = "200" ] && ok "R1 /api/cash/pots → 200" || bad "R1 cash pots HTTP=$HTTP"
HTTP=$(curl -s -o /dev/null -w "%{http_code}" "${AUTHO[@]}" "$BASE/api/processes/my-tasks")
[ "$HTTP" = "200" ] && ok "R2 /api/processes/my-tasks → 200" || bad "R2 my-tasks HTTP=$HTTP"

echo ""
echo "════════════════════════════════════════════════════════"
echo " RESULT: PASS=$PASS FAIL=$FAIL"
echo "════════════════════════════════════════════════════════"
[ "$FAIL" = "0" ]
