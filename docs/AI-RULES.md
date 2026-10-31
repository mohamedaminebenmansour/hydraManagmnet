# Rules for every change to HYDRA
1. Make only the change asked. Do not rename, reformat or refactor anything else.
2. Never edit an existing Flyway migration in backend/src/main/resources/db/migration. Add a new file with the next number.
3. Money is numeric(18,3) in SQL and BigDecimal in Java. Never double or float.
4. Every new table has org_id uuid not null and every query filters by org_id.
5. All user-facing text is in French. Every icon has a visible text label; icon-only buttons get an aria-label.
6. No demo or fake data outside classes annotated @Profile("demo").
7. Never modify or delete a test to make it pass. If a test fails, stop and explain why.
8. Before saying done: run `cd backend && ./mvnw test` and `cd frontend && npm run build`, and paste the last 20 lines of each output.
9. Finish with a report: files changed, what was not done, open questions.
10. Always work on a branch feature/<topic> or chore/<topic>. Never push to main. Never merge.
11. Never run git stash drop, git reset --hard, git clean, git push --force, or any command that deletes uncommitted work. Commit work in progress on the branch instead.
12. Never commit files with passwords, tokens, PDFs from upload/, or sandbox scripts.
