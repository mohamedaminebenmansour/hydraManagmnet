import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink, ActivatedRoute } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { MessageService } from 'primeng/api';
import { PurchaseService } from '@app/core/services/purchase.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { Facture, factureStatusVisual, formatDate, formatQty } from '@app/core/models/stock.model';

@Component({
  selector: 'app-facture-detail',
  standalone: true,
  imports: [RouterLink, ButtonModule],
  template: `
    <section class="fac-detail">
      @if (fac()) {
        <header class="head">
          <div>
            <h1><i class="pi pi-file"></i> {{ fac()?.reference || 'Facture sans référence' }}</h1>
            <p>{{ fac()?.projectName }} — <span class="chip" [style.--c]="sv(fac()!.status).color" [style.--s]="sv(fac()!.status).soft"><i class="pi" [class]="sv(fac()!.status).icon"></i> {{ sv(fac()!.status).label }}</span></p>
          </div>
          <a class="back" [routerLink]="'/stock/factures'"><i class="pi pi-arrow-left"></i> Retour</a>
        </header>
        <div class="card">
          <div class="info-grid">
            <div class="info"><label>Date</label><span>{{ fmt(fac()?.invoiceDate) }}</span></div>
            <div class="info"><label>Fournisseur</label><span>{{ fac()?.supplierName || '—' }}</span></div>
            <div class="info"><label>BC lié</label><span>{{ fac()?.bcReference || '—' }}</span></div>
            <div class="info"><label>BL lié</label><span>{{ fac()?.blReference || '—' }}</span></div>
            @if (fac()?.amountTtc != null) {
              <div class="info"><label>Montant TTC</label><span>{{ fac()?.amountTtc }} TND</span></div>
            }
          </div>
          @if (fac()?.notes) { <div class="notes"><i class="pi pi-info-circle"></i> {{ fac()?.notes }}</div> }
          <table class="lines-table">
            <thead><tr><th>Description</th><th class="num">Qté</th><th>Unité</th><th class="num">Prix unit.</th><th class="num">Ligne HT</th></tr></thead>
            <tbody>
              @for (line of fac()?.lines ?? []; track line.id) {
                <tr>
                  <td>{{ line.description }}</td>
                  <td class="num">{{ line.qty }}</td>
                  <td>{{ line.uom || '—' }}</td>
                  <td class="num">{{ line.unitPrice != null ? line.unitPrice : '—' }}</td>
                  <td class="num">{{ line.lineHt != null ? line.lineHt : '—' }}</td>
                </tr>
              }
            </tbody>
          </table>
          <div class="actions">
            @if (fac()?.status === 'DRAFT' && canMove()) {
              <button pButton label="Valider" icon="pi pi-check" severity="success" (click)="validate()"></button>
              <button pButton label="Annuler" icon="pi pi-times" severity="danger" [text]="true" (click)="cancel()"></button>
            }
          </div>
        </div>
      } @else if (loading()) { <div class="loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div> }
    </section>
  `,
  styles: [`
    .fac-detail { max-width: 800px; }
    .head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
    .head h1 { margin: 0 0 4px; font-size: 22px; font-weight: 800; display: flex; align-items: center; gap: 10px; }
    .head h1 i { color: #7c3aed; }
    .head p { margin: 0; color: #64748b; font-size: 14px; }
    .back { color: #475569; text-decoration: none; font-size: 13px; font-weight: 600; display: inline-flex; align-items: center; gap: 6px; }
    .card { background: #fff; border: 1px solid #e2e8f0; border-radius: 14px; padding: 22px; }
    .info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 16px; }
    .info { display: flex; flex-direction: column; gap: 2px; }
    .info label { font-size: 12px; color: #94a3b8; text-transform: uppercase; }
    .info span { font-size: 14px; font-weight: 600; }
    .notes { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; padding: 10px 12px; margin-bottom: 16px; font-size: 13px; color: #475569; }
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
export class FactureDetailComponent implements OnInit {
  private purchase = inject(PurchaseService);
  private auth = inject(AuthService);
  private route = inject(ActivatedRoute);
  private msgs = inject(MessageService);
  fac = signal<Facture | null>(null);
  loading = signal(true);
  canMove = computed(() => this.auth.can(PERM.STOCK_MOVE) || !!this.auth.me()?.isOwner);
  sv = factureStatusVisual;
  fmt = formatDate;
  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) this.purchase.getFacture(id).subscribe(f => { this.fac.set(f); this.loading.set(false); });
  }
  validate(): void {
    const f = this.fac(); if (!f) return;
    this.purchase.validateFacture(f.id).subscribe(updated => { if (updated) { this.fac.set(updated); this.msgs.add({ severity: 'success', summary: 'Facture validée' }); } });
  }
  cancel(): void {
    const f = this.fac(); if (!f) return;
    this.purchase.cancelFacture(f.id, 'Annulée').subscribe(updated => { if (updated) { this.fac.set(updated); this.msgs.add({ severity: 'info', summary: 'Facture annulée' }); } });
  }
}
