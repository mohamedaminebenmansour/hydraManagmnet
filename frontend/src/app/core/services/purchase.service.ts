import { Injectable, inject, signal, computed } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, map, catchError, of } from 'rxjs';
import { MessageService } from 'primeng/api';

import {
  PurchaseOrder, PurchaseOrderLine, BonLivraison, BonLivraisonLine,
  BcLineMatchRow, MatchPage,
  Facture, FactureLine,
  mapFacture, mapFactureLine,
  mapPurchaseOrder, mapPurchaseOrderLine, mapBonLivraison, mapBonLivraisonLine, mapBcLineMatchRow,
} from '@app/core/models/stock.model';

type AnyRaw = any;

/**
 * ============================================================================
 *  PurchaseService — wraps /api/purchase-orders/* + /api/bons-livraison/* + /match
 *  Mirrors StockService conventions: signal caches + French error toasts.
 * ============================================================================
 */
@Injectable({ providedIn: 'root' })
export class PurchaseService {
  private http = inject(HttpClient);
  private msgs = inject(MessageService);

  // ── BC cache ────────────────────────────────────────────────────────
  private bcsCache = signal<PurchaseOrder[]>([]);
  private bcsLoaded = signal(false);
  bcs = computed(() => this.bcsCache());
  bcsReady = computed(() => this.bcsLoaded());

  // ── BL cache ────────────────────────────────────────────────────────
  private blsCache = signal<BonLivraison[]>([]);
  private blsLoaded = signal(false);
  bls = computed(() => this.blsCache());
  blsReady = computed(() => this.blsLoaded());

  // ════════════════════════════════════════════════════════════════════
  //  BONS DE COMMANDE (BC)
  // ════════════════════════════════════════════════════════════════════

  loadBcs(force = false, projectId?: string, status?: string): Observable<PurchaseOrder[]> {
    if (this.bcsLoaded() && !force && !projectId && !status) return of(this.bcsCache());
    let params = new HttpParams();
    if (projectId) params = params.set('projectId', projectId);
    if (status) params = params.set('status', status);
    return this.http.get<AnyRaw[]>('/api/purchase-orders', { params }).pipe(
      map(list => {
        const bcs = (Array.isArray(list) ? list : []).map(mapPurchaseOrder);
        if (!projectId && !status) { this.bcsCache.set(bcs); this.bcsLoaded.set(true); }
        return bcs;
      }),
      catchError(err => { this.toastError(err, 'Impossible de charger les bons de commande'); return of([]); })
    );
  }

  getBc(id: string): Observable<PurchaseOrder | null> {
    return this.http.get<AnyRaw>(`/api/purchase-orders/${id}`).pipe(
      map(raw => mapPurchaseOrder(raw)),
      catchError(err => { this.toastError(err, 'Bon de commande introuvable'); return of(null); })
    );
  }

  createBc(body: any): Observable<PurchaseOrder | null> {
    return this.http.post<AnyRaw>('/api/purchase-orders', body).pipe(
      map(raw => { const bc = mapPurchaseOrder(raw); this.bcsCache.update(l => [...l, bc]); return bc; }),
      catchError(err => { this.toastError(err, 'Impossible de créer le bon de commande'); return of(null); })
    );
  }

  updateBc(id: string, body: any): Observable<PurchaseOrder | null> {
    return this.http.patch<AnyRaw>(`/api/purchase-orders/${id}`, body).pipe(
      map(raw => mapPurchaseOrder(raw)),
      catchError(err => { this.toastError(err, 'Impossible de modifier le bon de commande'); return of(null); })
    );
  }

  confirmBc(id: string): Observable<PurchaseOrder | null> {
    return this.http.post<AnyRaw>(`/api/purchase-orders/${id}/confirm`, {}).pipe(
      map(raw => mapPurchaseOrder(raw)),
      catchError(err => { this.toastError(err, 'Confirmation impossible'); return of(null); })
    );
  }

  cancelBc(id: string, reason?: string): Observable<PurchaseOrder | null> {
    return this.http.post<AnyRaw>(`/api/purchase-orders/${id}/cancel`, { reason: reason ?? null }).pipe(
      map(raw => mapPurchaseOrder(raw)),
      catchError(err => { this.toastError(err, 'Annulation impossible'); return of(null); })
    );
  }

  /** Part 4.3 — Close a CONFIRMED BC when all lines MATCHED. Optional force (OWNER/STOCK_ADMIN). */
  closeBc(id: string, force?: boolean, reason?: string): Observable<PurchaseOrder | null> {
    let params = new HttpParams();
    if (force) params = params.set('force', 'true');
    return this.http.post<AnyRaw>(`/api/purchase-orders/${id}/close`, { reason: reason ?? null }, { params }).pipe(
      map(raw => mapPurchaseOrder(raw)),
      catchError(err => { this.toastError(err, 'Clôture impossible'); return of(null); })
    );
  }

  addBcLine(bcId: string, body: any): Observable<PurchaseOrderLine | null> {
    return this.http.post<AnyRaw>(`/api/purchase-orders/${bcId}/lines`, body).pipe(
      map(raw => mapPurchaseOrderLine(raw)),
      catchError(err => { this.toastError(err, 'Impossible d\'ajouter la ligne'); return of(null); })
    );
  }

  updateBcLine(bcId: string, lineId: string, body: any): Observable<PurchaseOrderLine | null> {
    return this.http.patch<AnyRaw>(`/api/purchase-orders/${bcId}/lines/${lineId}`, body).pipe(
      map(raw => mapPurchaseOrderLine(raw)),
      catchError(err => { this.toastError(err, 'Impossible de modifier la ligne'); return of(null); })
    );
  }

  removeBcLine(bcId: string, lineId: string): Observable<boolean> {
    return this.http.delete<AnyRaw>(`/api/purchase-orders/${bcId}/lines/${lineId}`).pipe(
      map(() => true),
      catchError(err => { this.toastError(err, 'Impossible de supprimer la ligne'); return of(false); })
    );
  }

  // ════════════════════════════════════════════════════════════════════
  //  BONS DE LIVRAISON (BL)
  // ════════════════════════════════════════════════════════════════════

  loadBls(force = false, projectId?: string, bcId?: string, status?: string): Observable<BonLivraison[]> {
    if (this.blsLoaded() && !force && !projectId && !bcId && !status) return of(this.blsCache());
    let params = new HttpParams();
    if (projectId) params = params.set('projectId', projectId);
    if (bcId) params = params.set('bcId', bcId);
    if (status) params = params.set('status', status);
    return this.http.get<AnyRaw[]>('/api/bons-livraison', { params }).pipe(
      map(list => {
        const bls = (Array.isArray(list) ? list : []).map(mapBonLivraison);
        if (!projectId && !bcId && !status) { this.blsCache.set(bls); this.blsLoaded.set(true); }
        return bls;
      }),
      catchError(err => { this.toastError(err, 'Impossible de charger les bons de livraison'); return of([]); })
    );
  }

  getBl(id: string): Observable<BonLivraison | null> {
    return this.http.get<AnyRaw>(`/api/bons-livraison/${id}`).pipe(
      map(raw => mapBonLivraison(raw)),
      catchError(err => { this.toastError(err, 'Bon de livraison introuvable'); return of(null); })
    );
  }

  createBl(body: any): Observable<BonLivraison | null> {
    return this.http.post<AnyRaw>('/api/bons-livraison', body).pipe(
      map(raw => { const bl = mapBonLivraison(raw); this.blsCache.update(l => [...l, bl]); return bl; }),
      catchError(err => { this.toastError(err, 'Impossible de créer le bon de livraison'); return of(null); })
    );
  }

  addBlLine(blId: string, body: any): Observable<BonLivraisonLine | null> {
    return this.http.post<AnyRaw>(`/api/bons-livraison/${blId}/lines`, body).pipe(
      map(raw => mapBonLivraisonLine(raw)),
      catchError(err => { this.toastError(err, 'Impossible d\'ajouter la ligne'); return of(null); })
    );
  }

  updateBlLine(blId: string, lineId: string, body: any): Observable<BonLivraisonLine | null> {
    return this.http.patch<AnyRaw>(`/api/bons-livraison/${blId}/lines/${lineId}`, body).pipe(
      map(raw => mapBonLivraisonLine(raw)),
      catchError(err => { this.toastError(err, 'Impossible de modifier la ligne'); return of(null); })
    );
  }

  removeBlLine(blId: string, lineId: string): Observable<boolean> {
    return this.http.delete<AnyRaw>(`/api/bons-livraison/${blId}/lines/${lineId}`).pipe(
      map(() => true),
      catchError(err => { this.toastError(err, 'Impossible de supprimer la ligne'); return of(false); })
    );
  }

  postBl(id: string): Observable<BonLivraison | null> {
    return this.http.post<AnyRaw>(`/api/bons-livraison/${id}/post`, {}).pipe(
      map(raw => mapBonLivraison(raw)),
      catchError(err => { this.toastError(err, 'Réception impossible — vérifiez les quantités'); return of(null); })
    );
  }

  cancelBl(id: string, reason?: string): Observable<BonLivraison | null> {
    return this.http.post<AnyRaw>(`/api/bons-livraison/${id}/cancel`, { reason: reason ?? null }).pipe(
      map(raw => mapBonLivraison(raw)),
      catchError(err => { this.toastError(err, 'Annulation impossible'); return of(null); })
    );
  }

  linkBlDocument(blId: string, documentId: string): Observable<boolean> {
    return this.http.post<AnyRaw>(`/api/bons-livraison/${blId}/documents`, { documentId }).pipe(
      map(() => true),
      catchError(err => { this.toastError(err, 'Impossible de lier le document'); return of(false); })
    );
  }

  // ════════════════════════════════════════════════════════════════════
  //  MATCH (Part 2.3 — read-only)
  // ════════════════════════════════════════════════════════════════════

  matchOverview(projectId?: string, page = 0, size = 50): Observable<MatchPage | null> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (projectId) params = params.set('projectId', projectId);
    return this.http.get<AnyRaw>('/api/purchase-orders/match', { params }).pipe(
      map(raw => ({
        items: (Array.isArray(raw?.items) ? raw.items : []).map(mapBcLineMatchRow),
        total: Number(raw?.total ?? 0), page: Number(raw?.page ?? 0), size: Number(raw?.size ?? size),
      })),
      catchError(err => { this.toastError(err, 'Rapprochement indisponible'); return of(null); })
    );
  }

  matchForBc(bcId: string): Observable<BcLineMatchRow[]> {
    return this.http.get<AnyRaw[]>(`/api/purchase-orders/${bcId}/match`).pipe(
      map(list => (Array.isArray(list) ? list : []).map(mapBcLineMatchRow)),
      catchError(err => { this.toastError(err, 'Rapprochement indisponible'); return of([]); })
    );
  }

  // ── HELPERS ────────────────────────────────────────────────────────
  private toastError(err: any, fallback: string): void {
    this.msgs.add({
      severity: 'error', summary: 'Erreur',
      detail: err?.error?.message ?? err?.error?.detail ?? err?.error?.code ?? fallback,
    });
  }


  // ════════════════════════════════════════════════════════════════════
  //  FACTURES (Part 3.1+3.4)
  // ════════════════════════════════════════════════════════════════════

  loadFactures(force = false, projectId?: string, status?: string): Observable<Facture[]> {
    let params = new HttpParams();
    if (projectId) params = params.set('projectId', projectId);
    if (status) params = params.set('status', status);
    return this.http.get<AnyRaw[]>('/api/factures', { params }).pipe(
      map(list => (Array.isArray(list) ? list : []).map(mapFacture)),
      catchError(err => { this.toastError(err, 'Impossible de charger les factures'); return of([]); })
    );
  }

  getFacture(id: string): Observable<Facture | null> {
    return this.http.get<AnyRaw>(`/api/factures/${id}`).pipe(
      map(raw => mapFacture(raw)),
      catchError(err => { this.toastError(err, 'Facture introuvable'); return of(null); })
    );
  }

  createFacture(body: any): Observable<Facture | null> {
    return this.http.post<AnyRaw>('/api/factures', body).pipe(
      map(raw => mapFacture(raw)),
      catchError(err => { this.toastError(err, 'Impossible de créer la facture'); return of(null); })
    );
  }

  updateFacture(id: string, body: any): Observable<Facture | null> {
    return this.http.patch<AnyRaw>(`/api/factures/${id}`, body).pipe(
      map(raw => mapFacture(raw)),
      catchError(err => { this.toastError(err, 'Impossible de modifier la facture'); return of(null); })
    );
  }

  validateFacture(id: string): Observable<Facture | null> {
    return this.http.post<AnyRaw>(`/api/factures/${id}/validate`, {}).pipe(
      map(raw => mapFacture(raw)),
      catchError(err => { this.toastError(err, 'Validation impossible'); return of(null); })
    );
  }

  cancelFacture(id: string, reason?: string): Observable<Facture | null> {
    return this.http.post<AnyRaw>(`/api/factures/${id}/cancel`, { reason: reason ?? null }).pipe(
      map(raw => mapFacture(raw)),
      catchError(err => { this.toastError(err, 'Annulation impossible'); return of(null); })
    );
  }

  addFactureLine(factureId: string, body: any): Observable<any | null> {
    return this.http.post<AnyRaw>(`/api/factures/${factureId}/lines`, body).pipe(
      map(raw => raw),
      catchError(err => { this.toastError(err, 'Impossible d\'ajouter la ligne'); return of(null); })
    );
  }

  linkFactureDocument(factureId: string, documentId: string): Observable<boolean> {
    return this.http.post<AnyRaw>(`/api/factures/${factureId}/documents`, { documentId }).pipe(
      map(() => true),
      catchError(err => { this.toastError(err, 'Impossible de lier le document'); return of(false); })
    );
  }
}
