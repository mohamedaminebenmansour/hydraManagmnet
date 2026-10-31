#!/usr/bin/env bash
# ============================================================================
#  keep-frontend-alive.sh — watchdog for serve-dist.mjs
# ----------------------------------------------------------------------------
#  Restarts the frontend production server whenever it dies.
#  Logs to /tmp/my-project/logs/frontend-watchdog.log
#  Runs forever (until killed).
# ============================================================================
set -u

LOG="/tmp/my-project/logs/frontend-watchdog.log"
FRONTEND_DIR="/tmp/my-project/frontend"
SERVE_LOG="/tmp/my-project/logs/frontend-dist.log"

mkdir -p /tmp/my-project/logs

echo "[$(date '+%F %T')] Watchdog started" >> "$LOG"

while true; do
  # Is anything listening on port 3000?
  if ! curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:3000/ 2>/dev/null | grep -qE "^(200|301|302)$"; then
    echo "[$(date '+%F %T')] Frontend down — restarting serve-dist.mjs" >> "$LOG"
    # Kill any stale processes
    pkill -f "serve-dist.mjs" 2>/dev/null
    sleep 1
    # Restart
    cd "$FRONTEND_DIR" && nohup node serve-dist.mjs >> "$SERVE_LOG" 2>&1 &
    NEWPID=$!
    echo "[$(date '+%F %T')] Started PID $NEWPID" >> "$LOG"
    sleep 5
    # Verify
    CODE=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:3000/ 2>/dev/null)
    echo "[$(date '+%F %T')] Status after restart: HTTP $CODE" >> "$LOG"
  fi
  # Sleep 30s between checks
  sleep 10
done
