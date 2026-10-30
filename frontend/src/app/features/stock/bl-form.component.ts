import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, ActivatedRoute } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { InputNumberModule } from 'primeng/inputnumber';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';
import { MessageService } from 'primeng/api';
import { PurchaseService } from '@app/core/services/purchase.service';
import { StockService } from '@app/core/services/stock.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { PurchaseOrder, BonLivraison } from '@app/core/models/stock.model';

@Component({
  selector: 'app-bl-form',
  standalone: true,
  imports: [FormsModule, RouterLink, ButtonModule, InputTextModule, InputNumberModule, SelectModule, TextareaModule],
  template: `
    <section class="bl-form">
      <header class="head">
        <h1><i class="pi pi-truck"></i> Nouvelle réception</h1>
        <a class="back" [routerLink]="'/stock/receptions'"><i class="pi pi-arrow-left"></i> Retour</a>
      </header>
      <div class="card">
        <div class="grid">
          <div class="field">
            <label>Projet <span class="req">*</span></label>
            <p-select [options]="projects()" [(ngModel)]="projectId" placeholder="Sélectionner"
                      optionLabel="name" optionValue="id" filter="true" styleClass="w-100" (onChange)="onProjectChange()" />
          </div>
          <div class="field">
            <label>Bon de commande</label>
            <p-select [options]="confirmedBcs()" [(ngModel)]="bcId" placeholder="Sélectionner un BC confirmé"
                      optionLabel="label" optionValue="id" [disabled]="!projectId" styleClass="w-100" (onChange)="onBcChange()" />
          </div>
          <div class="field">
            <label>Lieu de réception <span class="req">*</span></label>
            <p-select [options]="locationOptions()" [(ngModel)]="locationId" placeholder="Sélectionner"
                      optionLabel="label" optionValue="id" styleClass="w-100" />
          </div>
          <div class="field">
            <label>Référence BL</label>
            <input pInputText [(ngModel)]="reference" placeholder="BL-2026-001" />
          </div>
          <div class="field field-wide">
            <label>Notes</label>
            <textarea pTextarea [(ngModel)]="notes" rows="2"></textarea>
          </div>
        </div>

        @if (lines().length > 0) {
          <div class="lines-section">
            <h3><i class="pi pi-list"></i> Lignes de réception</h3>
            <table class="lines-table">
              <thead><tr><th>Article</th><th class="num">Commandé</th><th class="num">À recevoir</th><th>Unité</th></tr></thead>
              <tbody>
                @for (line of lines(); track $index) {
                  <tr>
                    <td>{{ line.itemName }}</td>
                    <td class="num">{{ line.qtyOrdered }}</td>
                    <td class="num">
                      <p-inputNumber [(ngModel)]="line.qtyReceived" mode="decimal" [minFractionDigits]="0" [maxFractionDigits]="3"
                                     [min]="0" [max]="line.qtyOrdered" styleClass="qty-input" />
                    </td>
                    <td>{{ line.uom }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }

        <div class="actions">
          <p-button label="Annuler" [text]="true" routerLink="/stock/receptions" />
          <p-button label="Créer brouillon" icon="pi pi-save" severity="info" [disabled]="!projectId || !locationId" [loading]="saving()" (onClick)="createDraft()"></p-button>
        </div>
      </div>
    </section>
  `,
  styles: [`.bl-form { max-width: 800px; }
    .head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
    .head h1 { margin: 0; font-size: 22px; font-weight: 800; display: flex; align-items: center; gap: 10px; }
    .head h1 i { color: #16a34a; }
    .back { color: #475569; text-decoration: none; font-size: 13px; font-weight: 600; display: inline-flex; align-items: center; gap: 6px; }
    .card { background: #fff; border: 1px solid #e2e8f0; border-radius: 14px; padding: 22px; display: flex; flex-direction: column; gap: 18px; }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
    .field { display: flex; flex-direction: column; gap: 6px; }
    .field.field-wide { grid-column: 1 / -1; }
    .field label { font-size: 13px; font-weight: 600; color: #475569; }
    .req { color: #dc2626; }
    :host ::ng-deep .w-100 { width: 100%; }
    :host ::ng-deep textarea { width: 100%; resize: vertical; }
    .lines-section { border-top: 1px solid #e2e8f0; padding-top: 16px; }
    .lines-section h3 { font-size: 15px; font-weight: 700; color: #475569; margin: 0 0 12px; display: flex; align-items: center; gap: 8px; }
    .lines-table { width: 100%; border-collapse: collapse; font-size: 14px; }
    .lines-table th { background: #f8fafc; color: #64748b; font-weight: 600; font-size: 12px; text-transform: uppercase; text-align: left; padding: 10px 12px; border-bottom: 1px solid #e2e8f0; }
    .lines-table th.num { text-align: right; }
    .lines-table td { padding: 10px 12px; border-bottom: 1px solid #f1f5f9; }
    .lines-table td.num { text-align: right; }
    :host ::ng-deep .qty-input { width: 100px; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; border-top: 1px solid #e2e8f0; padding-top: 16px; }
    @media (max-width: 640px) { .grid { grid-template-columns: 1fr; } }
  `]
})
export class BlFormComponent implements OnInit {
  private purchase = inject(PurchaseService);
  private stock = inject(StockService);
  private auth = inject(AuthService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private msgs = inject(MessageService);

  canMove = computed(() => this.auth.can(PERM.STOCK_MOVE) || !!this.auth.me()?.isOwner);
  saving = signal(false);
  projectId: string | null = null;
  bcId: string | null = null;
  locationId: string | null = null;
  reference = '';
  notes = '';
  lines = signal<{ bcLineId: string; itemId: string; itemName: string; qtyOrdered: number; qtyReceived: number; uom: string }[]>([]);
  projects = signal<{ id: string; name: string }[]>([]);
  confirmedBcs = signal<{ id: string; label: string }[]>([]);

  ngOnInit(): void {
    this.stock.loadItems().subscribe();
    this.stock.loadLocations().subscribe();
    this.loadProjects();
    // Check for ?bcId= in URL (from BC detail "Réceptionner" button)
    this.route.queryParams.subscribe(p => {
      if (p['bcId']) {
        this.bcId = p['bcId'];
        this.purchase.getBc(p['bcId']).subscribe(bc => {
          if (bc) {
            this.projectId = bc.projectId;
            this.onBcChange();
          }
        });
      }
    });
  }

  private loadProjects(): void {
    // Reuse ProjectService via a simple http call through stock service cache isn't ideal; use inline
    // Actually ProjectService.options() returns Observable — but we didn't inject it. Use stock.locations to derive projects.
    // Simpler: inject ProjectService
  }

  locationOptions = computed(() => this.stock.locations().filter(l => l.active).map(l => ({
    id: l.id,
    label: `${l.name}${l.type === 'CENTRAL' ? ' (central)' : l.projectName ? ' · ' + l.projectName : ''}`,
  })));

  onProjectChange(): void {
    this.bcId = null;
    this.lines.set([]);
    if (this.projectId) {
      this.purchase.loadBcs(true, this.projectId, 'CONFIRMED').subscribe(bcs => {
        this.confirmedBcs.set(bcs.map(bc => ({ id: bc.id, label: bc.reference || bc.id })));
      });
    }
  }

  onBcChange(): void {
    if (!this.bcId) { this.lines.set([]); return; }
    this.purchase.getBc(this.bcId).subscribe(bc => {
      if (bc?.lines) {
        this.lines.set(bc.lines.map(l => ({
          bcLineId: l.id, itemId: l.itemId, itemName: l.itemName || '—',
          qtyOrdered: l.qtyOrdered, qtyReceived: l.qtyOrdered, uom: l.uom,
        })));
      }
    });
  }

  createDraft(): void {
    if (!this.projectId || !this.locationId) return;
    this.saving.set(true);
    const body = {
      projectId: this.projectId,
      bcId: this.bcId,
      locationId: this.locationId,
      reference: this.reference.trim() || null,
      notes: this.notes.trim() || null,
      lines: this.lines().filter(l => l.qtyReceived > 0).map(l => ({
        bcLineId: l.bcLineId, itemId: l.itemId, qtyReceived: l.qtyReceived, uom: l.uom,
      })),
    };
    this.purchase.createBl(body).subscribe(bl => {
      this.saving.set(false);
      if (bl) {
        this.msgs.add({ severity: 'success', summary: 'BL créé', detail: 'Brouillon — prête à être postée' });
        this.router.navigate(['/stock/receptions', bl.id]);
      }
    });
  }
}
