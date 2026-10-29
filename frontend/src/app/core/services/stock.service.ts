import { Injectable, inject, signal, computed } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map, catchError, of } from 'rxjs';
import { MessageService } from 'primeng/api';

import {
  StockItem, ItemAlias, StockLocation, BalanceRow, OrgBalanceRow,
  StockMove, MovePage, LinkedDocument,
  CreateItemBody, UpdateItemBody, CreateAliasBody, CreateLocationBody,
  PostMoveBody, ReverseBody, LinkDocumentBody,
  mapItem, mapAlias, mapLocation, mapBalanceRow, mapOrgBalanceRow,
  mapMove, mapMovePage, mapLinkedDocument,
  StockDocKind,
} from '@app/core/models/stock.model';

// AnyRaw is `any` so the defensive mappers can use dot-notation for
// backend JSON keys without tripping noPropertyAccessFromIndexSignature.
type AnyRaw = any;

/**
 * ============================================================================
 *  StockService — wraps all /api/stock/* endpoints with defensive parsing
 *  and French error toasts (mirrors CashService conventions).
 * ============================================================================
 *  Caches items + locations in signals so the hub, list, forms, and admin
 *  pages all share the same data without refetching.
 *  Errors return `null` / `[]` and push a French toast — components never
 *  see the raw error.
 * ============================================================================
 */
@Injectable({ providedIn: 'root' })
export class StockService {
  private http = inject(HttpClient);
  private msgs = inject(MessageService);

  // ── Cache (shared across all stock pages) ─────────────────────────
  private itemsCache = signal<StockItem[]>([]);
  private itemsLoaded = signal(false);
  private locationsCache = signal<StockLocation[]>([]);
  private locationsLoaded = signal(false);

  items = computed(() => this.itemsCache());
  itemsReady = computed(() => this.itemsLoaded());
  locations = computed(() => this.locationsCache());
  locationsReady = computed(() => this.locationsLoaded());

  // ── ITEMS ──────────────────────────────────────────────────────────

  /** GET /api/stock/items?includeInactive=&q= — fills the cache. */
  loadItems(force = false, includeInactive = false, q?: string): Observable<StockItem[]> {
    if (this.itemsLoaded() && !force && !includeInactive && !q) {
      return of(this.itemsCache());
    }
    let params = new HttpParams();
    if (includeInactive) params = params.set('includeInactive', 'true');
    if (q) params = params.set('q', q);
    return this.http.get<AnyRaw[]>('/api/stock/items', { params }).pipe(
      map(list => {
        const items = (Array.isArray(list) ? list : []).map(mapItem);
        this.itemsCache.set(items);
        this.itemsLoaded.set(true);
        return items;
      }),
      catchError(err => {
        this.toastError(err, 'Impossible de charger les articles');
        this.itemsCache.set([]);
        this.itemsLoaded.set(true);
        return of([]);
      })
    );
  }

  /** POST /api/stock/items — create. */
  createItem(body: CreateItemBody): Observable<StockItem | null> {
    return this.http.post<AnyRaw>('/api/stock/items', body).pipe(
      map(raw => {
        const item = mapItem(raw);
        this.itemsCache.update(list => [...list, item]);
        return item;
      }),
      catchError(err => {
        this.toastError(err, 'Impossible de créer l\'article');
        return of(null);
      })
    );
  }

  /** PUT /api/stock/items/{id} — update name/uom/active. */
  updateItem(id: string, body: UpdateItemBody): Observable<StockItem | null> {
    return this.http.put<AnyRaw>(`/api/stock/items/${id}`, body).pipe(
      map(raw => {
        const item = mapItem(raw);
        this.itemsCache.update(list => list.map(i => i.id === id ? item : i));
        return item;
      }),
      catchError(err => {
        this.toastError(err, 'Impossible de modifier l\'article');
        return of(null);
      })
    );
  }

  // ── ALIASES ────────────────────────────────────────────────────────

  /** GET /api/stock/items/{id}/aliases. */
  listAliases(itemId: string): Observable<ItemAlias[]> {
    return this.http.get<AnyRaw[]>(`/api/stock/items/${itemId}/aliases`).pipe(
      map(list => (Array.isArray(list) ? list : []).map(mapAlias)),
      catchError(err => {
        this.toastError(err, 'Impossible de charger les alias');
        return of([]);
      })
    );
  }

  /** POST /api/stock/items/{id}/aliases. */
  createAlias(itemId: string, body: CreateAliasBody): Observable<ItemAlias | null> {
    return this.http.post<AnyRaw>(`/api/stock/items/${itemId}/aliases`, body).pipe(
      map(raw => mapAlias(raw)),
      catchError(err => {
        this.toastError(err, 'Impossible d\'ajouter l\'alias');
        return of(null);
      })
    );
  }

  /** DELETE /api/stock/items/{id}/aliases/{aliasId}. */
  deleteAlias(itemId: string, aliasId: string): Observable<boolean> {
    return this.http.delete<AnyRaw>(`/api/stock/items/${itemId}/aliases/${aliasId}`).pipe(
      map(() => true),
      catchError(err => {
        this.toastError(err, 'Impossible de supprimer l\'alias');
        return of(false);
      })
    );
  }

  // ── LOCATIONS ──────────────────────────────────────────────────────

  /** GET /api/stock/locations?projectId=&includeInactive= — fills cache. */
  loadLocations(force = false, projectId?: string, includeInactive = false): Observable<StockLocation[]> {
    if (this.locationsLoaded() && !force && !projectId && !includeInactive) {
      return of(this.locationsCache());
    }
    let params = new HttpParams();
    if (projectId) params = params.set('projectId', projectId);
    if (includeInactive) params = params.set('includeInactive', 'true');
    return this.http.get<AnyRaw[]>('/api/stock/locations', { params }).pipe(
      map(list => {
        const locs = (Array.isArray(list) ? list : []).map(mapLocation);
        if (!projectId && !includeInactive) {
          this.locationsCache.set(locs);
          this.locationsLoaded.set(true);
        }
        return locs;
      }),
      catchError(err => {
        this.toastError(err, 'Impossible de charger les emplacements');
        if (!projectId && !includeInactive) {
          this.locationsCache.set([]);
          this.locationsLoaded.set(true);
        }
        return of([]);
      })
    );
  }

  /** POST /api/stock/locations — create. */
  createLocation(body: CreateLocationBody): Observable<StockLocation | null> {
    return this.http.post<AnyRaw>('/api/stock/locations', body).pipe(
      map(raw => {
        const loc = mapLocation(raw);
        this.locationsCache.update(list => [...list, loc]);
        return loc;
      }),
      catchError(err => {
        this.toastError(err, 'Impossible de créer l\'emplacement');
        return of(null);
      })
    );
  }

  // ── BALANCES ──────────────────────────────────────────────────────

  /** GET /api/stock/balances?projectId=&locationId=&itemId=&includeZero=. */
  balances(filters: {
    projectId?: string; locationId?: string; itemId?: string; includeZero?: boolean;
  } = {}): Observable<BalanceRow[]> {
    let params = new HttpParams();
    if (filters.projectId) params = params.set('projectId', filters.projectId);
    if (filters.locationId) params = params.set('locationId', filters.locationId);
    if (filters.itemId) params = params.set('itemId', filters.itemId);
    if (filters.includeZero) params = params.set('includeZero', 'true');
    return this.http.get<AnyRaw[]>('/api/stock/balances', { params }).pipe(
      map(list => (Array.isArray(list) ? list : []).map(mapBalanceRow)),
      catchError(err => {
        this.toastError(err, 'Impossible de charger le stock');
        return of([]);
      })
    );
  }

  /** GET /api/stock/balances/summary — company-wide per-item aggregation. */
  orgSummary(): Observable<OrgBalanceRow[]> {
    return this.http.get<AnyRaw[]>('/api/stock/balances/summary').pipe(
      map(list => (Array.isArray(list) ? list : []).map(mapOrgBalanceRow)),
      catchError(err => {
        this.toastError(err, 'Synthèse du stock indisponible');
        return of([]);
      })
    );
  }

  // ── MOVES ──────────────────────────────────────────────────────────

  /** POST /api/stock/moves — post IN/OUT/TRANSFER/ADJUST/DIRECT_USE. */
  postMove(body: PostMoveBody): Observable<StockMove | null> {
    return this.http.post<AnyRaw>('/api/stock/moves', body).pipe(
      map(raw => mapMove(raw)),
      catchError(err => {
        this.toastError(err, 'Mouvement impossible');
        return of(null);
      })
    );
  }

  /** GET /api/stock/moves?itemId=&locationId=&projectId=&page=&size=. */
  history(filters: {
    itemId?: string; locationId?: string; projectId?: string;
    page?: number; size?: number;
  } = {}): Observable<MovePage | null> {
    let params = new HttpParams();
    if (filters.itemId) params = params.set('itemId', filters.itemId);
    if (filters.locationId) params = params.set('locationId', filters.locationId);
    if (filters.projectId) params = params.set('projectId', filters.projectId);
    params = params.set('page', String(filters.page ?? 0));
    params = params.set('size', String(filters.size ?? 50));
    return this.http.get<AnyRaw>('/api/stock/moves', { params }).pipe(
      map(raw => mapMovePage(raw)),
      catchError(err => {
        this.toastError(err, 'Historique indisponible');
        return of(null);
      })
    );
  }

  /** POST /api/stock/moves/{id}/reverse — creates the opposite move. */
  reverse(id: string, body: ReverseBody): Observable<StockMove | null> {
    return this.http.post<AnyRaw>(`/api/stock/moves/${id}/reverse`, body ?? {}).pipe(
      map(raw => mapMove(raw)),
      catchError(err => {
        this.toastError(err, 'Inversion impossible');
        return of(null);
      })
    );
  }

  /** POST /api/stock/moves/{id}/documents — link an existing document. */
  linkDocument(moveId: string, body: LinkDocumentBody): Observable<boolean> {
    return this.http.post<AnyRaw>(`/api/stock/moves/${moveId}/documents`, body).pipe(
      map(() => true),
      catchError(err => {
        this.toastError(err, 'Impossible de lier le document');
        return of(false);
      })
    );
  }

  /** GET /api/stock/moves/{id}/documents — primary doc + junction docs. */
  documentsOf(moveId: string): Observable<LinkedDocument[]> {
    return this.http.get<AnyRaw[]>(`/api/stock/moves/${moveId}/documents`).pipe(
      map(list => (Array.isArray(list) ? list : []).map(mapLinkedDocument)),
      catchError(err => {
        this.toastError(err, 'Pièces jointes indisponibles');
        return of([]);
      })
    );
  }

  // ── DOCUMENT FOLDER HOOK (non-breaking) ────────────────────────────

  /**
   * GET /api/stock/doc-folder?projectId=&kind=BL|BC|FACTURE|AUTRE — resolve
   * (or create) the stock subfolder under the project's "05 ACHAT" folder.
   * Returns {folderId, code, created}. Pass folderId to the EXISTING
   * document upload API — nothing in the upload path is modified.
   */
  ensureStockFolder(projectId: string, kind: StockDocKind): Observable<{ folderId: string; code: string; created: boolean } | null> {
    const params = new HttpParams().set('projectId', projectId).set('kind', kind);
    return this.http.get<AnyRaw>('/api/stock/doc-folder', { params }).pipe(
      map(raw => ({
        folderId: String(raw?.folderId ?? raw?.folder_id ?? ''),
        code: String(raw?.code ?? ''),
        created: Boolean(raw?.created ?? false),
      })),
      catchError(err => {
        this.toastError(err, 'Dossier stock indisponible');
        return of(null);
      })
    );
  }

  // ── CACHE HELPERS ─────────────────────────────────────────────────

  /** Invalidate both caches (after a heavy mutation). */
  invalidateAll(): void {
    this.itemsLoaded.set(false);
    this.locationsLoaded.set(false);
    this.loadItems(true).subscribe();
    this.loadLocations(true).subscribe();
  }

  /** Find a location in the cache by id. */
  locationById(id: string): StockLocation | undefined {
    return this.locationsCache().find(l => l.id === id);
  }

  /** Find an item in the cache by id. */
  itemById(id: string): StockItem | undefined {
    return this.itemsCache().find(i => i.id === id);
  }

  private toastError(err: any, fallback: string): void {
    this.msgs.add({
      severity: 'error',
      summary: 'Erreur',
      detail: err?.error?.message ?? err?.error?.detail ?? err?.error?.code ?? fallback,
    });
  }
}
