#!/bin/bash
# ticket-e2e.sh — TASK C1 live test: create → assign → close, JSON errors, no cash side effects.
set -u
BASE="http://127.0.0.1:8080"
JAR="/tmp/ticket-e2e.cookies"
rm -f "$JAR"

# ── helpers ─────────────────────────────────────────────────────────
function psql_val() {
  /home/z/my-project/.venv-pg/lib/python3.12/site-packages/pgserver/pginstall/bin/psql \
    -h 127.0.0.1 -p 5432 -U postgres -d hydra -tAc "$1"
}
function psql_count() { psql_val "$1"; }


# ── helpers (must be defined before use) ───────────────────────────
function psql_val() {
  /home/z/my-project/.venv-pg/lib/python3.12/site-packages/pgserver/pginstall/bin/psql \
    -h 127.0.0.1 -p 5432 -U postgres -d hydra -tAc "$1"
}
function psql_count() { psql_val "$1"; }

# ── CSRF + login (owner) ────────────────────────────────────────────
curl -s -c "$JAR" "$BASE/api/auth/me" > /dev/null
XSRF=$(grep XSRF-TOKEN "$JAR" | awk '{print $7}')
LOGIN=$(curl -s -b "$JAR" -c "$JAR" -X POST "$BASE/api/auth/login" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"email":"owner@hydra.tn","password":"HydraOwner2026!"}')
echo "LOGIN: $(echo "$LOGIN" | head -c 120)"

# refresh CSRF after login (session cookie changes)
XSRF=$(grep XSRF-TOKEN "$JAR" | awk '{print $7}')

CASH_BEFORE=$(curl -s -b "$JAR" "$BASE/api/cash/movements?size=1" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("totalElements", json.load(sys.stdin)))' 2>/dev/null || echo "?")
CASH_COUNT_BEFORE=$(psql_count "select count(*) from cash_movement")
echo "CASH_MOVEMENTS BEFORE: $CASH_COUNT_BEFORE"

# ── 1. LIST (baseline sanity) ──────────────────────────────────────
LIST_HTTP=$(curl -s -o /dev/null -w '%{http_code}' -b "$JAR" "$BASE/api/tickets")
echo "1. GET /api/tickets → $LIST_HTTP"

# ── 2. CREATE ───────────────────────────────────────────────────────
PROJECT_ID=$(psql_val "select id from project where org_id = (select id from organization limit 1) order by created_at limit 1")
ASSIGNEE_ID=$(psql_val "select id from app_user where email='amel.riahi@hydra.tn'")
CREATED=$(curl -s -b "$JAR" -X POST "$BASE/api/tickets" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d "{\"title\":\"Fuite regard VRD\",\"description\":\"Fuite au niveau du regard 12\",\"projectId\":\"$PROJECT_ID\",\"assignedToId\":\"$ASSIGNEE_ID\",\"priority\":\"HIGH\"}")
TICKET_ID=$(echo "$CREATED" | python3 -c 'import sys,json; print(json.load(sys.stdin)["id"])')
echo "2. CREATE → id=$TICKET_ID status=$(echo "$CREATED" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["status"], d["priority"], d["assignedToName"], d["processInstanceId"])')"

# ── 3. GET one ──────────────────────────────────────────────────────
GOT=$(curl -s -b "$JAR" "$BASE/api/tickets/$TICKET_ID")
echo "3. GET → title=$(echo "$GOT" | python3 -c 'import sys,json; print(json.load(sys.stdin)["title"])') processInstanceId=$(echo "$GOT" | python3 -c 'import sys,json; print(json.load(sys.stdin)["processInstanceId"])')"

# ── 4. ASSIGN (PUT partial update: change assignee) ─────────────────
NEW_ASSIGNEE=$(psql_val "select id from app_user where email='safa.mtibaa@hydra.tn'")
ASSIGNED=$(curl -s -b "$JAR" -X PUT "$BASE/api/tickets/$TICKET_ID" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d "{\"assignedToId\":\"$NEW_ASSIGNEE\"}")
echo "4. ASSIGN → assignedToName=$(echo "$ASSIGNED" | python3 -c 'import sys,json; print(json.load(sys.stdin)["assignedToName"])')"

# ── 5. CLOSE ────────────────────────────────────────────────────────
CLOSED=$(curl -s -b "$JAR" -X POST "$BASE/api/tickets/$TICKET_ID/close" -H "X-XSRF-TOKEN: $XSRF")
echo "5. CLOSE → status=$(echo "$CLOSED" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d["status"], "resolvedAt=" + str(d["resolvedAt"]), "resolvedById=" + str(d["resolvedById"]))')"

# ── 6. CLOSE again → 409 JSON ───────────────────────────────────────
RECLOSE_HTTP=$(curl -s -o /tmp/reclose.json -w '%{http_code}' -b "$JAR" -X POST "$BASE/api/tickets/$TICKET_ID/close" -H "X-XSRF-TOKEN: $XSRF")
echo "6. CLOSE again → HTTP $RECLOSE_HTTP body=$(cat /tmp/reclose.json)"

# ── 7. GET unknown id → 404 JSON ────────────────────────────────────
NOTFOUND_HTTP=$(curl -s -o /tmp/notfound.json -w '%{http_code}' -b "$JAR" "$BASE/api/tickets/00000000-0000-0000-0000-000000000000")
echo "7. GET unknown → HTTP $NOTFOUND_HTTP body=$(cat /tmp/notfound.json)"

# ── 8. No cash side effects ─────────────────────────────────────────
CASH_COUNT_AFTER=$(psql_count "select count(*) from cash_movement")
PROC_BEFORE_AFTER=$(psql_count "select count(*) from process_instance")
echo "8. CASH_MOVEMENTS AFTER: $CASH_COUNT_AFTER (before: $CASH_COUNT_BEFORE) — process_instance total: $PROC_BEFORE_AFTER"

# ── 9. Audit trail ──────────────────────────────────────────────────
AUDIT=$(psql_val "select action from event_log where entity_type='ticket' and entity_id='$TICKET_ID' order by created_at")
echo "9. AUDIT EVENTS: $(echo $AUDIT)"

# ── 10. DB column check ─────────────────────────────────────────────
COL=$(psql_val "select column_name from information_schema.columns where table_name='ticket' and column_name='process_instance_id'")
echo "10. ticket.process_instance_id column: $COL"

