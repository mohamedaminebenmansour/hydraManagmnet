import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { Router, RouterLink, ActivatedRoute } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { MessageService } from 'primeng/api';
import { PurchaseService } from '@app/core/services/purchase.service';
import { DocumentService, OpenOutcome } from '@app/core/services/document.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { PurchaseOrder, bcStatusVisual } from '@app/core/models/stock.model';

@Component({
  selector: 'app-bc-detail',
  standalone: true,
  imports: [RouterLink, ButtonModule, DialogModule],
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
          @if (bc()?.documentId) {
            <div class="doc-link">
              <i class="pi pi-file-pdf"></i>
              <span class="doc-label">BC numérisé</span>
              <button pButton label="Ouvrir" icon="pi pi-external-link" [text]="true" severity="info" (click)="openProof()"></button>
              <button pButton label="Télécharger" icon="pi pi-download" [text]="true" (click)="downloadProof()"></button>
              <!-- PHASE-B-DOC-VIEW -->
            </div>
          }
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
              <button pButton label="Clôturer la commande" icon="pi pi-lock" severity="warn" (click)="closeVisible.set(true)"></button>
              <button pButton label="Annuler" icon="pi pi-times" severity="danger" [text]="true" (click)="cancel()"></button>
            }
          </div>
        </div>
      } @else if (loading()) {
        <div class="loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      }
      <p-dialog header="Clôturer la commande" [(visible)]="closeVisible" [modal]="true" [style]="{ width: '420px' }">
        <div class="close-hint">
          <i class="pi pi-info-circle"></i>
          Cette action est définitive. La commande sera marquée comme clôturée.
          <br><br>
          <small>Tous les lignes doivent être conformes (MATCHED). Sinon, la clôture sera refusée.</small>
        </div>
        <ng-template pTemplate="footer">
          <p-button label="Annuler" [text]="true" (onClick)="closeVisible.set(false)" />
          <p-button label="Clôturer" icon="pi pi-lock" severity="warn" [loading]="closing()" (onClick)="confirmClose()" />
        </ng-template>
      </p-dialog>
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
    .doc-link { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; padding: 10px 12px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; font-size: 14px; }
    .doc-link i { color: #dc2626; }
    .doc-label { font-weight: 600; color: #475569; margin-right: 4px; }
    .chip { display: inline-flex; align-items: center; gap: 5px; padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 700; background: var(--s); color: var(--c); }
    .loading { text-align: center; padding: 40px; color: #64748b; }
    .close-hint { font-size: 14px; color: #475569; line-height: 1.5; }
    .close-hint i { color: #d97706; margin-right: 4px; }
    .close-hint small { color: #94a3b8; }
  `]
})
export class BcDetailComponent implements OnInit {
  private purchase = inject(PurchaseService);
  private documentService = inject(DocumentService); // PHASE-B-DOC-VIEW
  private auth = inject(AuthService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private msgs = inject(MessageService);

  bc = signal<PurchaseOrder | null>(null);
  loading = signal(true);
  canMove = computed(() => this.auth.can(PERM.STOCK_MOVE) || !!this.auth.me()?.isOwner);
  closeVisible = signal(false);
  sv = bcStatusVisual;

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) this.purchase.getBc(id).subscribe(bc => { this.bc.set(bc); this.loading.set(false); });
  }

  confirm(): void {
    const bc = this.bc(); if (!bc) return;
    this.purchase.confirmBc(bc.id).subscribe(updated => { if (updated) { this.bc.set(updated); this.msgs.add({ severity: 'success', summary: 'BC confirmé' }); } });
  }

  closing = signal(false);

  confirmClose(): void {
    const bc = this.bc(); if (!bc) return;
    this.closing.set(true);
    this.purchase.closeBc(bc.id).subscribe(updated => {
      this.closing.set(false);
      this.closeVisible.set(false);
      if (updated) {
        this.bc.set(updated);
        this.msgs.add({ severity: 'success', summary: 'Commande clôturée', detail: 'Le bon de commande est maintenant clos.' });
      }
    });
  }

  cancel(): void {
    const bc = this.bc(); if (!bc) return;
    this.purchase.cancelBc(bc.id, 'Annulé depuis le détail').subscribe(updated => { if (updated) { this.bc.set(updated); this.msgs.add({ severity: 'info', summary: 'BC annulé' }); } });
  }


  // PHASE-B-DOC-VIEW — Ouvrir / Télécharger the linked BC document (latest revision).
  onProofOutcome(o: OpenOutcome, verb: 'Ouverture' | 'Téléchargement'): void {
    if (o.kind === 'error') this.msgs.add({ severity: 'error', summary: verb + ' impossible', detail: o.message });
    else if (o.kind === 'downloaded' && o.reason === 'popup-blocked')
      this.msgs.add({ severity: 'info', summary: 'Fenêtre bloquée', detail: 'Le fichier a été téléchargé à la place.' });
  }
  openProof(): void {
    const bc = this.bc(); if (!bc?.documentId) return;
    this.documentService.openDocument(bc.documentId, bc.reference).subscribe(o => this.onProofOutcome(o, 'Ouverture'));
  }
  downloadProof(): void {
    const bc = this.bc(); if (!bc?.documentId) return;
    this.documentService.downloadDocument(bc.documentId, bc.reference).subscribe(o => this.onProofOutcome(o, 'Téléchargement'));
  }
}
