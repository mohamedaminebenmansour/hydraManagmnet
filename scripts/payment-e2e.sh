#!/bin/bash
# payment-e2e.sh — TASK 1 live E2E: claimed vs assessed + variance rule,
# complete→posts once, reject→never posts, legacy amount-only back-compat.
set -u
BASE="http://127.0.0.1:8080"
JAR="/tmp/payment-e2e.cookies"
PSQL() { /home/z/my-project/.venv-pg/lib/python3.12/site-packages/pgserver/pginstall/bin/psql \
  -h 127.0.0.1 -p 5432 -U postgres -d hydra "$@"; }
psql_val() { PSQL -tAc "$1"; }

rm -f "$JAR"
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "  ✔ $1"; }
bad()  { FAIL=$((FAIL+1)); echo "  ✘ $1"; }

# ── CSRF + login (owner) ────────────────────────────────────────────
curl -s -c "$JAR" "$BASE/api/auth/me" > /dev/null
XSRF=$(grep XSRF-TOKEN "$JAR" | awk '{print $7}')
curl -s -b "$JAR" -c "$JAR" -X POST "$BASE/api/auth/login" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"email":"owner@hydra.tn","password":"HydraOwner2026!"}' > /dev/null
XSRF=$(grep XSRF-TOKEN "$JAR" | awk '{print $7}')
AUTH=(-b "$JAR" -H "X-XSRF-TOKEN: $XSRF")
echo "== logged in as owner =="

# ── fixture: project pot + party ────────────────────────────────────
PROJECT_ID=$(psql_val "select id from project order by created_at limit 1")
POT_ID=$(psql_val "select id from cash_pot where project_id='$PROJECT_ID' and type='PROJECT' and active limit 1")
PARTY_ID=$(psql_val "select id from party where org_id=(select org_id from project where id='$PROJECT_ID') order by created_at limit 1")
echo "project=$PROJECT_ID pot=$POT_ID party=$PARTY_ID"
[ -n "$POT_ID" ] && [ "$POT_ID" != "" ] || { echo "NO POT — abort"; exit 1; }

post() { curl -s -w '\n%{http_code}' "${AUTH[@]}" -H "Content-Type: application/json" -X POST "$@"; }

# ── top-up IN 50 000 → post (IN needs no process) ───────────────────
RESP=$(post "$BASE/api/cash/movements" -d "{\"movementType\":\"IN\",\"amount\":50000,\"movementDate\":\"2026-10-27\",\"toPotId\":\"$POT_ID\",\"label\":\"Top-up E2E TASK1\"}")
IN_ID=$(echo "$RESP" | head -1 | python3 -c 'import sys,json;print(json.load(sys.stdin)["id"])')
HTTP=$(echo "$RESP" | tail -1)
[ "$HTTP" = "200" ] || [ "$HTTP" = "201" ] && ok "top-up IN created ($HTTP)" || bad "top-up IN ($HTTP)"
RESP=$(post "$BASE/api/cash/movements/$IN_ID/post" -d '{}')
[ "$(echo "$RESP" | tail -1)" = "200" ] && ok "top-up posted" || bad "top-up post: $RESP"

BAL_BEFORE=$(curl -s -b "$JAR" "${AUTH[@]}" "$BASE/api/cash/pots/$POT_ID/balance" | python3 -c 'import sys,json;print(json.load(sys.stdin)["balance"])')
echo "pot balance before OUT tests: $BAL_BEFORE"

echo "== TEST A: variance without reason → 400 VARIANCE_REASON_REQUIRED =="
RESP=$(post "$BASE/api/cash/movements/submit-payment" -d "{\"fromPotId\":\"$POT_ID\",\"amount\":8000,\"movementDate\":\"2026-10-27\",\"projectId\":\"$PROJECT_ID\",\"partyId\":\"$PARTY_ID\",\"label\":\"Test A\",\"claimedAmount\":10000,\"assessedAmount\":8000}")
CODE=$(echo "$RESP" | head -1 | python3 -c 'import sys,json;print(json.load(sys.stdin).get("code",""))' 2>/dev/null)
HTTP=$(echo "$RESP" | tail -1)
[ "$HTTP" = "400" ] && [ "$CODE" = "VARIANCE_REASON_REQUIRED" ] && ok "400 VARIANCE_REASON_REQUIRED" || bad "got $HTTP $CODE"

echo "== TEST B: assessed <= 0 → 400 with clear JSON (effective amount check) =="
RESP=$(post "$BASE/api/cash/movements/submit-payment" -d "{\"fromPotId\":\"$POT_ID\",\"amount\":0,\"movementDate\":\"2026-10-27\",\"projectId\":\"$PROJECT_ID\",\"partyId\":\"$PARTY_ID\",\"label\":\"Test B\",\"claimedAmount\":5000,\"assessedAmount\":0}")
CODE=$(echo "$RESP" | head -1 | python3 -c 'import sys,json;print(json.load(sys.stdin).get("code",""))' 2>/dev/null)
HTTP=$(echo "$RESP" | tail -1)
# assessed=0 → effectiveAmount=0 → the generic amount>0 guard fires first with
# INVALID_AMOUNT; both codes are valid 400 rejections with a French message.
[ "$HTTP" = "400" ] && { [ "$CODE" = "INVALID_AMOUNT" ] || [ "$CODE" = "INVALID_ASSESSED_AMOUNT" ]; } && ok "400 $CODE (assessed<=0 refused)" || bad "got $HTTP $CODE"

echo "== TEST C: legacy amount-only submit → REJECT path never posts =="
RESP=$(post "$BASE/api/cash/movements/submit-payment" -d "{\"fromPotId\":\"$POT_ID\",\"amount\":777.250,\"movementDate\":\"2026-10-27\",\"projectId\":\"$PROJECT_ID\",\"partyId\":\"$PARTY_ID\",\"label\":\"Test C legacy shape\"}")
HTTP=$(echo "$RESP" | tail -1); BODY=$(echo "$RESP" | head -1)
MV_C=$(echo "$BODY" | python3 -c 'import sys,json;d=json.load(sys.stdin);print(d["movement"]["id"]);import os' 2>/dev/null)
PROC_C=$(echo "$BODY" | python3 -c 'import sys,json;print(json.load(sys.stdin)["processInstanceId"])' 2>/dev/null)
[ "$HTTP" = "200" ] || [ "$HTTP" = "201" ] && ok "legacy submit OK mv=$MV_C proc=$PROC_C" || { bad "legacy submit $HTTP: $BODY"; }
CLAIMED_C=$(echo "$BODY" | python3 -c 'import sys,json;print(json.load(sys.stdin)["movement"].get("claimedAmount"))' 2>/dev/null)
[ "$CLAIMED_C" = "None" ] && ok "legacy submit leaves claimed/assessed NULL" || bad "legacy claimed=$CLAIMED_C"

STEP_C=$(psql_val "select id from process_step_instance where instance_id='$PROC_C' and status='PENDING' order by step_order limit 1")
RESP=$(post "$BASE/api/processes/$PROC_C/steps/$STEP_C/reject" -d '{"comment":"Montant non justifié"}')
[ "$(echo "$RESP" | tail -1)" = "200" ] && ok "step rejected" || bad "reject: $RESP"
MV_STATUS=$(psql_val "select status from cash_movement where id='$MV_C'")
PROC_STATUS=$(psql_val "select status from process_instance where id='$PROC_C'")
PROC_LINK=$(psql_val "select process_instance_id is null from cash_movement where id='$MV_C'")
POSTED_C=$(psql_val "select count(*) from event_log where entity_id='$MV_C' and action='CASH_MOVEMENT_POSTED'")
[ "$MV_STATUS" = "DRAFT" ] && ok "movement stays DRAFT after reject" || bad "movement=$MV_STATUS"
[ "$PROC_STATUS" = "REJECTED" ] && ok "process REJECTED" || bad "process=$PROC_STATUS"
[ "$PROC_LINK" = "t" ] && ok "process_instance_id cleared (editable DRAFT)" || bad "link still set"
[ "$POSTED_C" = "0" ] && ok "NO CASH_MOVEMENT_POSTED after reject" || bad "posted events=$POSTED_C"

echo "== TEST D: claimed 12500.500 vs assessed 11000 + reason → complete posts ONCE =="
RESP=$(post "$BASE/api/cash/movements/submit-payment" -d "{\"fromPotId\":\"$POT_ID\",\"amount\":11000,\"movementDate\":\"2026-10-27\",\"projectId\":\"$PROJECT_ID\",\"partyId\":\"$PARTY_ID\",\"label\":\"Acompte situation 3 - STE BTP\",\"claimedAmount\":12500.500,\"assessedAmount\":11000,\"varianceReason\":\"Travaux non conformes déduits (attachement 2)\"}")
HTTP=$(echo "$RESP" | tail -1); BODY=$(echo "$RESP" | head -1)
MV_D=$(echo "$BODY" | python3 -c 'import sys,json;print(json.load(sys.stdin)["movement"]["id"])' 2>/dev/null)
PROC_D=$(echo "$BODY" | python3 -c 'import sys,json;print(json.load(sys.stdin)["processInstanceId"])' 2>/dev/null)
[ "$HTTP" = "200" ] || [ "$HTTP" = "201" ] && ok "submit OK mv=$MV_D" || { bad "submit $HTTP: $BODY"; exit 1; }
ROW=$(psql_val "select status||'|'||amount||'|'||claimed_amount||'|'||assessed_amount||'|'||coalesce(variance_reason,'-') from cash_movement where id='$MV_D'")
echo "  row: $ROW"
echo "$ROW" | grep -qF 'DRAFT|11000.000|12500.500|11000.000|Travaux non conformes' && ok "DRAFT amount=11000.000 (=assessed), claimed=12500.500, reason stored" || bad "row: $ROW"

BAL_PRE_POST=$(curl -s -b "$JAR" "${AUTH[@]}" "$BASE/api/cash/pots/$POT_ID/balance" | python3 -c 'import sys,json;print(json.load(sys.stdin)["balance"])')
N=0
while true; do
  STEP_D=$(psql_val "select id from process_step_instance where instance_id='$PROC_D' and status='PENDING' order by step_order limit 1")
  [ -z "$STEP_D" ] && break
  N=$((N+1))
  RESP=$(post "$BASE/api/processes/$PROC_D/steps/$STEP_D/complete" -d '{"comment":"OK gérant"}' )
  [ "$(echo "$RESP" | tail -1)" = "200" ] || { bad "step $N complete: $RESP"; break; }
done
ok "walked $N sequential steps to COMPLETED"
PROC_D_STATUS=$(psql_val "select status from process_instance where id='$PROC_D'")
MV_D_STATUS=$(psql_val "select status from cash_movement where id='$MV_D'")
[ "$PROC_D_STATUS" = "COMPLETED" ] && ok "process COMPLETED" || bad "process=$PROC_D_STATUS"
[ "$MV_D_STATUS" = "POSTED" ] && ok "movement POSTED (auto)" || bad "movement=$MV_D_STATUS"
POSTED_D=$(psql_val "select count(*) from event_log where entity_id='$MV_D' and action='CASH_MOVEMENT_POSTED'")
[ "$POSTED_D" = "1" ] && ok "exactly ONE CASH_MOVEMENT_POSTED (posts once)" || bad "posted events=$POSTED_D"
BAL_POST_POST=$(curl -s -b "$JAR" "${AUTH[@]}" "$BASE/api/cash/pots/$POT_ID/balance" | python3 -c 'import sys,json;print(json.load(sys.stdin)["balance"])')
DIFF=$(python3 -c "print(f'{float('$BAL_PRE_POST')-float('$BAL_POST_POST'):.3f}')")
[ "$DIFF" = "11000.000" ] && ok "pot debited by exactly the ASSESSED 11000.000" || bad "balance diff=$DIFF"

echo "== detail + timeline expose claimed/assessed =="
DETAIL=$(curl -s "${AUTH[@]}" "$BASE/api/cash/movements/$MV_D/detail")
echo "$DETAIL" | python3 -c '
import sys,json
d=json.load(sys.stdin)
assert d.get("claimedAmount")==12500.500, d.get("claimedAmount")
assert d.get("assessedAmount")==11000.0, d.get("assessedAmount")
assert d.get("varianceReason")=="Travaux non conformes déduits (attachement 2)"
print("  ✔ detail JSON: claimedAmount/assessedAmount/varianceReason correct")' && ok "detail JSON" || bad "detail JSON"
TL=$(curl -s "${AUTH[@]}" "$BASE/api/cash/movements/$MV_D/timeline")
echo "$TL" | python3 -c '
import sys,json
d=json.load(sys.stdin)
assert d.get("claimedAmount")==12500.500
acts=[e["action"] for e in d["events"]]
for a in ["CASH_MOVEMENT_CREATED","CASH_MOVEMENT_SUBMITTED","CASH_MOVEMENT_POSTED"]:
    assert a in acts, (a, acts)
print("  ✔ timeline: claimed shown, actions =", acts)' && ok "timeline JSON" || bad "timeline JSON"

echo "== DB safety net: chk_cm_variance_reason blocks silent edit =="
ERR=$(PSQL -c "begin; update cash_movement set variance_reason=null where id='$MV_D'; rollback;" 2>&1 | grep -o "chk_cm_variance_reason" | head -1)
[ -n "$ERR" ] && ok "DB constraint fired: $ERR" || bad "constraint did not fire"

echo ""
echo "== RESULT: $PASS passed, $FAIL failed =="
exit $([ $FAIL -eq 0 ] && echo 0 || echo 1)
