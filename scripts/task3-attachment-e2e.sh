#!/usr/bin/env bash
# task3-attachment-e2e.sh — TASK 3 live E2E: payment attachment → Document in Finance folder.
#
# A) attach PDF to a DRAFT project movement  → 201 + documentId + documentName
# B) document appears under project documents in the Finance (06_FIN) folder
# C) attach to a POSTED movement             → 409 MOVEMENT_NOT_EDITABLE
# D) attach to a no-project movement         → 400 PROJECT_REQUIRED
# E) invalid docType                         → 400 INVALID_DOC_TYPE
# F) timeline shows DOCUMENT_ATTACHED_TO_MOVEMENT
# G) junction row cash_movement_document exists (DB check via API only)
set -u
BASE="http://127.0.0.1:8080"
COOKIES=/tmp/task3-cookies.txt
PASS=0; FAIL=0
rm -f "$COOKIES"

login() {
  curl -s -c "$COOKIES" "$BASE/api/auth/me" -o /dev/null
  CSRF=$(grep -oP 'XSRF-TOKEN\s+\K\S+' "$COOKIES" | python3 -c 'import sys,urllib.parse;print(urllib.parse.unquote(sys.stdin.read().strip()))')
  curl -s -c "$COOKIES" -b "$COOKIES" -X POST "$BASE/api/auth/login" \
    -H 'Content-Type: application/json' -H "X-XSRF-TOKEN: $CSRF" \
    -d '{"email":"owner@hydra.tn","password":"HydraOwner2026!"}' \
    -o /tmp/task3-login.json -w "%{http_code}"
}
CODE=$(login)
echo "login: $CODE"
[ "$CODE" = "200" ] || { echo "FATAL: login failed"; exit 1; }

api() { # api METHOD PATH [extra curl args...]
  local m=$1 p=$2; shift 2
  CSRF=$(grep -oP 'XSRF-TOKEN\s+\K\S+' "$COOKIES" | python3 -c 'import sys,urllib.parse;print(urllib.parse.unquote(sys.stdin.read().strip()))')
  curl -s -b "$COOKIES" -H "X-XSRF-TOKEN: $CSRF" -X "$m" "$BASE$p" "$@"
}

# a tiny PDF (valid %PDF header so extension/content checks pass)
printf '%%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 100]>>endobj\nxref\n0 4\ntrailer<</Size 4/Root 1 0 R>>\n%%%%EOF\n' > /tmp/task3-facture.pdf
[ -s /tmp/task3-facture.pdf ] && echo "pdf fixture: $(wc -c < /tmp/task3-facture.pdf) bytes"

PROJECT_ID="bbbbbb00-0000-0000-0000-000000000001"   # Adduction Aïn Draham
PARTY_ID=$(api GET /api/parties | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d[0]["id"])')
echo "party: $PARTY_ID"

echo "=== TEST A: attach to DRAFT project movement ==="
# OUT requires from_pot_id — ensure the project caisse first
POT=$(api POST "/api/cash/pots/ensure-project/$PROJECT_ID" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "project pot: $POT"
MOV=$(api POST /api/cash/movements -H 'Content-Type: application/json' -d "{
  \"movementType\":\"OUT\",\"amount\":2500.000,\"movementDate\":\"2026-10-27\",\"fromPotId\":\"$POT\",
  \"projectId\":\"$PROJECT_ID\",\"partyId\":\"$PARTY_ID\",\"label\":\"Paiement sous-traitant VRD (attach test)\"}")
MID=$(echo "$MOV" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
echo "draft movement: $MID"
ATT=$(api POST "/api/cash/movements/$MID/attachment" \
  -F "file=@/tmp/task3-facture.pdf;type=application/pdf" \
  -F "title=Facture SOTRAM situation 12" \
  -F "docType=FACTURE")
echo "$ATT" | python3 -c '
import json,sys
d=json.load(sys.stdin)
assert d.get("documentId"), "documentId missing"
assert d.get("documentName"), "documentName missing"
print("attachment OK: documentId =", d["documentId"])
print("                documentName =", d["documentName"])
print("                status =", d["status"])
' && PASS=$((PASS+1)) || { echo "TEST A FAILED: $ATT"; FAIL=$((FAIL+1)); }
DOC_ID=$(echo "$ATT" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("documentId",""))')

echo "=== TEST B: document lives in the Finance (06_FIN) folder of the project ==="
FIN=$(api GET "/api/projects/$PROJECT_ID/folders" | python3 -c '
import json,sys
fs=json.load(sys.stdin)
fin=[f for f in fs if f.get("code")=="06_FIN" or f.get("name")=="Finance"]
print(fin[0]["id"] if fin else "")')
echo "finance folder: $FIN"
DOCS=$(api GET "/api/projects/$PROJECT_ID/documents?folderId=$FIN&size=50" | python3 -c "
import json,sys
d=json.load(sys.stdin)
items=d.get('items', d.get('content', d if isinstance(d,list) else []))
hit=[x for x in items if x['id']=='$DOC_ID']
print(json.dumps(hit[0]) if hit else '')")
echo "$DOCS" | python3 -c '
import json,sys
d=json.loads(sys.stdin.read() or "null")
assert d, "attached document NOT found in Finance folder listing"
assert d["docType"]=="FACTURE", "docType=" + str(d["docType"])
assert d["folderId"], "folderId missing"
print("finance-folder doc OK:", d["reference"], "|", d["title"], "|", d["docType"])
' && PASS=$((PASS+1)) || { echo "TEST B FAILED"; FAIL=$((FAIL+1)); }

echo "=== TEST C: attach to POSTED movement → 409 ==="
POSTED_ID=$(api GET "/api/cash/movements/details?status=POSTED" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d[0]["id"] if d else "")')
RC=$(api POST "/api/cash/movements/$POSTED_ID/attachment" \
  -F "file=@/tmp/task3-facture.pdf;type=application/pdf" -F "title=X" -o /tmp/task3-c.json -w "%{http_code}")
BODY=$(cat /tmp/task3-c.json)
echo "$RC $BODY" | python3 -c '
import sys
line=sys.stdin.read().strip()
rc, body = line.split(" ", 1)
import json; b=json.loads(body)
assert rc=="409" and b.get("code")=="MOVEMENT_NOT_EDITABLE", f"got {rc} {b}"
print("posted-guard OK:", b["code"])
' && PASS=$((PASS+1)) || { echo "TEST C FAILED"; FAIL=$((FAIL+1)); }

echo "=== TEST D: attach to a no-project movement → 400 PROJECT_REQUIRED ==="
NOP=$(api POST /api/cash/movements -H 'Content-Type: application/json' -d '{
  "movementType":"IN","amount":100.000,"movementDate":"2026-10-27","label":"Caisse centrale IN (no project)"}')
# IN movement needs a toPot — find company pot first
POTS=$(api GET /api/cash/pots)
CPOT=$(echo "$POTS" | python3 -c 'import json,sys; d=json.load(sys.stdin); c=[p for p in d if p["type"]=="COMPANY"]; print(c[0]["id"] if c else "")')
NOP=$(api POST /api/cash/movements -H 'Content-Type: application/json' -d "{
  \"movementType\":\"IN\",\"amount\":100.000,\"movementDate\":\"2026-10-27\",\"toPotId\":\"$CPOT\",\"label\":\"IN sans projet (attach test)\"}")
NOPID=$(echo "$NOP" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
RC=$(api POST "/api/cash/movements/$NOPID/attachment" \
  -F "file=@/tmp/task3-facture.pdf;type=application/pdf" -F "title=Y" -o /tmp/task3-d.json -w "%{http_code}")
echo "$RC $(cat /tmp/task3-d.json)" | python3 -c '
import sys, json
line=sys.stdin.read().strip(); rc, body=line.split(" ",1); b=json.loads(body)
assert rc=="400" and b.get("code")=="PROJECT_REQUIRED", f"got {rc} {b}"
print("project-guard OK:", b["code"])
' && PASS=$((PASS+1)) || { echo "TEST D FAILED"; FAIL=$((FAIL+1)); }

echo "=== TEST E: invalid docType → 400 ==="
RC=$(api POST "/api/cash/movements/$MID/attachment" \
  -F "file=@/tmp/task3-facture.pdf;type=application/pdf" -F "title=Z" -F "docType=PLAN" -o /tmp/task3-e.json -w "%{http_code}")
echo "$RC $(cat /tmp/task3-e.json)" | python3 -c '
import sys, json
line=sys.stdin.read().strip(); rc, body=line.split(" ",1); b=json.loads(body)
assert rc=="400" and b.get("code")=="INVALID_DOC_TYPE", f"got {rc} {b}"
print("docType-guard OK:", b["code"])
' && PASS=$((PASS+1)) || { echo "TEST E FAILED"; FAIL=$((FAIL+1)); }

echo "=== TEST F: timeline contains DOCUMENT_ATTACHED_TO_MOVEMENT ==="
api GET "/api/cash/movements/$MID/timeline" | python3 -c '
import json,sys
d=json.load(sys.stdin)
actions=[e["action"] for e in d["events"]]
assert "DOCUMENT_ATTACHED_TO_MOVEMENT" in actions, f"missing; got {actions}"
assert d.get("documentId") and d.get("documentName"), "timeline snapshot missing document fields"
print("timeline OK: DOCUMENT_ATTACHED_TO_MOVEMENT present; documentId in snapshot =", d["documentId"])
' && PASS=$((PASS+1)) || { echo "TEST F FAILED"; FAIL=$((FAIL+1)); }

echo "=== RESULT: $PASS passed, $FAIL failed ==="
[ "$FAIL" = "0" ] && echo "ALL GREEN" || echo "SOME FAILURES"
