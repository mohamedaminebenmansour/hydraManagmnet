/**
 * Folder as returned by GET /api/projects/{id}/folders (FolderResponse).
 * Serialized camelCase by Jackson: id, projectId, parentId, code, name,
 * sortOrder, createdAt. There is NO documentCount — the UI derives
 * per-folder counts from its own search results when needed.
 */
export interface Folder {
  id: string;
  projectId: string | null;
  parentId?: string | null;
  code?: string | null;
  name: string;
  sortOrder?: number | null;
  createdAt?: string | null;
}

/** Centralized backend → UI mapping (single place to adjust). */
export function mapFolder(raw: any): Folder {
  return {
    id: raw?.id,
    projectId: raw?.projectId ?? null,
    parentId: raw?.parentId ?? null,
    code: raw?.code ?? null,
    name: raw?.name ?? '',
    sortOrder: raw?.sortOrder ?? null,
    createdAt: raw?.createdAt ?? null
  };
}

/** Folder access grant returned by GET /access. */
export interface FolderAccess {
  id: string;
  projectId?: string;
  folderId?: string;
  userId: string | null;
  roleCode: string | null;
  canRead: boolean;
  canUpload: boolean;
  canDownload: boolean;
  canApprove: boolean;
  canDelete: boolean;
  userName?: string | null;
  roleLabel?: string | null;
}

/** Payload sent to POST /api/projects/{id}/folders/{folderId}/access */
export interface FolderAccessGrant {
  id?: string;
  userId?: string | null;
  roleCode?: string | null;
  canRead?: boolean;
  canUpload?: boolean;
  canDownload?: boolean;
  canApprove?: boolean;
  canDelete?: boolean;
}

/** Static company role catalog used by the folder ACL UI. */
export const COMPANY_ROLE_OPTS = [
  'OWNER', 'DIRECTION', 'CHEF_PROJET', 'DOC_CTRL', 'INGENIEUR', 'CONDUCTEUR',
  'QA', 'CHEF_CHANTIER', 'FINANCE', 'ACHAT', 'HSE', 'SOUS_TRAITANT'
] as const;

/** Static French labels for role codes (fallback when backend labels missing). */
export const COMPANY_ROLE_LABELS: Record<string, string> = {
  OWNER: 'Propriétaire',
  DIRECTION: 'Direction',
  CHEF_PROJET: 'Chef de projet',
  DOC_CTRL: 'Contrôle documentaire',
  INGENIEUR: 'Ingénieur',
  CONDUCTEUR: 'Conducteur de travaux',
  QA: 'Qualité',
  CHEF_CHANTIER: 'Chef de chantier',
  FINANCE: 'Finance',
  ACHAT: 'Achats',
  HSE: 'HSE',
  SOUS_TRAITANT: 'Sous-traitant'
};

export function mapFolderAccess(raw: any): FolderAccess {
  return {
    id: raw?.id ?? '',
    projectId: raw?.projectId ?? '',
    folderId: raw?.folderId ?? '',
    userId: raw?.userId ?? null,
    roleCode: raw?.roleCode ?? null,
    canRead: !!raw?.canRead,
    canUpload: !!raw?.canUpload,
    canDownload: !!raw?.canDownload,
    canApprove: !!raw?.canApprove,
    canDelete: !!raw?.canDelete,
    userName: raw?.userName ?? null,
    roleLabel: raw?.roleLabel ?? null
  };
}
