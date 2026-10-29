# UI Process Templates

## Pages

### Process Builder (`/processes/builder` + `/processes/builder/:id`)
- Owner-only page (ownerGuard).
- 3-step wizard: Identity → Steps → Preview.
- Identity: category (icon/color), name, **description** (new V29), key, active.
- Steps: ordered list with add/edit/delete; each step has stepKey, name,
  assigneeRole (FINANCE/OWNER/...), requiresAttachment checkbox.
- Preview: visual graph of the workflow with role colors.

### Project Settings — "Qui valide quoi"
- Embedded in the Processus tab of the project detail page.
- Component: `ProjectRoleAssigneesComponent`.
- For each role used by enabled templates: French label + PrimeNG Select
  of candidates (project members + org owner for OWNER).
- "Enregistrer" button batches all changes into one PUT.

### Project Settings — Template enablement
- Also in the Processus tab, above "Qui valide quoi".
- Lists all active templates + enabled/disabled toggle per project.
- Toggling calls `PUT /api/projects/{projectId}/process-templates`.

## What changed in this task

- Added `description` field to the process builder identity form.
  - Frontend model: `TemplateDetail.description?`, `CreateTemplateBody.description?`,
    `UpdateTemplateBody.description?`
  - Mapper: `mapTemplateDetail` reads `description` from backend response.
  - Builder form: `TextareaModule` imported, `formGroup.description` added,
    template patching on edit mode includes description.
  - Save: both create + update send `description` in the body.
- Backend (TASK 1): V29 migration adds the column, all SELECT/INSERT/UPDATE
  queries handle it, records carry it, docs/process-engine.md documents it.

## How to test

1. Login as owner → Processus → "Créer un processus".
2. Fill: category, name "Paiement ST", description "Finance vérifie puis gérant approuve",
   key "ST_PAYMENT", active = true.
3. Add 2 steps: VERIFY (FINANCE, requiresAttachment=true), APPROVE (OWNER, false).
4. Save → template created with description.
5. Edit the template → description field should be pre-filled.
6. On a project → Processus tab → enable the template → "Qui valide quoi" →
   map FINANCE to a project member → Enregistrer.
