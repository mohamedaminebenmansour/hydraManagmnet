import { Component, OnInit, computed, inject, signal, ViewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { TableModule } from 'primeng/table';
import { SelectModule } from 'primeng/select';
import { TooltipModule } from 'primeng/tooltip';
import { MessageService } from 'primeng/api';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { UserService } from '@app/core/services/user.service';
import { UserDto, UserSummary } from '@app/core/models/user.model';
import { ViewModeService } from '@app/core/view-mode.service';
import { EmptyStateComponent } from '@app/shared/empty-state.component';
import { LoadErrorComponent } from '@app/shared/load-error.component';
import { roleVisual, avatarGradient, initials } from '@app/shared/hydra-design';
import { userStatusVisual } from './user-status';
import { UserPanelComponent } from './user-panel.component';
import { UserCreateDialogComponent } from './user-create-dialog.component';

@Component({
  selector: 'app-users',
  standalone: true,
  imports: [FormsModule, ButtonModule, InputTextModule, TableModule, SelectModule, TooltipModule, RouterLink, EmptyStateComponent, LoadErrorComponent, UserPanelComponent, UserCreateDialogComponent],
  template: `
    <section class="users">
      <!-- Header -->
      <header class="u-head">
        <div>
          <h1>Utilisateurs</h1>
          <p class="u-sub">Qui a accès à HYDRA, avec quels rôles et sur quels chantiers</p>
        </div>
        <div class="u-actions">
          <div class="view-toggle">
            <button class="vt-btn" [class.active]="viewMode() === 'table'" (click)="setView('table')">Tableau</button>
            <button class="vt-btn" [class.active]="viewMode() === 'cards'" (click)="setView('cards')">Cartes</button>
          </div>
          <p-button label="Exporter (CSV)" icon="pi pi-download" [outlined]="true" size="small" (onClick)="exportCSV()"></p-button>
          @if (canCreate()) {
            <p-button label="+ Nouvel utilisateur" icon="pi pi-plus" severity="success" size="small" (onClick)="showCreate()"></p-button>
          }
        </div>
      </header>

      <!-- Counter row -->
      @if (summary()) {
        <div class="counters">
          <button class="counter" [class.active]="filterStatus() === null" (click)="filterStatus.set(null)">
            <span class="c-n">{{ summary()!.active }}</span>
            <span class="c-l"><i class="pi pi-check-circle" style="color:#16a34a"></i> Actifs</span>
          </button>
          <button class="counter" [class.active]="filterStatus() === 'PENDING'" (click)="filterStatus.set('PENDING')">
            <span class="c-n">{{ summary()!.pendingFirstLogin }}</span>
            <span class="c-l"><i class="pi pi-clock" style="color:#ea580c"></i> En attente 1re co.</span>
          </button>
          <button class="counter" [class.danger]="summary()!.dormant30 > 0" [class.active]="filterStatus() === 'DORMANT'"
                  (click)="filterStatus.set('DORMANT')">
            <span class="c-n">{{ summary()!.dormant30 }}</span>
            <span class="c-l"><i class="pi pi-exclamation-triangle" [style.color]="summary()!.dormant30 > 0 ? '#dc2626' : '#64748b'"></i> Sans co. 30 j</span>
          </button>
          <button class="counter" [class.active]="filterStatus() === 'INACTIVE'" (click)="filterStatus.set('INACTIVE')">
            <span class="c-n">{{ summary()!.inactive }}</span>
            <span class="c-l"><i class="pi pi-ban" style="color:#dc2626"></i> Inactifs</span>
          </button>
          @if (summary()!.signedExceptions > 0) {
            <button class="counter" (click)="filterStatus.set('EXCEPTION')">
              <span class="c-n">{{ summary()!.signedExceptions }}</span>
              <span class="c-l"><i class="pi pi-flag" style="color:#b45309"></i> Exceptions signées</span>
            </button>
          }
        </div>
      }

      <!-- Filters -->
      <div class="filters">
        <input pInputText type="text" placeholder="Rechercher (nom, email, téléphone)"
               [(ngModel)]="searchText" (ngModelChange)="applyFilters()" />
        <p-select [options]="statusOptions" [(ngModel)]="filterStatusVal" (ngModelChange)="onStatusFilter($event)"
                  placeholder="Statut" optionLabel="label" optionValue="value" styleClass="filter-select" />
        <p-select [options]="chantierOptions()" [(ngModel)]="filterChantier" (ngModelChange)="applyFilters()"
                  placeholder="Chantier" optionLabel="label" optionValue="value" styleClass="filter-select" [showClear]="true" />
      </div>

      <!-- Active filter chips -->
      @if (activeChips().length > 0) {
        <div class="chips">
          @for (chip of activeChips(); track chip) {
            <span class="chip">{{ chip.label }} <button class="chip-x" (click)="chip.remove()">×</button></span>
          }
        </div>
      }

      <!-- Content -->
      @if (loadError()) {
        <app-load-error message="Impossible de charger les utilisateurs." (retry)="load()" />
      } @else {
        @if (filtered().length === 0) {
          <app-empty-state icon="pi pi-users" message="Aucun utilisateur ne correspond à vos filtres."></app-empty-state>
        } @else if (viewMode() === 'table') {
        <p-table [value]="filtered()" [rows]="25" [paginator]="true"
                 styleClass="users-table" [tableStyle]="{'min-width': '800px'}">
          <ng-template #header>
            <tr>
              <th pSortableColumn="fullName">Utilisateur <p-sortIcon field="fullName"></p-sortIcon></th>
              <th>Rôles</th>
              <th pSortableColumn="projectCount">Chantiers <p-sortIcon field="projectCount"></p-sortIcon></th>
              <th pSortableColumn="status">Statut <p-sortIcon field="status"></p-sortIcon></th>
              <th pSortableColumn="lastLoginAt">Dernière connexion <p-sortIcon field="lastLoginAt"></p-sortIcon></th>
              <th></th>
            </tr>
          </ng-template>
          <ng-template #body let-u sortMode="multiple">
            <tr (click)="openPanel(u)" [class.inactive-row]="!u.isActive" style="cursor: pointer">
              <td>
                <div class="user-cell">
                  <span class="mini-avatar" [style.background]="avatarFor(u)">{{ initialsFor(u) }}</span>
                  <div class="user-info">
                    <span class="user-name">
                      {{ u.fullName }}
                      @if (u.isOwner) { <i class="pi pi-crown" aria-label="Gérant" style="color:#b45309"></i> }
                    </span>
                    <span class="user-email">{{ u.email }}</span>
                  </div>
                </div>
              </td>
              <td>
                <div class="roles-cell">
                  @for (r of firstRoles(u); track r) {
                    <span class="role-chip" [style.--rc-color]="roleVisual(r).color" [style.--rc-soft]="roleVisual(r).soft">
                      <i class="pi {{ roleVisual(r).icon }}"></i> {{ roleVisual(r).label }}
                    </span>
                  }
                  @if (u.roleCodes.length > 2) {
                    <span class="role-more" [pTooltip]="allRolesTooltip(u)">+{{ u.roleCodes.length - 2 }}</span>
                  }
                </div>
              </td>
              <td>
                <span [pTooltip]="u.projectNames?.join(', ') ?? ''">{{ u.projectCount ?? 0 }}</span>
              </td>
              <td>
                <span class="status-pill" [style.color]="statusVisual(u.status).color" [style.background]="statusVisual(u.status).soft">
                  <i class="pi {{ statusVisual(u.status).icon }}"></i> {{ statusVisual(u.status).label }}
                </span>
              </td>
              <td [class.dormant]="isDormant(u)">
                <span [pTooltip]="fullDate(u.lastLoginAt)">{{ lastLoginLabel(u) }}</span>
              </td>
              <td (click)="$event.stopPropagation()">
                <button class="row-menu-btn" (click)="rowMenuUser.set(u)" aria-label="Actions">
                  <i class="pi pi-ellipsis-v"></i>
                </button>
                @if (rowMenuUser()?.id === u.id) {
                  <div class="row-menu" (click)="rowMenuUser.set(null)">
                    <button class="mi" (click)="openPanel(u)"><i class="pi pi-folder-open"></i> Ouvrir</button>
                    <button class="mi" (click)="resetPw(u)"><i class="pi pi-key"></i> Réinitialiser le mot de passe</button>
                    @if (mailEnabled()) {
                      <button class="mi" (click)="sendLink(u)"><i class="pi pi-send"></i> Envoyer un lien</button>
                      @if (u.status === 'PENDING') {
                        <button class="mi" (click)="resendInvite(u)"><i class="pi pi-envelope"></i> Renvoyer l'invitation</button>
                      }
                    }
                    <button class="mi" (click)="toggleActive(u)"><i class="pi {{ u.isActive ? 'pi-ban' : 'pi-check' }}"></i> {{ u.isActive ? 'Désactiver' : 'Réactiver' }}</button>
                  </div>
                }
              </td>
            </tr>
          </ng-template>
        </p-table>
      } @else {
        <!-- Cards view -->
        <div class="cards-view">
          @for (u of filtered(); track u.id) {
            <div class="user-card" (click)="openPanel(u)" [class.inactive]="!u.isActive">
              <div class="card-top">
                <span class="mini-avatar" [style.background]="avatarFor(u)">{{ initialsFor(u) }}</span>
                <div class="card-info">
                  <div class="card-name">
                    {{ u.fullName }}
                    @if (u.isOwner) { <i class="pi pi-crown" style="color:#b45309"></i> }
                  </div>
                  <div class="card-email">{{ u.email }}</div>
                </div>
                <span class="status-pill" [style.color]="statusVisual(u.status).color" [style.background]="statusVisual(u.status).soft">
                  <i class="pi {{ statusVisual(u.status).icon }}"></i>
                </span>
              </div>
              <div class="card-roles">
                @for (r of firstRoles(u); track r) {
                  <span class="role-chip" [style.--rc-color]="roleVisual(r).color" [style.--rc-soft]="roleVisual(r).soft">
                    {{ roleVisual(r).label }}
                  </span>
                }
              </div>
              <div class="card-meta">
                <span [class.dormant]="isDormant(u)"><i class="pi pi-clock"></i> {{ lastLoginLabel(u) }}</span>
                <span><i class="pi pi-briefcase"></i> {{ u.projectCount ?? 0 }} chantier(s)</span>
              </div>
            </div>
          }
        </div>
      }
      }
    </section>

    <!-- Side panel -->
    <app-user-panel (userChanged)="onUserChanged($event)" (tempPasswordShown)="onTempPw($event)"></app-user-panel>

    <!-- Create dialog -->
    <app-user-create-dialog (created)="onCreated($event)"></app-user-create-dialog>
  `,
  styles: [`
    .users { display: flex; flex-direction: column; gap: 16px; }
    .u-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
    .u-head h1 { font-size: 22px; color: #0f172a; margin: 0; }
    .u-sub { font-size: 14px; color: #64748b; margin: 4px 0 0; }
    .u-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .view-toggle { display: flex; border: 1px solid #cbd5e1; border-radius: 8px; overflow: hidden; }
    .vt-btn { padding: 6px 12px; border: none; background: none; cursor: pointer; font-size: 13px; font-weight: 600; color: #64748b; }
    .vt-btn.active { background: #0ea5e9; color: #fff; }

    .counters { display: flex; gap: 8px; flex-wrap: wrap; }
    .counter { display: flex; flex-direction: column; align-items: center; gap: 2px; padding: 10px 16px;
      border-radius: 10px; border: 1px solid #e2e8f0; background: #fff; cursor: pointer; min-width: 120px; }
    .counter.active { border-color: #0ea5e9; background: #f0f9ff; }
    .counter.danger { border-color: #dc2626; }
    .c-n { font-size: 20px; font-weight: 800; color: #0f172a; }
    .c-l { font-size: 12px; color: #475569; display: flex; align-items: center; gap: 4px; }

    .filters { display: flex; gap: 8px; flex-wrap: wrap; }
    .filters input { flex: 1; min-width: 200px; }
    :host ::ng-deep .filter-select { min-width: 140px; }

    .chips { display: flex; gap: 6px; flex-wrap: wrap; }
    .chip { padding: 4px 8px; border-radius: 999px; background: #eff6ff; color: #1e40af;
      font-size: 12px; font-weight: 600; display: flex; align-items: center; gap: 4px; }
    .chip-x { background: none; border: none; cursor: pointer; color: #1e40af; font-size: 14px; padding: 0; }

    :host ::ng-deep .users-table { .p-datatable-thead > tr > th { background: #f8fafc; position: sticky; top: 0; z-index: 1; } }
    .user-cell { display: flex; align-items: center; gap: 8px; }
    .mini-avatar { width: 32px; height: 32px; border-radius: 50%; display: flex; align-items: center;
      justify-content: center; color: #fff; font-weight: 700; font-size: 12px; flex-shrink: 0; }
    .user-name { font-weight: 600; font-size: 14px; color: #0f172a; display: flex; align-items: center; gap: 4px; }
    .user-email { font-size: 12px; color: #64748b; }
    .inactive-row .user-name, .inactive-row .user-email { color: #94a3b8; }
    .roles-cell { display: flex; gap: 4px; align-items: center; flex-wrap: wrap; }
    .role-chip { padding: 2px 6px; border-radius: 999px; font-size: 11px; font-weight: 600;
      color: var(--rc-color); background: var(--rc-soft); display: inline-flex; align-items: center; gap: 3px; white-space: nowrap; }
    .role-more { font-size: 11px; color: #64748b; cursor: help; }
    .status-pill { padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600;
      display: inline-flex; align-items: center; gap: 3px; white-space: nowrap; }
    .dormant { color: #dc2626 !important; font-weight: 600; }
    .row-menu-btn { background: none; border: none; cursor: pointer; color: #64748b; padding: 4px 8px; font-size: 16px; }
    .row-menu-btn:hover { color: #0f172a; }
    .row-menu { position: absolute; right: 0; top: 32px; background: #fff; border: 1px solid #e2e8f0;
      border-radius: 8px; box-shadow: 0 4px 12px rgba(0,0,0,0.1); min-width: 220px; z-index: 10; padding: 4px; }
    .mi { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 6px;
      font-size: 13px; color: #0f172a; background: none; border: none; cursor: pointer; width: 100%; text-align: left; }
    .mi:hover { background: #f1f5f9; }

    .cards-view { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
    .user-card { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 14px; cursor: pointer; }
    .user-card:hover { border-color: #0ea5e9; }
    .user-card.inactive { opacity: 0.6; }
    .card-top { display: flex; align-items: center; gap: 10px; }
    .card-info { flex: 1; min-width: 0; }
    .card-name { font-weight: 600; font-size: 14px; display: flex; align-items: center; gap: 4px; }
    .card-email { font-size: 12px; color: #64748b; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .card-roles { display: flex; flex-wrap: wrap; gap: 4px; margin: 8px 0; }
    .card-meta { display: flex; justify-content: space-between; font-size: 12px; color: #64748b; }
    .card-meta span { display: flex; align-items: center; gap: 4px; }

    @media (max-width: 768px) {
      .cards-view { grid-template-columns: 1fr; }
    }
  `]
})
export class UsersComponent implements OnInit {
  private userService = inject(UserService);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);
  private viewModeService = inject(ViewModeService);
  private route = inject(ActivatedRoute);

  users = signal<UserDto[]>([]);
  summary = signal<UserSummary | null>(null);
  loadError = signal(false);
  mailEnabled = signal(false);

  viewMode = this.viewModeService.forPage('users', 'table');

  // Filters
  searchText = '';
  filterStatus = signal<string | null>(null);
  filterStatusVal: string | null = null;
  filterChantier: string | null = null;
  filterRole = signal<string | null>(null);

  // Row menu
  rowMenuUser = signal<UserDto | null>(null);

  @ViewChild(UserPanelComponent) panel!: UserPanelComponent;
  @ViewChild(UserCreateDialogComponent) createDialog!: UserCreateDialogComponent;

  canCreate = computed(() => this.auth.can(PERM.USER_CREATE));

  statusOptions = [
    { label: 'Tous', value: null },
    { label: 'Actifs', value: 'ACTIVE' },
    { label: 'En attente 1re co.', value: 'PENDING' },
    { label: 'Inactifs', value: 'INACTIVE' },
  ];

  chantierOptions = computed(() => {
    const names = new Set<string>();
    for (const u of this.users()) {
      for (const p of u.projectNames ?? []) names.add(p);
    }
    return [...names].sort().map(n => ({ label: n, value: n }));
  });

  filtered = computed(() => {
    let list = this.users();
    const s = this.searchText.trim().toLowerCase();
    if (s) {
      list = list.filter(u =>
        u.fullName.toLowerCase().includes(s) ||
        u.email.toLowerCase().includes(s) ||
        (u.phone ?? '').toLowerCase().includes(s));
    }
    const st = this.filterStatus();
    if (st === 'DORMANT') {
      list = list.filter(u => this.isDormant(u));
    } else if (st === 'EXCEPTION') {
      // not filterable client-side; show all (the counter is just a badge)
    } else if (st) {
      list = list.filter(u => u.status === st);
    }
    if (this.filterChantier) {
      list = list.filter(u => (u.projectNames ?? []).includes(this.filterChantier!));
    }
    const role = this.filterRole();
    if (role) {
      list = list.filter(u => u.roleCodes.includes(role!));
    }
    return list;
  });

  activeChips = computed(() => {
    const chips: { label: string; remove: () => void }[] = [];
    if (this.searchText) chips.push({ label: `« ${this.searchText} »`, remove: () => { this.searchText = ''; this.applyFilters(); } });
    const st = this.filterStatus();
    if (st) chips.push({ label: this.statusOptions.find(o => o.value === st)?.label ?? st, remove: () => { this.filterStatus.set(null); this.filterStatusVal = null; } });
    if (this.filterChantier) chips.push({ label: this.filterChantier, remove: () => { this.filterChantier = null; } });
    return chips;
  });

  ngOnInit(): void {
    this.load();
    this.userService.summary().subscribe({ next: s => this.summary.set(s), error: () => {} });
    // U5: read ?role=CODE to preselect the filter (from the roles page)
    const roleCode = this.route.snapshot.queryParamMap.get('role');
    if (roleCode) this.filterRole.set(roleCode);
    this.auth.check().subscribe(() => {});
  }

  load(): void {
    this.loadError.set(false);
    this.userService.list().subscribe({
      next: (u) => { this.users.set(u ?? []); },
      error: () => { this.loadError.set(true); this.users.set([]); }
    });
  }

  setView(mode: 'table' | 'cards'): void {
    this.viewModeService.set('users', mode);
  }

  applyFilters(): void {
    // signals are reactive; this just triggers change detection
  }

  onStatusFilter(v: string | null): void {
    this.filterStatus.set(v);
  }

  openPanel(u: UserDto): void {
    this.rowMenuUser.set(null);
    if (this.panel) {
      this.panel.open(u);
    }
  }

  onUserChanged(u: UserDto): void {
    this.users.update(list => list.map(x => x.id === u.id ? u : x));
  }

  onCreated(u: UserDto): void {
    this.users.update(list => [...list, u]);
    this.userService.summary().subscribe({ next: s => this.summary.set(s), error: () => {} });
  }

  onTempPw(e: { name: string; pw: string }): void {
    if (this.createDialog) {
      this.createDialog.showTempPassword(e.name, e.pw);
    }
  }

  showCreate(): void {
    if (this.createDialog) this.createDialog.show();
  }

  resetPw(u: UserDto): void {
    this.rowMenuUser.set(null);
    this.userService.resetPassword(u.id).subscribe({
      next: (r) => {
        if (r.temporaryPassword && this.createDialog) {
          this.createDialog.showTempPassword(u.fullName, r.temporaryPassword);
        }
        this.msgs.add({ severity: 'info', summary: 'Mot de passe réinitialisé' });
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err?.error?.message ?? 'Erreur' })
    });
  }

  sendLink(u: UserDto): void {
    this.rowMenuUser.set(null);
    this.userService.sendResetLink(u.id).subscribe({
      next: () => this.msgs.add({ severity: 'success', summary: 'Lien envoyé', detail: `à ${u.email}` }),
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err?.error?.message ?? 'Erreur' })
    });
  }

  resendInvite(u: UserDto): void {
    this.rowMenuUser.set(null);
    this.userService.resendInvitation(u.id).subscribe({
      next: () => this.msgs.add({ severity: 'success', summary: 'Invitation renvoyée', detail: `à ${u.email}` }),
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err?.error?.message ?? 'Erreur' })
    });
  }

  toggleActive(u: UserDto): void {
    this.rowMenuUser.set(null);
    this.userService.setActive(u.id, !u.isActive).subscribe({
      next: (updated) => {
        this.users.update(list => list.map(x => x.id === updated.id ? updated : x));
        this.msgs.add({ severity: u.isActive ? 'warn' : 'success', summary: u.isActive ? 'Compte désactivé' : 'Compte réactivé' });
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err?.error?.message ?? 'Erreur' })
    });
  }

  exportCSV(): void {
    const rows = this.filtered();
    const header = ['Nom', 'Email', 'Téléphone', 'Fonction', 'Rôles', 'Chantiers', 'Statut', 'Dernière connexion'];
    const lines = rows.map(u => [
      u.fullName, u.email, u.phone ?? '', u.jobTitle ?? '',
      u.roleLabels.join(', '),
      (u.projectNames ?? []).join(', '),
      this.statusVisual(u.status).label,
      u.lastLoginAt ?? ''
    ].map(v => `"${(v ?? '').replace(/"/g, '""')}"`).join(';'));
    const csv = '\uFEFF' + header.join(';') + '\n' + lines.join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'utilisateurs.csv';
    a.click();
    URL.revokeObjectURL(url);
  }

  // ── helpers ────────────────────────────────────────────────────────
  avatarFor(u: UserDto): string {
    return u.roleCodes.length > 0 ? roleVisual(u.roleCodes[0]).color : avatarGradient(u.email);
  }
  initialsFor(u: UserDto): string { return initials(u.fullName); }
  firstRoles(u: UserDto): string[] { return u.roleCodes.slice(0, 2); }
  allRolesTooltip(u: UserDto): string {
    return u.roleCodes.map(c => roleVisual(c).label).join(', ');
  }
  statusVisual(status?: string) { return userStatusVisual(status); }
  roleVisual(code: string) { return roleVisual(code); }

  isDormant(u: UserDto): boolean {
    if (!u.lastLoginAt) return false;
    const d = new Date(u.lastLoginAt);
    return !isNaN(d.getTime()) && (Date.now() - d.getTime()) > 30 * 24 * 3600000;
  }

  lastLoginLabel(u: UserDto): string {
    if (!u.lastLoginAt) return 'Jamais';
    const d = new Date(u.lastLoginAt);
    if (isNaN(d.getTime())) return 'Jamais';
    const diff = Date.now() - d.getTime();
    const h = Math.floor(diff / 3600000);
    if (h < 1) return "à l'instant";
    if (h < 24) return `il y a ${h} h`;
    const days = Math.floor(h / 24);
    if (days < 30) return `il y a ${days} j`;
    return d.toLocaleDateString('fr-FR');
  }

  fullDate(iso: string | null | undefined): string {
    if (!iso) return '';
    const d = new Date(iso);
    return isNaN(d.getTime()) ? '' : d.toLocaleString('fr-FR');
  }
}
