#!/bin/bash
# e2e-docs-fix2.sh — owner-flow uploads + dev-proxy multipart sanity.
set -u
BASE=http://127.0.0.1:8080
PROJ=bbbbbb00-0000-0000-0000-000000000001
FOLDER=eeeeee00-0000-0000-0000-010100000000
J=/home/z/my-project/logs/e2e-owner-cookies.txt
rm -f "$J"

echo "=== 1. owner login ==="
CSRF=$(curl -s -c "$J" "$BASE/api/auth/me" -o /dev/null && rg -i XSRF-TOKEN "$J" | awk '{print $NF}')
LOGIN=$(curl -s -b "$J" -c "$J" -X POST "$BASE/api/auth/login" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $CSRF" \
  -d '{"email":"owner@hydra.tn","password":"HydraOwner2026!"}')
echo "login → $(echo "$LOGIN" | head -c 140)"

echo "=== 2. owner uploads into 01_ADMIN folder of Adduction Aïn Draham ==="
printf '%%PDF-1.4 rapport test\n' > /tmp/test-rapport.pdf
printf 'photo heic iphone' > /tmp/photo-chantier.heic
printf 'malware' > /tmp/pivot.exe
printf '%%PDF-1.4 via dev proxy\n' > /tmp/test-proxy.pdf

up() { # url jar file title type
  local C=$(rg -i XSRF-TOKEN "$2" | awk '{print $NF}')
  curl -s -b "$2" -o "$6" -w "%{http_code}" -X POST "$1" -H "X-XSRF-TOKEN: $C" \
    -F "file=@$3" -F "title=$4" -F "docType=$5"
}
echo "PDF  → $(up "$BASE/api/projects/$PROJ/folders/$FOLDER/documents/upload" "$J" /tmp/test-rapport.pdf "Rapport géotechnique Aïn Draham" RAPPORT /home/z/my-project/logs/e2e-o-pdf.json)  $(head -c 150 /home/z/my-project/logs/e2e-o-pdf.json)"
echo "HEIC → $(up "$BASE/api/projects/$PROJ/folders/$FOLDER/documents/upload" "$J" /tmp/photo-chantier.heic "Photo chantier iPhone" PHOTO /home/z/my-project/logs/e2e-o-heic.json)  $(head -c 150 /home/z/my-project/logs/e2e-o-heic.json)"
echo "EXE  → $(up "$BASE/api/projects/$PROJ/folders/$FOLDER/documents/upload" "$J" /tmp/pivot.exe "Pièce interdite" AUTRE /home/z/my-project/logs/e2e-o-exe.json)  $(cat /home/z/my-project/logs/e2e-o-exe.json)"

echo "=== 3. dossier search again (documents list must show new docs) ==="
CODE=$(curl -s -b "$J" -o /home/z/my-project/logs/e2e-o-search.json -w "%{http_code}" \
  "$BASE/api/projects/$PROJ/documents?folderId=$FOLDER&page=0&size=20")
echo "GET documents?folderId=$FOLDER → HTTP $CODE  ($(python3 -c "import json;d=json.load(open('/home/z/my-project/logs/e2e-o-search.json'));print(d.get('total'),'docs')" 2>/dev/null))"

echo "=== 4. pure dev-proxy multipart hop :3000 → :8080 (no fake Host) ==="
P=/home/z/my-project/logs/e2e-proxy-cookies.txt; rm -f "$P"
C=$(curl -s -c "$P" "http://127.0.0.1:3000/api/auth/me" -o /dev/null && rg -i XSRF-TOKEN "$P" | awk '{print $NF}')
curl -s -b "$P" -c "$P" -X POST "http://127.0.0.1:3000/api/auth/login" \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $C" \
  -d '{"email":"owner@hydra.tn","password":"HydraOwner2026!"}' > /dev/null
C=$(rg -i XSRF-TOKEN "$P" | awk '{print $NF}')
CODE=$(curl -s -b "$P" -o /home/z/my-project/logs/e2e-p-up.json -w "%{http_code}" \
  -X POST "http://127.0.0.1:3000/api/projects/$PROJ/folders/$FOLDER/documents/upload" \
  -H "X-XSRF-TOKEN: $C" \
  -F "file=@/tmp/test-proxy.pdf" -F "title=Test via proxy dev" -F "docType=ETUDE")
echo "via :3000 UPLOAD → HTTP $CODE  $(head -c 150 /home/z/my-project/logs/e2e-p-up.json)"
CODE=$(curl -s -b "$P" -o /home/z/my-project/logs/e2e-p-search.json -w "%{http_code}" \
  "http://127.0.0.1:3000/api/projects/$PROJ/documents?folderId=$FOLDER&page=0&size=20")
echo "via :3000 SEARCH → HTTP $CODE"
echo "=== DONE ==="
