import { Injectable, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map, catchError, of } from 'rxjs';
import { MessageService } from 'primeng/api';

import { Party, PartyType, mapParty } from '@app/core/models/cash.model';

type AnyRaw = Record<string, any>;

/**
 * PartyService — directory of sous-traitants / fournisseurs.
 * Signal-based cache so the payment form + party list + detail share data.
 */
@Injectable({ providedIn: 'root' })
export class PartyService {
  private http = inject(HttpClient);
  private msgs = inject(MessageService);

  private partiesCache = signal<Party[]>([]);
  private partiesLoaded = signal(false);

  parties = computed(() => this.partiesCache());
  partiesReady = computed(() => this.partiesLoaded());

  /** GET /api/parties — optional type filter ('SUB'|'SUPPLIER'|'OTHER'). */
  loadParties(type?: PartyType, includeInactive = false, force = false): Observable<Party[]> {
    if (this.partiesLoaded() && !type && !includeInactive && !force) {
      return of(this.partiesCache());
    }
    const params: Record<string, string> = {};
    if (type) params['type'] = type;
    if (includeInactive) params['includeInactive'] = 'true';

    return this.http.get<AnyRaw[]>('/api/parties', { params }).pipe(
      map(list => {
        const parties = (Array.isArray(list) ? list : []).map(mapParty);
        if (!type && !includeInactive) {
          this.partiesCache.set(parties);
          this.partiesLoaded.set(true);
        }
        return parties;
      }),
      catchError(err => {
        this.toastError(err, 'Impossible de charger les sous-traitants');
        return of([]);
      })
    );
  }

  /** GET /api/parties/{id}. */
  getParty(id: string): Observable<Party | null> {
    return this.http.get<AnyRaw>(`/api/parties/${id}`).pipe(
      map(raw => mapParty(raw)),
      catchError(err => {
        this.toastError(err, 'Sous-traitant introuvable');
        return of(null);
      })
    );
  }

  /** POST /api/parties — create {name, type, phone}. */
  createParty(body: { name: string; type: PartyType; phone?: string }): Observable<Party | null> {
    return this.http.post<AnyRaw>('/api/parties', body).pipe(
      map(raw => {
        const p = mapParty(raw);
        // Append to cache
        this.partiesCache.set([...this.partiesCache(), p]);
        return p;
      }),
      catchError(err => {
        this.toastError(err, 'Impossible de créer le sous-traitant');
        return of(null);
      })
    );
  }

  /** PUT /api/parties/{id} — update {name?, type?, phone?, active?}. */
  updateParty(id: string, body: { name?: string; type?: PartyType; phone?: string; active?: boolean }): Observable<Party | null> {
    return this.http.put<AnyRaw>(`/api/parties/${id}`, body).pipe(
      map(raw => {
        const p = mapParty(raw);
        const list = this.partiesCache();
        const idx = list.findIndex(x => x.id === id);
        if (idx >= 0) {
          const copy = [...list];
          copy[idx] = p;
          this.partiesCache.set(copy);
        }
        return p;
      }),
      catchError(err => {
        this.toastError(err, 'Impossible de modifier le sous-traitant');
        return of(null);
      })
    );
  }

  partyById(id: string): Party | undefined {
    return this.partiesCache().find(p => p.id === id);
  }

  private toastError(err: any, fallback: string): void {
    this.msgs.add({
      severity: 'error',
      summary: 'Erreur',
      detail: err?.error?.message ?? err?.error?.code ?? fallback,
    });
  }
}
