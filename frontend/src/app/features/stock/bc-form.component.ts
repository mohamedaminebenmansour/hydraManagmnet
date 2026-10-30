import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink, ActivatedRoute } from '@angular/router';
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
import { StockItem } from '@app/core/models/stock.model';

@Component({
  selector: 'app-bc-form',
  standalone: true,
  imports: [FormsModule, RouterLink, ButtonModule, InputTextModule, InputNumberModule, SelectModule, TextareaModule, DatePickerModule],
  template: `
    <section class="bc-form">
      <header class="head">
        <h1><i class="pi pi-shopping-cart"></i> Nouveau bon de commande</h1>
        <a class="back" [routerLink]="'/stock/commandes'"><i class="pi pi-arrow-left"></i> Retour</a>
      </header>
      <div class="card">
        <div class="grid">
          <div class="field">
            <label>Projet <span class="req">*</span></label>
            <p-select [options]="projects()" [(ngModel)]="projectId" placeholder="Sélectionner"
                      optionLabel="name" optionValue="id" filter="true" styleClass="w-100" />
          </div>
          <div class="field">
            <label>Référence</label>
            <input pInputText [(ngModel)]="reference" placeholder="BC-2026-001" />
          </div>
          <div class="field">
            <label>Date commande</label>
            <p-datepicker [(ngModel)]="orderedAt" dateFormat="dd/mm/yy" [showIcon]="true" styleClass="w-100" />
          </div>
          <div class="field">
            <label>Livraison prévue</label>
            <p-datepicker [(ngModel)]="expectedAt" dateFormat="dd/mm/yy" [showIcon]="true" styleClass="w-100" />
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
              <p-select [options]="itemOptions()" [(ngModel)]="line.itemId" placeholder="Article"
                        optionLabel="label" optionValue="id" filter="true" styleClass="line-item" />
              <p-inputNumber [(ngModel)]="line.qty" mode="decimal" [minFractionDigits]="0" [maxFractionDigits]="3"
                             [min]="0.001" placeholder="Qté" styleClass="line-qty" />
              <input pInputText [(ngModel)]="line.uom" placeholder="Unité" class="uom-input" />
              <button pButton icon="pi pi-times" [text]="true" severity="danger" (click)="removeLine($index)"></button>
            </div>
          }
          <button pButton label="Ajouter une ligne" icon="pi pi-plus" [text]="true" (click)="addLine()"></button>
        </div>

        <div class="actions">
          <p-button label="Annuler" [text]="true" routerLink="/stock/commandes" />
          <p-button label="Créer le BC" icon="pi pi-check" severity="success"
                    [disabled]="!projectId || lines().length === 0" [loading]="saving()"
                    (onClick)="submit()"></p-button>
        </div>
      </div>
    </section>
  `,
  styles: [`.bc-form { max-width: 800px; }
    .head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
    .head h1 { margin: 0; font-size: 22px; font-weight: 800; display: flex; align-items: center; gap: 10px; }
    .head h1 i { color: #2563eb; }
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
    :host ::ng-deep .line-item { flex: 2; }
    :host ::ng-deep .line-qty { flex: 1; min-width: 100px; }
    .uom-input { width: 80px; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; border-top: 1px solid #e2e8f0; padding-top: 16px; }
    @media (max-width: 640px) { .grid { grid-template-columns: 1fr; } }
  `]
})
export class BcFormComponent implements OnInit {
  private purchase = inject(PurchaseService);
  private stock = inject(StockService);
  private projectService = inject(ProjectService);
  private auth = inject(AuthService);
  private router = inject(Router);
  private msgs = inject(MessageService);

  canMove = computed(() => this.auth.can(PERM.STOCK_MOVE) || !!this.auth.me()?.isOwner);
  saving = signal(false);

  projectId: string | null = null;
  reference = '';
  orderedAt: Date = new Date();
  expectedAt: Date | null = null;
  notes = '';

  lines = signal<{ itemId: string | null; qty: number | null; uom: string }[]>([{ itemId: null, qty: null, uom: 'sac' }]);

  projects = signal<{ id: string; name: string }[]>([]);
  itemOptions = computed(() => this.stock.items().filter(i => i.active).map(i => ({ id: i.id, label: `${i.name}${i.code ? ' (' + i.code + ')' : ''}` })));

  ngOnInit(): void {
    this.stock.loadItems().subscribe();
    this.projectService.options().subscribe(opts => this.projects.set((opts ?? []).map(o => ({ id: o.id, name: o.name }))));
  }

  addLine(): void { this.lines.update(l => [...l, { itemId: null, qty: null, uom: 'sac' }]); }
  removeLine(idx: number): void { this.lines.update(l => l.filter((_, i) => i !== idx)); }

  submit(): void {
    if (!this.projectId || this.lines().length === 0) return;
    this.saving.set(true);
    this.purchase.createBc({
      projectId: this.projectId,
      reference: this.reference.trim() || null,
      orderedAt: this.orderedAt.toISOString().slice(0, 10),
      expectedAt: this.expectedAt ? this.expectedAt.toISOString().slice(0, 10) : null,
      notes: this.notes.trim() || null,
      lines: this.lines().filter(l => l.itemId && l.qty && l.qty > 0).map(l => ({
        itemId: l.itemId,
        qtyOrdered: l.qty,
        uom: l.uom || 'sac',
      })),
    }).subscribe(bc => {
      this.saving.set(false);
      if (bc) {
        this.msgs.add({ severity: 'success', summary: 'BC créé', detail: bc.reference || bc.id });
        this.router.navigate(['/stock/commandes', bc.id]);
      }
    });
  }
}
