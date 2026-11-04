import { Component, EventEmitter, Input, Output, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { DatePickerModule } from 'primeng/datepicker';
import { DrawerModule } from 'primeng/drawer';
import { DialogModule } from 'primeng/dialog';
import { MessageService } from 'primeng/api';
import { UserService } from '@app/core/services/user.service';
import { AccountService } from '@app/core/services/account.service';
import { UserDto, UserActivity } from '@app/core/models/user.model';
import { roleVisual, avatarGradient, initials, ROLE_OPTIONS } from '@app/shared/hydra-design';
import { userStatusVisual } from './user-status';

@Component({
  selector: 'app-user-panel',
  standalone: true,
  imports: [FormsModule, ButtonModule, InputTextModule, DatePickerModule, DrawerModule, DialogModule, RouterLink],
  template: `
    <p-drawer [visible]="visible()" (visibleChange)="visible.set($event)" position="right"
              [style]="{width: '560px'}" styleClass="user-panel-drawer">
      @if (user()) {
        <!-- Header -->
        <div class="panel-header" [style.background]="avatarBg()">
          <div class="big-avatar">
            <span>{{ avatarInitials() }}</span>
          </div>
          <div class="header-info">
            <div class="header-name">{{ user()?.fullName }}</div>
            <div class="header-status">
              <span class="status-pill" [style.color]="statusV().color" [style.background]="statusV().soft">
                <i class="pi {{ statusV().icon }}"></i> {{ statusV().label }}
              </span>
            </div>
            <div class="header-roles">
              @for (r of user()?.roleCodes ?? []; track r) {
                <span class="role-chip" [style.--rc-color]="roleColor(r)" [style.--rc-soft]="roleSoft(r)">
                  <i class="pi {{ roleIcon(r) }}"></i> {{ roleLabel(r) }}
                </span>
              }
            </div>
          </div>
        </div>

        <!-- Tabs -->
        <div class="tabs">
          <button class="tab" [class.active]="tab() === 'profil'" (click)="tab.set('profil')">Profil</button>
          <button class="tab" [class.active]="tab() === 'roles'" (click)="tab.set('roles')">Rôles</button>
          <button class="tab" [class.active]="tab() === 'chantiers'" (click)="tab.set('chantiers')">Chantiers</button>
          <button class="tab" [class.active]="tab() === 'activite'" (click)="tab.set('activite')">Activité</button>
          <button class="tab" [class.active]="tab() === 'securite'" (click)="tab.set('securite')">Sécurité</button>
        </div>

        <div class="tab-content">
          @if (tab() === 'profil') {
            <div class="fields">
              <div class="field">
                <label>Nom complet</label>
                <input pInputText [(ngModel)]="editFullName" />
              </div>
              <div class="field">
                <label>Email (non modifiable)</label>
                <input pInputText [value]="user()?.email ?? ''" disabled />
              </div>
              <div class="field">
                <label>Téléphone</label>
                <input pInputText type="tel" [(ngModel)]="editPhone" />
              </div>
              <div class="field">
                <label>Fonction</label>
                <input pInputText [(ngModel)]="editJobTitle" />
              </div>
              <p-button label="Enregistrer" icon="pi pi-check" severity="success" size="small"
                        [loading]="savingProfile()" (onClick)="saveProfile()"></p-button>
            </div>
          }

          @if (tab() === 'roles') {
            <div class="fields">
              <div class="role-list">
                @for (r of roleOptions; track r.code) {
                  <label class="role-item" [style.--rc-color]="r.color" [style.--rc-soft]="r.soft">
                    <input type="checkbox" [checked]="editRoles.has(r.code)" (change)="toggleRole(r.code)" />
                    <i class="pi {{ r.icon }}"></i>
                    <span>{{ r.label }}</span>
                  </label>
                }
              </div>
              @if (conflictMsg()) {
                <div class="conflict">
                  <i class="pi pi-exclamation-triangle"></i> {{ conflictMsg() }}
                  <div class="conflict-actions">
                    <input pInputText [(ngModel)]="overrideReason" placeholder="Motif de l'exception signée" />
                    <p-button label="Forcer quand même" severity="warn" size="small" (onClick)="saveRoles(true)"></p-button>
                  </div>
                </div>
              }
              <p-button label="Enregistrer" icon="pi pi-check" severity="success" size="small"
                        [loading]="savingRoles()" (onClick)="saveRoles(false)"></p-button>
            </div>
          }

          @if (tab() === 'chantiers') {
            <div class="chantiers">
              @if ((user()?.projectNames ?? []).length === 0) {
                <p class="empty">Aucun chantier assigné.</p>
              } @else {
                @for (p of user()?.projectNames ?? []; track p) {
                  <div class="chantier-row">
                    <i class="pi pi-briefcase"></i>
                    <span>{{ p }}</span>
                  </div>
                }
              }
            </div>
          }

          @if (tab() === 'activite') {
            <div class="activity">
              @if (activityLoading()) {
                <p class="loading">Chargement…</p>
              } @else if (activity().length === 0) {
                <p class="empty">Aucune activité récente.</p>
              } @else {
                @for (a of activity(); track a.at) {
                  <div class="activity-row">
                    <i class="pi pi-clock"></i>
                    <div class="act-info">
                      <span class="act-action">{{ a.action }}</span>
                      @if (a.subjectLabel) { <span class="act-subject">{{ a.subjectLabel }}</span> }
                      <span class="act-time">{{ relativeTime(a.at) }}</span>
                    </div>
                  </div>
                }
              }
            </div>
          }

          @if (tab() === 'securite') {
            <div class="securite">
              <p-button label="Réinitialiser le mot de passe" icon="pi pi-key" [outlined]="true" size="small"
                        styleClass="full-btn" (onClick)="resetPassword()"></p-button>
              @if (mailEnabled()) {
                <p-button label="Envoyer un lien de réinitialisation" icon="pi pi-send" [outlined]="true" size="small"
                          styleClass="full-btn" (onClick)="sendResetLink()"></p-button>
                @if (isPending()) {
                  <p-button label="Renvoyer l'invitation" icon="pi pi-envelope" [outlined]="true" size="small"
                            styleClass="full-btn" (onClick)="resendInvitation()"></p-button>
                }
              }
              @if (!isOwner()) {
                <p-button label="Désactiver le compte" icon="pi pi-ban" severity="danger" [outlined]="true" size="small"
                          styleClass="full-btn" (onClick)="showDeactivate.set(true)"></p-button>
              }
            </div>
          }
        </div>
      }
    </p-drawer>

    <!-- Deactivate confirm -->
    <p-dialog header="Désactiver le compte" [visible]="showDeactivate()" (visibleChange)="showDeactivate.set($event)"
              [modal]="true" [style]="{width: '400px'}">
      <p>Voulez-vous vraiment désactiver le compte de <strong>{{ user()?.fullName }}</strong> ?</p>
      <ng-template pTemplate="footer">
        <p-button label="Annuler" [text]="true" (onClick)="showDeactivate.set(false)"></p-button>
        <p-button label="Désactiver" icon="pi pi-ban" severity="danger"
                  [loading]="deactivating()" (onClick)="confirmDeactivate()"></p-button>
      </ng-template>
    </p-dialog>
  `,
  styles: [`
    .panel-header { padding: 20px; display: flex; gap: 14px; align-items: center; color: #fff; }
    .big-avatar { width: 52px; height: 52px; border-radius: 14px; background: rgba(255,255,255,0.25);
      display: flex; align-items: center; justify-content: center; font-weight: 800; font-size: 18px; }
    .header-info { flex: 1; min-width: 0; }
    .header-name { font-weight: 700; font-size: 17px; }
    .header-status { margin: 4px 0; }
    .status-pill { padding: 2px 8px; border-radius: 999px; font-size: 12px; font-weight: 600; display: inline-flex;
      align-items: center; gap: 4px; }
    .header-roles { display: flex; flex-wrap: wrap; gap: 4px; }
    .role-chip { padding: 2px 8px; border-radius: 999px; font-size: 11px; font-weight: 600;
      color: var(--rc-color); background: var(--rc-soft); display: inline-flex; align-items: center; gap: 3px; }
    .tabs { display: flex; gap: 2px; padding: 0 12px; border-bottom: 1px solid #e2e8f0; overflow-x: auto; }
    .tab { padding: 10px 12px; border: none; background: none; cursor: pointer; font-size: 13px; font-weight: 600;
      color: #64748b; white-space: nowrap; border-bottom: 2px solid transparent; }
    .tab.active { color: #0ea5e9; border-bottom-color: #0ea5e9; }
    .tab-content { padding: 16px; overflow-y: auto; }
    .fields { display: flex; flex-direction: column; gap: 12px; }
    .field { display: flex; flex-direction: column; gap: 4px; }
    .field label { font-size: 13px; font-weight: 600; color: #475569; }
    .field input { font-size: 15px; }
    .role-list { display: flex; flex-wrap: wrap; gap: 6px; }
    .role-item { display: flex; align-items: center; gap: 6px; padding: 6px 10px; border-radius: 999px;
      font-size: 13px; font-weight: 600; cursor: pointer; border: 1px solid #e2e8f0;
      color: var(--rc-color); background: var(--rc-soft); }
    .role-item input { accent-color: var(--rc-color); }
    .conflict { padding: 10px 12px; border-radius: 8px; background: #fffbeb; color: #b45309; font-size: 13px;
      display: flex; flex-direction: column; gap: 8px; }
    .conflict-actions { display: flex; gap: 6px; align-items: center; }
    .conflict-actions input { flex: 1; font-size: 13px; }
    .chantiers { display: flex; flex-direction: column; gap: 8px; }
    .chantier-row { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-radius: 8px;
      border: 1px solid #e2e8f0; font-size: 14px; }
    .chantier-row i { color: #64748b; }
    .activity { display: flex; flex-direction: column; gap: 8px; }
    .activity-row { display: flex; gap: 8px; padding: 8px 0; border-bottom: 1px solid #f1f5f9; }
    .activity-row i { color: #94a3b8; font-size: 14px; margin-top: 2px; }
    .act-info { display: flex; flex-direction: column; gap: 2px; }
    .act-action { font-size: 14px; font-weight: 600; color: #0f172a; }
    .act-subject { font-size: 13px; color: #475569; }
    .act-time { font-size: 12px; color: #94a3b8; }
    .securite { display: flex; flex-direction: column; gap: 8px; }
    :host ::ng-deep .full-btn { width: 100%; }
    .empty, .loading { color: #94a3b8; font-size: 14px; }
  `]
})
export class UserPanelComponent {
  private userService = inject(UserService);
  private accountService = inject(AccountService);
  private msgs = inject(MessageService);

  visible = signal(false);
  user = signal<UserDto | null>(null);
  tab = signal<'profil' | 'roles' | 'chantiers' | 'activite' | 'securite'>('profil');

  // Profil editing
  editFullName = '';
  editPhone = '';
  editJobTitle = '';
  savingProfile = signal(false);

  // Roles editing
  editRoles = new Set<string>();
  overrideReason = '';
  conflictMsg = signal<string | null>(null);
  savingRoles = signal(false);

  // Activity
  activity = signal<UserActivity[]>([]);
  activityLoading = signal(false);

  // Mail
  mailEnabled = signal(false);

  // Deactivate
  showDeactivate = signal(false);
  deactivating = signal(false);

  roleOptions = ROLE_OPTIONS;

  @Output() userChanged = new EventEmitter<UserDto>();
  @Output() tempPasswordShown = new EventEmitter<{ name: string; pw: string }>();

  avatarBg = computed(() => avatarGradient(this.user()?.email));
  avatarInitials = computed(() => initials(this.user()?.fullName));
  statusV = computed(() => userStatusVisual(this.user()?.status));

  isOwner = computed(() => this.user()?.isOwner ?? false);
  isPending = computed(() => this.user()?.status === 'PENDING');

  constructor() {
    this.accountService.mailStatus().subscribe({
      next: s => this.mailEnabled.set(s.enabled),
      error: () => this.mailEnabled.set(false)
    });
  }

  open(u: UserDto): void {
    this.user.set(u);
    this.visible.set(true);
    this.tab.set('profil');
    this.editFullName = u.fullName;
    this.editPhone = u.phone ?? '';
    this.editJobTitle = u.jobTitle ?? '';
    this.editRoles = new Set(u.roleCodes);
    this.overrideReason = '';
    this.conflictMsg.set(null);
  }

  saveProfile(): void {
    const u = this.user();
    if (!u) return;
    this.savingProfile.set(true);
    this.userService.update(u.id, { fullName: this.editFullName }).subscribe({
      next: (updated) => {
        this.savingProfile.set(false);
        this.user.set(updated);
        this.userChanged.emit(updated);
        this.msgs.add({ severity: 'success', summary: 'Profil enregistré' });
      },
      error: (err) => {
        this.savingProfile.set(false);
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err?.error?.message ?? 'Erreur' });
      }
    });
  }

  toggleRole(code: string): void {
    if (this.editRoles.has(code)) this.editRoles.delete(code);
    else this.editRoles.add(code);
  }

  saveRoles(force: boolean): void {
    const u = this.user();
    if (!u) return;
    this.savingRoles.set(true);
    this.conflictMsg.set(null);
    this.userService.assignRoles(u.id, {
      roleCodes: [...this.editRoles],
      overrideReason: force ? this.overrideReason : undefined
    }).subscribe({
      next: (updated) => {
        this.savingRoles.set(false);
        this.user.set(updated);
        this.userChanged.emit(updated);
        this.msgs.add({ severity: 'success', summary: 'Rôles mis à jour' });
      },
      error: (err) => {
        this.savingRoles.set(false);
        if (err?.status === 409 && err?.error?.code === 'TOXIC_PAIR') {
          this.conflictMsg.set(err.error.message ?? 'Conflit de rôles');
        } else {
          this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err?.error?.message ?? 'Erreur' });
        }
      }
    });
  }

  loadActivity(): void {
    const u = this.user();
    if (!u) return;
    this.activityLoading.set(true);
    this.userService.activity(u.id).subscribe({
      next: (a) => { this.activity.set(a); this.activityLoading.set(false); },
      error: () => { this.activity.set([]); this.activityLoading.set(false); }
    });
  }

  resetPassword(): void {
    const u = this.user();
    if (!u) return;
    this.userService.resetPassword(u.id).subscribe({
      next: (r) => {
        if (r.temporaryPassword) {
          this.tempPasswordShown.emit({ name: u.fullName, pw: r.temporaryPassword });
          this.msgs.add({ severity: 'info', summary: 'Mot de passe réinitialisé',
            detail: 'Le nouveau mot de passe temporaire est affiché.' });
        } else {
          this.msgs.add({ severity: 'success', summary: 'Mot de passe réinitialisé' });
        }
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err?.error?.message ?? 'Erreur' })
    });
  }

  sendResetLink(): void {
    const u = this.user();
    if (!u) return;
    this.userService.sendResetLink(u.id).subscribe({
      next: () => this.msgs.add({ severity: 'success', summary: 'Lien envoyé',
        detail: `à ${u.email}` }),
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err?.error?.message ?? "L'email n'est pas configuré" })
    });
  }

  resendInvitation(): void {
    const u = this.user();
    if (!u) return;
    this.userService.resendInvitation(u.id).subscribe({
      next: () => this.msgs.add({ severity: 'success', summary: 'Invitation renvoyée',
        detail: `à ${u.email}` }),
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err?.error?.message ?? 'Erreur' })
    });
  }

  confirmDeactivate(): void {
    const u = this.user();
    if (!u) return;
    this.deactivating.set(true);
    this.userService.setActive(u.id, false).subscribe({
      next: (updated) => {
        this.deactivating.set(false);
        this.showDeactivate.set(false);
        this.user.set(updated);
        this.userChanged.emit(updated);
        this.msgs.add({ severity: 'warn', summary: 'Compte désactivé' });
      },
      error: (err) => {
        this.deactivating.set(false);
        this.msgs.add({ severity: 'error', summary: 'Erreur',
          detail: err?.error?.message ?? 'Erreur' });
      }
    });
  }

  // ── helpers ────────────────────────────────────────────────────────
  roleColor(code: string): string { return roleVisual(code).color; }
  roleSoft(code: string): string { return roleVisual(code).soft; }
  roleIcon(code: string): string { return roleVisual(code).icon; }
  roleLabel(code: string): string { return roleVisual(code).label; }

  relativeTime(iso: string): string {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '';
    const diff = Date.now() - d.getTime();
    const m = Math.floor(diff / 60000);
    if (m < 1) return "à l'instant";
    if (m < 60) return `il y a ${m} min`;
    const h = Math.floor(m / 60);
    if (h < 24) return `il y a ${h} h`;
    const days = Math.floor(h / 24);
    if (days < 30) return `il y a ${days} j`;
    return d.toLocaleDateString('fr-FR');
  }
}
