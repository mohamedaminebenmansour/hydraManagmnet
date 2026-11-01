import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { SelectModule } from 'primeng/select';
import { DatePickerModule } from 'primeng/datepicker';
import { DialogModule } from 'primeng/dialog';
import { TagModule } from 'primeng/tag';
import { ChipModule } from 'primeng/chip';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { MessageService } from 'primeng/api';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { ProjectService } from '@app/core/services/project.service';
import { UserService } from '@app/core/services/user.service';
import { TicketService } from '@app/core/services/ticket.service';
import { SelectOption } from '@app/core/models/select-option';
import { Project } from '@app/core/models/project.model';
import {
  Ticket, TICKET_STATUS_OPTS, TICKET_STATUS_LABELS, TICKET_STATUS_SEVERITY,
  TICKET_STATUS_FLOW, TICKET_PRIORITY_OPTS, TICKET_PRIORITY_LABELS, TicketStatus
} from '@app/core/models/ticket.model';
import { EmptyStateComponent } from '@app/shared/empty-state.component';
import { LoadErrorComponent } from '@app/shared/load-error.component';

@Component({
  selector: 'app-tickets',
  standalone: true,
  imports: [
    FormsModule, ButtonModule, InputTextModule, TextareaModule, SelectModule,
    DatePickerModule, DialogModule, TagModule, ChipModule, ToggleSwitchModule,
    EmptyStateComponent, LoadErrorComponent
  ],
  templateUrl: './tickets.component.html',
  styleUrl: './tickets.component.scss'
})
export class TicketsComponent implements OnInit {
  private ticketService = inject(TicketService);
  private projectService = inject(ProjectService);
  private userService = inject(UserService);
  private auth = inject(AuthService);
  private router = inject(Router);
  private msgs = inject(MessageService);

  tickets = signal<Ticket[]>([]);
  projects = signal<Project[]>([]);
  userOptions = signal<SelectOption[]>([]);
  loaded = signal(false);
  loadError = signal(false);
  /** Filter / assignee lists (chantiers, utilisateurs) failed to load. */
  optionsError = signal(false);
  showDialog = signal(false);

  readonly statusOpts = [{ label: 'Tous', value: '' }, ...TICKET_STATUS_OPTS];
  readonly priorityOpts = TICKET_PRIORITY_OPTS;

  fProject: string | null = null;
  fStatus = '';
  fPriority: string | null = null;
  fMine = false;

  mode: 'create' | 'edit' = 'create';
  editTarget: Ticket | null = null;
  formTitle = '';
  formDescription = '';
  formProject: string | null = null;
  formAssignee: string | null = null;
  formPriority = 'MEDIUM';
  formStart: Date | null = null;
  formDue: Date | null = null;
  formDocumentId = '';

  canWrite = computed(() => this.auth.can(PERM.TICKET_WRITE));
  canAssign = computed(() => this.auth.can(PERM.TICKET_ASSIGN) || this.auth.can(PERM.TICKET_WRITE));

  projectOpts = computed(() =>
    this.projects().map(p => ({ label: p.code ? `${p.code} — ${p.name}` : p.name, value: p.id })));
  activeProjectOpts = computed(() =>
    this.projects().filter(p => p.status === 'ACTIF')
      .map(p => ({ label: p.code ? `${p.code} — ${p.name}` : p.name, value: p.id })));
  assigneeOpts = computed(() => this.userOptions());

  ngOnInit(): void {
    this.loadOptions(true);
  }

  /** Chantiers + users for the filters and the form; the ticket list loads either way. */
  loadOptions(thenApply = false): void {
    this.optionsError.set(false);
    this.projectService.list().subscribe({
      next: list => { this.projects.set(list ?? []); if (thenApply) this.apply(); },
      error: () => { this.projects.set([]); this.optionsError.set(true); if (thenApply) this.apply(); }
    });
    this.userService.options().subscribe({
      next: users => this.userOptions.set((users ?? []).map(u => ({ label: u.fullName, value: u.id }))),
      error: () => { this.userOptions.set([]); this.optionsError.set(true); }
    });
  }

  apply(): void {
    this.loaded.set(false);
    this.loadError.set(false);
    this.ticketService.list({
      projectId: this.fProject,
      status: this.fStatus || null,
      priority: this.fPriority,
      mine: this.fMine || undefined
    }).subscribe({
      next: list => { this.tickets.set(list ?? []); this.loaded.set(true); },
      error: () => { this.tickets.set([]); this.loadError.set(true); this.loaded.set(true); }
    });
  }

  reset(): void {
    this.fProject = null; this.fStatus = ''; this.fPriority = null; this.fMine = false;
    this.apply();
  }

  openTicket(t: Ticket): void { this.router.navigate(['/tickets', t.id]); }

  openCreate(): void {
    this.mode = 'create'; this.editTarget = null;
    this.formTitle = ''; this.formDescription = ''; this.formProject = null;
    this.formAssignee = null; this.formPriority = 'MEDIUM';
    this.formStart = null; this.formDue = null; this.formDocumentId = '';
    this.showDialog.set(true);
  }

  openEdit(t: Ticket): void {
    this.mode = 'edit'; this.editTarget = t;
    this.formTitle = t.title; this.formDescription = t.description ?? '';
    this.formProject = t.projectId; this.formAssignee = t.assignedToId;
    this.formPriority = t.priority ?? 'MEDIUM';
    this.formStart = this.parseDateOnly(t.startAt); this.formDue = this.parseDateOnly(t.dueAt);
    this.formDocumentId = t.documentId ?? '';
    this.showDialog.set(true);
  }
  // ── workflow helpers (transmittal / RFI) ──────────────────────────
  statusLabel(s: string): string { return TICKET_STATUS_LABELS[s] ?? s; }
  statusSeverity(s: string): 'success'|'info'|'warn'|'danger'|'secondary'|'contrast' { return TICKET_STATUS_SEVERITY[s] ?? 'secondary'; }

  nextStatuses(t: Ticket): TicketStatus[] {
    return TICKET_STATUS_FLOW[(t.status as TicketStatus)] ?? [];
  }

  canAdvance(t: Ticket): boolean {
    return this.canWrite() && this.nextStatuses(t).length > 0;
  }

  advance(t: Ticket, next: string): void {
    this.ticketService.setStatus(t.id, next).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Statut mis à jour',
          detail: TICKET_STATUS_LABELS[next] ?? next });
        this.apply();
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err.error?.message ?? 'Transition impossible' })
    });
  }

  priorityLabel(p: string | null | undefined): string { return p ? (TICKET_PRIORITY_LABELS[p] ?? p) : '—'; }

  isOverdue(t: Ticket): boolean {
    if (!t.dueAt || t.status === 'AS_BUILT') { return false; }
    const due = this.parseDateOnly(t.dueAt);
    if (!due) { return false; }
    const today = new Date(); today.setHours(0, 0, 0, 0);
    return due < today;
  }

  fmtDate(s: string | null): string {
    if (!s) { return '—'; }
    const d = this.parseDateOnly(s) ?? new Date(s);
    if (isNaN(d.getTime())) { return s; }
    return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  projectNameOf(t: Ticket): string {
    if (t.projectName) { return t.projectName; }
    return this.projects().find(p => p.id === t.projectId)?.name ?? '—';
  }

  // ── create / edit ────────────────────────────────────────────────
  submit(): void {
    if (!this.formTitle.trim()) { return; }
    if (this.mode === 'edit') { this.saveEdit(); } else { this.create(); }
  }

  private parseDateOnly(s: string | null | undefined): Date | null {
    if (!s) { return null; }
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s);
    if (m) { return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])); }
    const d = new Date(s);
    return isNaN(d.getTime()) ? null : d;
  }

  private body() {
    return {
      title: this.formTitle.trim(),
      description: this.formDescription.trim() || null,
      projectId: this.formProject,
      assignedToId: this.formAssignee,
      priority: this.formPriority,
      startAt: this.formStart,
      dueAt: this.formDue,
      documentId: this.formDocumentId.trim() || null
    };
  }

  create(): void {
    this.ticketService.create(this.body()).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Ticket créé' });
        this.showDialog.set(false); this.apply();
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err.error?.message ?? 'Création impossible' })
    });
  }

  saveEdit(): void {
    if (!this.editTarget) { return; }
    this.ticketService.update(this.editTarget.id, this.body()).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Ticket modifié' });
        this.showDialog.set(false); this.apply();
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err.error?.message ?? 'Modification impossible' })
    });
  }
}
