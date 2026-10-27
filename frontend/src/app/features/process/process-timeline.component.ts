import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextarea } from 'primeng/inputtextarea';
import { MessageService } from 'primeng/api';
import { AuthService } from '@app/core/auth.service';
import { ProcessService } from '@app/core/services/process.service';
import { ProcessTimeline, TimelineStep } from '@app/core/models/process.model';
import {
  processTypeVisual, roleVisual, stepStatusVisual,
} from '@app/shared/process-visual';

@Component({
  selector: 'app-process-timeline',
  standalone: true,
  imports: [ButtonModule, InputTextarea, FormsModule, DatePipe, RouterLink],
  styleUrl: './process-timeline.component.scss',
  template: `
    @if (loading()) {
      <div class="tl-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
    } @else {
      @let inst = timeline();
      @if (inst) {
      <section class="tl">
        <!-- Breadcrumb -->
        <nav class="bc">
          <a routerLink="/processus"><i class="pi pi-sitemap"></i> Processus</a>
          <i class="pi pi-angle-right"></i>
          <span>{{ inst.templateName || inst.templateKey }}</span>
        </nav>

        <!-- Hero with template identity + status -->
        <header class="tl-hero"
                [style.--hero-gradient]="typeV(inst.templateKey).gradient"
                [style.--hero-color]="typeV(inst.templateKey).color">
          <div class="hero-icon"><i class="pi {{ typeV(inst.templateKey).icon }}"></i></div>
          <div class="hero-text">
            <h1>{{ inst.templateName || inst.templateKey }}</h1>
            <div class="hero-meta">
              <span><i class="pi pi-folder"></i> {{ inst.projectName || '—' }}</span>
              <span><i class="pi pi-tag"></i> {{ inst.subjectType }}</span>
              @if (inst.createdByName) {
                <span><i class="pi pi-user"></i> {{ inst.createdByName }}</span>
              }
              <span><i class="pi pi-calendar"></i> {{ inst.createdAt | date:'short' }}</span>
            </div>
          </div>
          <div class="hero-status">
            <span class="status-pill" [class]="statusClass(inst.status)">
              <i class="pi {{ statusV(inst.status).icon }}"></i>
              {{ statusV(inst.status).label }}
            </span>
          </div>
        </header>

        <!-- Progress bar -->
        <div class="tl-progress">
          <div class="progress-track">
            <div class="progress-fill"
                 [style.width.%]="progressPct()"
                 [style.background]="typeV(inst.templateKey).gradient"></div>
          </div>
          <div class="progress-meta">
            <span><i class="pi pi-list-check"></i>
              Étape {{ currentStepNum() }} / {{ inst.steps.length }}
              ({{ progressPct() }}%)
            </span>
            @if (blockedStepName()) {
              <span class="blocked-tag">
                <i class="pi pi-exclamation-triangle"></i> Bloqué à « {{ blockedStepName() }} »
              </span>
            }
          </div>
        </div>

        <!-- Horizontal stepper (mini) -->
        <ol class="tl-stepper">
          @for (step of inst.steps; track step.stepInstanceId; let i = $index) {
            <li [class.done]="step.status === 'DONE'"
                [class.pending]="step.status === 'PENDING'"
                [class.rejected]="step.status === 'REJECTED'"
                [class.skipped]="step.status === 'SKIPPED'"
                [title]="step.name">
              <span class="dot">{{ step.stepOrder }}</span>
              <span class="lbl">{{ step.name }}</span>
            </li>
            @if (i < inst.steps.length - 1) {
              <i class="pi pi-arrow-right stepper-arrow"></i>
            }
          }
        </ol>

        <!-- Detailed step cards -->
        <div class="tl-steps">
          @for (step of inst.steps; track step.stepInstanceId; let i = $index) {
            <article class="step-card"
                     [style.--role-color]="roleV(step.assigneeRole).color"
                     [style.--role-soft]="roleV(step.assigneeRole).soft"
                     [class.is-current]="step.status === 'PENDING'"
                     [class.is-rejected]="step.status === 'REJECTED'"
                     [class.is-done]="step.status === 'DONE'">

              <div class="step-left">
                <div class="step-num">{{ step.stepOrder }}</div>
                <i class="pi {{ stepStatusIcon(step.status) }} step-status-icon"></i>
              </div>

              <div class="step-body">
                <header class="step-head">
                  <div class="step-title">{{ step.name }}</div>
                  <div class="step-status-pill" [class]="statusClass(step.status)">
                    <i class="pi {{ stepStatusIcon(step.status) }}"></i>
                    {{ statusV(step.status).label }}
                  </div>
                </header>

                <div class="step-key-row">
                  <code>{{ step.stepKey }}</code>
                  @if (step.requiresAttachment) {
                    <span class="attach-flag"><i class="pi pi-paperclip"></i> Document requis</span>
                  }
                </div>

                <div class="step-people">
                  <!-- RESPONSABLE — always visible (the user's #1 requirement) -->
                  <div class="who-chip" [style.--role-soft]="roleV(step.assigneeRole).soft">
                    <i class="pi {{ roleV(step.assigneeRole).icon }}"></i>
                    <div>
                      <div class="who-label">Responsable</div>
                      <div class="who-name">{{ step.assigneeName || '— non résolu —' }}</div>
                      <div class="who-role">{{ roleV(step.assigneeRole).label }}</div>
                    </div>
                  </div>
                </div>

                <div class="step-times" *ngIf="step.startedAt || step.completedAt">
                  @if (step.startedAt) {
                    <span><i class="pi pi-clock"></i> Début: {{ step.startedAt | date:'short' }}</span>
                  }
                  @if (step.completedAt) {
                    <span><i class="pi pi-check"></i> Fin: {{ step.completedAt | date:'short' }}</span>
                  }
                </div>

                @if (step.comment) {
                  <div class="step-comment">
                    <i class="pi pi-comment"></i>
                    <div>{{ step.comment }}</div>
                  </div>
                }

                <!-- Actions (only for the current PENDING step + only if user can act) -->
                @if (step.status === 'PENDING' && canActOnStep(step)) {
                  <div class="step-actions">
                    <button pButton label="Valider l'étape" icon="pi pi-check"
                            severity="success" size="small"
                            (click)="openComplete(step)"></button>
                    <button pButton label="Rejeter" icon="pi pi-times"
                            severity="danger" size="small" [text]="true"
                            (click)="openReject(step)"></button>
                  </div>
                }
                @if (step.status === 'PENDING' && !canActOnStep(step)) {
                  <div class="step-locked">
                    <i class="pi pi-lock"></i> En attente de {{ step.assigneeName || roleV(step.assigneeRole).label }}
                  </div>
                }
              </div>
            </article>

            @if (i < inst.steps.length - 1) {
              <i class="pi pi-arrow-down tl-arrow" [class.blocked]="inst.steps[i].status === 'REJECTED'"></i>
            }
          }
        </div>

        <!-- Inline action panel for current step -->
        @if (actionMode() === 'complete' && currentStep(); as cs) {
          <div class="action-panel complete">
            <header>
              <i class="pi pi-check-circle"></i>
              <div>
                <h3>Valider l'étape « {{ cs.name }} »</h3>
                <p>Un commentaire est optionnel. Les documents joints (le cas échéant) seront liés à cette étape.</p>
              </div>
              <button pButton icon="pi pi-times" [text]="true" (click)="cancelAction()"></button>
            </header>
            <textarea pInputTextarea [(ngModel)]="actionComment"
                      rows="3" placeholder="Commentaire (optionnel)"
                      class="action-text"></textarea>
            <div class="action-buttons">
              <button pButton label="Confirmer et avancer" icon="pi pi-check"
                      severity="success" [loading]="acting()"
                      (click)="confirmComplete(cs)"></button>
            </div>
          </div>
        }

        @if (actionMode() === 'reject' && currentStep(); as cs) {
          <div class="action-panel reject">
            <header>
              <i class="pi pi-times-circle"></i>
              <div>
                <h3>Rejeter l'étape « {{ cs.name }} »</h3>
                <p>Le processus sera marqué REJECTED. Un motif est obligatoire.</p>
              </div>
              <button pButton icon="pi pi-times" [text]="true" (click)="cancelAction()"></button>
            </header>
            <textarea pInputTextarea [(ngModel)]="actionComment" rows="3"
                      placeholder="Motif du rejet (obligatoire)"
                      class="action-text"></textarea>
            <div class="action-buttons">
              <button pButton label="Confirmer le rejet" icon="pi pi-times"
                      severity="danger" [loading]="acting()"
                      [disabled]="!actionComment.trim()"
                      (click)="confirmReject(cs)"></button>
            </div>
          </div>
        }
      </section>
      }
    }
  `,
})
export class ProcessTimelineComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private processService = inject(ProcessService);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  timeline = signal<ProcessTimeline | null>(null);
  loading = signal(true);
  acting = signal(false);

  actionMode = signal<'complete' | 'reject' | null>(null);
  currentStep = signal<TimelineStep | null>(null);
  actionComment = '';

  instanceId = '';

  // Helpers
  typeV = processTypeVisual;
  roleV = roleVisual;
  statusV = stepStatusVisual;

  ngOnInit(): void {
    this.instanceId = this.route.snapshot.paramMap.get('id') || '';
    if (!this.instanceId) {
      this.loading.set(false);
      this.router.navigate(['/processus']);
      return;
    }
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.processService.getTimeline(this.instanceId).subscribe({
      next: (result) => {
        // Defensive: getTimeline() can return an empty stub on error.
        const steps = (result?.steps ?? []).slice().sort((a, b) =>
          (a.stepOrder ?? 0) - (b.stepOrder ?? 0));
        this.timeline.set({ ...(result ?? { instanceId: '', status: 'RUNNING', steps: [] }), steps });
        this.loading.set(false);
      },
      error: () => {
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Impossible de charger la timeline' });
        this.loading.set(false);
      },
    });
  }

  /** Progress percent: DONE steps / total steps. */
  progressPct = computed(() => {
    const tl = this.timeline();
    if (!tl || !tl.steps.length) return 0;
    const done = tl.steps.filter(s => s.status === 'DONE').length;
    return Math.round((done / tl.steps.length) * 100);
  });

  currentStepNum = computed(() => {
    const tl = this.timeline();
    if (!tl) return 0;
    const pending = tl.steps.find(s => s.status === 'PENDING');
    if (pending) return pending.stepOrder ?? 0;
    const done = tl.steps.filter(s => s.status === 'DONE');
    return done.length > 0 ? Math.max(...done.map(s => s.stepOrder ?? 0)) : 0;
  });

  /** Name of the step where the process is blocked (REJECTED or stuck PENDING). */
  blockedStepName = computed(() => {
    const tl = this.timeline();
    if (!tl) return '';
    const rejected = tl.steps.find(s => s.status === 'REJECTED');
    if (rejected) return rejected.name || '';
    return '';
  });

  /** Owner can always act. Non-owner can act if they're the assignee or hold the role. */
  canActOnStep(step: TimelineStep): boolean {
    // Owner can always act (enforced on backend too)
    if (this.auth.me()?.isOwner) return true;
    // Direct assignee
    if (step.assigneeUserId && step.assigneeUserId === this.auth.me()?.id) return true;
    // Role-based fallback: frontend can't reliably check user_role without
    // extra fetch; assume not allowed and let backend reject (PENDING shows
    // a "locked" hint). Backend owner path always works.
    return false;
  }

  stepStatusIcon(status: string): string {
    return stepStatusVisual(status).icon;
  }

  statusClass(status: string): string {
    const s = String(status ?? '').toUpperCase();
    if (s === 'DONE' || s === 'COMPLETED') return 'st-done';
    if (s === 'PENDING' || s === 'RUNNING') return 'st-pending';
    if (s === 'REJECTED') return 'st-rejected';
    if (s === 'SKIPPED' || s === 'CANCELLED') return 'st-skipped';
    return 'st-pending';
  }

  openComplete(step: TimelineStep): void {
    this.currentStep.set(step);
    this.actionMode.set('complete');
    this.actionComment = '';
  }

  openReject(step: TimelineStep): void {
    this.currentStep.set(step);
    this.actionMode.set('reject');
    this.actionComment = '';
  }

  cancelAction(): void {
    this.actionMode.set(null);
    this.currentStep.set(null);
    this.actionComment = '';
  }

  confirmComplete(step: TimelineStep): void {
    this.acting.set(true);
    this.processService.complete(
      this.instanceId,
      step.stepInstanceId || '',
      { comment: this.actionComment || undefined }
    ).subscribe({
      next: () => {
        this.acting.set(false);
        this.msgs.add({
          severity: 'success',
          summary: 'Étape validée',
          detail: 'Le processus avance à l\'étape suivante',
        });
        this.cancelAction();
        this.load();
      },
      error: () => this.acting.set(false),
    });
  }

  confirmReject(step: TimelineStep): void {
    this.acting.set(true);
    this.processService.reject(this.instanceId, step.stepInstanceId || '', this.actionComment).subscribe({
      next: () => {
        this.acting.set(false);
        this.msgs.add({
          severity: 'warn',
          summary: 'Étape rejetée',
          detail: 'Le processus est marqué REJECTED',
        });
        this.cancelAction();
        this.load();
      },
      error: () => this.acting.set(false),
    });
  }
}
