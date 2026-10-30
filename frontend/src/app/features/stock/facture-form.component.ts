import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { InputNumberModule } from 'primeng/inputnumber';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';
import { DatePickerModule } from 'primeng/datepicker';
import { MessageService } from 'primeng/api';
import { PurchaseService } from '@app/core/services/purchase.service';
import { StockService } from '@app/core/services/stock.service';
import { ProjectService } from '@app/core/services/project.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';

@Component({
  selector: 'app-facture-form',
  standalone: true,
  imports: [FormsModule, RouterLink, ButtonModule, InputTextModule, InputNumberModule, SelectModule, TextareaModule, DatePickerModule],
  template: `
    <section class="fac-form">
      <header class="head">
        <h1><i class="pi pi-file"></i> Nouvelle facture</h1>
        <a class="back" [routerLink]="'/stock/factures'"><i class="pi pi-arrow-left"></i> Retour</a>
      </header>
      <div class="card">
        <div class="grid">
          <div class="field">
            <label>Projet <span class="req">*</span></label>
            <p-select [options]="projects()" [(ngModel)]="projectId" placeholder="Sélectionner" optionLabel="name" optionValue="id" filter="true" styleClass="w-100" />
          </div>
          <div class="field">
            <label>Bon de commande</label>
            <p-select [options]="confirmedBcs()" [(ngModel)]="bcId" placeholder="Optionnel" optionLabel="label" optionValue="id" [showClear]="true" styleClass="w-100" />
          </div>
          <div class="field">
            <label>Référence facture</label>
            <input pInputText [(ngModel)]="reference" placeholder="N° facture fournisseur" />
          </div>
          <div class="field">
            <label>Date facture</label>
            <p-datepicker [(ngModel)]="invoiceDate" dateFormat="dd/mm/yy" [showIcon]="true" styleClass="w-100" />
          </div>
          <div class="field">
            <label>Montant HT</label>
            <p-inputNumber [(ngModel)]="amountHt" mode="decimal" [minFractionDigits]="3" [maxFractionDigits]="3" styleClass="w-100" />
          </div>
          <div class="field">
            <label>TVA</label>
            <p-inputNumber [(ngModel)]="amountTva" mode="decimal" [minFractionDigits]="3" [maxFractionDigits]="3" styleClass="w-100" />
          </div>
          <div class="field field-wide">
            <label>Notes</label>
            <textarea pTextarea [(ngModel)]="notes" rows="2"></textarea>
          </div>
        </div>
        <div class="lines-section">
          <h3><i class="pi pi-list"></i> Lignes</h3>
          @for (line of lines(); track $index) {
            <div class="line-row">
              <input pInputText [(ngModel)]="line.description" placeholder="Description" class="desc-input" />
              <p-inputNumber [(ngModel)]="line.qty" mode="decimal" [minFractionDigits]="0" [maxFractionDigits]="3" [min]="0.001" placeholder="Qté" styleClass="qty-input" />
              <input pInputText [(ngModel)]="line.uom" placeholder="Unité" class="uom-input" />
              <p-inputNumber [(ngModel)]="line.unitPrice" mode="decimal" [minFractionDigits]="3" [maxFractionDigits]="3" placeholder="Prix unit." styleClass="price-input" />
              <button pButton icon="pi pi-times" [text]="true" severity="danger" (click)="removeLine($index)"></button>
            </div>
          }
          <button pButton label="Ajouter une ligne" icon="pi pi-plus" [text]="true" (click)="addLine()"></button>
        </div>
        <div class="actions">
          <p-button label="Annuler" [text]="true" routerLink="/stock/factures" />
          <p-button label="Créer la facture" icon="pi pi-check" severity="success" [disabled]="!projectId || lines().length === 0" [loading]="saving()" (onClick)="submit()"></p-button>
        </div>
      </div>
    </section>
  `,
  styles: [`
    .fac-form { max-width: 800px; }
    .head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
    .head h1 { margin: 0; font-size: 22px; font-weight: 800; display: flex; align-items: center; gap: 10px; }
    .head h1 i { color: #7c3aed; }
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
    .line-row { display: flex; gap: 8px; align-items: center; margin-bottom: 8px; }
    .desc-input { flex: 2; }
    :host ::ng-deep .qty-input { width: 100px; }
    .uom-input { width: 80px; }
    :host ::ng-deep .price-input { width: 120px; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; border-top: 1px solid #e2e8f0; padding-top: 16px; }
    @media (max-width: 640px) { .grid { grid-template-columns: 1fr; } }
  `]
})
export class FactureFormComponent implements OnInit {
  private purchase = inject(PurchaseService);
  private stock = inject(StockService);
  private projectService = inject(ProjectService);
  private auth = inject(AuthService);
  private router = inject(Router);
  private msgs = inject(MessageService);
  saving = signal(false);
  projectId: string | null = null;
  bcId: string | null = null;
  reference = '';
  invoiceDate: Date = new Date();
  amountHt: number | null = null;
  amountTva: number | null = null;
  notes = '';
  lines = signal<{ description: string; qty: number | null; uom: string; unitPrice: number | null }[]>([{ description: '', qty: null, uom: 'sac', unitPrice: null }]);
  projects = signal<{ id: string; name: string }[]>([]);
  confirmedBcs = signal<{ id: string; label: string }[]>([]);
  canMove = computed(() => this.auth.can(PERM.STOCK_MOVE) || !!this.auth.me()?.isOwner);
  ngOnInit(): void {
    this.projectService.options().subscribe(opts => this.projects.set((opts ?? []).map(o => ({ id: o.id, name: o.name }))));
  }
  addLine(): void { this.lines.update(l => [...l, { description: '', qty: null, uom: 'sac', unitPrice: null }]); }
  removeLine(idx: number): void { this.lines.update(l => l.filter((_, i) => i !== idx)); }
  submit(): void {
    if (!this.projectId || this.lines().length === 0) return;
    this.saving.set(true);
    this.purchase.createFacture({
      projectId: this.projectId,
      bcId: this.bcId,
      reference: this.reference.trim() || null,
      invoiceDate: this.invoiceDate.toISOString().slice(0, 10),
      amountHt: this.amountHt,
      amountTva: this.amountTva,
      amountTtc: (this.amountHt ?? 0) + (this.amountTva ?? 0) || null,
      notes: this.notes.trim() || null,
      lines: this.lines().filter(l => l.description.trim() && l.qty && l.qty > 0).map(l => ({
        description: l.description.trim(),
        qty: l.qty,
        uom: l.uom || 'sac',
        unitPrice: l.unitPrice,
      })),
    }).subscribe(f => {
      this.saving.set(false);
      if (f) {
        this.msgs.add({ severity: 'success', summary: 'Facture créée', detail: f.reference || f.id });
        this.router.navigate(['/stock/factures', f.id]);
      }
    });
  }
}
