# HYDRA — instructions for Claude

HYDRA is a construction ERP for Tunisian hydraulic-works companies (BTP).
Spring Boot 4.1 / Java 21 / PostgreSQL / Flyway in `backend/`,
Angular 18 + PrimeNG in `frontend/`. Users are French-speaking office and site staff.

## Read these first, and only these
- `docs/MAP.md` — where everything is. Use it instead of exploring folders.
- `docs/AI-RULES.md` — rules every change must follow (same rules GLM follows).
- `docs/BORDEREAU-ANALYSIS.md` — only when the task touches bordereau, ouvrages or import.

Do not read other files "to understand the project". Open a file only when the
task names it, the diff touches it, or a test/compile error points to it.

## Your role
GLM 5.2 (on z.ai) writes most of the code. Claude:
1. writes task prompts for GLM (skill `write-glm-prompt`),
2. reviews GLM branches (skill `review-branch`),
3. fixes what GLM could not fix, and does architecture work.
The human merges. Claude never merges and never pushes to `main`.

## Token rules
- One task per session. Run `/clear` when switching task.
- Reviews read `git diff main...<branch>`, not whole files. Read a whole file only
  if a hunk cannot be understood alone.
- Never read: `node_modules/`, `target/`, `dist/`, `.angular/`, `upload/`,
  `download/`, `scripts/`, `tool-results/`, `backend/data/`, PDFs, images, lock files.
- Large files (`ProcessService.java` 2,200+ lines, `CashService.java` 2,000 lines):
  use Grep to find the method, then Read with offset/limit around it.
- Show command output with `| tail -40`, test output with failures only.
- Prefer CI results (GitHub Actions) over running the full test suite locally.

## Commands
- Backend tests: `cd backend && ./mvnw -q test 2>&1 | tail -40`
- One backend test class: `cd backend && ./mvnw -q test -Dtest=ClassName 2>&1 | tail -40`
- Frontend build: `cd frontend && npm run build 2>&1 | grep -E "ERROR|error TS|✘" | head -20`
- Known pre-existing failures (ignore until fixed): `TicketServiceTest` (2 NPEs).
- The owner's Windows PC may have no JDK 21, Node or PostgreSQL. Do not install
  anything without asking. If a command is missing, rely on the GitHub Actions
  result for the branch instead (`gh run list --branch <branch> --limit 1`, or ask
  the user to open the Actions tab).
- GLM works in its own z.ai sandbox and pushes branches; Claude reviews them here.

## Git
- Work on `feature/<topic>`, `fix/<topic>` or `chore/<topic>`. Push the branch only.
- Never: push to `main`, merge, force-push, `git stash drop`, `git reset --hard`, `git clean`.
- Start every task with: `git switch main`, `git pull`, `git switch -c <type>/<topic>`.
- Before every commit run: `git branch --show-current`. If it prints `main`, stop.
- After every push run: `git log --oneline -1` and `git log --oneline -1 origin/<branch>`,
  paste both. If they differ, the push failed: say so, never report "pushed".
- One-time setup for the human (enables the `.githooks/pre-commit` guard against commits on `main`):
  `git config core.hooksPath .githooks`

# Compact instructions
When compacting, keep: the task, the branch name, files changed, test results,
open problems. Drop file contents already reviewed.
