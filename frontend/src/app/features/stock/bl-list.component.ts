import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { SelectModule } from 'primeng/select';
import { FormsModule } from '@angular/forms';
import { PurchaseService } from '@app/core/services/purchase.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { BonLivraison, blStatusVisual, formatDateTime } from '@app/core/models/stock.model';

@Component({
  selector: 'app-bl-list',
  standalone: true,
  imports: [FormsModule, RouterLink, ButtonModule, SelectModule],
  template: `
    <section class="bl-list">
      <header class="head">
        <div>
          <h1><i class="pi pi-truck"></i> Réceptions fournisseur</h1>
          <p>Bons de livraison fournisseur — réceptions de stock.</p>
        </div>
        <div class="actions">
          <a class="back" [routerLink]="'/stock'"><i class="pi pi-arrow-left"></i> Hub</a>
          @if (canMove()) { <button pButton label="Nouvelle réception" icon="pi pi-plus" severity="success" [routerLink]="'/stock/receptions/new'"></button> }
        </div>
      </header>
      <div class="filters">
        <p-select [options]="statusOptions" [(ngModel)]="statusFilter" placeholder="Tous les statuts" optionLabel="label" optionValue="value" [showClear]="true" (onChange)="reload()" styleClass="filter-sel" />
      </div>
      @if (loading()) { <div class="loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div> }
      @else if (bls().length === 0) {
        <div class="empty"><i class="pi pi-truck"></i><h3>Aucune réception</h3><p>Créez une réception depuis une commande confirmée.</p></div>
      } @else {
        <div class="table-wrap">
          <table class="table">
            <thead><tr><th>Référence</th><th>BC</th><th>Statut</th><th>Lieu</th><th>Lignes</th><th>Reçu le</th></tr></thead>
            <tbody>
              @for (bl of bls(); track bl.id) {
                <tr [routerLink]="'/stock/receptions/' + bl.id">
                  <td class="ref">{{ bl.reference || '—' }}</td>
                  <td>{{ bl.bcReference || '—' }}</td>
                  <td><span class="chip" [style.--c]="sv(bl.status).color" [style.--s]="sv(bl.status).soft"><i class="pi" [class]="sv(bl.status).icon"></i> {{ sv(bl.status).label }}</span></td>
                  <td>{{ bl.locationName || '—' }}</td>
                  <td class="num">{{ bl.lineCount ?? 0 }}</td>
                  <td>{{ fmt(bl.receivedAt) }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </section>
  `,
  styles: [`
    .bl-list { display: flex; flex-direction: column; gap: 16px; }
    .head { display: flex; justify-content: space-between; align-items: flex-end; }
    .head h1 { margin: 0 0 4px; font-size: 22px; font-weight: 800; display: flex; align-items: center; gap: 10px; }
    .head h1 i { color: #16a34a; }
    .head p { margin: 0; color: #64748b; font-size: 14px; }
    .actions { display: flex; align-items: center; gap: 12px; }
    .back { color: #475569; text-decoration: none; font-size: 13px; font-weight: 600; display: inline-flex; align-items: center; gap: 6px; }
    .filters { display: flex; gap: 10px; background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 10px 12px; }
    :host ::ng-deep .filter-sel { min-width: 200px; }
    .table-wrap { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden; }
    .table { width: 100%; border-collapse: collapse; font-size: 14px; }
    .table th { background: #f8fafc; color: #64748b; font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: .04em; text-align: left; padding: 12px 16px; border-bottom: 1px solid #e2e8f0; }
    .table td { padding: 12px 16px; border-bottom: 1px solid #f1f5f9; }
    .table td.num { text-align: right; }
    .table tr { cursor: pointer; }
    .table tr:last-child td { border-bottom: none; }
    .table tr:hover td { background: #f8fafc; }
    .ref { font-family: 'Courier New', monospace; font-weight: 600; }
    .chip { display: inline-flex; align-items: center; gap: 5px; padding: 4px 10px; border-radius: 999px; font-size: 12px; font-weight: 700; background: var(--s); color: var(--c); }
    .loading, .empty { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 50px 20px; text-align: center; color: #64748b; display: flex; flex-direction: column; align-items: center; gap: 10px; }
    .loading i, .empty i { font-size: 2.4rem; color: #94a3b8; }
    .empty h3 { margin: 0; }
  `]
})
export class BlListComponent implements OnInit {
  private purchase = inject(PurchaseService);
  private auth = inject(AuthService);
  loading = signal(true);
  bls = computed(() => this.purchase.bls());
  statusFilter: string | null = null;
  canMove = computed(() => this.auth.can(PERM.STOCK_MOVE) || !!this.auth.me()?.isOwner);
  sv = blStatusVisual;
  fmt = formatDateTime;
  readonly statusOptions = [{ label: 'Brouillon', value: 'DRAFT' }, { label: 'Réceptionné', value: 'POSTED' }, { label: 'Annulé', value: 'CANCELLED' }];
  ngOnInit(): void { this.reload(); }
  reload(): void { this.loading.set(true); this.purchase.loadBls(true, undefined, undefined, this.statusFilter ?? undefined).subscribe(() => this.loading.set(false)); }
}
