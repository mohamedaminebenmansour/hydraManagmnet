import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';
import {
  Ticket, TicketDocument, TicketFilter, TicketUpsert, mapTicket, mapTicketDocument
} from '@app/core/models/ticket.model';

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
