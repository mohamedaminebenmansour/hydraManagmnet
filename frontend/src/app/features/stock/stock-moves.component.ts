import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { SelectModule } from 'primeng/select';
import { DialogModule } from 'primeng/dialog';
import { TooltipModule } from 'primeng/tooltip';

import { StockService } from '@app/core/services/stock.service';
import { DocumentPickerDialogComponent } from './document-picker-dialog.component';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { MessageService } from 'primeng/api';
import {
  StockMove, MovePage, LinkedDocument,
  moveTypeVisual, formatQty, formatDateTime, formatDate,
} from '@app/core/models/stock.model';

/**
 * StockMovesComponent — /stock/mouvements
 *
 * History of every stock move (append-only ledger — reverse creates an
 * opposite move, never edits or deletes). Table-first (Excel mental model).
 *
 * Filters: project (proxy = location), item, location.
 * Pagination: prev/next buttons on top of the page (size = 50).
 * Per-row actions: reverse (with reason dialog), view linked documents.
 */
@Component({
  selector: 'app-stock-moves',
  standalone: true,
  imports: [FormsModule, RouterLink, ButtonModule, SelectModule, DialogModule, TooltipModule, DocumentPickerDialogComponent],
  template: `
    <section class="sm">
      <header class="sm-head">
        <div>
          <h1><i class="pi pi-history"></i> Historique des mouvements</h1>
          <p>Toutes les entrées, sorties, transferts et ajustements — journal append-only.</p>
        </div>
        <div class="head-actions">
          <a class="back-link" [routerLink]="'/stock'"><i class="pi pi-arrow-left"></i> Hub stock</a>
          @if (canMove()) {
            <button pButton label="Nouveau mouvement" icon="pi pi-plus" severity="success"
                    [routerLink]="'/stock/mouvements/new'"></button>
          }
        </div>
      </header>

      <!-- Filters -->
      <div class="sm-filters">
        <p-select [options]="itemOptions()" [(ngModel)]="itemFilter"
                  placeholder="Tous les articles" [showClear]="true"
                  optionLabel="name" optionValue="id"
                  (onChange)="reload()" styleClass="filter-sel" />
        <p-select [options]="locationOptions()" [(ngModel)]="locationFilter"
                  placeholder="Tous les emplacements" [showClear]="true"
                  optionLabel="label" optionValue="id"
                  (onChange)="reload()" styleClass="filter-sel" />
        <div class="pager">
          <button pButton icon="pi pi-angle-left" [text]="true" [disabled]="page() === 0"
                  (click)="prevPage()" pTooltip="Page précédente" tooltipPosition="top"></button>
          <span class="pager-info">Page {{ page() + 1 }} · {{ total() }} mvt(s)</span>
          <button pButton icon="pi pi-angle-right" [text]="true"
                  [disabled]="(page() + 1) * pageSize >= total()"
                  (click)="nextPage()" pTooltip="Page suivante" tooltipPosition="top"></button>
        </div>
      </div>

      @if (loading()) {
        <div class="sm-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else if (moves().length === 0) {
        <div class="sm-empty">
          <i class="pi pi-history"></i>
          <h3>Aucun mouvement</h3>
          <p>Les entrées et sorties de stock apparaîtront ici.</p>
          @if (canMove()) {
            <button pButton label="Saisir une entrée" icon="pi pi-arrow-down" severity="success"
                    [routerLink]="'/stock/mouvements/new?type=IN'"></button>
          }
        </div>
      } @else {
        <div class="sm-table-wrap">
          <table class="sm-table">
            <thead>
              <tr>
                <th>Date</th>
                <th>Type</th>
                <th>Article</th>
                <th class="num">Qté</th>
                <th>De → Vers</th>
                <th>Par</th>
                <th>Pièce</th>
                <th>Note</th>
                @if (canMove()) { <th class="col-actions">Actions</th> }
              </tr>
            </thead>
            <tbody>
              @for (m of moves(); track m.id) {
                <tr>
                  <td class="c-date">{{ formatDateTime(m.createdAt) }}</td>
                  <td>
                    <span class="type-chip" [style.--mt-color]="mtv(m.moveType).color"
                          [style.--mt-soft]="mtv(m.moveType).soft">
                      <i class="pi" [class]="mtv(m.moveType).icon"></i>
                      {{ mtv(m.moveType).label }}
                    </span>
                  </td>
                  <td>
                    <div class="c-item">{{ m.itemName }}</div>
                    <div class="c-item-code">{{ m.uom }}</div>
                  </td>
                  <td class="num strong" [style.color]="mtv(m.moveType).color">
                    {{ m.moveType === 'ADJUST' && m.qty < 0 ? '−' : '' }}{{ formatQty(abs(m.qty)) }}
                  </td>
                  <td>
                    @if (m.moveType === 'IN' || m.moveType === 'TRANSFER') {
                      <span class="loc-arrow">
                        @if (m.fromLocationName) {
                          <span class="loc-from">{{ m.fromLocationName }}</span>
                          <i class="pi pi-arrow-right"></i>
                        }
                        <span class="loc-to">{{ m.toLocationName }}</span>
                      </span>
                    } @else {
                      <span class="loc-from">{{ m.fromLocationName ?? '—' }}</span>
                      @if (m.blLineId) { <span class="via-bl-badge" pTooltip="Mouvement issu d une réception fournisseur (BL)" tooltipPosition="left"><i class="pi pi-truck"></i> Via BL</span> }
                    }
                  </td>
                  <td class="c-by">{{ m.createdByName ?? '—' }}</td>
                  <td>
                    @if ((m.linkedDocCount ?? 0) > 0 || m.documentId) {
                      <button pButton [text]="true" icon="pi pi-paperclip" severity="info"
                              [label]="((m.linkedDocCount ?? 0) + (m.documentId ? 1 : 0)).toString()"
                              pTooltip="Voir les pièces jointes" tooltipPosition="left"
                              (click)="showDocs(m)"></button>
                    } @else {
                      <span class="c-nodoc" pTooltip="Aucun justificatif" tooltipPosition="left">
                        <i class="pi pi-minus-circle"></i>
                      </span>
                    }
                  </td>
                  <td class="c-note">{{ m.note ?? '—' }}</td>
                  @if (canMove()) {
                    <td class="col-actions">
                      <button pButton icon="pi pi-undo" [text]="true" severity="warn"
                              pTooltip="Inverser ce mouvement" tooltipPosition="left"
                              (click)="openReverse(m)"></button>
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
      }

      <!-- Reverse dialog -->
      <p-dialog header="Inverser le mouvement" [(visible)]="reverseVisible" [modal]="true"
                [style]="{ width: '460px' }">
        @if (reverseTarget()) {
          <div class="rev-summary">
            <div class="rev-row">
              <span class="rev-label">Type original :</span>
              <span class="type-chip" [style.--mt-color]="mtv(reverseTarget()!.moveType).color"
                    [style.--mt-soft]="mtv(reverseTarget()!.moveType).soft">
                <i class="pi" [class]="mtv(reverseTarget()!.moveType).icon"></i>
                {{ mtv(reverseTarget()!.moveType).label }}
              </span>
            </div>
            <div class="rev-row">
              <span class="rev-label">Article :</span>
              <span>{{ reverseTarget()!.itemName }} ({{ formatQty(reverseTarget()!.qty, reverseTarget()!.uom) }})</span>
            </div>
            <div class="rev-row">
              <span class="rev-label">De :</span>
              <span>{{ reverseTarget()!.fromLocationName ?? '—' }}</span>
            </div>
            <div class="rev-row">
              <span class="rev-label">Vers :</span>
              <span>{{ reverseTarget()!.toLocationName ?? '—' }}</span>
            </div>
          </div>
          <div class="rev-hint">
            <i class="pi pi-info-circle"></i>
            L'inversion crée un <strong>mouvement opposé</strong> dans le journal append-only.
            L'original n'est jamais modifié ni supprimé.
          </div>
          <div class="field">
            <label>Raison <span class="req">*</span></label>
            <textarea pInputText [(ngModel)]="reverseReason"
                      placeholder="Ex : erreur de saisie, double entrée…"
                      rows="3" style="width: 100%;"></textarea>
          </div>
        }
        <ng-template pTemplate="footer">
          <p-button label="Annuler" [text]="true" (onClick)="reverseVisible.set(false)" />
          <p-button label="Confirmer l'inversion" icon="pi pi-undo" severity="warn"
                    [disabled]="!reverseReason.trim()" [loading]="reversing()"
                    (onClick)="confirmReverse()" />
        </ng-template>
      </p-dialog>

      <!-- Documents dialog -->
      <p-dialog header="Pièces jointes" [(visible)]="docsVisible" [modal]="true"
                [style]="{ width: '520px' }">
        @if (docsLoading()) {
          <div class="docs-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
        } @else if (linkedDocs().length === 0) {
          <div class="docs-empty">
            <i class="pi pi-paperclip"></i>
            <p>Aucune pièce jointe pour ce mouvement.</p>
          </div>
        } @else {
          <ul class="docs-list">
            @for (d of linkedDocs(); track d.id) {
              <li>
                <a [routerLink]="'/documents/' + d.id" class="doc-link">
                  <i class="pi pi-file-o"></i>
                  <div class="doc-info">
                    <div class="doc-title">{{ d.title ?? d.reference ?? d.id }}</div>
                    @if (d.reference) { <div class="doc-ref">Réf : {{ d.reference }}</div> }
                    @if (d.docType) { <div class="doc-type">{{ d.docType }}</div> }
                  </div>
                  @if (d.isPrimary) {
                    <span class="doc-primary">Pièce principale</span>
                  }
                </a>
              </li>
            }
          </ul>
        }
        @if (canMove()) {
          <div class="dialog-add-action">
            <button pButton label="Ajouter un justificatif" icon="pi pi-plus"
                    severity="success" [text]="true"
                    (click)="openAddDocPicker()"></button>
          </div>
        }
        <ng-template pTemplate="footer">
          <p-button label="Fermer" [text]="true" (onClick)="docsVisible.set(false)" />
        </ng-template>
      </p-dialog>

      <!-- Document picker for attaching an existing doc to a move (junction link) -->
      @if (docsTargetMove()) {
        <app-document-picker-dialog [projectId]="docsTargetMove()!.projectId ?? ''"
                                    [(visible)]="docPickerVisible"
                                    (picked)="onAddDocPicked($event)" />
      }
    </section>
  `,
  styles: [`
    .sm { display: flex; flex-direction: column; gap: 18px; }

    .sm-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; }
    .sm-head h1 { margin: 0 0 4px; font-size: 22px; font-weight: 800; display: flex; align-items: center; gap: 10px; }
    .sm-head h1 i { color: #0d9488; }
    .sm-head p { margin: 0; color: #64748b; font-size: 14px; }
    .head-actions { display: flex; align-items: center; gap: 12px; }
    .back-link { color: #475569; text-decoration: none; font-size: 13px; font-weight: 600;
      display: inline-flex; align-items: center; gap: 6px; }
    .back-link:hover { color: #0d9488; }

    .sm-filters {
      display: flex; gap: 10px; flex-wrap: wrap; align-items: center;
      background: #fff; border: 1px solid #e2e8f0; border-radius: 12px;
      padding: 10px 12px;
    }
    :host ::ng-deep .filter-sel { min-width: 200px; }
    .pager {
      margin-left: auto; display: flex; align-items: center; gap: 6px;
      font-size: 13px; color: #64748b;
    }
    .pager-info { font-weight: 600; padding: 0 6px; }

    /* Table */
    .sm-table-wrap { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden; }
    .sm-table { width: 100%; border-collapse: collapse; font-size: 14px; }
    .sm-table th {
      background: #f8fafc; color: #64748b; font-weight: 600; font-size: 12px;
      text-transform: uppercase; letter-spacing: .04em;
      text-align: left; padding: 12px 14px; border-bottom: 1px solid #e2e8f0;
    }
    .sm-table th.num { text-align: right; }
    .sm-table th.col-actions { text-align: center; width: 80px; }
    .sm-table td {
      padding: 11px 14px; border-bottom: 1px solid #f1f5f9; vertical-align: middle;
    }
    .sm-table td.num { text-align: right; font-variant-numeric: tabular-nums; }
    .sm-table td.strong { font-weight: 700; font-size: 15px; }
    .sm-table td.col-actions { text-align: center; white-space: nowrap; }
    .sm-table tr:last-child td { border-bottom: none; }
    .sm-table tr:hover td { background: #f8fafc; }

    .c-date { font-size: 13px; color: #475569; white-space: nowrap; }
    .c-item { font-weight: 600; }
    .c-item-code { font-size: 12px; color: #94a3b8; }
    .c-by { font-size: 13px; color: #64748b; }
    .c-note { font-size: 13px; color: #64748b; max-width: 220px; overflow: hidden;
      text-overflow: ellipsis; white-space: nowrap; }
    .c-nodoc { color: #cbd5e1; }

    .type-chip {
      display: inline-flex; align-items: center; gap: 6px;
      padding: 4px 10px; border-radius: 999px;
      font-size: 12px; font-weight: 700;
      background: var(--mt-soft, #f0fdf4);
      color: var(--mt-color, #16a34a);
    }
    .loc-arrow { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; }
    .loc-from { color: #94a3b8; }
    .loc-to { color: #0f172a; font-weight: 600; }
    .loc-arrow i { font-size: 10px; color: #94a3b8; }

    /* Reverse dialog */
    .rev-summary {
      background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px;
      padding: 12px 14px; display: flex; flex-direction: column; gap: 8px; margin-bottom: 12px;
    }
    .rev-row { display: flex; align-items: center; gap: 8px; font-size: 14px; }
    .rev-label { color: #64748b; min-width: 100px; font-weight: 600; }
    .rev-hint {
      background: #fffbeb; border: 1px solid #fcd34d; border-radius: 8px;
      padding: 10px 12px; font-size: 13px; color: #92400e;
      display: flex; align-items: flex-start; gap: 8px; margin-bottom: 14px;
    }
    .rev-hint i { color: #d97706; margin-top: 2px; }
    .field { display: flex; flex-direction: column; gap: 6px; }
    .field label { font-size: 13px; font-weight: 600; color: #475569; }
    .field .req { color: #dc2626; }
    .field textarea { resize: vertical; }

    /* Documents dialog */
    .docs-list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 8px; }
    .docs-list li { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; }
    .doc-link {
      display: flex; align-items: center; gap: 12px;
      padding: 10px 12px; text-decoration: none; color: #0f172a;
    }
    .doc-link i.pi-file-o { font-size: 18px; color: #0d9488; }
    .doc-info { flex: 1; min-width: 0; }
    .doc-title { font-weight: 600; font-size: 14px; }
    .doc-ref, .doc-type { font-size: 12px; color: #64748b; }
    .doc-primary {
      font-size: 11px; font-weight: 700; color: #b45309;
      background: #fffbeb; padding: 2px 8px; border-radius: 999px;
    }
    .docs-loading, .docs-empty {
      text-align: center; padding: 30px 20px; color: #64748b;
      display: flex; flex-direction: column; align-items: center; gap: 8px;
    }
    .dialog-add-action {
      padding: 8px 0 12px;
      border-top: 1px solid #f1f5f9;
      margin-top: 8px;
    }
    .c-nodoc i { font-size: 12px; }
    .via-bl-badge { display: inline-flex; align-items: center; gap: 3px; margin-left: 6px; padding: 2px 6px; border-radius: 999px; font-size: 11px; font-weight: 600; background: #ecfeff; color: #0891b2; border: 1px solid #cffafe; }

    /* States */
    .sm-loading, .sm-empty {
      background: #fff; border: 1px solid #e2e8f0; border-radius: 12px;
      padding: 50px 20px; text-align: center; color: #64748b;
      display: flex; flex-direction: column; align-items: center; gap: 10px;
    }
    .sm-loading i, .sm-empty i { font-size: 2.4rem; color: #94a3b8; }
    .sm-empty h3 { margin: 0; }
    .sm-empty p { margin: 0 0 6px; }
  `]
})
export class StockMovesComponent implements OnInit {
  private stock = inject(StockService);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  canMove = computed(() => this.auth.can(PERM.STOCK_MOVE) || !!this.auth.me()?.isOwner);

  loading = signal(true);
  private pageData = signal<MovePage | null>(null);
  moves = computed<StockMove[]>(() => this.pageData()?.items ?? []);
  total = computed(() => this.pageData()?.total ?? 0);
  page = signal(0);
  pageSize = 50;

  itemFilter: string | null = null;
  locationFilter: string | null = null;

  // Reverse dialog state
  reverseVisible = signal(false);
  reverseTarget = signal<StockMove | null>(null);
  reverseReason = '';
  reversing = signal(false);

  // Documents dialog state
  docsVisible = signal(false);
  docsLoading = signal(false);
  linkedDocs = signal<LinkedDocument[]>([]);

  // Add-document picker state (for linking an existing doc to a move via junction)
  docPickerVisible = signal(false);
  docsTargetMove = signal<StockMove | null>(null);

  // Dropdown options
  itemOptions = computed(() => this.stock.items().map(i => ({ id: i.id, name: i.name })));
  locationOptions = computed(() => this.stock.locations().map(l => ({
    id: l.id, label: `${l.name}${l.type === 'CENTRAL' ? ' (central)' : ''}`,
  })));

  // Visual helpers
  mtv = moveTypeVisual;
  abs = (n: number | null | undefined): number => Math.abs(Number(n ?? 0));
  formatQty = formatQty;
  formatDateTime = formatDateTime;
  formatDate = formatDate;

  ngOnInit(): void {
    this.stock.loadItems().subscribe();
    this.stock.loadLocations().subscribe();
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.stock.history({
      itemId: this.itemFilter ?? undefined,
      locationId: this.locationFilter ?? undefined,
      page: this.page(),
      size: this.pageSize,
    }).subscribe(p => {
      this.pageData.set(p);
      this.loading.set(false);
      // No N+1: linkedDocCount comes from the enriched history() SQL response.
    });
  }

  prevPage(): void {
    if (this.page() > 0) {
      this.page.set(this.page() - 1);
      this.reload();
    }
  }

  nextPage(): void {
    if ((this.page() + 1) * this.pageSize < this.total()) {
      this.page.set(this.page() + 1);
      this.reload();
    }
  }

  openReverse(m: StockMove): void {
    this.reverseTarget.set(m);
    this.reverseReason = '';
    this.reverseVisible.set(true);
  }

  confirmReverse(): void {
    const target = this.reverseTarget();
    if (!target || !this.reverseReason.trim()) return;
    this.reversing.set(true);
    this.stock.reverse(target.id, { reason: this.reverseReason.trim() }).subscribe(reversed => {
      this.reversing.set(false);
      if (reversed) {
        this.reverseVisible.set(false);
        this.reload();
      }
    });
  }

  /** Show linked documents for a move (fetches lazily). */
  showDocs(m: StockMove): void {
    this.docsTargetMove.set(m);
    this.linkedDocs.set([]);
    this.docsVisible.set(true);
    this.docsLoading.set(true);
    this.stock.documentsOf(m.id).subscribe(docs => {
      this.linkedDocs.set(docs);
      this.docsLoading.set(false);
    });
  }

  /**
   * Open the document picker to attach an existing doc to the move currently
   * shown in the docs dialog. Requires STOCK_MOVE perm (UI-gated) and a
   * projectId on the move (SITE moves have one; CENTRAL moves may not).
   */
  openAddDocPicker(): void {
    const m = this.docsTargetMove();
    if (!m?.projectId) {
      this.msgs.add({
        severity: 'warn', summary: 'Projet requis',
        detail: 'Ce mouvement n\'est pas rattaché à un projet. Impossible d\'ajouter un justificatif ici.',
      });
      return;
    }
    this.docPickerVisible.set(true);
  }

  /** Called when the picker emits a selected documentId → link via junction. */
  onAddDocPicked(docId: string): void {
    const m = this.docsTargetMove();
    if (!m) return;
    this.stock.linkDocument(m.id, { documentId: docId }).subscribe(ok => {
      if (ok) {
        this.msgs.add({
          severity: 'success', summary: 'Justificatif lié',
          detail: 'Le document a été rattaché au mouvement.',
        });
        // Refresh the docs list so the new link appears immediately
        this.showDocs(m);
      }
    });
  }
}
