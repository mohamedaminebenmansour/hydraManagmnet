import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, ActivatedRoute, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { MultiSelectModule } from 'primeng/multiselect';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { MessageService } from 'primeng/api';
import { ProcessService } from '@app/core/services/process.service';
import { ProjectService } from '@app/core/services/project.service';
import { Project } from '@app/core/models/project.model';
import {
  TemplateDetail, TemplateStep, TestRuleResponse,
  MOVEMENT_NATURES, NATURE_LABELS, PAYMENT_MODES, PAYMENT_MODE_LABELS,
} from '@app/core/models/process.model';
import { ROLE_OPTIONS, roleVisual } from '@app/shared/process-visual';

interface DraftStep {
  id: string;
  stepOrder: number;
  stepKey: string;
  name: string;
  assigneeRole: string;
  requiresAttachment: boolean;
  approvalMode: 'ANY' | 'ALL';
  dueDays: number | null;
}

@Component({
  selector: 'app-process-builder',
  standalone: true,
  imports: [
    FormsModule, ButtonModule, InputTextModule, SelectModule, MultiSelectModule, ToggleSwitchModule, RouterLink,
  ],
  styleUrl: './process-builder.component.scss',
  template: `
    <section class="builder">
      <nav class="bc">
        <a routerLink="/processus"><i class="pi pi-sitemap"></i> Processus</a>
        <i class="pi pi-angle-right"></i>
        <span>{{ isEdit() ? 'Modifier la règle' : 'Créer une règle' }}</span>
      </nav>

      <header class="builder-hero">
        <div class="hero-icon"><i class="pi pi-sitemap"></i></div>
        <div class="hero-text">
          <h1>{{ isEdit() ? 'Modifier la règle d’approbation' : 'Créer une règle d’approbation' }}</h1>
          <p>Quoi, quand, qui valide, puis aperçu et test.</p>
        </div>
      </header>

      <ol class="stepper">
        <li [class.active]="screen() === 1" [class.done]="screen() > 1" (click)="goto(1)">
          <span class="dot">1</span><span class="lbl">Quoi</span>
        </li>
        <li class="step-line" [class.filled]="screen() > 1"></li>
        <li [class.active]="screen() === 2" [class.done]="screen() > 2" (click)="goto(2)">
          <span class="dot">2</span><span class="lbl">Quand</span>
        </li>
        <li class="step-line" [class.filled]="screen() > 2"></li>
        <li [class.active]="screen() === 3" [class.done]="screen() > 3" (click)="goto(3)">
          <span class="dot">3</span><span class="lbl">Qui</span>
        </li>
        <li class="step-line" [class.filled]="screen() > 3"></li>
        <li [class.active]="screen() === 4" (click)="goto(4)">
          <span class="dot">4</span><span class="lbl">Aperçu</span>
        </li>
      </ol>

      <!-- ── Screen 1 — Quoi ── -->
      @if (screen() === 1) {
        <div class="card">
          <header class="card-head">
            <h2><i class="pi pi-th-large"></i> Quoi — type de règle</h2>
            <p>Choisissez ce que cette règle gouverne.</p>
          </header>
          <div class="form-grid">
            <div class="quoi-tiles">
              <div class="quoi-tile active" style="--tile-c:#0891b2">
                <i class="pi pi-wallet"></i>
                <div class="qt-name">Paiement</div>
                <div class="qt-sub">Approbation de paiement</div>
              </div>
              <div class="quoi-tile disabled">
                <i class="pi pi-shopping-cart"></i>
                <div class="qt-name">Bon de commande</div>
                <div class="qt-sub">Bientôt disponible</div>
              </div>
              <div class="quoi-tile disabled">
                <i class="pi pi-users"></i>
                <div class="qt-name">Situation sous-traitant</div>
                <div class="qt-sub">Bientôt disponible</div>
              </div>
            </div>

            <div class="form-row">
              <label>Nom de la règle</label>
              <input pInputText [ngModel]="name()" (ngModelChange)="name.set($event)"
                     placeholder="Ex: Paiement sous-traitant" />
            </div>

            <div class="form-actions">
              <button pButton type="button" label="Annuler" severity="secondary" [text]="true"
                      (click)="router.navigate(['/processus'])"></button>
              <button pButton type="button" [label]="isEdit() ? 'Continuer' : 'Créer & continuer'"
                      icon="pi pi-arrow-right" severity="success"
                      (click)="continueFromQuoi()" [loading]="saving()" [disabled]="!name().trim()"></button>
            </div>
          </div>
        </div>
      }

      <!-- ── Screen 2 — Quand ── -->
      @if (screen() === 2) {
        <div class="card">
          <header class="card-head">
            <h2><i class="pi pi-filter"></i> Quand — conditions</h2>
            <p>Ajoutez les conditions qui déclenchent cette règle. Aucune condition = tous les paiements.</p>
          </header>

          <div class="chips">
            <div class="chip">
              <div class="chip-head">
                <p-toggleswitch [ngModel]="chipActive('amount_min')" (ngModelChange)="toggleChip('amount_min', $event)" />
                <span>Montant à partir de</span>
              </div>
              @if (chipActive('amount_min')) {
                <div class="chip-body">
                  <input pInputText type="number" [ngModel]="conditions()['amount_min']"
                         (ngModelChange)="setCondition('amount_min', toAmount($event))" placeholder="TND" />
                </div>
              }
              @if (chipErrors()['amount_min']; as err) { <div class="chip-err">{{ err }}</div> }
            </div>

            <div class="chip">
              <div class="chip-head">
                <p-toggleswitch [ngModel]="chipActive('amount_max')" (ngModelChange)="toggleChip('amount_max', $event)" />
                <span>Montant jusqu’à</span>
              </div>
              @if (chipActive('amount_max')) {
                <div class="chip-body">
                  <input pInputText type="number" [ngModel]="conditions()['amount_max']"
                         (ngModelChange)="setCondition('amount_max', toAmount($event))" placeholder="TND" />
                </div>
              }
              @if (chipErrors()['amount_max']; as err) { <div class="chip-err">{{ err }}</div> }
            </div>

            <div class="chip">
              <div class="chip-head">
                <p-toggleswitch [ngModel]="chipActive('project_ids')" (ngModelChange)="toggleChip('project_ids', $event)" />
                <span>Chantiers</span>
              </div>
              @if (chipActive('project_ids')) {
                <div class="chip-body">
                  <p-multiSelect [options]="projects()" optionValue="id" optionLabel="name"
                            [ngModel]="conditions()['project_ids']"
                            (ngModelChange)="setCondition('project_ids', $event)"
                            placeholder="Sélectionner les chantiers" styleClass="chip-select"></p-multiSelect>
                </div>
              }
              @if (chipErrors()['project_ids']; as err) { <div class="chip-err">{{ err }}</div> }
            </div>

            <div class="chip">
              <div class="chip-head">
                <p-toggleswitch [ngModel]="chipActive('natures')" (ngModelChange)="toggleChip('natures', $event)" />
                <span>Nature</span>
              </div>
              @if (chipActive('natures')) {
                <div class="chip-body">
                  <p-multiSelect [options]="natureOptions" optionValue="code" optionLabel="label"
                            [ngModel]="conditions()['natures']"
                            (ngModelChange)="setCondition('natures', $event)"
                            placeholder="Sélectionner les natures" styleClass="chip-select"></p-multiSelect>
                </div>
              }
              @if (chipErrors()['natures']; as err) { <div class="chip-err">{{ err }}</div> }
            </div>

            <div class="chip">
              <div class="chip-head">
                <p-toggleswitch [ngModel]="chipActive('payment_modes')" (ngModelChange)="toggleChip('payment_modes', $event)" />
                <span>Mode de paiement</span>
              </div>
              @if (chipActive('payment_modes')) {
                <div class="chip-body">
                  <p-multiSelect [options]="modeOptions" optionValue="code" optionLabel="label"
                            [ngModel]="conditions()['payment_modes']"
                            (ngModelChange)="setCondition('payment_modes', $event)"
                            placeholder="Sélectionner les modes" styleClass="chip-select"></p-multiSelect>
                </div>
              }
              @if (chipErrors()['payment_modes']; as err) { <div class="chip-err">{{ err }}</div> }
            </div>
          </div>

          @if (!hasConditions()) {
            <div class="chip-empty">Aucune condition — tous les paiements sont concernés.</div>
          }

          <div class="form-actions">
            <button pButton label="Retour" icon="pi pi-arrow-left" severity="secondary" [text]="true"
                    (click)="goto(1)"></button>
            <button pButton label="Continuer" icon="pi pi-arrow-right" severity="success"
                    (click)="continueFromQuand()" [loading]="saving()"></button>
          </div>
        </div>
      }

      <!-- ── Screen 3 — Qui ── -->
      @if (screen() === 3) {
        <div class="card">
          <header class="card-head">
            <h2><i class="pi pi-users"></i> Qui — étapes de validation</h2>
            <p>Ordonnez les étapes. Chaque étape a un rôle, un mode (un seul suffit / tous) et un délai.</p>
          </header>

          <ol class="steps-list">
            @for (s of steps(); track s.id; let i = $index) {
              <li class="step-card" [style.--role-color]="roleV(s.assigneeRole).color">
                <div class="step-order">{{ i + 1 }}</div>
                <div class="step-content">
                  <p-select [options]="roleOptions" optionValue="code" optionLabel="label"
                            [ngModel]="s.assigneeRole"
                            (onChange)="updateStepField(i, 'assigneeRole', $event.value)" styleClass="step-role-select">
                    <ng-template let-r pTemplate="item">
                      <span><i class="pi {{ r.icon }}" [style.color]="r.color"></i> {{ r.label }}</span>
                    </ng-template>
                  </p-select>
                  <p-select [options]="approvalModeOptions" optionValue="code" optionLabel="label"
                            [ngModel]="s.approvalMode"
                            (onChange)="updateStepField(i, 'approvalMode', $event.value)"></p-select>
                  <label class="step-due">
                    <span>Délai (jours)</span>
                    <input pInputText type="number" [ngModel]="s.dueDays"
                           (change)="updateStepField(i, 'dueDays', asInt($event))" />
                  </label>
                </div>
                <div class="step-actions">
                  <button pButton label="Monter" size="small" severity="info" [text]="true"
                          (click)="moveStep(i, -1)" [disabled]="i === 0"></button>
                  <button pButton label="Descendre" size="small" severity="info" [text]="true"
                          (click)="moveStep(i, 1)" [disabled]="i === steps().length - 1"></button>
                  <button pButton label="Supprimer" size="small" severity="danger" [text]="true"
                          (click)="removeStep(i)" [loading]="saving()"></button>
                </div>
              </li>
            }
          </ol>

          <button pButton label="Ajouter une étape" icon="pi pi-plus" severity="info" [outlined]="true"
                  (click)="addStep()" [loading]="saving()"></button>

          <div class="form-actions">
            <button pButton label="Retour" icon="pi pi-arrow-left" severity="secondary" [text]="true"
                    (click)="goto(2)"></button>
            <button pButton label="Continuer" icon="pi pi-arrow-right" severity="success"
                    (click)="goto(4); loadSentence()"></button>
          </div>
        </div>
      }

      <!-- ── Screen 4 — Aperçu ── -->
      @if (screen() === 4) {
        <div class="card">
          <header class="card-head">
            <h2><i class="pi pi-eye"></i> Aperçu</h2>
          </header>

          <div class="sentence-box">
            <i class="pi pi-quote-right"></i>
            <p>{{ sentence() || 'Chargement…' }}</p>
          </div>

          <div class="test-box">
            <h3><i class="pi pi-flask"></i> Tester la règle</h3>
            <div class="test-row">
              <input pInputText type="number" [ngModel]="testAmount()"
                     (ngModelChange)="testAmount.set(toAmount($event))" placeholder="Montant (TND)" />
              <p-select [options]="natureOptions" optionValue="code" optionLabel="label"
                        [ngModel]="testNature()" (ngModelChange)="testNature.set($event)"
                        placeholder="Nature"></p-select>
              <p-select [options]="modeOptions" optionValue="code" optionLabel="label"
                        [ngModel]="testMode()" (ngModelChange)="testMode.set($event)"
                        placeholder="Mode"></p-select>
              <p-select [options]="projects()" optionValue="id" optionLabel="name"
                        [ngModel]="testProject()" (ngModelChange)="testProject.set($event)"
                        placeholder="Chantier"></p-select>
              <button pButton label="Tester" icon="pi pi-play" severity="info"
                      (click)="runTest()" [loading]="testing()"
                      [disabled]="testAmount() == null"></button>
            </div>
            @if (testResult(); as tr) {
              <div class="test-result">
                <div class="tr-chosen"><i class="pi pi-bolt"></i> Règle choisie : <strong>{{ tr.chosenTemplateKey ?? 'aucune' }}</strong></div>
                <div class="tr-matched">Cette règle correspond : <strong>{{ tr.testedMatched ? 'Oui' : 'Non' }}</strong></div>
                <div class="tr-reason">{{ tr.reason }}</div>
              </div>
            }
          </div>

          <div class="form-row form-row-inline">
            <label>Activer la règle</label>
            <div class="toggle-wrap">
              <p-toggleswitch [ngModel]="active()" (ngModelChange)="active.set($event)" />
              <span>{{ active() ? 'Active (visible)' : 'Inactive (brouillon)' }}</span>
            </div>
          </div>

          <div class="form-actions">
            <button pButton label="Retour" icon="pi pi-arrow-left" severity="secondary" [text]="true"
                    (click)="goto(3)"></button>
            <button pButton label="Enregistrer" icon="pi pi-check" severity="success"
                    (click)="saveAndFinish()" [loading]="saving()"></button>
          </div>
        </div>
      }
    </section>
  `,
})
export class ProcessBuilderComponent implements OnInit {
  private processService = inject(ProcessService);
  private projectService = inject(ProjectService);
  private msgs = inject(MessageService);
  router = inject(Router);
  private route = inject(ActivatedRoute);

  // Wizard state
  screen = signal<1 | 2 | 3 | 4>(1);
  saving = signal(false);
  templateId = signal('');
  isEdit = computed(() => !!this.templateId());

  // Template fields
  name = signal('');
  active = signal(false);
  priority = signal(100);
  conditions = signal<Record<string, any>>({});
  chipErrors = signal<Record<string, string>>({});

  // Steps
  steps = signal<DraftStep[]>([]);

  // Aperçu
  sentence = signal('');
  testResult = signal<TestRuleResponse | null>(null);
  testing = signal(false);
  testAmount = signal<number | null>(null);
  testNature = signal<string | null>(null);
  testMode = signal<string | null>(null);
  testProject = signal<string | null>(null);

  // Reference data
  projects = signal<Project[]>([]);
  roleOptions = ROLE_OPTIONS;
  natureOptions = MOVEMENT_NATURES.map(c => ({ code: c, label: NATURE_LABELS[c] ?? c }));
  modeOptions = PAYMENT_MODES.map(c => ({ code: c, label: PAYMENT_MODE_LABELS[c] ?? c }));
  approvalModeOptions = [
    { code: 'ANY', label: 'Un seul suffit' },
    { code: 'ALL', label: 'Tous' },
  ];

  roleV = roleVisual;

  ngOnInit(): void {
    this.projectService.list().subscribe(p => this.projects.set(p ?? []));
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.templateId.set(id);
      this.loadTemplate(id);
    }
  }

  goto(n: 1 | 2 | 3 | 4): void {
    this.screen.set(n);
  }

  // ── Conditions (chips) ────────────────────────────────────────
  chipActive(key: string): boolean {
    return this.conditions()[key] !== undefined;
  }

  toggleChip(key: string, on: boolean): void {
    if (on) {
      const def = (key === 'amount_min' || key === 'amount_max') ? null : [];
      this.conditions.update(c => ({ ...c, [key]: def }));
    } else {
      this.conditions.update(c => {
        const copy = { ...c };
        delete copy[key];
        return copy;
      });
      this.clearChipError(key);
    }
  }

  setCondition(key: string, value: any): void {
    this.conditions.update(c => ({ ...c, [key]: value }));
    this.clearChipError(key);
  }

  hasConditions(): boolean {
    return Object.keys(this.conditions()).length > 0;
  }

  private clearChipError(key: string): void {
    this.chipErrors.update(m => {
      const copy = { ...m };
      delete copy[key];
      return copy;
    });
  }

  /** Map a backend condition error message to the chip it concerns. */
  private chipForError(msg: string): string {
    const m = (msg ?? '').toLowerCase();
    if (m.includes('minimum')) return 'amount_min';
    if (m.includes('maximum')) return 'amount_max';
    if (m.includes('nature')) return 'natures';
    if (m.includes('règlement') || m.includes('reglement') || m.includes('mode')) return 'payment_modes';
    if (m.includes('chantier')) return 'project_ids';
    return '';
  }

  toAmount(v: any): number | null {
    if (v === null || v === undefined || v === '') return null;
    const n = typeof v === 'number' ? v : Number(String(v).replace(',', '.'));
    return isNaN(n) ? null : n;
  }

  asInt(ev: Event): number | null {
    const v = (ev.target as HTMLInputElement).value;
    if (v === null || v === undefined || v === '') return null;
    const n = parseInt(v, 10);
    return isNaN(n) ? null : n;
  }

  // ── Screen 1 — Quoi ───────────────────────────────────────────
  continueFromQuoi(): void {
    const name = this.name().trim();
    if (!name) return;
    this.saving.set(true);
    if (this.templateId()) {
      this.processService.updateTemplate(this.templateId(), { name }).subscribe(r => {
        this.saving.set(false);
        if (r.ok) this.goto(2);
        else this.msgs.add({ severity: 'error', summary: 'Erreur', detail: r.error });
      });
    } else {
      const slug = name.toUpperCase().replace(/[^A-Z0-9]+/g, '_').replace(/^_+|_+$/g, '') || 'REGLE';
      const key = `${slug}_${Math.random().toString(36).slice(2, 6).toUpperCase()}`;
      this.processService.createTemplate({
        key, name, subjectType: 'CASH_PAYMENT', conditions: {}, priority: this.priority(),
      }).subscribe(r => {
        this.saving.set(false);
        if (r.ok && r.detail) {
          this.templateId.set(r.detail.id);
          this.goto(2);
        } else {
          this.msgs.add({ severity: 'error', summary: 'Erreur', detail: r.error });
        }
      });
    }
  }

  // ── Screen 2 — Quand ──────────────────────────────────────────
  continueFromQuand(): void {
    if (!this.templateId()) { this.goto(1); return; }
    this.saving.set(true);
    this.chipErrors.set({});
    this.processService.updateTemplate(this.templateId(), { conditions: this.conditions() }).subscribe(r => {
      this.saving.set(false);
      if (r.ok) {
        this.goto(3);
      } else {
        const field = this.chipForError(r.error ?? '');
        if (field) this.chipErrors.update(m => ({ ...m, [field]: r.error! }));
        this.msgs.add({ severity: 'error', summary: 'Condition invalide', detail: r.error });
      }
    });
  }

  // ── Screen 3 — Qui ────────────────────────────────────────────
  addStep(): void {
    if (!this.templateId()) { this.goto(1); return; }
    const n = this.steps().length + 1;
    this.saving.set(true);
    this.processService.addStep(this.templateId(), {
      stepOrder: n, stepKey: `STEP_${n}`, name: `Étape ${n}`,
      assigneeRole: 'FINANCE', requiresAttachment: false, approvalMode: 'ANY', dueDays: null,
    }).subscribe(r => {
      this.saving.set(false);
      if (r.ok && r.detail) this.syncSteps(r.detail);
      else if (!r.ok) this.msgs.add({ severity: 'error', summary: 'Erreur', detail: r.error });
    });
  }

  removeStep(index: number): void {
    const step = this.steps()[index];
    if (!step?.id) return;
    this.saving.set(true);
    this.processService.deleteStep(this.templateId(), step.id).subscribe(ok => {
      this.saving.set(false);
      if (ok) this.reloadTemplate();
    });
  }

  updateStepField(index: number, field: 'assigneeRole' | 'approvalMode' | 'dueDays', value: any): void {
    const step = this.steps()[index];
    if (!step?.id) return;
    this.saving.set(true);
    const body: any = { requiresAttachment: step.requiresAttachment };
    body[field] = value;
    this.processService.updateStep(this.templateId(), step.id, body).subscribe(r => {
      this.saving.set(false);
      if (r.ok && r.detail) this.syncSteps(r.detail);
      else if (!r.ok) this.msgs.add({ severity: 'error', summary: 'Erreur', detail: r.error });
    });
  }

  moveStep(index: number, dir: -1 | 1): void {
    const target = index + dir;
    const list = this.steps();
    if (target < 0 || target >= list.length) return;
    // Swap locally + renumber.
    this.steps.update(arr => {
      const copy = [...arr];
      [copy[index], copy[target]] = [copy[target], copy[index]];
      return copy.map((s, i) => ({ ...s, stepOrder: i + 1 }));
    });
    this.persistOrder();
  }

  private persistOrder(): void {
    const id = this.templateId();
    if (!id) return;
    const steps = this.steps();
    // Two-phase: push to a temp range first to avoid the (template_id, step_order)
    // unique constraint clashing on swap, then set the final 1..N order.
    const temp = steps.map((s, i) => ({ stepId: s.id, stepOrder: 1000 + i + 1 }));
    const final = steps.map((s, i) => ({ stepId: s.id, stepOrder: i + 1 }));
    this.saving.set(true);
    this.processService.reorderSteps(id, temp).subscribe(ok1 => {
      if (!ok1) { this.saving.set(false); this.reloadTemplate(); return; }
      this.processService.reorderSteps(id, final).subscribe(ok2 => {
        this.saving.set(false);
        if (!ok2) this.reloadTemplate();
      });
    });
  }

  // ── Screen 4 — Aperçu ─────────────────────────────────────────
  loadSentence(): void {
    if (!this.templateId()) return;
    this.processService.getTemplateSentence(this.templateId()).subscribe(s => this.sentence.set(s));
  }

  runTest(): void {
    if (!this.templateId() || this.testAmount() == null) return;
    this.testing.set(true);
    this.processService.testRule(this.templateId(), {
      projectId: this.testProject(),
      amount: this.testAmount()!,
      nature: this.testNature(),
      paymentMode: this.testMode(),
    }).subscribe(r => {
      this.testing.set(false);
      this.testResult.set(r);
    });
  }

  saveAndFinish(): void {
    if (!this.templateId()) { this.goto(1); return; }
    this.saving.set(true);
    this.processService.updateTemplate(this.templateId(), {
      name: this.name(), active: this.active(), priority: this.priority(), conditions: this.conditions(),
    }).subscribe(r => {
      this.saving.set(false);
      if (r.ok) {
        this.msgs.add({ severity: 'success', summary: 'Règle enregistrée', detail: this.active() ? 'Règle active' : 'Règle en brouillon' });
        this.router.navigate(['/processus']);
      } else {
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: r.error });
      }
    });
  }

  // ── Load / sync ───────────────────────────────────────────────
  private loadTemplate(id: string): void {
    this.saving.set(true);
    this.processService.getTemplate(id).subscribe(tpl => {
      this.saving.set(false);
      if (tpl) this.fillFromTemplate(tpl);
    });
  }

  private reloadTemplate(): void {
    if (!this.templateId()) return;
    this.processService.getTemplate(this.templateId()).subscribe(tpl => {
      if (tpl) this.fillFromTemplate(tpl);
    });
  }

  private fillFromTemplate(tpl: TemplateDetail): void {
    this.name.set(tpl.name);
    this.active.set(tpl.active);
    if (tpl.priority != null) this.priority.set(tpl.priority);
    this.conditions.set(tpl.conditions ?? {});
    this.syncSteps(tpl);
  }

  private syncSteps(detail: TemplateDetail): void {
    const list: DraftStep[] = (detail.steps || []).map((s: TemplateStep) => ({
      id: s.id,
      stepOrder: s.stepOrder,
      stepKey: s.stepKey,
      name: s.name,
      assigneeRole: s.assigneeRole,
      requiresAttachment: s.requiresAttachment,
      approvalMode: (s.approvalMode === 'ALL' ? 'ALL' : 'ANY'),
      dueDays: s.dueDays ?? null,
    }));
    this.steps.set(list);
  }
}
