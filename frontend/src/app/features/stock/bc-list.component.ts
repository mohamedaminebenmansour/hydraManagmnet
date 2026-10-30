import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TooltipModule } from 'primeng/tooltip';
import { PurchaseService } from '@app/core/services/purchase.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { PurchaseOrder, bcStatusVisual } from '@app/core/models/stock.model';

@Component({
  selector: 'app-bc-list',
  standalone: true,
  imports: [FormsModule, RouterLink, ButtonModule, InputTextModule, SelectModule, TooltipModule],
  template: `
    <section class="bc-list">
      <header class="head">
        <div>
          <h1><i class="pi pi-shopping-cart"></i> Commandes</h1>
          <p>Bons de commande (BC) par chantier.</p>
        </div>
        <div class="actions">
          <a class="back" [routerLink]="'/stock'"><i class="pi pi-arrow-left"></i> Hub</a>
          @if (canMove()) {
            <button pButton label="Nouveau BC" icon="pi pi-plus" severity="success" [routerLink]="'/stock/commandes/new'"></button>
          }
        </div>
      </header>
      <div class="filters">
        <p-select [options]="statusOptions" [(ngModel)]="statusFilter" placeholder="Tous les statuts"
                  optionLabel="label" optionValue="value" [showClear]="true"
                  (onChange)="reload()" styleClass="filter-sel" />
      </div>
      @if (loading()) {
        <div class="loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else if (bcs().length === 0) {
        <div class="empty">
          <i class="pi pi-shopping-cart"></i>
          <h3>Aucune commande</h3>
          <p>Créez votre premier bon de commande.</p>
          @if (canMove()) {
            <button pButton label="Nouveau BC" icon="pi pi-plus" severity="success" [routerLink]="'/stock/commandes/new'"></button>
          }
        </div>
      } @else {
        <div class="table-wrap">
          <table class="table">
            <thead><tr><th>Référence</th><th>Projet</th><th>Statut</th><th>Lignes</th><th>Date</th></tr></thead>
            <tbody>
              @for (bc of bcs(); track bc.id) {
                <tr [routerLink]="'/stock/commandes/' + bc.id">
                  <td class="ref">{{ bc.reference || '—' }}</td>
                  <td>{{ bc.projectName || '—' }}</td>
                  <td><span class="chip" [style.--c]="sv(bc.status).color" [style.--s]="sv(bc.status).soft"><i class="pi" [class]="sv(bc.status).icon"></i> {{ sv(bc.status).label }}</span></td>
                  <td class="num">{{ bc.lineCount ?? 0 }}</td>
                  <td>{{ bc.orderedAt || '—' }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
  `,
  styles: [`
    .bc-list { display: flex; flex-direction: column; gap: 16px; }
    .head { display: flex; justify-content: space-between; align-items: flex-end; }
    .head h1 { margin: 0 0 4px; font-size: 22px; font-weight: 800; display: flex; align-items: center; gap: 10px; }
    .head h1 i { color: #2563eb; }
    .head p { margin: 0; color: #64748b; font-size: 14px; }
    .actions { display: flex; align-items: center; gap: 12px; }
    .back { color: #475569; text-decoration: none; font-size: 13px; font-weight: 600; display: inline-flex; align-items: center; gap: 6px; }
    .back:hover { color: #2563eb; }
    .filters { display: flex; gap: 10px; background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 10px 12px; }
    :host ::ng-deep .filter-sel { min-width: 200px; }
    .table-wrap { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden; }
    .table { width: 100%; border-collapse: collapse; font-size: 14px; }
    .table th { background: #f8fafc; color: #64748b; font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: .04em; text-align: left; padding: 12px 16px; border-bottom: 1px solid #e2e8f0; }
    .table td { padding: 12px 16px; border-bottom: 1px solid #f1f5f9; }
    .table td.num { text-align: right; font-variant-numeric: tabular-nums; }
    .table tr { cursor: pointer; }
    .table tr:last-child td { border-bottom: none; }
    .table tr:hover td { background: #f8fafc; }
    .ref { font-family: 'Courier New', monospace; font-weight: 600; }
    .chip { display: inline-flex; align-items: center; gap: 5px; padding: 4px 10px; border-radius: 999px; font-size: 12px; font-weight: 700; background: var(--s); color: var(--c); }
    .loading, .empty { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 50px 20px; text-align: center; color: #64748b; display: flex; flex-direction: column; align-items: center; gap: 10px; }
    .loading i, .empty i { font-size: 2.4rem; color: #94a3b8; }
    .empty h3 { margin: 0; }
    .empty p { margin: 0 0 6px; }
  `]
})
export class BcListComponent implements OnInit {
  private purchase = inject(PurchaseService);
  private auth = inject(AuthService);
  loading = signal(true);
  bcs = computed(() => this.purchase.bcs());
  statusFilter: string | null = null;
  canMove = computed(() => this.auth.can(PERM.STOCK_MOVE) || !!this.auth.me()?.isOwner);
  sv = bcStatusVisual;
  readonly statusOptions = [
    { label: 'Brouillon', value: 'DRAFT' },
    { label: 'Confirmé', value: 'CONFIRMED' },
    { label: 'Annulé', value: 'CANCELLED' },
  ];
  ngOnInit(): void {
    this.reload();
  }
  reload(): void {
    this.loading.set(true);
    this.purchase.loadBcs(true, undefined, this.statusFilter ?? undefined).subscribe(() => this.loading.set(false));
  }
}
