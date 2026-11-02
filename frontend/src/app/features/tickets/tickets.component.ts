import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { Subject, debounceTime, distinctUntilChanged } from 'rxjs';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { SelectModule } from 'primeng/select';
import { DatePickerModule } from 'primeng/datepicker';
import { DialogModule } from 'primeng/dialog';
import { TagModule } from 'primeng/tag';
import { ChipModule } from 'primeng/chip';
import { PaginatorModule } from 'primeng/paginator';
import { MessageService } from 'primeng/api';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { ProjectService } from '@app/core/services/project.service';
import { UserService } from '@app/core/services/user.service';
import { TicketService } from '@app/core/services/ticket.service';
import { SelectOption } from '@app/core/models/select-option';
import { Project } from '@app/core/models/project.model';
import {
  Ticket, TicketUpsert, TicketCatalogType, TicketSummary,
  TICKET_PRIORITY_OPTS, TICKET_PRIORITY_LABELS
} from '@app/core/models/ticket.model';
import { EmptyStateComponent } from '@app/shared/empty-state.component';
import { LoadErrorComponent } from '@app/shared/load-error.component';
import { typeVisual, statusVisual } from './signalement-visual';

type Tab = 'mine' | 'all' | 'overdue';

@Component({
  selector: 'app-tickets',
  standalone: true,
  imports: [
    FormsModule, ButtonModule, InputTextModule, TextareaModule, SelectModule,
    DatePickerModule, DialogModule, TagModule, ChipModule, PaginatorModule,
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

  // ── data / state ──────────────────────────────────────────────────────
  catalog = signal<TicketCatalogType[]>([]);
  summary = signal<TicketSummary | null>(null);
  displayed = signal<Ticket[]>([]);
  total = signal(0);
  page = signal(0);
  readonly pageSize = 20;
  loaded = signal(false);
  loadError = signal(false);

  projects = signal<Project[]>([]);
  userOptions = signal<SelectOption[]>([]);
  optionsError = signal(false);

  // ── filters ───────────────────────────────────────────────────────────
  activeTab = signal<Tab>('mine');
  selectedType = signal<string | null>(null);
  fStatus = signal<string | null>(null);
  fProject = signal<string | null>(null);
  searchQuery = signal('');
  isMobile = signal(false);

  // ── create / edit form (kept; T5 replaces it) ─────────────────────────
  showDialog = signal(false);
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

  readonly priorityOpts = TICKET_PRIORITY_OPTS;

  canWrite = computed(() => this.auth.can(PERM.TICKET_WRITE));

  projectOpts = computed(() =>
    this.projects().map(p => ({ label: p.code ? `${p.code} — ${p.name}` : p.name, value: p.id })));
  activeProjectOpts = computed(() =>
    this.projects().filter(p => p.status === 'ACTIF')
      .map(p => ({ label: p.code ? `${p.code} — ${p.name}` : p.name, value: p.id })));
  assigneeOpts = computed(() => this.userOptions());

  /** Status dropdown options built from the catalog (all statuses, deduped). */
  statusOpts = computed(() => {
    const seen = new Map<string, string>();
    for (const t of this.catalog() ?? []) {
      for (const s of t.statuses ?? []) { seen.set(s.code, s.label); }
    }
    return [{ label: 'Tous les statuts', value: null },
      ...Array.from(seen, ([code, label]) => ({ label, value: code }))];
  });

  /** The 4 type chips (icon + colour + label). */
  typeChips = computed(() =>
    (this.catalog() ?? []).map(t => ({ code: t.code, label: t.label, icon: t.icon, colour: t.colour })));

  /** Counter row from the summary. */
  counters = computed(() => {
    const s = this.summary();
    return [
      { key: 'mine', label: 'À moi', value: s?.mine ?? 0, danger: false },
      { key: 'toVerify', label: 'À vérifier', value: s?.toVerify ?? 0, danger: false },
      { key: 'overdue', label: 'En retard', value: s?.overdue ?? 0, danger: (s?.overdue ?? 0) > 0 },
      { key: 'open', label: 'Ouverts', value: s?.open ?? 0, danger: false }
    ];
  });

  private searchSubject = new Subject<string>();
  private mobileQuery?: MediaQueryList;

  ngOnInit(): void {
    // debounced search (300 ms)
    this.searchSubject.pipe(debounceTime(300), distinctUntilChanged()).subscribe(q => {
      this.searchQuery.set(q);
      this.runSearch('reset');
    });
    // responsive: table vs cards + paginator vs "Charger plus"
    this.mobileQuery = window.matchMedia('(max-width: 767px)');
    this.isMobile.set(this.mobileQuery.matches);
    this.mobileQuery.addEventListener('change', e => this.isMobile.set(e.matches));
    // load catalog (once, cached) + options, then the first search
    this.ticketService.catalog().subscribe({
      next: c => { this.catalog.set(c ?? []); },
      error: () => { this.catalog.set([]); }
    });
    this.loadOptions();
    this.loadSummary();
    this.runSearch('reset');
  }

  loadSummary(): void {
    this.ticketService.summary(this.fProject() ?? undefined).subscribe({
      next: s => this.summary.set(s),
      error: () => this.summary.set(null)
    });
  }

  /** Chantiers + users for the filters and the form. */
  loadOptions(): void {
    this.optionsError.set(false);
    this.projectService.list().subscribe({
      next: list => { this.projects.set(list ?? []); },
      error: () => { this.projects.set([]); this.optionsError.set(true); }
    });
    this.userService.options().subscribe({
      next: users => this.userOptions.set((users ?? []).map(u => ({ label: u.fullName, value: u.id }))),
      error: () => { this.userOptions.set([]); this.optionsError.set(true); }
    });
  }

  // ── search ────────────────────────────────────────────────────────────

  /** mode: 'reset' (page 0, replace) | 'page' (replace) | 'more' (append). */
  runSearch(mode: 'reset' | 'page' | 'more'): void {
    if (mode === 'reset') { this.page.set(0); }
    if (mode === 'more') { this.page.update(p => p + 1); }
    this.loaded.set(false);
    this.loadError.set(false);
    const tab = this.activeTab();
    this.ticketService.search({
      projectId: this.fProject() ?? undefined,
      type: this.selectedType() ?? undefined,
      status: this.fStatus() ?? undefined,
      mine: tab === 'mine',
      overdue: tab === 'overdue',
      q: this.searchQuery() || undefined,
      page: this.page(),
      size: this.pageSize
    }).subscribe({
      next: res => {
        this.total.set(res.total);
        const rows = res.items.map(i => i.ticket);
        if (mode === 'more') { this.displayed.update(arr => [...arr, ...rows]); }
        else { this.displayed.set(rows); }
        this.loaded.set(true);
      },
      error: () => { this.loadError.set(true); this.loaded.set(true); this.displayed.set([]); }
    });
  }

  onTabChange(tab: Tab): void {
    this.activeTab.set(tab);
    this.runSearch('reset');
  }

  /** One type at a time: click again to clear. */
  onTypeChipClick(code: string): void {
    this.selectedType.set(this.selectedType() === code ? null : code);
    this.runSearch('reset');
  }

  onProjectChange(v: string | null): void {
    this.fProject.set(v);
    this.runSearch('reset');
    this.loadSummary();
  }

  onStatusChange(v: string | null): void {
    this.fStatus.set(v);
    this.runSearch('reset');
  }

  onSearchInput(value: string): void {
    this.searchSubject.next(value ?? '');
  }

  onCounterClick(key: string): void {
    if (key === 'mine') { this.activeTab.set('mine'); this.fStatus.set(null); }
    else if (key === 'overdue') { this.activeTab.set('overdue'); this.fStatus.set(null); }
    else if (key === 'toVerify') { this.activeTab.set('all'); this.fStatus.set('RESOLVED'); }
    else if (key === 'open') { this.activeTab.set('all'); this.fStatus.set('OPEN'); }
    this.runSearch('reset');
  }

  onPageChange(event: any): void {
    this.page.set(event.page ?? 0);
    this.runSearch('page');
  }

  loadMore(): void {
    this.runSearch('more');
  }

  get hasMore(): boolean {
    return this.displayed().length < this.total();
  }

  // ── row helpers ───────────────────────────────────────────────────────

  openTicket(t: Ticket): void { this.router.navigate(['/tickets', t.id]); }

  typeVisual = typeVisual;
  statusVisual = statusVisual;

  isOverdue(t: Ticket): boolean {
    return t.overdue ?? false;
  }

  fmtDate(s: string | null | undefined): string {
    if (!s) { return '—'; }
    const d = this.parseDateOnly(s) ?? new Date(s);
    if (isNaN(d.getTime())) { return s; }
    return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  /** Ouvrage / lieu line: ouvrage name, else location text, else project name. */
  placeLine(t: Ticket): string {
    return t.ouvrageName ?? t.locationText ?? t.projectName ?? '—';
  }

  /** Responsable: assignee name, or tiers (responsible party) name with « externe ». */
  responsable(t: Ticket): { name: string; externe: boolean } {
    if (t.assignedToName) { return { name: t.assignedToName, externe: false }; }
    if (t.responsiblePartyName) { return { name: t.responsiblePartyName, externe: true }; }
    return { name: 'Non assigné', externe: false };
  }

  priorityLabel(p: string | null | undefined): string {
    return p ? (TICKET_PRIORITY_LABELS[p] ?? p) : '—';
  }

  // ── create / edit (existing inline form, kept for T5) ─────────────────

  openCreate(): void {
    this.mode = 'create'; this.editTarget = null;
    this.formTitle = ''; this.formDescription = ''; this.formProject = this.fProject() ?? null;
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

  private body(): TicketUpsert {
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
        this.msgs.add({ severity: 'success', summary: 'Signalement créé' });
        this.showDialog.set(false); this.runSearch('reset'); this.loadSummary();
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err.error?.message ?? 'Création impossible' })
    });
  }

  saveEdit(): void {
    if (!this.editTarget) { return; }
    this.ticketService.update(this.editTarget.id, this.body()).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Signalement modifié' });
        this.showDialog.set(false); this.runSearch('reset');
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err.error?.message ?? 'Modification impossible' })
    });
  }
}
