import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { MessageService } from 'primeng/api';
import { CashService } from '@app/core/services/cash.service';
import { PartyService } from '@app/core/services/party.service';
import { ProcessService } from '@app/core/services/process.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { ProcessTimeline } from '@app/core/models/process.model';
import {
  CashMovement, MovementTimeline, formatAmount, formatDate,
  movementTypeVisual, movementStatusVisual, movementPending,
  timelineActionVisual, parseTimelineDetails,
  REJECTED_VISUAL, movementRejected,
} from '@app/core/models/cash.model';

/**
 * MovementDetailComponent — /tresorerie/operations/:id
 *
 * Shows all fields + status chip + audit timeline (B6, GET /timeline).
 * DRAFT (no process) IN/TRANSFER → Valider (poster, CASH_POST) + Modifier
 * DRAFT (no process) OUT → NO direct post (B5): payments must go through the
 *   approval workflow — offers "Dupliquer et soumettre" (prefilled payment form)
 * DRAFT (process en cours) → "En attente" chip, no action buttons
 * DRAFT (process rejeté)  → "Rejeté" chip (TASK 4) + resubmission shortcut
 * POSTED → Annuler (CASH_VOID) with required reason dialog
 * VOID   → read-only
 */
@Component({
  selector: 'app-movement-detail',
  standalone: true,
  imports: [ButtonModule, DialogModule, InputTextModule, TextareaModule, FormsModule, DatePipe, RouterLink],
  styleUrl: './movement-detail.component.scss',
  template: `
    <section class="mv-detail">
      <nav class="md-bc">
        <a routerLink="/tresorerie"><i class="pi pi-wallet"></i> Trésorerie</a>
        <i class="pi pi-angle-right"></i>
        @if (movement(); as m) {
          <span>{{ tv(m.movementType).label }} — {{ fmt(m.amount) }}</span>
        }
      </nav>

      @if (loading()) {
        <div class="md-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else {
        @let m = movement();
        @if (m) {
        <!-- Hero -->
        <header class="md-hero"
                [style.--mv-color]="tv(m.movementType).color"
                [style.--mv-soft]="tv(m.movementType).soft">
          <div class="md-icon"><i class="pi" [class]="tv(m.movementType).icon"></i></div>
          <div class="md-text">
            <div class="md-title">{{ m.label || tv(m.movementType).label }}</div>
            <div class="md-sub">{{ tv(m.movementType).label }} · {{ formatDate(m.movementDate) }}</div>
          </div>
          <div class="md-right">
            <div class="md-amount" [class.in]="m.movementType === 'IN'" [class.out]="m.movementType === 'OUT'">
              {{ m.movementType === 'IN' ? '+' : '−' }}{{ fmt(m.amount) }}
            </div>
            <span class="md-status" [style.--st-color]="chip(m).color" [style.--st-soft]="chip(m).soft">
              <i class="pi" [class]="chip(m).icon"></i> {{ chip(m).label }}
            </span>
          </div>
        </header>

        <!-- Details grid -->
        <div class="md-grid">
          <div class="md-field">
            <div class="mdf-label">Caisse source</div>
            <div class="mdf-value">
              @if (m.fromPotId) {
                <a [routerLink]="['/tresorerie/caisses', m.fromPotId]">{{ potName(m.fromPotId) || 'Caisse' }}</a>
              } @else { — }
            </div>
          </div>
          <div class="md-field">
            <div class="mdf-label">Caisse destination</div>
            <div class="mdf-value">
              @if (m.toPotId) {
                <a [routerLink]="['/tresorerie/caisses', m.toPotId]">{{ potName(m.toPotId) || 'Caisse' }}</a>
              } @else { — }
            </div>
          </div>
          <div class="md-field">
            <div class="mdf-label">Sous-traitant</div>
            <div class="mdf-value">
              @if (m.partyId) {
                <a [routerLink]="['/tresorerie/parties', m.partyId]">{{ partyName(m.partyId) || 'Tiers' }}</a>
              } @else { — }
            </div>
          </div>
          <div class="md-field">
            <div class="mdf-label">Devise</div>
            <div class="mdf-value">{{ m.currency || 'TND' }}</div>
          </div>
          @if (m.claimedAmount != null || m.assessedAmount != null) {
            <!-- TASK 5 — claimed vs assessed (« where every dinar went ») -->
            <div class="md-field">
              <div class="mdf-label">Montant demandé</div>
              <div class="mdf-value">{{ fmt(m.claimedAmount ?? m.amount) }}</div>
            </div>
            <div class="md-field">
              <div class="mdf-label">Montant à payer</div>
              <div class="mdf-value assessed">{{ fmt(m.assessedAmount ?? m.amount) }}</div>
            </div>
            @if (m.varianceReason) {
              <div class="md-field md-field-wide">
                <div class="mdf-label">Motif de l'écart</div>
                <div class="mdf-value">{{ m.varianceReason }}</div>
              </div>
            }
          }
          <div class="md-field md-field-wide">
            <div class="mdf-label">Note</div>
            <div class="mdf-value">{{ m.note || '—' }}</div>
          </div>
          @if (m.processInstanceId) {
            <div class="md-field md-field-wide">
              <div class="mdf-label">Processus lié</div>
              <div class="mdf-value">
                <a [routerLink]="['/processes', m.processInstanceId]">
                  <i class="pi pi-sitemap"></i> Voir le workflow
                </a>
              </div>
            </div>
            @if (processTimeline(); as pt) {
              <div class="process-strip">
                <div class="ps-label">Progression du processus</div>
                <div class="ps-steps">
                  @for (s of pt.steps; track s.stepInstanceId) {
                    <div class="ps-step {{ s.status.toLowerCase() }}"
                         [class.ps-current]="s.status === 'PENDING'">
                      <div class="ps-dot">
                        @if (s.status === 'DONE') { <i class="pi pi-check"></i> }
                        @else if (s.status === 'PENDING') { <i class="pi pi-clock"></i> }
                        @else if (s.status === 'REJECTED') { <i class="pi pi-times"></i> }
                        @else { <i class="pi pi-circle"></i> }
                      </div>
                      <div class="ps-info">
                        <div class="ps-name">{{ s.name }}</div>
                        <div class="ps-role">{{ s.assigneeRole }}</div>
                        @if (s.assigneeName) { <div class="ps-who">{{ s.assigneeName }}</div> }
                      </div>
                    </div>
                  }
                </div>
              </div>
            }
          }
          @if (proofDocumentId()) {
            <!-- TASK 6 — open the payment proof stored in the Finance folder -->
            <div class="md-field md-field-wide">
              <div class="mdf-label">Pièce justificative</div>
              <div class="mdf-value proof">
                <i class="pi pi-file-pdf"></i>
                <span class="proof-name" [title]="proofDocumentName()">{{ proofDocumentName() }}</span>
                @if (canReadDocs) {
                  <a class="proof-btn" [routerLink]="['/documents', proofDocumentId()]">
                    <i class="pi pi-eye"></i> Voir la pièce
                  </a>
                }
              </div>
            </div>
          }
          @if (m.status === 'VOID') {
            <div class="md-field md-field-wide">
              <div class="mdf-label">Motif d'annulation</div>
              <div class="mdf-value void-reason">{{ voidReason || '—' }}</div>
            </div>
          }
        </div>

        <!-- Actions -->
        <div class="md-actions">
          @if (pd(m)) {
            <div class="md-note info">
              <i class="pi pi-clock"></i>
              Un processus d'approbation est en cours — l'opération sera comptabilisée automatiquement à la validation.
              <a [routerLink]="['/mes-taches']">Mes tâches</a>
            </div>
          } @else if (m.status === 'DRAFT') {
            @if (canDraft) {
              <button pButton label="Modifier" icon="pi pi-pencil" severity="info" [outlined]="true"
                      [routerLink]="['/tresorerie/operations', m.id, 'edit']"></button>
            }
            @if (m.movementType === 'OUT') {
              <!-- B5: no naked post for OUT — approval workflow only -->
              @if (isRejected()) {
                <!-- TASK 4: the approval was refused → clear French feedback -->
                <div class="md-note danger">
                  <i class="pi pi-times-circle"></i>
                  Paiement refusé lors de la validation — voir l'historique ci-dessous pour le motif.
                </div>
              } @else {
                <div class="md-note muted">
                  <i class="pi pi-shield"></i>
                  Les sorties d'argent passent par le workflow de validation.
                </div>
              }
              @if (canSubmit) {
                <button pButton [label]="isRejected() ? 'Soumettre à nouveau' : 'Dupliquer et soumettre'"
                        icon="pi pi-send" severity="success"
                        [loading]="acting()"
                        (click)="duplicateAndSubmit()"></button>
              }
            } @else if (canPost) {
              <button pButton label="Valider (poster)" icon="pi pi-check" severity="success"
                      [loading]="acting()"
                      (click)="post()"></button>
            } @else {
              <div class="md-note muted">
                <i class="pi pi-lock"></i>
                Seul un utilisateur autorisé peut comptabiliser cette opération — soumettez-la via un processus d'approbation.
              </div>
            }
          } @else if (m.status === 'POSTED') {
            <div class="md-note info">
              <i class="pi pi-lock"></i>
              Opération comptabilisée — le solde de la caisse est à jour.
              @if (canVoid) { Utilisez « Annuler » pour la corriger. }
            </div>
            @if (canVoid) {
              <button pButton label="Annuler (void)" icon="pi pi-ban" severity="danger" [outlined]="true"
                      (click)="showVoidDialog()"></button>
            }
          } @else {
            <div class="md-note muted">
              <i class="pi pi-ban"></i>
              Opération annulée — ignorée dans le calcul du solde. Créez une nouvelle opération si besoin.
            </div>
          }
        </div>

        <!-- B6: audit timeline from GET /api/cash/movements/{id}/timeline -->
        @if (timeline(); as t) {
          <div class="md-timeline">
            <h2><i class="pi pi-history"></i> Historique</h2>
            @if (t.events.length === 0) {
              <div class="md-note muted"><i class="pi pi-info-circle"></i> Aucun événement enregistré.</div>
            } @else {
              <ol class="tl-list">
                @for (ev of t.events; track $index) {
                  <li class="tl-item">
                    <span class="tl-dot" [style.background]="tav(ev.action).color">
                      <i class="pi" [class]="tav(ev.action).icon"></i>
                    </span>
                    <div class="tl-body">
                      <div class="tl-head">
                        <span class="tl-action">{{ tav(ev.action).label }}</span>
                        @if (sourceBadge(ev); as sb) {
                          <span class="tl-source" [style.--sb-color]="sb.color">{{ sb.label }}</span>
                        }
                        @if (ev.at) { <span class="tl-date">{{ fmtDateTime(ev.at) }}</span> }
                      </div>
                      <div class="tl-meta">
                        @if (ev.userName) { <span><i class="pi pi-user"></i> {{ ev.userName }}</span> }
                        @if (eventNote(ev)) { <span class="tl-reason">{{ eventNote(ev) }}</span> }
                      </div>
                    </div>
                  </li>
                }
              </ol>
            }
          </div>
        }
        } @else {
        <div class="md-empty">
          <i class="pi pi-exclamation-circle"></i>
          <p>Opération introuvable.</p>
          <button pButton label="Retour à la trésorerie" severity="info" routerLink="/tresorerie"></button>
        </div>
        }
      }

      <!-- Void dialog -->
      <p-dialog header="Annuler l'opération" [(visible)]="voidVisible" [modal]="true"
                [style]="{ width: '440px' }">
        <div class="void-form">
          <p class="void-warn">
            <i class="pi pi-exclamation-triangle"></i>
            L'opération sera marquée <strong>Annulée</strong> et ignorée du solde.
            Cette action est définitive.
          </p>
          <label>Motif (obligatoire)</label>
          <textarea pInputTextarea [(ngModel)]="voidReason" rows="3"
                    placeholder="Ex: erreur de saisie, paiement annulé…"></textarea>
        </div>
        <ng-template pTemplate="footer">
          <p-button label="Retour" [text]="true" (onClick)="voidVisible = false" />
          <p-button label="Confirmer l'annulation" icon="pi pi-ban" severity="danger"
                    [disabled]="!voidReason.trim()" [loading]="acting()"
                    (onClick)="confirmVoid()" />
        </ng-template>
      </p-dialog>
    </section>
  `,
})
export class MovementDetailComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private cashService = inject(CashService);
  private partyService = inject(PartyService);
  private processService = inject(ProcessService);
  private msgs = inject(MessageService);
  private auth = inject(AuthService);

  movement = signal<CashMovement | null>(null);
  timeline = signal<MovementTimeline | null>(null);
  processTimeline = signal<ProcessTimeline | null>(null);
  voidReason = '';
  loading = signal(true);
  acting = signal(false);
  voidVisible = false;

  // Permission gates (route only requires CASH_READ — actions re-checked here)
  canDraft = this.auth.can(PERM.CASH_DRAFT);
  canPost = this.auth.can(PERM.CASH_POST);
  canVoid = this.auth.can(PERM.CASH_VOID);
  canSubmit = this.auth.can(PERM.CASH_SUBMIT);
  canReadDocs = this.auth.can(PERM.DOCUMENT_READ);

  // Template helpers
  tv = movementTypeVisual;
  sv = movementStatusVisual;
  pd = movementPending;
  tav = timelineActionVisual;
  fmt = (a: number) => formatAmount(a);
  formatDate = formatDate;

  /**
   * Status chip for the hero (TASK 4 — complete French chip set):
   * Brouillon | En attente | Comptabilisé | Rejeté | Annulé.
   * "Rejeté" = DRAFT whose approval timeline contains a rejection event.
   */
  chip(m: CashMovement) {
    if (movementRejected(m, this.timeline()?.events)) return REJECTED_VISUAL;
    return movementStatusVisual(m.status, movementPending(m));
  }

  /** True when the movement's approval was rejected (drives the action note). */
  isRejected(): boolean {
    const m = this.movement();
    return !!m && movementRejected(m, this.timeline()?.events);
  }

  /**
   * TASK 6 — the attached proof document (movement read model first; the
   * timeline snapshot is the fallback while the page loads).
   */
  proofDocumentId(): string | null {
    return this.movement()?.documentId ?? this.timeline()?.documentId ?? null;
  }

  proofDocumentName(): string {
    return this.movement()?.documentName ?? this.timeline()?.documentName ?? 'Pièce jointe';
  }

  /**
   * TASK 6 — small badge separating the three timeline sources
   * (cash_movement = opération itself, process_instance/process_step = validation).
   */
  sourceBadge(ev: { source?: string | null }): { label: string; color: string } | null {
    switch (String(ev.source ?? '')) {
      case 'process_instance': return { label: 'Validation', color: '#0d9488' };
      case 'process_step':     return { label: 'Étape', color: '#0891b2' };
      default: return null; // 'cash_movement' rows need no badge
    }
  }

  /** Timeline event timestamp — date + time (fr-FR). */
  fmtDateTime(iso: string | null | undefined): string {
    if (!iso) return '—';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    return d.toLocaleString('fr-FR', {
      day: '2-digit', month: '2-digit', year: 'numeric',
      hour: '2-digit', minute: '2-digit'
    });
  }

  /** Human-readable note extracted from the event details JSON (reason, title, reference…). */
  eventNote(ev: { details?: string | null }): string | null {
    const d = parseTimelineDetails(ev.details);
    if (!d) return null;
    for (const key of ['reason', 'title', 'reference', 'label', 'stepName']) {
      const v = d[key];
      if (typeof v === 'string' && v.trim()) return v.trim();
    }
    return null;
  }

  private id = '';

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.load();
  }

  load(): void {
    if (!this.id) {
      this.loading.set(false);
      return;
    }
    this.cashService.getMovement(this.id).subscribe(m => {
      this.movement.set(m);
      this.voidReason = '';
      this.loading.set(false);
      // If the movement has a linked process, fetch the process timeline
      // to show the step strip (visual progress: done / current / waiting).
      if (m?.processInstanceId) {
        this.processService.getTimeline(m.processInstanceId).subscribe(pt => {
          this.processTimeline.set(pt);
        });
      }
    });
    // B6 — audit timeline (independent from the movement fetch)
    this.cashService.getTimeline(this.id).subscribe(t => this.timeline.set(t));
    this.partyService.loadParties().subscribe();
    this.cashService.loadPots().subscribe();
  }

  /**
   * B5 — OUT drafts cannot be posted directly. This opens the prefilled
   * payment form (amount/date/pot/party/label/project) where the user clicks
   * « Envoyer pour validation » — creating a fresh submit-payment request.
   */
  duplicateAndSubmit(): void {
    const m = this.movement();
    if (!m) return;
    this.router.navigate(['/tresorerie/operations/new'], {
      queryParams: {
        type: 'OUT',
        fromPotId: m.fromPotId ?? '',
        projectId: m.projectId ?? '',
        partyId: m.partyId ?? '',
        amount: m.amount,
        label: m.label ?? '',
      },
    });
  }

  potName(id: string | null | undefined): string {
    if (!id) return '';
    return this.cashService.potById(id)?.name ?? '';
  }

  partyName(id: string | null | undefined): string {
    if (!id) return '';
    return this.partyService.partyById(id)?.name ?? '';
  }

  post(): void {
    this.acting.set(true);
    this.cashService.postMovement(this.id).subscribe(res => {
      this.acting.set(false);
      if (res) {
        this.msgs.add({ severity: 'success', summary: 'Opération comptabilisée',
          detail: 'Le solde de la caisse est à jour.' });
        this.cashService.invalidatePots();
        this.load();
      }
    });
  }

  showVoidDialog(): void {
    this.voidReason = '';
    this.voidVisible = true;
  }

  confirmVoid(): void {
    const reason = this.voidReason.trim();
    if (!reason) return;
    this.acting.set(true);
    this.cashService.voidMovement(this.id, reason).subscribe(m => {
      this.acting.set(false);
      if (m) {
        this.msgs.add({ severity: 'warn', summary: 'Opération annulée',
          detail: 'Elle est ignorée dans le calcul du solde.' });
        this.voidVisible = false;
        this.cashService.invalidatePots();
        this.load();
      }
    });
  }
}
