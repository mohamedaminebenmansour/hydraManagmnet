/** Status of a single step inside a process instance. */
export type StepStatus = 'PENDING' | 'DONE' | 'REJECTED' | 'SKIPPED';

/** Status of a whole process instance. */
export type ProcessStatus = 'RUNNING' | 'COMPLETED' | 'REJECTED' | 'CANCELLED';

/** Body for POST /api/processes/start */
export interface StartProcessBody {
  projectId: string;
  templateKey: string;
  subjectType: string;
  subjectId: string;
}

/** Lightweight task card for "Mes t\u00e2ches" */
export interface MyTask {
  stepInstanceId: string;
  instanceId: string;
  projectId: string;
  projectName?: string;
  templateKey?: string;
  templateName?: string;
  subjectType?: string;
  subjectId?: string;
  stepKey?: string;
  stepName?: string;
  status: StepStatus | string;
  assigneeRole?: string;
  assigneeName?: string;
  requiresAttachment?: boolean;
  startedAt?: string;
  // Subject display info (B5): when subjectType === 'CASH_MOVEMENT' the
  // backend joins the movement's label + amount so "Mes tâches" can show
  // "Paiement — 2 500,000 TND" without extra fetches. Null for other subjects.
  subjectLabel?: string | null;
  subjectAmount?: number | null;
  // Extra fields for chantier-level progress display (added in v1.1)
  currentStepOrder?: number;
  currentStepName?: string;
  currentAssigneeName?: string;
  currentAssigneeRole?: string;
  totalSteps?: number;
  createdAt?: string;
}

/** A single step in the timeline */
export interface TimelineStep {
  stepInstanceId?: string;
  stepOrder?: number;
  stepKey?: string;
  name?: string;
  status: string;
  assigneeRole?: string;
  assigneeUserId?: string;
  assigneeName?: string;
  requiresAttachment?: boolean;
  comment?: string;
  startedAt?: string;
  completedAt?: string;
  documentIds?: string[];
}

/** Full process timeline snapshot */
export interface ProcessTimeline {
  instanceId: string;
  status: string;
  projectId?: string;
  projectName?: string;
  templateKey?: string;
  templateName?: string;
  subjectType?: string;
  subjectId?: string;
  createdAt?: string;
  createdByName?: string;
  currentStepOrder?: number;
  steps: TimelineStep[];
}

/** Process template definition used by start page and hub cards. */
export interface ProcessTemplate {
  key: string;
  name: string;
  icon: string;
}

/** Default templates \u2013 fallback when /api/processes/templates 404s. */
export const PROCESS_TEMPLATES: ProcessTemplate[] = [
  { key: 'PAYMENT_APPROVAL', name: 'Approbation de paiement', icon: 'pi-wallet' },
  { key: 'MATERIAL_PURCHASE', name: 'Achat de mat\u00e9riel', icon: 'pi-shopping-cart' },
  { key: 'SUBCONTRACT_APPROVAL', name: 'Sous-traitance', icon: 'pi-users' },
];

/** Role \u2192 icon mapping (UX system) */
export const ROLE_ICONS: Record<string, string> = {
  OWNER: 'pi-crown',
  FINANCE: 'pi-wallet',
  CHEF_PROJET: 'pi-briefcase',
  default: 'pi-user',
};

/** Status \u2192 icon + French label (UX system) */
export const STATUS_INFO: Record<string, { icon: string; label: string; colorClass: string }> = {
  PENDING:   { icon: 'pi-clock',          label: 'En attente',   colorClass: 'proc-text-pending' },
  DONE:      { icon: 'pi-check-circle',   label: 'Termin\u00e9',      colorClass: 'proc-text-done' },
  REJECTED:  { icon: 'pi-times-circle',   label: 'Refus\u00e9',       colorClass: 'proc-text-rejected' },
  RUNNING:   { icon: 'pi-play',           label: 'En cours',     colorClass: 'proc-text-running' },
  CANCELLED: { icon: 'pi-ban',            label: 'Annul\u00e9',       colorClass: 'proc-text-muted' },
  SKIPPED:   { icon: 'pi-minus-circle',   label: 'Ignor\u00e9',       colorClass: 'proc-text-muted' },
};

/** Dot color class for a step status (timeline dots). */
export function dotColorClass(status: string): string {
  const s = String(status ?? '').toUpperCase();
  switch (s) {
    case 'DONE':   return 'proc-dot-done';
    case 'PENDING': return 'proc-dot-pending';
    case 'REJECTED': return 'proc-dot-rejected';
    case 'RUNNING': return 'proc-dot-running';
    default: return 'proc-dot-muted';
  }
}

/** Mapper: defensive \u2013 accepts camelCase, snake_case, and legacy keys. */
export function mapProcessStep(raw: any): TimelineStep {
  const rawStatus = String(raw?.status ?? raw?.step_status ?? 'PENDING').toUpperCase();
  return {
    stepInstanceId: String(raw?.stepInstanceId ?? raw?.step_instance_id ?? raw?.id ?? ''),
    stepOrder: raw?.stepOrder ?? raw?.step_order ?? raw?.order ?? undefined,
    stepKey: String(raw?.stepKey ?? raw?.step_key ?? ''),
    name: String(raw?.name ?? raw?.stepName ?? raw?.step_name ?? '\u00c9tape'),
    status: rawStatus,
    assigneeRole: String(raw?.assigneeRole ?? raw?.assignee_role ?? ''),
    assigneeName: String(raw?.assigneeName ?? raw?.assignee_name ?? raw?.assigneeUserEmail ?? raw?.assignee ?? ''),
    requiresAttachment: Boolean(raw?.requiresAttachment ?? raw?.requires_attachment ?? false),
    comment: String(raw?.comment ?? raw?.comment_text ?? ''),
    startedAt: String(raw?.startedAt ?? raw?.started_at ?? ''),
    completedAt: String(raw?.completedAt ?? raw?.completed_at ?? ''),
  };
}

/** Mapper: defensive \u2013 accepts camelCase, snake_case, and legacy keys.
 *  NOTE: status is kept as the RAW string (not coerced to StepStatus) because
 *  when used to map ProcessInstanceSummary, status is the INSTANCE status
 *  (RUNNING/COMPLETED/REJECTED/CANCELLED), not the STEP status
 *  (PENDING/DONE/REJECTED/SKIPPED). The template does string equality on the
 *  raw value. */
export function mapMyTask(raw: any): MyTask {
  const rawStatus = String(raw?.status ?? raw?.instanceStatus ?? raw?.instance_status ?? 'PENDING').toUpperCase();

  return {
    stepInstanceId: String(raw?.stepInstanceId ?? raw?.step_instance_id ?? raw?.id ?? ''),
    instanceId: String(raw?.instanceId ?? raw?.instance_id ?? raw?.processId ?? ''),
    projectId: String(raw?.projectId ?? raw?.project_id ?? ''),
    projectName: String(raw?.projectName ?? raw?.project_name ?? ''),
    templateKey: String(raw?.templateKey ?? raw?.template_key ?? ''),
    templateName: String(raw?.templateName ?? raw?.template_name ?? raw?.templateKey ?? ''),
    subjectType: String(raw?.subjectType ?? raw?.subject_type ?? ''),
    subjectId: String(raw?.subjectId ?? raw?.subject_id ?? ''),
    stepKey: String(raw?.stepKey ?? raw?.step_key ?? ''),
    stepName: String(raw?.stepName ?? raw?.step_name ?? raw?.name ?? '\u00c9tape'),
    status: rawStatus,
    assigneeRole: String(raw?.assigneeRole ?? raw?.assignee_role ?? ''),
    assigneeName: String(raw?.assigneeName ?? raw?.assignee_name ?? raw?.assigneeUserEmail ?? raw?.assignee ?? ''),
    requiresAttachment: Boolean(raw?.requiresAttachment ?? raw?.requires_attachment ?? false),
    startedAt: String(raw?.startedAt ?? raw?.started_at ?? ''),
    // B5 — subject display info for CASH_MOVEMENT tasks (backend LEFT JOIN)
    subjectLabel: raw?.subjectLabel ?? raw?.subject_label ?? null,
    subjectAmount: raw?.subjectAmount ?? raw?.subject_amount ?? null,
    // v1.1 fields for chantier-level progress display
    currentStepOrder: raw?.currentStepOrder ?? raw?.current_step_order ?? undefined,
    currentStepName: String(raw?.currentStepName ?? raw?.current_step_name ?? ''),
    currentAssigneeName: String(raw?.currentAssigneeName ?? raw?.current_assignee_name ?? ''),
    currentAssigneeRole: String(raw?.currentAssigneeRole ?? raw?.current_assignee_role ?? ''),
    totalSteps: raw?.totalSteps ?? raw?.total_steps ?? undefined,
    createdAt: String(raw?.createdAt ?? raw?.created_at ?? ''),
  };
}

/** Mapper: defensive \u2013 accepts camelCase, snake_case, and legacy keys. */
export function mapProcessTimeline(raw: any): ProcessTimeline {
  const steps = Array.isArray(raw?.steps) ? raw.steps : (Array.isArray(raw) ? raw : []);
  return {
    instanceId: String(raw?.instanceId ?? raw?.instance_id ?? raw?.id ?? raw?.processId ?? ''),
    status: String(raw?.status ?? raw?.process_status ?? 'RUNNING'),
    projectId: String(raw?.projectId ?? raw?.project_id ?? ''),
    templateKey: String(raw?.templateKey ?? raw?.template_key ?? ''),
    templateName: String(raw?.templateName ?? raw?.template_name ?? ''),
    subjectType: String(raw?.subjectType ?? raw?.subject_type ?? ''),
    subjectId: String(raw?.subjectId ?? raw?.subject_id ?? ''),
    steps: steps.map(mapProcessStep),
  };
}

// ============================================================================
//  TEMPLATE BUILDER — types pour le constructeur de processus
//  (POST/PUT/DELETE /api/processes/templates[/{id}/steps...])
// ============================================================================

/** Une étape dans un template (définition, pas exécution). */
export interface TemplateStep {
  id: string;
  stepOrder: number;
  stepKey: string;
  name: string;
  assigneeRole: string;
  requiresAttachment: boolean;
}

/** Détail complet d'un template (avec ses étapes). */
export interface TemplateDetail {
  id: string;
  key: string;
  name: string;
  active: boolean;
  steps: TemplateStep[];
}

/** Body POST /api/processes/templates. */
export interface CreateTemplateBody {
  key: string;
  name: string;
}

/** Body PUT /api/processes/templates/{id}. */
export interface UpdateTemplateBody {
  name: string;
  active: boolean;
}

/** Body POST /api/processes/templates/{id}/steps. */
export interface CreateStepBody {
  stepOrder: number;
  stepKey: string;
  name: string;
  assigneeRole: string;
  requiresAttachment: boolean;
}

/** Body PUT /api/processes/templates/{id}/steps/{stepId}.
 *  Tous les champs sont optionnels côté backend ; le front envoie tout. */
export interface UpdateStepBody {
  name: string;
  assigneeRole: string;
  requiresAttachment: boolean;
  stepOrder: number;
}

/** Mapper: convertit n'importe quelle réponse backend en TemplateDetail. */
export function mapTemplateDetail(raw: any): TemplateDetail {
  if (!raw || typeof raw !== 'object') {
    return { id: '', key: '', name: '', active: false, steps: [] };
  }
  const stepsRaw = Array.isArray(raw?.steps) ? raw.steps : [];
  const steps: TemplateStep[] = stepsRaw.map((s: any) => ({
    id: String(s?.id ?? s?.stepId ?? ''),
    stepOrder: Number(s?.stepOrder ?? s?.step_order ?? 0),
    stepKey: String(s?.stepKey ?? s?.step_key ?? ''),
    name: String(s?.name ?? s?.stepName ?? ''),
    assigneeRole: String(s?.assigneeRole ?? s?.assignee_role ?? ''),
    requiresAttachment: Boolean(s?.requiresAttachment ?? s?.requires_attachment ?? false),
  }));
  return {
    id: String(raw?.id ?? raw?.templateId ?? ''),
    key: String(raw?.key ?? raw?.templateKey ?? ''),
    name: String(raw?.name ?? raw?.templateName ?? ''),
    active: Boolean(raw?.active ?? true),
    steps: steps.sort((a, b) => a.stepOrder - b.stepOrder),
  };
}


