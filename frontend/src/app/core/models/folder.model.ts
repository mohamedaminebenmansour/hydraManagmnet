/**
 * Folder as returned by GET /api/projects/{id}/folders (FolderResponse).
 * Serialized camelCase by Jackson: id, projectId, parentId, code, name,
 * sortOrder, createdAt, documentCount (documents directly in the folder).
 * Two levels (V46): main folders (parentId null) and their sub-folders.
 */
export interface Folder {
  id: string;
  projectId: string | null;
  parentId?: string | null;
  code?: string | null;
  name: string;
  sortOrder?: number | null;
  createdAt?: string | null;
  documentCount?: number;
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
    createdAt: raw?.createdAt ?? null,
    documentCount: Number(raw?.documentCount ?? 0),
  };
}

type FolderLike = { id: string; parentId?: string | null; name: string; sortOrder?: number | null; documentCount?: number };

const bySort = (a: FolderLike, b: FolderLike) =>
  (a.sortOrder ?? 0) - (b.sortOrder ?? 0) || a.name.localeCompare(b.name, 'fr');

/** Sub-folders of a folder, in display order. */
export function childFolders<T extends FolderLike>(folders: T[], parentId: string): T[] {
  return folders.filter(f => f.parentId === parentId).sort(bySort);
}

/** Documents of a folder plus those of its sub-folders. */
export function folderTotalCount(folders: FolderLike[], folder: FolderLike): number {
  return (folder.documentCount ?? 0)
    + childFolders(folders, folder.id).reduce((n, c) => n + (c.documentCount ?? 0), 0);
}

/** Folder picker rows: main folder, then its sub-folders (depth 1, shown indented). */
export function folderTreeOptions<T extends FolderLike>(folders: T[]): { id: string; label: string; depth: number }[] {
  const ids = new Set(folders.map(f => f.id));
  const roots = folders.filter(f => !f.parentId || !ids.has(f.parentId)).sort(bySort);
  return roots.flatMap(r => [
    { id: r.id, label: r.name, depth: 0 },
    ...childFolders(folders, r.id).map(c => ({ id: c.id, label: c.name, depth: 1 })),
  ]);
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
