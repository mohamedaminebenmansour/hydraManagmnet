import { Component, Input, Output, EventEmitter, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { InputNumberModule } from 'primeng/inputnumber';
import { SelectModule } from 'primeng/select';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { DialogModule } from 'primeng/dialog';
import { TooltipModule } from 'primeng/tooltip';
import { MessageService } from 'primeng/api';
import { ProjectService } from '@app/core/services/project.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import {
  Ouvrage, OuvrageType, OuvrageCreate, OuvrageUpdate, Troncon,
  OUVRAGE_TYPE_LABELS, ouvrageTypeVisual, ouvrageTypeLabel,
  ouvrageCharacteristics
} from '@app/core/models/ouvrage.model';
import { EmptyStateComponent } from '@app/shared/empty-state.component';

/**
 * OuvragesTabComponent — standalone component that shows the ouvrages of a
 * chantier as a card grid, with add / edit / delete dialogs.
 *
 * Inputs:  `projectId` — the chantier id.
 * Outputs: `changed` — emitted whenever an ouvrage is created/updated/deleted,
 *          so the parent (project-detail) can re-fetch the project to refresh
 *          the ouvrageSummary on the hero strip.
 *
 * Extracted from project-detail.component per the user's instruction to keep
 * the Ouvrages tab styles in its own component (instead of growing the
 * project-detail SCSS budget).
 */
@Component({
  selector: 'app-ouvrages-tab',
  standalone: true,
  imports: [
    CommonModule, FormsModule,
    ButtonModule, InputTextModule, InputNumberModule, SelectModule,
    ToggleSwitchModule, DialogModule, TooltipModule,
    EmptyStateComponent,
  ],
  templateUrl: './ouvrages-tab.component.html',
  styleUrl: './ouvrages-tab.component.scss'
})
export class OuvragesTabComponent implements OnInit {
  private projectService = inject(ProjectService);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  @Input({ required: true }) projectId!: string;
  @Output() changed = new EventEmitter<void>();

  ouvrages = signal<Ouvrage[]>([]);
  loading = signal(false);

  readonly ADDABLE_OUVRAGE_TYPES: OuvrageType[] =
    ['RESERVOIR', 'CONDUITE', 'STATION_POMPAGE', 'FORAGE', 'OUVRAGE_ANNEXE', 'AUTRE'];

  ouvrageTypeLabel = ouvrageTypeLabel;
  ouvrageTypeVisual = ouvrageTypeVisual;
  ouvrageCharacteristics = ouvrageCharacteristics;

  canWrite = computed(() => this.auth.can(PERM.PROJECT_WRITE));

  // Add / edit / delete ouvrage dialogs
  showDialog = false;
  editMode = false;
  editId: string | null = null;
  formType: OuvrageType = 'RESERVOIR';
  formName = '';
  formCode = '';
  formCharacteristics: Record<string, any> = {};
  formTroncons: Troncon[] = [];
  saving = signal(false);

  showDeleteDialog = false;
  deleteTarget: Ouvrage | null = null;

  ngOnInit(): void { this.load(); }

  load(): void {
    if (!this.projectId) { return; }
    this.loading.set(true);
    this.projectService.listOuvrages(this.projectId).subscribe({
      next: list => { this.ouvrages.set(list ?? []); this.loading.set(false); },
      error: () => { this.ouvrages.set([]); this.loading.set(false); }
    });
  }

  openAdd(type: OuvrageType): void {
    this.editMode = false;
    this.editId = null;
    this.formType = type;
    this.formName = OUVRAGE_TYPE_LABELS[type];
    this.formCode = '';
    this.formCharacteristics = {};
    this.formTroncons = type === 'CONDUITE' ? [] : [];
    this.showDialog = true;
  }

  openEdit(o: Ouvrage): void {
    this.editMode = true;
    this.editId = o.id;
    this.formType = o.type;
    this.formName = o.name;
    this.formCode = o.code ?? '';
    this.formCharacteristics = { ...(o.characteristics ?? {}) };
    const tr = (o.characteristics ?? {})['troncons'];
    this.formTroncons = Array.isArray(tr) ? tr.map((t: any) => ({
      de_mm: Number(t?.de_mm ?? 0), pn_bar: Number(t?.pn_bar ?? 16), longueur_m: Number(t?.longueur_m ?? 0)
    })) : [];
    if (this.formTroncons.length && o.type === 'CONDUITE') {
      delete this.formCharacteristics['troncons'];
    }
    this.showDialog = true;
  }

  addTroncon(): void {
    this.formTroncons = [...this.formTroncons, { de_mm: 110, pn_bar: 16, longueur_m: 0 }];
  }

  removeTroncon(idx: number): void {
    this.formTroncons = this.formTroncons.filter((_, i) => i !== idx);
  }

  save(): void {
    if (!this.projectId || !this.formName.trim()) { return; }
    this.saving.set(true);
    const chars: Record<string, any> = { ...this.formCharacteristics };
    Object.keys(chars).forEach(k => {
      if (chars[k] === null || chars[k] === undefined || chars[k] === '') { delete chars[k]; }
    });
    if (this.formType === 'CONDUITE' && this.formTroncons.length) {
      chars['troncons'] = this.formTroncons;
    }

    const done = () => {
      this.saving.set(false);
      this.showDialog = false;
      this.load();
      this.changed.emit();
    };
    const fail = (err: any) => {
      this.saving.set(false);
      this.msgs.add({ severity: 'error', summary: 'Ouvrage non enregistré',
        detail: err?.error?.message ?? 'Enregistrement impossible' });
    };

    if (this.editMode && this.editId) {
      const body: OuvrageUpdate = {
        name: this.formName.trim(), code: this.formCode.trim() || null, characteristics: chars,
      };
      this.projectService.updateOuvrage(this.projectId, this.editId, body)
        .subscribe({ next: () => {
            this.msgs.add({ severity: 'success', summary: 'Ouvrage modifié' }); done();
          }, error: fail });
    } else {
      const body: OuvrageCreate = {
        type: this.formType, name: this.formName.trim(),
        code: this.formCode.trim() || null, characteristics: chars,
      };
      this.projectService.createOuvrage(this.projectId, body)
        .subscribe({ next: () => {
            this.msgs.add({ severity: 'success', summary: 'Ouvrage créé' }); done();
          }, error: fail });
    }
  }

  confirmDelete(o: Ouvrage): void {
    this.deleteTarget = o;
    this.showDeleteDialog = true;
  }

  doDelete(): void {
    if (!this.projectId || !this.deleteTarget) { return; }
    this.projectService.deleteOuvrage(this.projectId, this.deleteTarget.id).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Ouvrage supprimé' });
        this.showDeleteDialog = false;
        this.load();
        this.changed.emit();
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Suppression impossible',
        detail: err?.error?.message ?? 'Suppression impossible' })
    });
  }

  canDelete(o: Ouvrage): boolean { return o.type !== 'COMMUN'; }
  hasCharacteristics(o: Ouvrage): boolean {
    const c = o.characteristics;
    if (!c || typeof c !== 'object') { return false; }
    return Object.keys(c).some(k => {
      const v = c[k];
      return v !== null && v !== undefined && v !== '' &&
             !(Array.isArray(v) && v.length === 0);
    });
  }
  formatCharacteristic(spec: { kind: string; unit?: string; choices?: string[] }, val: any): string {
    if (val === null || val === undefined || val === '') { return '—'; }
    if (spec.kind === 'BOOLEAN') { return val ? 'Oui' : 'Non'; }
    if (spec.kind === 'NUMBER_WITH_UNIT' || spec.kind === 'INTEGER') {
      const n = Number(val);
      const num = isNaN(n) ? String(val) : (spec.kind === 'INTEGER' ? String(Math.round(n)) : String(n));
      return spec.unit ? `${num} ${spec.unit}` : num;
    }
    return String(val);
  }
}
