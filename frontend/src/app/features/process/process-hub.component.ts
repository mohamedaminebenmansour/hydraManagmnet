import { Component, OnInit, inject, signal, computed } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { MessageService } from 'primeng/api';
import { ProcessService } from '@app/core/services/process.service';
import { AuthService } from '@app/core/auth.service';
import { TemplateDetail } from '@app/core/models/process.model';
import { processTypeVisual, roleVisual } from '@app/shared/process-visual';
import { LoadErrorComponent } from '@app/shared/load-error.component';

@Component({
  selector: 'app-process-hub',
  standalone: true,
  imports: [ButtonModule, RouterLink, LoadErrorComponent],
  styleUrl: './process-hub.component.scss',
  template: `
    <section class="process-hub">
      <header class="hub-hero">
        <div class="hero-icon"><i class="pi pi-sitemap"></i></div>
        <div class="hero-text">
          <h1>Processus</h1>
          <p>Créez et suivez vos workflows métier : paiement, sous-traitance, achat, réception chantier…</p>
        </div>
        @if (isOwner) {
          <div class="hero-cta">
            <button pButton label="Créer un processus" icon="pi pi-plus"
                    severity="success" size="large"
                    routerLink="/processes/builder"></button>
          </div>
        }
      </header>

      <nav class="hub-quicknav">
        <a routerLink="/mes-taches" class="quicknav-card">
          <i class="pi pi-check-square"></i>
          <div>
            <div class="qn-title">Mes tâches</div>
            <div class="qn-desc">Vos étapes en attente</div>
          </div>
        </a>
        <a routerLink="/processes/start" class="quicknav-card">
          <i class="pi pi-play"></i>
          <div>
            <div class="qn-title">Lancer une instance</div>
            <div class="qn-desc">Démarrer un processus sur un chantier</div>
          </div>
        </a>
        @if (isOwner) {
          <a routerLink="/processes/builder" class="quicknav-card">
            <i class="pi pi-sitemap"></i>
            <div>
              <div class="qn-title">Constructeur</div>
              <div class="qn-desc">Créer / modifier un modèle</div>
            </div>
          </a>
        }
      </nav>

      @if (isOwner && readyTemplates().length > 0) {
        <section class="hub-ready">
          <div class="section-head">
            <h2><i class="pi pi-bookmark"></i> Modèles prêts</h2>
            <p>Utilisez un modèle système comme point de départ : une copie est créée dans votre organisation.</p>
          </div>
          <div class="ready-grid">
            @for (tpl of readyTemplates(); track tpl.id) {
              <article class="ready-card"
                       [style.--card-color]="typeV(tpl.key).color"
                       [style.--card-soft]="typeV(tpl.key).soft">
                <div class="ready-icon"><i class="pi {{ typeV(tpl.key).icon }}"></i></div>
                <div class="ready-body">
                  <div class="ready-name">{{ tpl.name }}</div>
                  <div class="ready-key">{{ tpl.key }}</div>
                </div>
                <button pButton label="Utiliser" icon="pi pi-copy" size="small" severity="success"
                        (click)="useTemplate(tpl)" [loading]="copyingId() === tpl.id"></button>
              </article>
            }
          </div>
        </section>
      }

      <section class="hub-templates">
        <div class="section-head">
          <h2><i class="pi pi-objects-column"></i> Modèles de processus</h2>
          @if (isOwner) {
            <button pButton label="Nouveau" icon="pi pi-plus" size="small"
                    severity="success" [outlined]="true"
                    routerLink="/processes/builder"></button>
          }
        </div>

        @if (loading()) {
          <div class="loading-state"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
        } @else if (loadError()) {
          <app-load-error message="Impossible de charger les processus." (retry)="loadTemplates()" />
        } @else if (templates().length === 0) {
          <div class="empty-state">
            <i class="pi pi-sitemap"></i>
            <h3>Aucun processus défini</h3>
            <p>Créez votre premier modèle de processus métier en quelques clics.</p>
            @if (isOwner) {
              <button pButton label="Créer mon premier processus" icon="pi pi-plus"
                      severity="success" routerLink="/processes/builder"></button>
            }
          </div>
        } @else {
          <div class="template-grid">
            @for (tpl of templates(); track tpl.id) {
              <article class="template-card"
                       [class.inactive]="!tpl.active"
                       [style.--card-color]="typeV(tpl.key).color"
                       [style.--card-soft]="typeV(tpl.key).soft"
                       [style.--card-gradient]="typeV(tpl.key).gradient">
                <div class="card-band"></div>
                <div class="card-body">
                  <header class="card-head">
                    <div class="card-icon"><i class="pi {{ typeV(tpl.key).icon }}"></i></div>
                    <div class="card-meta">
                      <div class="card-name">{{ tpl.name }}</div>
                      <div class="card-key">{{ tpl.key }}</div>
                    </div>
                    <div class="card-state">
                      @if (tpl.active) {
                        <span class="state-pill active"><i class="pi pi-circle-fill"></i> Actif</span>
                      } @else {
                        <span class="state-pill inactive"><i class="pi pi-circle"></i> Inactif</span>
                      }
                    </div>
                  </header>

                  <div class="card-flow">
                    @for (step of tpl.steps; track step.id; let i = $index) {
                      <div class="flow-step" [style.--step-color]="roleV(step.assigneeRole).color">
                        <div class="flow-step-num">{{ i + 1 }}</div>
                        <div class="flow-step-body">
                          <div class="flow-step-name">{{ step.name }}</div>
                          <div class="flow-step-role">
                            <i class="pi {{ roleV(step.assigneeRole).icon }}"></i>
                            {{ roleV(step.assigneeRole).label }}
                          </div>
                        </div>
                        @if (step.requiresAttachment) {
                          <i class="pi pi-paperclip attach-flag" title="Document requis"></i>
                        }
                      </div>
                      @if (i < tpl.steps.length - 1) {
                        <i class="pi pi-arrow-down flow-arrow"></i>
                      }
                    }
                    @if (tpl.steps.length === 0) {
                      <div class="flow-empty">Aucune étape définie</div>
                    }
                  </div>

                  <footer class="card-foot">
                    <span class="step-count">
                      <i class="pi pi-list"></i> {{ tpl.steps.length }} étape(s)
                    </span>
                    <div class="card-actions">
                      @if (isOwner) {
                        <button pButton icon="pi pi-pencil" label="Modifier" size="small"
                                severity="info" [text]="true"
                                [routerLink]="['/processes/builder', tpl.id]"></button>
                      }
                      <button pButton icon="pi pi-play" label="Lancer" size="small"
                              severity="success" [text]="true"
                              [routerLink]="['/processes/start']"
                              [queryParams]="{ template: tpl.key }"></button>
                    </div>
                  </footer>
                </div>
              </article>
            }
          </div>
        }
      </section>
    </section>
  `,
})
export class ProcessHubComponent implements OnInit {
  private processService = inject(ProcessService);
  private msgs = inject(MessageService);
  private router = inject(Router);
  private auth = inject(AuthService);

  // Builder routes are ownerGuard-protected — hide all builder CTAs for non-owners
  isOwner = this.auth.me()?.isOwner === true;

  templates = signal<TemplateDetail[]>([]);
  loading = signal(true);
  loadError = signal(false);
  copyingId = signal<string | null>(null);

  // System templates (org_id null) with subjectType CASH_PAYMENT — the
  // "Modèles prêts" quick-start section.
  readyTemplates = computed(() =>
    this.templates().filter(t => !t.orgId && t.subjectType === 'CASH_PAYMENT'));

  // Helpers exposed to template
  typeV = processTypeVisual;
  roleV = roleVisual;

  ngOnInit(): void {
    this.loadTemplates();
  }

  /** Copy a system template into an org-scoped one and open it in the builder. */
  useTemplate(tpl: TemplateDetail): void {
    this.copyingId.set(tpl.id);
    this.processService.copyTemplate(tpl.id).subscribe(copy => {
      this.copyingId.set(null);
      if (copy && copy.id) {
        this.msgs.add({ severity: 'success', summary: 'Modèle copié', detail: 'Personnalisez votre copie' });
        this.router.navigate(['/processes/builder', copy.id]);
      }
    });
  }

  loadTemplates(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.processService.listTemplatesAdmin().subscribe({
      next: (list) => {
        this.templates.set(list ?? []);
        this.loading.set(false);
      },
      error: () => {
        this.templates.set([]);
        this.loadError.set(true);
        this.loading.set(false);
      },
    });
  }
}
