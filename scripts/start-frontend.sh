#!/bin/bash
# start-frontend.sh — static dist + /api proxy on :3000, daemonized.
set -u
cd /home/z/my-project
mkdir -p /home/z/my-project/logs
nohup /usr/bin/node /home/z/my-project/scripts/serve-dist.mjs \
  > /home/z/my-project/logs/frontend.log 2>&1 &
disown
echo "frontend launcher started (logs: /home/z/my-project/logs/frontend.log)"
