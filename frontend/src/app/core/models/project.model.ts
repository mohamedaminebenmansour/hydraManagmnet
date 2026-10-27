/** Real natures of Tunisian water works (mirror of backend ProjectType enum). */
export type ProjectType =
  | 'RESERVOIR' | 'RESEAU' | 'BRANCHEMENTS' | 'MIXTE' | 'AUTRE'
  | 'ADDUCTION' | 'IRRIGATION' | 'VANNE' | 'FORAGE' | 'POMPAGE'
  | 'ASSAINISSEMENT' | 'REHABILITATION' | 'BASSIN';

export type ProjectRole = 'ADMIN' | 'MANAGER' | 'ENGINEER' | 'VIEWER';

export interface Project {
  id: string;
  name: string;
  code?: string | null;
  status: string; // 'ACTIF' | 'CLOTURE'
  clientName?: string | null;
  location?: string | null;
  governorate?: string | null;
  projectType?: ProjectType | null;
  startDate?: string | null;
  endDate?: string | null;
  description?: string | null;
  contractValue?: number | null;
  createdAt?: string;
}

/** POST /api/projects */
export interface ProjectCreate {
  name: string;
  code?: string | null;
  clientName?: string | null;
  location?: string | null;
  governorate?: string | null;
  projectType?: ProjectType | null;
  startDate?: Date | string | null;
  endDate?: Date | string | null;
  description?: string | null;
  contractValue?: number | null;
  memberUserIds?: string[];
}

/** PUT /api/projects/{id} */
export interface ProjectUpdate {
  name: string;
  code?: string | null;
  status: string;
  clientName?: string | null;
  location?: string | null;
  governorate?: string | null;
  projectType?: ProjectType | null;
  startDate?: Date | string | null;
  endDate?: Date | string | null;
  description?: string | null;
  contractValue?: number | null;
}

/** Member of a project — MemberResponse: userId, fullName, email, projectRole, canUpload, createdAt. */
export interface ProjectMember {
  userId: string;
  fullName: string;
  email: string;
  projectRole: ProjectRole | string;
  canUpload: boolean;
  createdAt?: string | null;
}

/** Folder of a project — FolderResponse: id, projectId, parentId, code, name, sortOrder, createdAt. */
export interface ProjectFolder {
  id: string;
  projectId: string | null;
  parentId?: string | null;
  code?: string | null;
  name: string;
  sortOrder?: number | null;
  createdAt?: string | null;
}

/** POST /api/projects/{id}/members — body: { userId, projectRole, canUpload }. */
export interface MemberCreate {
  userId: string;
  projectRole?: ProjectRole | string | null;
  canUpload?: boolean;
}

/** PUT /api/projects/{id}/members/{userId} — body: { projectRole, canUpload }. */
export interface MemberUpdate {
  projectRole?: ProjectRole | string | null;
  canUpload?: boolean;
}

/**
 * GET /api/projects/{id}/me — the current user's membership & rights on this
 * project. Drives project-scoped UI gating (e.g. hide the upload button when
 * canUpload is false). Backend returns the owner/admin as canUpload=true.
 */
export interface MyPermissions {
  isMember: boolean;
  projectRole: ProjectRole | string | null;
  canUpload: boolean;
}

/** Map MyPermissionsResponse → MyPermissions (camelCase, with snake_case fallback). */
export function mapMyPermissions(raw: any): MyPermissions {
  return {
    isMember: !!raw?.isMember,
    projectRole: raw?.projectRole ?? raw?.project_role ?? null,
    canUpload: !!raw?.canUpload
  };
}

export const PROJECT_TYPE_OPTS: { label: string; value: ProjectType }[] = [
  { label: 'Adduction', value: 'ADDUCTION' },
  { label: 'Irrigation', value: 'IRRIGATION' },
  { label: 'Réservoir', value: 'RESERVOIR' },
  { label: 'Réseau', value: 'RESEAU' },
  { label: 'Branchements', value: 'BRANCHEMENTS' },
  { label: 'Chambre de vanne', value: 'VANNE' },
  { label: 'Forage', value: 'FORAGE' },
  { label: 'Station de pompage', value: 'POMPAGE' },
  { label: 'Assainissement', value: 'ASSAINISSEMENT' },
  { label: 'Réhabilitation', value: 'REHABILITATION' },
  { label: 'Bassin', value: 'BASSIN' },
  { label: 'Mixte', value: 'MIXTE' },
  { label: 'Autre', value: 'AUTRE' }
];

export const PROJECT_TYPE_LABELS: Record<string, string> = {
  ADDUCTION: 'Adduction',
  IRRIGATION: 'Irrigation',
  RESERVOIR: 'Réservoir',
  RESEAU: 'Réseau',
  BRANCHEMENTS: 'Branchements',
  VANNE: 'Chambre de vanne',
  FORAGE: 'Forage',
  POMPAGE: 'Station de pompage',
  ASSAINISSEMENT: 'Assainissement',
  REHABILITATION: 'Réhabilitation',
  BASSIN: 'Bassin',
  MIXTE: 'Mixte',
  AUTRE: 'Autre'
};

export const PROJECT_ROLE_OPTS: { label: string; value: ProjectRole }[] = [
  { label: 'Admin', value: 'ADMIN' },
  { label: 'Chef de projet', value: 'MANAGER' },
  { label: 'Ingénieur', value: 'ENGINEER' },
  { label: 'Observateur', value: 'VIEWER' }
];

export const PROJECT_ROLE_LABELS: Record<string, string> = {
  ADMIN: 'Admin',
  MANAGER: 'Chef de projet',
  ENGINEER: 'Ingénieur',
  VIEWER: 'Observateur'
};

/**
 * Centralized backend → UI mapping. Accepts camelCase and snake_case
 * variants to stay resilient to backend serialization differences.
 */
export function mapProject(raw: any): Project {
  return {
    id: raw.id,
    name: raw.name,
    code: raw.code ?? null,
    status: raw.status ?? 'ACTIF',
    clientName: raw.clientName ?? raw.client_name ?? raw.client ?? null,
    location: raw.location ?? null,
    governorate: raw.governorate ?? null,
    projectType: raw.projectType ?? raw.project_type ?? null,
    startDate: raw.startDate ?? raw.start_date ?? null,
    endDate: raw.endDate ?? raw.end_date ?? null,
    description: raw.description ?? null,
    contractValue: raw.contractValue ?? raw.contract_value ?? null,
    createdAt: raw.createdAt ?? raw.created_at
  };
}

