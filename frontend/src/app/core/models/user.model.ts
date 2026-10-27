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
  roleCodes: string[];
  roleLabels: string[];
  lastLoginAt?: string | null;
}

/** POST /api/users */
export interface UserCreate {
  fullName: string;
  email: string;
  password: string;
  roleCodes: string[];
  overrideReason?: string;
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
