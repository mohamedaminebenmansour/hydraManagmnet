#!/bin/bash
# bootstrap-all.sh — one-shot: PG -> build backend -> start backend -> npm install (if needed) -> start frontend
# Detached execution: survives tool-call session drops. Progress written to /home/z/my-project/logs/bootstrap-status.txt
set -u
LOG=/home/z/my-project/logs
STATUS=$LOG/bootstrap-status.txt
mkdir -p "$LOG"
: > "$STATUS"

step() { echo "[$(date -u +%H:%M:%S)] $1" >> "$STATUS"; }

step "BOOTSTRAP START"

# 1. PostgreSQL
step "PG: launching"
/home/z/my-project/scripts/start-postgres.sh
for i in $(seq 1 40); do
  if timeout 2 bash -c 'echo > /dev/tcp/127.0.0.1/5432' 2>/dev/null; then break; fi
  sleep 2
done
if timeout 2 bash -c 'echo > /dev/tcp/127.0.0.1/5432' 2>/dev/null; then step "PG: UP on 5432"; else step "PG: FAILED"; exit 1; fi

# 2. Backend build
step "MVN: package start"
cd /home/z/my-project/backend
export JAVA_HOME=/home/z/my-project/.jdk
export PATH="$JAVA_HOME/bin:$PATH"
chmod +x mvnw
if ./mvnw -B -q -DskipTests clean package > $LOG/mvn-build.log 2>&1; then
  step "MVN: BUILD SUCCESS"
else
  step "MVN: BUILD FAILED (see logs/mvn-build.log)"
  tail -40 $LOG/mvn-build.log >> $STATUS
  exit 1
fi
ls -la target/hydra-0.0.1-SNAPSHOT.jar >> $STATUS 2>&1

# 3. Backend start
step "BACKEND: starting"
/home/z/my-project/scripts/start-backend.sh
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:8080/health 2>/dev/null)
  if [ "$code" = "200" ]; then break; fi
  sleep 2
done
code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:8080/health 2>/dev/null)
if [ "$code" = "200" ]; then step "BACKEND: UP /health=200"; else step "BACKEND: FAILED (see logs/backend.log)"; tail -30 $LOG/backend.log >> $STATUS; exit 1; fi

# 4. Frontend deps + start
step "FRONTEND: npm install check"
cd /home/z/my-project/frontend
if [ ! -d node_modules ]; then
  step "FRONTEND: npm install running"
  npm install --no-audit --no-fund > $LOG/npm-install.log 2>&1
  step "FRONTEND: npm install done"
fi
step "FRONTEND: ng serve starting"
/home/z/my-project/scripts/start-frontend.sh
for i in $(seq 1 60); do
  code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:3000/ 2>/dev/null)
  if [ "$code" = "200" ]; then break; fi
  sleep 2
done
code=$(curl -s -o /dev/null -w "%{http_code}" --max-time 2 http://127.0.0.1:3000/ 2>/dev/null)
if [ "$code" = "200" ]; then step "FRONTEND: UP :3000"; else step "FRONTEND: STILL STARTING (check logs/frontend.log)"; fi

step "BOOTSTRAP DONE"
