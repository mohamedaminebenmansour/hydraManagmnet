import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import {
  Project, ProjectCreate, ProjectUpdate, ProjectMember, ProjectFolder,
  MemberCreate, MemberUpdate, MyPermissions, mapMyPermissions, mapProject
} from '@app/core/models/project.model';
import { Ouvrage, OuvrageCreate, OuvrageUpdate, mapOuvrage } from '@app/core/models/ouvrage.model';

@Injectable({ providedIn: 'root' })
export class ProjectService {
  private http = inject(HttpClient);

  /** GET /api/projects */
  list(): Observable<Project[]> {
    return this.http.get<any[]>('/api/projects').pipe(
      map(list => (list ?? []).map(mapProject))
    );
  }

  /** Backend GET /api/projects/options → [{ id, name, projectType }] (lightweight). */
  options(): Observable<{ id: string; name: string; projectType?: string | null }[]> {
    return this.http.get<{ id: string; name: string; projectType?: string | null }[]>(
      '/api/projects/options');
  }

  /** GET /api/projects/{id} */
  get(id: string): Observable<Project> {
    return this.http.get<any>(`/api/projects/${id}`).pipe(map(mapProject));
  }

  /** POST /api/projects */
  create(body: ProjectCreate): Observable<Project> {
    return this.http.post<any>('/api/projects', this.normalizeDates(body)).pipe(map(mapProject));
  }

  /** PUT /api/projects/{id} */
  update(id: string, body: ProjectUpdate): Observable<Project> {
    return this.http.put<any>(`/api/projects/${id}`, this.normalizeDates(body)).pipe(map(mapProject));
  }

  /** GET /api/projects/{id}/members */
  listMembers(id: string): Observable<ProjectMember[]> {
    return this.http.get<ProjectMember[]>(`/api/projects/${id}/members`);
  }

  /** POST /api/projects/{id}/members */
  addMember(id: string, body: MemberCreate): Observable<ProjectMember> {
    return this.http.post<ProjectMember>(`/api/projects/${id}/members`, body);
  }

  /** PUT /api/projects/{id}/members/{userId} */
  updateMember(id: string, userId: string, body: MemberUpdate): Observable<ProjectMember> {
    return this.http.put<ProjectMember>(`/api/projects/${id}/members/${userId}`, body);
  }

  /** DELETE /api/projects/{id}/members/{userId} */
  removeMember(id: string, userId: string): Observable<void> {
    return this.http.delete<void>(`/api/projects/${id}/members/${userId}`);
  }

  /** GET /api/projects/{id}/folders — auto-created by backend. */
  listFolders(id: string): Observable<ProjectFolder[]> {
    return this.http.get<ProjectFolder[]>(`/api/projects/${id}/folders`);
  }

  /**
   * GET /api/projects/{id}/me — the current user's membership & upload rights
   * on this project. Used for project-scoped UI gating (hide upload button).
   * Returns a safe default so a 403 (non-member) never breaks the caller.
   */
  myPermissions(id: string): Observable<MyPermissions> {
    return this.http.get<any>(`/api/projects/${id}/me`).pipe(
      map(raw => mapMyPermissions(raw)),
      // Backend always returns 200 here; the catchError keeps callers resilient.
    );
  }

  // ── Ouvrages (structured objects of a chantier) ──────────────────────
  // GET /api/projects/{projectId}/ouvrages
  listOuvrages(projectId: string): Observable<Ouvrage[]> {
    return this.http.get<any[]>(`/api/projects/${projectId}/ouvrages`).pipe(
      map(list => (list ?? []).map(mapOuvrage))
    );
  }

  // GET /api/projects/{projectId}/ouvrages/{ouvrageId}
  getOuvrage(projectId: string, ouvrageId: string): Observable<Ouvrage> {
    return this.http.get<any>(`/api/projects/${projectId}/ouvrages/${ouvrageId}`).pipe(map(mapOuvrage));
  }

  // POST /api/projects/{projectId}/ouvrages
  createOuvrage(projectId: string, body: OuvrageCreate): Observable<Ouvrage> {
    return this.http.post<any>(`/api/projects/${projectId}/ouvrages`, body).pipe(map(mapOuvrage));
  }

  // PUT /api/projects/{projectId}/ouvrages/{ouvrageId}
  updateOuvrage(projectId: string, ouvrageId: string, body: OuvrageUpdate): Observable<Ouvrage> {
    return this.http.put<any>(`/api/projects/${projectId}/ouvrages/${ouvrageId}`, body).pipe(map(mapOuvrage));
  }

  // DELETE /api/projects/{projectId}/ouvrages/{ouvrageId}
  deleteOuvrage(projectId: string, ouvrageId: string): Observable<void> {
    return this.http.delete<void>(`/api/projects/${projectId}/ouvrages/${ouvrageId}`);
  }

  /** Backend expects yyyy-MM-dd date strings, not full ISO datetimes. */
  private normalizeDates<T extends { startDate?: Date | string | null; endDate?: Date | string | null }>(body: T) {
    return { ...body, startDate: this.toYmd(body.startDate), endDate: this.toYmd(body.endDate) };
  }

  private toYmd(d: Date | string | null | undefined): string | null {
    if (d === null || d === undefined) { return null; }
    if (typeof d === 'string') { return d; } // already yyyy-MM-dd
    const x = new Date(d);
    if (isNaN(x.getTime())) { return null; }
    const y = x.getFullYear();
    const m = String(x.getMonth() + 1).padStart(2, '0');
    const day = String(x.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }
}

