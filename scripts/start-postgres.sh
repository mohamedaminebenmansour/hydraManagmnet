#!/bin/bash
# start-postgres.sh — idempotent launcher (daemonize pattern).
set -u
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/runtime-$(id -u)}"
mkdir -p "$XDG_RUNTIME_DIR"
cd /home/z/my-project
nohup /home/z/my-project/.venv-pg/bin/python /home/z/my-project/scripts/pg_start.py \
  > /home/z/my-project/logs/pg-start.log 2>&1 &
disown
echo "postgres launcher started (logs: /home/z/my-project/logs/pg-start.log)"
