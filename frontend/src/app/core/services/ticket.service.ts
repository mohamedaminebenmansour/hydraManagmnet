import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpEvent, HttpParams } from '@angular/common/http';
import { Observable, map, shareReplay } from 'rxjs';
import {
  Ticket, TicketDocument, TicketFilter, TicketUpsert, TicketSearchParams,
  TicketSearchPage, TicketSummary, TicketCatalogType,
  mapTicket, mapTicketDocument, mapTicketSearchPage, mapTicketSummary
} from '@app/core/models/ticket.model';
import { cacheSignalementLabels } from '@app/features/tickets/signalement-visual';

export interface PhotoResponse {
  id: string; documentId: string; phase: string; title: string;
  createdBy: string | null; createdAt: string; downloadUrl: string | null;
}
export interface TimelineEntry {
  id: string; kind: string; body: string | null;
  authorId: string; authorName: string | null; authorRole: string | null;
  fromStatus: string | null; toStatus: string | null;
  fromStatusLabel: string | null; toStatusLabel: string | null;
  createdAt: string;
}
export interface CommentResponse {
  id: string; body: string; authorId: string; authorName: string | null; createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class TicketService {
  private http = inject(HttpClient);

  /** GET /api/tickets — builds query params only for defined filters. */
  list(filter: TicketFilter): Observable<Ticket[]> {
    let params = new HttpParams();
    if (filter.projectId) { params = params.set('projectId', filter.projectId); }
    if (filter.status) { params = params.set('status', filter.status); }
    if (filter.priority) { params = params.set('priority', filter.priority); }
    if (filter.mine) { params = params.set('mine', 'true'); }
    return this.http.get<any[]>('/api/tickets', { params }).pipe(
      map(list => (list ?? []).map(mapTicket))
    );
  }

  /** GET /api/tickets/{id} */
  get(id: string): Observable<Ticket> {
    return this.http.get<any>(`/api/tickets/${id}`).pipe(map(mapTicket));
  }

  /** POST /api/tickets */
  create(body: TicketUpsert): Observable<Ticket> {
    return this.http.post<any>('/api/tickets', this.normalize(body)).pipe(map(mapTicket));
  }

  /** PUT /api/tickets/{id} */
  update(id: string, body: TicketUpsert): Observable<Ticket> {
    return this.http.put<any>(`/api/tickets/${id}`, this.normalize(body)).pipe(map(mapTicket));
  }

  /** POST /api/tickets/{id}/status?status=XX — move along the workflow. */
  setStatus(id: string, status: string): Observable<Ticket> {
    const params = new HttpParams().set('status', status);
    return this.http.post<any>(`/api/tickets/${id}/status`, null, { params }).pipe(map(mapTicket));
  }

  /** GET /api/tickets/{id}/transitions — allowed next statuses. */
  transitions(id: string): Observable<string[]> {
    return this.http.get<string[]>(`/api/tickets/${id}/transitions`);
  }

  /** GET /api/tickets/{id}/documents — documents linked to this ticket. */
  documents(id: string): Observable<TicketDocument[]> {
    return this.http.get<any[]>(`/api/tickets/${id}/documents`).pipe(
      map(list => (list ?? []).map(mapTicketDocument))
    );
  }

  // ── T2–T3 additions ──────────────────────────────────────────────────────

  /**
   * GET /api/tickets/catalog — the 4 types with labels, icons, colours,
   * categories and statuses. Loaded once per session (shareReplay(1)) and used
   * to populate the signalement-visual label cache.
   */
  private catalogCache?: Observable<TicketCatalogType[]>;
  catalog(): Observable<TicketCatalogType[]> {
    if (!this.catalogCache) {
      this.catalogCache = this.http.get<TicketCatalogType[]>('/api/tickets/catalog').pipe(
        shareReplay(1)
      );
      // populate the label cache so signalement-visual returns the backend labels
      this.catalogCache.subscribe(c => cacheSignalementLabels(c));
    }
    return this.catalogCache;
  }

  /** GET /api/tickets/search — paginated search with filters + photoCount. */
  search(params: TicketSearchParams): Observable<TicketSearchPage> {
    let p = new HttpParams();
    if (params.projectId) { p = p.set('projectId', params.projectId); }
    if (params.type) { p = p.set('type', params.type); }
    if (params.status) { p = p.set('status', params.status); }
    if (params.mine) { p = p.set('mine', 'true'); }
    if (params.overdue) { p = p.set('overdue', 'true'); }
    if (params.q) { p = p.set('q', params.q); }
    p = p.set('page', String(params.page ?? 0));
    p = p.set('size', String(params.size ?? 20));
    return this.http.get<any>('/api/tickets/search', { params: p }).pipe(map(mapTicketSearchPage));
  }

  /** GET /api/tickets/summary — counts (mine, overdue, toVerify, open, byType). */
  summary(projectId?: string | null): Observable<TicketSummary> {
    let p = new HttpParams();
    if (projectId) { p = p.set('projectId', projectId); }
    return this.http.get<any>('/api/tickets/summary', { params: p }).pipe(map(mapTicketSummary));
  }

  /** POST /api/tickets/with-photos (multipart: ticket JSON + photos). Reports progress. */
  createWithPhotos(ticket: Record<string, any>, photos: File[]): Observable<HttpEvent<any>> {
    const fd = new FormData();
    fd.append('ticket', new Blob([JSON.stringify(ticket)], { type: 'application/json' }), 'ticket.json');
    for (const p of photos) { fd.append('photos', p, p.name); }
    return this.http.post<any>('/api/tickets/with-photos', fd, { reportProgress: true, observe: 'events' });
  }

  // ── T3 actions + timeline + comments + photos ──────────────────────────

  /** GET /api/tickets/{id}/actions — action codes the current user may do. */
  actions(id: string): Observable<string[]> {
    return this.http.get<string[]>(`/api/tickets/${id}/actions`);
  }

  /** GET /api/tickets/{id}/timeline — comments + status changes, oldest first. */
  timeline(id: string): Observable<TimelineEntry[]> {
    return this.http.get<TimelineEntry[]>(`/api/tickets/${id}/timeline`);
  }

  /** POST /api/tickets/{id}/comments {body} — add a comment. */
  comment(id: string, body: string): Observable<CommentResponse> {
    return this.http.post<CommentResponse>(`/api/tickets/${id}/comments`, { body });
  }

  /** POST /api/tickets/{id}/start — OPEN → IN_PROGRESS. */
  start(id: string): Observable<Ticket> {
    return this.http.post<any>(`/api/tickets/${id}/start`, null).pipe(map(mapTicket));
  }

  /** POST /api/tickets/{id}/resolve {note} — → RESOLVED. */
  resolve(id: string, note: string): Observable<Ticket> {
    return this.http.post<any>(`/api/tickets/${id}/resolve`, { note }).pipe(map(mapTicket));
  }

  /** POST /api/tickets/{id}/verify — RESOLVED → CLOSED. */
  verify(id: string): Observable<Ticket> {
    return this.http.post<any>(`/api/tickets/${id}/verify`, null).pipe(map(mapTicket));
  }

  /** POST /api/tickets/{id}/reopen {comment} — RESOLVED → IN_PROGRESS. */
  reopen(id: string, comment: string): Observable<Ticket> {
    return this.http.post<any>(`/api/tickets/${id}/reopen`, { comment }).pipe(map(mapTicket));
  }

  /** POST /api/tickets/{id}/cancel {reason} — → CANCELLED. */
  cancel(id: string, reason: string): Observable<Ticket> {
    return this.http.post<any>(`/api/tickets/${id}/cancel`, { reason }).pipe(map(mapTicket));
  }

  /** GET /api/tickets/{id}/photos — list photos. */
  photos(id: string): Observable<PhotoResponse[]> {
    return this.http.get<PhotoResponse[]>(`/api/tickets/${id}/photos`);
  }

  /** POST /api/tickets/{id}/photos (multipart: file, phase) — add one photo. */
  addPhoto(id: string, file: File, phase: string): Observable<PhotoResponse> {
    const fd = new FormData();
    fd.append('file', file, file.name);
    fd.append('phase', phase);
    return this.http.post<PhotoResponse>(`/api/tickets/${id}/photos`, fd);
  }

  /** DELETE /api/tickets/{id}/photos/{attachmentId} — remove a photo. */
  deletePhoto(id: string, attachmentId: string): Observable<void> {
    return this.http.delete<void>(`/api/tickets/${id}/photos/${attachmentId}`);
  }

  /** POST /api/tickets/{id}/files (multipart: file) — attach a TACHE deliverable. */
  addFile(id: string, file: File): Observable<PhotoResponse> {
    const fd = new FormData();
    fd.append('file', file, file.name);
    return this.http.post<PhotoResponse>(`/api/tickets/${id}/files`, fd);
  }

  /** Backend expects yyyy-MM-dd date strings, not full ISO datetimes. */
  private normalize(body: TicketUpsert) {
    return { ...body, startAt: this.toYmd(body.startAt), dueAt: this.toYmd(body.dueAt) };
  }

  private toYmd(d: Date | string | null): string | null {
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
