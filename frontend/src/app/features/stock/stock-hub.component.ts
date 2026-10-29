import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { TooltipModule } from 'primeng/tooltip';

import { StockService } from '@app/core/services/stock.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import {
  OrgBalanceRow, moveTypeVisual, formatQty, STOCK_MOVE_TYPE_VISUAL, StockMoveType,
} from '@app/core/models/stock.model';

interface QuickAction {
  type: StockMoveType;
  icon: string;
  label: string;
  hint: string;
  path: string;
}

/**
 * StockHubComponent — /stock
 *
 * The landing page for the Material / Stock module. Mirrors cash-hub:
 *   - Hero header with summary counts (articles, emplacements)
 *   - 4 large quick-action tiles (Entrée, Sortie, Transfert, Ajustement)
 *   - Org-wide summary table (per-item total across all locations) so
 *     the owner can see at a glance what's in stock without drilling in.
 *
 * Visible to anyone with STOCK_VIEW. Quick actions are also gated by
 * STOCK_MOVE (the move-form re-checks at submit time).
 */
@Component({
  selector: 'app-stock-hub',
  standalone: true,
  imports: [RouterLink, ButtonModule, TooltipModule],
  template: `
    <section class="hub">
      <!-- Hero header -->
      <header class="hub-hero">
        <div class="hero-disc"><i class="pi pi-box"></i></div>
        <div class="hero-body">
          <h1>Stock &amp; Matériel</h1>
          <p>Suivi des articles, des emplacements et des mouvements de stock.</p>
          <div class="hero-stats">
            <div class="hero-stat">
              <span class="hero-stat-num">{{ itemCount() }}</span>
              <span class="hero-stat-lbl">article(s)</span>
            </div>
            <div class="hero-stat">
              <span class="hero-stat-num">{{ locationCount() }}</span>
              <span class="hero-stat-lbl">emplacement(s)</span>
            </div>
            <div class="hero-stat">
              <span class="hero-stat-num">{{ orgTotalQty() }}</span>
              <span class="hero-stat-lbl">qté totale</span>
            </div>
          </div>
        </div>
      </header>

      <!-- Quick actions -->
      <div class="qa-section">
        <h2><i class="pi pi-bolt"></i> Actions rapides</h2>
        <div class="qa-grid">
          @for (qa of quickActions; track qa.type) {
            <a class="qa-tile" [routerLink]="qa.path"
               [style.--qa-color]="mtv(qa.type).color"
               [style.--qa-soft]="mtv(qa.type).soft"
               [class.disabled]="!canMove()">
              <span class="qa-icon"><i class="pi" [class]="qa.icon"></i></span>
              <span class="qa-label">{{ qa.label }}</span>
              <span class="qa-hint">{{ qa.hint }}</span>
              @if (!canMove()) {
                <span class="qa-lock"><i class="pi pi-lock" pTooltip="Permission STOCK_MOVE requise"
                                          tooltipPosition="bottom"></i></span>
              }
            </a>
          }
        </div>
      </div>

      <!-- Secondary navigation -->
      <div class="qa-section">
        <h2><i class="pi pi-directions"></i> Aller à</h2>
        <div class="nav-grid">
          <a class="nav-tile" [routerLink]="'/stock/stocks'">
            <i class="pi pi-table"></i>
            <span>Voir le stock</span>
            <small>Stock par article et emplacement</small>
          </a>
          <a class="nav-tile" [routerLink]="'/stock/mouvements'">
            <i class="pi pi-history"></i>
            <span>Historique des mouvements</span>
            <small>Toutes les entrées / sorties / transferts</small>
          </a>
          @if (canAdmin()) {
            <a class="nav-tile" [routerLink]="'/stock/articles'">
              <i class="pi pi-tags"></i>
              <span>Articles</span>
              <small>Répertoire des articles et alias</small>
            </a>
            <a class="nav-tile" [routerLink]="'/stock/emplacements'">
              <i class="pi pi-map-marker"></i>
              <span>Emplacements</span>
              <small>Dépôt central + chantiers</small>
            </a>
          }
        </div>
      </div>

      <!-- Org-wide summary -->
      <div class="qa-section">
        <h2><i class="pi pi-chart-bar"></i> Synthèse du stock</h2>
        @if (loading()) {
          <div class="hub-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
        } @else if (orgSummary().length === 0) {
          <div class="hub-empty">
            <i class="pi pi-box"></i>
            <p>Aucun article en stock pour le moment.</p>
            @if (canAdmin()) {
              <p-button label="Ajouter un article" icon="pi pi-plus" severity="success"
                        routerLink="/stock/articles"></p-button>
            }
          </div>
        } @else {
          <div class="summary-table-wrap">
            <table class="summary-table">
              <thead>
                <tr>
                  <th>Article</th>
                  <th class="num">Qté totale</th>
                  <th class="num">Unité</th>
                </tr>
              </thead>
              <tbody>
                @for (row of orgSummary(); track row.itemId) {
                  <tr>
                    <td>
                      <div class="sum-name">{{ row.itemName }}</div>
                      @if (row.itemCode) {
                        <div class="sum-code">{{ row.itemCode }}</div>
                      }
                    </td>
                    <td class="num strong">{{ formatQty(row.totalQty) }}</td>
                    <td class="num">{{ row.uom }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </div>
    </section>
  `,
  styles: [`
    .hub { display: flex; flex-direction: column; gap: 28px; }

    /* Hero */
    .hub-hero {
      background: linear-gradient(135deg, #0d9488 0%, #0891b2 100%);
      color: #fff;
      border-radius: 18px;
      padding: 24px 28px;
      display: flex; align-items: center; gap: 20px;
      box-shadow: 0 10px 24px -10px rgba(13, 148, 136, 0.4);
    }
    .hero-disc {
      width: 64px; height: 64px;
      border-radius: 16px;
      background: rgba(255, 255, 255, 0.2);
      display: grid; place-items: center;
      font-size: 30px;
      flex-shrink: 0;
    }
    .hero-body h1 { margin: 0 0 4px; font-size: 22px; font-weight: 800; }
    .hero-body p  { margin: 0 0 12px; font-size: 14px; color: rgba(255,255,255,0.9); }
    .hero-stats { display: flex; gap: 22px; }
    .hero-stat { display: flex; flex-direction: column; align-items: flex-start; }
    .hero-stat-num { font-size: 22px; font-weight: 800; line-height: 1; }
    .hero-stat-lbl { font-size: 11px; color: rgba(255,255,255,0.85); text-transform: uppercase; letter-spacing: .06em; margin-top: 2px; }

    /* Section heading */
    .qa-section h2 {
      font-size: 15px; font-weight: 700; color: #475569;
      margin: 0 0 14px;
      display: flex; align-items: center; gap: 8px;
      text-transform: uppercase; letter-spacing: .04em;
    }
    .qa-section h2 i { font-size: 14px; color: #94a3b8; }

    /* Quick action tiles */
    .qa-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
      gap: 14px;
    }
    .qa-tile {
      position: relative;
      background: var(--qa-soft, #f0fdf4);
      border: 1px solid color-mix(in srgb, var(--qa-color, #16a34a) 25%, transparent);
      border-radius: 14px;
      padding: 18px 16px;
      text-decoration: none;
      color: #0f172a;
      display: flex; flex-direction: column; align-items: flex-start; gap: 6px;
      transition: all .14s ease;
      min-height: 110px;
      cursor: pointer;
    }
    .qa-tile:hover {
      transform: translateY(-2px);
      box-shadow: 0 8px 20px -8px color-mix(in srgb, var(--qa-color, #16a34a) 50%, transparent);
    }
    .qa-tile.disabled {
      opacity: 0.55;
      cursor: not-allowed;
      pointer-events: none;
    }
    .qa-icon {
      width: 40px; height: 40px;
      border-radius: 12px;
      background: var(--qa-color, #16a34a);
      color: #fff;
      display: grid; place-items: center;
      font-size: 18px;
      margin-bottom: 6px;
    }
    .qa-label { font-weight: 700; font-size: 15px; }
    .qa-hint { font-size: 12px; color: #64748b; }
    .qa-lock {
      position: absolute; top: 10px; right: 10px;
      color: #94a3b8; font-size: 12px;
    }

    /* Nav tiles */
    .nav-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
      gap: 12px;
    }
    .nav-tile {
      background: #fff;
      border: 1px solid #e2e8f0;
      border-radius: 12px;
      padding: 14px 16px;
      text-decoration: none;
      color: #0f172a;
      display: flex; flex-direction: column; gap: 4px;
      transition: all .14s ease;
    }
    .nav-tile:hover {
      border-color: #0d9488;
      box-shadow: 0 6px 16px -6px rgba(13, 148, 136, 0.3);
      transform: translateY(-1px);
    }
    .nav-tile i { font-size: 18px; color: #0d9488; margin-bottom: 4px; }
    .nav-tile span { font-weight: 700; font-size: 14px; }
    .nav-tile small { font-size: 12px; color: #64748b; }

    /* Summary table */
    .summary-table-wrap {
      background: #fff;
      border: 1px solid #e2e8f0;
      border-radius: 12px;
      overflow: hidden;
    }
    .summary-table {
      width: 100%;
      border-collapse: collapse;
      font-size: 14px;
    }
    .summary-table th {
      background: #f8fafc;
      color: #64748b;
      font-weight: 600;
      font-size: 12px;
      text-transform: uppercase;
      letter-spacing: .04em;
      text-align: left;
      padding: 12px 16px;
      border-bottom: 1px solid #e2e8f0;
    }
    .summary-table th.num { text-align: right; }
    .summary-table td {
      padding: 12px 16px;
      border-bottom: 1px solid #f1f5f9;
      vertical-align: middle;
    }
    .summary-table td.num { text-align: right; font-variant-numeric: tabular-nums; }
    .summary-table td.strong { font-weight: 700; font-size: 15px; color: #0d9488; }
    .summary-table tr:last-child td { border-bottom: none; }
    .summary-table tr:hover td { background: #f8fafc; }
    .sum-name { font-weight: 600; }
    .sum-code { font-size: 12px; color: #94a3b8; margin-top: 2px; }

    /* States */
    .hub-loading, .hub-empty {
      background: #fff;
      border: 1px solid #e2e8f0;
      border-radius: 12px;
      padding: 40px 20px;
      text-align: center;
      color: #64748b;
      display: flex; flex-direction: column; align-items: center; gap: 10px;
    }
    .hub-loading i, .hub-empty i { font-size: 2.2rem; color: #94a3b8; }
    .hub-empty p { margin: 0; }
  `]
})
export class StockHubComponent implements OnInit {
  private stock = inject(StockService);
  private auth = inject(AuthService);

  loading = signal(true);
  orgSummary = signal<OrgBalanceRow[]>([]);

  canMove = computed(() => this.auth.can(PERM.STOCK_MOVE) || !!this.auth.me()?.isOwner);
  canAdmin = computed(() => this.auth.can(PERM.STOCK_ADMIN) || !!this.auth.me()?.isOwner);

  itemCount = computed(() => this.stock.items().length);
  locationCount = computed(() => this.stock.locations().length);
  orgTotalQty = computed(() => {
    // Sum of all totalQty rows (a rough "how much stuff is in stock" indicator)
    return this.orgSummary().reduce((sum, r) => sum + (r.totalQty || 0), 0);
  });

  readonly quickActions: QuickAction[] = [
    { type: 'IN',         icon: STOCK_MOVE_TYPE_VISUAL['IN'].icon,         label: 'Entrée',         hint: 'Réceptionner un article',  path: '/stock/mouvements/new?type=IN' },
    { type: 'OUT',        icon: STOCK_MOVE_TYPE_VISUAL['OUT'].icon,        label: 'Sortie',          hint: 'Sortir du stock',          path: '/stock/mouvements/new?type=OUT' },
    { type: 'TRANSFER',   icon: STOCK_MOVE_TYPE_VISUAL['TRANSFER'].icon,   label: 'Transfert',       hint: 'Déplacer entre emplacements', path: '/stock/mouvements/new?type=TRANSFER' },
    { type: 'DIRECT_USE', icon: STOCK_MOVE_TYPE_VISUAL['DIRECT_USE'].icon, label: 'Consommation',    hint: 'Carburant / conso chantier', path: '/stock/mouvements/new?type=DIRECT_USE' },
  ];

  mtv = moveTypeVisual;
  formatQty = formatQty;

  ngOnInit(): void {
    // Load items + locations into the shared cache (cheap, fills the hero counts)
    this.stock.loadItems().subscribe();
    this.stock.loadLocations().subscribe();
    // Load the org-wide summary for the table
    this.stock.orgSummary().subscribe(rows => {
      this.orgSummary.set(rows);
      this.loading.set(false);
    });
  }
}
