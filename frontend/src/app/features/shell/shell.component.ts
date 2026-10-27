import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { ToastModule } from 'primeng/toast';
import { TooltipModule } from 'primeng/tooltip';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { ProjectService } from '@app/core/services/project.service';
import { ProcessService } from '@app/core/services/process.service';
import { roleVisual, avatarGradient, initials } from '@app/shared/hydra-design';

interface NavItem {
  label: string;
  icon: string;
  path: string;
  perm: string;
  color: string;
  badge?: 'count'; // whether to show a notification count
}

const NAV: NavItem[] = [
  { label: 'Accueil',        icon: 'pi-home',          path: '/',           perm: '',           color: '#0ea5e9' },
  { label: 'Mes tâches',     icon: 'pi-check-square', path: '/mes-taches',  perm: '',           color: '#f59e0b', badge: 'count' },
  { label: 'Trésorerie',     icon: 'pi-wallet',       path: '/tresorerie',  perm: PERM.CASH_READ, color: '#0d9488' },
  { label: 'Processus',      icon: 'pi-sitemap',      path: '/processus',   perm: '',           color: '#7c3aed' },
  { label: 'Chantiers',      icon: 'pi-briefcase',    path: '/projects',    perm: PERM.PROJECT_READ, color: '#0d9488' },
  { label: 'Utilisateurs',   icon: 'pi-users',        path: '/users',       perm: PERM.USER_CREATE, color: '#2563eb' },
  { label: 'Rôles',          icon: 'pi-id-card',      path: '/roles',       perm: PERM.ROLE_MANAGE, color: '#0891b2' },
  { label: 'Journal',        icon: 'pi-history',       path: '/audit',       perm: PERM.AUDIT_VIEW, color: '#64748b' },
];

@Component({
  selector: 'app-shell',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ButtonModule, ToastModule, TooltipModule],
  template: `
    <p-toast position="top-right" />
    <div class="shell">
      <aside>
        <!-- Brand -->
        <div class="brand">
          <div class="brand-disc"><i class="pi pi-shield"></i></div>
          <div class="brand-text">
            <div class="brand-name">HYDRA</div>
            <div class="brand-sub">BTP hydraulique</div>
          </div>
        </div>

        <!-- User identity card with role badge -->
        <div class="user-card" [style.background]="userGradient()">
          <div class="user-avatar">
            <span class="user-initials">{{ userInitials() }}</span>
          </div>
          <div class="user-info">
            <div class="user-name">{{ name() }}</div>
            <div class="user-role">
              @if (me()?.isOwner) {
                <i class="pi pi-crown"></i> Gérant
              } @else {
                <i class="pi pi-user"></i> Membre
              }
            </div>
          </div>
        </div>

        <!-- Navigation (icon-first, color-coded) -->
        <nav>
          @for (item of menu(); track item.path) {
            <a [routerLink]="item.path" routerLinkActive="active"
               [routerLinkActiveOptions]="{ exact: item.path === '/' }"
               [style.--nav-color]="item.color"
               pTooltip="{{ item.label }}" tooltipPosition="right"
               class="nav-item">
              <span class="nav-icon"><i class="pi" [class]="item.icon"></i></span>
              <span class="nav-label">{{ item.label }}</span>
              @if (item.badge === 'count' && taskCount() > 0) {
                <span class="nav-badge">{{ taskCount() }}</span>
              }
            </a>
          }
        </nav>

        <!-- Side projects (chantiers) -->
        @if (showProjects()) {
          <div class="side-projects">
            <div class="side-title">
              <i class="pi pi-briefcase"></i> Mes chantiers
            </div>
            @for (p of projects(); track p.id) {
              <a [routerLink]="['/projects', p.id]" routerLinkActive="active" class="side-proj">
                <i class="pi pi-folder"></i>
                <span class="side-proj-name">{{ p.name }}</span>
              </a>
            }
            <a class="see-all" [routerLink]="['/projects']">
              <i class="pi pi-list"></i> Voir tout
            </a>
          </div>
        }

        <!-- Logout -->
        <div class="side-bottom">
          <button pButton label="Déconnexion" icon="pi pi-power-off" severity="danger"
                  size="small" [text]="true" (click)="logout()" class="logout-btn"></button>
        </div>
      </aside>
      <div class="col">
        <header class="topbar">
          <div class="who">
            <i class="pi pi-bell" [class.has-tasks]="taskCount() > 0"></i>
            @if (taskCount() > 0) {
              <span class="who-tasks">{{ taskCount() }} tâche(s) en attente</span>
            } @else {
              <span class="who-tasks">Aucune tâche en attente</span>
            }
          </div>
        </header>
        <main><router-outlet /></main>
      </div>
    </div>
  `,
  styles: [`
    .shell { display: flex; min-height: 100vh; }
    aside {
      width: 240px;
      background: linear-gradient(180deg, #0f172a 0%, #1e293b 100%);
      color: #fff;
      padding: 18px 12px 12px;
      flex-shrink: 0;
      display: flex; flex-direction: column;
      gap: 14px;
    }

    /* Brand */
    .brand { display: flex; align-items: center; gap: 12px; padding: 4px 8px 12px; }
    .brand-disc {
      width: 38px; height: 38px;
      border-radius: 10px;
      background: linear-gradient(135deg, #0ea5e9, #2563eb);
      display: grid; place-items: center;
      font-size: 18px; color: #fff;
    }
    .brand-name { font-weight: 800; letter-spacing: 2px; font-size: 18px; }
    .brand-sub { font-size: 10px; color: #64748b; text-transform: uppercase; letter-spacing: .08em; }

    /* User card */
    .user-card {
      display: flex; align-items: center; gap: 12px;
      padding: 10px 12px;
      border-radius: 12px;
      color: #fff;
      box-shadow: 0 4px 12px -4px rgba(0,0,0,0.3);
    }
    .user-avatar {
      width: 38px; height: 38px;
      border-radius: 50%;
      background: rgba(255, 255, 255, 0.2);
      display: grid; place-items: center;
      flex-shrink: 0;
    }
    .user-initials { font-weight: 800; font-size: 14px; }
    .user-name { font-weight: 700; font-size: 14px; line-height: 1.1; }
    .user-role {
      font-size: 11px;
      color: rgba(255, 255, 255, 0.85);
      display: inline-flex; align-items: center; gap: 4px;
      margin-top: 2px;
    }

    /* Navigation */
    nav { display: flex; flex-direction: column; gap: 3px; flex: 1; min-height: 0; }
    .nav-item {
      display: flex; align-items: center; gap: 12px;
      color: #cbd5e1; text-decoration: none;
      padding: 11px 12px;
      border-radius: 10px;
      font-size: 14px; font-weight: 500;
      position: relative;
      transition: all .12s ease;
      --nav-color: #0ea5e9;
    }
    .nav-item:hover {
      background: rgba(255, 255, 255, 0.06);
      color: #fff;
    }
    .nav-item.active {
      background: var(--nav-color);
      color: #fff;
      box-shadow: 0 4px 12px -3px color-mix(in srgb, var(--nav-color) 60%, transparent);
    }
    .nav-item.active::before {
      content: ''; position: absolute; left: -12px; top: 8px; bottom: 8px; width: 4px;
      background: var(--nav-color); border-radius: 0 4px 4px 0;
    }
    .nav-icon {
      width: 22px; height: 22px;
      display: grid; place-items: center;
      font-size: 16px;
    }
    .nav-label { flex: 1; }
    .nav-badge {
      background: #dc2626; color: #fff;
      font-size: 11px; font-weight: 700;
      padding: 1px 7px;
      border-radius: 999px;
      min-width: 18px; text-align: center;
    }
    .nav-item.active .nav-badge {
      background: rgba(255, 255, 255, 0.25);
      color: #fff;
    }

    /* Side projects */
    .side-projects {
      padding-top: 10px;
      border-top: 1px solid #1e293b;
      display: flex; flex-direction: column; gap: 2px;
      max-height: 220px; overflow-y: auto;
    }
    .side-title {
      font-size: 11px; font-weight: 700;
      color: #64748b;
      text-transform: uppercase;
      letter-spacing: .08em;
      padding: 6px 12px;
      display: flex; align-items: center; gap: 6px;
      i { color: #64748b; }
    }
    .side-proj {
      color: #cbd5e1; text-decoration: none;
      padding: 8px 12px; border-radius: 8px;
      font-size: 13px;
      display: flex; align-items: center; gap: 8px;
      overflow: hidden;
    }
    .side-proj-name {
      overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
    }
    .side-proj i { color: #64748b; flex-shrink: 0; }
    .side-proj:hover { background: rgba(255, 255, 255, 0.06); color: #fff; }
    .side-proj.active { background: #0d9488; color: #fff; }
    .side-proj.active i { color: #fff; }
    .see-all {
      margin-top: 4px;
      padding: 8px 12px;
      font-size: 12px; font-weight: 700;
      color: #38bdf8; text-decoration: none;
      display: flex; align-items: center; gap: 6px;
      border-radius: 6px;
    }
    .see-all:hover { background: rgba(56, 189, 248, 0.1); }

    /* Side bottom */
    .side-bottom { padding-top: 10px; border-top: 1px solid #1e293b; }
    .logout-btn { width: 100%; }

    /* Topbar */
    .col { flex: 1; display: flex; flex-direction: column; min-width: 0; }
    .topbar {
      background: #fff; border-bottom: 1px solid #e2e8f0;
      padding: 12px 24px;
      display: flex; justify-content: flex-end; align-items: center;
    }
    .who {
      display: flex; align-items: center; gap: 8px;
      font-size: 13px; color: #475569;
    }
    .who i.pi-bell { font-size: 18px; color: #94a3b8; }
    .who i.pi-bell.has-tasks { color: #dc2626; animation: pulse 1.6s infinite; }
    @keyframes pulse {
      0%, 100% { transform: scale(1); }
      50% { transform: scale(1.15); }
    }
    .who-tasks { font-weight: 600; }
    main { flex: 1; padding: 24px; overflow-y: auto; }
  `]
})
export class ShellComponent implements OnInit {
  private auth = inject(AuthService);
  private projectService = inject(ProjectService);
  private processService = inject(ProcessService);

  projects = signal<{ id: string; name: string }[]>([]);
  taskCount = signal(0);
  showProjects = computed(() => this.auth.can(PERM.PROJECT_READ));
  name = computed(() => this.auth.me()?.fullName ?? '');
  me = this.auth.me;

  menu = computed(() => NAV.filter(i => !i.perm || this.auth.can(i.perm)));

  userInitials = computed(() => initials(this.auth.me()?.fullName));
  userGradient = computed(() => {
    const me = this.auth.me();
    if (me?.isOwner) return 'linear-gradient(135deg, #f59e0b 0%, #b45309 100%)';
    return avatarGradient(me?.id ?? me?.email);
  });

  ngOnInit(): void {
    if (!this.showProjects()) { return; }
    this.projectService.options().subscribe({
      next: list => this.projects.set((list ?? []).slice(0, 6)),
      error: () => this.projects.set([])
    });

    // Load pending task count for the bell + sidebar badge
    this.processService.myTasks().subscribe({
      next: list => this.taskCount.set((list ?? []).length),
      error: () => this.taskCount.set(0),
    });
  }

  logout(): void { this.auth.logout(); }
}
