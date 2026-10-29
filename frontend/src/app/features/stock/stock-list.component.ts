import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TooltipModule } from 'primeng/tooltip';

import { StockService } from '@app/core/services/stock.service';
import { ProjectService } from '@app/core/services/project.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { ViewModeService } from '@app/core/view-mode.service';
import {
  BalanceRow, StockLocation, stockLevelVisual, locationTypeVisual, formatQty,
} from '@app/core/models/stock.model';

interface ProjectOption { id: string; name: string; }

/**
 * StockListComponent — /stock/stocks
 *
 * The "Stock overview" page. Default TABLE (Excel mental model — matches
 * the rest of HYDRA where every list view is a hand-built HTML table).
 * Toggle to CARDS for a per-item visual summary.
 *
 * Filters: project, location, search by item name.
 * Totals chip: count of articles, emplacements, and sum of qtys in the
 * current filter scope.
 *
 * Each row has a quick "Nouveau mouvement" action that preselects the
 * item + location in the move form.
 */
@Component({
  selector: 'app-stock-list',
  standalone: true,
  imports: [FormsModule, RouterLink, ButtonModule, InputTextModule, SelectModule, TooltipModule],
  template: `
    <section class="sl">
      <header class="sl-head">
        <div>
          <h1><i class="pi pi-box"></i> Stock</h1>
          <p>Suivi du stock par article et emplacement.</p>
        </div>
        <div class="head-actions">
          <a class="back-link" [routerLink]="'/stock'"><i class="pi pi-arrow-left"></i> Hub stock</a>
          @if (canMove()) {
            <button pButton label="Nouveau mouvement" icon="pi pi-plus" severity="success"
                    (click)="openMovePicker()"></button>
          }
        </div>
      </header>

      <!-- Filters -->
      <div class="sl-filters">
        <div class="search">
          <i class="pi pi-search"></i>
          <input pInputText placeholder="Rechercher un article…" [(ngModel)]="search"
                 (ngModelChange)="refresh.set(refresh() + 1)" />
        </div>
        <p-select [options]="projectOptions()" [(ngModel)]="projectFilter"
                  placeholder="Tous les projets" [showClear]="true"
                  optionLabel="name" optionValue="id"
                  (onChange)="onProjectChange()" styleClass="filter-sel" />
        <p-select [options]="locationOptions" [(ngModel)]="locationFilter"
                  placeholder="Tous les emplacements" [showClear]="true"
                  optionLabel="label" optionValue="id"
                  (onChange)="refresh.set(refresh() + 1)" styleClass="filter-sel" />
        <div class="view-toggle">
          <button pButton [text]="true" [outlined]="viewMode() !== 'table'"
                  icon="pi pi-list" pTooltip="Tableau" tooltipPosition="top"
                  (click)="viewModeService.set('stock', 'table')"></button>
          <button pButton [text]="true" [outlined]="viewMode() !== 'cards'"
                  icon="pi pi-th-large" pTooltip="Cartes" tooltipPosition="top"
                  (click)="viewModeService.set('stock', 'cards')"></button>
        </div>
      </div>

      <!-- Totals chip strip -->
      @if (!loading() && filtered().length > 0) {
        <div class="sl-totals">
          <div class="tot-chip">
            <i class="pi pi-tags"></i>
            <span class="tot-num">{{ filteredArticleCount() }}</span>
            <span class="tot-lbl">article(s)</span>
          </div>
          <div class="tot-chip">
            <i class="pi pi-map-marker"></i>
            <span class="tot-num">{{ filteredLocationCount() }}</span>
            <span class="tot-lbl">emplacement(s)</span>
          </div>
          <div class="tot-chip tot-strong">
            <i class="pi pi-database"></i>
            <span class="tot-num">{{ formatQty(totalQty()) }}</span>
            <span class="tot-lbl">qté totale (filtres)</span>
          </div>
        </div>
      }

      @if (loading()) {
        <div class="sl-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else if (filtered().length === 0) {
        <div class="sl-empty">
          <i class="pi pi-box"></i>
          <h3>Aucun stock</h3>
          <p>Créez d'abord des articles et emplacements, puis saisissez une entrée.</p>
          @if (canAdmin()) {
            <div class="empty-actions">
              <button pButton label="Ajouter un article" icon="pi pi-plus" severity="success"
                      [routerLink]="'/stock/articles'"></button>
              <button pButton label="Gérer les emplacements" icon="pi pi-map-marker" severity="info"
                      [routerLink]="'/stock/emplacements'"></button>
            </div>
          }
        </div>
      } @else {
        @if (viewMode() === 'table') {
          <div class="sl-table-wrap">
            <table class="sl-table">
              <thead>
                <tr>
                  <th>Article</th>
                  <th>Emplacement</th>
                  <th>Projet</th>
                  <th class="num">Qté</th>
                  <th>Unité</th>
                  <th>Niveau</th>
                  @if (canMove()) { <th class="col-actions">Actions</th> }
                </tr>
              </thead>
              <tbody>
                @for (row of filtered(); track row.itemId + ':' + row.locationId) {
                  <tr [style.--row-color]="lvl(row.qty).color">
                    <td>
                      <div class="c-item">
                        <span class="item-disc" [style.background]="lvl(row.qty).soft"
                              [style.color]="lvl(row.qty).color">
                          <i class="pi" [class]="lvl(row.qty).icon"></i>
                        </span>
                        <div>
                          <div class="item-name">{{ row.itemName }}</div>
                          @if (row.itemCode) { <div class="item-code">{{ row.itemCode }}</div> }
                        </div>
                      </div>
                    </td>
                    <td>
                      <span class="loc-chip" [style.--lc-color]="ltv(row.locationType).color">
                        <i class="pi" [class]="ltv(row.locationType).icon"></i>
                        {{ row.locationName }}
                      </span>
                    </td>
                    <td class="c-proj">{{ row.projectName ?? (row.locationType === 'CENTRAL' ? 'Central' : '—') }}</td>
                    <td class="num strong" [style.color]="lvl(row.qty).color">{{ formatQty(row.qty) }}</td>
                    <td class="c-uom">{{ row.uom }}</td>
                    <td>
                      <span class="level-chip" [style.--lv-color]="lvl(row.qty).color"
                            [style.--lv-soft]="lvl(row.qty).soft">
                        {{ lvl(row.qty).label }}
                      </span>
                    </td>
                    @if (canMove()) {
                      <td class="col-actions">
                        <button pButton icon="pi pi-arrow-down" [text]="true" severity="success"
                                pTooltip="Entrée" tooltipPosition="left"
                                (click)="newMove('IN', row)"></button>
                        <button pButton icon="pi pi-arrow-up" [text]="true" severity="danger"
                                pTooltip="Sortie" tooltipPosition="left"
                                (click)="newMove('OUT', row)"></button>
                        <button pButton icon="pi pi-arrows-h" [text]="true" severity="info"
                                pTooltip="Transférer" tooltipPosition="left"
                                (click)="newMove('TRANSFER', row)"></button>
                      </td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @else {
          <!-- Cards view: one card per article (aggregated across locations) -->
          <div class="sl-cards">
            @for (card of cards(); track card.itemId) {
              <div class="stock-card" [style.--card-color]="lvl(card.totalQty).color"
                   [style.--card-soft]="lvl(card.totalQty).soft"
                   (click)="newMove('IN', card.firstRow)">
                <div class="sc-head">
                  <span class="sc-icon"><i class="pi" [class]="lvl(card.totalQty).icon"></i></span>
                  <div class="sc-title">
                    <div class="sc-name">{{ card.itemName }}</div>
                    @if (card.itemCode) { <div class="sc-code">{{ card.itemCode }}</div> }
                  </div>
                </div>
                <div class="sc-qty">
                  <span class="sc-num">{{ formatQty(card.totalQty) }}</span>
                  <span class="sc-uom">{{ card.uom }}</span>
                </div>
                <div class="sc-meta">
                  <i class="pi pi-map-marker"></i>
                  {{ card.locationCount }} emplacement(s)
                </div>
              </div>
            }
          </div>
        }
      }
    </section>
  `,
  styles: [`
    .sl { display: flex; flex-direction: column; gap: 18px; }

    /* Header */
    .sl-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; }
    .sl-head h1 { margin: 0 0 4px; font-size: 22px; font-weight: 800; display: flex; align-items: center; gap: 10px; }
    .sl-head h1 i { color: #0d9488; }
    .sl-head p { margin: 0; color: #64748b; font-size: 14px; }
    .head-actions { display: flex; align-items: center; gap: 12px; }
    .back-link { color: #475569; text-decoration: none; font-size: 13px; font-weight: 600;
      display: inline-flex; align-items: center; gap: 6px; }
    .back-link:hover { color: #0d9488; }

    /* Filters */
    .sl-filters {
      display: flex; gap: 10px; flex-wrap: wrap; align-items: center;
      background: #fff; border: 1px solid #e2e8f0; border-radius: 12px;
      padding: 10px 12px;
    }
    .sl-filters .search {
      flex: 1; min-width: 220px;
      display: flex; align-items: center; gap: 8px;
      background: #f8fafc; border: 1px solid #e2e8f0;
      border-radius: 8px; padding: 0 12px;
    }
    .sl-filters .search i { color: #94a3b8; }
    .sl-filters .search input { border: none; background: transparent; padding: 9px 0; width: 100%; font-size: 14px; outline: none; }
    .sl-filters .search input:focus { box-shadow: none; }
    :host ::ng-deep .filter-sel { min-width: 180px; }
    .view-toggle { display: flex; gap: 4px; margin-left: auto; }

    /* Totals chip strip */
    .sl-totals {
      display: flex; gap: 10px; flex-wrap: wrap;
    }
    .tot-chip {
      background: #fff; border: 1px solid #e2e8f0; border-radius: 10px;
      padding: 8px 14px;
      display: flex; align-items: center; gap: 8px;
      font-size: 13px;
    }
    .tot-chip i { color: #94a3b8; }
    .tot-chip .tot-num { font-weight: 800; font-size: 16px; color: #0f172a; }
    .tot-chip .tot-lbl { color: #64748b; }
    .tot-chip.tot-strong {
      background: #f0fdfa; border-color: #0d9488;
    }
    .tot-chip.tot-strong i, .tot-chip.tot-strong .tot-num { color: #0d9488; }

    /* Table */
    .sl-table-wrap {
      background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden;
    }
    .sl-table { width: 100%; border-collapse: collapse; font-size: 14px; }
    .sl-table th {
      background: #f8fafc; color: #64748b; font-weight: 600; font-size: 12px;
      text-transform: uppercase; letter-spacing: .04em;
      text-align: left; padding: 12px 16px; border-bottom: 1px solid #e2e8f0;
    }
    .sl-table th.num { text-align: right; }
    .sl-table th.col-actions { text-align: center; width: 130px; }
    .sl-table td {
      padding: 12px 16px; border-bottom: 1px solid #f1f5f9; vertical-align: middle;
    }
    .sl-table td.num { text-align: right; font-variant-numeric: tabular-nums; }
    .sl-table td.strong { font-weight: 700; font-size: 15px; }
    .sl-table td.c-uom { color: #64748b; }
    .sl-table td.col-actions { text-align: center; white-space: nowrap; }
    .sl-table tr:last-child td { border-bottom: none; }
    .sl-table tr:hover td { background: #f8fafc; }
    .sl-table tr { --row-color: #16a34a; }

    .c-item { display: flex; align-items: center; gap: 10px; }
    .item-disc {
      width: 32px; height: 32px; border-radius: 8px;
      display: grid; place-items: center;
      font-size: 14px;
    }
    .item-name { font-weight: 600; }
    .item-code { font-size: 12px; color: #94a3b8; }

    .loc-chip {
      display: inline-flex; align-items: center; gap: 6px;
      padding: 4px 10px; border-radius: 999px;
      font-size: 12px; font-weight: 600;
      background: color-mix(in srgb, var(--lc-color, #0d9488) 12%, transparent);
      color: var(--lc-color, #0d9488);
    }

    .level-chip {
      display: inline-block;
      padding: 4px 10px; border-radius: 999px;
      font-size: 12px; font-weight: 700;
      background: var(--lv-soft, #f0fdf4);
      color: var(--lv-color, #16a34a);
    }

    /* Cards */
    .sl-cards {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
      gap: 14px;
    }
    .stock-card {
      background: var(--card-soft, #f0fdf4);
      border: 1px solid color-mix(in srgb, var(--card-color, #16a34a) 20%, transparent);
      border-radius: 14px;
      padding: 16px;
      cursor: pointer;
      transition: all .14s ease;
      display: flex; flex-direction: column; gap: 10px;
    }
    .stock-card:hover {
      transform: translateY(-2px);
      box-shadow: 0 10px 24px -10px color-mix(in srgb, var(--card-color, #16a34a) 50%, transparent);
    }
    .sc-head { display: flex; align-items: flex-start; gap: 10px; }
    .sc-icon {
      width: 36px; height: 36px; border-radius: 10px;
      background: var(--card-color, #16a34a); color: #fff;
      display: grid; place-items: center; font-size: 16px;
      flex-shrink: 0;
    }
    .sc-name { font-weight: 700; font-size: 15px; line-height: 1.2; }
    .sc-code { font-size: 12px; color: #94a3b8; margin-top: 2px; }
    .sc-qty { display: flex; align-items: baseline; gap: 6px; }
    .sc-num { font-size: 24px; font-weight: 800; color: var(--card-color, #16a34a); line-height: 1; }
    .sc-uom { font-size: 13px; color: #64748b; }
    .sc-meta { font-size: 12px; color: #64748b; display: flex; align-items: center; gap: 6px; }
    .sc-meta i { font-size: 11px; }

    /* States */
    .sl-loading, .sl-empty {
      background: #fff; border: 1px solid #e2e8f0; border-radius: 12px;
      padding: 50px 20px; text-align: center; color: #64748b;
      display: flex; flex-direction: column; align-items: center; gap: 10px;
    }
    .sl-loading i, .sl-empty i { font-size: 2.4rem; color: #94a3b8; }
    .sl-empty h3 { margin: 0; }
    .sl-empty p { margin: 0 0 6px; }
    .empty-actions { display: flex; gap: 10px; margin-top: 6px; }
  `]
})
export class StockListComponent implements OnInit {
  private stock = inject(StockService);
  private projectService = inject(ProjectService);
  private auth = inject(AuthService);
  private router = inject(Router);
  viewModeService = inject(ViewModeService);

  canMove = computed(() => this.auth.can(PERM.STOCK_MOVE) || !!this.auth.me()?.isOwner);
  canAdmin = computed(() => this.auth.can(PERM.STOCK_ADMIN) || !!this.auth.me()?.isOwner);

  loading = signal(true);
  balances = signal<BalanceRow[]>([]);
  refresh = signal(0);

  // Filters
  search = '';
  projectFilter: string | null = null;
  locationFilter: string | null = null;

  projectOptions = signal<ProjectOption[]>([]);
  // All locations known to the user (cached once); filtered per-project on the fly
  private allLocations = signal<StockLocation[]>([]);

  viewMode = this.viewModeService.forPage('stock', 'table');

  // Visual helpers exposed to the template
  lvl = stockLevelVisual;
  ltv = locationTypeVisual;
  formatQty = formatQty;

  ngOnInit(): void {
    this.projectService.options().subscribe(opts => {
      this.projectOptions.set((opts ?? []).map(p => ({ id: p.id, name: p.name })));
    });
    this.stock.loadLocations().subscribe(locs => this.allLocations.set(locs));
    this.reload();
  }

  /** Available location options for the dropdown, scoped by selected project. */
  get locationOptions(): { id: string; label: string }[] {
    const projId = this.projectFilter;
    return this.allLocations()
      .filter(l => !projId || l.type === 'CENTRAL' || l.projectId === projId)
      .map(l => ({
        id: l.id,
        label: `${l.name}${l.type === 'CENTRAL' ? ' (central)' : ''}`,
      }));
  }

  /** When the project filter changes, reset the location filter and reload. */
  onProjectChange(): void {
    this.locationFilter = null;
    this.refresh.set(this.refresh() + 1);
    this.reload();
  }

  /** Re-fetch balances whenever a filter changes. */
  reload(): void {
    this.loading.set(true);
    this.stock.balances({
      projectId: this.projectFilter ?? undefined,
      locationId: this.locationFilter ?? undefined,
      includeZero: false,
    }).subscribe(rows => {
      this.balances.set(rows);
      this.loading.set(false);
    });
  }

  /** Apply client-side search on top of the server-filtered balances. */
  filtered = computed<BalanceRow[]>(() => {
    this.refresh(); // register plain-string filter dependency
    const q = this.search.trim().toLowerCase();
    return this.balances().filter(r => {
      if (q && !r.itemName.toLowerCase().includes(q) &&
          !(r.itemCode ?? '').toLowerCase().includes(q)) return false;
      if (this.locationFilter && r.locationId !== this.locationFilter) return false;
      return true;
    });
  });

  /** Aggregated cards (one per item) for the cards view. */
  cards = computed(() => {
    this.refresh();
    const byItem = new Map<string, { itemId: string; itemName: string; itemCode: string | null;
                                     uom: string; totalQty: number; locationCount: number;
                                     firstRow: BalanceRow }>();
    for (const r of this.filtered()) {
      const e = byItem.get(r.itemId) ?? {
        itemId: r.itemId, itemName: r.itemName, itemCode: r.itemCode ?? null,
        uom: r.uom, totalQty: 0, locationCount: 0, firstRow: r,
      };
      e.totalQty += r.qty;
      e.locationCount += 1;
      byItem.set(r.itemId, e);
    }
    return Array.from(byItem.values());
  });

  filteredArticleCount = computed(() => new Set(this.filtered().map(r => r.itemId)).size);
  filteredLocationCount = computed(() => new Set(this.filtered().map(r => r.locationId)).size);
  totalQty = computed(() => this.filtered().reduce((s, r) => s + r.qty, 0));

  /** Quick "create a move" with item + location preselected. */
  newMove(type: string, row: BalanceRow): void {
    const params = new URLSearchParams();
    params.set('type', type);
    if (row.itemId) params.set('itemId', row.itemId);
    if (type === 'IN' || type === 'TRANSFER') {
      params.set('toLocationId', row.locationId);
    } else {
      params.set('fromLocationId', row.locationId);
    }
    this.router.navigate(['/stock/mouvements/new'], { queryParams: params });
  }

  /** No preselected row — open the bare move form. */
  openMovePicker(): void {
    this.router.navigate(['/stock/mouvements/new']);
  }
}
