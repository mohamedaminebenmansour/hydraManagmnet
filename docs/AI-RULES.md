# Rules for every change to HYDRA
1. Make only the change asked. Do not rename, reformat or refactor anything else.
2. Never edit an existing Flyway migration in backend/src/main/resources/db/migration. Add a new file with the next number.
3. Money is numeric(18,3) in SQL and BigDecimal in Java. Never double or float.
4. Every new table has org_id uuid not null and every query filters by org_id.
5. All user-facing text is in French. Every icon has a visible text label; icon-only buttons get an aria-label.
6. No demo or fake data outside classes annotated @Profile("demo").
7. Never modify or delete an existing test to make it pass. Adding new tests is expected. Tests must call the real code, not a copy of its logic. If a test fails, fix the code or stop and explain why.
8. Before saying done: run `cd backend && ./mvnw test` and `cd frontend && npm run build`, and paste the last 20 lines of each output.
9. Finish with a report: files changed, what was not done, open questions.
10. Always work on a branch named feature/<topic>, fix/<topic> or chore/<topic>. Push that branch only. Never push to main and never merge. Start every task with git switch main, git pull, git switch -c <type>/<topic>; before every commit run git branch --show-current and stop if it prints main; after every push run git log --oneline -1 and git log --oneline -1 origin/<branch>, paste both, and if they differ say the push failed, never "pushed".
11. Never run git stash drop, git reset --hard, git clean, git push --force, or any command that throws away uncommitted work. Commit work in progress on the branch instead.
12. Never commit passwords, tokens, .env files, PDFs, files from upload/ or download/, sandbox scripts, or tool output.
13. Read docs/MAP.md to find files. Read only the files the task names or needs.
14. One user action = one transaction. Never save related records with separate fire-and-forget calls.
15. Do not change build budgets, lint rules or test configuration unless the task asks for it.
16. When you add a migration, a new module or a file over 500 lines, update docs/MAP.md in the same commit (last migration number, file and size).
