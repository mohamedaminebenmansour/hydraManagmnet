/**
 * User status visual identity (U4) — maps the 5 status codes from the backend
 * (UserService.userStatus) to a French label, a colour, and an icon.
 *
 * Priority order (from the backend):
 *   INACTIVE > PENDING > OWNER > NO_PROJECT > ACTIVE
 */
export interface UserStatusVisual {
  label: string;
  color: string;
  soft: string;
  icon: string;
}

const STATUS_VISUAL: Record<string, UserStatusVisual> = {
  ACTIVE:           { label: 'Actif',              color: '#16a34a', soft: '#f0fdf4', icon: 'pi-check-circle' },
  PENDING:          { label: 'En attente 1re co.', color: '#ea580c', soft: '#fff7ed', icon: 'pi-clock' },
  NO_PROJECT:       { label: 'Sans chantier',      color: '#64748b', soft: '#f8fafc', icon: 'pi-exclamation-circle' },
  INACTIVE:         { label: 'Inactif',            color: '#dc2626', soft: '#fef2f2', icon: 'pi-ban' },
  OWNER:            { label: 'Gérant',             color: '#b45309', soft: '#fffbeb', icon: 'pi-crown' },
};

export function userStatusVisual(status: string | null | undefined): UserStatusVisual {
  if (!status) return { label: '—', color: '#64748b', soft: '#f8fafc', icon: 'pi-user' };
  return STATUS_VISUAL[status] ?? { label: status, color: '#64748b', soft: '#f8fafc', icon: 'pi-user' };
}
