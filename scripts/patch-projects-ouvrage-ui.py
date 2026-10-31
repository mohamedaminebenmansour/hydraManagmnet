#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Patch projects.component.{ts,html,scss} to:
  - add the "Ouvrages" wizard step (after Identité, before Calendrier)
  - replace the type label with ouvrageSummary on cards + table
  - add a helper to format the wizard ouvrage cards + their dynamic forms

Idempotent: refuses to re-patch if the sentinel is present.
"""
from pathlib import Path
import sys

ROOT = Path('/tmp/my-project')
TS = ROOT / 'frontend/src/app/features/projects/projects.component.ts'
HTML = ROOT / 'frontend/src/app/features/projects/projects.component.html'
SCSS = ROOT / 'frontend/src/app/features/projects/projects.component.scss'
SENTINEL = '// PHASE-OUVRAGE-UI'

def read(p): return p.read_text(encoding='utf-8')
def write(p, s): p.write_text(s, encoding='utf-8')

# ============================================================================
# 1) projects.component.ts — add imports + wizard step + ouvrage draft state
# ============================================================================
def patch_ts():
    src = read(TS)
    if SENTINEL in src:
        print("[SKIP] projects.component.ts already patched")
        return

    # 1a) imports: add InputNumberModule (already there) + ToggleSwitchModule +
    #     the ouvrage model + the ProjectService ouvrage methods (already on the
    #     injected service, no new import needed for that).
    old_imp = "import { MultiSelectModule } from 'primeng/multiselect';"
    new_imp = ("import { MultiSelectModule } from 'primeng/multiselect';\n"
               "import { ToggleSwitchModule } from 'primeng/toggleswitch'; // PHASE-OUVRAGE-UI")
    assert old_imp in src, "MultiSelectModule import not found"
    src = src.replace(old_imp, new_imp, 1)

    # Add the ouvrage model import after the project model import.
    old_proj_imp = "} from '@app/core/models/project.model';"
    new_proj_imp = ("} from '@app/core/models/project.model';\n"
                    "import {\n"
                    "  OuvrageType, OuvrageSpec, OUVRAGE_TYPE_LABELS, OUVRAGE_TYPE_VISUAL,\n"
                    "  ouvrageTypeVisual, ouvrageTypeLabel, ouvrageCharacteristics, Troncon\n"
                    "} from '@app/core/models/ouvrage.model'; // PHASE-OUVRAGE-UI")
    assert old_proj_imp in src, "project.model import tail not found"
    src = src.replace(old_proj_imp, new_proj_imp, 1)

    # 1b) imports array: add ToggleSwitchModule.
    old_arr = "    DialogModule,\n    TagModule,"
    new_arr = "    DialogModule,\n    ToggleSwitchModule,\n    TagModule,"
    assert old_arr in src, "imports array anchor not found"
    src = src.replace(old_arr, new_arr, 1)

    # 1c) WIZARD_STEPS: insert "Ouvrages" between Identité and Calendrier.
    old_steps = "readonly WIZARD_STEPS = ['Identité', 'Calendrier', 'Contrat'];"
    new_steps = "readonly WIZARD_STEPS = ['Identité', 'Ouvrages', 'Calendrier', 'Contrat']; // PHASE-OUVRAGE-UI: 4 steps"
    assert old_steps in src, "WIZARD_STEPS not found"
    src = src.replace(old_steps, new_steps, 1)

    # 1d) Update wNext / wBack to use step count 4 instead of 3.
    old_wnext = "wNext(): void { if (this.step() < 3) { this.step.set(this.step() + 1); } }"
    new_wnext = "wNext(): void { if (this.step() < 4) { this.step.set(this.step() + 1); } } // PHASE-OUVRAGE-UI: 4 steps"
    assert old_wnext in src, "wNext not found"
    src = src.replace(old_wnext, new_wnext, 1)

    # 1e) Add ouvrage draft state + helpers. Insert before `canWrite = computed`.
    old_canwrite = "  canWrite = computed(() => this.auth.can(PERM.PROJECT_WRITE));"
    ouvrage_block = """  // ── PHASE-OUVRAGE-UI — wizard "Ouvrages" step draft state ────────────
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

  canWrite = computed(() => this.auth.can(PERM.PROJECT_WRITE));"""
    assert old_canwrite in src, "canWrite computed not found"
    src = src.replace(old_canwrite, ouvrage_block + "\n  " + old_canwrite, 1)

    # 1f) openCreate: reset draft ouvrages.
    old_open = "    this.formStatus = 'ACTIF';\n    this.showDialog = true;"
    new_open = "    this.formStatus = 'ACTIF';\n    this.resetDraftOuvrages(); // PHASE-OUVRAGE-UI\n    this.showDialog = true;"
    assert old_open in src, "openCreate tail not found"
    src = src.replace(old_open, new_open, 1)

    # 1g) create(): after the chantier is created, persist the draft ouvrages.
    old_create_next = """      next: (project) => {
        this.msgs.add({ severity: 'success',
          summary: 'Chantier créé — dossiers créés automatiquement' });
        this.showDialog = false;
        this.router.navigate(['/projects', project.id]);
      },"""
    new_create_next = """      next: (project) => {
        this.msgs.add({ severity: 'success',
          summary: 'Chantier créé — dossiers créés automatiquement' });
        // PHASE-OUVRAGE-UI — persist the draft ouvrages (if any) on the new chantier.
        // COMMUN is auto-created by the backend, so we don't send it.
        const drafts = this.draftOuvrages();
        if (drafts.length) {
          // Fire-and-forget: the chantier page will refetch ouvrages on load.
          // Errors are toasted but don't block navigation.
          drafts.forEach(d => {
            this.projectService.createOuvrage(project.id, {
              type: d.type, name: d.name, code: d.code || null,
              characteristics: d.type === 'CONDUITE' && d.troncons.length
                ? { ...d.characteristics, troncons: d.troncons }
                : d.characteristics,
            }).subscribe({
              error: (err) => this.msgs.add({ severity: 'warn', summary: 'Ouvrage non créé',
                detail: err?.error?.message ?? 'Création impossible pour ' + d.name })
            });
          });
        }
        this.showDialog = false;
        this.router.navigate(['/projects', project.id]);
      },"""
    assert old_create_next in src, "create() next block not found"
    src = src.replace(old_create_next, new_create_next, 1)

    # 1h) Add a ouvrageSummary display helper for the cards/table.
    #     Replace the type label usage with ouvrageSummary when available.
    old_typelabel = """  typeLabel(t: string | null | undefined): string {
    return t ? (PROJECT_TYPE_LABELS[t] ?? t) : '';
  }"""
    new_typelabel = """  typeLabel(t: string | null | undefined): string {
    return t ? (PROJECT_TYPE_LABELS[t] ?? t) : '';
  }

  /** PHASE-OUVRAGE-UI — what to show on a chantier card instead of the type
   *  label: the backend-built ouvrageSummary when present, else the type label
   *  (so existing projects without ouvrages still show something). */
  cardSubtitle(p: Project): string {
    const s = (p as any).ouvrageSummary;
    return s && s.trim() ? s.trim() : this.typeLabel(p.projectType);
  }"""
    assert old_typelabel in src, "typeLabel not found"
    src = src.replace(old_typelabel, new_typelabel, 1)

    write(TS, src)
    print("[OK] projects.component.ts patched")

# ============================================================================
# 2) projects.component.html — add the wizard step + replace labels
# ============================================================================
def patch_html():
    src = read(HTML)
    if SENTINEL in src:
        print("[SKIP] projects.component.html already patched")
        return

    # 2a) Wizard step indicator: change `step() < 3` to `step() < 4`.
    src = src.replace("@if (step() < 3) {", "@if (step() < 4) { /* PHASE-OUVRAGE-UI: 4 steps */")

    # 2b) Insert the new "Ouvrages" step (step 2) between Identité and Calendrier.
    #     The Calendrier step currently starts with `@if (step() === 2) {`.
    #     After insertion, Calendrier becomes step 3 and Contrat becomes step 4.
    old_step2 = "    <!-- ÉTAPE 2 — CALENDRIER & ÉQUIPE -->\n    @if (step() === 2) {"
    new_step2 = """    <!-- PHASE-OUVRAGE-UI — ÉTAPE 2 — OUVRAGES (optionnel, peut être sauté) -->
    @if (step() === 2) {
      <div class="field">
        <label class="q">Ouvrages du chantier <small>(optionnel — peut être sauté)</small></label>
        <div class="ouvrage-add-btns">
          @for (t of ADDABLE_OUVRAGE_TYPES; track t) {
            <button type="button" class="ouvrage-add-btn"
                    [style.--c]="ouvrageTypeVisual(t).color"
                    [style.--s]="ouvrageTypeVisual(t).soft"
                    (click)="addDraftOuvrage(t)"
                    [attr.aria-label]="'Ajouter ' + ouvrageTypeLabel(t)">
              <i class="pi pi-plus"></i>
              <i class="pi" [class]="ouvrageTypeVisual(t).icon"></i>
              <span>{{ ouvrageTypeLabel(t) }}</span>
            </button>
          }
        </div>
      </div>

      @if (draftOuvrages().length) {
        <div class="ouvrage-cards">
          @for (o of draftOuvrages(); track o.tempId) {
            <div class="ouvrage-card" [style.--c]="ouvrageTypeVisual(o.type).color"
                                      [style.--s]="ouvrageTypeVisual(o.type).soft">
              <div class="oc-head">
                <span class="oc-disc"><i class="pi" [class]="ouvrageTypeVisual(o.type).icon"></i></span>
                <input pInputText class="oc-name" placeholder="Nom de l'ouvrage"
                       [(ngModel)]="o.name" [attr.aria-label]="'Nom de l\\'ouvrage ' + ouvrageTypeLabel(o.type)" />
                <button type="button" class="oc-remove" (click)="removeDraftOuvrage(o.tempId)"
                        [attr.aria-label]="'Retirer cet ouvrage'">
                  <i class="pi pi-times"></i>
                </button>
              </div>
              <div class="oc-body">
                <div class="oc-two">
                  <div class="oc-field">
                    <label>Code</label>
                    <input pInputText class="inp" placeholder="Ex. : RES-01" [(ngModel)]="o.code" />
                  </div>
                </div>
                @for (entry of ouvrageCharacteristics(o.type); track entry.key) {
                  <div class="oc-field">
                    <label>{{ entry.spec.label }}</label>
                    @switch (entry.spec.kind) {
                      @case ('CHOICE') {
                        <p-select [options]="entry.spec.choices!"
                                  [(ngModel)]="o.characteristics[entry.key]"
                                  [showClear]="true" placeholder="—"
                                  styleClass="w100" />
                      }
                      @case ('BOOLEAN') {
                        <div class="oc-switch">
                          <p-toggleswitch [(ngModel)]="o.characteristics[entry.key]" />
                          <span>{{ o.characteristics[entry.key] ? 'Oui' : 'Non' }}</span>
                        </div>
                      }
                      @case ('INTEGER') {
                        <p-inputNumber [(ngModel)]="o.characteristics[entry.key]"
                                       [min]="0" mode="decimal" [showButtons]="true"
                                       [suffix]="entry.spec.unit ? ' ' + entry.spec.unit : ''"
                                       styleClass="w100" />
                      }
                      @case ('NUMBER_WITH_UNIT') {
                        <p-inputNumber [(ngModel)]="o.characteristics[entry.key]"
                                       [min]="0" mode="decimal" [minFractionDigits]="0" [maxFractionDigits]="3"
                                       [suffix]="entry.spec.unit ? ' ' + entry.spec.unit : ''"
                                       styleClass="w100" />
                      }
                      @case ('TEXT') {
                        <input pInputText class="inp" [(ngModel)]="o.characteristics[entry.key]"
                               placeholder="{{ entry.spec.label }}" />
                      }
                      @case ('TRONCONS_ARRAY') {
                        <div class="oc-troncons">
                          @for (tr of o.troncons; track $index) {
                            <div class="oc-troncon">
                              <p-inputNumber [(ngModel)]="tr.de_mm" [min]="0" mode="decimal"
                                             suffix=" mm DE" styleClass="oc-tr-inp" />
                              <p-select [options]="['10','16']" [(ngModel)]="tr.pn_bar"
                                        styleClass="oc-tr-pn" />
                              <p-inputNumber [(ngModel)]="tr.longueur_m" [min]="0" mode="decimal"
                                             [minFractionDigits]="0" [maxFractionDigits]="3"
                                             suffix=" m" styleClass="oc-tr-inp" />
                              <button type="button" class="oc-tr-rm" (click)="removeTroncon(o.tempId, $index)"
                                      [attr.aria-label]="'Retirer ce tronçon'">
                                <i class="pi pi-times"></i>
                              </button>
                            </div>
                          } @empty {
                            <small class="muted">Aucun tronçon. Ajoutez-en un par diamètre.</small>
                          }
                          <button type="button" class="oc-tr-add" (click)="addTroncon(o.tempId)">
                            <i class="pi pi-plus"></i> Ajouter un tronçon
                          </button>
                        </div>
                      }
                    }
                  </div>
                }
                @if (!ouvrageCharacteristics(o.type).length) {
                  <small class="muted">Aucune caractéristique requise pour ce type.</small>
                }
              </div>
            </div>
          }
        </div>
      } @else {
        <p class="wizard-note"><i class="pi pi-info-circle"></i> Aucun ouvrage ajouté. Vous pourrez en ajouter plus tard depuis la fiche du chantier.</p>
      }
    }

    <!-- ÉTAPE 3 — CALENDRIER & ÉQUIPE -->
    @if (step() === 3) {"""
    assert old_step2 in src, "step 2 anchor not found"
    src = src.replace(old_step2, new_step2, 1)

    # 2c) Update the Contrat step number from 3 to 4.
    src = src.replace("<!-- ÉTAPE 3 — CONTRAT & DÉTAILS (tout optionnel) -->\n    @if (step() === 3) {",
                      "<!-- ÉTAPE 4 — CONTRAT & DÉTAILS (tout optionnel) -->\n    @if (step() === 4) { /* PHASE-OUVRAGE-UI */ }")

    # 2d) Card grid: replace the type label with the ouvrageSummary (cardSubtitle).
    old_card_type = """              <span class="type-tag" [style]="{ background: tv(p.projectType).soft, color: tv(p.projectType).color }">
                <i class="pi" [class]="tv(p.projectType).icon"></i> {{ tv(p.projectType).label }}
              </span>"""
    new_card_type = """              <span class="type-tag" [style]="{ background: tv(p.projectType).soft, color: tv(p.projectType).color }">
                <i class="pi" [class]="tv(p.projectType).icon"></i> {{ cardSubtitle(p) }}
              </span>"""
    assert old_card_type in src, "card type-tag block not found"
    src = src.replace(old_card_type, new_card_type, 1)

    # 2e) Table view: replace the type cell with ouvrageSummary.
    old_table_type = """            <td>
              <span class="type-chip" [style.--tc-color]="tv(p.projectType).color">
                <i class="pi" [class]="tv(p.projectType).icon"></i> {{ tv(p.projectType).label }}
              </span>
            </td>"""
    new_table_type = """            <td>
              <span class="type-chip" [style.--tc-color]="tv(p.projectType).color">
                <i class="pi" [class]="tv(p.projectType).icon"></i> {{ cardSubtitle(p) }}
              </span>
            </td>"""
    assert old_table_type in src, "table type cell not found"
    src = src.replace(old_table_type, new_table_type, 1)

    write(HTML, src)
    print("[OK] projects.component.html patched")

# ============================================================================
# 3) projects.component.scss — add styles for the wizard ouvrage cards
# ============================================================================
def patch_scss():
    src = read(SCSS)
    if SENTINEL in src:
        print("[SKIP] projects.component.scss already patched")
        return
    styles = """

// ── PHASE-OUVRAGE-UI — wizard "Ouvrages" step styles ──────────────────────
.ouvrage-add-btns {
  display: flex; flex-wrap: wrap; gap: 8px;
  margin-bottom: 14px;
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

.ouvrage-cards {
  display: flex; flex-direction: column; gap: 12px;
  margin-top: 6px;
}
.ouvrage-card {
  border: 1px solid var(--c, #e2e8f0);
  border-left: 4px solid var(--c, #94a3b8);
  border-radius: 10px;
  background: var(--s, #f8fafc);
  padding: 12px 14px;
}
.oc-head {
  display: flex; align-items: center; gap: 10px; margin-bottom: 10px;
}
.oc-disc {
  width: 32px; height: 32px; border-radius: 8px;
  background: var(--c, #94a3b8); color: #fff;
  display: inline-grid; place-items: center;
  i { font-size: 16px; }
}
.oc-name {
  flex: 1; font-size: 15px; font-weight: 700; color: #1e293b;
  background: #fff; border: 1px solid #e2e8f0; border-radius: 6px; padding: 6px 10px;
}
.oc-remove {
  width: 28px; height: 28px; border-radius: 50%;
  border: none; background: transparent; color: #94a3b8; cursor: pointer;
  &:hover { background: #fee2e2; color: #dc2626; }
}
.oc-body { display: flex; flex-direction: column; gap: 10px; }
.oc-two { display: grid; grid-template-columns: 1fr; gap: 10px; }
.oc-field {
  display: flex; flex-direction: column; gap: 4px;
  label { font-size: 12px; font-weight: 700; color: #475569; text-transform: uppercase; letter-spacing: .03em; }
}
.oc-switch {
  display: inline-flex; align-items: center; gap: 8px;
  span { font-size: 13px; color: #475569; }
}
.oc-troncons {
  display: flex; flex-direction: column; gap: 6px;
  background: #fff; border: 1px dashed #cbd5e1; border-radius: 6px; padding: 8px;
}
.oc-troncon {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
}
.oc-tr-inp { width: 110px; }
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
  &:hover { background: var(--s, #f8fafc); }
}
.wizard-note {
  font-size: 13px; color: #64748b; margin: 8px 0 0;
  i { color: #0ea5e9; margin-right: 6px; }
}
.muted { color: #94a3b8; font-size: 12px; }
"""
    write(SCSS, src + styles)
    print("[OK] projects.component.scss patched")

def main():
    patch_ts()
    patch_html()
    patch_scss()
    print("\nAll projects.component patches applied.")

if __name__ == '__main__':
    main()
