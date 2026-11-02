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
  // T1–T3 fields (all optional — old screens that don't use them still compile)
  type?: string | null;
  typeLabel?: string | null;
  category?: string | null;
  categoryLabel?: string | null;
  origin?: string | null;
  locationText?: string | null;
  ouvrageId?: string | null;
  ouvrageName?: string | null;
  responsiblePartyId?: string | null;
  responsiblePartyName?: string | null;
  statusLabel?: string | null;
  overdue?: boolean;
  isPrivate?: boolean;
  processInstanceId?: string | null;
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

/** Search params for GET /api/tickets/search (T2). */
export interface TicketSearchParams {
  projectId?: string | null;
  type?: string | null;
  status?: string | null;
  mine?: boolean;
  overdue?: boolean;
  q?: string | null;
  page?: number;
  size?: number;
}

/** One row of the reference catalogue (GET /api/tickets/catalog). */
export interface TicketCatalogType {
  code: string;
  label: string;
  icon: string;
  colour: string;
  defaultDays: number | null;
  requiresAfterPhoto: boolean;
  categories: { code: string; label: string }[];
  statuses: { code: string; label: string }[];
}

/** A search result item = a Ticket + its photo count. */
export interface TicketSearchItem {
  ticket: Ticket;
  photoCount: number;
}

/** Paginated search response: { items, total, page, size }. */
export interface TicketSearchPage {
  items: TicketSearchItem[];
  total: number;
  page: number;
  size: number;
}

/** GET /api/tickets/summary response. */
export interface TicketSummary {
  mine: number;
  overdue: number;
  toVerify: number;
  open: number;
  inProgress: number;
  byType: Record<string, number>;
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
 * createdAt, documentId, type, typeLabel, category, categoryLabel, origin,
 * locationText, ouvrageId, ouvrageName, responsiblePartyId, responsiblePartyName,
 * statusLabel, overdue, processInstanceId… Accept a few legacy aliases defensively.
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
    createdAt: raw?.createdAt ?? raw?.created_at ?? '',
    type: raw?.type ?? null,
    typeLabel: raw?.typeLabel ?? null,
    category: raw?.category ?? null,
    categoryLabel: raw?.categoryLabel ?? null,
    origin: raw?.origin ?? null,
    locationText: raw?.locationText ?? raw?.location_text ?? null,
    ouvrageId: raw?.ouvrageId ?? raw?.ouvrage_id ?? null,
    ouvrageName: raw?.ouvrageName ?? raw?.ouvrage_name ?? null,
    responsiblePartyId: raw?.responsiblePartyId ?? raw?.responsible_party_id ?? null,
    responsiblePartyName: raw?.responsiblePartyName ?? raw?.responsible_party_name ?? null,
    statusLabel: raw?.statusLabel ?? null,
    overdue: raw?.overdue ?? false,
    isPrivate: raw?.isPrivate ?? raw?.is_private ?? false,
    processInstanceId: raw?.processInstanceId ?? raw?.process_instance_id ?? null
  };
}

/** Map a search item: { ticket: {...}, photoCount } (backend wraps the TicketResponse). */
export function mapTicketSearchItem(raw: any): TicketSearchItem {
  return {
    ticket: mapTicket(raw?.ticket ?? raw),
    photoCount: raw?.photoCount ?? raw?.photo_count ?? 0
  };
}

/** Map a search page: { items, total, page, size }. */
export function mapTicketSearchPage(raw: any): TicketSearchPage {
  return {
    items: (raw?.items ?? []).map(mapTicketSearchItem),
    total: raw?.total ?? 0,
    page: raw?.page ?? 0,
    size: raw?.size ?? 20
  };
}

/** Map a summary response (byType keys are type codes). */
export function mapTicketSummary(raw: any): TicketSummary {
  return {
    mine: raw?.mine ?? 0,
    overdue: raw?.overdue ?? 0,
    toVerify: raw?.toVerify ?? 0,
    open: raw?.open ?? 0,
    inProgress: raw?.inProgress ?? raw?.in_progress ?? 0,
    byType: raw?.byType ?? {}
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
