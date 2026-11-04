import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { DialogModule } from 'primeng/dialog';
import { CheckboxModule } from 'primeng/checkbox';
import { MessageService } from 'primeng/api';
import { PERM_GROUPS, PERM_SENTENCES } from '../../core/permissions';
import { roleVisual, type RoleVisual } from '../../shared/hydra-design';
import { userStatusVisual, type UserStatusVisual } from '../users/user-status';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { LoadErrorComponent } from '../../shared/load-error.component';

interface RoleRow {
  id: string;
  code: string;
  label: string;
  isSystem: boolean;
  permissions: string[];
  userCount?: number;
  moduleKeys?: string[];
}

interface PermRow { id: string; description: string | null; }

interface RoleUserRow {
  id: string;
  fullName: string;
  email: string;
  status: string;
}

interface PermGroup {
  key: string;
  icon: string;
  title: string;
  ids: string[];
}

@Component({
  selector: 'app-role-detail',
  standalone: true,
  imports: [
    FormsModule,
    RouterLink,
    ButtonModule,
    InputTextModule,
    DialogModule,
    CheckboxModule,
    EmptyStateComponent,
    LoadErrorComponent,
  ],
  template: `
    <div class="page">
      <header class="head">
        <a routerLink="/roles" class="back-link">
          <i class="pi pi-arrow-left"></i> Retour aux rôles
        </a>
        @if (role(); as r) {
          <div class="title-block">
            <span class="role-dot" [style.background]="visual().color"></span>
            <i class="pi {{ visual().icon }}" [style.color]="visual().color"></i>
            <h1>{{ r.label }}</h1>
            <span class="role-code">{{ r.code }}</span>
            @if (r.isSystem) {
              <span class="sys-tag"><i class="pi pi-lock"></i> Système</span>
            } @else {
              <span class="custom-tag">Personnalisé</span>
            }
          </div>
        }
      </header>

      @if (role(); as r) {
        @if (r.isSystem) {
          <div class="sys-banner">
            <div class="sys-banner-text">
              <i class="pi pi-lock"></i>
              <span>Rôle système — dupliquez-le pour le personnaliser</span>
            </div>
            <p-button label="Dupliquer" icon="pi pi-copy" size="small" (onClick)="openDuplicate()" />
          </div>
        }

        <nav class="tabs">
          <button class="tab" [class.active]="activeTab() === 'perms'" (click)="setTab('perms')">
            <i class="pi pi-shield"></i> Permissions
          </button>
          <button class="tab" [class.active]="activeTab() === 'users'" (click)="setTab('users')">
            <i class="pi pi-users"></i> Personnes
            <span class="tab-count">{{ users().length }}</span>
          </button>
        </nav>

        @if (activeTab() === 'perms') {
          <section class="perms-tab">
            <div class="name-block">
              <label class="field-label">Nom du rôle</label>
              <input pInputText class="role-name"
                     [ngModel]="labelSig()" (ngModelChange)="labelSig.set($event)"
                     [readonly]="r.isSystem" [disabled]="r.isSystem"
                     placeholder="Nom du rôle" />
            </div>

            <div class="cards">
              @for (g of groups; track g.key) {
                <article class="perm-card">
                  <header class="pc-head">
                    <div class="pc-title">
                      <i [className]="g.icon"></i>
                      <span>{{ g.title }}</span>
                    </div>
                    <div class="pc-meta">
                      <span class="pc-count">{{ countInGroup(g) }} / {{ g.ids.length }}</span>
                      <p-checkbox [binary]="true"
                                  [ngModel]="allInGroup(g)"
                                  [indeterminate]="partialInGroup(g)"
                                  [disabled]="r.isSystem"
                                  (onChange)="toggleAll(g, $event.checked)"
                                  [ariaLabel]="'Tout sélectionner pour ' + g.title" />
                    </div>
                  </header>
                  <ul class="perm-list">
                    @for (pid of g.ids; track pid) {
                      <li class="perm-row" [class.disabled]="r.isSystem">
                        <label class="perm-label">
                          <p-checkbox [binary]="true"
                                      [ngModel]="selected().has(pid)"
                                      [disabled]="r.isSystem"
                                      (onChange)="toggle(pid, $event.checked)" />
                          <span class="perm-sentence">{{ sentence(pid) }}</span>
                          <span class="perm-code">{{ pid }}</span>
                        </label>
                      </li>
                    }
                  </ul>
                </article>
              }
            </div>
          </section>
        } @else {
          <section class="users-tab">
            @if (usersLoading()) {
              <div class="state"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
            } @else if (usersError()) {
              <app-load-error message="Impossible de charger les personnes." (retry)="loadUsers()" />
            } @else if (users().length === 0) {
              <app-empty-state icon="pi pi-users" message="Aucune personne avec ce rôle." />
            } @else {
              <ul class="user-list">
                @for (u of users(); track u.id) {
                  <li class="user-row" (click)="goToUsers()" role="button" tabindex="0"
                      (keyup.enter)="goToUsers()"
                      [attr.aria-label]="'Voir les utilisateurs du rôle ' + r.code">
                    <span class="user-name">{{ u.fullName }}</span>
                    <span class="user-email">{{ u.email }}</span>
                    <span class="status-pill"
                          [style.color]="statusVisual(u.status).color"
                          [style.background]="statusVisual(u.status).soft">
                      <i class="pi {{ statusVisual(u.status).icon }}"></i>
                      {{ statusVisual(u.status).label }}
                    </span>
                  </li>
                }
              </ul>
            }
          </section>
        }
      } @else if (loading()) {
        <div class="state"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else if (loadError()) {
        <app-load-error message="Impossible de charger le rôle." (retry)="loadRole()" />
      } @else if (notFound()) {
        <app-empty-state icon="pi pi-exclamation-triangle" message="Rôle introuvable." />
      }

      @if (modifications() > 0 && !saving()) {
        <div class="save-bar">
          <span class="mod-count">{{ modifications() }} modification{{ modifications() > 1 ? 's' : '' }}</span>
          <div class="save-actions">
            <p-button label="Annuler" [text]="true" (onClick)="cancel()" />
            <p-button label="Enregistrer" icon="pi pi-check" severity="success" (onClick)="save()" />
          </div>
        </div>
      }
      @if (saving()) {
        <div class="save-bar">
          <span class="mod-count"><i class="pi pi-spin pi-spinner"></i> Enregistrement…</span>
        </div>
      }
    </div>

    <!-- Duplicate dialog (system roles) -->
    <p-dialog header="Dupliquer le rôle" [visible]="duplicateVisible()" (visibleChange)="duplicateVisible.set($event)"
              [modal]="true" [style]="{ width: '460px' }" [dismissableMask]="true">
      <div class="dlg">
        <p class="dlg-text">Le nouveau rôle sera une copie modifiable des permissions actuelles.</p>
        <label class="field-label">Libellé du nouveau rôle</label>
        <input pInputText [ngModel]="duplicateLabel()" (ngModelChange)="duplicateLabel.set($event)"
               class="dlg-input"
               placeholder="Ex. : Chef chantier (copie)" />
      </div>
      <ng-template pTemplate="footer">
        <p-button label="Annuler" [text]="true" (onClick)="duplicateVisible.set(false)" />
        <p-button label="Dupliquer" icon="pi pi-copy" severity="success"
                  [disabled]="!duplicateLabel().trim()"
                  (onClick)="submitDuplicate()" />
      </ng-template>
    </p-dialog>

    <!-- Confirm impact dialog (409 CONFIRM_IMPACT) -->
    <p-dialog header="Confirmation requise" [visible]="confirmVisible()" (visibleChange)="confirmVisible.set($event)"
              [modal]="true" [style]="{ width: '480px' }" [closable]="false">
      <div class="dlg">
        <div class="confirm-icon"><i class="pi pi-exclamation-triangle"></i></div>
        <p class="confirm-text">{{ confirmMessage() }}</p>
        <p class="confirm-hint">Confirmer la modification pour toutes les personnes concernées ?</p>
      </div>
      <ng-template pTemplate="footer">
        <p-button label="Annuler" [text]="true" (onClick)="confirmVisible.set(false)" />
        <p-button label="Confirmer" icon="pi pi-check" severity="warn" (onClick)="confirmSave()" />
      </ng-template>
    </p-dialog>
  `,
  styles: [`
    .page { display: flex; flex-direction: column; gap: 16px; padding-bottom: 80px; }
    .head { display: flex; flex-direction: column; gap: 14px; }
    .back-link { color: #0ea5e9; font-size: 13px; font-weight: 600; text-decoration: none;
      display: inline-flex; align-items: center; gap: 6px; width: fit-content; }
    .back-link:hover { text-decoration: underline; }
    .title-block { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .title-block h1 { margin: 0; font-size: 22px; font-weight: 700; color: #0f172a; }
    .role-dot { width: 12px; height: 12px; border-radius: 999px; flex: none; }
    .title-block .pi { font-size: 18px; }
    .role-code { font-size: 12px; color: #94a3b8; font-family: ui-monospace, SFMono-Regular, monospace; }
    .sys-tag, .custom-tag {
      display: inline-flex; align-items: center; gap: 4px;
      padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 600;
    }
    .sys-tag { background: #f1f5f9; color: #475569; }
    .custom-tag { background: #ecfeff; color: #0891b2; }

    .sys-banner {
      display: flex; justify-content: space-between; align-items: center; gap: 12px;
      padding: 12px 16px; border-radius: 10px;
      background: #fffbeb; border: 1px solid #fde68a; color: #92400e;
    }
    .sys-banner-text { display: inline-flex; align-items: center; gap: 10px; font-weight: 600; }

    .tabs { display: flex; gap: 4px; border-bottom: 1px solid #e2e8f0; }
    .tab {
      display: inline-flex; align-items: center; gap: 8px;
      padding: 10px 16px; border: none; background: transparent;
      font-size: 14px; font-weight: 600; color: #64748b; cursor: pointer;
      border-bottom: 2px solid transparent; font-family: inherit;
    }
    .tab:hover { color: #0f172a; }
    .tab.active { color: #0f172a; border-bottom-color: #0f172a; }
    .tab-count {
      background: #f1f5f9; color: #475569; font-size: 11px; font-weight: 700;
      padding: 1px 8px; border-radius: 999px;
    }

    .perms-tab { display: flex; flex-direction: column; gap: 16px; }
    .name-block { display: flex; flex-direction: column; gap: 6px; max-width: 480px; }
    .field-label { font-size: 12px; font-weight: 700; color: #475569;
      text-transform: uppercase; letter-spacing: .04em; }
    .role-name { font-size: 17px; padding: 10px 12px; width: 100%; box-sizing: border-box; }

    .cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
    .perm-card { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px;
      padding: 14px 16px; display: flex; flex-direction: column; gap: 8px; }
    .pc-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
    .pc-title { display: inline-flex; align-items: center; gap: 8px;
      font-size: 14px; font-weight: 700; color: #0f172a; }
    .pc-title i { color: #475569; }
    .pc-meta { display: inline-flex; align-items: center; gap: 10px; }
    .pc-count { font-size: 12px; font-weight: 700; color: #64748b; font-variant-numeric: tabular-nums; }

    .perm-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; }
    .perm-row { padding: 4px 0; }
    .perm-row.disabled { opacity: .7; }
    .perm-label { display: flex; align-items: center; gap: 10px; cursor: pointer; flex-wrap: wrap; }
    .perm-sentence { font-size: 14px; color: #1e293b; flex: 1; }
    .perm-code { font-size: 11px; color: #94a3b8; font-family: ui-monospace, SFMono-Regular, monospace; }

    .users-tab { display: flex; flex-direction: column; gap: 12px; }
    .user-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column;
      gap: 6px; background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden; }
    .user-row {
      display: flex; align-items: center; gap: 12px; padding: 12px 16px;
      border-bottom: 1px solid #f1f5f9; cursor: pointer; transition: background .15s;
    }
    .user-row:last-child { border-bottom: none; }
    .user-row:hover { background: #f8fafc; }
    .user-name { font-weight: 600; color: #0f172a; font-size: 14px; min-width: 160px; }
    .user-email { color: #64748b; font-size: 13px; flex: 1; }
    .status-pill {
      display: inline-flex; align-items: center; gap: 5px;
      padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 600;
    }

    .state { padding: 40px; text-align: center; color: #64748b; font-size: 15px; }

    .save-bar {
      position: fixed; left: 0; right: 0; bottom: 0;
      display: flex; justify-content: space-between; align-items: center; gap: 12px;
      padding: 12px 24px; background: #fff; border-top: 1px solid #e2e8f0;
      box-shadow: 0 -4px 16px rgba(15, 23, 42, .08);
      z-index: 50;
    }
    .mod-count { font-size: 14px; font-weight: 600; color: #1e293b;
      display: inline-flex; align-items: center; gap: 8px; }
    .save-actions { display: inline-flex; gap: 8px; align-items: center; }

    .dlg { display: flex; flex-direction: column; gap: 10px; }
    .dlg-text { margin: 0; color: #64748b; font-size: 14px; }
    .dlg-input { width: 100%; box-sizing: border-box; padding: 10px 12px; font-size: 15px; }
    .confirm-icon { text-align: center; font-size: 28px; color: #ea580c; }
    .confirm-text { margin: 0; color: #1e293b; font-size: 15px; font-weight: 600; text-align: center; }
    .confirm-hint { margin: 0; color: #64748b; font-size: 13px; text-align: center; }
  `],
})
export class RoleDetailComponent implements OnInit, OnDestroy {
  private http = inject(HttpClient);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private msgs = inject(MessageService);

  readonly groups: PermGroup[] = PERM_GROUPS;

  readonly role = signal<RoleRow | null>(null);
  readonly loading = signal<boolean>(true);
  readonly loadError = signal<boolean>(false);
  readonly notFound = signal<boolean>(false);

  readonly labelSig = signal<string>('');
  readonly selected = signal<Set<string>>(new Set());
  private original = signal<{ label: string; perms: Set<string> } | null>(null);

  readonly allPermissions = signal<string[]>([]);

  readonly activeTab = signal<'perms' | 'users'>('perms');

  readonly users = signal<RoleUserRow[]>([]);
  readonly usersLoading = signal<boolean>(false);
  readonly usersError = signal<boolean>(false);

  readonly saving = signal<boolean>(false);
  readonly duplicateVisible = signal<boolean>(false);
  readonly duplicateLabel = signal<string>('');
  readonly confirmVisible = signal<boolean>(false);
  readonly confirmMessage = signal<string>('');

  private roleId: string | null = null;

  readonly visual = computed<RoleVisual>(() => roleVisual(this.role()?.code ?? null));
  readonly isSystem = computed<boolean>(() => this.role()?.isSystem ?? false);

  readonly modifications = computed<number>(() => {
    const orig = this.original();
    if (!orig) return 0;
    let n = 0;
    if (this.labelSig().trim() !== orig.label) n++;
    const cur = this.selected();
    for (const p of cur) if (!orig.perms.has(p)) n++;
    for (const p of orig.perms) if (!cur.has(p)) n++;
    return n;
  });

  private beforeUnloadHandler = (e: BeforeUnloadEvent): void => {
    if (this.modifications() > 0) {
      e.preventDefault();
      e.returnValue = '';
    }
  };

  ngOnInit(): void {
    this.roleId = this.route.snapshot.paramMap.get('id');
    window.addEventListener('beforeunload', this.beforeUnloadHandler);
    if (!this.roleId) {
      this.notFound.set(true);
      this.loading.set(false);
      return;
    }
    this.http.get<PermRow[]>('/api/roles/permissions').subscribe({
      next: p => this.allPermissions.set((p ?? []).map(x => x.id)),
      error: () => { /* catalog optional */ },
    });
    this.loadRole();
  }

  ngOnDestroy(): void {
    window.removeEventListener('beforeunload', this.beforeUnloadHandler);
  }

  loadRole(): void {
    if (!this.roleId) return;
    this.loading.set(true);
    this.loadError.set(false);
    this.notFound.set(false);
    this.http.get<RoleRow[]>('/api/roles').subscribe({
      next: list => {
        const r = (list ?? []).find(x => x.id === this.roleId);
        if (!r) {
          this.notFound.set(true);
          this.loading.set(false);
          return;
        }
        this.role.set(r);
        this.labelSig.set(r.label);
        const perms = new Set<string>(r.permissions ?? []);
        this.selected.set(new Set(perms));
        this.original.set({ label: r.label, perms });
        this.loading.set(false);
        this.loadUsers();
      },
      error: () => {
        this.loadError.set(true);
        this.loading.set(false);
      },
    });
  }

  loadUsers(): void {
    if (!this.roleId) return;
    this.usersLoading.set(true);
    this.usersError.set(false);
    this.http.get<RoleUserRow[]>(`/api/roles/${this.roleId}/users`).subscribe({
      next: u => { this.users.set(u ?? []); this.usersLoading.set(false); },
      error: () => { this.usersError.set(true); this.usersLoading.set(false); },
    });
  }

  setTab(tab: 'perms' | 'users'): void {
    this.activeTab.set(tab);
  }

  goToUsers(): void {
    const r = this.role();
    if (!r) return;
    this.router.navigate(['/users'], { queryParams: { role: r.code } });
  }

  sentence(code: string): string { return PERM_SENTENCES[code] ?? code; }

  countInGroup(g: PermGroup): number {
    const sel = this.selected();
    return g.ids.filter(p => sel.has(p)).length;
  }

  allInGroup(g: PermGroup): boolean {
    const sel = this.selected();
    return g.ids.length > 0 && g.ids.every(p => sel.has(p));
  }

  partialInGroup(g: PermGroup): boolean {
    const sel = this.selected();
    const n = g.ids.filter(p => sel.has(p)).length;
    return n > 0 && n < g.ids.length;
  }

  toggleAll(g: PermGroup, checked: boolean): void {
    if (this.isSystem()) return;
    const next = new Set(this.selected());
    if (checked) g.ids.forEach(p => next.add(p));
    else g.ids.forEach(p => next.delete(p));
    this.selected.set(next);
  }

  toggle(code: string, checked: boolean): void {
    if (this.isSystem()) return;
    const next = new Set(this.selected());
    if (checked) next.add(code); else next.delete(code);
    this.selected.set(next);
  }

  statusVisual(status: string): UserStatusVisual { return userStatusVisual(status); }

  cancel(): void {
    const orig = this.original();
    if (!orig) return;
    this.labelSig.set(orig.label);
    this.selected.set(new Set(orig.perms));
  }

  save(): void {
    if (this.isSystem() || !this.roleId) return;
    const trimmed = this.labelSig().trim();
    if (!trimmed) {
      this.msgs.add({ severity: 'warn', summary: 'Libellé requis' });
      return;
    }
    this.saving.set(true);
    const body = { label: trimmed, permissionCodes: [...this.selected()], confirm: false };
    this.http.put(`/api/roles/${this.roleId}`, body).subscribe({
      next: () => {
        this.saving.set(false);
        this.msgs.add({ severity: 'success', summary: 'Rôle enregistré' });
        this.commitOriginal();
        this.router.navigate(['/roles']);
      },
      error: err => {
        this.saving.set(false);
        if (err?.status === 409 && err?.error?.code === 'CONFIRM_IMPACT') {
          this.confirmMessage.set(err?.error?.message ?? "Ce changement s'applique à plusieurs personnes.");
          this.confirmVisible.set(true);
          return;
        }
        this.msgs.add({
          severity: 'error', summary: 'Erreur',
          detail: err?.error?.message ?? 'Enregistrement impossible',
        });
      },
    });
  }

  confirmSave(): void {
    if (this.isSystem() || !this.roleId) return;
    this.confirmVisible.set(false);
    this.saving.set(true);
    const body = { label: this.labelSig().trim(), permissionCodes: [...this.selected()], confirm: true };
    this.http.put(`/api/roles/${this.roleId}`, body).subscribe({
      next: () => {
        this.saving.set(false);
        this.msgs.add({ severity: 'success', summary: 'Rôle enregistré' });
        this.commitOriginal();
        this.router.navigate(['/roles']);
      },
      error: err => {
        this.saving.set(false);
        this.msgs.add({
          severity: 'error', summary: 'Erreur',
          detail: err?.error?.message ?? 'Enregistrement impossible',
        });
      },
    });
  }

  openDuplicate(): void {
    const r = this.role();
    if (!r) return;
    this.duplicateLabel.set(r.label + ' (copie)');
    this.duplicateVisible.set(true);
  }

  submitDuplicate(): void {
    const r = this.role();
    if (!r) return;
    const trimmed = this.duplicateLabel().trim();
    if (!trimmed) {
      this.msgs.add({ severity: 'warn', summary: 'Libellé requis' });
      return;
    }
    this.http.post<RoleRow>(`/api/roles/${r.id}/duplicate`, { label: trimmed }).subscribe({
      next: created => {
        this.duplicateVisible.set(false);
        this.msgs.add({ severity: 'success', summary: 'Rôle dupliqué' });
        this.router.navigate(['/roles', created.id]);
      },
      error: err => this.msgs.add({
        severity: 'error', summary: 'Erreur',
        detail: err?.error?.message ?? 'Duplication impossible',
      }),
    });
  }

  private commitOriginal(): void {
    const r = this.role();
    if (!r) return;
    this.original.set({ label: this.labelSig().trim(), perms: new Set(this.selected()) });
  }
}
