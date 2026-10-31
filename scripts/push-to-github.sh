#!/bin/bash
# push-to-github.sh — push committed changes to GitHub + trigger Render/Vercel deploy.
#
# Usage:
#   /tmp/my-project/scripts/push-to-github.sh                  # push pending commits
#   /tmp/my-project/scripts/push-to-github.sh "feat(x): msg"    # stage+commit ALL, then push
#
# Token resolution order (first non-empty wins):
#   1. $GH_TOKEN env var (if exported by caller)
#   2. /home/z/.config/hydra/gh-token (persisted, perms 600, never committed)
#   3. falls back to OPTION B (manual push from user's machine)
#
# The token is used ONLY to construct the push URL for this single push, then
# the remote URL is restored to its plain (no-credential) form. The token is
# never printed, never logged, never written inside the git repo.

set -e
cd /tmp/my-project

REMOTE_PLAIN="https://github.com/mohamedaminebenmansour/hydraManagmnet.git"
BRANCH="main"
TOKEN_FILE="/home/z/.config/hydra/gh-token"

# Sanity: ensure origin is configured
if ! git remote get-url origin >/dev/null 2>&1; then
  git remote add origin "$REMOTE_PLAIN"
fi

# Optional: stage + commit if a message was passed
if [ -n "${1:-}" ]; then
  git add -A
  if git diff --cached --quiet; then
    echo "[push] nothing to commit — working tree clean"
  else
    git -c user.email="z@hydra.local" -c user.name="Z (hydra dev)" commit -m "$1" -q
    echo "[push] committed: $(git log --oneline -1)"
  fi
fi

# Fetch origin to know what's ahead
git fetch --depth=1 origin "$BRANCH" 2>&1 | tail -3

# Show what we're about to push
AHEAD=$(git rev-list --count origin/main..HEAD 2>/dev/null || echo 0)
if [ "$AHEAD" = "0" ]; then
  echo "[push] nothing ahead of origin/main — already up to date"
  exit 0
fi
echo "[push] $AHEAD commit(s) ahead of origin/main:"
git log --oneline origin/main..HEAD

# Resolve the token (env first, then file)
TOKEN=""
if [ -n "${GH_TOKEN:-}" ]; then
  TOKEN="$GH_TOKEN"
elif [ -f "$TOKEN_FILE" ]; then
  TOKEN="$(head -1 "$TOKEN_FILE" | tr -d '[:space:]')"
fi

# Try the push
if [ -n "$TOKEN" ]; then
  echo ""
  echo "[push] token found — pushing to $BRANCH..."
  git remote set-url origin "https://x-access-token:${TOKEN}@github.com/mohamedaminebenmansour/hydraManagmnet.git"
  if git push origin "HEAD:$BRANCH" 2>&1 | tail -15; then
    git remote set-url origin "$REMOTE_PLAIN"
    echo ""
    echo "[push] OK — pushed to $BRANCH."
    echo "       Render backend auto-deploys: https://hydra-0gjc.onrender.com"
    echo "       Vercel frontend auto-deploys on its own webhook."
  else
    git remote set-url origin "$REMOTE_PLAIN"
    echo "[push] FAILED — see error above. Token was wiped from .git/config."
    exit 1
  fi
else
  echo ""
  echo "[push] no token available — push skipped."
  echo ""
  echo "=== OPTION A — set the token ==="
  echo "  Option A1: export GH_TOKEN=<pat> ; /tmp/my-project/scripts/push-to-github.sh"
  echo "  Option A2: save the token once to $TOKEN_FILE (perms 600):"
  echo "             install -m 600 /dev/stdin $TOKEN_FILE <<<'<pat>'"
  echo ""
  echo "=== OPTION B — push manually from your own machine ==="
  echo "  cd <your local hydraManagmnet checkout>"
  echo "  git pull --rebase origin main"
  echo "  git cherry-pick $(git rev-parse --short HEAD)"
  echo "  git push origin main"
  exit 2
fi
