import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink, ActivatedRoute } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { MessageService } from 'primeng/api';
import { PurchaseService } from '@app/core/services/purchase.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { PurchaseOrder, bcStatusVisual } from '@app/core/models/stock.model';

@Component({
  selector: 'app-bc-detail',
  standalone: true,
  imports: [RouterLink, ButtonModule],
  template: `
    <section class="bc-detail">
      @if (bc()) {
        <header class="head">
          <div>
            <h1><i class="pi pi-shopping-cart"></i> {{ bc()?.reference || 'BC sans référence' }}</h1>
            <p>{{ bc()?.projectName }} — <span class="chip" [style.--c]="sv(bc()!.status).color" [style.--s]="sv(bc()!.status).soft"><i class="pi" [class]="sv(bc()!.status).icon"></i> {{ sv(bc()!.status).label }}</span></p>
          </div>
          <a class="back" [routerLink]="'/stock/commandes'"><i class="pi pi-arrow-left"></i> Retour</a>
        </header>
        <div class="card">
          <div class="info-grid">
            <div class="info"><label>Date</label><span>{{ bc()?.orderedAt || '—' }}</span></div>
            <div class="info"><label>Livraison prévue</label><span>{{ bc()?.expectedAt || '—' }}</span></div>
            <div class="info"><label>Fournisseur</label><span>{{ bc()?.supplierName || '—' }}</span></div>
            <div class="info"><label>Lignes</label><span>{{ bc()?.lineCount ?? 0 }}</span></div>
          </div>
          @if (bc()?.notes) { <div class="notes"><i class="pi pi-info-circle"></i> {{ bc()?.notes }}</div> }
          <table class="lines-table">
            <thead><tr><th>Article</th><th class="num">Qté</th><th>Unité</th></tr></thead>
            <tbody>
              @for (line of bc()?.lines ?? []; track line.id) {
                <tr><td>{{ line.itemName || '—' }}</td><td class="num">{{ line.qtyOrdered }}</td><td>{{ line.uom }}</td></tr>
              }
            </tbody>
          </table>
          <div class="actions">
            @if (bc()?.status === 'DRAFT' && canMove()) {
              <button pButton label="Confirmer" icon="pi pi-check" severity="success" (click)="confirm()"></button>
              <button pButton label="Annuler" icon="pi pi-times" severity="danger" [text]="true" (click)="cancel()"></button>
            }
            @if (bc()?.status === 'CONFIRMED' && canMove()) {
              <button pButton label="Réceptionner (BL)" icon="pi pi-truck" severity="info" [routerLink]="'/stock/receptions/new?bcId=' + bc()?.id"></button>
              <button pButton label="Annuler" icon="pi pi-times" severity="danger" [text]="true" (click)="cancel()"></button>
            }
          </div>
        </div>
      } @else if (loading()) {
        <div class="loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      }
    </section>
  `,
  styles: [`.bc-detail { max-width: 800px; }
    .head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
    .head h1 { margin: 0 0 4px; font-size: 22px; font-weight: 800; display: flex; align-items: center; gap: 10px; }
    .head h1 i { color: #2563eb; }
    .head p { margin: 0; color: #64748b; font-size: 14px; }
    .back { color: #475569; text-decoration: none; font-size: 13px; font-weight: 600; display: inline-flex; align-items: center; gap: 6px; }
    .card { background: #fff; border: 1px solid #e2e8f0; border-radius: 14px; padding: 22px; }
    .info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 16px; }
    .info { display: flex; flex-direction: column; gap: 2px; }
    .info label { font-size: 12px; color: #94a3b8; text-transform: uppercase; letter-spacing: .04em; }
    .info span { font-size: 14px; font-weight: 600; }
    .notes { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 12px; margin-bottom: 16px; font-size: 13px; color: #475569; }
    .notes i { color: #94a3b8; margin-right: 6px; }
    .lines-table { width: 100%; border-collapse: collapse; font-size: 14px; margin-bottom: 16px; }
    .lines-table th { background: #f8fafc; color: #64748b; font-weight: 600; font-size: 12px; text-transform: uppercase; text-align: left; padding: 10px 12px; border-bottom: 1px solid #e2e8f0; }
    .lines-table th.num { text-align: right; }
    .lines-table td { padding: 10px 12px; border-bottom: 1px solid #f1f5f9; }
    .lines-table td.num { text-align: right; font-variant-numeric: tabular-nums; }
    .actions { display: flex; gap: 8px; border-top: 1px solid #e2e8f0; padding-top: 16px; }
    .chip { display: inline-flex; align-items: center; gap: 5px; padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 700; background: var(--s); color: var(--c); }
    .loading { text-align: center; padding: 40px; color: #64748b; }
  `]
})
export class BcDetailComponent implements OnInit {
  private purchase = inject(PurchaseService);
  private auth = inject(AuthService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private msgs = inject(MessageService);

  bc = signal<PurchaseOrder | null>(null);
  loading = signal(true);
  canMove = computed(() => this.auth.can(PERM.STOCK_MOVE) || !!this.auth.me()?.isOwner);
  sv = bcStatusVisual;

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) this.purchase.getBc(id).subscribe(bc => { this.bc.set(bc); this.loading.set(false); });
  }

  confirm(): void {
    const bc = this.bc(); if (!bc) return;
    this.purchase.confirmBc(bc.id).subscribe(updated => { if (updated) { this.bc.set(updated); this.msgs.add({ severity: 'success', summary: 'BC confirmé' }); } });
  }

  cancel(): void {
    const bc = this.bc(); if (!bc) return;
    this.purchase.cancelBc(bc.id, 'Annulé depuis le détail').subscribe(updated => { if (updated) { this.bc.set(updated); this.msgs.add({ severity: 'info', summary: 'BC annulé' }); } });
  }
}
