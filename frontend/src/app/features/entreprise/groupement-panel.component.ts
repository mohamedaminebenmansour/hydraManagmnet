import { Component, EventEmitter, Input, Output, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { DrawerModule } from 'primeng/drawer';
import { MessageService } from 'primeng/api';
import { CompanyService, Groupement, GroupementMember } from '@app/core/services/company.service';
import { ProjectService } from '@app/core/services/project.service';
import { CompanyProfile } from '@app/core/services/company.service';

interface MemberRow {
  isSelf: boolean;
  raisonSociale: string;
  matriculeFiscal: string;
  pays: string;
  role: string;
  partPct: string; // string for input binding, parsed to number on save
  prestations: string;
  fieldErrors: { raisonSociale?: string; matriculeFiscal?: string; role?: string; partPct?: string };
}

interface ProjectOption { id: string; name: string; }

@Component({
  selector: 'app-groupement-panel',
  standalone: true,
  imports: [FormsModule, ButtonModule, InputTextModule, SelectModule, DrawerModule],
  template: `
    <p-drawer [visible]="visible()" (visibleChange)="visible.set($event)" position="right"
              [style]="{width: '640px'}" styleClass="groupement-panel-drawer">
      <div class="panel-header">
        <h2>{{ editing() ? 'Modifier le groupement' : 'Nouveau groupement' }}</h2>
        <button class="close" (click)="close()" aria-label="Fermer"><i class="pi pi-times"></i></button>
      </div>

      @if (staleError()) {
        <div class="banner banner-warn">
          <i class="pi pi-exclamation-triangle"></i> Modifié par quelqu'un d'autre.
          <p-button label="Recharger" size="small" (onClick)="reloadFromServer()" />
        </div>
      }

      <div class="panel-body">
        <!-- Identity fields -->
        <div class="field">
          <label>Nom du groupement *</label>
          <input pInputText [(ngModel)]="nom" (ngModelChange)="clearFieldError('nom')" />
          @if (fieldErrors()['nom']) { <small class="field-error">{{ fieldErrors()['nom'] }}</small> }
        </div>

        <div class="field">
          <label>Type *</label>
          <div class="radio-cards">
            <label class="radio-card" [class.selected]="type === 'CONJOINT'">
              <input type="radio" name="type" value="CONJOINT" [(ngModel)]="type" (ngModelChange)="clearFieldError('type')" />
              <span class="rc-title">Conjoint</span>
              <span class="rc-desc">chaque membre s'engage sur sa part</span>
            </label>
            <label class="radio-card" [class.selected]="type === 'SOLIDAIRE'">
              <input type="radio" name="type" value="SOLIDAIRE" [(ngModel)]="type" (ngModelChange)="clearFieldError('type')" />
              <span class="rc-title">Solidaire</span>
              <span class="rc-desc">chaque membre répond de tout le marché</span>
            </label>
          </div>
          @if (fieldErrors()['type']) { <small class="field-error">{{ fieldErrors()['type'] }}</small> }
        </div>

        <div class="field">
          <label>Chantier (facultatif)</label>
          <p-select [options]="projects()" [(ngModel)]="projectId" optionLabel="name" optionValue="id"
                    placeholder="Sélectionner un chantier" styleClass="w100" [showClear]="true" />
        </div>

        <div class="field">
          <label>Référence marché</label>
          <input pInputText [(ngModel)]="referenceMarche" />
        </div>

        <div class="field">
          <label>Notes</label>
          <input pInputText [(ngModel)]="notes" />
        </div>

        <!-- Members table -->
        <div class="members-section">
          <div class="members-header">
            <label>Membres</label>
            <p-button label="Ajouter un membre" icon="pi pi-plus" size="small" [text]="true" (onClick)="addMember()" />
          </div>
          @if (fieldErrors()['membres']) { <small class="field-error">{{ fieldErrors()['membres'] }}</small> }

          <!-- Desktop: table -->
          <div class="members-table desktop-only">
            <div class="mt-head">
              <span class="col-name">Raison sociale</span>
              <span class="col-mf">Matricule fiscal</span>
              <span class="col-mand">Mandataire</span>
              <span class="col-part">Part %</span>
              <span class="col-prest">Prestations</span>
              <span class="col-action"></span>
            </div>
            @for (m of members(); track $index) {
              <div class="mt-row" [class.is-self]="m.isSelf">
                <span class="col-name">
                  @if (m.isSelf) {
                    <input pInputText [value]="m.raisonSociale" disabled />
                    <small class="self-tag">Notre entreprise</small>
                  } @else {
                    <input pInputText [(ngModel)]="m.raisonSociale" (ngModelChange)="clearMemberError($index, 'raisonSociale')" />
                    @if (m.fieldErrors.raisonSociale) { <small class="field-error">{{ m.fieldErrors.raisonSociale }}</small> }
                  }
                </span>
                <span class="col-mf">
                  @if (m.isSelf) {
                    <input pInputText [value]="m.matriculeFiscal" disabled />
                  } @else {
                    <input pInputText [(ngModel)]="m.matriculeFiscal" (ngModelChange)="clearMemberError($index, 'matriculeFiscal')" placeholder="1234567/A/B/M/000" />
                    @if (m.fieldErrors.matriculeFiscal) { <small class="field-error">{{ m.fieldErrors.matriculeFiscal }}</small> }
                  }
                </span>
                <span class="col-mand">
                  <input type="radio" name="mandataire" [checked]="m.role === 'MANDATAIRE'"
                         (change)="setMandataire($index)" aria-label="Mandataire" />
                </span>
                <span class="col-part">
                  <input pInputText type="text" inputmode="decimal" [(ngModel)]="m.partPct"
                         (ngModelChange)="clearMemberError($index, 'partPct')" />
                  @if (m.fieldErrors.partPct) { <small class="field-error">{{ m.fieldErrors.partPct }}</small> }
                </span>
                <span class="col-prest">
                  <input pInputText [(ngModel)]="m.prestations" />
                </span>
                <span class="col-action">
                  @if (!m.isSelf) {
                    <button class="link-btn" (click)="removeMember($index)" aria-label="Retirer">
                      <i class="pi pi-trash"></i> Retirer
                    </button>
                  }
                </span>
              </div>
            }
          </div>

          <!-- Mobile: cards -->
          <div class="members-cards mobile-only">
            @for (m of members(); track $index) {
              <div class="member-card" [class.is-self]="m.isSelf">
                <div class="mc-field">
                  <label>Raison sociale</label>
                  @if (m.isSelf) {
                    <input pInputText [value]="m.raisonSociale" disabled />
                    <small class="self-tag">Notre entreprise</small>
                  } @else {
                    <input pInputText [(ngModel)]="m.raisonSociale" (ngModelChange)="clearMemberError($index, 'raisonSociale')" />
                    @if (m.fieldErrors.raisonSociale) { <small class="field-error">{{ m.fieldErrors.raisonSociale }}</small> }
                  }
                </div>
                <div class="mc-field">
                  <label>Matricule fiscal</label>
                  @if (m.isSelf) {
                    <input pInputText [value]="m.matriculeFiscal" disabled />
                  } @else {
                    <input pInputText [(ngModel)]="m.matriculeFiscal" (ngModelChange)="clearMemberError($index, 'matriculeFiscal')" placeholder="1234567/A/B/M/000" />
                    @if (m.fieldErrors.matriculeFiscal) { <small class="field-error">{{ m.fieldErrors.matriculeFiscal }}</small> }
                  }
                </div>
                <div class="mc-field">
                  <label>Mandataire</label>
                  <label class="toggle"><input type="radio" name="mandataire-mob" [checked]="m.role === 'MANDATAIRE'"
                         (change)="setMandataire($index)" /> Mandataire</label>
                </div>
                <div class="mc-field">
                  <label>Part %</label>
                  <input pInputText type="text" inputmode="decimal" [(ngModel)]="m.partPct"
                         (ngModelChange)="clearMemberError($index, 'partPct')" />
                  @if (m.fieldErrors.partPct) { <small class="field-error">{{ m.fieldErrors.partPct }}</small> }
                </div>
                <div class="mc-field">
                  <label>Prestations</label>
                  <input pInputText [(ngModel)]="m.prestations" />
                </div>
                @if (!m.isSelf) {
                  <button class="link-btn" (click)="removeMember($index)"><i class="pi pi-trash"></i> Retirer</button>
                }
              </div>
            }
          </div>

          <!-- Live total footer -->
          <div class="total-footer" [class.ok]="totalState() === 'ok'" [class.under]="totalState() === 'under'" [class.over]="totalState() === 'over'">
            @if (totalState() === 'ok') {
              <i class="pi pi-check-circle"></i> Total des parts : 100 %
            } @else if (totalState() === 'under') {
              <i class="pi pi-exclamation-triangle"></i> Total des parts : {{ totalPct() }} % — il manque {{ 100 - totalHundredths() / 100 }} %
            } @else {
              <i class="pi pi-exclamation-triangle"></i> Total des parts : {{ totalPct() }} % — {{ totalHundredths() / 100 - 100 }} % de trop
            }
          </div>
          @if (fieldErrors()['parts']) { <small class="field-error">{{ fieldErrors()['parts'] }}</small> }
        </div>

        <div class="panel-actions">
          <p-button label="Annuler" [text]="true" (onClick)="close()" />
          <p-button label="Enregistrer" icon="pi pi-check" severity="success"
                    [disabled]="!canSave()" [loading]="saving()" (onClick)="save()" />
        </div>
      </div>
    </p-drawer>
  `,
  styles: [`
    .panel-header { display: flex; justify-content: space-between; align-items: center;
      padding: 16px 20px; border-bottom: 1px solid #e2e8f0; }
    .panel-header h2 { margin: 0; font-size: 18px; font-weight: 700; color: #0f172a; }
    .close { border: none; background: none; cursor: pointer; color: #64748b; font-size: 18px; padding: 4px; }
    .panel-body { padding: 20px; overflow-y: auto; display: flex; flex-direction: column; gap: 16px; }

    .banner { padding: 10px 12px; border-radius: 8px; display: flex; align-items: center; gap: 8px; font-size: 13px; }
    .banner-warn { background: #fffbeb; color: #b45309; }

    .field { display: flex; flex-direction: column; gap: 4px; }
    .field label { font-size: 13px; font-weight: 600; color: #475569; }
    .field input { font-size: 15px; }
    :host ::ng-deep .w100 { width: 100%; }
    .field-error { color: #dc2626; font-size: 12px; margin-top: 2px; display: block; }

    .radio-cards { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    .radio-card { display: flex; flex-direction: column; gap: 2px; padding: 10px 12px; border: 2px solid #e2e8f0;
      border-radius: 8px; cursor: pointer; }
    .radio-card.selected { border-color: #0f766e; background: #f0fdfa; }
    .radio-card input { display: none; }
    .rc-title { font-weight: 700; font-size: 14px; color: #0f172a; }
    .rc-desc { font-size: 12px; color: #64748b; }

    .members-section { display: flex; flex-direction: column; gap: 8px; }
    .members-header { display: flex; justify-content: space-between; align-items: center; }
    .members-header label { font-size: 14px; font-weight: 700; color: #0f172a; }

    .members-table { border: 1px solid #e2e8f0; border-radius: 8px; overflow: hidden; }
    .mt-head, .mt-row { display: grid;
      grid-template-columns: 1.6fr 1.4fr 60px 80px 1.2fr 80px; gap: 4px; padding: 6px 8px; align-items: start; }
    .mt-head { background: #f8fafc; font-size: 12px; font-weight: 600; color: #475569; padding: 8px; }
    .mt-row { border-top: 1px solid #f1f5f9; }
    .mt-row.is-self { background: #f0fdfa; }
    .col-name, .col-mf, .col-part, .col-prest { display: flex; flex-direction: column; }
    .col-mand, .col-action { display: flex; align-items: center; justify-content: center; }
    .self-tag { color: #0f766e; font-size: 11px; font-weight: 600; }
    .link-btn { border: none; background: none; color: #dc2626; cursor: pointer; font-size: 12px; padding: 4px;
      display: inline-flex; align-items: center; gap: 4px; }

    .members-cards { display: flex; flex-direction: column; gap: 8px; }
    .member-card { border: 1px solid #e2e8f0; border-radius: 8px; padding: 12px; display: flex; flex-direction: column; gap: 8px; }
    .member-card.is-self { background: #f0fdfa; }
    .mc-field { display: flex; flex-direction: column; gap: 4px; }
    .mc-field label { font-size: 12px; font-weight: 600; color: #475569; }
    .toggle { display: flex; align-items: center; gap: 6px; font-size: 14px; }

    .total-footer { padding: 10px 12px; border-radius: 8px; font-size: 14px; font-weight: 600; display: flex; align-items: center; gap: 6px; }
    .total-footer.ok { background: #f0fdf4; color: #16a34a; }
    .total-footer.under { background: #fef2f2; color: #dc2626; }
    .total-footer.over { background: #fef2f2; color: #dc2626; }

    .panel-actions { display: flex; justify-content: flex-end; gap: 8px; padding-top: 8px; border-top: 1px solid #f1f5f9; }

    .desktop-only { display: flex; flex-direction: column; }
    .mobile-only { display: none; }
    @media (max-width: 640px) {
      .desktop-only { display: none; }
      .mobile-only { display: flex; flex-direction: column; }
    }
  `]
})
export class GroupementPanelComponent {
  private company = inject(CompanyService);
  private projectService = inject(ProjectService);
  private msgs = inject(MessageService);

  visible = signal(false);
  editing = signal(false);
  editingId = signal<string | null>(null);
  version = signal(0);
  saving = signal(false);
  staleError = signal(false);

  // Form fields (plain properties — ngModel binds to these directly)
  nom = '';
  type: 'CONJOINT' | 'SOLIDAIRE' | '' = '';
  projectId: string | null = null;
  referenceMarche = '';
  notes = '';
  members = signal<MemberRow[]>([]);

  // Server field errors keyed by "nom" / "type" / "membres" / "parts" / "membres[i].x"
  fieldErrors = signal<Record<string, string>>({});

  projects = signal<ProjectOption[]>([]);

  // Profile (for isSelf row)
  private profileData: CompanyProfile | null = null;

  @Input() set profile(p: CompanyProfile | null) {
    this.profileData = p;
    // If the panel is open and the self row exists, refresh its read-only fields
    const rows = this.members();
    if (rows.length > 0 && rows[0].isSelf && p) {
      rows[0].raisonSociale = p.raisonSociale ?? '';
      rows[0].matriculeFiscal = p.matriculeFiscal ?? '';
      this.members.set([...rows]);
    }
  }
  @Output() saved = new EventEmitter<void>();

  constructor() {
    this.projectService.options().subscribe({
      next: opts => this.projects.set((opts ?? []).map(o => ({ id: o.id, name: o.name }))),
      error: () => this.projects.set([])
    });
  }

  openNew(): void {
    this.editing.set(false);
    this.editingId.set(null);
    this.version.set(0);
    this.nom = '';
    this.type = '';
    this.projectId = null;
    this.referenceMarche = '';
    this.notes = '';
    this.fieldErrors.set({});
    this.staleError.set(false);
    // Start with row 1 (self) + one empty partner row
    const selfRow: MemberRow = {
      isSelf: true,
      raisonSociale: this.profileData?.raisonSociale ?? '',
      matriculeFiscal: this.profileData?.matriculeFiscal ?? '',
      pays: 'TN',
      role: 'MANDATAIRE',
      partPct: '',
      prestations: '',
      fieldErrors: {}
    };
    const partnerRow: MemberRow = {
      isSelf: false,
      raisonSociale: '',
      matriculeFiscal: '',
      pays: 'TN',
      role: 'MEMBRE',
      partPct: '',
      prestations: '',
      fieldErrors: {}
    };
    this.members.set([selfRow, partnerRow]);
    this.visible.set(true);
  }

  openEdit(g: Groupement): void {
    this.editing.set(true);
    this.editingId.set(g.id);
    this.version.set(g.version);
    this.nom = g.nom;
    this.type = g.type as 'CONJOINT' | 'SOLIDAIRE';
    this.projectId = g.projectId ?? null;
    this.referenceMarche = g.referenceMarche ?? '';
    this.notes = g.notes ?? '';
    this.fieldErrors.set({});
    this.staleError.set(false);
    this.members.set(g.membres.map(m => ({
      isSelf: m.isSelf,
      raisonSociale: m.isSelf ? (this.profileData?.raisonSociale ?? m.raisonSociale) : m.raisonSociale,
      matriculeFiscal: m.isSelf ? (this.profileData?.matriculeFiscal ?? m.matriculeFiscal ?? '') : (m.matriculeFiscal ?? ''),
      pays: m.pays ?? 'TN',
      role: m.role,
      partPct: m.partPct != null ? String(m.partPct) : '',
      prestations: m.prestations ?? '',
      fieldErrors: {}
    })));
    this.visible.set(true);
  }

  close(): void {
    this.visible.set(false);
  }

  reloadFromServer(): void {
    const id = this.editingId();
    if (!id) return;
    this.company.getGroupement(id).subscribe({
      next: g => this.openEdit(g),
      error: () => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Rechargement impossible.' })
    });
  }

  addMember(): void {
    this.members.update(rows => [...rows, {
      isSelf: false, raisonSociale: '', matriculeFiscal: '', pays: 'TN',
      role: 'MEMBRE', partPct: '', prestations: '', fieldErrors: {}
    }]);
  }

  removeMember(idx: number): void {
    this.members.update(rows => rows.filter((_, i) => i !== idx));
  }

  setMandataire(idx: number): void {
    this.members.update(rows => rows.map((r, i) => ({ ...r, role: i === idx ? 'MANDATAIRE' : 'MEMBRE' })));
  }

  clearFieldError(field: string): void {
    if (this.fieldErrors()[field]) {
      const next = { ...this.fieldErrors() };
      delete next[field];
      this.fieldErrors.set(next);
    }
  }

  clearMemberError(idx: number, field: 'raisonSociale' | 'matriculeFiscal' | 'role' | 'partPct'): void {
    const rows = this.members();
    if (rows[idx]?.fieldErrors[field]) {
      rows[idx].fieldErrors[field] = undefined;
      this.members.set([...rows]);
    }
    // Also clear the server-side key
    const key = `membres[${idx}].${field}`;
    if (this.fieldErrors()[key]) {
      const next = { ...this.fieldErrors() };
      delete next[key];
      this.fieldErrors.set(next);
    }
  }

  // ── Live total: computed with integer hundredths (no float sums) ────────

  /** Parse a partPct string into hundredths (integer). Returns null if invalid. */
  private parseHundredths(s: string): number | null {
    if (!s || !s.trim()) return null;
    const t = s.trim().replace(',', '.');
    if (!/^\d+(\.\d{1,2})?$/.test(t)) return null;
    const n = Math.round(Number(t) * 100);
    return n > 0 ? n : null;
  }

  /** Sum of all member parts in hundredths. */
  totalHundredths = computed(() => {
    let sum = 0;
    for (const m of this.members()) {
      const h = this.parseHundredths(m.partPct);
      if (h == null) continue;
      sum += h;
    }
    return sum;
  });

  totalPct = computed(() => {
    const h = this.totalHundredths();
    return (h / 100).toString().replace('.', ',');
  });

  totalState = computed<'ok' | 'under' | 'over' | 'empty'>(() => {
    const h = this.totalHundredths();
    if (h === 0) return 'empty';
    if (h === 10000) return 'ok';
    return h < 10000 ? 'under' : 'over';
  });

  // ── Save validation ─────────────────────────────────────────────────────

  canSave(): boolean {
    if (!this.nom.trim()) return false;
    if (this.type !== 'CONJOINT' && this.type !== 'SOLIDAIRE') return false;
    const rows = this.members();
    if (rows.length < 2) return false;
    let mandataire = 0;
    for (const m of rows) {
      if (!m.isSelf && !m.raisonSociale.trim()) return false;
      if (m.role === 'MANDATAIRE') mandataire++;
      const h = this.parseHundredths(m.partPct);
      if (h == null) return false;
    }
    if (mandataire !== 1) return false;
    return this.totalHundredths() === 10000;
  }

  save(): void {
    if (!this.canSave()) return;
    this.saving.set(true);
    this.fieldErrors.set({});
    this.staleError.set(false);

    const body = {
      nom: this.nom.trim(),
      type: this.type,
      projectId: this.projectId ?? null,
      referenceMarche: this.referenceMarche || null,
      notes: this.notes || null,
      version: this.version(),
      membres: this.members().map(m => ({
        isSelf: m.isSelf,
        raisonSociale: m.raisonSociale || null,
        matriculeFiscal: m.matriculeFiscal || null,
        pays: m.pays,
        role: m.role,
        partPct: Number(m.partPct.replace(',', '.')),
        prestations: m.prestations || null
      }))
    };

    const id = this.editingId();
    const op = id ? this.company.updateGroupement(id, body) : this.company.createGroupement(body);
    op.subscribe({
      next: () => {
        this.saving.set(false);
        this.visible.set(false);
        this.msgs.add({ severity: 'success', summary: 'Groupement enregistré' });
        this.saved.emit();
      },
      error: (err) => {
        this.saving.set(false);
        if (err?.status === 400 && err?.error?.code === 'VALIDATION') {
          this.applyServerFieldErrors(err.error.fields ?? {});
        } else if (err?.status === 409) {
          this.staleError.set(true);
        } else {
          this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err?.error?.message ?? 'Enregistrement impossible.' });
        }
      }
    });
  }

  /** Map server field errors like "membres[1].raisonSociale" to the matching input. */
  private applyServerFieldErrors(fields: Record<string, string>): void {
    const next: Record<string, string> = {};
    const rows = this.members();
    for (const k of Object.keys(fields)) {
      next[k] = fields[k];
      const m = /^membres\[(\d+)\]\.(raisonSociale|matriculeFiscal|role|partPct)$/.exec(k);
      if (m) {
        const idx = Number(m[1]);
        const field = m[2] as 'raisonSociale' | 'matriculeFiscal' | 'role' | 'partPct';
        if (rows[idx]) {
          rows[idx].fieldErrors[field] = fields[k];
        }
      }
    }
    this.fieldErrors.set(next);
    this.members.set([...rows]);
    // Switch to the tab/section of the first error (the panel is one section, so just scroll up)
    this.msgs.add({ severity: 'error', summary: 'Erreur de validation', detail: 'Vérifiez les champs en rouge.' });
  }
}
