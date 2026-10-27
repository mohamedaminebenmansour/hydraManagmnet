import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { DatePipe } from '@angular/common';
import { AuthService } from '../../core/auth.service';
import { PERM } from '../../core/permissions';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { ProjectService } from '../../core/services/project.service';
import { ProcessService } from '../../core/services/process.service';
import { MyTask } from '../../core/models/process.model';
import {
  processTypeVisual, roleVisual, stepStatusVisual,
  typeVisual, statusVisual,
} from '../../shared/hydra-design';

interface Tile {
  label: string;
  icon: string;
  path: string;
  perm: string;
  color: string;
  gradient: string;
  desc: string;
}

const TILES: Tile[] = [
  { label: 'Tâches / Signalements', icon: 'pi-ticket', path: '/tickets', perm: PERM.TICKET_READ, color: '#d97706', gradient: 'linear-gradient(135deg, #fbbf24, #d97706)', desc: 'Problèmes et travaux assignés' },
  { label: 'Chantiers',   icon: 'pi-briefcase', path: '/projects', perm: PERM.PROJECT_READ, color: '#0d9488', gradient: 'linear-gradient(135deg, #2dd4bf, #0d9488)', desc: 'Projets et travaux' },
  { label: 'Processus',   icon: 'pi-sitemap',   path: '/processus', perm: '', color: '#7c3aed', gradient: 'linear-gradient(135deg, #a78bfa, #7c3aed)', desc: 'Workflows métier' },
  { label: 'Utilisateurs', icon: 'pi-users',     path: '/users',   perm: PERM.USER_CREATE, color: '#2563eb', gradient: 'linear-gradient(135deg, #60a5fa, #2563eb)', desc: 'Gérer les accès' },
  { label: 'Rôles',        icon: 'pi-id-card',   path: '/roles',   perm: PERM.ROLE_MANAGE, color: '#0891b2', gradient: 'linear-gradient(135deg, #22d3ee, #0891b2)', desc: 'Configurer les droits' },
  { label: 'Journal',      icon: 'pi-history',    path: '/audit',   perm: PERM.AUDIT_VIEW, color: '#64748b', gradient: 'linear-gradient(135deg, #94a3b8, #64748b)', desc: "Voir l'activité" },
];

@Component({
  selector: 'app-home',
  standalone: true,
  imports: [RouterLink, ButtonModule, DatePipe, EmptyStateComponent],
  template: `
    <section class="home">
      <!-- Hero with greeting + role badge -->
      <header class="home-hero" [style.background]="heroGradient()">
        <div class="hero-inner">
          <div class="hero-avatar">
            <span class="hero-initials">{{ initials() }}</span>
          </div>
          <div class="hero-text">
            <div class="hero-greeting">Bonjour, {{ name() }}</div>
            <div class="hero-role">
              @if (me()?.isOwner) {
                <i class="pi pi-crown"></i> Gérant de l'entreprise
              } @else {
                <i class="pi pi-user"></i> Membre
              }
            </div>
          </div>
          <div class="hero-stats">
            <div class="hs-stat">
              <span class="hs-n">{{ tasks().length }}</span>
              <span class="hs-l">Tâches en attente</span>
            </div>
            <div class="hs-stat">
              <span class="hs-n">{{ blockedProcesses().length }}</span>
              <span class="hs-l">Processus bloqués</span>
            </div>
          </div>
        </div>
      </header>

      <!-- ⚠️ PRIORITY 1: Processus bloqués (red cards first) -->
      @if (blockedProcesses().length > 0) {
        <section class="home-section">
          <header class="hs-head">
            <h2><i class="pi pi-exclamation-triangle" style="color: #dc2626"></i> Processus bloqués</h2>
            <p>Ces processus ont été refusés — votre attention est requise.</p>
          </header>
          <div class="blocked-grid">
            @for (t of blockedProcesses(); track t.instanceId) {
              <a [routerLink]="['/processes', t.instanceId]" class="blocked-card">
                <div class="bc-head">
                  <div class="bc-icon" [style.color]="processTypeColor(t.templateKey)">
                    <i class="pi" [class]="processTypeIcon(t.templateKey)"></i>
                  </div>
                  <div class="bc-info">
                    <div class="bc-name">{{ t.templateName || t.templateKey }}</div>
                    <div class="bc-proj">{{ t.projectName }}</div>
                  </div>
                  <i class="pi pi-times-circle bc-status-icon" style="color: #dc2626"></i>
                </div>
                <div class="bc-step">Refusé à « {{ t.stepName || t.stepKey || '—' }} »</div>
                <div class="bc-cta">Voir le détail <i class="pi pi-angle-right"></i></div>
              </a>
            }
          </div>
        </section>
      }

      <!-- ✅ PRIORITY 2: Mes tâches en attente -->
      @if (tasks().length > 0) {
        <section class="home-section">
          <header class="hs-head">
            <h2><i class="pi pi-check-square" style="color: #f59e0b"></i> Mes actions en attente</h2>
            <p>Cliquez sur une carte pour valider ou rejeter.</p>
          </header>
          <div class="task-grid">
            @for (t of tasks(); track t.stepInstanceId) {
              <a [routerLink]="['/processes', t.instanceId]" class="task-card"
                 [style.--role-color]="roleColor(t.assigneeRole)">
                <div class="tc-head">
                  <div class="tc-step-num">{{ t.startedAt ? '⏰' : '·' }}</div>
                  <div class="tc-step-name">{{ t.stepName || t.stepKey }}</div>
                </div>
                <div class="tc-template">
                  <i class="pi" [class]="processTypeIcon(t.templateKey)"
                     [style.color]="processTypeColor(t.templateKey)"></i>
                  {{ t.templateName || t.templateKey }}
                </div>
                <div class="tc-project">
                  <i class="pi pi-folder"></i> {{ t.projectName || '—' }}
                </div>
                <div class="tc-responsible">
                  <i class="pi" [class]="roleIcon(t.assigneeRole)"></i>
                  <span>{{ t.assigneeName || roleLabel(t.assigneeRole) }}</span>
                </div>
                @if (t.requiresAttachment) {
                  <span class="tc-attach"><i class="pi pi-paperclip"></i> Doc requis</span>
                }
                <div class="tc-cta">Agir <i class="pi pi-arrow-right"></i></div>
              </a>
            }
          </div>
        </section>
      } @else {
        <section class="home-section">
          <div class="empty-good">
            <i class="pi pi-check-circle"></i>
            <h3>Aucune tâche en attente</h3>
            <p>Tous vos processus sont à jour — rien à traiter maintenant.</p>
          </div>
        </section>
      }

      <!-- 📋 PRIORITY 3: Chantiers actifs -->
      @if (showProjects()) {
        <section class="home-section">
          <header class="hs-head">
            <h2><i class="pi pi-briefcase" style="color: #0d9488"></i> Chantiers actifs</h2>
            <a [routerLink]="['/projects']" class="hs-link">Voir tout <i class="pi pi-angle-right"></i></a>
          </header>
          <div class="chantier-grid">
            @for (p of recentProjects(); track p.id) {
              <a [routerLink]="['/projects', p.id]" class="chantier-card"
                 [style.--c-gradient]="typeV(p.projectType).gradient"
                 [style.--c-color]="typeV(p.projectType).color">
                <div class="cc-band"></div>
                <div class="cc-body">
                  <div class="cc-icon">
                    <i class="pi" [class]="typeV(p.projectType).icon"></i>
                  </div>
                  <div class="cc-info">
                    <div class="cc-name">{{ p.name }}</div>
                    <div class="cc-meta">
                      <span class="cc-type">{{ typeV(p.projectType).label }}</span>
                      @if (p.code) { <span class="cc-code">{{ p.code }}</span> }
                    </div>
                  </div>
                  <span class="cc-status">
                    <i class="pi" [class]="statusV(p.status).icon"
                       [style.color]="statusV(p.status).color"></i>
                  </span>
                </div>
              </a>
            } @empty {
              <div class="empty-good">
                <i class="pi pi-folder"></i>
                <h3>Aucun chantier</h3>
                <p>Créez votre premier chantier pour démarrer.</p>
                <button pButton label="Créer un chantier" icon="pi pi-plus"
                        severity="success" [routerLink]="['/projects']"></button>
              </div>
            }
          </div>
        </section>
      }

      <!-- 🧭 PRIORITY 4: Navigation rapide -->
      <section class="home-section">
        <header class="hs-head">
          <h2><i class="pi pi-compass" style="color: #0ea5e9"></i> Navigation rapide</h2>
        </header>
        <div class="tiles">
          @for (t of tiles(); track t.path) {
            <a class="tile" [routerLink]="t.path" [style.--tile-color]="t.color">
              <div class="tile-icon-disc" [style.background]="t.gradient">
                <i class="pi" [class]="t.icon"></i>
              </div>
              <div class="tile-text">
                <div class="t-label">{{ t.label }}</div>
                <div class="t-desc">{{ t.desc }}</div>
              </div>
              <i class="pi pi-angle-right tile-arrow"></i>
            </a>
          } @empty {
            <app-empty-state icon="pi pi-lock"
              message="Aucun module ne vous est encore attribué. Demandez au gérant de vous donner un accès.">
            </app-empty-state>
          }
        </div>
      </section>
    </section>
  `,
  styles: [`
    .home {
      max-width: 1280px;
      margin: 0 auto;
      padding: 4px;
      display: flex; flex-direction: column; gap: 28px;
      font-family: 'Inter', system-ui, -apple-system, sans-serif;
      color: #0f172a;
    }

    /* HERO */
    .home-hero {
      border-radius: 18px;
      padding: 28px 32px;
      color: #fff;
      background: linear-gradient(135deg, #1e293b 0%, #0f172a 100%);
      box-shadow: 0 12px 28px -10px rgba(15, 23, 42, 0.35);
    }
    .hero-inner {
      display: grid;
      grid-template-columns: auto 1fr auto;
      gap: 22px; align-items: center;
    }
    .hero-avatar {
      width: 64px; height: 64px;
      border-radius: 16px;
      background: rgba(255, 255, 255, 0.15);
      display: grid; place-items: center;
      font-size: 28px;
    }
    .hero-greeting { font-size: 26px; font-weight: 800; letter-spacing: -0.02em; }
    .hero-role {
      margin-top: 4px;
      font-size: 14px;
      color: rgba(255, 255, 255, 0.85);
      display: inline-flex; align-items: center; gap: 6px;
    }
    .hero-stats { display: flex; gap: 18px; }
    .hs-stat {
      background: rgba(255, 255, 255, 0.1);
      padding: 10px 16px;
      border-radius: 12px;
      text-align: center;
      min-width: 90px;
    }
    .hs-n { font-size: 22px; font-weight: 800; }
    .hs-l { font-size: 11px; color: rgba(255, 255, 255, 0.75); margin-top: 2px; }

    /* SECTIONS */
    .home-section {}
    .hs-head {
      display: flex; justify-content: space-between; align-items: baseline;
      margin-bottom: 12px;
    }
    .hs-head h2 {
      margin: 0; font-size: 18px; font-weight: 700;
      display: inline-flex; align-items: center; gap: 8px;
    }
    .hs-head p { margin: 0; color: #64748b; font-size: 13px; }
    .hs-link {
      color: #0ea5e9; text-decoration: none; font-size: 13px; font-weight: 600;
      display: inline-flex; align-items: center; gap: 4px;
      &:hover { text-decoration: underline; }
    }

    /* BLOCKED CARDS (PRIORITY 1) */
    .blocked-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
      gap: 12px;
    }
    .blocked-card {
      background: #fff;
      border: 1px solid #fca5a5;
      border-left: 5px solid #dc2626;
      border-radius: 12px;
      padding: 14px 16px;
      text-decoration: none; color: inherit;
      display: flex; flex-direction: column; gap: 8px;
      transition: all .15s ease;
      box-shadow: 0 0 0 2px rgba(220, 38, 38, 0.08);
      &:hover { transform: translateY(-2px); box-shadow: 0 10px 24px -10px rgba(220, 38, 38, 0.3); }
    }
    .bc-head {
      display: grid;
      grid-template-columns: 36px 1fr auto;
      gap: 12px; align-items: center;
    }
    .bc-icon {
      width: 36px; height: 36px;
      border-radius: 10px;
      background: #fef2f2;
      display: grid; place-items: center;
      font-size: 18px;
    }
    .bc-name { font-weight: 700; font-size: 14px; }
    .bc-proj { font-size: 12px; color: #64748b; }
    .bc-status-icon { font-size: 22px; justify-self: end; }
    .bc-step {
      font-size: 13px; color: #dc2626; font-weight: 600;
      background: #fef2f2; padding: 6px 10px; border-radius: 6px;
    }
    .bc-cta {
      font-size: 12px; color: #dc2626; font-weight: 700;
      display: flex; align-items: center; gap: 4px;
    }

    /* TASK CARDS */
    .task-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
      gap: 12px;
    }
    .task-card {
      background: #fff;
      border: 1px solid #e2e8f0;
      border-left: 5px solid var(--role-color, #64748b);
      border-radius: 12px;
      padding: 14px 16px;
      text-decoration: none; color: inherit;
      display: flex; flex-direction: column; gap: 8px;
      transition: all .15s ease;
      &:hover { transform: translateY(-2px); box-shadow: 0 10px 24px -10px rgba(15, 23, 42, 0.18); }
    }
    .tc-head {
      display: grid; grid-template-columns: 28px 1fr;
      gap: 10px; align-items: center;
    }
    .tc-step-num {
      font-size: 18px; text-align: center;
    }
    .tc-step-name { font-weight: 700; font-size: 15px; }
    .tc-template {
      display: inline-flex; align-items: center; gap: 6px;
      font-size: 12px; color: #475569; font-weight: 600;
      background: #f1f5f9; padding: 4px 8px; border-radius: 6px;
      width: fit-content;
    }
    .tc-project {
      font-size: 12px; color: #64748b;
      display: inline-flex; align-items: center; gap: 5px;
    }
    .tc-responsible {
      display: inline-flex; align-items: center; gap: 8px;
      background: #f8fafc; padding: 6px 10px; border-radius: 8px;
      font-size: 13px; color: var(--role-color, #64748b); font-weight: 600;
      i { color: var(--role-color, #64748b); }
    }
    .tc-attach {
      font-size: 11px; color: #ca8a04;
      background: #fefce8;
      padding: 3px 8px; border-radius: 6px;
      display: inline-flex; align-items: center; gap: 4px;
      width: fit-content;
    }
    .tc-cta {
      margin-top: auto;
      font-size: 13px; color: #0ea5e9; font-weight: 700;
      display: inline-flex; align-items: center; gap: 4px;
    }

    /* EMPTY GOOD */
    .empty-good {
      background: #f0fdf4;
      border: 1px solid #bbf7d0;
      border-radius: 12px;
      padding: 28px;
      text-align: center;
      i { font-size: 36px; color: #16a34a; }
      h3 { margin: 6px 0 4px; font-size: 16px; }
      p { margin: 0; color: #475569; font-size: 13px; }
      button { margin-top: 14px; }
    }

    /* CHANTIER GRID */
    .chantier-grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
      gap: 12px;
    }
    .chantier-card {
      background: #fff;
      border: 1px solid #e2e8f0;
      border-radius: 12px;
      overflow: hidden;
      text-decoration: none; color: inherit;
      transition: all .15s ease;
      &:hover { transform: translateY(-2px); box-shadow: 0 10px 24px -10px rgba(15, 23, 42, 0.18); }
    }
    .cc-band { height: 6px; background: var(--c-gradient, linear-gradient(90deg, #94a3b8, #64748b)); }
    .cc-body {
      display: grid; grid-template-columns: 38px 1fr auto;
      gap: 10px; align-items: center;
      padding: 12px 14px;
    }
    .cc-icon {
      width: 38px; height: 38px;
      border-radius: 10px;
      background: color-mix(in srgb, var(--c-color, #0ea5e9) 12%, white);
      color: var(--c-color, #0ea5e9);
      display: grid; place-items: center;
      font-size: 18px;
    }
    .cc-name { font-weight: 700; font-size: 14px; line-height: 1.2; }
    .cc-meta {
      display: flex; gap: 8px; margin-top: 2px;
      font-size: 11px; color: #64748b;
    }
    .cc-code { font-family: 'JetBrains Mono', monospace; }
    .cc-status { font-size: 18px; }

    /* TILES */
    .tiles {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 12px;
    }
    .tile {
      background: #fff;
      border: 1px solid #e2e8f0;
      border-radius: 12px;
      padding: 16px 18px;
      text-decoration: none; color: inherit;
      display: grid;
      grid-template-columns: 44px 1fr auto;
      gap: 14px; align-items: center;
      transition: all .15s ease;
      &:hover { transform: translateY(-2px); box-shadow: 0 10px 24px -10px rgba(15, 23, 42, 0.18); }
    }
    .tile-icon-disc {
      width: 44px; height: 44px;
      border-radius: 12px;
      display: grid; place-items: center;
      font-size: 22px; color: #fff;
    }
    .t-label { font-weight: 700; font-size: 15px; }
    .t-desc { color: #64748b; font-size: 12px; }
    .tile-arrow { color: #cbd5e1; font-size: 14px; }

    @media (max-width: 720px) {
      .hero-inner { grid-template-columns: 1fr; text-align: center; }
      .hero-stats { justify-content: center; }
    }
  `]
})
export class HomeComponent implements OnInit {
  private auth = inject(AuthService);
  private projectService = inject(ProjectService);
  private processService = inject(ProcessService);

  name = computed(() => this.auth.me()?.fullName ?? '');
  me = this.auth.me;
  initials = computed(() => {
    const n = this.auth.me()?.fullName ?? '';
    if (!n) return '?';
    const parts = n.trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) return '?';
    if (parts.length === 1) return parts[0].substring(0, 2).toUpperCase();
    return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
  });
  heroGradient = computed(() => {
    return this.auth.me()?.isOwner
      ? 'linear-gradient(135deg, #f59e0b 0%, #b45309 50%, #1e293b 100%)'
      : 'linear-gradient(135deg, #0ea5e9 0%, #2563eb 50%, #1e293b 100%)';
  });

  showProjects = computed(() => this.auth.can(PERM.PROJECT_READ));
  tasks = signal<MyTask[]>([]);
  recentProjects = signal<any[]>([]);

  tiles = computed(() => TILES.filter(t => !t.perm || this.auth.can(t.perm)));

  // Helpers
  typeV = typeVisual;
  statusV = statusVisual;
  processTypeIcon = (key: string | undefined) => processTypeVisual(key).icon;
  processTypeColor = (key: string | undefined) => processTypeVisual(key).color;
  roleIcon = (role: string | undefined) => roleVisual(role).icon;
  roleColor = (role: string | undefined) => roleVisual(role).color;
  roleLabel = (role: string | undefined) => roleVisual(role).label;

  blockedProcesses = computed(() => {
    return this.tasks().filter(t => (t.status ?? '').toUpperCase() === 'REJECTED');
  });

  ngOnInit(): void {
    // Load my tasks (PENDING + REJECTED — owner sees both)
    this.processService.myTasks().subscribe({
      next: list => this.tasks.set(list ?? []),
      error: () => this.tasks.set([]),
    });

    if (this.showProjects()) {
      this.projectService.options().subscribe({
        next: list => this.recentProjects.set((list ?? []).slice(0, 6)),
        error: () => this.recentProjects.set([]),
      });
    }
  }
}
