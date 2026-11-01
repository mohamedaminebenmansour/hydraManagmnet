import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map, catchError, of } from 'rxjs';
import { UserCreate, UserDto, UserOptionDto, UserUpdate, RoleAssign } from '@app/core/models/user.model';

@Injectable({ providedIn: 'root' })
export class UserService {
  private http = inject(HttpClient);

  /** GET /api/users (admin). */
  list(): Observable<UserDto[]> {
    return this.http.get<UserDto[]>('/api/users');
  }

  /** GET /api/users/options — lightweight list for pickers. */
  options(): Observable<UserOptionDto[]> {
    return this.http.get<UserOptionDto[]>('/api/users/options');
  }

  /** POST /api/users */
  create(body: UserCreate): Observable<UserDto> {
    return this.http.post<UserDto>('/api/users', body);
  }

  /** PUT /api/users/{id} */
  update(id: string, body: UserUpdate): Observable<UserDto> {
    return this.http.put<UserDto>(`/api/users/${id}`, body);
  }

  /** PUT /api/users/{id}/roles */
  assignRoles(id: string, body: RoleAssign): Observable<UserDto> {
    return this.http.put<UserDto>(`/api/users/${id}/roles`, body);
  }

  /** POST /api/users/{id}/activate | deactivate */
  setActive(id: string, active: boolean): Observable<UserDto> {
    return this.http.post<UserDto>(`/api/users/${id}/${active ? 'activate' : 'deactivate'}`, {});
  }

  /** PUT /api/users/{id}/absence — set or clear absence (null = end). */
  setAbsence(id: string, absentUntil: string | null): Observable<{ ok: boolean; error?: string }> {
    return this.http.put(`/api/users/${id}/absence`, { absentUntil }).pipe(
      map(() => ({ ok: true })),
      catchError(err => of({ ok: false, error: err?.error?.message ?? 'Erreur' }))
    );
  }
}
