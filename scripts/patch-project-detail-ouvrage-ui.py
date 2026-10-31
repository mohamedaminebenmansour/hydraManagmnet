#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Patch project-detail.component.{ts,html,scss} to add an Ouvrages tab.

Idempotent: refuses to re-patch if the sentinel is present.
"""
from pathlib import Path
import sys

ROOT = Path('/tmp/my-project')
TS = ROOT / 'frontend/src/app/features/projects/project-detail.component.ts'
HTML = ROOT / 'frontend/src/app/features/projects/project-detail.component.html'
SCSS = ROOT / 'frontend/src/app/features/projects/project-detail.component.scss'
SENTINEL = '// PHASE-OUVRAGE-UI'

def read(p): return p.read_text(encoding='utf-8')
def write(p, s): p.write_text(s, encoding='utf-8')

# ============================================================================
# 1) project-detail.component.ts — add imports + ouvrage state + CRUD methods
# ============================================================================
def patch_ts():
    src = read(TS)
    if SENTINEL in src:
        print("[SKIP] project-detail.component.ts already patched")
        return

    # 1a) imports: add InputNumberModule, ToggleSwitchModule + ouvrage model.
    old_imp = "import { ToggleSwitchModule } from 'primeng/toggleswitch';"
    new_imp = ("import { ToggleSwitchModule } from 'primeng/toggleswitch';\n"
               "import { InputNumberModule } from 'primeng/inputnumber'; // PHASE-OUVRAGE-UI")
    assert old_imp in src, "ToggleSwitchModule import not found"
    src = src.replace(old_imp, new_imp, 1)

    old_proj_imp = "} from '@app/core/models/project.model';"
    new_proj_imp = ("} from '@app/core/models/project.model';\n"
                    "import {\n"
                    "  Ouvrage, OuvrageType, OuvrageCreate, OuvrageUpdate, Troncon,\n"
                    "  OUVRAGE_TYPE_LABELS, ouvrageTypeVisual, ouvrageTypeLabel,\n"
                    "  ouvrageCharacteristics\n"
                    "} from '@app/core/models/ouvrage.model'; // PHASE-OUVRAGE-UI")
    assert old_proj_imp in src, "project.model import tail not found"
    src = src.replace(old_proj_imp, new_proj_imp, 1)

    # 1b) imports array: add InputNumberModule.
    old_arr = "    ToggleSwitchModule,\n    TabsModule,"
    new_arr = "    ToggleSwitchModule,\n    InputNumberModule,\n    TabsModule,"
    assert old_arr in src, "imports array anchor not found"
    src = src.replace(old_arr, new_arr, 1)

    # 1c) Add ouvrage state + methods. Insert before `canManage = computed`.
    old_state = "  canManage = computed(() => this.auth.can(PERM.PROJECT_ASSIGN) || this.auth.can(PERM.PROJECT_WRITE));"
    ouvrage_state = """  // ── PHASE-OUVRAGE-UI — Ouvrages tab state ───────────────────────────
  ouvrages = signal<Ouvrage[]>([]);
  ouvragesLoading = signal(false);

  /** The 6 ouvrage types the user can add (COMMUN is auto). */
  readonly ADDABLE_OUVRAGE_TYPES: OuvrageType[] =
    ['RESERVOIR', 'CONDUITE', 'STATION_POMPAGE', 'FORAGE', 'OUVRAGE_ANNEXE', 'AUTRE'];

  ouvrageTypeLabel = ouvrageTypeLabel;
  ouvrageTypeVisual = ouvrageTypeVisual;
  ouvrageCharacteristics = ouvrageCharacteristics;

  // Add / edit / delete ouvrage dialogs
  showOuvrageDialog = false;
  ouvrageEditMode = false;
  ouvrageEditId: string | null = null;
  ouvrageFormType: OuvrageType = 'RESERVOIR';
  ouvrageFormName = '';
  ouvrageFormCode = '';
  ouvrageFormCharacteristics: Record<string, any> = {};
  ouvrageFormTroncons: Troncon[] = [];
  ouvrageSaving = signal(false);

  // Delete ouvrage dialog
  showOuvrageDeleteDialog = false;
  ouvrageDeleteTarget: Ouvrage | null = null;

  /** Stats now include ouvrages so the hero strip shows the count. */
  stats = computed(() => ({
    folders: this.folders().length,
    members: this.members().length,
    processes: this.processes().length,
    ouvrages: this.ouvrages().length
  }));

  canManage = computed(() => this.auth.can(PERM.PROJECT_ASSIGN) || this.auth.can(PERM.PROJECT_WRITE));"""
    assert old_state in src, "canManage computed not found"
    src = src.replace(old_state, ouvrage_state, 1)

    # 1d) ngOnInit: load ouvrages after folders + processes.
    old_ngoninit_tail = "    this.loadProcesses();\n    this.loadProjectCash();\n  }"
    new_ngoninit_tail = "    this.loadProcesses();\n    this.loadProjectCash();\n    this.loadOuvrages(); // PHASE-OUVRAGE-UI\n  }"
    assert old_ngoninit_tail in src, "ngOnInit tail not found"
    src = src.replace(old_ngoninit_tail, new_ngoninit_tail, 1)

    # 1e) Add the ouvrage CRUD methods. Insert before the closing brace of the class.
    #     Find a stable anchor — `viewProjectPot()` is the last method.
    old_viewpot = """  viewProjectPot(): void {
    const pot = this.projectPot();
    if (!pot) return;
    this.router.navigate(['/tresorerie/caisses', pot.id]);
  }
}"""
    new_methods = """  viewProjectPot(): void {
    const pot = this.projectPot();
    if (!pot) return;
    this.router.navigate(['/tresorerie/caisses', pot.id]);
  }

  // ───────────────────────────────────────────────────────────────────
  //  OUVRAGES — PHASE-OUVRAGE-UI
  // ───────────────────────────────────────────────────────────────────

  loadOuvrages(): void {
    if (!this.projectId) { return; }
    this.ouvragesLoading.set(true);
    this.projectService.listOuvrages(this.projectId).subscribe({
      next: list => { this.ouvrages.set(list ?? []); this.ouvragesLoading.set(false); },
      error: () => { this.ouvrages.set([]); this.ouvragesLoading.set(false); }
    });
  }

  openAddOuvrage(type: OuvrageType): void {
    this.ouvrageEditMode = false;
    this.ouvrageEditId = null;
    this.ouvrageFormType = type;
    this.ouvrageFormName = OUVRAGE_TYPE_LABELS[type];
    this.ouvrageFormCode = '';
    this.ouvrageFormCharacteristics = {};
    this.ouvrageFormTroncons = type === 'CONDUITE' ? [] : [];
    this.showOuvrageDialog = true;
  }

  openEditOuvrage(o: Ouvrage): void {
    this.ouvrageEditMode = true;
    this.ouvrageEditId = o.id;
    this.ouvrageFormType = o.type;
    this.ouvrageFormName = o.name;
    this.ouvrageFormCode = o.code ?? '';
    this.ouvrageFormCharacteristics = { ...(o.characteristics ?? {}) };
    // Pull troncons out of characteristics into the dedicated array for the form.
    const tr = (o.characteristics ?? {})['troncons'];
    this.ouvrageFormTroncons = Array.isArray(tr) ? tr.map((t: any) => ({
      de_mm: Number(t?.de_mm ?? 0), pn_bar: Number(t?.pn_bar ?? 16), longueur_m: Number(t?.longueur_m ?? 0)
    })) : [];
    if (this.ouvrageFormTroncons.length && o.type === 'CONDUITE') {
      delete this.ouvrageFormCharacteristics['troncons'];
    }
    this.showOuvrageDialog = true;
  }

  addTronconInDialog(): void {
    this.ouvrageFormTroncons = [...this.ouvrageFormTroncons, { de_mm: 110, pn_bar: 16, longueur_m: 0 }];
  }

  removeTronconInDialog(idx: number): void {
    this.ouvrageFormTroncons = this.ouvrageFormTroncons.filter((_, i) => i !== idx);
  }

  saveOuvrage(): void {
    if (!this.projectId || !this.ouvrageFormName.trim()) { return; }
    this.ouvrageSaving.set(true);
    const chars: Record<string, any> = { ...this.ouvrageFormCharacteristics };
    // Drop nulls/empties (the backend rejects unknown keys, not empty values,
    // but cleaner to send only what the user filled).
    Object.keys(chars).forEach(k => {
      if (chars[k] === null || chars[k] === undefined || chars[k] === '') { delete chars[k]; }
    });
    if (this.ouvrageFormType === 'CONDUITE' && this.ouvrageFormTroncons.length) {
      chars['troncons'] = this.ouvrageFormTroncons;
    }

    const done = () => {
      this.ouvragesSaving_reset();
      this.showOuvrageDialog = false;
      this.loadOuvrages();
      // Also refresh the project so ouvrageSummary updates on the hero strip.
      if (this.projectId) {
        this.projectService.get(this.projectId).subscribe(p => this.project.set(p));
      }
    };
    const fail = (err: any) => {
      this.ouvrageSaving.set(false);
      this.msgs.add({ severity: 'error', summary: 'Ouvrage non enregistré',
        detail: err?.error?.message ?? 'Enregistrement impossible' });
    };

    if (this.ouvrageEditMode && this.ouvrageEditId) {
      const body: OuvrageUpdate = {
        name: this.ouvrageFormName.trim(),
        code: this.ouvrageFormCode.trim() || null,
        characteristics: chars,
      };
      this.projectService.updateOuvrage(this.projectId, this.ouvrageEditId, body)
        .subscribe({ next: () => {
            this.msgs.add({ severity: 'success', summary: 'Ouvrage modifié' });
            done();
          }, error: fail });
    } else {
      const body: OuvrageCreate = {
        type: this.ouvrageFormType,
        name: this.ouvrageFormName.trim(),
        code: this.ouvrageFormCode.trim() || null,
        characteristics: chars,
      };
      this.projectService.createOuvrage(this.projectId, body)
        .subscribe({ next: () => {
            this.msgs.add({ severity: 'success', summary: 'Ouvrage créé' });
            done();
          }, error: fail });
    }
  }

  private ouvragesSaving_reset(): void { this.ouvrageSaving.set(false); }

  confirmDeleteOuvrage(o: Ouvrage): void {
    this.ouvrageDeleteTarget = o;
    this.showOuvrageDeleteDialog = true;
  }

  doDeleteOuvrage(): void {
    if (!this.projectId || !this.ouvrageDeleteTarget) { return; }
    this.projectService.deleteOuvrage(this.projectId, this.ouvrageDeleteTarget.id).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Ouvrage supprimé' });
        this.showOuvrageDeleteDialog = false;
        this.loadOuvrages();
        if (this.projectId) {
          this.projectService.get(this.projectId).subscribe(p => this.project.set(p));
        }
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Suppression impossible',
        detail: err?.error?.message ?? 'Suppression impossible' })
    });
  }

  /** Don't allow deleting the auto-created COMMUN ouvrage (UI mirrors backend). */
  canDeleteOuvrage(o: Ouvrage): boolean { return o.type !== 'COMMUN'; }
}"""
    assert old_viewpot in src, "viewProjectPot tail not found"
    src = src.replace(old_viewpot, new_methods, 1)

    write(TS, src)
    print("[OK] project-detail.component.ts patched")

# ============================================================================
# 2) project-detail.component.html — add Ouvrages tab + dialogs
# ============================================================================
def patch_html():
    src = read(HTML)
    if SENTINEL in src:
        print("[SKIP] project-detail.component.html already patched")
        return

    # 2a) Hero stats: add an Ouvrages tile (4th stat) — but the hero currently
    #     has 3 stats (Dossiers, Équipe, Processus). We add a 4th Ouvrages tile.
    old_hero_stats = """        <button type="button" class="hstat" (click)="activeTab.set('2')">
          <i class="pi pi-sitemap"></i>
          <span class="hs-n">{{ stats().processes }}</span>
          <span class="hs-l">Processus</span>
        </button>
      </div>"""
    new_hero_stats = """        <button type="button" class="hstat" (click)="activeTab.set('2')">
          <i class="pi pi-sitemap"></i>
          <span class="hs-n">{{ stats().processes }}</span>
          <span class="hs-l">Processus</span>
        </button>
        <button type="button" class="hstat" (click)="activeTab.set('3')">
          <i class="pi pi-database"></i>
          <span class="hs-n">{{ stats().ouvrages }}</span>
          <span class="hs-l">Ouvrages</span>
        </button>
      </div>"""
    assert old_hero_stats in src, "hero stats block not found"
    src = src.replace(old_hero_stats, new_hero_stats, 1)

    # 2b) Tab list: shift Processus from value="2" to value="2" (keep), and add
    #     a new "Ouvrages" tab with value="3". The Processus tabpanel keeps value="2".
    old_tablist = """    <p-tablist>
      <p-tab value="0"><i class="pi pi-folder"></i> Dossiers</p-tab>
      <p-tab value="1"><i class="pi pi-users"></i> Équipe</p-tab>
      <p-tab value="2"><i class="pi pi-sitemap"></i> Processus</p-tab>
    </p-tablist>"""
    new_tablist = """    <p-tablist>
      <p-tab value="0"><i class="pi pi-folder"></i> Dossiers</p-tab>
      <p-tab value="3"><i class="pi pi-database"></i> Ouvrages</p-tab>
      <p-tab value="1"><i class="pi pi-users"></i> Équipe</p-tab>
      <p-tab value="2"><i class="pi pi-sitemap"></i> Processus</p-tab>
    </p-tablist>"""
    assert old_tablist in src, "tablist block not found"
    src = src.replace(old_tablist, new_tablist, 1)

    # 2c) Insert the Ouvrages tabpanel right after the Dossiers tabpanel closes
    #     (`</p-tabpanel>` for value="0") and before the Équipe tabpanel.
    old_team_panel = """      <!-- ==================== ÉQUIPE ==================== -->
      <p-tabpanel value="1">"""
    new_team_panel = """      <!-- ==================== OUVRAGES (PHASE-OUVRAGE-UI) ==================== -->
      <p-tabpanel value="3">
        <div class="panel">
          <p class="note">
            <i class="pi pi-info-circle"></i>
            Les ouvrages structurent le chantier (réservoir, conduite, station…).
            L'ouvrage « Commun » (installation, repli, essais) est créé automatiquement.
          </p>

          <div class="ouvrage-add-btns">
            @for (t of ADDABLE_OUVRAGE_TYPES; track t) {
              <button type="button" class="ouvrage-add-btn"
                      [style.--c]="ouvrageTypeVisual(t).color"
                      [style.--s]="ouvrageTypeVisual(t).soft"
                      (click)="openAddOuvrage(t)"
                      [attr.aria-label]="'Ajouter ' + ouvrageTypeLabel(t)">
                <i class="pi pi-plus"></i>
                <i class="pi" [class]="ouvrageTypeVisual(t).icon"></i>
                <span>{{ ouvrageTypeLabel(t) }}</span>
              </button>
            }
          </div>

          @if (ouvragesLoading()) {
            <div class="proc-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
          } @else if (!ouvrages().length) {
            <app-empty-state icon="pi pi-database" message="Aucun ouvrage pour le moment."></app-empty-state>
          } @else {
            <div class="ouvrage-grid">
              @for (o of ouvrages(); track o.id) {
                <div class="ouvrage-card" [style.--c]="ouvrageTypeVisual(o.type).color"
                                          [style.--s]="ouvrageTypeVisual(o.type).soft">
                  <div class="oc-head">
                    <span class="oc-disc"><i class="pi" [class]="ouvrageTypeVisual(o.type).icon"></i></span>
                    <div class="oc-info">
                      <div class="oc-name">{{ o.name }}</div>
                      <div class="oc-type">{{ ouvrageTypeLabel(o.type) }}@if (o.code) { · {{ o.code }} }</div>
                    </div>
                    <div class="oc-actions">
                      <p-button icon="pi pi-pencil" [text]="true" size="small"
                                (onClick)="openEditOuvrage(o)"
                                [attr.aria-label]="'Modifier ' + o.name" />
                      @if (canDeleteOuvrage(o)) {
                        <p-button icon="pi pi-trash" [text]="true" size="small" severity="danger"
                                  (onClick)="confirmDeleteOuvrage(o)"
                                  [attr.aria-label]="'Supprimer ' + o.name" />
                      }
                    </div>
                  </div>
                  @if (o.characteristics && Object.keys(o.characteristics).length) {
                    <div class="oc-chips">
                      @for (entry of ouvrageCharacteristics(o.type); track entry.key) {
                        @if (o.characteristics[entry.key] !== undefined && o.characteristics[entry.key] !== null && o.characteristics[entry.key] !== '') {
                          <span class="oc-chip">
                            <span class="oc-chip-k">{{ entry.spec.label }}</span>
                            <span class="oc-chip-v">{{ formatCharacteristic(entry.spec, o.characteristics[entry.key]) }}</span>
                          </span>
                        }
                      }
                      @if (o.characteristics['troncons']?.length) {
                        <span class="oc-chip">
                          <span class="oc-chip-k">Tronçons</span>
                          <span class="oc-chip-v">{{ o.characteristics['troncons'].length }}</span>
                        </span>
                      }
                    </div>
                  } @else {
                    <small class="muted">Aucune caractéristique renseignée.</small>
                  }
                </div>
              }
            </div>
          }
        </div>
      </p-tabpanel>

      <!-- ==================== ÉQUIPE ==================== -->
      <p-tabpanel value="1">"""
    assert old_team_panel in src, "Équipe tabpanel anchor not found"
    src = src.replace(old_team_panel, new_team_panel, 1)

    # 2d) Add the Ouvrage add/edit dialog + delete dialog before the closing
    #     `}` of the @if (project()) block. The folder-access dialog is the last
    #     one; we insert after it.
    old_folder_dialog_close = """  @if (accessFolder; as f) {
    <app-folder-access-dialog
      [(visible)]="showAccessDialog"
      [projectId]="projectId ?? ''"
      [folder]="f"
      (saved)="onAccessSaved()">
    </app-folder-access-dialog>
  }
}"""
    new_dialogs = """  @if (accessFolder; as f) {
    <app-folder-access-dialog
      [(visible)]="showAccessDialog"
      [projectId]="projectId ?? ''"
      [folder]="f"
      (saved)="onAccessSaved()">
    </app-folder-access-dialog>
  }

  <!-- ==================== DIALOGUE OUVRAGE (PHASE-OUVRAGE-UI) ==================== -->
  <p-dialog [(visible)]="showOuvrageDialog" [modal]="true" [style]="{ width: '600px' }"
            [header]="ouvrageEditMode ? 'Modifier l\\'ouvrage' : 'Nouvel ouvrage'"
            [draggable]="false">
    <div class="dlg-form">
      <div class="oc-head dlg-oc-head">
        <span class="oc-disc" [style.--c]="ouvrageTypeVisual(ouvrageFormType).color"
              [style.background]="ouvrageTypeVisual(ouvrageFormType).soft"
              [style.color]="ouvrageTypeVisual(ouvrageFormType).color">
          <i class="pi" [class]="ouvrageTypeVisual(ouvrageFormType).icon"></i>
        </span>
        <div class="dlg-oc-type">{{ ouvrageTypeLabel(ouvrageFormType) }}</div>
      </div>
      <div class="two">
        <div class="field">
          <label>Nom *</label>
          <input pInputText class="inp" [(ngModel)]="ouvrageFormName" placeholder="Nom de l'ouvrage" />
        </div>
        <div class="field">
          <label>Code</label>
          <input pInputText class="inp" [(ngModel)]="ouvrageFormCode" placeholder="Ex. : RES-01" />
        </div>
      </div>
      @for (entry of ouvrageCharacteristics(ouvrageFormType); track entry.key) {
        <div class="field">
          <label>{{ entry.spec.label }}</label>
          @switch (entry.spec.kind) {
            @case ('CHOICE') {
              <p-select [options]="entry.spec.choices!"
                        [(ngModel)]="ouvrageFormCharacteristics[entry.key]"
                        [showClear]="true" placeholder="—"
                        styleClass="w100" />
            }
            @case ('BOOLEAN') {
              <div class="oc-switch">
                <p-toggleswitch [(ngModel)]="ouvrageFormCharacteristics[entry.key]" />
                <span>{{ ouvrageFormCharacteristics[entry.key] ? 'Oui' : 'Non' }}</span>
              </div>
            }
            @case ('INTEGER') {
              <p-inputNumber [(ngModel)]="ouvrageFormCharacteristics[entry.key]"
                             [min]="0" mode="decimal" [showButtons]="true"
                             [suffix]="entry.spec.unit ? ' ' + entry.spec.unit : ''"
                             styleClass="w100" />
            }
            @case ('NUMBER_WITH_UNIT') {
              <p-inputNumber [(ngModel)]="ouvrageFormCharacteristics[entry.key]"
                             [min]="0" mode="decimal" [minFractionDigits]="0" [maxFractionDigits]="3"
                             [suffix]="entry.spec.unit ? ' ' + entry.spec.unit : ''"
                             styleClass="w100" />
            }
            @case ('TEXT') {
              <input pInputText class="inp" [(ngModel)]="ouvrageFormCharacteristics[entry.key]"
                     placeholder="{{ entry.spec.label }}" />
            }
            @case ('TRONCONS_ARRAY') {
              <div class="oc-troncons">
                @for (tr of ouvrageFormTroncons; track $index) {
                  <div class="oc-troncon">
                    <p-inputNumber [(ngModel)]="tr.de_mm" [min]="0" mode="decimal"
                                   suffix=" mm DE" styleClass="oc-tr-inp" />
                    <p-select [options]="['10','16']" [(ngModel)]="tr.pn_bar" styleClass="oc-tr-pn" />
                    <p-inputNumber [(ngModel)]="tr.longueur_m" [min]="0" mode="decimal"
                                   [minFractionDigits]="0" [maxFractionDigits]="3"
                                   suffix=" m" styleClass="oc-tr-inp" />
                    <button type="button" class="oc-tr-rm" (click)="removeTronconInDialog($index)"
                            [attr.aria-label]="'Retirer ce tronçon'">
                      <i class="pi pi-times"></i>
                    </button>
                  </div>
                } @empty {
                  <small class="muted">Aucun tronçon. Ajoutez-en un par diamètre.</small>
                }
                <button type="button" class="oc-tr-add" (click)="addTronconInDialog()">
                  <i class="pi pi-plus"></i> Ajouter un tronçon
                </button>
              </div>
            }
          }
        </div>
      }
      @if (!ouvrageCharacteristics(ouvrageFormType).length) {
        <small class="muted">Aucune caractéristique requise pour ce type.</small>
      }
    </div>
    <ng-template pTemplate="footer">
      <p-button label="Annuler" [text]="true" (onClick)="showOuvrageDialog = false" [disabled]="ouvrageSaving()" />
      <p-button [label]="ouvrageEditMode ? 'Enregistrer' : 'Créer l\\'ouvrage'"
                icon="pi pi-check" (onClick)="saveOuvrage()"
                [loading]="ouvrageSaving()" [disabled]="!ouvrageFormName.trim()" />
    </ng-template>
  </p-dialog>

  <p-dialog [(visible)]="showOuvrageDeleteDialog" [modal]="true" [style]="{ width: '440px' }"
            header="Supprimer l'ouvrage">
    <p>Supprimer <strong>{{ ouvrageDeleteTarget?.name }}</strong> ?</p>
    @if (ouvrageDeleteTarget && !canDeleteOuvrage(ouvrageDeleteTarget)) {
      <p class="muted">L'ouvrage « Commun » est obligatoire et ne peut pas être supprimé.</p>
    }
    <ng-template pTemplate="footer">
      <p-button label="Annuler" [text]="true" (onClick)="showOuvrageDeleteDialog = false" />
      <p-button label="Supprimer" icon="pi pi-trash" severity="danger"
                (onClick)="doDeleteOuvrage()"
                [disabled]="!ouvrageDeleteTarget || !canDeleteOuvrage(ouvrageDeleteTarget)" />
    </ng-template>
  </p-dialog>
}"""
    assert old_folder_dialog_close in src, "folder-access dialog close anchor not found"
    src = src.replace(old_folder_dialog_close, new_dialogs, 1)

    write(HTML, src)
    print("[OK] project-detail.component.html patched")

# ============================================================================
# 3) project-detail.component.scss — add Ouvrages tab styles
# ============================================================================
def patch_scss():
    src = read(SCSS)
    if SENTINEL in src:
        print("[SKIP] project-detail.component.scss already patched")
        return
    styles = """

// ── PHASE-OUVRAGE-UI — Ouvrages tab styles ─────────────────────────────────
.ouvrage-add-btns {
  display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 14px;
}
.ouvrage-add-btn {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 14px; border-radius: 999px;
  border: 1px solid var(--c, #cbd5e1);
  background: var(--s, #f1f5f9);
  color: var(--c, #475569);
  font-size: 13px; font-weight: 600; cursor: pointer;
  transition: transform .12s ease, box-shadow .12s ease;
  &:hover { transform: translateY(-1px); box-shadow: 0 2px 6px rgba(0,0,0,.08); }
  i.pi-plus { font-size: 11px; opacity: .8; }
}
.ouvrage-grid {
  display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 12px;
}
.ouvrage-card {
  border: 1px solid var(--c, #e2e8f0);
  border-left: 4px solid var(--c, #94a3b8);
  border-radius: 10px;
  background: #fff;
  padding: 12px 14px;
}
.oc-head { display: flex; align-items: center; gap: 10px; }
.oc-disc {
  width: 36px; height: 36px; border-radius: 8px;
  background: var(--s, #f1f5f9); color: var(--c, #475569);
  display: inline-grid; place-items: center;
  i { font-size: 18px; }
}
.oc-info { flex: 1; min-width: 0; }
.oc-name { font-size: 15px; font-weight: 700; color: #1e293b; }
.oc-type { font-size: 12px; color: #64748b; }
.oc-actions { display: flex; gap: 2px; }
.oc-chips {
  display: flex; flex-wrap: wrap; gap: 6px; margin-top: 10px;
}
.oc-chip {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 12px; padding: 2px 8px; border-radius: 999px;
  background: var(--s, #f8fafc); color: var(--c, #475569);
}
.oc-chip-k { font-weight: 700; opacity: .7; }
.oc-chip-v { font-weight: 600; }

.dlg-oc-head {
  margin-bottom: 6px;
  .dlg-oc-type { font-size: 16px; font-weight: 700; color: var(--c, #475569); }
}
.oc-troncons {
  display: flex; flex-direction: column; gap: 6px;
  background: #f8fafc; border: 1px dashed #cbd5e1; border-radius: 6px; padding: 8px;
}
.oc-troncon { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.oc-tr-inp { width: 130px; }
.oc-tr-pn { width: 90px; }
.oc-tr-rm {
  width: 24px; height: 24px; border-radius: 50%;
  border: none; background: transparent; color: #94a3b8; cursor: pointer;
  &:hover { background: #fee2e2; color: #dc2626; }
}
.oc-tr-add {
  align-self: flex-start;
  background: transparent; border: 1px dashed var(--c, #94a3b8);
  color: var(--c, #475569); padding: 4px 10px; border-radius: 6px;
  font-size: 12px; font-weight: 600; cursor: pointer;
  &:hover { background: #f8fafc; }
}
.oc-switch {
  display: inline-flex; align-items: center; gap: 8px;
  span { font-size: 13px; color: #475569; }
}
.muted { color: #94a3b8; font-size: 12px; }
"""
    write(SCSS, src + styles)
    print("[OK] project-detail.component.scss patched")

def main():
    patch_ts()
    patch_html()
    patch_scss()
    print("\nAll project-detail.component patches applied.")

if __name__ == '__main__':
    main()
