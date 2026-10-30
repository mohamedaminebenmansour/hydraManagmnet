import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { SelectModule } from 'primeng/select';
import { TooltipModule } from 'primeng/tooltip';
import { PurchaseService } from '@app/core/services/purchase.service';
import { BcLineMatchRow, matchStatusVisual, formatQty } from '@app/core/models/stock.model';

@Component({
  selector: 'app-match-overview',
  standalone: true,
  imports: [FormsModule, RouterLink, SelectModule, TooltipModule],
  template: `
    <section class="match">
      <header class="head">
        <div>
          <h1><i class="pi pi-verify"></i> Rapprochement</h1>
          <p>Conformité des réceptions (BL) et entrées en stock par rapport aux commandes (BC).</p>
        </div>
        <a class="back" [routerLink]="'/stock'"><i class="pi pi-arrow-left"></i> Hub</a>
      </header>
      <div class="filters">
        <p-select [options]="statusOptions" [(ngModel)]="statusFilter" placeholder="Tous les statuts"
                  optionLabel="label" optionValue="value" [showClear]="true"
                  (onChange)="reload()" styleClass="filter-sel" />
      </div>
      @if (loading()) { <div class="loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div> }
      @else if (filtered().length === 0) {
        <div class="empty"><i class="pi pi-verify"></i><h3>Aucun rapprochement</h3><p>Créez d'abord des commandes.</p></div>
      } @else {
        <div class="table-wrap">
          <table class="table">
            <thead><tr><th>BC</th><th>Article</th><th class="num">Commandé</th><th class="num">Reçu</th><th class="num">En stock</th><th>Statut</th></tr></thead>
            <tbody>
              @for (row of filtered(); track row.bcLineId) {
                <tr>
                  <td class="ref">{{ row.bcReference || '—' }}</td>
                  <td>{{ row.itemName }}</td>
                  <td class="num">{{ formatQty(row.qtyOrdered) }}</td>
                  <td class="num">{{ formatQty(row.qtyReceived) }}</td>
                  <td class="num">{{ formatQty(row.qtyInStockMoves) }}</td>
                  <td>
                    <span class="chip" [style.--c]="sv(row.status).color" [style.--s]="sv(row.status).soft"
                          [pTooltip]="sv(row.status).label" tooltipPosition="left">
                      <i class="pi" [class]="sv(row.status).icon"></i> {{ sv(row.status).label }}
                    </span>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
  `,
  styles: [`.match { display: flex; flex-direction: column; gap: 16px; }
    .head { display: flex; justify-content: space-between; align-items: flex-end; }
    .head h1 { margin: 0 0 4px; font-size: 22px; font-weight: 800; display: flex; align-items: center; gap: 10px; }
    .head h1 i { color: #7c3aed; }
    .head p { margin: 0; color: #64748b; font-size: 14px; }
    .back { color: #475569; text-decoration: none; font-size: 13px; font-weight: 600; display: inline-flex; align-items: center; gap: 6px; }
    .filters { display: flex; gap: 10px; background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 10px 12px; }
    :host ::ng-deep .filter-sel { min-width: 200px; }
    .table-wrap { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden; }
    .table { width: 100%; border-collapse: collapse; font-size: 14px; }
    .table th { background: #f8fafc; color: #64748b; font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: .04em; text-align: left; padding: 12px 16px; border-bottom: 1px solid #e2e8f0; }
    .table th.num { text-align: right; }
    .table td { padding: 12px 16px; border-bottom: 1px solid #f1f5f9; }
    .table td.num { text-align: right; font-variant-numeric: tabular-nums; }
    .table tr:last-child td { border-bottom: none; }
    .table tr:hover td { background: #f8fafc; }
    .ref { font-family: 'Courier New', monospace; font-weight: 600; }
    .chip { display: inline-flex; align-items: center; gap: 5px; padding: 4px 10px; border-radius: 999px; font-size: 12px; font-weight: 700; background: var(--s); color: var(--c); }
    .loading, .empty { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 50px 20px; text-align: center; color: #64748b; display: flex; flex-direction: column; align-items: center; gap: 10px; }
    .loading i, .empty i { font-size: 2.4rem; color: #94a3b8; }
    .empty h3 { margin: 0; }
  `]
})
export class MatchOverviewComponent implements OnInit {
  private purchase = inject(PurchaseService);
  loading = signal(true);
  rows = signal<BcLineMatchRow[]>([]);
  statusFilter: string | null = null;
  sv = matchStatusVisual;
  formatQty = formatQty;
  readonly statusOptions = [
    { label: 'Conforme', value: 'MATCHED' },
    { label: 'Partiel', value: 'PARTIAL' },
    { label: 'Sans réception', value: 'MISSING_BL' },
    { label: 'Écart stock', value: 'QTY_MISMATCH' },
  ];

  filtered = computed(() => {
    if (!this.statusFilter) return this.rows();
    return this.rows().filter(r => r.status === this.statusFilter);
  });

  ngOnInit(): void { this.reload(); }
  reload(): void {
    this.loading.set(true);
    this.purchase.matchOverview(undefined, 0, 100).subscribe(page => {
      this.rows.set(page?.items ?? []);
      this.loading.set(false);
    });
  }
}
