# Process Project Assignees — "Qui valide quoi"

> STANDING RULE 11 (process): Owner configures "on this project, role FINANCE = user X,
> OWNER approver = user Y". Starting a process assigns steps to those users.

## V27 Migration

```sql
create table project_role_assignee (
    id          uuid PK,
    project_id  uuid FK→project on delete cascade,
    role_code   text,       -- FINANCE, OWNER, ACHAT, etc. (same as process_template_step.assignee_role)
    user_id     uuid FK→app_user,
    created_at  timestamptz,
    updated_at  timestamptz,
    UNIQUE (project_id, role_code)   -- v1: single user per role per project
);
```

## resolveAssigneeByRole (resolution order)

1. **project_role_assignee** table (per-project explicit mapping). The mapping user is
   re-checked: still active + still project_member (non-OWNER roles). If stale → fall through.
2. **OWNER** → org owner directly (legacy).
3. **First project_member** holding that business role via `user_role + role.code` (legacy).

## APIs

### `GET /api/projects/{projectId}/process-role-assignees`
List current role→user mappings.

### `PUT /api/projects/{projectId}/process-role-assignees`
Body: `{ items: [{ roleCode, userId }] }` — upsert batch.
- Validates: user in org; non-OWNER role must be project_member; OWNER role may be org owner.
- Auth: org owner OR project ADMIN.
- Returns the full updated mapping list.

### `DELETE /api/projects/{projectId}/process-role-assignees/{roleCode}`
Drop a single mapping.

### `GET /api/projects/{projectId}/process-roles-needed`
Composite view for the UI: distinct `assignee_role` from steps of templates enabled
on this project (or all active org/system templates when no project_process_template
rows exist). Each role carries:
- `currentAssignee` (if any mapping exists)
- `candidates` (project_members + org owner for OWNER role)

Single round-trip — candidates resolved once per call, not per role (no N+1).

## Frontend (TASK 5)

Route: embedded in the Processus tab of project detail.
Component: `ProjectRoleAssigneesComponent` (`project-role-assignees.component.ts`).

Layout: one card per role with French label + PrimeNG Select of candidates +
"Enregistrer" button (enabled only when changes exist).

French role labels: FINANCE→Finance, OWNER→Gérant, ACHAT→Achats,
CHEF_CHANTIER→Chef de chantier, etc. Unknown codes fall back to the raw code.

## How to test

```bash
# Login as owner
curl -c /tmp/c.txt http://localhost:8080/api/auth/me
XSRF=$(grep XSRF-TOKEN /tmp/c.txt | awk '{print $7}')
curl -b /tmp/c.txt -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"email":"owner@hydra.tn","password":"<OWNER_PASSWORD>"}'

# Get roles needed for project
curl -b /tmp/c.txt http://localhost:8080/api/projects/<project-id>/process-roles-needed

# Map FINANCE → Ahlem Cherif (a project member)
curl -b /tmp/c.txt -X PUT http://localhost:8080/api/projects/<project-id>/process-role-assignees \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"items":[{"roleCode":"FINANCE","userId":"<ahlem-user-id>"}]}'

# Submit a payment → step 1 should have assignee_user_id = Ahlem
curl -b /tmp/c.txt -X POST http://localhost:8080/api/cash/movements/submit-payment \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"fromPotId":"<pot>","amount":500,"movementDate":"2026-10-28","projectId":"<project>","paymentMode":"ESPECE"}'

# Verify the step was assigned to Ahlem
psql -c "select assignee_user_id from process_step_instance where instance_id='<instance>' and status='PENDING'"
```
