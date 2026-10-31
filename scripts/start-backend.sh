#!/bin/bash
# start-backend.sh — daemonized backend launcher (script exits, java orphaned to PID 1).
set -u
cd /home/z/my-project/backend
export JAVA_HOME=/home/z/my-project/.jdk
export PATH="$JAVA_HOME/bin:$PATH"
export PORT=8080
export DB_URL="jdbc:postgresql://127.0.0.1:5432/hydra"
export DB_USER=postgres
export DB_PASSWORD=hydra
export OWNER_EMAIL=owner@hydra.tn
export OWNER_PASSWORD=HydraOwner2026!
export HYDRA_STORAGE_TYPE=local
export HYDRA_STORAGE_ROOT=/home/z/my-project/backend/data/files
mkdir -p /home/z/my-project/logs
nohup "$JAVA_HOME/bin/java" -jar target/hydra-0.0.1-SNAPSHOT.jar \
  > /home/z/my-project/logs/backend.log 2>&1 &
disown
echo "backend launcher started (logs: /home/z/my-project/logs/backend.log)"
