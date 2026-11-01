import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map } from 'rxjs';

/** One business event (GET /api/events, owner only). */
export interface DomainEvent {
  id: string;
  type: string;
  typeLabel: string;
  subjectType: string;
  subjectId: string;
  projectId: string | null;
  projectName: string | null;
  payload: Record<string, string>;
  createdBy: string | null;
  createdByName: string | null;
  createdAt: string | null;
}

export interface DomainEventPage { items: DomainEvent[]; total: number; page: number; size: number; }

export interface DomainEventFilter {
  type?: string | null; projectId?: string | null;
  from?: string | null; to?: string | null; // yyyy-mm-dd
  page?: number;
}

/** Event types and their French labels (same as backend EventType). */
export const DOMAIN_EVENT_TYPES: { value: string; label: string }[] = [
  { value: 'PAYMENT_SUBMITTED', label: 'Paiement soumis' },
  { value: 'CASH_MOVEMENT_POSTED', label: 'Mouvement de caisse comptabilisé' },
  { value: 'PROCESS_STEP_COMPLETED', label: 'Étape de processus validée' },
  { value: 'PURCHASE_ORDER_CREATED', label: 'Bon de commande créé' },
  { value: 'DELIVERY_RECEIVED', label: 'Livraison réceptionnée' },
  { value: 'INVOICE_RECORDED', label: 'Facture enregistrée' },
  { value: 'BORDEREAU_IMPORTED', label: 'Bordereau importé' },
  { value: 'BORDEREAU_ACTIVATED', label: 'Version de bordereau activée' },
  { value: 'DOCUMENT_UPLOADED', label: 'Document téléversé' },
];

@Injectable({ providedIn: 'root' })
export class DomainEventService {
  private http = inject(HttpClient);

  list(f: DomainEventFilter): Observable<DomainEventPage> {
    let params = new HttpParams().set('page', String(f.page ?? 0));
    if (f.type) params = params.set('type', f.type);
    if (f.projectId) params = params.set('projectId', f.projectId);
    if (f.from) params = params.set('from', f.from);
    if (f.to) params = params.set('to', f.to);
    return this.http.get<any>('/api/events', { params }).pipe(map(raw => ({
      items: (raw?.items ?? []) as DomainEvent[],
      total: Number(raw?.total ?? 0),
      page: Number(raw?.page ?? 0),
      size: Number(raw?.size ?? 50),
    })));
  }
}
