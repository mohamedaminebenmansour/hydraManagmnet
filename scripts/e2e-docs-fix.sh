#!/bin/bash
# e2e-docs-fix.sh — reproduce + verify the user-reported dossier/upload bugs.
# 1) GET project documents by folder (was 500 No enum constant ProjectType.ADDUCTION)
# 2) upload PDF (expect 201), HEIC photo (expect 201), disallowed .exe (expect 400 JSON)
# 3) same flow through the Angular dev-server proxy on :3000 with a foreign Host
#    header (simulates the platform preview proxy hop)
set -u
BASE=http://127.0.0.1:8080
PROJ=bbbbbb00-0000-0000-0000-000000000001
FOLDER=eeeeee00-0000-0000-0000-010100000000
J=/home/z/my-project/logs/e2e-cookies.txt
rm -f "$J"

echo "=== 1. login (owner) ==="
CSRF=$(curl -s -c "$J" "$BASE/api/auth/me" -o /dev/null && rg -i XSRF-TOKEN "$J" | awk '{print $NF}')
echo "csrf token: ${CSRF:0:12}..."
LOGIN=$(curl -s -b "$J" -c "$J" -X POST "$BASE/api/auth/login" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"email":"karim.bouzid@hydra.tn","password":"HydraDemo2026!"}')
echo "login → $(echo "$LOGIN" | head -c 160)"
CSRF=$(rg -i XSRF-TOKEN "$J" | awk '{print $NF}')

echo "=== 2. dossier search (THE 500) ==="
CODE=$(curl -s -b "$J" -o /home/z/my-project/logs/e2e-search.json -w "%{http_code}" \
  "$BASE/api/projects/$PROJ/documents?folderId=$FOLDER&page=0&size=20")
echo "GET /api/projects/$PROJ/documents?folderId=$FOLDER → HTTP $CODE"
head -c 300 /home/z/my-project/logs/e2e-search.json; echo

echo "=== 3. project GET (detail) ==="
CODE=$(curl -s -b "$J" -o /home/z/my-project/logs/e2e-project.json -w "%{http_code}" "$BASE/api/projects/$PROJ")
echo "GET /api/projects/$PROJ → HTTP $CODE"

echo "=== 4. uploads ==="
printf '%%PDF-1.4 test rapport geotech\n' > /tmp/test-rapport.pdf
printf 'test heic photo chantier' > /tmp/photo-chantier.heic
printf 'malware' > /tmp/pivot.exe

CSRF=$(rg -i XSRF-TOKEN "$J" | awk '{print $NF}')
CODE=$(curl -s -b "$J" -o /home/z/my-project/logs/e2e-up-pdf.json -w "%{http_code}" \
  -X POST "$BASE/api/projects/$PROJ/folders/$FOLDER/documents/upload" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -F "file=@/tmp/test-rapport.pdf" -F "title=Rapport géotechnique Aïn Draham" -F "docType=RAPPORT")
echo "upload PDF      → HTTP $CODE  $(head -c 200 /home/z/my-project/logs/e2e-up-pdf.json)"

CSRF=$(rg -i XSRF-TOKEN "$J" | awk '{print $NF}')
CODE=$(curl -s -b "$J" -o /home/z/my-project/logs/e2e-up-heic.json -w "%{http_code}" \
  -X POST "$BASE/api/projects/$PROJ/folders/$FOLDER/documents/upload" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -F "file=@/tmp/photo-chantier.heic" -F "title=Photo chantier iPhone" -F "docType=PHOTO")
echo "upload HEIC     → HTTP $CODE  $(head -c 200 /home/z/my-project/logs/e2e-up-heic.json)"

CSRF=$(rg -i XSRF-TOKEN "$J" | awk '{print $NF}')
CODE=$(curl -s -b "$J" -o /home/z/my-project/logs/e2e-up-exe.json -w "%{http_code}" \
  -X POST "$BASE/api/projects/$PROJ/folders/$FOLDER/documents/upload" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -F "file=@/tmp/pivot.exe" -F "title=Pièce interdite" -F "docType=AUTRE")
echo "upload EXE      → HTTP $CODE  $(cat /home/z/my-project/logs/e2e-up-exe.json)"

echo "=== 5. through dev-server :3000 (proxy hop, foreign Host header) ==="
CODE=$(curl -s -b "$J" -o /home/z/my-project/logs/e2e-proxy.json -w "%{http_code}" \
  -H "Host: preview-chat-3dd86a45-a6b2-4c50-9e9c-0043cdff70f5.space-z.ai" \
  -H "X-Forwarded-Proto: https" \
  "$BASE_PROXYPATH" 2>/dev/null || true)
CODE=$(curl -s -b "$J" -o /home/z/my-project/logs/e2e-proxy.json -w "%{http_code}" \
  -H "Host: preview-chat-3dd86a45-a6b2-4c50-9e9c-0043cdff70f5.space-z.ai" \
  -H "X-Forwarded-Proto: https" \
  "http://127.0.0.1:3000/api/projects/$PROJ/documents?folderId=$FOLDER&page=0&size=20")
echo "via :3000 proxy GET documents → HTTP $CODE"

CSRF=$(rg -i XSRF-TOKEN "$J" | awk '{print $NF}')
printf '%%PDF-1.4 proxy hop upload\n' > /tmp/test-proxy.pdf
CODE=$(curl -s -b "$J" -o /home/z/my-project/logs/e2e-proxy-up.json -w "%{http_code}" \
  -H "Host: preview-chat-3dd86a45-a6b2-4c50-9e9c-0043cdff70f5.space-z.ai" \
  -H "X-Forwarded-Proto: https" \
  -X POST "http://127.0.0.1:3000/api/projects/$PROJ/folders/$FOLDER/documents/upload" \
  -H "X-XSRF-TOKEN: $CSRF" \
  -F "file=@/tmp/test-proxy.pdf" -F "title=Test via proxy" -F "docType=ETUDE")
echo "via :3000 proxy UPLOAD → HTTP $CODE  $(head -c 200 /home/z/my-project/logs/e2e-proxy-up.json)"
echo "=== DONE ==="
