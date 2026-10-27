import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { DialogModule } from 'primeng/dialog';
import { MessageService } from 'primeng/api';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { UserService } from '@app/core/services/user.service';
import { UserDto } from '@app/core/models/user.model';
import { EmptyStateComponent } from '@app/shared/empty-state.component';
import {
  roleVisual, avatarGradient, initials,
} from '@app/shared/hydra-design';

interface RoleRef { code: string; label: string; }

interface UserRow {
  id: string;
  email: string;
  fullName: string;
  isOwner: boolean;
  isActive: boolean;
  roles: RoleRef[];
  lastLoginAt: string | null;
}

interface RoleRow { id: string; code: string; label: string; isSystem: boolean; }

@Component({
  selector: 'app-users',
  standalone: true,
  imports: [FormsModule, ButtonModule, InputTextModule, SelectModule, DialogModule, DatePipe, RouterLink, EmptyStateComponent],
  template: `
    <section class="users">
      <!-- Header -->
      <header class="u-head">
        <div>
          <h1><i class="pi pi-users"></i> Utilisateurs</h1>
          <p class="u-sub">Gérez les comptes, rôles métier et statuts depuis un seul endroit.</p>
        </div>
        @if (canCreate()) {
          <button pButton icon="pi pi-plus" label="Nouvel utilisateur" severity="success"
                  (click)="showForm = !showForm"></button>
        }
      </header>

      <!-- Quick stats -->
      <div class="u-stats">
        <div class="u-stat">
          <span class="us-n">{{ users().length }}</span>
          <span class="us-l"><i class="pi pi-users"></i> Utilisateurs</span>
        </div>
        <div class="u-stat">
          <span class="us-n">{{ activeCount() }}</span>
          <span class="us-l"><i class="pi pi-check-circle" style="color: #16a34a"></i> Actifs</span>
        </div>
        <div class="u-stat">
          <span class="us-n">{{ inactiveCount() }}</span>
          <span class="us-l"><i class="pi pi-ban" style="color: #dc2626"></i> Inactifs</span>
        </div>
        <div class="u-stat">
          <span class="us-n">{{ roles.length }}</span>
          <span class="us-l"><i class="pi pi-id-card" style="color: #7c3aed"></i> Rôles</span>
        </div>
      </div>

      <!-- Create form (collapsible) -->
      @if (showForm) {
        <div class="u-form">
          <h3><i class="pi pi-user-plus"></i> Nouvel utilisateur</h3>
          <div class="form-row">
            <input pInputText placeholder="Nom complet" [(ngModel)]="fullName" />
            <input pInputText type="email" placeholder="Email" [(ngModel)]="email" />
            <input pInputText type="password" placeholder="Mot de passe (8 caractères min.)"
                   [(ngModel)]="password" (keyup.enter)="create()" />
          </div>
          <div class="rb-title"><i class="pi pi-id-card"></i> Rôles métier</div>
          <div class="rb-grid">
            @for (r of roles; track r.code) {
              <label class="rb-item" [style.--rc-color]="rv(r.code).color" [style.--rc-soft]="rv(r.code).soft">
                <input type="checkbox" [checked]="selectedRoles.has(r.code)"
                       (change)="toggleRole(r.code, $event)" />
                <span class="rb-icon"><i class="pi" [class]="rv(r.code).icon"></i></span>
                <span class="rb-label">{{ r.label }}</span>
              </label>
            }
          </div>
          @if (createConflict) {
            <div class="conflict">
              <div class="conflict-msg">
                <i class="pi pi-exclamation-triangle"></i> {{ createConflict }}
              </div>
              <input pInputText placeholder="Raison de la dérogation (obligatoire)"
                     [(ngModel)]="createOverrideReason" />
              <p-button label="Forcer quand même" icon="pi pi-shield" severity="danger"
                        (onClick)="create()" [disabled]="!createOverrideReason.trim()" />
            </div>
          }
          <div class="form-actions">
            <button pButton label="Annuler" severity="secondary" [text]="true" (click)="showForm = false"></button>
            <button pButton label="Créer l'utilisateur" icon="pi pi-check" severity="success"
                    (click)="create()"
                    [disabled]="!fullName.trim() || !email.trim() || !password"></button>
          </div>
        </div>
      }

      <!-- Filters -->
      <div class="u-filters">
        <div class="u-search">
          <i class="pi pi-search u-search-icon"></i>
          <input pInputText placeholder="Rechercher par nom ou email…" [(ngModel)]="searchQuery"
                 (ngModelChange)="bumpRefresh()" />
        </div>
        <div class="u-filter-field">
          <label><i class="pi pi-id-card"></i> Rôle</label>
          <p-select [options]="roleFilterOptions()" [(ngModel)]="roleFilter"
                    placeholder="Tous les rôles" [showClear]="true"
                    optionLabel="label" optionValue="value"
                    (onChange)="bumpRefresh()" />
        </div>
        <div class="u-filter-field">
          <label><i class="pi pi-circle"></i> Statut</label>
          <p-select [options]="statusFilterOptions" [(ngModel)]="statusFilter"
                    placeholder="Tous les statuts" [showClear]="true"
                    optionLabel="label" optionValue="value"
                    (onChange)="bumpRefresh()" />
        </div>
        @if (hasActiveFilters()) {
          <button pButton label="Réinitialiser" icon="pi pi-times" severity="secondary" [text]="true"
                  (click)="clearFilters()"></button>
        }
      </div>

      <!-- Active filters chips -->
      @if (hasActiveFilters()) {
        <div class="u-active-chips">
          <span class="u-active-label">Filtres :</span>
          @if (searchQuery) { <span class="u-active-chip"><i class="pi pi-search"></i> "{{ searchQuery }}" <button (click)="searchQuery = ''; bumpRefresh()"><i class="pi pi-times"></i></button></span> }
          @if (roleFilter) { <span class="u-active-chip" [style.--chip-color]="rv(roleFilter).color"><i class="pi" [class]="rv(roleFilter).icon"></i> {{ rv(roleFilter).label }} <button (click)="roleFilter = null; bumpRefresh()"><i class="pi pi-times"></i></button></span> }
          @if (statusFilter) { <span class="u-active-chip" [style.--chip-color]="statusFilter === 'active' ? '#16a34a' : '#dc2626'"><i class="pi" [class]="statusFilter === 'active' ? 'pi-check-circle' : 'pi-ban'"></i> {{ statusFilter === 'active' ? 'Actifs' : 'Inactifs' }} <button (click)="statusFilter = null; bumpRefresh()"><i class="pi pi-times"></i></button></span> }
        </div>
      }

      <!-- Users list -->
      @if (loaded() && filteredUsers().length === 0) {
        <div class="u-empty">
          <i class="pi pi-users"></i>
          <h3>Aucun utilisateur trouvé</h3>
          <p>Aucun utilisateur ne correspond à ces filtres.</p>
          @if (hasActiveFilters()) {
            <button pButton label="Réinitialiser les filtres" icon="pi pi-times" severity="info" [text]="true"
                    (click)="clearFilters()"></button>
          }
        </div>
      } @else {
        <div class="u-grid">
          @for (u of filteredUsers(); track u.id) {
            <article class="user-card"
                     [style.--role-color]="userMainRoleColor(u.roles)"
                     [class.inactive]="!u.isActive">
              <div class="uc-head">
                <span class="uc-avatar" [style.background]="ag(u)">{{ ui(u) }}</span>
                <div class="uc-info">
                  <div class="uc-name">
                    {{ u.fullName }}
                    @if (u.isOwner) { <span class="uc-owner-badge"><i class="pi pi-crown"></i> Gérant</span> }
                  </div>
                  <div class="uc-email">{{ u.email }}</div>
                </div>
                <span class="uc-status" [class.active]="u.isActive" [class.inactive]="!u.isActive">
                  <i class="pi" [class]="u.isActive ? 'pi-check-circle' : 'pi-ban'"></i>
                  {{ u.isActive ? 'Actif' : 'Inactif' }}
                </span>
              </div>

              <div class="uc-roles">
                @for (r of u.roles; track r.code) {
                  <span class="uc-role-chip" [style.--rc-color]="rv(r.code).color" [style.--rc-soft]="rv(r.code).soft">
                    <i class="pi" [class]="rv(r.code).icon"></i> {{ rv(r.code).label }}
                  </span>
                } @empty {
                  <span class="uc-no-role"><i class="pi pi-info"></i> Aucun rôle</span>
                }
              </div>

              @if (u.lastLoginAt) {
                <div class="uc-last-login">
                  <i class="pi pi-clock"></i> Dernière connexion : {{ u.lastLoginAt | date:'short' }}
                </div>
              } @else {
                <div class="uc-last-login never">
                  <i class="pi pi-clock"></i> Jamais connecté
                </div>
              }

              <div class="uc-actions">
                @if (!u.isOwner) {
                  @if (canAssign()) {
                    <button pButton label="Rôles" icon="pi pi-id-card" size="small" severity="info" [text]="true"
                            (click)="openRoles(u)"></button>
                  }
                  @if (canUpdate()) {
                    <button pButton label="Modifier" icon="pi pi-pencil" size="small" severity="info" [text]="true"
                            (click)="openEdit(u)"></button>
                  }
                  @if (canDeactivate()) {
                    <button pButton [label]="u.isActive ? 'Désactiver' : 'Réactiver'"
                            [icon]="u.isActive ? 'pi pi-ban' : 'pi pi-check-circle'"
                            size="small" [severity]="u.isActive ? 'danger' : 'success'" [text]="true"
                            (click)="setActive(u, !u.isActive)"></button>
                  }
                }
              </div>
            </article>
          }
        </div>
      }

      <!-- Roles dialog -->
      <p-dialog header="Attribuer les rôles" [(visible)]="showRolesDialog"
                [modal]="true" [style]="{ width: '480px' }">
        <div class="dlg-form">
          @if (rolesTarget) {
            <div class="dlg-who">
              <span class="dlg-avatar" [style.background]="ag(rolesTarget)">{{ ui(rolesTarget) }}</span>
              <div>
                <div class="dlg-name">{{ rolesTarget.fullName }}</div>
                <div class="dlg-email">{{ rolesTarget.email }}</div>
              </div>
            </div>
          }
          <div class="rb-grid">
            @for (r of roles; track r.code) {
              <label class="rb-item" [style.--rc-color]="rv(r.code).color" [style.--rc-soft]="rv(r.code).soft">
                <input type="checkbox" [checked]="rolesSelected.has(r.code)"
                       (change)="toggleRoleEdit(r.code, $event)" />
                <span class="rb-icon"><i class="pi" [class]="rv(r.code).icon"></i></span>
                <span class="rb-label">{{ r.label }}</span>
              </label>
            }
          </div>
          @if (rolesConflict) {
            <div class="conflict">
              <div class="conflict-msg">
                <i class="pi pi-exclamation-triangle"></i> {{ rolesConflict }}
              </div>
              <input pInputText placeholder="Raison de la dérogation (obligatoire)"
                     [(ngModel)]="rolesOverrideReason" />
              <p-button label="Forcer quand même" icon="pi pi-shield" severity="danger"
                        (onClick)="saveRoles(true)" [disabled]="!rolesOverrideReason.trim()" />
            </div>
          }
        </div>
        <ng-template pTemplate="footer">
          <p-button label="Annuler" [text]="true" (onClick)="showRolesDialog = false" />
          <p-button label="Enregistrer" icon="pi pi-check" (onClick)="saveRoles()" />
        </ng-template>
      </p-dialog>

      <!-- Edit dialog -->
      <p-dialog header="Modifier l'utilisateur" [(visible)]="showEditDialog"
                [modal]="true" [style]="{ width: '440px' }">
        <div class="dlg-form">
          @if (editTarget) {
            <div class="dlg-who">
              <span class="dlg-avatar" [style.background]="ag(editTarget)">{{ ui(editTarget) }}</span>
              <div>
                <div class="dlg-name">{{ editTarget.fullName }}</div>
                <div class="dlg-email">{{ editTarget.email }}</div>
              </div>
            </div>
          }
          <input pInputText placeholder="Nom complet" [(ngModel)]="editFullName" />
          <input pInputText type="password" placeholder="Nouveau mot de passe (optionnel)"
                 [(ngModel)]="editPassword" (keyup.enter)="saveEdit()" />
        </div>
        <ng-template pTemplate="footer">
          <p-button label="Annuler" [text]="true" (onClick)="showEditDialog = false" />
          <p-button label="Enregistrer" icon="pi pi-check" (onClick)="saveEdit()"
                    [disabled]="!editFullName.trim()" />
        </ng-template>
      </p-dialog>
    </section>
  `,
  styleUrl: './users.component.scss',
})
export class UsersComponent {
  private http = inject(HttpClient); // kept only for /api/roles
  private userService = inject(UserService);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  users = signal<UserRow[]>([]);
  roles: RoleRow[] = [];
  loaded = signal(false);

  showForm = false;
  fullName = '';
  email = '';
  password = '';
  selectedRoles = new Set<string>();
  createConflict = '';
  createOverrideReason = '';

  showRolesDialog = false;
  rolesTarget: UserRow | null = null;
  rolesSelected = new Set<string>();
  rolesConflict = '';
  rolesOverrideReason = '';

  showEditDialog = false;
  editTarget: UserRow | null = null;
  editFullName = '';
  editPassword = '';

  // Filters (plain values — but use a refresh signal so computed re-evaluates)
  searchQuery = '';
  roleFilter: string | null = null;
  statusFilter: 'active' | 'inactive' | null = null;
  refresh = signal(0);

  canCreate = computed(() => this.auth.can(PERM.USER_CREATE));
  canUpdate = computed(() => this.auth.can(PERM.USER_UPDATE));
  canDeactivate = computed(() => this.auth.can(PERM.USER_DEACTIVATE));
  canAssign = computed(() => this.auth.can(PERM.ROLE_ASSIGN));

  // Expose hydra-design helpers
  rv = roleVisual;
  ag = (u: { id: string }) => avatarGradient(u.id);
  ui = (u: { fullName: string }) => initials(u.fullName);

  activeCount = computed(() => this.users().filter(u => u.isActive).length);
  inactiveCount = computed(() => this.users().filter(u => !u.isActive).length);

  roleFilterOptions = computed(() => {
    const codes = new Set<string>();
    for (const u of this.users()) for (const r of u.roles) codes.add(r.code);
    return [...codes].sort().map(c => ({ label: roleVisual(c).label, value: c }));
  });

  readonly statusFilterOptions = [
    { label: 'Actifs', value: 'active' },
    { label: 'Inactifs', value: 'inactive' },
  ];

  filteredUsers = computed(() => {
    // touch the refresh signal so computed re-evaluates when filters change
    this.refresh();
    const q = this.searchQuery.trim().toLowerCase();
    return this.users().filter(u => {
      if (q && !(u.fullName.toLowerCase().includes(q) || u.email.toLowerCase().includes(q))) return false;
      if (this.roleFilter && !u.roles.some(r => r.code === this.roleFilter)) return false;
      if (this.statusFilter === 'active' && !u.isActive) return false;
      if (this.statusFilter === 'inactive' && u.isActive) return false;
      return true;
    });
  });

  hasActiveFilters(): boolean {
    return !!(this.searchQuery || this.roleFilter || this.statusFilter);
  }

  clearFilters(): void {
    this.searchQuery = '';
    this.roleFilter = null;
    this.statusFilter = null;
    this.refresh.set(this.refresh() + 1);
  }

  bumpRefresh(): void { this.refresh.set(this.refresh() + 1); }

  userMainRoleColor(roles: RoleRef[]): string {
    if (!roles?.length) return '#64748b';
    const priority = ['OWNER', 'DIRECTION', 'CHEF_PROJET', 'CONDUCTEUR', 'INGENIEUR', 'BUREAU',
      'QA', 'DOC_CTRL', 'ACHAT', 'FINANCE', 'CHEF_CHANTIER', 'HSE', 'SOUS_TRAITANT'];
    for (const p of priority) {
      if (roles.some(r => r.code === p)) return roleVisual(p).color;
    }
    return roleVisual(roles[0].code).color;
  }

  constructor() { this.load(); }

  load(): void {
    this.userService.list().subscribe(list => {
      this.users.set((list ?? []).map(d => ({
        id: d.id,
        email: d.email,
        fullName: d.fullName,
        isOwner: d.isOwner,
        isActive: d.isActive,
        roles: d.roleCodes.map((code, i) => ({ code, label: d.roleLabels[i] ?? code })),
        lastLoginAt: d.lastLoginAt ?? null
      })));
      this.loaded.set(true);
    });
    this.http.get<RoleRow[]>('/api/roles').subscribe(r => this.roles = r);
  }

  formatLastLogin(iso: string): string {
    const d = new Date(iso);
    if (isNaN(d.getTime())) { return iso; }
    return d.toLocaleString('fr-FR', { dateStyle: 'short', timeStyle: 'short' });
  }

  toggleRole(code: string, ev: Event): void {
    const c = (ev.target as HTMLInputElement).checked;
    if (c) { this.selectedRoles.add(code); } else { this.selectedRoles.delete(code); }
  }

  toggleRoleEdit(code: string, ev: Event): void {
    const c = (ev.target as HTMLInputElement).checked;
    if (c) { this.rolesSelected.add(code); } else { this.rolesSelected.delete(code); }
  }

  create(): void {
    this.userService.create({
      fullName: this.fullName,
      email: this.email,
      password: this.password,
      roleCodes: [...this.selectedRoles],
      overrideReason: this.createOverrideReason || undefined
    }).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Utilisateur créé' });
        this.email = ''; this.fullName = ''; this.password = '';
        this.selectedRoles.clear(); this.showForm = false;
        this.createOverrideReason = '';
        this.load();
      },
      error: (err) => this.handleError(err, m => this.createConflict = m,
        () => { this.createConflict = ''; this.load(); })
    });
  }

  openRoles(u: UserRow): void {
    this.rolesTarget = u;
    this.rolesSelected = new Set(u.roles.map(r => r.code));
    this.rolesConflict = '';
    this.rolesOverrideReason = '';
    this.showRolesDialog = true;
  }

  saveRoles(override = false): void {
    if (!this.rolesTarget) return;
    this.rolesConflict = '';
    this.userService.assignRoles(this.rolesTarget.id, {
      roleCodes: [...this.rolesSelected],
      overrideReason: override ? this.rolesOverrideReason : undefined
    }).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Rôles enregistrés',
          detail: 'La personne doit se reconnecter.' });
        this.showRolesDialog = false;
        this.load();
      },
      error: (err) => this.handleError(err, m => this.rolesConflict = m,
        () => { this.rolesConflict = ''; this.showRolesDialog = false; this.load(); })
    });
  }

  openEdit(u: UserRow): void {
    this.editTarget = u;
    this.editFullName = u.fullName;
    this.editPassword = '';
    this.showEditDialog = true;
  }

  saveEdit(): void {
    if (!this.editTarget) return;
    this.userService.update(this.editTarget.id, {
      fullName: this.editFullName, password: this.editPassword || undefined
    }).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Utilisateur modifié' });
        this.showEditDialog = false;
        this.load();
      },
      error: () => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Modification impossible' })
    });
  }

  setActive(u: UserRow, active: boolean): void {
    this.userService.setActive(u.id, active).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success',
          summary: active ? 'Utilisateur réactivé' : 'Utilisateur désactivé' });
        this.load();
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err.error?.message ?? 'Action impossible' })
    });
  }

  private handleError(err: any, setConflict: (m: string) => void, onSuccessOverride: () => void): void {
    if (err.status === 409 && (err.error?.code === 'TOXIC_PAIR_CONFLICT' || err.error?.code === 'TOXIC_PAIR')) {
      setConflict(err.error.message);
    } else {
      this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err.error?.message ?? 'Action impossible' });
      onSuccessOverride();
    }
  }
}
