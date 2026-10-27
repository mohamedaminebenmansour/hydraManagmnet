import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, ActivatedRoute, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { CheckboxModule } from 'primeng/checkbox';
import { MessageService } from 'primeng/api';
import { ProcessService } from '@app/core/services/process.service';
import { TemplateDetail, TemplateStep } from '@app/core/models/process.model';
import {
  PROCESS_TYPE_VISUAL,
  processTypeVisual,
  roleVisual,
  ROLE_OPTIONS,
  ProcessTypeVisual,
} from '@app/shared/process-visual';

interface DraftStep {
  id: string | null;      // null = not yet saved
  stepOrder: number;
  stepKey: string;
  name: string;
  assigneeRole: string;
  requiresAttachment: boolean;
  isEditing: boolean;
  isDirty: boolean;
}

@Component({
  selector: 'app-process-builder',
  standalone: true,
  imports: [
    ButtonModule,
    InputTextModule,
    SelectModule,
    ToggleSwitchModule,
    CheckboxModule,
    ReactiveFormsModule,
    RouterLink,
  ],
  styleUrl: './process-builder.component.scss',
  template: `
    <section class="builder">
      <!-- ───────── Breadcrumb ───────── -->
      <nav class="bc">
        <a routerLink="/processus"><i class="pi pi-sitemap"></i> Processus</a>
        <i class="pi pi-angle-right"></i>
        <span>{{ isEdit() ? 'Modifier' : 'Créer' }}</span>
      </nav>

      <!-- ───────── Hero ───────── -->
      <header class="builder-hero" [style.--hero-gradient]="typeV(form.controls.key.value).gradient">
        <div class="hero-icon"><i class="pi {{ typeV(form.controls.key.value).icon }}"></i></div>
        <div class="hero-text">
          <h1>{{ isEdit() ? 'Modifier le processus' : 'Créer un nouveau processus' }}</h1>
          <p>Définissez le nom, la clé et les étapes du workflow. Les étapes seront jouées séquentiellement.</p>
        </div>
      </header>

      <!-- ───────── STEPPER ───────── -->
      <ol class="stepper">
        <li [class.active]="step() === 1" [class.done]="step() > 1"
            (click)="step.set(1)">
          <span class="dot">1</span>
          <span class="lbl">Identité</span>
        </li>
        <li class="step-line" [class.filled]="step() > 1"></li>
        <li [class.active]="step() === 2" [class.done]="step() > 2"
            [class.disabled]="!templateId()" (click)="templateId() && step.set(2)">
          <span class="dot">2</span>
          <span class="lbl">Étapes</span>
        </li>
        <li class="step-line" [class.filled]="step() > 2"></li>
        <li [class.active]="step() === 3" [class.disabled]="steps().length === 0"
            (click)="steps().length && step.set(3)">
          <span class="dot">3</span>
          <span class="lbl">Résumé</span>
        </li>
      </ol>

      <!-- ───────── STEP 1 — IDENTITY ───────── -->
      @if (step() === 1) {
        <div class="card">
          <header class="card-head">
            <h2><i class="pi pi-id-card"></i> Identité du processus</h2>
            <p>Choisissez la catégorie métier et un nom explicite. La clé est technique et sert à brancher le template sur un sujet.</p>
          </header>

          <form [formGroup]="form" (ngSubmit)="saveIdentity()" class="form-grid">
            <div class="form-row">
              <label>Catégorie</label>
              <p-select [options]="categoryOptions" optionValue="key" optionLabel="label"
                        formControlName="key" placeholder="Sélectionner"
                        (onChange)="onCategoryChange()">
                <ng-template let-t pTemplate="selectedItem">
                  <span class="cat-sel"><i class="pi {{ t.icon }}" [style.color]="t.color"></i> {{ t.label }}</span>
                </ng-template>
                <ng-template let-t pTemplate="item">
                  <span class="cat-sel"><i class="pi {{ t.icon }}" [style.color]="t.color"></i> {{ t.label }}</span>
                </ng-template>
              </p-select>
            </div>

            <div class="form-row">
              <label>Nom du processus</label>
              <input pInputText formControlName="name" placeholder="Ex: Paiement sous-traitant Tronçon 3" />
            </div>

            <div class="form-row">
              <label>Clé technique</label>
              <input pInputText formControlName="key"
                     placeholder="Ex: PAYMENT_APPROVAL" />
              <small class="hint">Lettres majuscules + _ — cette clé est utilisée par le backend pour brancher le template.</small>
            </div>

            <div class="form-row form-row-inline">
              <label>Statut</label>
              <div class="toggle-wrap">
                <p-toggleswitch formControlName="active" />
                <span>{{ form.controls.active.value ? 'Actif (visible partout)' : 'Inactif (masqué)' }}</span>
              </div>
            </div>

            <div class="form-actions">
              <button pButton type="button" label="Annuler" icon="pi pi-times" severity="secondary" [text]="true"
                      (click)="router.navigate(['/processus'])"></button>
              <button pButton type="submit" [label]="isEdit() ? 'Enregistrer & continuer' : 'Créer & continuer'" icon="pi pi-arrow-right"
                      severity="success" [loading]="saving()" [disabled]="form.invalid"></button>
            </div>
          </form>
        </div>
      }

      <!-- ───────── STEP 2 — STEPS ───────── -->
      @if (step() === 2) {
        <div class="card">
          <header class="card-head">
            <h2><i class="pi pi-list-check"></i> Étapes du processus</h2>
            <p>Ajoutez les étapes dans l'ordre où elles doivent être jouées. Chaque étape a un responsable (rôle métier) et peut exiger un document.</p>
          </header>

          @if (steps().length === 0) {
            <div class="empty-mini">
              <i class="pi pi-inbox"></i>
              <p>Aucune étape pour l'instant. Ajoutez la première ci-dessous.</p>
            </div>
          }

          <ol class="steps-list">
            @for (s of steps(); track s.id || s.stepOrder; let i = $index) {
              <li class="step-card" [style.--role-color]="roleV(s.assigneeRole).color">
                <div class="step-order">{{ i + 1 }}</div>
                <div class="step-content">
                  @if (s.isEditing) {
                    @if (getStepForm(i); as stepForm) {
                      <div [formGroup]="stepForm" class="step-edit">
                        <input pInputText formControlName="name" placeholder="Nom de l'étape" class="step-input-name" />
                        <input pInputText formControlName="stepKey" placeholder="Clé (ex: DRAFT)" class="step-input-key" />
                        <p-select [options]="roleOptions" optionValue="code" optionLabel="label"
                                  formControlName="assigneeRole" placeholder="Rôle"
                                  styleClass="step-role-select">
                          <ng-template let-r pTemplate="selectedItem">
                            <span><i class="pi {{ r.icon }}" [style.color]="r.color"></i> {{ r.label }}</span>
                          </ng-template>
                          <ng-template let-r pTemplate="item">
                            <span><i class="pi {{ r.icon }}" [style.color]="r.color"></i> {{ r.label }}</span>
                          </ng-template>
                        </p-select>
                        <label class="step-attach">
                          <p-checkbox formControlName="requiresAttachment" [binary]="true" />
                          <span>Pièce jointe requise</span>
                        </label>
                      </div>
                    }
                  } @else {
                    <div class="step-view">
                      <div class="step-view-name">
                        <span>{{ s.name }}</span>
                        <code class="step-view-key">{{ s.stepKey }}</code>
                      </div>
                      <div class="step-view-role">
                        <i class="pi {{ roleV(s.assigneeRole).icon }}"
                           [style.color]="roleV(s.assigneeRole).color"></i>
                        {{ roleV(s.assigneeRole).label }}
                      </div>
                      @if (s.requiresAttachment) {
                        <i class="pi pi-paperclip step-attach-flag" title="Document requis"></i>
                      }
                    </div>
                  }
                </div>

                <div class="step-actions">
                  @if (s.isEditing) {
                    <button pButton icon="pi pi-check" label="Enregistrer" size="small" severity="success"
                            (click)="saveStep(i)" [loading]="saving()"></button>
                    <button pButton icon="pi pi-times" size="small" severity="secondary" [text]="true"
                            (click)="cancelEditStep(i)"></button>
                  } @else {
                    <button pButton icon="pi pi-pencil" size="small" severity="info" [text]="true"
                            (click)="editStep(i)"></button>
                    <button pButton icon="pi pi-trash" size="small" severity="danger" [text]="true"
                            (click)="removeStep(i)" [loading]="saving()"></button>
                  }
                </div>
              </li>

              @if (i < steps().length - 1) {
                <i class="pi pi-arrow-down step-arrow"></i>
              }
            }
          </ol>

          <!-- Add new step inline -->
          <div class="step-add" [formGroup]="newStepForm">
            <h3><i class="pi pi-plus"></i> Nouvelle étape</h3>
            <div class="step-edit">
              <input pInputText formControlName="name" placeholder="Nom (ex: Demande d'achat)" class="step-input-name" />
              <input pInputText formControlName="stepKey" placeholder="Clé (ex: REQUEST)" class="step-input-key" />
              <p-select [options]="roleOptions" optionValue="code" optionLabel="label"
                        formControlName="assigneeRole" placeholder="Rôle">
                <ng-template let-r pTemplate="selectedItem">
                  <span><i class="pi {{ r.icon }}" [style.color]="r.color"></i> {{ r.label }}</span>
                </ng-template>
                <ng-template let-r pTemplate="item">
                  <span><i class="pi {{ r.icon }}" [style.color]="r.color"></i> {{ r.label }}</span>
                </ng-template>
              </p-select>
              <label class="step-attach">
                <p-checkbox formControlName="requiresAttachment" [binary]="true" />
                <span>Pièce jointe</span>
              </label>
            </div>
            <div class="add-actions">
              <button pButton type="button" label="Ajouter l'étape" icon="pi pi-plus"
                      severity="info" (click)="addStep()" [loading]="saving()"
                      [disabled]="newStepForm.invalid"></button>
              <button pButton type="button" label="Voir le résumé" icon="pi pi-eye" severity="success"
                      [outlined]="true" (click)="step.set(3)"
                      [disabled]="steps().length === 0"></button>
            </div>
          </div>

          <div class="form-actions">
            <button pButton type="button" label="Retour" icon="pi pi-arrow-left" severity="secondary" [text]="true"
                    (click)="step.set(1)"></button>
            <button pButton type="button" label="Voir le résumé" icon="pi pi-arrow-right" severity="success"
                    (click)="step.set(3)" [disabled]="steps().length === 0"></button>
          </div>
        </div>
      }

      <!-- ───────── STEP 3 — REVIEW (GRAPH OF CARDS) ───────── -->
      @if (step() === 3) {
        <div class="card">
          <header class="card-head">
            <h2><i class="pi pi-objects-column"></i> Résumé visuel</h2>
            <p>Voici votre processus tel qu'il sera joué. Le responsable voit en un coup d'œil où le workflow peut être bloqué.</p>
          </header>

          <div class="summary-hero"
               [style.--summary-gradient]="typeV(form.controls.key.value).gradient"
               [style.--summary-color]="typeV(form.controls.key.value).color"
               [style.--summary-soft]="typeV(form.controls.key.value).soft">
            <div class="summary-icon"><i class="pi {{ typeV(form.controls.key.value).icon }}"></i></div>
            <div class="summary-text">
              <div class="summary-name">{{ form.controls.name.value || 'Sans nom' }}</div>
              <div class="summary-key">{{ form.controls.key.value || '—' }}</div>
            </div>
            <div class="summary-stat">
              <i class="pi pi-list"></i>
              <span>{{ steps().length }} étape(s)</span>
            </div>
          </div>

          <div class="graph">
            @for (s of steps(); track s.id || s.stepOrder; let i = $index) {
              <article class="graph-card" [style.--role-color]="roleV(s.assigneeRole).color"
                       [style.--role-soft]="roleV(s.assigneeRole).soft">
                <header class="graph-card-head">
                  <div class="graph-step-num">{{ i + 1 }}</div>
                  <i class="pi {{ roleV(s.assigneeRole).icon }} graph-role-icon"></i>
                </header>
                <div class="graph-step-name">{{ s.name }}</div>
                <div class="graph-step-key"><code>{{ s.stepKey }}</code></div>
                <div class="graph-step-role">
                  {{ roleV(s.assigneeRole).label }}
                </div>
                @if (s.requiresAttachment) {
                  <div class="graph-attach">
                    <i class="pi pi-paperclip"></i> Pièce requise
                  </div>
                }
              </article>

              @if (i < steps().length - 1) {
                <div class="graph-connector">
                  <i class="pi pi-arrow-right"></i>
                </div>
              }
            }
            @if (steps().length === 0) {
              <div class="graph-empty">
                <i class="pi pi-inbox"></i>
                <p>Aucune étape définie.</p>
              </div>
            }
          </div>

          <!-- Owner-blocking info panel -->
          <aside class="owner-tips">
            <div class="tip-icon"><i class="pi pi-bolt"></i></div>
            <div>
              <h3>Astuce visibilité owner</h3>
              <ul>
                <li>Chaque carte montre le rôle responsable — l'owner sait QUI bloque.</li>
                <li>Les documents requis sont signalés par l'icône <i class="pi pi-paperclip"></i> sur la carte.</li>
                <li>Les étapes sont jouées séquentiellement — le point d'arrêt reste visible jusqu'à validation.</li>
              </ul>
            </div>
          </aside>

          <div class="form-actions">
            <button pButton type="button" label="Retour aux étapes" icon="pi pi-arrow-left" severity="secondary" [text]="true"
                    (click)="step.set(2)"></button>
            <button pButton type="button" label="Terminer" icon="pi pi-check" severity="success"
                    (click)="finish()" [loading]="saving()"></button>
          </div>
        </div>
      }
    </section>
  `,
})
export class ProcessBuilderComponent implements OnInit {
  private fb = inject(FormBuilder);
  router = inject(Router);
  private route = inject(ActivatedRoute);
  private processService = inject(ProcessService);
  private msgs = inject(MessageService);

  // Wizard state
  step = signal<1 | 2 | 3>(1);
  saving = signal(false);

  // Template identity
  templateId = signal<string>('');
  isEdit = computed(() => !!this.templateId());

  form = this.fb.nonNullable.group({
    key: ['', [Validators.required, Validators.pattern(/^[A-Z][A-Z0-9_]*$/)]],
    name: ['', [Validators.required, Validators.minLength(3)]],
    active: [true],
  });

  // Steps list (each has its own FormGroup when editing)
  steps = signal<DraftStep[]>([]);

  // Per-step FormGroup instances — only populated while editing that step.
  // Keyed by index for stable access; cleared when leaving edit mode.
  stepForms = signal<Record<number, FormGroup>>({});

  getStepForm = (i: number): FormGroup | null => this.stepForms()[i] ?? null;

  // Inline "new step" form
  newStepForm = this.fb.nonNullable.group({
    name: ['', [Validators.required, Validators.minLength(3)]],
    stepKey: ['', [Validators.required, Validators.pattern(/^[A-Z][A-Z0-9_]*$/)]],
    assigneeRole: ['', Validators.required],
    requiresAttachment: [false],
  });

  // Reference data
  roleOptions = ROLE_OPTIONS;
  categoryOptions: { key: string; label: string; icon: string; color: string; description: string }[] =
    Object.entries(PROCESS_TYPE_VISUAL).map(([k, v]) => ({
      key: k, label: v.label, icon: v.icon, color: v.color, description: v.description
    }));

  // Helpers exposed to template
  typeV = processTypeVisual;
  roleV = roleVisual;

  ngOnInit(): void {
    const id = this.route.snapshot.paramMap.get('id');
    if (id) {
      this.templateId.set(id);
      this.loadTemplate(id);
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  STEP 1 — Identity
  // ══════════════════════════════════════════════════════════════

  onCategoryChange(): void {
    // If the name is empty or default for a previous category, suggest a name
    const key = this.form.controls.key.value;
    if (!key) return;
    const tv = processTypeVisual(key);
    if (!this.form.controls.name.value || this.isDefaultName(this.form.controls.name.value)) {
      this.form.controls.name.setValue(tv.label);
    }
  }

  private isDefaultName(name: string): boolean {
    return Object.values(PROCESS_TYPE_VISUAL).some(v => v.label === name);
  }

  saveIdentity(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.msgs.add({ severity: 'warn', summary: 'Champs requis', detail: 'Vérifiez le nom et la clé' });
      return;
    }
    this.saving.set(true);
    const body = {
      key: this.form.controls.key.value,
      name: this.form.controls.name.value,
    };

    if (this.templateId()) {
      // Edit mode — update name/active
      this.processService.updateTemplate(this.templateId(), {
        name: this.form.controls.name.value,
        active: this.form.controls.active.value,
      }).subscribe({
        next: (updated) => {
          this.saving.set(false);
          if (updated) {
            this.msgs.add({ severity: 'success', summary: 'Mis à jour', detail: 'Processus mis à jour' });
            this.step.set(2);
          }
        },
        error: () => this.saving.set(false),
      });
    } else {
      // Create mode
      this.processService.createTemplate(body).subscribe({
        next: (created) => {
          this.saving.set(false);
          if (created && created.id) {
            this.templateId.set(created.id);
            // Backend may return the original key (if conflict) — sync form
            if (created.key) this.form.controls.key.setValue(created.key);
            this.msgs.add({ severity: 'success', summary: 'Processus créé', detail: 'Passez aux étapes' });
            this.step.set(2);
          }
        },
        error: () => this.saving.set(false),
      });
    }
  }

  // ══════════════════════════════════════════════════════════════
  //  STEP 2 — Steps editor
  // ══════════════════════════════════════════════════════════════

  editStep(index: number): void {
    this.steps.update(list => list.map((s, i) => i === index ? { ...s, isEditing: true } : s));
    // Create a dedicated FormGroup for this row, seeded with current step values.
    const step = this.steps()[index];
    const fg = this.fb.nonNullable.group({
      name: [step.name, [Validators.required, Validators.minLength(3)]],
      stepKey: [step.stepKey, [Validators.required, Validators.pattern(/^[A-Z][A-Z0-9_]*$/)]],
      assigneeRole: [step.assigneeRole, Validators.required],
      requiresAttachment: [step.requiresAttachment],
    });
    this.stepForms.update(m => ({ ...m, [index]: fg }));
  }

  cancelEditStep(index: number): void {
    this.steps.update(list => list.map((s, i) => i === index ? { ...s, isEditing: false } : s));
    this.stepForms.update(m => {
      const copy = { ...m };
      delete copy[index];
      return copy;
    });
  }

  saveStep(index: number): void {
    const fg = this.stepForms()[index];
    if (!fg || fg.invalid) {
      fg?.markAllAsTouched();
      this.msgs.add({ severity: 'warn', summary: 'Champs invalides', detail: 'Vérifiez l\'étape' });
      return;
    }
    const step = this.steps()[index];
    const v = fg.value as { name: string; stepKey: string; assigneeRole: string; requiresAttachment: boolean };
    const body = {
      name: v.name,
      assigneeRole: v.assigneeRole,
      requiresAttachment: v.requiresAttachment,
      stepOrder: step.stepOrder,
    };

    this.saving.set(true);
    this.processService.updateStep(this.templateId(), step.id!, body).subscribe({
      next: (updated) => {
        this.saving.set(false);
        if (updated) {
          this.syncStepsFromBackend(updated);
          this.stepForms.update(m => {
            const copy = { ...m };
            delete copy[index];
            return copy;
          });
          this.msgs.add({ severity: 'success', summary: 'Étape mise à jour', detail: body.name });
        }
      },
      error: () => this.saving.set(false),
    });
  }

  removeStep(index: number): void {
    const step = this.steps()[index];
    if (!step.id) {
      this.steps.update(list => list.filter((_, i) => i !== index));
      return;
    }
    this.saving.set(true);
    this.processService.deleteStep(this.templateId(), step.id).subscribe({
      next: (ok) => {
        this.saving.set(false);
        if (ok) {
          this.steps.update(list => list.filter((_, i) => i !== index));
          // Renumber locally (backend already renumbers via unique constraint on step_order)
          this.steps.update(list => list.map((s, i) => ({ ...s, stepOrder: i + 1 })));
          this.msgs.add({ severity: 'success', summary: 'Étape supprimée' });
        }
      },
      error: () => this.saving.set(false),
    });
  }

  addStep(): void {
    if (this.newStepForm.invalid) {
      this.newStepForm.markAllAsTouched();
      this.msgs.add({ severity: 'warn', summary: 'Champs requis', detail: 'Tous les champs sont requis' });
      return;
    }
    if (!this.templateId()) {
      this.msgs.add({ severity: 'error', summary: 'Template non créé', detail: 'Sauvegardez d\'abord l\'identité' });
      this.step.set(1);
      return;
    }
    const body = {
      stepOrder: this.steps().length + 1,
      stepKey: this.newStepForm.controls.stepKey.value,
      name: this.newStepForm.controls.name.value,
      assigneeRole: this.newStepForm.controls.assigneeRole.value,
      requiresAttachment: this.newStepForm.controls.requiresAttachment.value,
    };

    this.saving.set(true);
    this.processService.addStep(this.templateId(), body).subscribe({
      next: (updated) => {
        this.saving.set(false);
        if (updated) {
          this.syncStepsFromBackend(updated);
          this.newStepForm.reset({
            name: '', stepKey: '', assigneeRole: '', requiresAttachment: false,
          });
          this.msgs.add({ severity: 'success', summary: 'Étape ajoutée', detail: body.name });
        }
      },
      error: () => this.saving.set(false),
    });
  }

  private syncStepsFromBackend(detail: TemplateDetail): void {
    const list: DraftStep[] = (detail.steps || []).map((s: TemplateStep) => ({
      id: s.id,
      stepOrder: s.stepOrder,
      stepKey: s.stepKey,
      name: s.name,
      assigneeRole: s.assigneeRole,
      requiresAttachment: s.requiresAttachment,
      isEditing: false,
      isDirty: false,
    }));
    this.steps.set(list);
  }

  // ══════════════════════════════════════════════════════════════
  //  STEP 3 — Review
  // ══════════════════════════════════════════════════════════════

  finish(): void {
    this.msgs.add({ severity: 'success', summary: 'Processus prêt', detail: 'Vous pouvez le lier à un chantier' });
    this.router.navigate(['/processus']);
  }

  // ══════════════════════════════════════════════════════════════
  //  Initial load (edit mode)
  // ══════════════════════════════════════════════════════════════

  private loadTemplate(id: string): void {
    this.saving.set(true);
    this.processService.getTemplate(id).subscribe({
      next: (tpl) => {
        this.saving.set(false);
        if (tpl) {
          this.form.controls.key.setValue(tpl.key);
          this.form.controls.name.setValue(tpl.name);
          this.form.controls.active.setValue(tpl.active);
          this.syncStepsFromBackend(tpl);
          this.step.set(2); // Skip directly to steps in edit mode
        }
      },
      error: () => this.saving.set(false),
    });
  }
}
