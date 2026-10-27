export interface Ticket {
  id: string;
  title: string;
  description: string | null;
  status: string;
  priority: string | null;
  projectId: string | null;
  projectName: string | null;
  assignedToId: string | null;
  assignedToName: string | null;
  startAt: string | null;
  dueAt: string | null;
  documentId: string | null;
  createdAt: string;
}

/**
 * Body for create/update. startAt/dueAt accept a Date (from p-datepicker) or an
 * already-formatted 'yyyy-MM-dd' string; TicketService normalizes to 'yyyy-MM-dd'.
 */
export interface TicketUpsert {
  title: string;
  description: string | null;
  projectId: string | null;
  assignedToId: string | null;
  priority: string;
  startAt: Date | string | null;
  dueAt: Date | string | null;
  documentId: string | null;
}

export interface TicketFilter {
  projectId?: string | null;
  status?: string | null;
  priority?: string | null;
  mine?: boolean;
}

/** Transmittal / RFI workflow: Draft → For Review → Approved → IFC → As-built. */
export type TicketStatus = 'DRAFT' | 'IN_REVIEW' | 'APPROVED' | 'IFC' | 'AS_BUILT';

export const TICKET_STATUS_OPTS: { label: string; value: TicketStatus }[] = [
  { label: 'Brouillon', value: 'DRAFT' },
  { label: 'En revue', value: 'IN_REVIEW' },
  { label: 'Approuvé', value: 'APPROVED' },
  { label: 'Bon pour exécution', value: 'IFC' },
  { label: 'As-built', value: 'AS_BUILT' }
];

export const TICKET_STATUS_LABELS: Record<string, string> =
  Object.fromEntries(TICKET_STATUS_OPTS.map(o => [o.value, o.label]));

export const TICKET_STATUS_SEVERITY: Record<string, 'success'|'info'|'warn'|'danger'|'secondary'|'contrast'> = {
  DRAFT: 'secondary',
  IN_REVIEW: 'warn',
  APPROVED: 'info',
  IFC: 'success',
  AS_BUILT: 'contrast'
};

export const TICKET_STATUS_FLOW: Record<TicketStatus, TicketStatus[]> = {
  DRAFT: ['IN_REVIEW'],
  IN_REVIEW: ['APPROVED', 'DRAFT'],
  APPROVED: ['IFC', 'IN_REVIEW'],
  IFC: ['AS_BUILT'],
  AS_BUILT: []
};

export const TICKET_PRIORITY_OPTS: { label: string; value: string }[] = [
  { label: 'Basse', value: 'LOW' },
  { label: 'Moyenne', value: 'MEDIUM' },
  { label: 'Haute', value: 'HIGH' }
];
export const TICKET_PRIORITY_LABELS: Record<string, string> =
  Object.fromEntries(TICKET_PRIORITY_OPTS.map(o => [o.value, o.label]));

/**
 * Backend TicketResponse serializes records as camelCase (Jackson default):
 * projectId, projectName, assignedToId, assignedToName, startAt, dueAt,
 * createdAt, documentId… Accept a few legacy aliases defensively.
 */
export function mapTicket(raw: any): Ticket {
  return {
    id: raw?.id,
    title: raw?.title ?? '',
    description: raw?.description ?? null,
    status: raw?.status ?? 'DRAFT',
    priority: raw?.priority ?? 'MEDIUM',
    projectId: raw?.projectId ?? raw?.project_id ?? null,
    projectName: raw?.projectName ?? raw?.project_name ?? null,
    assignedToId: raw?.assignedToId ?? raw?.assignedTo ?? raw?.assigned_to ?? null,
    assignedToName: raw?.assignedToName ?? raw?.assigneeName ?? raw?.assigned_to_name ?? null,
    startAt: raw?.startAt ?? raw?.start_at ?? null,
    dueAt: raw?.dueAt ?? raw?.due_at ?? null,
    documentId: raw?.documentId ?? raw?.document_id ?? null,
    createdAt: raw?.createdAt ?? raw?.created_at ?? ''
  };
}

/** A document linked to a ticket (GET /api/tickets/{id}/documents → snake_case Map). */
export interface TicketDocument {
  id: string;
  reference: string;
  title: string;
  docType: string | null;
  currentRev: string | null;
  status: string | null;
  createdAt: string | null;
  createdById: string | null;
  createdByName: string | null;
}

/** Map a raw row from /api/tickets/{id}/documents (keys are snake_case). */
export function mapTicketDocument(raw: any): TicketDocument {
  return {
    id: raw?.id,
    reference: raw?.reference ?? '',
    title: raw?.title ?? '',
    docType: raw?.doc_type ?? null,
    currentRev: raw?.current_rev ?? null,
    status: raw?.status ?? null,
    createdAt: raw?.created_at ?? null,
    createdById: raw?.created_by ?? null,
    createdByName: raw?.created_by_name ?? null
  };
}

