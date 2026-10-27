export type DocType =
  | 'APD' | 'ETUDE' | 'PLAN' | 'PV_RECEPTION' | 'MARCHE' | 'FACTURE'
  | 'SITUATION' | 'BON_LIVRAISON' | 'RAPPORT' | 'PHOTO' | 'HSE' | 'AUTRE';

export type DocStatus = 'DRAFT' | 'IN_REVIEW' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED' | 'ARCHIVED';

export const DOC_TYPE_OPTS: { label: string; value: DocType }[] = [
  { label: 'APD', value: 'APD' },
  { label: 'Étude', value: 'ETUDE' },
  { label: 'Plan', value: 'PLAN' },
  { label: 'PV réception', value: 'PV_RECEPTION' },
  { label: 'Marché', value: 'MARCHE' },
  { label: 'Facture', value: 'FACTURE' },
  { label: 'Situation', value: 'SITUATION' },
  { label: 'Bon de livraison', value: 'BON_LIVRAISON' },
  { label: 'Rapport', value: 'RAPPORT' },
  { label: 'Photo', value: 'PHOTO' },
  { label: 'HSE', value: 'HSE' },
  { label: 'Autre', value: 'AUTRE' }
];

export const DOC_TYPE_LABELS: Record<string, string> =
  Object.fromEntries(DOC_TYPE_OPTS.map(o => [o.value, o.label]));

export const DOC_STATUS_LABELS: Record<string, string> = {
  DRAFT: 'Brouillon',
  IN_REVIEW: 'En revue',
  APPROVED: 'Approuvé',
  REJECTED: 'Rejeté',
  SUPERSEDED: 'Obsolète',
  ARCHIVED: 'Archivé'
};

export const DOC_STATUS_SEVERITY: Record<string, 'success'|'info'|'warn'|'danger'|'secondary'|'contrast'> = {
  DRAFT: 'secondary',
  IN_REVIEW: 'warn',
  APPROVED: 'success',
  REJECTED: 'danger',
  SUPERSEDED: 'contrast',
  ARCHIVED: 'contrast'
};

/** A single revision of a document (RevisionResponse). */
export interface Revision {
  id: string;
  revLabel: string;
  fileName?: string | null;
  mimeType?: string | null;
  sizeBytes?: number | null;
  changeNote?: string | null;
  uploadedBy?: string | null;
  uploadedAt?: string | null;
}

/** A document as returned by the backend (DocumentResponse). */
export interface Document {
  id: string;
  reference: string;
  projectId: string | null;
  folderId: string | null;
  ticketId: string | null;
  title: string;
  description?: string | null;
  docType: DocType | string | null;
  currentRev?: string | null;
  status: DocStatus | string | null;
  tags: string[];
  revisionCount: number;
  createdBy?: string | null;
  createdAt?: string | null;
  updatedAt?: string | null;
}

/** GET paged envelope returned by search / register. */
export interface Page<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

/** Search / list filters for documents. */
export interface DocumentQuery {
  folderId?: string | null;
  q?: string | null;
  type?: DocType | null;
  status?: DocStatus | null;
  page?: number;
  size?: number;
}

/**
 * Centralized backend → UI mapping. DocumentResponse serializes camelCase
 * (Jackson default): projectId, folderId, ticketId, docType, currentRev,
 * revisionCount, createdBy, createdAt, updatedAt; tags is List<String>.
 */
export function mapDocument(raw: any): Document {
  const tags: string[] = Array.isArray(raw?.tags) ? raw.tags.filter((t: any) => typeof t === 'string') : [];
  return {
    id: raw?.id,
    reference: raw?.reference ?? '',
    projectId: raw?.projectId ?? null,
    folderId: raw?.folderId ?? null,
    ticketId: raw?.ticketId ?? null,
    title: raw?.title ?? '',
    description: raw?.description ?? null,
    docType: raw?.docType ?? null,
    currentRev: raw?.currentRev ?? null,
    status: raw?.status ?? null,
    tags,
    revisionCount: raw?.revisionCount ?? 0,
    createdBy: raw?.createdBy ?? null,
    createdAt: raw?.createdAt ?? null,
    updatedAt: raw?.updatedAt ?? null
  };
}

/** RevisionResponse serializes camelCase: revLabel, fileName, mimeType, sizeBytes, changeNote, uploadedBy, uploadedAt. */
export function mapRevision(raw: any): Revision {
  return {
    id: raw?.id,
    revLabel: raw?.revLabel ?? '',
    fileName: raw?.fileName ?? null,
    mimeType: raw?.mimeType ?? null,
    sizeBytes: raw?.sizeBytes ?? null,
    changeNote: raw?.changeNote ?? null,
    uploadedBy: raw?.uploadedBy ?? null,
    uploadedAt: raw?.uploadedAt ?? null
  };
}
