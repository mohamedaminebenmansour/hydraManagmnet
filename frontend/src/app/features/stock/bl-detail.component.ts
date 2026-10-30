import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink, ActivatedRoute } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { MessageService } from 'primeng/api';
import { PurchaseService } from '@app/core/services/purchase.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { BonLivraison, blStatusVisual, formatDateTime } from '@app/core/models/stock.model';

@Component({
  selector: 'app-bl-detail',
  standalone: true,
  imports: [RouterLink, ButtonModule],
  template: `
    <section class="bl-detail">
      @if (bl()) {
        <header class="head">
          <div>
            <h1><i class="pi pi-truck"></i> {{ bl()?.reference || 'BL sans référence' }}</h1>
            <p>{{ bl()?.projectName }} — <span class="chip" [style.--c]="sv(bl()!.status).color" [style.--s]="sv(bl()!.status).soft"><i class="pi" [class]="sv(bl()!.status).icon"></i> {{ sv(bl()!.status).label }}</span></p>
          </div>
          <a class="back" [routerLink]="'/stock/receptions'"><i class="pi pi-arrow-left"></i> Retour</a>
        </header>
        <div class="card">
          <div class="info-grid">
            <div class="info"><label>BC lié</label><span>{{ bl()?.bcReference || '—' }}</span></div>
            <div class="info"><label>Lieu</label><span>{{ bl()?.locationName || '—' }}</span></div>
            <div class="info"><label>Reçu le</label><span>{{ fmt(bl()?.receivedAt) }}</span></div>
            <div class="info"><label>Lignes</label><span>{{ bl()?.lineCount ?? 0 }}</span></div>
          </div>
          <table class="lines-table">
            <thead><tr><th>Article</th><th class="num">Qté reçue</th><th>Unité</th></tr></thead>
            <tbody>
              @for (line of bl()?.lines ?? []; track line.id) {
                <tr><td>{{ line.itemName || '—' }}</td><td class="num">{{ line.qtyReceived }}</td><td>{{ line.uom }}</td></tr>
              }
            </tbody>
          </table>
          <div class="actions">
            @if (bl()?.status === 'DRAFT' && canMove()) {
              <button pButton label="Poster (réceptionner)" icon="pi pi-check" severity="success" [loading]="posting()" (click)="post()"></button>
              <button pButton label="Annuler" icon="pi pi-times" severity="danger" [text]="true" (click)="cancel()"></button>
            }
          </div>
        </div>
      } @else if (loading()) { <div class="loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div> }
    </section>
  `,
  styles: [`.bl-detail { max-width: 800px; }
    .head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
    .head h1 { margin: 0 0 4px; font-size: 22px; font-weight: 800; display: flex; align-items: center; gap: 10px; }
    .head h1 i { color: #16a34a; }
    .head p { margin: 0; color: #64748b; font-size: 14px; }
    .back { color: #475569; text-decoration: none; font-size: 13px; font-weight: 600; display: inline-flex; align-items: center; gap: 6px; }
    .card { background: #fff; border: 1px solid #e2e8f0; border-radius: 14px; padding: 22px; }
    .info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; margin-bottom: 16px; }
    .info { display: flex; flex-direction: column; gap: 2px; }
    .info label { font-size: 12px; color: #94a3b8; text-transform: uppercase; }
    .info span { font-size: 14px; font-weight: 600; }
    .lines-table { width: 100%; border-collapse: collapse; font-size: 14px; margin-bottom: 16px; }
    .lines-table th { background: #f8fafc; color: #64748b; font-weight: 600; font-size: 12px; text-transform: uppercase; text-align: left; padding: 10px 12px; border-bottom: 1px solid #e2e8f0; }
    .lines-table th.num { text-align: right; }
    .lines-table td { padding: 10px 12px; border-bottom: 1px solid #f1f5f9; }
    .lines-table td.num { text-align: right; }
    .actions { display: flex; gap: 8px; border-top: 1px solid #e2e8f0; padding-top: 16px; }
    .chip { display: inline-flex; align-items: center; gap: 5px; padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 700; background: var(--s); color: var(--c); }
    .loading { text-align: center; padding: 40px; color: #64748b; }
  `]
})
export class BlDetailComponent implements OnInit {
  private purchase = inject(PurchaseService);
  private auth = inject(AuthService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private msgs = inject(MessageService);
  bl = signal<BonLivraison | null>(null);
  loading = signal(true);
  posting = signal(false);
  canMove = computed(() => this.auth.can(PERM.STOCK_MOVE) || !!this.auth.me()?.isOwner);
  sv = blStatusVisual;
  fmt = formatDateTime;
  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) this.purchase.getBl(id).subscribe(bl => { this.bl.set(bl); this.loading.set(false); });
  }
  post(): void {
    const bl = this.bl(); if (!bl) return;
    this.posting.set(true);
    this.purchase.postBl(bl.id).subscribe(updated => {
      this.posting.set(false);
      if (updated) { this.bl.set(updated); this.msgs.add({ severity: 'success', summary: 'BL réceptionné', detail: 'Stock mis à jour' }); }
    });
  }
  cancel(): void {
    const bl = this.bl(); if (!bl) return;
    this.purchase.cancelBl(bl.id, 'Annulé').subscribe(updated => { if (updated) { this.bl.set(updated); this.msgs.add({ severity: 'info', summary: 'BL annulé' }); } });
  }
}
