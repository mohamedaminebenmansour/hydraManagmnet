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
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import {
  CashMovement, MovementBody, MovementType,
  formatAmount, movementTypeVisual, MOVEMENT_TYPE_VISUAL,
} from '@app/core/models/cash.model';

/**
 * MovementFormComponent — /tresorerie/operations/new (create)
 *                          /tresorerie/operations/:id/edit (edit DRAFT)
 *
 * Two layouts:
 *  - OUT create → SHORT PAYMENT UX (B5 + TASK 5): « Montant à payer » (assessed)
 *    + « Montant demandé » (claimed, optional) + motif d'écart when they differ,
 *    sous-traitant + libellé + pièce justificative (facultatif, uploadée après
 *    submission dans le dossier Finance du projet), caisse/projet/note under
 *    « Plus d'options », and a primary « Envoyer pour validation » button
 *    (submitPayment → PAYMENT_APPROVAL). Defaults: OUT, aujourd'hui, contexte
 *    projet via deep-link.
 *  - IN / TRANSFER (and edit mode) → full 4-step layout.
 * Buttons: "Enregistrer brouillon" (CASH_DRAFT) + "Enregistrer et valider"
 * (CASH_POST, IN/TRANSFER only — OUT must go through the approval workflow).
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

      <!-- SHORT PAYMENT UX (B5 + TASK 5): OUT creation — claimed vs assessed,
           tiers, libellé, pièce justificative. Defaults: OUT, aujourd'hui,
           contexte projet (deep-link). CTA = « Envoyer pour validation ». -->
      @if (movementType === 'OUT' && !isEdit()) {
        <div class="mf-section">
          <h2><span class="s-num">2</span> Montants et date</h2>
          <div class="field-row">
            <div class="field">
              <label>Montant à payer <span class="req">*</span></label>
              <p-inputnumber [(ngModel)]="amount" mode="decimal"
                             [minFractionDigits]="3" [maxFractionDigits]="3"
                             placeholder="0,000" [min]="0"
                             inputStyleClass="w-100" styleClass="w-100" />
              <small class="field-hint">Ce que l'entreprise accepte de payer (comptabilisé à la caisse).</small>
            </div>
            <div class="field">
              <label>Date <span class="req">*</span></label>
              <p-datepicker [(ngModel)]="movementDate" dateFormat="dd/mm/yy"
                            [showIcon]="true" styleClass="w-100" />
            </div>
          </div>
          <div class="field-row">
            <div class="field">
              <label>Montant demandé <small>(facultatif)</small></label>
              <p-inputnumber [(ngModel)]="claimedAmount" mode="decimal"
                             [minFractionDigits]="3" [maxFractionDigits]="3"
                             placeholder="Identique au montant à payer" [min]="0"
                             inputStyleClass="w-100" styleClass="w-100" />
              <small class="field-hint">Montant réclamé sur la facture / situation du sous-traitant.</small>
            </div>
            @if (hasVariance()) {
              <div class="field">
                <label>Motif de l'écart <span class="req">*</span></label>
                <textarea pInputTextarea [(ngModel)]="varianceReason" rows="2"
                          placeholder="Ex: retenue de garantie 5%, pénalité de retard, travaux non conformes…"></textarea>
                <small class="field-hint">Obligatoire : vous payez moins que le montant réclamé.</small>
              </div>
            }
          </div>
        </div>

        <div class="mf-section">
          <h2><span class="s-num">3</span> À qui, pour quoi</h2>
          <div class="field">
            <label>Sous-traitant / fournisseur</label>
            <p-select [options]="partyOptions()" optionValue="id" optionLabel="name"
                      [(ngModel)]="partyId" placeholder="Aucun"
                      filter filterPlaceholder="Rechercher" styleClass="w-100" [showClear]="true" />
          </div>
          <div class="field">
            <label>Libellé <small>(court)</small></label>
            <input pInputText [(ngModel)]="label" maxlength="120"
                   placeholder="Ex: Paiement Pipetech — Tronçon 3" />
          </div>
          <div class="field">
            <label>Pièce justificative <small>(facultatif — facture, bon de livraison, situation)</small></label>
            @if (attachmentFile(); as att) {
              <div class="attachment-chip">
                <i class="pi pi-paperclip"></i>
                <span class="att-name" [title]="att.name">{{ att.name }}</span>
                <span class="att-size">{{ fmtSize(att.size) }}</span>
                <button type="button" class="att-remove" (click)="clearAttachment()" aria-label="Retirer la pièce">
                  <i class="pi pi-times"></i>
                </button>
              </div>
            } @else {
              <label class="file-input">
                <input type="file" accept=".pdf,.png,.jpg,.jpeg,.webp"
                       (change)="onAttachmentPicked($event)" />
                <i class="pi pi-upload"></i>
                <span>Choisir un fichier</span>
                <small class="field-hint">PDF ou image — rangé automatiquement dans le dossier Finance du projet.</small>
              </label>
            }
          </div>
        </div>

        <details class="mf-more">
          <summary><i class="pi pi-chevron-down"></i> Plus d'options</summary>
          <div class="mf-more-body">
            <div class="field">
              <label>Caisse source <span class="req">*</span></label>
              <p-select [options]="potOptions()" optionValue="id" optionLabel="name"
                        [(ngModel)]="fromPotId" placeholder="D'où sort l'argent ?"
                        filter filterPlaceholder="Rechercher" styleClass="w-100" />
            </div>
            <div class="field">
              <label>Projet concerné <small>(requis pour une validation)</small></label>
              <p-select [options]="projectOptions()" optionValue="id" optionLabel="name"
                        [(ngModel)]="projectId" placeholder="Aucun (opération générale)"
                        filter filterPlaceholder="Rechercher" styleClass="w-100" [showClear]="true" />
            </div>
            <div class="field">
              <label>Note <small>(optionnel)</small></label>
              <textarea pInputTextarea [(ngModel)]="note" rows="2"
                        placeholder="Détails, référence facture, acompte %, …"></textarea>
            </div>
          </div>
        </details>

        <div class="mf-actions">
          @if (!canDraft) {
            <div class="mf-warn" style="width: 100%">
              <i class="pi pi-lock"></i>
              Lecture seule — vous n'avez pas le droit de créer des opérations.
            </div>
          } @else {
            <button pButton label="Annuler" severity="secondary" [text]="true"
                    routerLink="/tresorerie"></button>
            <div class="mf-actions-right">
              <button pButton label="Enregistrer brouillon" icon="pi pi-save"
                             severity="info" [outlined]="true"
                             [loading]="saving()"
                             [disabled]="!isValid()"
                             (click)="save(false)"></button>
              @if (canSubmit) {
                <button pButton label="Envoyer pour validation" icon="pi pi-send"
                               severity="success"
                               [loading]="saving()"
                               [disabled]="!isValid()"
                               (click)="submitForApproval()"></button>
              }
            </div>
            @if (canSubmit && !projectId) {
              <div class="mf-warn" style="width: 100%">
                <i class="pi pi-info-circle"></i>
                « Envoyer pour validation » nécessite un projet rattaché (Plus d'options).
              </div>
            }
            @if (hasVariance() && !varianceReason.trim()) {
              <div class="mf-warn" style="width: 100%">
                <i class="pi pi-exclamation-triangle"></i>
                Vous payez un montant différent de celui réclamé — indiquez le motif de l'écart.
              </div>
            }
          }
        </div>
      } @else {
      <!-- FULL LAYOUT (IN / TRANSFER / edit mode) -->
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
        @if (!canDraft) {
          <div class="mf-warn" style="width: 100%">
            <i class="pi pi-lock"></i>
            Lecture seule — vous n'avez pas le droit de créer ou modifier des opérations.
          </div>
        } @else {
          <button pButton label="Annuler" severity="secondary" [text]="true"
                  routerLink="/tresorerie"></button>
          <div class="mf-actions-right">
            <button pButton label="Enregistrer brouillon" icon="pi pi-save"
                           severity="info" [outlined]="true"
                           [loading]="saving()"
                           [disabled]="!isValid()"
                           (click)="save(false)"></button>
            @if (!isEdit() && canPost && movementType !== 'OUT') {
              <button pButton label="Enregistrer et valider" icon="pi pi-check"
                             severity="success"
                             [loading]="saving()"
                             [disabled]="!isValid()"
                             (click)="save(true)"></button>
            }
            @if (isEdit() && movementType === 'OUT' && canSubmit) {
              <div class="mf-warn">
                <i class="pi pi-info-circle"></i>
                Pour soumettre ce brouillon au workflow, créez un nouveau paiement —
                la validation part d'une demande fraîche.
              </div>
            }
          </div>
        }
      </div>
      }
    </section>
  `,
})
export class MovementFormComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private cashService = inject(CashService);
  private partyService = inject(PartyService);
  private msgs = inject(MessageService);
  private auth = inject(AuthService);

  // Permission gates (route only requires CASH_READ — write actions re-checked here)
  canDraft = this.auth.can(PERM.CASH_DRAFT);
  canPost = this.auth.can(PERM.CASH_POST);
  canSubmit = this.auth.can(PERM.CASH_SUBMIT);

  // Edit mode
  editId = '';
  isEdit = computed(() => !!this.editId);
  private editing: CashMovement | null = null;

  // Form state
  movementType: MovementType = 'OUT';
  amount: number | null = null;          // assessed — « Montant à payer »
  claimedAmount: number | null = null;   // claimed — « Montant demandé » (optional)
  varianceReason = '';
  movementDate: Date = new Date();
  fromPotId: string | null = null;
  toPotId: string | null = null;
  projectId: string | null = null;
  partyId: string | null = null;
  label = '';
  note = '';

  /** Optional payment proof (facture / BL / situation) — TASK 3 upload after submit. */
  private attachment = signal<File | null>(null);
  attachmentFile = this.attachment.asReadonly();

  /**
   * TASK 5 — claimed ≠ assessed (compared in millimes to dodge float noise).
   * When true, the « Motif de l'écart » field appears and is REQUIRED
   * (mirrors backend validateClaimedAssessed / VARIANCE_REASON_REQUIRED).
   */
  hasVariance(): boolean {
    if (this.claimedAmount == null || this.amount == null) return false;
    return Math.round(this.claimedAmount * 1000) !== Math.round(this.amount * 1000);
  }

  onAttachmentPicked(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    const f = input.files?.[0] ?? null;
    if (!f) return;
    if (f.size > 50 * 1024 * 1024) {
      this.msgs.add({ severity: 'warn', summary: 'Fichier trop volumineux',
        detail: 'La pièce jointe ne doit pas dépasser 50 Mo.' });
      input.value = '';
      return;
    }
    this.attachment.set(f);
  }

  clearAttachment(): void { this.attachment.set(null); }

  /** Human-readable file size (Ko / Mo). */
  fmtSize(bytes: number): string {
    return bytes >= 1024 * 1024
      ? `${(bytes / (1024 * 1024)).toFixed(1)} Mo`
      : `${Math.max(1, Math.round(bytes / 1024))} Ko`;
  }

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
      // Deep-link prefill (e.g. « Dupliquer et soumettre » from a movement detail)
      const amt = qp.get('amount');
      if (amt && Number(amt) > 0) this.amount = Number(amt);
      const lbl = qp.get('label');
      if (lbl) this.label = lbl;
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
    if (this.hasVariance() && !this.varianceReason.trim()) return false; // TASK 5
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
      // TASK 1/5 — claimed vs assessed ride along on OUT draft saves too, so
      // no filled field is lost when the user saves before submitting.
      // (IN/TRANSFER never see these fields — nothing to send.)
      ...(this.movementType === 'OUT' ? {
        claimedAmount: this.effectiveClaimed(),
        assessedAmount: this.amount ?? undefined,
        varianceReason: this.hasVariance() ? this.varianceReason.trim() : undefined,
      } : {}),
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
          severity: 'success', summary: 'Opération comptabilisée',
          detail: 'Le solde de la caisse est à jour.',
        });
        this.cashService.invalidatePots();
        this.router.navigate(['/tresorerie/operations', id]);
      }
    });
  }

  /**
   * Claimed actually sent to the backend: the filled « Montant demandé »,
   * else the assessed amount (spec: claimed defaults to the same value).
   */
  private effectiveClaimed(): number | undefined {
    return this.claimedAmount ?? this.amount ?? undefined;
  }

  /**
   * B5 + TASK 5 — primary CTA for OUT: create the draft AND start the
   * PAYMENT_APPROVAL workflow in one call (submit-payment), carrying
   * claimed vs assessed + variance reason, then upload the optional proof
   * document (Finance folder, TASK 3 backend) before navigating.
   * Requires a project (the backend rejects payments without one).
   */
  submitForApproval(): void {
    if (!this.isValid()) return;
    if (!this.projectId) {
      this.msgs.add({ severity: 'warn', summary: 'Projet requis',
        detail: 'Rattachez le paiement à un projet avant de l\'envoyer pour validation.' });
      return;
    }
    this.saving.set(true);
    const att = this.attachment();
    this.cashService.submitPayment({
      fromPotId: this.fromPotId!,
      amount: this.amount!,
      movementDate: this.toYmd(this.movementDate),
      projectId: this.projectId,
      partyId: this.partyId,
      label: this.label.trim() || undefined,
      note: this.note.trim() || undefined,
      claimedAmount: this.effectiveClaimed(),
      assessedAmount: this.amount ?? undefined,
      varianceReason: this.hasVariance() ? this.varianceReason.trim() : undefined,
    }).subscribe(res => {
      if (!res) {
        this.saving.set(false);
        return;
      }
      if (att) {
        // Best effort: the payment EXISTS now — a failed upload must not
        // swallow the submission (the proof can be re-attached later).
        this.cashService.uploadAttachment(res.movement.id, att,
          this.label.trim() || att.name).subscribe(up => {
          this.saving.set(false);
          if (up) {
            this.msgs.add({ severity: 'success', summary: 'Paiement envoyé',
              detail: 'Pièce justificative rangée dans le dossier Finance du projet.' });
          } else {
            this.msgs.add({ severity: 'warn', summary: 'Paiement envoyé',
              detail: 'La pièce jointe n\'a pas pu être téléversée — réessayez depuis la fiche de l\'opération.' });
          }
          this.finishSubmission(res.movement.id);
        });
      } else {
        this.saving.set(false);
        this.msgs.add({ severity: 'success', summary: 'Paiement envoyé',
          detail: 'Il attend maintenant la validation du workflow d\'approbation.' });
        this.finishSubmission(res.movement.id);
      }
    });
  }

  private finishSubmission(movementId: string): void {
    this.attachment.set(null);
    this.router.navigate(['/tresorerie/operations', movementId]);
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
