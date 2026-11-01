import { Component, Input, Output, EventEmitter, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { SelectModule } from 'primeng/select';
import { DialogModule } from 'primeng/dialog';
import { TooltipModule } from 'primeng/tooltip';
import { TagModule } from 'primeng/tag';
import { MessageService } from 'primeng/api';
import { BordereauService } from '@app/core/services/bordereau.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import {
  BordereauVersion, BordereauLine, ImportPreview, BordereauDiff, lineMontant
} from '@app/core/models/bordereau.model';
import { ProjectService } from '@app/core/services/project.service';
import { Ouvrage } from '@app/core/models/ouvrage.model';
import { EmptyStateComponent } from '@app/shared/empty-state.component';

@Component({
  selector: 'app-bordereau-tab',
  standalone: true,
  imports: [
    CommonModule, FormsModule, ButtonModule, SelectModule, DialogModule, TooltipModule, TagModule,
    EmptyStateComponent,
  ],
  templateUrl: './bordereau-tab.component.html',
  styleUrl: './bordereau-tab.component.scss'
})
export class BordereauTabComponent implements OnInit {
  private bordereauService = inject(BordereauService);
  private projectService = inject(ProjectService);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  @Input({ required: true }) projectId!: string;
  @Output() changed = new EventEmitter<void>();

  versions = signal<BordereauVersion[]>([]);
  selectedVersionId = signal<string | null>(null);
  lines = signal<BordereauLine[]>([]);
  loading = signal(false);
  canWrite = computed(() => this.auth.can(PERM.PROJECT_WRITE));

  // Inline editing state.
  editingLineId: string | null = null;
  editingField: string | null = null;
  editValue: string = '';

  // Import.
  importLoading = signal(false);
  showImportPreview = false;
  importPreview: ImportPreview | null = null;

  // Compare.
  showCompareDialog = false;
  compareOldId: string | null = null;
  compareNewId: string | null = null;
  diffs = signal<BordereauDiff[]>([]);
  compareLoading = signal(false);

  // New version.
  showNewVersionDialog = false;
  newVersionLabel = '';
  newVersionReason = '';

  activeVersion = computed(() => this.versions().find(v => v.status === 'ACTIVE'));

  // Ouvrage assignment (DRAFT only).
  ouvrages = signal<Ouvrage[]>([]);
  ouvrageOptions = computed(() => [
    { id: null as string | null, label: 'Aucun' },
    ...this.ouvrages().map(o => ({ id: o.id as string | null, label: o.name })),
  ]);
  selectedIds = signal<Set<string>>(new Set());
  showAssignDialog = false;
  assignOuvrageId: string | null = null;
  assigning = signal(false);
  /** Article lines (with a number) that have no ouvrage yet. */
  linesWithoutOuvrage = computed(() => this.lines().filter(l => !!l.articleNo?.trim() && !l.ouvrageId).length);

  // Lines grouped by sectionPath.
  groupedLines = computed(() => {
    const groups: { section: string; lines: BordereauLine[] }[] = [];
    let currentSection = '';
    for (const l of this.lines()) {
      const section = l.sectionPath.join(' / ') || '—';
      if (section !== currentSection) { groups.push({ section, lines: [] }); currentSection = section; }
      groups[groups.length - 1].lines.push(l);
    }
    return groups;
  });

  // Paired line lookup: map lineId → line (for the link icon tooltip).
  pairedLineMap = computed(() => {
    const m = new Map<string, BordereauLine>();
    for (const l of this.lines()) { m.set(l.id, l); }
    return m;
  });

  ngOnInit(): void {
    this.loadVersions();
    this.projectService.listOuvrages(this.projectId).subscribe({
      next: list => this.ouvrages.set(list ?? []),
      error: () => this.ouvrages.set([])
    });
  }

  loadVersions(): void {
    this.loading.set(true);
    this.bordereauService.listVersions(this.projectId).subscribe({
      next: list => {
        this.versions.set(list ?? []);
        const active = list?.find(v => v.status === 'ACTIVE');
        const latest = list?.[0];
        const id = active?.id ?? latest?.id ?? null;
        if (id) this.selectVersion(id);
        this.loading.set(false);
      },
      error: () => { this.versions.set([]); this.loading.set(false); }
    });
  }

  selectVersion(versionId: string): void {
    this.selectedVersionId.set(versionId);
    this.cancelEdit();
    this.selectedIds.set(new Set());
    this.bordereauService.listLines(this.projectId, versionId).subscribe({
      next: list => this.lines.set(list ?? []),
      error: () => this.lines.set([])
    });
  }

  isDraft(): boolean {
    return this.versions().find(v => v.id === this.selectedVersionId())?.status === 'DRAFT';
  }

  // ── Inline editing ──────────────────────────────────────────────

  startEdit(line: BordereauLine, field: string): void {
    if (!this.isDraft() || !this.canWrite()) return;
    this.editingLineId = line.id;
    this.editingField = field;
    const val = (line as any)[field];
    this.editValue = val != null ? String(val) : '';
  }

  isEditing(lineId: string, field: string): boolean {
    return this.editingLineId === lineId && this.editingField === field;
  }

  saveEdit(line: BordereauLine, field: string, moveNext: 'down' | 'tab' | null = null): void {
    if (!this.editingLineId) return;
    const versionId = this.selectedVersionId();
    if (!versionId) return;

    // Parse the edit value based on the field type.
    const body: any = {};
    if (field === 'quantity' || field === 'unitPrice') {
      const num = parseFloat(this.editValue.replace(/\s/g, '').replace(',', '.'));
      body[field] = isNaN(num) ? null : num;
    } else {
      body[field] = this.editValue.trim() || null;
    }

    this.bordereauService.updateLine(this.projectId, versionId, line.id, body).subscribe({
      next: (updated) => {
        // Update the line in the signal.
        this.lines.update(list => list.map(l => l.id === updated.id ? updated : l));
        // A new article number can add/remove the duplicate warning on OTHER lines: reload them.
        if (field === 'articleNo') {
          this.bordereauService.listLines(this.projectId, versionId).subscribe(list => this.lines.set(list ?? []));
        }
        // If the warning dot disappeared, show a toast.
        if ((line.importWarnings?.length ?? 0) > 0 && (updated.importWarnings?.length ?? 0) === 0) {
          this.msgs.add({ severity: 'success', summary: 'Avertissement résolu', detail: 'La ligne ne comporte plus d\'avertissement.' });
        }
        this.editingLineId = null;
        this.editingField = null;

        // Handle navigation: Enter = move down in same column, Tab = next cell.
        if (moveNext === 'down') {
          const allLines = this.lines();
          const idx = allLines.findIndex(l => l.id === line.id);
          if (idx >= 0 && idx < allLines.length - 1) {
            const nextLine = allLines[idx + 1];
            setTimeout(() => this.startEdit(nextLine, field), 50);
          }
        } else if (moveNext === 'tab') {
          // Tab to next field on the same line.
          const fields = ['articleNo', 'designation', 'unitRaw', 'quantity', 'unitPrice'];
          const fi = fields.indexOf(field);
          if (fi >= 0 && fi < fields.length - 1) {
            setTimeout(() => this.startEdit(line, fields[fi + 1]), 50);
          }
        }
      },
      error: (err) => {
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err?.error?.message ?? 'Modification impossible' });
        this.editingLineId = null;
        this.editingField = null;
      }
    });
  }

  cancelEdit(): void {
    this.editingLineId = null;
    this.editingField = null;
  }

  onEditKeydown(event: KeyboardEvent, line: BordereauLine, field: string): void {
    if (event.key === 'Enter') { event.preventDefault(); this.saveEdit(line, field, 'down'); }
    else if (event.key === 'Tab') { event.preventDefault(); this.saveEdit(line, field, 'tab'); }
    else if (event.key === 'Escape') { event.preventDefault(); this.cancelEdit(); }
  }

  // ── Ouvrage assignment ──────────────────────────────────────────

  /** Read-only ouvrage name (ACTIVE / ARCHIVED versions); "—" when none. */
  ouvrageName(ouvrageId: string | null | undefined): string {
    if (!ouvrageId) return '—';
    return this.ouvrages().find(o => o.id === ouvrageId)?.name ?? '—';
  }

  isSelected(lineId: string): boolean { return this.selectedIds().has(lineId); }

  toggleSelected(lineId: string, checked: boolean): void {
    this.selectedIds.update(s => {
      const copy = new Set(s);
      if (checked) copy.add(lineId); else copy.delete(lineId);
      return copy;
    });
  }

  allSelected(group: { lines: BordereauLine[] }): boolean {
    return group.lines.length > 0 && group.lines.every(l => this.isSelected(l.id));
  }

  toggleGroup(group: { lines: BordereauLine[] }, checked: boolean): void {
    this.selectedIds.update(s => {
      const copy = new Set(s);
      for (const l of group.lines) { if (checked) copy.add(l.id); else copy.delete(l.id); }
      return copy;
    });
  }

  /** Row dropdown: one line, same bulk endpoint (it also allows "Aucun"). */
  setLineOuvrage(line: BordereauLine, ouvrageId: string | null): void {
    this.assign([line.id], ouvrageId);
  }

  openAssign(): void {
    this.assignOuvrageId = null;
    this.showAssignDialog = true;
  }

  confirmAssign(): void {
    this.assign([...this.selectedIds()], this.assignOuvrageId, () => {
      this.showAssignDialog = false;
      this.selectedIds.set(new Set());
    });
  }

  private assign(lineIds: string[], ouvrageId: string | null, done?: () => void): void {
    const versionId = this.selectedVersionId();
    if (!versionId || !lineIds.length) return;
    this.assigning.set(true);
    this.bordereauService.assignOuvrage(this.projectId, versionId, lineIds, ouvrageId).subscribe({
      next: updated => {
        const byId = new Map(updated.map(u => [u.id, u]));
        this.lines.update(list => list.map(l => byId.get(l.id) ?? l));
        this.assigning.set(false);
        if (lineIds.length > 1) {
          this.msgs.add({ severity: 'success', summary: 'Ouvrage affecté', detail: `${updated.length} ligne(s) mise(s) à jour.` });
        }
        done?.();
      },
      error: err => {
        this.assigning.set(false);
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err?.error?.message ?? 'Affectation impossible' });
      }
    });
  }

  // ── Import ──────────────────────────────────────────────────────

  onInputChange(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.importLoading.set(true);
    this.bordereauService.importExcel(this.projectId, file).subscribe({
      next: preview => {
        this.importPreview = preview;
        this.showImportPreview = true;
        this.importLoading.set(false);
        this.loadVersions();
        this.changed.emit();
      },
      error: err => {
        this.importLoading.set(false);
        this.msgs.add({ severity: 'error', summary: 'Import impossible', detail: err?.error?.message ?? 'Import impossible' });
      }
    });
    input.value = '';
  }

  confirmImport(): void {
    this.showImportPreview = false;
    this.importPreview = null;
    this.msgs.add({ severity: 'success', summary: 'Bordereau importé', detail: 'Version en brouillon créée.' });
  }

  // ── New version ─────────────────────────────────────────────────

  openNewVersion(): void {
    this.newVersionLabel = ''; this.newVersionReason = '';
    this.showNewVersionDialog = true;
  }

  createNewVersion(): void {
    if (!this.newVersionLabel.trim()) return;
    this.bordereauService.createVersion(this.projectId, {
      label: this.newVersionLabel.trim(), reason: this.newVersionReason.trim() || undefined,
    }).subscribe({
      next: v => {
        this.msgs.add({ severity: 'success', summary: 'Version créée', detail: v.label });
        this.showNewVersionDialog = false;
        this.loadVersions();
        this.changed.emit();
      },
      error: err => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err?.error?.message ?? 'Création impossible' })
    });
  }

  activateCurrent(): void {
    const id = this.selectedVersionId();
    if (!id) return;
    this.bordereauService.activateVersion(this.projectId, id).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Version activée' });
        this.loadVersions();
        this.changed.emit();
      },
      error: err => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err?.error?.message ?? 'Activation impossible' })
    });
  }

  // ── Compare ─────────────────────────────────────────────────────

  openCompare(): void {
    this.compareOldId = null; this.compareNewId = null;
    this.diffs.set([]); this.showCompareDialog = true;
  }

  runCompare(): void {
    if (!this.compareOldId || !this.compareNewId) return;
    this.compareLoading.set(true);
    this.bordereauService.compareVersions(this.projectId, this.compareOldId, this.compareNewId).subscribe({
      next: list => { this.diffs.set(list ?? []); this.compareLoading.set(false); },
      error: () => { this.diffs.set([]); this.compareLoading.set(false); }
    });
  }

  diffColor(c: string): string { return c === 'ADDED' ? '#16a34a' : c === 'REMOVED' ? '#dc2626' : '#ea580c'; }
  diffLabel(c: string): string {
    const m: Record<string, string> = { ADDED: 'Ajouté', REMOVED: 'Supprimé', QUANTITY_CHANGED: 'Quantité modifiée', PRICE_CHANGED: 'Prix modifié' };
    return m[c] ?? c;
  }

  // ── Display helpers ─────────────────────────────────────────────

  fmt(n: number | null | undefined): string {
    if (n == null) return '—';
    return new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 0, maximumFractionDigits: 3 }).format(n);
  }

  fmtMoney(n: number | null | undefined): string {
    if (n == null) return '—';
    return new Intl.NumberFormat('fr-FR', { minimumFractionDigits: 3, maximumFractionDigits: 3 }).format(n);
  }

  montant(l: BordereauLine): number | null { return lineMontant(l); }

  versionStatusLabel(s: string): string {
    return { ACTIVE: 'Active', DRAFT: 'Brouillon', ARCHIVED: 'Archivée' }[s] ?? s;
  }

  versionStatusSeverity(s: string): 'success' | 'warn' | 'secondary' {
    return s === 'ACTIVE' ? 'success' : s === 'DRAFT' ? 'warn' : 'secondary';
  }

  pairedLineNo(line: BordereauLine): string {
    if (!line.pairedLineId) return '';
    const paired = this.pairedLineMap().get(line.pairedLineId);
    return paired?.articleNo ?? paired?.designation ?? '—';
  }
}
