import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map, shareReplay } from 'rxjs';
import {
  Ticket, TicketDocument, TicketFilter, TicketUpsert, TicketSearchParams,
  TicketSearchPage, TicketSummary, TicketCatalogType,
  mapTicket, mapTicketDocument, mapTicketSearchPage, mapTicketSummary
} from '@app/core/models/ticket.model';
import { cacheSignalementLabels } from '@app/features/tickets/signalement-visual';

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
