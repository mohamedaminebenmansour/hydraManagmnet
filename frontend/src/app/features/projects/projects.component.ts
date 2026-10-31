import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { MultiSelectModule } from 'primeng/multiselect';
import { ToggleSwitchModule } from 'primeng/toggleswitch'; // PHASE-OUVRAGE-UI
import { InputNumberModule } from 'primeng/inputnumber';
import { TextareaModule } from 'primeng/textarea';
import { DatePickerModule } from 'primeng/datepicker';
import { DialogModule } from 'primeng/dialog';
import { TagModule } from 'primeng/tag';
import { ChipModule } from 'primeng/chip';
import { MessageService } from 'primeng/api';
import { AuthService } from '@app/core/auth.service';
import { ViewModeService } from '@app/core/view-mode.service';
import { TooltipModule } from 'primeng/tooltip';
import { PERM } from '@app/core/permissions';
import { ProjectService } from '@app/core/services/project.service';
import { UserService } from '@app/core/services/user.service';
import {
  Project, ProjectCreate, ProjectUpdate, ProjectType,
  PROJECT_TYPE_OPTS, PROJECT_TYPE_LABELS
} from '@app/core/models/project.model';
import {
  OuvrageType, OuvrageSpec, OUVRAGE_TYPE_LABELS, OUVRAGE_TYPE_VISUAL,
  ouvrageTypeVisual, ouvrageTypeLabel, ouvrageCharacteristics, Troncon
} from '@app/core/models/ouvrage.model'; // PHASE-OUVRAGE-UI
import { SelectOption } from '@app/core/models/select-option';
import { EmptyStateComponent } from '@app/shared/empty-state.component';
import {
  typeVisual, statusVisual, CHANTIER_TYPE_VISUAL
} from '@app/shared/chantier-visual';

const STATUS_OPTS = [
  { label: 'Actif', value: 'ACTIF' },
  { label: 'Clôturé', value: 'CLOTURE' }
];

@Component({
  selector: 'app-projects',
  standalone: true,
  imports: [
    FormsModule,
    ButtonModule,
    InputTextModule,
    SelectModule,
    MultiSelectModule,
    InputNumberModule,
    TextareaModule,
    DatePickerModule,
    DialogModule,
    ToggleSwitchModule,
    TagModule,
    ChipModule,
    TooltipModule,
    EmptyStateComponent,
  ],
  templateUrl: './projects.component.html',
  styleUrl: './projects.component.scss'
})
export class ProjectsComponent implements OnInit {
  private projectService = inject(ProjectService);
  private userService = inject(UserService);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);
  viewModeService = inject(ViewModeService);
  viewMode = this.viewModeService.forPage('projects', 'table');

  /** Chantiers chargés depuis l'API (signal → l'UI se met à jour automatiquement). */
  projects = signal<Project[]>([]);
  userOptions = signal<SelectOption[]>([]);
  readonly statusOpts = STATUS_OPTS;
  readonly projectTypeOpts = PROJECT_TYPE_OPTS;

  mode: 'create' | 'edit' = 'create';
  showDialog = false;

  // ---- Wizard de création (3 étapes) --------------------------------------
  // Étape 1 : Identité (type + nom + lieu) — le minimum vital.
  // Étape 2 : Calendrier & équipe (optionnel).
  // Étape 3 : Contrat & détails (optionnel).
  readonly WIZARD_STEPS = ['Identité', 'Ouvrages', 'Calendrier', 'Contrat']; // PHASE-OUVRAGE-UI: 4 steps
  step = signal(1);
  typeKeys = Object.keys(CHANTIER_TYPE_VISUAL);
  // ⚠️ Signaux obligatoires : un computed() qui lit des propriétés simples ne
  //    se recalcule JAMAIS (dépendance non trackée) → grille figée sur "vide".
  statusFilter = signal<'ALL' | 'ACTIF' | 'CLOTURE'>('ALL');
  typeFilter = signal<string | null>(null);
  searchTerm = signal('');

  /** Chantiers après filtres visuels (statut, type, recherche). */
  filteredProjects = computed(() => {
    const q = this.searchTerm().trim().toLowerCase();
    const sf = this.statusFilter();
    const tf = this.typeFilter();
    return this.projects().filter(p => {
      if (sf !== 'ALL' && (p.status ?? 'ACTIF') !== sf) { return false; }
      if (tf && (p.projectType ?? 'AUTRE') !== tf) { return false; }
      if (q) {
        const hay = `${p.name} ${p.code ?? ''} ${p.clientName ?? ''} ${p.location ?? ''} ${p.governorate ?? ''}`.toLowerCase();
        if (!hay.includes(q)) { return false; }
      }
      return true;
    });
  });

  formName = '';
  formCode = '';
  formClientName = '';
  formLocation = '';
  formGovernorate = '';
  formProjectType: ProjectType | null = null;
  formStartDate: Date | null = null;
  formEndDate: Date | null = null;
  formContractValue: number | null = null;
  formMembers: string[] = [];
  formDescription = '';
  formStatus = 'ACTIF';
  private editTarget: Project | null = null;

  // ── PHASE-OUVRAGE-UI — wizard "Ouvrages" step draft state ────────────
  /** Draft ouvrages created in the wizard. Persisted as ouvrages after the
   *  chantier is created (POST /api/projects/{id}/ouvrages for each).
   *  Each draft gets a temporary negative id so the template can track it. */
  draftOuvrages = signal<Array<{
    tempId: number;
    type: OuvrageType;
    name: string;
    code: string;
    characteristics: Record<string, any>;
    troncons: Troncon[];
  }>>([]);
  private _nextDraftId = -1;

  /** The 6 ouvrage types the user can add in the wizard (COMMUN is auto). */
  readonly ADDABLE_OUVRAGE_TYPES: OuvrageType[] =
    ['RESERVOIR', 'CONDUITE', 'STATION_POMPAGE', 'FORAGE', 'OUVRAGE_ANNEXE', 'AUTRE'];

  ouvrageTypeLabel = ouvrageTypeLabel;
  ouvrageTypeVisual = ouvrageTypeVisual;
  ouvrageCharacteristics = ouvrageCharacteristics;

  /** Add a draft ouvrage of the given type to the wizard. */
  addDraftOuvrage(type: OuvrageType): void {
    const name = OUVRAGE_TYPE_LABELS[type];
    this.draftOuvrages.update(list => [...list, {
      tempId: this._nextDraftId--,
      type,
      name,
      code: '',
      characteristics: {},
      troncons: type === 'CONDUITE' ? [] : [],
    }]);
  }

  removeDraftOuvrage(tempId: number): void {
    this.draftOuvrages.update(list => list.filter(o => o.tempId !== tempId));
  }

  /** Add an empty tronçon to a CONDUITE draft. */
  addTroncon(tempId: number): void {
    this.draftOuvrages.update(list => list.map(o =>
      o.tempId === tempId ? { ...o, troncons: [...o.troncons, { de_mm: 110, pn_bar: 16, longueur_m: 0 }] } : o));
  }

  removeTroncon(tempId: number, idx: number): void {
    this.draftOuvrages.update(list => list.map(o =>
      o.tempId === tempId ? { ...o, troncons: o.troncons.filter((_, i) => i !== idx) } : o));
  }

  /** Reset the draft ouvrages list (called by openCreate). */
  resetDraftOuvrages(): void {
    this.draftOuvrages.set([]);
    this._nextDraftId = -1;
  }

  /** Whether a draft ouvrage has at least the name filled (for the "skip"
   *  hint). The step can always be skipped — this is purely cosmetic. */
  draftIsValid(o: { name: string }): boolean {
    return !!(o.name && o.name.trim());
  }

  canWrite = computed(() => this.auth.can(PERM.PROJECT_WRITE));
  memberOpts = computed(() => this.userOptions());

  tv = typeVisual;   // template helper : type → icône/couleur
  sv = statusVisual; // template helper : statut → point couleur

  activeCount(): number { return this.projects().filter(p => (p.status ?? 'ACTIF') === 'ACTIF').length; }

  setStatusFilter(v: 'ALL' | 'ACTIF' | 'CLOTURE'): void { this.statusFilter.set(v); }

  toggleTypeFilter(t: string): void { this.typeFilter.set(this.typeFilter() === t ? null : t); }

  resetFilters(): void { this.statusFilter.set('ALL'); this.typeFilter.set(null); this.searchTerm.set(''); }

  wNext(): void { if (this.step() < 4) { this.step.set(this.step() + 1); } } // PHASE-OUVRAGE-UI: 4 steps

  wBack(): void { if (this.step() > 1) { this.step.set(this.step() - 1); } }

  typeIcon(t: string): string { return typeVisual(t).icon; }

  setType(t: string): void { this.formProjectType = t as ProjectType; }

  ngOnInit(): void { this.load(); this.loadMemberOptions(); this.checkEditParam(); }

  /** Support de /projects?edit=<id> : ouvre directement le dialogue de modification. */
  private checkEditParam(): void {
    const id = this.route.snapshot.queryParamMap.get('edit');
    if (!id) { return; }
    this.projectService.get(id).subscribe({
      next: p => this.openEdit(p),
      error: () => { /* chantier inaccessible : on ignore silencieusement */ }
    });
  }

  isActive(p: Project): boolean { return (p.status ?? 'ACTIF') === 'ACTIF'; }

  typeLabel(t: string | null | undefined): string {
    return t ? (PROJECT_TYPE_LABELS[t] ?? t) : '';
  }

  /** PHASE-OUVRAGE-UI — what to show on a chantier card instead of the type
   *  label: the backend-built ouvrageSummary when present, else the type label
   *  (so existing projects without ouvrages still show something). */
  cardSubtitle(p: Project): string {
    const s = (p as any).ouvrageSummary;
    return s && s.trim() ? s.trim() : this.typeLabel(p.projectType);
  }

  fmtDate(iso: string | null | undefined): string {
    if (!iso) { return '—'; }
    const d = new Date(iso);
    if (isNaN(d.getTime())) { return iso; }
    return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  open(p: Project): void { this.router.navigate(['/projects', p.id]); }

  load(): void {
    this.projectService.list().subscribe({
      next: list => this.projects.set(list ?? []),
      error: () => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Chargement impossible' })
    });
  }

  private loadMemberOptions(): void {
    if (!this.canWrite()) { return; }
    this.userService.options().subscribe({
      next: list => this.userOptions.set(
        (list ?? []).map(u => ({ label: u.fullName || u.email, value: u.id }))
      ),
      error: () => this.userOptions.set([])
    });
  }

  openCreate(): void {
    this.mode = 'create';
    this.editTarget = null;
    this.step.set(1);
    this.formName = '';
    this.formCode = '';
    this.formClientName = '';
    this.formLocation = '';
    this.formGovernorate = '';
    this.formProjectType = null;
    this.formStartDate = null;
    this.formEndDate = null;
    this.formContractValue = null;
    this.formMembers = [];
    this.formDescription = '';
    this.formStatus = 'ACTIF';
    this.resetDraftOuvrages(); // PHASE-OUVRAGE-UI
    this.showDialog = true;
  }

  openEdit(p: Project): void {
    this.mode = 'edit';
    this.editTarget = p;
    this.step.set(1);
    this.formName = p.name ?? '';
    this.formCode = p.code ?? '';
    this.formClientName = p.clientName ?? '';
    this.formLocation = p.location ?? '';
    this.formGovernorate = p.governorate ?? '';
    this.formProjectType = p.projectType ?? null;
    this.formStartDate = p.startDate ? this.parseDateOnly(p.startDate) : null;
    this.formEndDate = p.endDate ? this.parseDateOnly(p.endDate) : null;
    this.formContractValue = p.contractValue ?? null;
    this.formMembers = [];
    this.formDescription = p.description ?? '';
    this.formStatus = p.status ?? 'ACTIF';
    this.showDialog = true;
  }

  submit(): void {
    if (!this.formName.trim()) { return; }
    if (this.mode === 'edit') { this.saveEdit(); } else { this.create(); }
  }

  private parseDateOnly(s: string): Date | null {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s);
    if (m) { return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3])); }
    const d = new Date(s);
    return isNaN(d.getTime()) ? null : d;
  }

  private createBody(): ProjectCreate {
    // PHASE-OUVRAGE-EXTRACT — send the draft ouvrages in the SAME request
    // as the project. The backend saves project + COMMUN + ouvrages in one
    // @Transactional; if any ouvrage is invalid, nothing is saved and the
    // error message says which ouvrage and which field.
    const ouvrages = this.draftOuvrages().map(d => ({
      type: d.type,
      name: d.name,
      code: d.code || null,
      characteristics: d.type === 'CONDUITE' && d.troncons.length
        ? { ...d.characteristics, troncons: d.troncons }
        : d.characteristics,
    }));
    return {
      name: this.formName.trim(),
      code: this.formCode.trim() || null,
      clientName: this.formClientName.trim() || null,
      location: this.formLocation.trim() || null,
      governorate: this.formGovernorate.trim() || null,
      projectType: this.formProjectType,
      startDate: this.formStartDate,
      endDate: this.formEndDate,
      contractValue: this.formContractValue ?? null,
      description: this.formDescription.trim() || null,
      memberUserIds: this.formMembers,
      ouvrages
    };
  }

  create(): void {
    this.projectService.create(this.createBody()).subscribe({
      next: (project) => {
        this.msgs.add({ severity: 'success',
          summary: 'Chantier créé — dossiers créés automatiquement' });
        this.showDialog = false;
        this.router.navigate(['/projects', project.id]);
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err.error?.message ?? 'Création impossible' })
    });
  }

  saveEdit(): void {
    if (!this.editTarget) { return; }
    const body: ProjectUpdate = {
      name: this.formName.trim(),
      code: this.formCode.trim() || null,
      status: this.formStatus,
      clientName: this.formClientName.trim() || null,
      location: this.formLocation.trim() || null,
      governorate: this.formGovernorate.trim() || null,
      projectType: this.formProjectType,
      startDate: this.formStartDate,
      endDate: this.formEndDate,
      contractValue: this.formContractValue ?? null,
      description: this.formDescription.trim() || null
    };
    this.projectService.update(this.editTarget.id, body).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Chantier modifié' });
        this.showDialog = false; this.load();
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err.error?.message ?? 'Modification impossible' })
    });
  }
}
