import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { TextareaModule } from 'primeng/textarea';
import { MessageService } from 'primeng/api';
import { ProcessService } from '@app/core/services/process.service';
import { AuthService } from '@app/core/auth.service';
import {
  ApprovalDesk,
  roleLabel,
} from '@app/core/models/process.model';
import {
  PAYMENT_MODE_LABEL, PAYMENT_MODE_ICON, NATURE_LABEL,
  formatAmount, formatDate,
} from '@app/core/models/cash.model';

/**
 * ApprovalDeskComponent — /processus/etapes/:stepInstanceId/validation
 *
 * The "real validation screen" (TASK 2). An approver opens a dossier and
 * sees the full truth before deciding:
 *   - Header: Projet | Tiers | big "À payer: X TND"
 *   - Band: Demandé / À payer / Écart + motif (warning color if variance)
 *   - Meta chips: Mode, Bon, Nature, Date, Caisse
 *   - Document block: name + "Ouvrir" button (links to document detail)
 *   - If no document + requiresAttachment: strong warning + disable Approuver
 *   - Timeline mini: previous steps DONE with who/when
 *   - Actions: Approuver | Rejeter (dialog, comment required on reject)
 *
 * Calls the approval-desk API ONCE per open (single round-trip, no N+1).
 * On success: toast + navigate back to Mes tâches.
 */
@Component({
  selector: 'app-approval-desk',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink,
    ButtonModule, DialogModule, TextareaModule,
  ],
  styleUrl: './approval-desk.component.scss',
  template: `
    <section class="desk">
      <nav class="desk-bc">
        <a routerLink="/mes-taches"><i class="pi pi-check-square"></i> Mes tâches</a>
        <i class="pi pi-angle-right"></i>
        <span>Dossier de validation</span>
      </nav>

      @if (loading()) {
        <div class="desk-loading"><i class="pi pi-spin pi-spinner"></i> Chargement du dossier…</div>
      } @else if (desk()) {
        @let d = desk()!;

        <!-- ── STEP BANNER: which step, which role, can I act? ── -->
        <div class="step-banner" [class.role-finance]="d.currentStepRole === 'FINANCE'"
             [class.role-owner]="d.currentStepRole === 'OWNER'">
          <div class="sb-left">
            <div class="sb-step">
              <span class="sb-step-name">{{ d.currentStepName }}</span>
              <span class="sb-role-chip">{{ roleLabel(d.currentStepRole) }}</span>
            </div>
            @if (d.assigneeName) {
              <div class="sb-assignee">
                <i class="pi pi-user"></i>
                {{ d.assigneeName }}
              </div>
            }
          </div>
          <div class="sb-right">
            @if (d.canComplete) {
              <span class="sb-badge sb-badge-act"><i class="pi pi-bolt"></i> À votre action</span>
            } @else {
              <span class="sb-badge sb-badge-readonly"><i class="pi pi-eye"></i> Lecture seule</span>
            }
          </div>
        </div>

        <!-- ── HEADER: Projet | Tiers | big "À payer" ── -->
        <header class="desk-hero">
          <div class="hero-left">
            <div class="hero-project">
              <i class="pi pi-briefcase"></i>
              <span>{{ d.projectName || 'Projet inconnu' }}</span>
            </div>
            @if (d.partyName) {
              <div class="hero-party">
                <i class="pi pi-users"></i>
                <span>{{ d.partyName }}</span>
                @if (d.partyType === 'SUB') { <small>sous-traitant</small> }
                @if (d.partyType === 'SUPPLIER') { <small>fournisseur</small> }
              </div>
            }
            <div class="hero-label">{{ d.movementLabel || 'Paiement sans libellé' }}</div>
          </div>
          <div class="hero-amount">
            <div class="ha-label">À payer</div>
            <div class="ha-value">{{ fmt(d.assessedAmount) }}</div>
            <div class="ha-status">
              <i class="pi" [class]="statusIcon(d.movementStatus)"></i>
              {{ statusLabel(d.movementStatus) }}
            </div>
          </div>
        </header>

        @if (d.movementId) {
          <a [routerLink]="['/tresorerie/operations', d.movementId]" class="desk-movement-link">
            <i class="pi pi-external-link"></i> Fiche mouvement complète
          </a>
        }

        <!-- ── BAND: Demandé / À payer / Écart + motif ── -->
        <section class="desk-amounts">
          <div class="amt-card">
            <div class="amt-label">Montant demandé</div>
            <div class="amt-value">{{ fmt(d.claimedAmount) }}</div>
          </div>
          <div class="amt-card amt-pay">
            <div class="amt-label">À payer</div>
            <div class="amt-value">{{ fmt(d.assessedAmount) }}</div>
          </div>
          <div class="amt-card" [class.amt-variance]="hasVariance(d)">
            <div class="amt-label">
              Écart
              @if (hasVariance(d)) { <i class="pi pi-exclamation-triangle"></i> }
            </div>
            <div class="amt-value" [class.variance-text]="hasVariance(d)">
              {{ varianceText(d) }}
            </div>
            @if (d.varianceReason) {
              <div class="amt-reason">{{ d.varianceReason }}</div>
            }
          </div>
        </section>

        <!-- ── META CHIPS: Mode, Bon, Nature, Date, Caisse ── -->
        <section class="desk-meta">
          <div class="meta-chip" [style.--chip-color]="'#0891b2'">
            <i class="pi" [class]="modeIcon(d.paymentMode)"></i>
            <span>{{ modeLabel(d.paymentMode) }}</span>
          </div>
          @if (d.voucherNo) {
            <div class="meta-chip" [style.--chip-color]="'#7c3aed'">
              <i class="pi pi-ticket"></i>
              <span>Bon {{ d.voucherNo }}</span>
            </div>
          }
          @if (d.nature) {
            <div class="meta-chip" [style.--chip-color]="'#0e7490'">
              <i class="pi pi-tag"></i>
              <span>{{ natureLabel(d.nature) }}</span>
            </div>
          }
          <div class="meta-chip" [style.--chip-color]="'#64748b'">
            <i class="pi pi-calendar"></i>
            <span>{{ formatDate(d.movementDate) }}</span>
          </div>
          @if (d.fromPotName) {
            <div class="meta-chip" [style.--chip-color]="'#b45309'">
              <i class="pi pi-building"></i>
              <span>{{ d.fromPotName }}</span>
            </div>
          }
          @if (d.paymentRef) {
            <div class="meta-chip" [style.--chip-color]="'#475569'">
              <i class="pi pi-file"></i>
              <span>Réf: {{ d.paymentRef }}</span>
            </div>
          }
        </section>

        <!-- ── DOCUMENT BLOCK ── -->
        <section class="desk-doc">
          <header class="dd-head">
            <h2><i class="pi pi-paperclip"></i> Pièce justificative</h2>
            @if (d.requiresAttachment && !d.hasAttachment) {
              <span class="dd-warn"><i class="pi pi-exclamation-triangle"></i> Requise</span>
            }
          </header>
          @if (d.documentId && d.documentName) {
            <div class="dd-attached">
              <i class="pi pi-file-pdf"></i>
              <span class="dd-name" [title]="d.documentName">{{ d.documentName }}</span>
              <a [routerLink]="['/documents', d.documentId]" class="dd-open">
                <i class="pi pi-external-link"></i> Ouvrir
              </a>
            </div>
          } @else {
            <div class="dd-missing" [class.dd-required]="d.requiresAttachment">
              <i class="pi" [class]="d.requiresAttachment ? 'pi-exclamation-triangle' : 'pi-info-circle'"></i>
              <span>
                @if (d.requiresAttachment) {
                  <strong>Pièce justificative requise</strong> — joignez la facture / le bon
                  de livraison sur le mouvement avant d'approuver.
                } @else {
                  Aucune pièce jointe sur ce paiement.
                }
              </span>
            </div>
          }
        </section>

        <!-- ── MINI TIMELINE ── -->
        @if (d.steps.length > 0) {
          <section class="desk-timeline">
            <header class="dt-head">
              <h2><i class="pi pi-list"></i> Historique du processus</h2>
            </header>
            <div class="dt-list">
              @for (s of d.steps; track s.stepOrder) {
                <div class="dt-step" [class.done]="s.status === 'DONE'"
                     [class.pending]="s.status === 'PENDING'"
                     [class.rejected]="s.status === 'REJECTED'">
                  <div class="dt-dot">
                    <i class="pi" [class]="stepStatusIcon(s.status)"></i>
                  </div>
                  <div class="dt-body">
                    <div class="dt-title">
                      <span class="dt-name">{{ s.name }}</span>
                      <span class="dt-role">{{ roleLabel(s.assigneeRole) }}</span>
                    </div>
                    <div class="dt-meta">
                      @if (s.assigneeName) { <span>{{ s.assigneeName }}</span> }
                      @if (s.completedAt) { <span>· {{ formatDate(s.completedAt) }}</span> }
                      @if (s.comment) { <span>· "{{ s.comment }}"</span> }
                    </div>
                  </div>
                </div>
              }
            </div>
          </section>
        }

        <!-- ── ACTIONS ── -->
        <section class="desk-actions">
          @if (!d.canComplete && !d.canReject) {
            <div class="da-readonly">
              <i class="pi pi-lock"></i>
              Lecture seule — vous n'êtes pas l'assignataire de cette étape (rôle: {{ roleLabel(d.currentStepRole) }}).
            </div>
          } @else {
            @if (d.currentStepRole === 'FINANCE') {
              <div class="da-hint">
                <i class="pi pi-info-circle"></i>
                Contrôlez le dossier (montants, pièce, écart) avant d'envoyer au gérant.
              </div>
            } @else if (d.currentStepRole === 'OWNER') {
              <div class="da-hint">
                <i class="pi pi-info-circle"></i>
                Vous autorisez la sortie de caisse pour le montant À payer.
              </div>
            }
            <div class="da-btns">
              <button pButton label="Rejeter" icon="pi pi-times" severity="danger"
                      [outlined]="true" [disabled]="acting()"
                      (click)="openRejectDialog()"></button>
              <button pButton label="Approuver" icon="pi pi-check" severity="success"
                      [disabled]="!canApprove(d) || acting()"
                      (click)="approve()"></button>
            </div>
            @if (d.requiresAttachment && !d.hasAttachment) {
              <div class="da-warn">
                <i class="pi pi-exclamation-triangle"></i>
                Pièce justificative requise — Approuver désactivé.
              </div>
            }
          }
          <button pButton label="Retour" icon="pi pi-arrow-left" severity="secondary"
                  [text]="true" routerLink="/mes-taches"></button>
        </section>
      } @else {
        <div class="desk-error">
          <i class="pi pi-exclamation-circle"></i>
          <p>Dossier introuvable ou vous n'avez pas accès.</p>
        </div>
      }

      <!-- ── REJECT DIALOG (comment required) ── -->
      <p-dialog header="Rejeter le paiement" [(visible)]="rejectOpen"
                [modal]="true" [style]="{ width: '480px' }" [dismissableMask]="false">
        <div class="reject-form">
          <label>Motif du rejet <span class="req">*</span></label>
          <textarea pInputTextarea [(ngModel)]="rejectComment" rows="4"
                    placeholder="Ex: Facture non conforme — revoir le tronçon 3…"></textarea>
          <small>Le commentaire est obligatoire et apparaîtra dans l'historique.</small>
        </div>
        <ng-template pTemplate="footer">
          <button pButton label="Annuler" severity="secondary" [text]="true"
                  (click)="closeRejectDialog()"></button>
          <button pButton label="Confirmer le rejet" icon="pi pi-times" severity="danger"
                  [disabled]="!rejectComment.trim() || acting()"
                  [loading]="acting()"
                  (click)="confirmReject()"></button>
        </ng-template>
      </p-dialog>
    </section>
  `,
})
export class ApprovalDeskComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private processService = inject(ProcessService);
  private msgs = inject(MessageService);

  loading = signal(true);
  acting = signal(false);
  desk = signal<ApprovalDesk | null>(null);

  // Reject dialog
  rejectOpen = false;
  rejectComment = '';

  // Template helpers (exposed to the template)
  fmt = (a: number | null | undefined) => formatAmount(a);
  formatDate = formatDate;
  roleLabel = roleLabel;

  ngOnInit(): void {
    const stepInstanceId = this.route.snapshot.paramMap.get('stepInstanceId') ?? '';
    if (!stepInstanceId) {
      this.loading.set(false);
      return;
    }
    this.processService.getApprovalDesk(stepInstanceId).subscribe(d => {
      this.desk.set(d);
      this.loading.set(false);
    });
  }

  // ── Variance ──
  hasVariance(d: ApprovalDesk): boolean {
    return d.varianceAmount != null && Math.abs(d.varianceAmount) > 0.001;
  }
  varianceText(d: ApprovalDesk): string {
    if (d.varianceAmount == null) return '—';
    const sign = d.varianceAmount > 0 ? '−' : '+';
    return `${sign}${fmt(Math.abs(d.varianceAmount))}`;
  }

  // ── Can approve? (requires attachment check) ──
  canApprove(d: ApprovalDesk): boolean {
    if (!d.canComplete) return false;
    if (d.requiresAttachment && !d.hasAttachment) return false;
    return true;
  }

  // ── Approve ──
  approve(): void {
    const d = this.desk();
    if (!d || !this.canApprove(d)) return;
    this.acting.set(true);
    this.processService.complete(d.instanceId, d.stepInstanceId, {}).subscribe({
      next: () => {
        this.acting.set(false);
        this.msgs.add({
          severity: 'success', summary: 'Paiement approuvé',
          detail: 'L\'étape a été validée.',
        });
        this.router.navigate(['/mes-taches']);
      },
      error: () => {
        this.acting.set(false);
        // error toast already shown by the service
      },
    });
  }

  // ── Reject ──
  openRejectDialog(): void {
    this.rejectComment = '';
    this.rejectOpen = true;
  }
  closeRejectDialog(): void {
    this.rejectOpen = false;
    this.rejectComment = '';
  }
  confirmReject(): void {
    const d = this.desk();
    if (!d || !this.rejectComment.trim()) return;
    this.acting.set(true);
    this.processService.reject(d.instanceId, d.stepInstanceId, this.rejectComment.trim()).subscribe({
      next: () => {
        this.acting.set(false);
        this.msgs.add({
          severity: 'success', summary: 'Paiement rejeté',
          detail: 'Le paiement a été rejeté avec votre commentaire.',
        });
        this.closeRejectDialog();
        this.router.navigate(['/mes-taches']);
      },
      error: () => {
        this.acting.set(false);
      },
    });
  }

  // ── Visual helpers (mode, nature, status icons/labels) ──
  modeLabel(m: string | null | undefined): string {
    if (!m) return 'Mode non renseigné';
    return PAYMENT_MODE_LABEL[m as keyof typeof PAYMENT_MODE_LABEL] ?? m;
  }
  modeIcon(m: string | null | undefined): string {
    if (!m) return 'pi-circle';
    return PAYMENT_MODE_ICON[m as keyof typeof PAYMENT_MODE_ICON] ?? 'pi-circle';
  }
  natureLabel(n: string | null | undefined): string {
    if (!n) return '';
    return NATURE_LABEL[n as keyof typeof NATURE_LABEL] ?? n;
  }
  statusLabel(s: string): string {
    switch (s?.toUpperCase()) {
      case 'DRAFT': return 'Brouillon';
      case 'POSTED': return 'Comptabilisé';
      case 'VOID': return 'Annulé';
      default: return s;
    }
  }
  statusIcon(s: string): string {
    switch (s?.toUpperCase()) {
      case 'DRAFT': return 'pi-pencil';
      case 'POSTED': return 'pi-check-circle';
      case 'VOID': return 'pi-ban';
      default: return 'pi-circle';
    }
  }
  stepStatusIcon(s: string): string {
    switch (s?.toUpperCase()) {
      case 'DONE': return 'pi-check';
      case 'PENDING': return 'pi-clock';
      case 'REJECTED': return 'pi-times';
      case 'SKIPPED': return 'pi-forward';
      default: return 'pi-circle';
    }
  }
}

function fmt(a: number | null | undefined): string {
  return formatAmount(a ?? 0);
}
