import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpEvent, HttpEventType, HttpParams, HttpResponse } from '@angular/common/http';
import { Observable, filter, map } from 'rxjs';
import {
  Document,
  DocumentQuery,
  Page,
  Revision,
  mapDocument,
  mapRevision
} from '@app/core/models/document.model';

/** Progress or completion of a multipart upload. */
export type UploadEvent<T> =
  | { type: 'progress'; percent: number }
  | { type: 'done'; result: T };

@Injectable({ providedIn: 'root' })
export class DocumentService {
  private http = inject(HttpClient);

  /** GET /api/projects/{projectId}/documents — paged search (q = reference/title). */
  search(projectId: string, q: DocumentQuery): Observable<Page<Document>> {
    let params = new HttpParams().set('page', q.page ?? 0).set('size', q.size ?? 20);
    if (q.folderId) { params = params.set('folderId', q.folderId); }
    if (q.q) { params = params.set('q', q.q); }
    if (q.type) { params = params.set('type', q.type); }
    if (q.status) { params = params.set('status', q.status); }
    return this.http.get<any>(`/api/projects/${projectId}/documents`, { params }).pipe(
      map((raw) => this.toPage(raw, mapDocument))
    );
  }

  /** GET /api/projects/{projectId}/documents/register — Master Document Register. */
  register(projectId: string, page = 0, size = 50): Observable<Page<Document>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<any>(`/api/projects/${projectId}/documents/register`, { params }).pipe(
      map((raw) => this.toPage(raw, mapDocument))
    );
  }

  /** GET /api/documents/{id} */
  get(id: string): Observable<Document> {
    return this.http.get<any>(`/api/documents/${id}`).pipe(map(mapDocument));
  }

  /**
   * POST /api/projects/{projectId}/folders/{folderId}/documents/upload — multipart.
   * Parts: file + title + docType (+ optional revision, ticketId, tagNames, description).
   */
  upload(
    projectId: string,
    folderId: string,
    form: FormData
  ): Observable<UploadEvent<Document>> {
    return this.uploadWithProgress<Document>(
      `/api/projects/${projectId}/folders/${folderId}/documents/upload`, form, mapDocument);
  }

  /** POST /api/documents/{id}/revisions — upload a new revision (multipart). */
  uploadRevision(
    id: string,
    form: FormData
  ): Observable<UploadEvent<Revision>> {
    return this.uploadWithProgress<Revision>(`/api/documents/${id}/revisions`, form, mapRevision);
  }

  /** PUT /api/documents/{id} — partial metadata update. */
  update(id: string, body: {
    title?: string; description?: string; docType?: string;
    status?: string; tagNames?: string[]; ticketId?: string | null;
  }): Observable<Document> {
    return this.http.put<any>(`/api/documents/${id}`, body).pipe(map(mapDocument));
  }

  /** POST /api/documents/{id}/link-ticket — link (or null → unlink). */
  linkTicket(id: string, ticketId: string | null): Observable<Document> {
    return this.http.post<any>(`/api/documents/${id}/link-ticket`, { ticketId }).pipe(map(mapDocument));
  }

  /** GET /api/documents/{id}/revisions */
  revisions(id: string): Observable<Revision[]> {
    return this.http.get<any[]>(`/api/documents/${id}/revisions`).pipe(
      map(list => (list ?? []).map(mapRevision))
    );
  }

  /** DELETE /api/documents/{id} (soft). */
  remove(id: string): Observable<void> {
    return this.http.delete<void>(`/api/documents/${id}`);
  }

  /** GET /api/documents/{id}/revisions/{revId}/download — file blob. */
  downloadRevision(id: string, revId: string): Observable<Blob> {
    return this.http.get(`/api/documents/${id}/revisions/${revId}/download`, { responseType: 'blob' });
  }

  /** GET /api/tags */
  tags(): Observable<{ id: string; name: string }[]> {
    return this.http.get<{ id: string; name: string }[]>('/api/tags');
  }

  // ── helpers ──────────────────────────────────────────────────────────
  private uploadWithProgress<T>(
    url: string, form: FormData, mapper: (raw: any) => T
  ): Observable<UploadEvent<T>> {
    return this.http.post<any>(url, form, { reportProgress: true, observe: 'events' }).pipe(
      filter((ev) => ev.type === HttpEventType.UploadProgress || ev.type === HttpEventType.Response),
      map((ev): UploadEvent<T> => {
        if (ev.type === HttpEventType.UploadProgress) {
          const total = ev.total ?? 0;
          return { type: 'progress', percent: total > 0 ? Math.round(100 * ev.loaded / total) : 0 };
        }
        return { type: 'done', result: mapper((ev as HttpResponse<any>).body) };
      })
    );
  }

  private toPage<T>(raw: any, mapper: (r: any) => T): Page<T> {
    return {
      items: (raw?.items ?? []).map(mapper),
      total: raw?.total ?? 0,
      page: raw?.page ?? 0,
      size: raw?.size ?? 20
    };
  }
}
