import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { InputNumberModule } from 'primeng/inputnumber';
import { DatePickerModule } from 'primeng/datepicker';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';
import { MessageService } from 'primeng/api';
import { CashService } from '@app/core/services/cash.service';
import { PartyService } from '@app/core/services/party.service';
import {
  CashMovement, MovementBody, MovementType,
  formatAmount, movementTypeVisual, MOVEMENT_TYPE_VISUAL,
} from '@app/core/models/cash.model';

/**
 * MovementFormComponent — /tresorerie/operations/new (create)
 *                          /tresorerie/operations/:id/edit (edit DRAFT)
 *
 * Visual type selector (3 big cards IN/OUT/TRANSFER with icons+colors),
 * simple fields, prefilled from query params when coming from a pot detail.
 * Buttons: "Enregistrer brouillon" + "Enregistrer et valider".
 */
@Component({
  selector: 'app-movement-form',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    ButtonModule, InputTextModule, InputNumberModule,
    DatePickerModule, SelectModule, TextareaModule, RouterLink,
  ],
  styleUrl: './movement-form.component.scss',
  template: `
    <section class="mv-form">
      <nav class="mf-bc">
        <a routerLink="/tresorerie"><i class="pi pi-wallet"></i> Trésorerie</a>
        <i class="pi pi-angle-right"></i>
        <span>{{ isEdit() ? 'Modifier l\\'opération' : 'Nouvelle opération' }}</span>
      </nav>

      <header class="mf-hero" [style.--mv-gradient]="currentVisual().color">
        <div class="hero-icon"><i class="pi" [class]="currentVisual().icon"></i></div>
        <div class="hero-text">
          <h1>{{ isEdit() ? 'Modifier l\\'opération' : 'Nouvelle opération' }}</h1>
          <p>{{ currentVisual().label }} — {{ currentVisual().verb }} en un clic. L'opération reste en brouillon jusqu'à validation.</p>
        </div>
      </header>

      <!-- STEP 1: visual type selector -->
      <div class="mf-section">
        <h2><span class="s-num">1</span> Type d'opération</h2>
        <div class="type-cards">
          @for (t of typeOptions; track t.key) {
            <button type="button" class="type-card"
                    [class.active]="movementType === t.key"
                    [style.--t-color]="t.visual.color"
                    [style.--t-soft]="t.visual.soft"
                    (click)="movementType = t.key">
              <i class="pi" [class]="t.visual.icon"></i>
              <div class="tc-label">{{ t.visual.label }}</div>
              <div class="tc-verb">{{ t.visual.verb }}</div>
            </button>
          }
        </div>
      </div>

      <!-- STEP 2: amount + date -->
      <div class="mf-section">
        <h2><span class="s-num">2</span> Montant et date</h2>
        <div class="field-row">
          <div class="field">
            <label>Montant <span class="req">*</span></label>
            <p-inputnumber [(ngModel)]="amount" mode="decimal"
                           [minFractionDigits]="3" [maxFractionDigits]="3"
                           placeholder="0,000" [min]="0"
                           inputStyleClass="w-100" styleClass="w-100" />
          </div>
          <div class="field">
            <label>Date <span class="req">*</span></label>
            <p-datepicker [(ngModel)]="movementDate" dateFormat="dd/mm/yy"
                          [showIcon]="true" styleClass="w-100" />
          </div>
        </div>
      </div>

      <!-- STEP 3: caisses (show/hide by type) -->
      <div class="mf-section">
        <h2><span class="s-num">3</span> Caisses</h2>
        <div class="field-row">
          @if (movementType === 'OUT' || movementType === 'TRANSFER') {
            <div class="field">
              <label>Caisse source <span class="req">*</span></label>
              <p-select [options]="potOptions()" optionValue="id" optionLabel="name"
                        [(ngModel)]="fromPotId" placeholder="D'où sort l'argent ?"
                        filter filterPlaceholder="Rechercher" styleClass="w-100" />
            </div>
          }
          @if (movementType === 'IN' || movementType === 'TRANSFER') {
            <div class="field">
              <label>Caisse destination <span class="req">*</span></label>
              <p-select [options]="potOptions()" optionValue="id" optionLabel="name"
                        [(ngModel)]="toPotId" placeholder="Où va l'argent ?"
                        filter filterPlaceholder="Rechercher" styleClass="w-100" />
            </div>
          }
        </div>
        @if (pots().length === 0) {
          <div class="mf-warn">
            <i class="pi pi-exclamation-triangle"></i>
            Aucune caisse — créez d'abord la caisse centrale depuis les
            <a routerLink="/tresorerie/caisses">Caisses</a>.
          </div>
        }
      </div>

      <!-- STEP 4: projet + sous-traitant (optional) -->
      <div class="mf-section">
        <h2><span class="s-num">4</span> Projet et sous-traitant <small>(optionnel)</small></h2>
        <div class="field-row">
          <div class="field">
            <label>Projet concerné</label>
            <p-select [options]="projectOptions()" optionValue="id" optionLabel="name"
                      [(ngModel)]="projectId" placeholder="Aucun (opération générale)"
                      filter filterPlaceholder="Rechercher" styleClass="w-100" [showClear]="true" />
          </div>
          <div class="field">
            <label>Sous-traitant / fournisseur</label>
            <p-select [options]="partyOptions()" optionValue="id" optionLabel="name"
                      [(ngModel)]="partyId" placeholder="Aucun"
                      filter filterPlaceholder="Rechercher" styleClass="w-100" [showClear]="true" />
          </div>
        </div>
        <div class="field">
          <label>Libellé <small>(court)</small></label>
          <input pInputText [(ngModel)]="label" maxlength="120"
                 placeholder="Ex: Paiement Pipetech — Tronçon 3" />
        </div>
        <div class="field">
          <label>Note <small>(optionnel)</small></label>
          <textarea pInputTextarea [(ngModel)]="note" rows="2"
                    placeholder="Détails, référence facture, acompte %, …"></textarea>
        </div>
      </div>

      <!-- Actions -->
      <div class="mf-actions">
        <button pButton label="Annuler" severity="secondary" [text]="true"
                routerLink="/tresorerie"></button>
        <div class="mf-actions-right">
          <button pButton label="Enregistrer brouillon" icon="pi pi-save"
                         severity="info" [outlined]="true"
                         [loading]="saving()"
                         [disabled]="!isValid()"
                         (click)="save(false)"></button>
          @if (!isEdit()) {
            <button pButton label="Enregistrer et valider" icon="pi pi-check"
                           severity="success"
                           [loading]="saving()"
                           [disabled]="!isValid()"
                           (click)="save(true)"></button>
          }
        </div>
      </div>
    </section>
  `,
})
export class MovementFormComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private cashService = inject(CashService);
  private partyService = inject(PartyService);
  private msgs = inject(MessageService);

  // Edit mode
  editId = '';
  isEdit = computed(() => !!this.editId);
  private editing: CashMovement | null = null;

  // Form state
  movementType: MovementType = 'OUT';
  amount: number | null = null;
  movementDate: Date = new Date();
  fromPotId: string | null = null;
  toPotId: string | null = null;
  projectId: string | null = null;
  partyId: string | null = null;
  label = '';
  note = '';

  saving = signal(false);

  pots = this.cashService.pots;
  parties = this.partyService.parties;

  readonly typeOptions = (Object.keys(MOVEMENT_TYPE_VISUAL) as MovementType[]).map(key => ({
    key,
    visual: MOVEMENT_TYPE_VISUAL[key],
  }));

  tv = movementTypeVisual;
  fmt = formatAmount;

  currentVisual = computed(() => movementTypeVisual(this.movementType));

  ngOnInit(): void {
    // Edit mode?
    this.editId = this.route.snapshot.paramMap.get('id') ?? '';
    if (this.editId) {
      this.cashService.getMovement(this.editId).subscribe(m => {
        if (!m) return;
        if (m.status !== 'DRAFT') {
          this.msgs.add({ severity: 'warn', summary: 'Lecture seule',
            detail: 'Seul un brouillon peut être modifié.' });
          this.router.navigate(['/tresorerie/operations', this.editId]);
          return;
        }
        this.editing = m;
        this.movementType = m.movementType;
        this.amount = m.amount;
        this.movementDate = new Date(m.movementDate);
        this.fromPotId = m.fromPotId ?? null;
        this.toPotId = m.toPotId ?? null;
        this.projectId = m.projectId ?? null;
        this.partyId = m.partyId ?? null;
        this.label = m.label ?? '';
        this.note = m.note ?? '';
      });
    } else {
      // Prefill from query params (deep link from pot detail / party detail)
      const qp = this.route.snapshot.queryParamMap;
      const type = qp.get('type') as MovementType | null;
      if (type && ['IN', 'OUT', 'TRANSFER'].includes(type)) {
        this.movementType = type;
      }
      const from = qp.get('fromPotId');
      const to = qp.get('toPotId');
      if (from) this.fromPotId = from;
      if (to) this.toPotId = to;
      const pid = qp.get('projectId');
      if (pid) this.projectId = pid;
      const party = qp.get('partyId');
      if (party) this.partyId = party;
    }

    // Load pots + parties (project options from ProjectService come via cash service pots)
    this.cashService.loadPots().subscribe();
    this.partyService.loadParties().subscribe();
  }

  potOptions = computed(() => this.pots().map(p => ({
    id: p.id, name: p.name, type: p.type,
  })));

  partyOptions = computed(() => this.parties().filter(p => p.active !== false).map(p => ({
    id: p.id, name: `${p.name} (${partyLabel(p.type)})`, type: p.type,
  })));

  projectOptions = computed(() => {
    // Derive project options from PROJECT pots (Phase 1 simplification)
    const seen = new Set<string>();
    const opts: { id: string; name: string }[] = [];
    for (const p of this.pots()) {
      if (p.type === 'PROJECT' && p.projectId && !seen.has(p.projectId)) {
        seen.add(p.projectId);
        opts.push({ id: p.projectId, name: p.name.replace(/^Caisse\s+/, '') });
      }
    }
    return opts;
  });

  isValid(): boolean {
    if (!this.amount || this.amount <= 0) return false;
    if (!this.movementDate) return false;
    switch (this.movementType) {
      case 'IN': return !!this.toPotId;
      case 'OUT': return !!this.fromPotId;
      case 'TRANSFER': return !!this.fromPotId && !!this.toPotId && this.fromPotId !== this.toPotId;
      default: return false;
    }
  }

  save(alsoPost: boolean): void {
    if (!this.isValid()) return;
    this.saving.set(true);

    const body: MovementBody = {
      movementType: this.movementType,
      amount: this.amount!,
      movementDate: this.toYmd(this.movementDate),
      fromPotId: this.fromPotId,
      toPotId: this.toPotId,
      projectId: this.projectId,
      partyId: this.partyId,
      label: this.label.trim() || undefined,
      note: this.note.trim() || undefined,
    };

    if (this.isEdit() && this.editId) {
      // Update DRAFT then optionally post
      this.cashService.updateMovement(this.editId, body).subscribe(m => {
        this.saving.set(false);
        if (m) {
          this.msgs.add({ severity: 'success', summary: 'Brouillon modifié' });
          if (alsoPost) {
            this.postAndNavigate(m.id);
          } else {
            this.router.navigate(['/tresorerie/operations', m.id]);
          }
        }
      });
    } else {
      this.cashService.createMovement(body).subscribe(m => {
        this.saving.set(false);
        if (m) {
          this.msgs.add({ severity: 'success', summary: 'Brouillon créé' });
          if (alsoPost) {
            this.postAndNavigate(m.id);
          } else {
            this.router.navigate(['/tresorerie/operations', m.id]);
          }
        }
      });
    }
  }

  private postAndNavigate(id: string): void {
    this.cashService.postMovement(id).subscribe(res => {
      if (res) {
        this.msgs.add({
          severity: 'success', summary: 'Opération validée',
          detail: 'Le solde de la caisse est à jour.',
        });
        this.cashService.invalidatePots();
        this.router.navigate(['/tresorerie/operations', id]);
      }
    });
  }

  private toYmd(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }
}

function partyLabel(t: string): string {
  const map: Record<string, string> = { SUB: 'sous-traitant', SUPPLIER: 'fournisseur', OTHER: 'autre' };
  return map[t] ?? t;
}
