import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map, catchError, of } from 'rxjs';

import {
  ProcessTimeline,
  ProcessTemplate,
  PROCESS_TEMPLATES,
  mapProcessTimeline,
  StartProcessBody,
  MyTask,
  mapMyTask,
  TemplateDetail,
  TemplateStep,
  CreateTemplateBody,
  UpdateTemplateBody,
  CreateStepBody,
  UpdateStepBody,
  ReorderStepItem,
  TestRuleRequest, TestRuleResponse,
  SaveResult,
  RoleAssignee, RoleNeeded, UpsertRoleAssigneesBody,
  mapRoleAssignee, mapRoleNeeded,
  ApprovalDesk, mapApprovalDesk,
} from '@app/core/models/process.model';

import { MessageService } from 'primeng/api';

type AnyRaw = Record<string, any>;

@Injectable({ providedIn: 'root' })
export class ProcessService {
  private http = inject(HttpClient);
  private msgs = inject(MessageService);

  // ══════════════════════════════════════════════════════════════
  //  TEMPLATE MANAGEMENT (owner only — for the Process Builder)
  // ══════════════════════════════════════════════════════════════

  /** GET /api/processes/templates — list active templates visible to caller org. */
  listTemplatesAdmin(): Observable<TemplateDetail[]> {
    return this.http.get<AnyRaw>('/api/processes/templates/admin').pipe(
      map(raw => {
        const list = Array.isArray(raw) ? raw : [];
        return list.map((r: any) => this.normalizeTemplateDetail(r));
      }),
      catchError(() => of([]))
    );
  }

  /** GET /api/processes/templates/{id} — full template detail with steps. */
  getTemplate(id: string): Observable<TemplateDetail | null> {
    return this.http.get<AnyRaw>(`/api/processes/templates/${id}`).pipe(
      map(raw => this.normalizeTemplateDetail(raw)),
      catchError(err => {
        this.msgs.add({
          severity: 'error',
          summary: 'Erreur',
          detail: err?.error?.message ?? 'Modèle introuvable',
        });
        return of(null);
      })
    );
  }

  /** POST /api/processes/templates — create a new template (owner only). */
  createTemplate(body: CreateTemplateBody): Observable<SaveResult<TemplateDetail>> {
    return this.http.post<AnyRaw>('/api/processes/templates', body).pipe(
      map(raw => ({ ok: true, detail: this.normalizeTemplateDetail(raw) })),
      catchError(err => of({ ok: false, error: this.errMsg(err, 'Impossible de créer la règle') }))
    );
  }

  /** PUT /api/processes/templates/{id} — update name / active / conditions / etc. */
  updateTemplate(id: string, body: UpdateTemplateBody): Observable<SaveResult<TemplateDetail>> {
    return this.http.put<AnyRaw>(`/api/processes/templates/${id}`, body).pipe(
      map(raw => ({ ok: true, detail: this.normalizeTemplateDetail(raw) })),
      catchError(err => of({ ok: false, error: this.errMsg(err, 'Impossible de mettre à jour la règle') }))
    );
  }

  /** POST /api/processes/templates/{id}/copy — copy a system template into an org-scoped one. */
  copyTemplate(id: string): Observable<TemplateDetail | null> {
    return this.http.post<AnyRaw>(`/api/processes/templates/${id}/copy`, {}).pipe(
      map(raw => this.normalizeTemplateDetail(raw)),
      catchError(err => {
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: this.errMsg(err, 'Impossible de copier le modèle') });
        return of(null);
      })
    );
  }

  /** GET /api/processes/templates/{id}/sentence — the rule as a French sentence. */
  getTemplateSentence(id: string): Observable<string> {
    return this.http.get(`/api/processes/templates/${id}/sentence`, { responseType: 'text' }).pipe(
      catchError(() => of(''))
    );
  }

  /** POST /api/processes/templates/{id}/test — which rule would be chosen + why (no data written). */
  testRule(id: string, body: TestRuleRequest): Observable<TestRuleResponse | null> {
    return this.http.post<AnyRaw>(`/api/processes/templates/${id}/test`, body).pipe(
      map(raw => this.normalizeTestRuleResponse(raw)),
      catchError(err => {
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: this.errMsg(err, 'Impossible de tester la règle') });
        return of(null);
      })
    );
  }

  /** PUT /api/processes/templates/{id}/steps/reorder — batch step_order update. */
  reorderSteps(templateId: string, items: ReorderStepItem[]): Observable<boolean> {
    return this.http.put<AnyRaw>(`/api/processes/templates/${templateId}/steps/reorder`, items).pipe(
      map(() => true),
      catchError(err => {
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: this.errMsg(err, 'Impossible de réordonner les étapes') });
        return of(false);
      })
    );
  }

  /** POST /api/processes/templates/{id}/steps — add a step. */
  addStep(templateId: string, body: CreateStepBody): Observable<SaveResult<TemplateDetail>> {
    return this.http.post<AnyRaw>(`/api/processes/templates/${templateId}/steps`, body).pipe(
      map(raw => ({ ok: true, detail: this.normalizeTemplateDetail(raw) })),
      catchError(err => of({ ok: false, error: this.errMsg(err, 'Impossible d\'ajouter l\'étape') }))
    );
  }

  /** PUT /api/processes/templates/{id}/steps/{stepId} — update a step. */
  updateStep(templateId: string, stepId: string, body: UpdateStepBody): Observable<SaveResult<TemplateDetail>> {
    return this.http.put<AnyRaw>(`/api/processes/templates/${templateId}/steps/${stepId}`, body).pipe(
      map(raw => ({ ok: true, detail: this.normalizeTemplateDetail(raw) })),
      catchError(err => of({ ok: false, error: this.errMsg(err, 'Impossible de modifier l\'étape') }))
    );
  }

  /** DELETE /api/processes/templates/{id}/steps/{stepId} — remove a step. */
  deleteStep(templateId: string, stepId: string): Observable<boolean> {
    return this.http.delete<void>(`/api/processes/templates/${templateId}/steps/${stepId}`).pipe(
      map(() => true),
      catchError(err => {
        this.msgs.add({
          severity: 'error',
          summary: 'Erreur',
          detail: err?.error?.message ?? 'Impossible de supprimer l\'étape',
        });
        return of(false);
      })
    );
  }

  /** Normalize any backend response shape into a TemplateDetail. */
  private normalizeTemplateDetail(raw: any): TemplateDetail {
    if (!raw || typeof raw !== 'object') {
      return { id: '', key: '', name: '', active: false, steps: [] };
    }
    const stepsRaw = Array.isArray(raw['steps']) ? raw['steps'] : [];
    const steps: TemplateStep[] = stepsRaw.map((s: any) => ({
      id: String(s?.['id'] ?? s?.['stepId'] ?? ''),
      stepOrder: Number(s?.['stepOrder'] ?? s?.['step_order'] ?? 0),
      stepKey: String(s?.['stepKey'] ?? s?.['step_key'] ?? ''),
      name: String(s?.['name'] ?? s?.['stepName'] ?? ''),
      assigneeRole: String(s?.['assigneeRole'] ?? s?.['assignee_role'] ?? ''),
      requiresAttachment: Boolean(s?.['requiresAttachment'] ?? s?.['requires_attachment'] ?? false),
      approvalMode: String(s?.['approvalMode'] ?? s?.['approval_mode'] ?? 'ANY'),
      dueDays: s?.['dueDays'] ?? s?.['due_days'] ?? null,
    }));
    return {
      id: String(raw['id'] ?? raw['templateId'] ?? ''),
      key: String(raw['key'] ?? raw['templateKey'] ?? ''),
      name: String(raw['name'] ?? raw['templateName'] ?? ''),
      active: Boolean(raw['active'] ?? true),
      description: raw['description'] ?? null,
      orgId: raw['orgId'] ?? raw['org_id'] ?? null,
      subjectType: raw['subjectType'] ?? raw['subject_type'] ?? null,
      conditions: (raw['conditions'] ?? null) ?? undefined,
      priority: raw['priority'] ?? null,
      steps: steps.sort((a, b) => a.stepOrder - b.stepOrder),
    };
  }

  /** Normalize a /test response. */
  private normalizeTestRuleResponse(raw: any): TestRuleResponse {
    const cands = Array.isArray(raw?.candidates) ? raw.candidates : [];
    return {
      chosenTemplateId: raw?.chosenTemplateId ?? null,
      chosenTemplateKey: raw?.chosenTemplateKey ?? null,
      chosenTemplateName: raw?.chosenTemplateName ?? null,
      chosenPriority: raw?.chosenPriority ?? null,
      testedTemplateId: raw?.testedTemplateId ?? null,
      testedMatched: Boolean(raw?.testedMatched),
      reason: String(raw?.reason ?? ''),
      candidates: cands.map((c: any) => ({
        templateId: String(c?.templateId ?? ''),
        key: String(c?.key ?? ''),
        name: String(c?.name ?? ''),
        priority: Number(c?.priority ?? 0),
        matched: Boolean(c?.matched),
        reason: c?.reason ?? null,
      })),
    };
  }

  private errMsg(err: any, fallback: string): string {
    return err?.error?.message ?? err?.message ?? fallback;
  }

  /** POST /api/processes/start */
  start(body: StartProcessBody): Observable<{ instanceId: string; status: string }> {
    return this.http.post<AnyRaw | string>('/api/processes/start', body).pipe(
      map((raw: AnyRaw | string) => {
        if (typeof raw === 'string') {
          return { instanceId: raw, status: 'RUNNING' };
        }
        return {
          instanceId: String(raw?.['id'] ?? raw?.['instanceId'] ?? raw?.['processId'] ?? ''),
          status: String(raw?.['status'] ?? raw?.['processStatus'] ?? 'RUNNING'),
        };
      }),
      catchError(err => {
        this.msgs.add({
          severity: 'error',
          summary: 'Erreur',
          detail: err?.error?.message ?? 'Impossible de lancer le processus',
        });
        return of({ instanceId: '', status: 'ERROR' });
      })
    );
  }

  /** GET /api/processes/my-tasks */
  myTasks(): Observable<MyTask[]> {
    return this.http.get<AnyRaw>('/api/processes/my-tasks').pipe(
      map(raw => {
        const list = Array.isArray(raw) ? raw : (raw?.['tasks'] ?? []);
        return (Array.isArray(list) ? list : []).map(mapMyTask);
      }),
      catchError(() => of([]))
    );
  }

  /** GET /api/processes/{instanceId}/timeline */
  getTimeline(instanceId: string): Observable<ProcessTimeline> {
    return this.http.get<AnyRaw>(`/api/processes/${instanceId}/timeline`).pipe(
      map(raw => mapProcessTimeline(raw)),
      catchError(err => {
        this.msgs.add({
          severity: 'error',
          summary: 'Erreur',
          detail: err?.error?.message ?? 'Impossible de charger la timeline',
        });
        return of({
          instanceId,
          status: 'RUNNING',
          steps: [],
        } as ProcessTimeline);
      })
    );
  }

  /** GET /api/projects/{projectId}/processes?status= */
  listByProject(projectId: string, status?: string): Observable<MyTask[]> {
    const url = status
      ? `/api/projects/${projectId}/processes?status=${encodeURIComponent(status)}`
      : `/api/projects/${projectId}/processes`;
    return this.http.get<AnyRaw>(url).pipe(
      map(raw => {
        const list = Array.isArray(raw) ? raw : (raw?.['processes'] ?? []);
        return (Array.isArray(list) ? list : []).map(mapMyTask);
      }),
      catchError(() => of([]))
    );
  }

  /** GET /api/processes/templates (fallback to hardcoded on 404) */
  listTemplates(): Observable<ProcessTemplate[]> {
    return this.http.get<ProcessTemplate[]>('/api/processes/templates').pipe(
      catchError(() => of(PROCESS_TEMPLATES))
    );
  }

  /** GET /api/projects/{projectId}/process-templates */
  getProjectTemplates(projectId: string): Observable<ProcessTemplate[]> {
    return this.http.get<ProcessTemplate[]>(`/api/projects/${projectId}/process-templates`).pipe(
      catchError(() => of(PROCESS_TEMPLATES))
    );
  }

  /** PUT /api/projects/{projectId}/process-templates */
  updateProjectTemplates(projectId: string, templates: ProcessTemplate[]): Observable<ProcessTemplate[]> {
    return this.http.put<ProcessTemplate[]>(`/api/projects/${projectId}/process-templates`, templates).pipe(
      catchError(err => {
        this.msgs.add({
          severity: 'error',
          summary: 'Erreur',
          detail: err?.error?.message ?? 'Impossible de mettre \u00e0 jour les mod\u00e8les',
        });
        return of(templates);
      })
    );
  }

  /** POST /api/processes/{instanceId}/steps/{stepInstanceId}/complete */
  complete(instanceId: string, stepInstanceId: string, body: { comment?: string; documentIds?: string[] } = {}): Observable<{ stepInstanceId: string; newStatus: string }> {
    return this.http
      .post<AnyRaw>(`/api/processes/${instanceId}/steps/${stepInstanceId}/complete`, body)
      .pipe(
        map(raw => ({
          stepInstanceId,
          newStatus: String(raw?.['newStatus'] ?? raw?.['status'] ?? 'DONE'),
        })),
        catchError(err => {
          this.msgs.add({
            severity: 'error',
            summary: 'Erreur',
            detail: err?.error?.message ?? 'Impossible de valider l\u2019\u00e9tape',
          });
          return of({ stepInstanceId, newStatus: 'ERROR' });
        })
      );
  }

  /** POST /api/processes/{instanceId}/steps/{stepInstanceId}/reject (comment required) */
  reject(instanceId: string, stepInstanceId: string, comment: string): Observable<{ stepInstanceId: string; newStatus: string }> {
    return this.http
      .post<AnyRaw>(`/api/processes/${instanceId}/steps/${stepInstanceId}/reject`, { comment })
      .pipe(
        map(raw => ({
          stepInstanceId,
          newStatus: String(raw?.['newStatus'] ?? raw?.['status'] ?? 'REJECTED'),
        })),
        catchError(err => {
          this.msgs.add({
            severity: 'error',
            summary: 'Erreur',
            detail: err?.error?.message ?? 'Impossible de rejeter l\u2019\u00e9tape',
          });
          return of({ stepInstanceId, newStatus: 'ERROR' });
        })
      );
  }

  /** POST /api/processes/{instanceId}/cancel */
  cancel(instanceId: string, reason?: string): Observable<{ instanceId: string }> {
    return this.http
      .post<AnyRaw>(`/api/processes/${instanceId}/cancel`, { reason })
      .pipe(
        map(raw => ({ instanceId })),
        catchError(err => {
          this.msgs.add({
            severity: 'error',
            summary: 'Erreur',
            detail: err?.error?.message ?? 'Impossible d\u2019annuler le processus',
          });
          return of({ instanceId });
        })
      );
  }

  // ══════════════════════════════════════════════════════════════
  //  V27 (TASK 1 backend) — PROJECT ROLE ASSIGNEES
  //  Per-project business role → user mapping. Powers the "Qui valide quoi"
  //  UI on the project detail page.
  // ══════════════════════════════════════════════════════════════

  /**
   * GET /api/projects/{projectId}/process-roles-needed — composite view for
   * the UI: distinct assignee_role from steps of templates enabled on this
   * project, each with current assignee (if any) + candidate users.
   * Single round-trip; no N+1.
   */
  listRolesNeeded(projectId: string): Observable<RoleNeeded[]> {
    return this.http
      .get<AnyRaw[]>(`/api/projects/${projectId}/process-roles-needed`)
      .pipe(
        map(list => (Array.isArray(list) ? list : []).map(mapRoleNeeded)),
        catchError(err => {
          this.msgs.add({
            severity: 'error',
            summary: 'Erreur',
            detail: err?.error?.message ?? 'Impossible de charger les rôles processus',
          });
          return of([]);
        })
      );
  }

  /** GET /api/projects/{projectId}/process-role-assignees — list current mappings. */
  listRoleAssignees(projectId: string): Observable<RoleAssignee[]> {
    return this.http
      .get<AnyRaw[]>(`/api/projects/${projectId}/process-role-assignees`)
      .pipe(
        map(list => (Array.isArray(list) ? list : []).map(mapRoleAssignee)),
        catchError(err => {
          this.msgs.add({
            severity: 'error',
            summary: 'Erreur',
            detail: err?.error?.message ?? 'Impossible de charger les affectations',
          });
          return of([]);
        })
      );
  }

  /**
   * PUT /api/projects/{projectId}/process-role-assignees — upsert batch.
   * Body: { items: [{ roleCode, userId }] }. Returns the full updated list.
   * Auth: org owner OR project ADMIN.
   */
  upsertRoleAssignees(projectId: string, body: UpsertRoleAssigneesBody): Observable<RoleAssignee[] | null> {
    return this.http
      .put<AnyRaw[]>(`/api/projects/${projectId}/process-role-assignees`, body)
      .pipe(
        map(list => (Array.isArray(list) ? list : []).map(mapRoleAssignee)),
        catchError(err => {
          this.msgs.add({
            severity: 'error',
            summary: 'Erreur',
            detail: err?.error?.message ?? 'Impossible d\u2019enregistrer les affectations',
          });
          return of(null);
        })
      );
  }

  /** DELETE /api/projects/{projectId}/process-role-assignees/{roleCode} — drop a single mapping. */
  deleteRoleAssignee(projectId: string, roleCode: string): Observable<boolean> {
    return this.http
      .delete(`/api/projects/${projectId}/process-role-assignees/${encodeURIComponent(roleCode)}`, {
        observe: 'response',
      })
      .pipe(
        map(resp => resp.status === 204),
        catchError(err => {
          this.msgs.add({
            severity: 'error',
            summary: 'Erreur',
            detail: err?.error?.message ?? 'Impossible de supprimer l\u2019affectation',
          });
          return of(false);
        })
      );
  }

  // ══════════════════════════════════════════════════════════════
  //  TASK 1 (backend) — APPROVAL DESK
  //  Single-payload read for an approver to decide on a CASH_MOVEMENT
  //  payment step. One round-trip, no N+1.
  // ══════════════════════════════════════════════════════════════

  /**
   * GET /api/process/steps/{stepInstanceId}/approval-desk — full dossier
   * payload: movement details (assessed/claimed/variance, paymentMode,
   * voucher, nature), linked entity names (project/party/pot/document),
   * flags (canComplete/canReject/requiresAttachment/hasAttachment), and a
   * mini-timeline of previous steps.
   */
  getApprovalDesk(stepInstanceId: string): Observable<ApprovalDesk | null> {
    return this.http
      .get<AnyRaw>(`/api/process/steps/${stepInstanceId}/approval-desk`)
      .pipe(
        map(raw => mapApprovalDesk(raw)),
        catchError(err => {
          this.msgs.add({
            severity: 'error',
            summary: 'Erreur',
            detail: err?.error?.message ?? 'Impossible de charger le dossier de validation',
          });
          return of(null);
        })
      );
  }
}
