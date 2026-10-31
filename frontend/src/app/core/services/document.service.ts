import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpEvent, HttpEventType, HttpParams, HttpResponse } from '@angular/common/http';
import { Observable, catchError, filter, map, of, switchMap } from 'rxjs';
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

// PHASE-B-DOC-VIEW — shared open/download outcome type used by every screen that
// surfaces a document (document list, document detail, facture/BL/BC/cash detail).
export type OpenOutcome =
  | { kind: 'opened' }                                    // new tab opened (preview)
  | { kind: 'downloaded'; reason?: 'popup-blocked' }     // browser saved the file
  | { kind: 'error'; message: string };                   // fetch failed or JSON-error blob

/** PDF + raster images can be opened in a new browser tab via createObjectURL. */
export function isPreviewable(mimeType: string | null | undefined): boolean {
  if (!mimeType) return false;
  const ct = mimeType.toLowerCase().split(';')[0].trim();
  return ct === 'application/pdf'
      || ct === 'application/x-pdf'
      || ct.startsWith('image/');
}

/** Trigger a real browser download via a synthetic <a download>. */
export function triggerBrowserDownload(url: string, filename: string): void {
  const a = document.createElement('a');
  a.href = url;
  a.download = filename || 'document';
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

/** Normalize any error (sync throw or HTTP error) into an OpenOutcome.error with FR message. */
export function toErrorOutcome(err: any): OpenOutcome {
  // The JSON-error-blob case: the blob body carries the API envelope
  // {code, message}. We try to read it synchronously (not possible); instead,
  // we degrade gracefully to a French message that includes the status.
  if (err && typeof err === 'object' && err.kind === 'error' && typeof err.message === 'string') {
    return err; // already an OpenOutcome
  }
  const status = err?.status;
  const body = err?.error;
  let msg = '';
  if (typeof body === 'string' && body.trim()) {
    msg = body;
  } else if (body && typeof body === 'object') {
    msg = body.message ?? body.detail ?? body.code ?? '';
  }
  if (!msg && typeof err?.message === 'string') {
    msg = err.message;
  }
  if (!msg) {
    msg = status === 404 ? 'Fichier introuvable.'
       : status === 403 ? 'Accès refusé.'
       : status === 401 ? 'Session expirée, veuillez vous reconnecter.'
       : 'Téléchargement impossible.';
  }
  return { kind: 'error', message: msg };
}

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

/**
   * PHASE-B-DOC-VIEW — Open or download a revision in the most natural way:
   *
   *  - PDF or image (jpg/png/gif/webp)  → createObjectURL + window.open (new tab)
   *  - everything else (xlsx, docx, …)  → trigger a real <a download> (saved by browser)
   *
   * GUARDS:
   *  - JSON-error blob: if the API answered 4xx/5xx with a JSON body (GlobalExceptionHandler),
   *    the responseType:'blob' will silently capture that JSON as the "file". We sniff the
   *    blob.type and, when it's JSON, parse the message and surface an error — never saving
   *    a .pdf that is actually `{"code":"DOCUMENT_NOT_FOUND","message":"…"}`.
   *  - Popup blocked: if window.open returns null (browser blocker / cross-origin),
   *    fall back to <a download> and report `kind:'downloaded'` so the caller can toast.
   *
   * Returns an OpenOutcome so the caller decides the toast wording (FR).
   */
  openRevision(id: string, revId: string, hintName?: string | null): Observable<OpenOutcome> {
    return this.fetchBlobGuarded(id, revId, (blob, mime) => {
      if (isPreviewable(mime)) {
        const url = URL.createObjectURL(blob);
        const win = window.open(url, '_blank', 'noopener');
        if (win) {
          setTimeout(() => URL.revokeObjectURL(url), 60_000);
          return { kind: 'opened' };
        }
        triggerBrowserDownload(url, hintName ?? 'document');
        setTimeout(() => URL.revokeObjectURL(url), 5_000);
        return { kind: 'downloaded', reason: 'popup-blocked' };
      }
      const url = URL.createObjectURL(blob);
      triggerBrowserDownload(url, hintName ?? 'document');
      setTimeout(() => URL.revokeObjectURL(url), 5_000);
      return { kind: 'downloaded' };
    });
  }

  /**
   * PHASE-B-DOC-VIEW — Convenience: fetch the latest revision of a document,
   * then open (preview) it. Used by facture/BL/BC/cash screens that only know
   * the documentId, not the revision id. Returns the same OpenOutcome shape.
   */
  openDocument(docId: string, hintName?: string | null): Observable<OpenOutcome> {
    return this.revisions(docId).pipe(
      switchMap((revs: Revision[]) => {
        const latest = revs && revs.length ? revs[revs.length - 1] : null;
        if (!latest) {
          return of<OpenOutcome>({ kind: 'error', message: 'Aucune révision disponible.' });
        }
        return this.openRevision(docId, latest.id, hintName ?? latest.fileName ?? latest.revLabel);
      }),
      catchError((err: any) => of(toErrorOutcome(err)))
    );
  }

  /**
   * PHASE-B-DOC-VIEW — Convenience: fetch the latest revision then download it
   * (forced attachment, regardless of mime). Same JSON-error guard as openRevision.
   */
  downloadDocument(docId: string, hintName?: string | null): Observable<OpenOutcome> {
    return this.revisions(docId).pipe(
      switchMap((revs: Revision[]) => {
        const latest = revs && revs.length ? revs[revs.length - 1] : null;
        if (!latest) {
          return of<OpenOutcome>({ kind: 'error', message: 'Aucune révision disponible.' });
        }
        return this.fetchBlobGuarded(docId, latest.id, (blob, _mime) => {
          const url = URL.createObjectURL(blob);
          triggerBrowserDownload(url, hintName ?? latest.fileName ?? latest.revLabel ?? 'document');
          setTimeout(() => URL.revokeObjectURL(url), 5_000);
          return { kind: 'downloaded' };
        });
      }),
      catchError((err: any) => of(toErrorOutcome(err)))
    );
  }

/**
   * PHASE-B-DOC-VIEW — Internal: fetch blob, then if the content-type signals a
   * JSON error, parse the message and return an OpenOutcome.error. Otherwise
   * hand the (valid) blob to the consumer callback.
   */
  private fetchBlobGuarded(
    id: string, revId: string,
    onBlob: (blob: Blob, mime: string) => OpenOutcome
  ): Observable<OpenOutcome> {
    return this.downloadRevision(id, revId).pipe(
      switchMap((blob: Blob) => {
        const mime = (blob.type || '').toLowerCase();
        if (mime.startsWith('application/json') || mime === 'text/json' || mime === '') {
          // Read the JSON text and surface the API message.
          return new Observable<OpenOutcome>(sub => {
            blob.text().then(txt => {
              let msg = 'Fichier introuvable ou accès refusé.';
              try {
                const j = JSON.parse(txt);
                msg = j?.message ?? j?.detail ?? j?.code ?? msg;
              } catch { /* keep default */ }
              sub.next({ kind: 'error', message: msg });
              sub.complete();
            }).catch(() => {
              sub.next({ kind: 'error', message: 'Lecture du fichier impossible.' });
              sub.complete();
            });
          });
        }
        return of(onBlob(blob, mime));
      }),
      catchError((err: any) => of(toErrorOutcome(err)))
    );
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
