/** Minimal user payload for pickers (assignee select, etc.). */
export interface UserOptionDto {
  id: string;
  fullName: string;
  email: string;
}

/** Shape returned by GET /api/users (parallel roleCodes/roleLabels arrays). */
export interface UserDto {
  id: string;
  email: string;
  fullName: string;
  isOwner: boolean;
  isActive: boolean;
  mustChangePassword?: boolean;
  phone?: string | null;
  jobTitle?: string | null;
  lastLoginAt?: string | null;
  passwordChangedAt?: string | null;
  status?: string;
  projectCount?: number;
  projectNames?: string[];
  roleCodes: string[];
  roleLabels: string[];
  temporaryPassword?: string | null;
  invitationSent?: boolean | null;
}

/** POST /api/users */
export interface UserCreate {
  fullName: string;
  email: string;
  password: string;
  roleCodes: string[];
  overrideReason?: string;
  sendInvitation?: boolean;
}

/** PUT /api/users/{id} */
export interface UserUpdate {
  fullName: string;
  password?: string;
}

/** PUT /api/users/{id}/roles */
export interface RoleAssign {
  roleCodes: string[];
  overrideReason?: string;
}
