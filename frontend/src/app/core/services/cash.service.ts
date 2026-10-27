import { Injectable, inject, signal, computed } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable, map, catchError, of } from 'rxjs';
import { MessageService } from 'primeng/api';

import {
  CashPot, CashMovement, MovementBody, BalanceResponse, PostResponse,
  mapPot, mapMovement, formatAmount,
} from '@app/core/models/cash.model';

type AnyRaw = Record<string, any>;

/**
 * CashService — wraps all /api/cash/* endpoints with defensive parsing,
 * French error toasts, and a signal-based cache for pots (so the hub,
 * pot list, pot detail, and forms share the same data without refetching).
 */
@Injectable({ providedIn: 'root' })
export class CashService {
  private http = inject(HttpClient);
  private msgs = inject(MessageService);

  // ── Cache ──────────────────────────────────────────────────────
  private potsCache = signal<CashPot[]>([]);
  private potsLoaded = signal(false);

  /** Shared pot balances: { potId: balance } — updated by loadBalance(). */
  private balancesCache = signal<Record<string, number>>({});

  /** Shared pots list — loaded once, refreshed on mutations. */
  pots = computed(() => this.potsCache());
  potsReady = computed(() => this.potsLoaded());
  balances = computed(() => this.balancesCache());

  // ── POTS ───────────────────────────────────────────────────────

  /** GET /api/cash/pots — fills the cache. */
  loadPots(force = false): Observable<CashPot[]> {
    if (this.potsLoaded() && !force) {
      return of(this.potsCache());
    }
    return this.http.get<AnyRaw[]>('/api/cash/pots').pipe(
      map(list => {
        const pots = (Array.isArray(list) ? list : []).map(mapPot);
        this.potsCache.set(pots);
        this.potsLoaded.set(true);
        return pots;
      }),
      catchError(err => {
        this.toastError(err, 'Impossible de charger les caisses');
        this.potsCache.set([]);
        this.potsLoaded.set(true);
        return of([]);
      })
    );
  }

  /** POST /api/cash/pots/ensure-company — idempotent "Caisse centrale". */
  ensureCompanyPot(): Observable<CashPot | null> {
    return this.http.post<AnyRaw>('/api/cash/pots/ensure-company', {}).pipe(
      map(raw => {
        const pot = mapPot(raw);
        this.refreshPotInCache(pot);
        return pot;
      }),
      catchError(err => {
        // 404 or 403 → calm message, no crash
        this.toastError(err, 'Caisse centrale indisponible');
        return of(null);
      })
    );
  }

  /** POST /api/cash/pots/ensure-project/{projectId} — idempotent project pot. */
  ensureProjectPot(projectId: string): Observable<CashPot | null> {
    return this.http.post<AnyRaw>(`/api/cash/pots/ensure-project/${projectId}`, {}).pipe(
      map(raw => {
        const pot = mapPot(raw);
        this.refreshPotInCache(pot);
        return pot;
      }),
      catchError(err => {
        this.toastError(err, 'Caisse chantier indisponible');
        return of(null);
      })
    );
  }

  /** GET /api/cash/pots/{id}/balance — result stored in the balances signal. */
  getBalance(potId: string): Observable<number | null> {
    return this.http.get<AnyRaw>(`/api/cash/pots/${potId}/balance`).pipe(
      map(raw => Number(raw?.['balance'] ?? 0)),
      map(bal => {
        this.balancesCache.update(m => ({ ...m, [potId]: bal }));
        return bal;
      }),
      catchError(err => {
        this.toastError(err, 'Solde indisponible');
        return of(null);
      })
    );
  }

  /** Read a pot's balance from the cache (0 if not loaded yet). */
  balanceOf(potId: string): number {
    return this.balancesCache()[potId] ?? 0;
  }

  // ── MOVEMENTS ──────────────────────────────────────────────────

  /** POST /api/cash/movements — create DRAFT. */
  createMovement(body: MovementBody): Observable<CashMovement | null> {
    return this.http.post<AnyRaw>('/api/cash/movements', body).pipe(
      map(raw => mapMovement(raw)),
      catchError(err => {
        this.toastError(err, 'Impossible de créer l\'opération');
        return of(null);
      })
    );
  }

  /** GET /api/cash/movements/{id}. */
  getMovement(id: string): Observable<CashMovement | null> {
    return this.http.get<AnyRaw>(`/api/cash/movements/${id}`).pipe(
      map(raw => mapMovement(raw)),
      catchError(err => {
        this.toastError(err, 'Opération introuvable');
        return of(null);
      })
    );
  }

  /** PUT /api/cash/movements/{id} — update DRAFT only. */
  updateMovement(id: string, body: MovementBody): Observable<CashMovement | null> {
    return this.http.put<AnyRaw>(`/api/cash/movements/${id}`, body).pipe(
      map(raw => mapMovement(raw)),
      catchError(err => {
        this.toastError(err, 'Impossible de modifier l\'opération (brouillon uniquement)');
        return of(null);
      })
    );
  }

  /** POST /api/cash/movements/{id}/post — DRAFT → POSTED (immutable). */
  postMovement(id: string): Observable<PostResponse | null> {
    return this.http.post<AnyRaw>(`/api/cash/movements/${id}/post`, {}).pipe(
      map(raw => ({
        id: String(raw?.['id'] ?? id),
        status: String(raw?.['status'] ?? 'POSTED'),
        fromPotBalanceAfter: raw?.['fromPotBalanceAfter'] ?? null,
      })),
      catchError(err => {
        this.toastError(err, 'Validation impossible (solde insuffisant ?)');
        return of(null);
      })
    );
  }

  /** POST /api/cash/movements/{id}/void — POSTED → VOID (reason required). */
  voidMovement(id: string, reason: string): Observable<CashMovement | null> {
    return this.http.post<AnyRaw>(`/api/cash/movements/${id}/void`, { reason }).pipe(
      map(raw => mapMovement(raw)),
      catchError(err => {
        this.toastError(err, 'Annulation impossible');
        return of(null);
      })
    );
  }

  /** GET /api/cash/movements with filters. */
  listMovements(filters: {
    potId?: string; partyId?: string; projectId?: string;
    status?: string; from?: string; to?: string;
  }): Observable<CashMovement[]> {
    let params: Record<string, string> = {};
    if (filters.potId) params['potId'] = filters.potId;
    if (filters.partyId) params['partyId'] = filters.partyId;
    if (filters.projectId) params['projectId'] = filters.projectId;
    if (filters.status) params['status'] = filters.status;
    if (filters.from) params['from'] = filters.from;
    if (filters.to) params['to'] = filters.to;

    return this.http.get<AnyRaw[]>('/api/cash/movements', { params }).pipe(
      map(list => (Array.isArray(list) ? list : []).map(mapMovement)),
      catchError(err => {
        this.toastError(err, 'Impossible de charger les opérations');
        return of([]);
      })
    );
  }

  // ── CACHE HELPERS ──────────────────────────────────────────────

  /** Get a pot by id from the cache (or fetch it if not cached). */
  potById(id: string): CashPot | undefined {
    return this.potsCache().find(p => p.id === id);
  }

  private refreshPotInCache(pot: CashPot): void {
    const list = this.potsCache();
    const idx = list.findIndex(p => p.id === pot.id);
    if (idx >= 0) {
      const copy = [...list];
      copy[idx] = pot;
      this.potsCache.set(copy);
    } else {
      this.potsCache.set([...list, pot]);
    }
  }

  /** Drop the cache (e.g. after posting a movement → balances change). */
  invalidatePots(): void {
    this.potsLoaded.set(false);
    this.loadPots(true).subscribe();
  }

  private toastError(err: any, fallback: string): void {
    this.msgs.add({
      severity: 'error',
      summary: 'Erreur',
      detail: err?.error?.message ?? err?.error?.code ?? fallback,
    });
  }
}
