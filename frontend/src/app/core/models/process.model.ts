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
  // Rule-builder (V38):
  approvalMode?: 'ANY' | 'ALL' | string;
  dueDays?: number | null;
}

/** Détail complet d'un template (avec ses étapes). */
export interface TemplateDetail {
  id: string;
  key: string;
  name: string;
  active: boolean;
  description?: string | null;
  orgId?: string | null;
  // Rule-builder (V38):
  subjectType?: string | null;
  conditions?: Record<string, any>;
  priority?: number | null;
  steps: TemplateStep[];
}

/** Body POST /api/processes/templates. */
export interface CreateTemplateBody {
  key: string;
  name: string;
  description?: string;
  subjectType: string;
  conditions?: Record<string, any>;
  priority?: number;
}

/** Body PUT /api/processes/templates/{id}. Tous les champs sont optionnels. */
export interface UpdateTemplateBody {
  name?: string;
  active?: boolean;
  description?: string;
  subjectType?: string;
  conditions?: Record<string, any>;
  priority?: number;
}

/** Body POST /api/processes/templates/{id}/steps. */
export interface CreateStepBody {
  stepOrder: number;
  stepKey: string;
  name: string;
  assigneeRole: string;
  requiresAttachment: boolean;
  approvalMode?: 'ANY' | 'ALL' | string;
  dueDays?: number | null;
}

/** Body PUT /api/processes/templates/{id}/steps/{stepId}. */
export interface UpdateStepBody {
  name?: string;
  assigneeRole?: string;
  requiresAttachment?: boolean;
  stepOrder?: number;
  approvalMode?: 'ANY' | 'ALL' | string;
  dueDays?: number | null;
}

/** Item for PUT /api/processes/templates/{id}/steps/reorder. */
export interface ReorderStepItem {
  stepId: string;
  stepOrder: number;
}

/** Body POST /api/processes/templates/{id}/test. */
export interface TestRuleRequest {
  projectId?: string | null;
  amount: number;
  nature?: string | null;
  paymentMode?: string | null;
}

/** One candidate in a /test response. */
export interface CandidateResult {
  templateId: string;
  key: string;
  name: string;
  priority: number;
  matched: boolean;
  reason: string | null;
}

/** Response POST /api/processes/templates/{id}/test. */
export interface TestRuleResponse {
  chosenTemplateId: string | null;
  chosenTemplateKey: string | null;
  chosenTemplateName: string | null;
  chosenPriority: number | null;
  testedTemplateId: string | null;
  testedMatched: boolean;
  reason: string;
  candidates: CandidateResult[];
}

/** Generic save result that surfaces the backend error code + message (no toast). */
export interface SaveResult<T> {
  ok: boolean;
  detail?: T;
  error?: string;
  code?: string;
}

/** Mapper: convertit n'importe quelle réponse backend en TemplateDetail. */
export function mapTemplateDetail(raw: any): TemplateDetail {
  if (!raw || typeof raw !== 'object') {
    return { id: '', key: '', name: '', active: false, description: null, steps: [] };
  }
  const stepsRaw = Array.isArray(raw?.steps) ? raw.steps : [];
  const steps: TemplateStep[] = stepsRaw.map((s: any) => ({
    id: String(s?.id ?? s?.stepId ?? ''),
    stepOrder: Number(s?.stepOrder ?? s?.step_order ?? 0),
    stepKey: String(s?.stepKey ?? s?.step_key ?? ''),
    name: String(s?.name ?? s?.stepName ?? ''),
    assigneeRole: String(s?.assigneeRole ?? s?.assignee_role ?? ''),
    requiresAttachment: Boolean(s?.requiresAttachment ?? s?.requires_attachment ?? false),
    approvalMode: String(s?.approvalMode ?? s?.approval_mode ?? 'ANY'),
    dueDays: s?.dueDays ?? s?.due_days ?? null,
  }));
  return {
    id: String(raw?.id ?? raw?.templateId ?? ''),
    key: String(raw?.key ?? raw?.templateKey ?? ''),
    name: String(raw?.name ?? raw?.templateName ?? ''),
    active: Boolean(raw?.active ?? true),
    description: raw?.description ?? null,
    orgId: raw?.orgId ?? raw?.org_id ?? null,
    subjectType: raw?.subjectType ?? raw?.subject_type ?? null,
    conditions: (raw?.conditions ?? null) as Record<string, any> | undefined,
    priority: raw?.priority ?? null,
    steps: steps.sort((a, b) => a.stepOrder - b.stepOrder),
  };
}



// ============================================================================
//  V27 (TASK 1 backend) — PROJECT ROLE ASSIGNEES (per-project business role
//  → user mapping). Powers the "Qui valide quoi" UI on the project detail.
// ============================================================================

/** A role → user mapping as stored in project_role_assignee. */
export interface RoleAssignee {
  roleCode: string;
  userId: string;
  userName: string;
  userEmail: string;
}

/** Body for PUT /api/projects/:id/process-role-assignees — upsert batch. */
export interface UpsertRoleAssigneesBody {
  items: { roleCode: string; userId: string }[];
}

/** One candidate user the owner can pick from. */
export interface RoleCandidate {
  userId: string;
  userName: string;
  userEmail: string;
  isOwner: boolean;
  isProjectMember: boolean;
}

/** Composite view returned by GET /process-roles-needed. */
export interface RoleNeeded {
  roleCode: string;
  currentAssignee: RoleAssignee | null;
  candidates: RoleCandidate[];
}

/** Mapper for RoleAssignee (defensive — backend may omit fields). */
export function mapRoleAssignee(raw: any): RoleAssignee {
  return {
    roleCode: String(raw?.roleCode ?? raw?.role_code ?? ''),
    userId: String(raw?.userId ?? raw?.user_id ?? ''),
    userName: String(raw?.userName ?? raw?.user_name ?? ''),
    userEmail: String(raw?.userEmail ?? raw?.user_email ?? ''),
  };
}

/** Mapper for RoleNeeded (with currentAssignee + candidates). */
export function mapRoleNeeded(raw: any): RoleNeeded {
  const candidatesRaw: any[] = Array.isArray(raw?.candidates) ? raw.candidates : [];
  return {
    roleCode: String(raw?.roleCode ?? raw?.role_code ?? ''),
    currentAssignee: raw?.currentAssignee
      ? mapRoleAssignee(raw.currentAssignee)
      : null,
    candidates: candidatesRaw.map((c: any) => ({
      userId: String(c?.userId ?? c?.user_id ?? ''),
      userName: String(c?.userName ?? c?.user_name ?? ''),
      userEmail: String(c?.userEmail ?? c?.user_email ?? ''),
      isOwner: Boolean(c?.isOwner ?? c?.is_owner ?? false),
      isProjectMember: Boolean(c?.isProjectMember ?? c?.is_project_member ?? false),
    })),
  };
}

/**
 * Business role code → French label + icon for the UI. Codes are an open
 * vocabulary (any template step can introduce a new one), so the map is
 * best-effort: unknown codes fall back to the raw code with a generic icon.
 */
export const ROLE_LABEL: Record<string, string> = {
  FINANCE: 'Finance',
  OWNER: 'Gérant',
  ACHAT: 'Achats',
  CHEF_CHANTIER: 'Chef de chantier',
  CHEF_PROJET: 'Chef de projet',
  CONDUCTEUR: 'Conducteur de travaux',
  BUREAU: 'Bureau d\u2019études',
  QA: 'Qualité',
  APPRO: 'Approvisionnement',
  MAGASIN: 'Magasin',
  COMPTE: 'Comptabilité',
  DAF: 'Direction financière',
};

export function roleLabel(code: string | null | undefined): string {
  if (!code) return '';
  return ROLE_LABEL[code.toUpperCase()] ?? code;
}

// ============================================================================
//  Rule-builder — condition option lists (MovementNature / PaymentMode codes).
//  Mirrors the backend enums (tn.hydra.cash.MovementNature / PaymentMode).
// ============================================================================

export const MOVEMENT_NATURES: string[] = [
  'ALIM_CAISSE', 'SOUS_TRAITANCE', 'MATERIEL', 'TRANSPORT', 'MAIN_OEUVRE',
  'LOCATION', 'LABO', 'CARBURANT', 'DIVERS',
];

export const NATURE_LABELS: Record<string, string> = {
  ALIM_CAISSE: 'Alimentation caisse',
  SOUS_TRAITANCE: 'Sous-traitance',
  MATERIEL: 'Matériel',
  TRANSPORT: 'Transport',
  MAIN_OEUVRE: 'Main d\u2019œuvre',
  LOCATION: 'Location',
  LABO: 'Laboratoire',
  CARBURANT: 'Carburant',
  DIVERS: 'Divers',
};

export const PAYMENT_MODES: string[] = [
  'ESPECE', 'CHEQUE', 'VIREMENT', 'VERSEMENT', 'MANDAT', 'AUTRE',
];

export const PAYMENT_MODE_LABELS: Record<string, string> = {
  ESPECE: 'Espèces',
  CHEQUE: 'Chèque',
  VIREMENT: 'Virement',
  VERSEMENT: 'Versement',
  MANDAT: 'Mandat',
  AUTRE: 'Autre',
};

// ============================================================================
//  TASK 1 (backend) — APPROVAL DESK (single payload for payment approvers)
//  Mirrors ProcessService.ApprovalDeskResponse + DeskStepView.
// ============================================================================

/** One step shown in the desk's mini-timeline (previous/completed steps). */
export interface DeskStepView {
  stepOrder: number;
  stepKey: string;
  name: string;
  status: string;
  assigneeRole: string;
  assigneeName: string | null;
  completedAt: string | null;
  comment: string | null;
}

/** Single-payload response from GET /api/process/steps/{id}/approval-desk. */
export interface ApprovalDesk {
  // ── process step + instance context ──
  stepInstanceId: string;
  instanceId: string;
  templateKey: string;
  templateName: string;
  instanceStatus: string;
  currentStepOrder: number;
  currentStepKey: string;
  currentStepName: string;
  currentStepRole: string;
  assigneeUserId: string | null;
  assigneeName: string | null;
  requiresAttachment: boolean;
  // ── subject identification ──
  subjectType: string;
  subjectId: string;
  // ── cash movement payload (CASH_MOVEMENT only) ──
  movementId: string | null;
  movementLabel: string | null;
  movementDate: string | null;
  movementType: string;
  movementStatus: string;
  assessedAmount: number | null;
  claimedAmount: number | null;
  varianceAmount: number | null;
  varianceReason: string | null;
  paymentMode: string | null;
  paymentRef: string | null;
  voucherNo: string | null;
  nature: string | null;
  invoiceRef: string | null;
  // ── linked entities (names — JOINed, no N+1) ──
  projectId: string | null;
  projectName: string | null;
  partyId: string | null;
  partyName: string | null;
  partyType: string | null;
  fromPotId: string | null;
  fromPotName: string | null;
  // ── proof document ──
  documentId: string | null;
  documentName: string | null;
  // ── flags ──
  canComplete: boolean;
  canReject: boolean;
  hasAttachment: boolean;
  // ── mini-timeline ──
  steps: DeskStepView[];
}

/** Defensive mapper: any raw backend row → ApprovalDesk. */
export function mapApprovalDesk(raw: any): ApprovalDesk {
  return {
    stepInstanceId: String(raw?.stepInstanceId ?? raw?.step_instance_id ?? ''),
    instanceId: String(raw?.instanceId ?? raw?.instance_id ?? ''),
    templateKey: String(raw?.templateKey ?? raw?.template_key ?? ''),
    templateName: String(raw?.templateName ?? raw?.template_name ?? ''),
    instanceStatus: String(raw?.instanceStatus ?? raw?.instance_status ?? ''),
    currentStepOrder: Number(raw?.currentStepOrder ?? raw?.current_step_order ?? 0),
    currentStepKey: String(raw?.currentStepKey ?? raw?.current_step_key ?? ''),
    currentStepName: String(raw?.currentStepName ?? raw?.current_step_name ?? ''),
    currentStepRole: String(raw?.currentStepRole ?? raw?.current_step_role ?? raw?.assigneeRole ?? ''),
    assigneeUserId: raw?.assigneeUserId ?? raw?.assignee_user_id ?? null,
    assigneeName: raw?.assigneeName ?? raw?.assignee_name ?? null,
    requiresAttachment: Boolean(raw?.requiresAttachment ?? raw?.requires_attachment ?? false),
    subjectType: String(raw?.subjectType ?? raw?.subject_type ?? ''),
    subjectId: raw?.subjectId ?? raw?.subject_id ? String(raw?.subjectId ?? raw?.subject_id) : '',
    movementId: raw?.movementId ?? raw?.movement_id ?? null,
    movementLabel: raw?.movementLabel ?? raw?.movement_label ?? null,
    movementDate: raw?.movementDate ?? raw?.movement_date ?? null,
    movementType: String(raw?.movementType ?? raw?.movement_type ?? ''),
    movementStatus: String(raw?.movementStatus ?? raw?.movement_status ?? ''),
    assessedAmount: raw?.assessedAmount != null ? Number(raw.assessedAmount) : null,
    claimedAmount: raw?.claimedAmount != null ? Number(raw.claimedAmount) : null,
    varianceAmount: raw?.varianceAmount != null ? Number(raw.varianceAmount) : null,
    varianceReason: raw?.varianceReason ?? raw?.variance_reason ?? null,
    paymentMode: raw?.paymentMode ?? raw?.payment_mode ?? null,
    paymentRef: raw?.paymentRef ?? raw?.payment_ref ?? null,
    voucherNo: raw?.voucherNo ?? raw?.voucher_no ?? null,
    nature: raw?.nature ?? null,
    invoiceRef: raw?.invoiceRef ?? raw?.invoice_ref ?? null,
    projectId: raw?.projectId ?? raw?.project_id ?? null,
    projectName: raw?.projectName ?? raw?.project_name ?? null,
    partyId: raw?.partyId ?? raw?.party_id ?? null,
    partyName: raw?.partyName ?? raw?.party_name ?? null,
    partyType: raw?.partyType ?? raw?.party_type ?? null,
    fromPotId: raw?.fromPotId ?? raw?.from_pot_id ?? null,
    fromPotName: raw?.fromPotName ?? raw?.from_pot_name ?? null,
    documentId: raw?.documentId ?? raw?.document_id ?? null,
    documentName: raw?.documentName ?? raw?.document_name ?? null,
    canComplete: Boolean(raw?.canComplete ?? raw?.can_complete ?? false),
    canReject: Boolean(raw?.canReject ?? raw?.can_reject ?? false),
    hasAttachment: Boolean(raw?.hasAttachment ?? raw?.has_attachment ?? false),
    steps: (Array.isArray(raw?.steps) ? raw.steps : []).map((s: any) => ({
      stepOrder: Number(s?.stepOrder ?? s?.step_order ?? 0),
      stepKey: String(s?.stepKey ?? s?.step_key ?? ''),
      name: String(s?.name ?? ''),
      status: String(s?.status ?? ''),
      assigneeRole: String(s?.assigneeRole ?? s?.assignee_role ?? ''),
      assigneeName: s?.assigneeName ?? s?.assignee_name ?? null,
      completedAt: s?.completedAt ?? s?.completed_at ?? null,
      comment: s?.comment ?? null,
    })),
  };
}
