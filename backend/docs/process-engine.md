# Process Engine — Templates, Steps, Project Enablement

> Architecture: one linear workflow engine (no BPMN, no parallel branches).
> A template = ordered steps. Each step has a role (FINANCE, OWNER, etc.).
> Projects enable templates + map roles to users.

## Schema (V17 + V27 + V29)

- `process_template` (id, key, name, org_id, active, description, created_at, updated_at)
- `process_template_step` (id, template_id, step_order, step_key, name, assignee_role, requires_attachment)
- `process_instance` (id, org_id, project_id, template_id, subject_type, subject_id, status, current_step_order)
- `process_step_instance` (id, instance_id, step_order, step_key, name, status, assignee_role, assignee_user_id, requires_attachment, started_at, completed_at, completed_by, comment)
- `project_process_template` (id, project_id, template_id, enabled, default_for_subject_type)
- `project_role_assignee` (id, project_id, role_code, user_id) — V27

## Subject types

- `CASH_MOVEMENT` — fully implemented (auto-post on last step, approval desk, immutability)
- `TICKET` — TODO: ticket table exists but no process hook yet. Start with `CASH_MOVEMENT` only.

## APIs

### Template CRUD (owner only)
- `GET /api/processes/templates` — active templates for caller's org
- `GET /api/processes/templates/admin` — all templates (active + inactive)
- `GET /api/processes/templates/{id}` — full detail with steps
- `POST /api/processes/templates` — create { key, name, description }
- `PUT /api/processes/templates/{id}` — update { name?, active?, description? }
- `DELETE /api/processes/templates/{id}` — delete (org-scoped only, no RUNNING instances)

### Step management (owner only)
- `POST /api/processes/templates/{id}/steps` — add step { stepOrder, stepKey, name, assigneeRole, requiresAttachment }
- `PUT /api/processes/templates/{id}/steps/{stepId}` — update step
- `DELETE /api/processes/templates/{id}/steps/{stepId}` — delete step
- `PUT /api/processes/templates/{id}/steps/reorder` — batch reorder [{ stepId, stepOrder }]

### Project enablement
- `GET /api/projects/{projectId}/process-templates` — list templates + enabled status
- `PUT /api/projects/{projectId}/process-templates` — enable/disable template on project

### Project role assignees (V27)
- `GET /api/projects/{projectId}/process-role-assignees` — current mappings
- `PUT /api/projects/{projectId}/process-role-assignees` — upsert batch { items: [{ roleCode, userId }] }
- `DELETE /api/projects/{projectId}/process-role-assignees/{roleCode}` — drop mapping
- `GET /api/projects/{projectId}/process-roles-needed` — distinct roles from enabled templates + candidates

### Runtime
- `POST /api/processes/start` — start instance { projectId, templateKey, subjectType, subjectId }
- `POST /api/processes/{instanceId}/steps/{stepInstanceId}/complete` — complete step { comment?, documentIds? }
- `POST /api/processes/{instanceId}/steps/{stepInstanceId}/reject` — reject step { comment: required }
- `POST /api/processes/{instanceId}/cancel` — cancel instance { reason? }
- `POST /api/processes/{instanceId}/steps/{stepInstanceId}/reassign` — reassign { userId, comment? }

### Query
- `GET /api/processes/my-tasks` — pending tasks for caller
- `GET /api/processes/{instanceId}/timeline` — full step history
- `GET /api/process/steps/{stepInstanceId}/approval-desk` — single-payload dossier
- `GET /api/projects/{projectId}/processes?status=` — instances for project

## CASH_MOVEMENT hook

On last step COMPLETED: `CashService.onProcessCompletedPostMovement(subjectId, userId)`
flips the movement DRAFT → POSTED (immutable). On REJECTED: clears process_instance_id,
movement stays DRAFT (editable).

## How to test

```bash
# Login as owner
curl -c /tmp/c.txt http://localhost:8080/api/auth/me
XSRF=$(grep XSRF-TOKEN /tmp/c.txt | awk '{print $7}')
curl -b /tmp/c.txt -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"email":"owner@hydra.tn","password":"<OWNER_PASSWORD>"}'

# Create template
curl -b /tmp/c.txt -X POST http://localhost:8080/api/processes/templates \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"key":"ST_PAYMENT","name":"Paiement sous-traitant","description":"Finance valide puis gérant approuve"}'

# Add step 1 (FINANCE)
curl -b /tmp/c.txt -X POST http://localhost:8080/api/processes/templates/{id}/steps \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"stepOrder":1,"stepKey":"VERIFY","name":"Vérifier le dossier","assigneeRole":"FINANCE","requiresAttachment":true}'

# Add step 2 (OWNER)
curl -b /tmp/c.txt -X POST http://localhost:8080/api/processes/templates/{id}/steps \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"stepOrder":2,"stepKey":"APPROVE","name":"Approuver le paiement","assigneeRole":"OWNER","requiresAttachment":false}'

# Enable on project
curl -b /tmp/c.txt -X PUT http://localhost:8080/api/projects/{projectId}/process-templates \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"templateKey":"ST_PAYMENT","enabled":true}'

# Map FINANCE → user
curl -b /tmp/c.txt -X PUT http://localhost:8080/api/projects/{projectId}/process-role-assignees \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"items":[{"roleCode":"FINANCE","userId":"{userId}"}]}'

# Start on a payment
curl -b /tmp/c.txt -X POST http://localhost:8080/api/processes/start \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"projectId":"{projectId}","templateKey":"ST_PAYMENT","subjectType":"CASH_MOVEMENT","subjectId":"{movementId}"}'

# Complete step 1
curl -b /tmp/c.txt -X POST http://localhost:8080/api/processes/{instanceId}/steps/{stepId}/complete \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"comment":"OK"}'

# Complete step 2 → movement POSTED
curl -b /tmp/c.txt -X POST http://localhost:8080/api/processes/{instanceId}/steps/{stepId}/complete \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $XSRF" \
  -d '{"comment":"Approuvé"}'
```
