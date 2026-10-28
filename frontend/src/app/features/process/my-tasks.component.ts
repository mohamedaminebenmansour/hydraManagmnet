import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { NgClass } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { MessageService } from 'primeng/api';
import { DialogModule } from 'primeng/dialog';
import { InputTextarea } from 'primeng/inputtextarea';
import { TagModule } from 'primeng/tag';
import { BadgeModule } from 'primeng/badge';
import { ProcessService } from '@app/core/services/process.service';
import { MyTask, StepStatus, ROLE_ICONS, STATUS_INFO, dotColorClass, roleLabel } from '@app/core/models/process.model';
import { formatAmount } from '@app/core/models/cash.model';

@Component({
  selector: 'app-my-tasks',
  standalone: true,
  styleUrl: './my-tasks.component.scss',
  imports: [ButtonModule, NgClass, FormsModule, DialogModule, InputTextarea, TagModule, BadgeModule],
  template: `
    <section class="my-tasks proc-bg">
      <header class="section-header">
        <h2><i class="pi pi-check-square"></i> Mes t\u00e2ches</h2>
        @if (pendingCount() > 0) {
          <p-badge [value]="pendingCount()" size="large" severity="warn" />
        }
      </header>

      @if (loading()) {
        <p class="skeleton">Chargement\u2026</p>
      } @else if (allTasks().length === 0) {
        <div class="empty-state">
          <i class="pi pi-inbox"></i>
          <p>Aucune tâche en attente</p>
        </div>
      } @else {
        <div class="status-chips">
          <p-tag [value]="'Toutes'" [severity]="'secondary'" [style]="{ 'cursor': 'pointer' }" (onClick)="filterStatus(null)" />
          <p-tag [value]="'En attente'" [severity]="activeFilter() === 'PENDING' ? 'warn' : 'secondary'" [style]="{ 'cursor': 'pointer' }" (onClick)="filterStatus('PENDING')" />
          <p-tag [value]="'Termin\u00e9es'" [severity]="activeFilter() === 'DONE' ? 'success' : 'secondary'" [style]="{ 'cursor': 'pointer' }" (onClick)="filterStatus('DONE')" />
          <p-tag [value]="'Rejet\u00e9es'" [severity]="activeFilter() === 'REJECTED' ? 'danger' : 'secondary'" [style]="{ 'cursor': 'pointer' }" (onClick)="filterStatus('REJECTED')" />
        </div>

        <div class="tasks-grid">
          @for (task of visibleTasks(); track task.stepInstanceId) {
            <div class="task-card"
                 [class.clickable]="task.subjectType === 'CASH_MOVEMENT' && task.subjectId"
                 (click)="openSubject(task)">
              <div class="card-header">
                <span class="card-project">{{ task.projectName || task.templateName || 'Processus' }}</span>
                @if (task.assigneeRole) {
                  <span class="card-role"><i class="pi {{ roleIcon(task.assigneeRole) }}"></i> {{ roleLabel(task.assigneeRole) }}</span>
                }
              </div>
              <div class="card-body">
                <p class="card-step">{{ task.stepName }}</p>
                @if (task.subjectType === 'CASH_MOVEMENT' && (task.subjectAmount || task.subjectLabel)) {
                  <p class="card-subject">
                    <i class="pi pi-wallet"></i>
                    @if (task.subjectAmount) { <strong>{{ fmtAmount(task.subjectAmount) }}</strong> }
                    @if (task.subjectLabel) { <span class="subj-label">{{ task.subjectLabel }}</span> }
                  </p>
                }
                <p class="card-assignee">{{ task.assigneeName || 'Non assign\u00e9' }}</p>
              </div>
              <div class="card-actions">
                @if (canAct(task)) {
                  @if (isCashTask(task)) {
                    <button pButton
                          label="Voir le dossier"
                          icon="pi pi-folder-open"
                          severity="success"
                          size="small"
                          (click)="onOpenDesk($event, task)"
                          class="btn-desk"
                    ></button>
                  } @else {
                    <button pButton
                          label="Valider"
                          icon="pi pi-check"
                          severity="success"
                          size="small"
                          [outlined]="true"
                          (click)="onValidate($event, task)"
                          class="btn-validate"
                    ></button>
                    <button pButton
                          label="Rejeter"
                          icon="pi pi-close"
                          severity="danger"
                          size="small"
                          [outlined]="true"
                          (click)="onReject($event, task)"
                          class="btn-reject"
                    ></button>
                  }
                } @else {
                  <span class="status-badge {{ statusBadgeClass(task.status) }}">
                    <i class="pi {{ statusIcon(task.status) }}"></i> {{ statusLabel(task.status) }}
                  </span>
                }
              </div>
            </div>
          }
        </div>
      }

      <p-dialog [(visible)]="rejectDialogVisible" [modal]="true" [style]="{ width: '440px' }" header="Rejeter l'\u00e9tape">
        <div class="dlg-form">
          <p class="dlg-task">{{ rejectTask?.stepName }}</p>
          <label for="reject-comment">Commentaire (obligatoire)</label>
          <textarea pInputTextarea id="reject-comment" [(ngModel)]="rejectComment"
                    [autoResize]="true" rows="3" placeholder="Motif du rejet\u2026"></textarea>
          <small class="hint">Un commentaire est requis pour rejeter l'\u00e9tape.</small>
        </div>
        <ng-template pTemplate="footer">
          <p-button label="Annuler" [text]="true" (onClick)="closeRejectDialog()" />
          <p-button label="Confirmer le rejet" icon="pi pi-close" severity="danger"
                    (onClick)="confirmReject()" [disabled]="!rejectComment.trim()" />
        </ng-template>
      </p-dialog>
    </section>
  `,
})
export class MyTasksComponent implements OnInit {
  private processService = inject(ProcessService);
  private msgs = inject(MessageService);
  private router = inject(Router);

  loading = signal(true);
  allTasks = signal<MyTask[]>([]);
  activeFilter = signal<StepStatus | null>(null);

  rejectDialogVisible = false;
  rejectTask: MyTask | null = null;
  rejectComment = '';

  visibleTasks = computed<MyTask[]>(() => {
    const tasks = this.allTasks();
    const f = this.activeFilter();
    return f ? tasks.filter(t => t.status === f) : tasks;
  });

  pendingCount = computed(() => this.allTasks().filter(t => t.status === 'PENDING').length);

  roleIcon = (role: string): string => ROLE_ICONS[role] ?? ROLE_ICONS['default'];
  roleLabel = roleLabel;

  statusBadgeClass = (status: string): string => {
    const s = String(status ?? '').toUpperCase();
    switch (s) {
      case 'DONE':   return 'badge-done';
      case 'PENDING': return 'badge-current';
      case 'REJECTED': return 'badge-rejected';
      default: return 'badge-muted';
    }
  };

  statusIcon = (status: string): string => {
    const info = STATUS_INFO[String(status ?? '').toUpperCase()];
    return info ? info.icon : 'pi-clock';
  };

  statusLabel = (status: string): string => {
    const info = STATUS_INFO[String(status ?? '').toUpperCase()];
    return info ? info.label : status;
  };

  taskStatusFrench = (status: StepStatus): string => {
    const info = STATUS_INFO[status];
    return info ? info.label : status;
  };

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.processService.myTasks().subscribe({
      next: (tasks: MyTask[]) => {
        this.allTasks.set(tasks ?? []);
        this.loading.set(false);
      },
      error: () => {
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Impossible de charger mes t\u00e2ches' });
        this.allTasks.set([]);
        this.loading.set(false);
      },
    });
  }

  canAct(task: MyTask): boolean {
    return task.status === 'PENDING';
  }

  /** Action buttons live inside the clickable card — stop the bubbling so
   *  openSubject()'s navigation doesn't race the action (B5). */
  onValidate(ev: MouseEvent, task: MyTask): void {
    ev.stopPropagation();
    this.validate(task);
  }

  onReject(ev: MouseEvent, task: MyTask): void {
    ev.stopPropagation();
    this.openRejectDialog(task);
  }

  fmtAmount = (a: number | null | undefined): string => formatAmount(a ?? 0);

  /** CASH_MOVEMENT tasks must open the dossier — no blind Valider/Rejeter. */
  isCashTask(task: MyTask): boolean {
    return task.subjectType === 'CASH_MOVEMENT' && !!task.subjectId && !!task.stepInstanceId;
  }

  /** CASH_MOVEMENT tasks deep-link to the validation desk (not the movement detail). */
  openSubject(task: MyTask): void {
    if (this.isCashTask(task)) {
      this.router.navigate(['/processus/etapes', task.stepInstanceId, 'validation']);
    }
  }

  /** "Voir le dossier" button — same navigation as openSubject but stops the
   *  card click from racing. */
  onOpenDesk(ev: MouseEvent, task: MyTask): void {
    ev.stopPropagation();
    this.openSubject(task);
  }

  validate(task: MyTask): void {
    this.processService.complete(task.instanceId, task.stepInstanceId, {}).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Valid\u00e9' });
        this.reload();
      },
      error: () => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: '\u00c9chec de la validation' }),
    });
  }

  openRejectDialog(task: MyTask): void {
    this.rejectTask = task;
    this.rejectComment = '';
    this.rejectDialogVisible = true;
  }

  closeRejectDialog(): void {
    this.rejectDialogVisible = false;
    this.rejectTask = null;
    this.rejectComment = '';
  }

  confirmReject(): void {
    if (!this.rejectTask || !this.rejectComment.trim()) { return; }
    this.processService.reject(this.rejectTask.instanceId, this.rejectTask.stepInstanceId, this.rejectComment).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Rejet\u00e9' });
        this.closeRejectDialog();
        this.reload();
      },
      error: () => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: '\u00c9chec du rejet' }),
    });
  }

  filterStatus(status: StepStatus | null): void {
    this.activeFilter.set(status);
  }
}

