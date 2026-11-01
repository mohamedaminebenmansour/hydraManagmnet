import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router, ActivatedRoute, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { MessageService } from 'primeng/api';
import { ProcessService } from '@app/core/services/process.service';
import { ProjectService } from '@app/core/services/project.service';
import { UserService } from '@app/core/services/user.service';
import { UserDto } from '@app/core/models/user.model';
import { TemplateDetail } from '@app/core/models/process.model';
import { processTypeVisual, roleVisual } from '@app/shared/process-visual';
import { LoadErrorComponent } from '@app/shared/load-error.component';

interface ProjectOption {
  id: string;
  name: string;
  code: string;
  projectType: string;
}

interface SubjectTypeOption {
  value: string;
  label: string;
  icon: string;
}

@Component({
  selector: 'app-start-process',
  standalone: true,
  imports: [ButtonModule, InputTextModule, SelectModule, ReactiveFormsModule, RouterLink, LoadErrorComponent],
  styleUrl: './start-process.component.scss',
  template: `
    <section class="start-proc">
      <nav class="bc">
        <a routerLink="/processus"><i class="pi pi-sitemap"></i> Processus</a>
        <i class="pi pi-angle-right"></i>
        <span>Lancer une instance</span>
      </nav>

      <header class="sp-hero" [style.--hero-gradient]="currentTypeV().gradient">
        <div class="hero-icon"><i class="pi {{ currentTypeV().icon }}"></i></div>
        <div class="hero-text">
          <h1>Lancer un processus</h1>
          <p>Choisissez un chantier, un modèle de processus, puis démarrez le workflow. Les responsables sont automatiquement résolus depuis l'équipe du projet.</p>
        </div>
      </header>

      <form [formGroup]="form" (ngSubmit)="onSubmit()" class="sp-form">
        <!-- 1) Chantier -->
        <div class="sp-section">
          <h2><span class="sp-num">1</span> Chantier</h2>
          <p>Sur quel projet ce processus doit-il s'exécuter ?</p>
          @if (projectsError()) {
            <app-load-error message="Impossible de charger les chantiers." (retry)="loadProjects()" />
          }
          <div class="field">
            <p-select [options]="projectOptions()" optionValue="id"
                      optionLabel="name" formControlName="projectId"
                      placeholder="Sélectionner un chantier"
                      (onChange)="onProjectChange()"
                      styleClass="w-100">
              <ng-template let-p pTemplate="selectedItem">
                <span class="proj-sel">
                  <i class="pi" [class]="projIcon(p?.projectType)"></i>
                  <span class="proj-name">{{ p?.name }}</span>
                  @if (p?.code) { <code class="proj-code">{{ p?.code }}</code> }
                </span>
              </ng-template>
              <ng-template let-p pTemplate="item">
                <span class="proj-sel">
                  <i class="pi" [class]="projIcon(p?.projectType)"></i>
                  <span class="proj-name">{{ p?.name }}</span>
                  @if (p?.code) { <code class="proj-code">{{ p?.code }}</code> }
                </span>
              </ng-template>
            </p-select>
          </div>
        </div>

        <!-- 2) Template -->
        <div class="sp-section">
          <h2><span class="sp-num">2</span> Modèle de processus</h2>
          <p>Quel workflow déclencher ? Seuls les modèles actifs sont listés.</p>
          @if (templatesError()) {
            <app-load-error message="Impossible de charger les modèles de processus." (retry)="loadTemplates()" />
          }
          <div class="field">
            <p-select [options]="templateOptions()" optionValue="key"
                      optionLabel="name" formControlName="templateKey"
                      placeholder="Sélectionner un modèle"
                      (onChange)="onTemplateChange()"
                      styleClass="w-100"
                      [disabled]="!form.controls.projectId.value">
              <ng-template let-t pTemplate="selectedItem">
                <span class="tpl-sel">
                  <i class="pi {{ typeV(t?.key).icon }}" [style.color]="typeV(t?.key).color"></i>
                  {{ t?.name }}
                </span>
              </ng-template>
              <ng-template let-t pTemplate="item">
                <span class="tpl-sel">
                  <i class="pi {{ typeV(t?.key).icon }}" [style.color]="typeV(t?.key).color"></i>
                  {{ t?.name }} <small>{{ t?.steps?.length || 0 }} étape(s)</small>
                </span>
              </ng-template>
            </p-select>
            @if (!form.controls.projectId.value) {
              <small class="hint-warn"><i class="pi pi-info-circle"></i> Sélectionnez d'abord un chantier</small>
            }
          </div>
        </div>

        <!-- 3) Sujet -->
        <div class="sp-section">
          <h2><span class="sp-num">3</span> Sujet</h2>
          <p>À quoi ce processus se rapporte-t-il ? (Type + libellé court)</p>
          <div class="field-row">
            <div class="field">
              <label>Type de sujet</label>
              <p-select [options]="subjectTypeOptions" formControlName="subjectType"
                        optionValue="value" optionLabel="label"
                        placeholder="Sélectionner"
                        styleClass="w-100" />
            </div>
            <div class="field">
              <label>Libellé (optionnel)</label>
              <input pInputText formControlName="subjectLabel"
                     placeholder="Ex: Paiement sous-traitant Pipetech Tronçon 3" />
              <small class="hint">Pour vous aider à identifier ce processus plus tard.</small>
            </div>
          </div>
        </div>

        <!-- 4) Aperçu des étapes + responsables -->
        @if (selectedTemplate(); as tpl) {
          <div class="sp-section">
            <h2><span class="sp-num">4</span> Aperçu des étapes + responsables</h2>
            <p>Le workflow sera démarré avec les étapes ci-dessous. Le responsable de chaque étape est automatiquement choisi parmi les membres du chantier ayant le rôle métier correspondant.</p>

            <div class="steps-preview">
              @for (step of tpl.steps; track step.id; let i = $index) {
                <div class="sp-step" [style.--role-color]="roleV(step.assigneeRole).color">
                  <div class="sp-step-num">{{ i + 1 }}</div>
                  <div class="sp-step-body">
                    <div class="sp-step-name">{{ step.name }}</div>
                    <div class="sp-step-meta">
                      <span class="sp-role-pill" [style.--role-soft]="roleV(step.assigneeRole).soft">
                        <i class="pi {{ roleV(step.assigneeRole).icon }}"></i>
                        {{ roleV(step.assigneeRole).label }}
                      </span>
                      @if (step.requiresAttachment) {
                        <span class="sp-attach"><i class="pi pi-paperclip"></i> Doc requis</span>
                      }
                    </div>
                  </div>
                </div>
                @if (i < tpl.steps.length - 1) {
                  <i class="pi pi-arrow-down sp-arrow"></i>
                }
              }
            </div>

            <!-- Project team mapping -->
            @if (projectRoleMap().size > 0) {
              <div class="role-map">
                <h3><i class="pi pi-users"></i> Équipe du chantier par rôle</h3>
                <p>Vérifiez qui est responsable de chaque rôle sur ce chantier.</p>
                <div class="role-map-grid">
                  @for (entry of roleMapEntries(); track entry.role) {
                    <div class="role-map-card" [style.--role-color]="roleV(entry.role).color">
                      <div class="rm-icon"><i class="pi {{ roleV(entry.role).icon }}"></i></div>
                      <div class="rm-body">
                        <div class="rm-role">{{ roleV(entry.role).label }}</div>
                        <div class="rm-people">
                          @for (name of entry.names; track name) {
                            <span class="rm-person">{{ name }}</span>
                          } @empty {
                            <span class="rm-none">Aucun — le workflow restera en attente</span>
                          }
                        </div>
                      </div>
                    </div>
                  }
                </div>
              </div>
            }
          </div>
        }

        <div class="sp-actions">
          <button pButton type="button" label="Annuler" icon="pi pi-times" severity="secondary" [text]="true"
                  routerLink="/processus"></button>
          <button pButton type="submit" label="Lancer le processus" icon="pi pi-play"
                  severity="success" [loading]="submitting()"
                  [disabled]="form.invalid || !selectedTemplate()"></button>
        </div>
      </form>
    </section>
  `,
})
export class StartProcessComponent implements OnInit {
  private fb = inject(FormBuilder);
  private router = inject(Router);
  private route = inject(ActivatedRoute);
  private processService = inject(ProcessService);
  private projectService = inject(ProjectService);
  private userService = inject(UserService);
  private msgs = inject(MessageService);

  submitting = signal(false);
  templates = signal<TemplateDetail[]>([]);
  templatesError = signal(false);
  projectOptions = signal<ProjectOption[]>([]);
  projectsError = signal(false);
  selectedTemplate = signal<TemplateDetail | null>(null);
  projectRoleMap = signal<Map<string, string[]>>(new Map());

  form = this.fb.nonNullable.group({
    projectId: ['', Validators.required],
    templateKey: ['', Validators.required],
    subjectType: ['PAYMENT', Validators.required],
    subjectLabel: [''],
  });

  readonly subjectTypeOptions: SubjectTypeOption[] = [
    { value: 'PAYMENT',   label: 'Paiement',    icon: 'pi-wallet' },
    { value: 'PURCHASE',  label: 'Achat',       icon: 'pi-shopping-cart' },
    { value: 'CONTRACT',  label: 'Contrat',     icon: 'pi-briefcase' },
    { value: 'RECEPTION', label: 'Réception',  icon: 'pi-verified' },
    { value: 'OTHER',     label: 'Autre',       icon: 'pi-sitemap' },
  ];

  // Helpers exposed to template
  typeV = processTypeVisual;
  roleV = roleVisual;

  ngOnInit(): void {
    // Pre-fill from query params (deep link from project detail "Lancer un processus")
    const qp = this.route.snapshot.queryParamMap;
    const projectId = qp.get('projectId');
    const templateKey = qp.get('template');
    if (projectId) this.form.controls.projectId.setValue(projectId);
    if (templateKey) this.form.controls.templateKey.setValue(templateKey);

    this.loadProjects();
    this.loadTemplates();
  }

  /** Available templates (active only) — to be filtered by project enablement later. */
  templateOptions = computed(() =>
    this.templates().filter(t => t.active).map(t => ({
      key: t.key,
      name: t.name,
      steps: t.steps,
    }))
  );

  currentTypeV = computed(() => {
    const key = this.form.controls.templateKey.value;
    return processTypeVisual(key);
  });

  /** Pretty icon for a project type (uses chantier visual system). */
  projIcon(t: string | undefined): string {
    return 'pi pi-folder';
  }

  roleMapEntries = computed(() => {
    const map = this.projectRoleMap();
    const entries: { role: string; names: string[] }[] = [];
    map.forEach((names, role) => entries.push({ role, names }));
    return entries.sort((a, b) => a.role.localeCompare(b.role));
  });

  loadProjects(): void {
    this.projectsError.set(false);
    this.projectService.options().subscribe({
      next: (list: any[]) => {
        this.projectOptions.set((list ?? []).map(p => ({
          id: p.id ?? p.value,
          name: p.name ?? p.label,
          code: p.code ?? '',
          projectType: p.projectType ?? p.project_type ?? '',
        })));
        if (this.form.controls.projectId.value) {
          this.onProjectChange();
        }
      },
      error: () => { this.projectOptions.set([]); this.projectsError.set(true); },
    });
  }

  loadTemplates(): void {
    this.templatesError.set(false);
    this.processService.listTemplatesAdmin().subscribe({
      next: (list) => {
        this.templates.set(list ?? []);
        if (this.form.controls.templateKey.value) {
          this.onTemplateChange();
        }
      },
      error: () => { this.templates.set([]); this.templatesError.set(true); },
    });
  }

  onProjectChange(): void {
    const projectId = this.form.controls.projectId.value;
    if (!projectId) {
      this.projectRoleMap.set(new Map());
      return;
    }
    // We need both:
    //   - project_member (which users are on this project) — listMembers()
    //   - user_role (each user's business role code) — users.list() returns roleCodes[]
    // We join them client-side because the backend doesn't return business roles
    // in the listMembers response.
    this.projectService.listMembers(projectId).subscribe({
      next: (members) => {
        const memberIds = new Set((members ?? []).map(m => m.userId));
        // Fetch user list to get roleCodes per user
        this.userService.list().subscribe({
          next: (users) => {
            const usersById = new Map<string, UserDto>();
            for (const u of users ?? []) usersById.set(u.id, u);
            const map = new Map<string, string[]>();
            for (const m of members ?? []) {
              const u = usersById.get(m.userId);
              if (!u || !u.roleCodes?.length) continue;
              const fullName = u.fullName || u.email || '—';
              for (const code of u.roleCodes) {
                const c = String(code ?? '').toUpperCase();
                if (!c) continue;
                const arr = map.get(c) ?? [];
                if (!arr.includes(fullName)) arr.push(fullName);
                map.set(c, arr);
              }
            }
            this.projectRoleMap.set(map);
          },
          error: () => this.projectRoleMap.set(new Map()),
        });
      },
      error: () => this.projectRoleMap.set(new Map()),
    });
  }

  onTemplateChange(): void {
    const key = this.form.controls.templateKey.value;
    if (!key) {
      this.selectedTemplate.set(null);
      return;
    }
    const tpl = this.templates().find(t => t.key === key);
    this.selectedTemplate.set(tpl ?? null);

    // Auto-set subject type from template category prefix
    const prefix = key.split('_')[0].toUpperCase();
    const subjectTypeMap: Record<string, string> = {
      PAYMENT: 'PAYMENT',
      MATERIAL: 'PURCHASE',
      SUBCONTRACT: 'CONTRACT',
      SITE: 'RECEPTION',
      HSE: 'OTHER',
      DOC: 'OTHER',
    };
    const suggested = subjectTypeMap[prefix] ?? 'OTHER';
    if (this.form.controls.subjectType.value === 'PAYMENT' && suggested !== 'PAYMENT') {
      this.form.controls.subjectType.setValue(suggested);
    }
  }

  onSubmit(): void {
    if (this.form.invalid || !this.selectedTemplate()) {
      this.msgs.add({ severity: 'warn', summary: 'Champs requis', detail: 'Sélectionnez un chantier et un modèle' });
      return;
    }
    this.submitting.set(true);
    // Generate a fresh subjectId (UUID) client-side for the new instance
    const subjectId = (crypto as any).uuid ? (crypto as any).uuid() : this.uuid4();
    this.processService.start({
      projectId: this.form.controls.projectId.value,
      templateKey: this.form.controls.templateKey.value,
      subjectType: this.form.controls.subjectType.value,
      subjectId,
    }).subscribe({
      next: (result) => {
        this.submitting.set(false);
        if (result?.instanceId) {
          this.msgs.add({
            severity: 'success',
            summary: 'Processus démarré',
            detail: 'Redirection vers la timeline…',
          });
          this.router.navigate(['/processes', result.instanceId]);
        }
      },
      error: () => this.submitting.set(false),
    });
  }

  private uuid4(): string {
    // Fallback if crypto.randomUUID isn't available
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
      const r = Math.random() * 16 | 0;
      const v = c === 'x' ? r : (r & 0x3) | 0x8;
      return v.toString(16);
    });
  }
}
