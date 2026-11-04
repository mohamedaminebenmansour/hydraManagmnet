import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { DatePickerModule } from 'primeng/datepicker';
import { DialogModule } from 'primeng/dialog';
import { MessageService } from 'primeng/api';
import { AuthService } from '@app/core/auth.service';
import { AccountService, MyProfile } from '@app/core/services/account.service';
import { PasswordFieldComponent } from '@app/shared/password-field.component';
import { roleVisual, projectRoleVisual, avatarGradient, initials } from '@app/shared/hydra-design';

@Component({
  selector: 'app-my-account',
  standalone: true,
  imports: [FormsModule, ButtonModule, InputTextModule, DatePickerModule, DialogModule, PasswordFieldComponent],
  template: `
    <div class="account-page">
      <h1>Mon compte</h1>

      @if (loading()) {
        <div class="loading">Chargement…</div>
      } @else if (error()) {
        <div class="app-load-error">
          <i class="pi pi-exclamation-triangle"></i> {{ error() }}
          <button pButton label="Réessayer" icon="pi pi-refresh" [text]="true" (click)="load()"></button>
        </div>
      } @else if (profile()) {
        <div class="sections">
          <!-- Profil -->
          <section class="card">
            <h2><i class="pi pi-user"></i> Profil</h2>
            <div class="avatar-row">
              <div class="big-avatar" [style.background]="avatarBg()">
                <span>{{ avatarInitials() }}</span>
              </div>
              <div class="avatar-info">
                <div class="full-name">{{ profile()?.fullName }}</div>
                <div class="email-row">
                  <span class="email">{{ profile()?.email }}</span>
                  <span class="email-note">Seul un administrateur peut modifier l'email</span>
                </div>
              </div>
            </div>
            <div class="field">
              <label>Nom complet</label>
              <input pInputText [(ngModel)]="editFullName" placeholder="Votre nom" />
            </div>
            <div class="field">
              <label>Téléphone</label>
              <input pInputText type="tel" [(ngModel)]="editPhone" placeholder="+216 …" />
            </div>
            <div class="field">
              <label>Fonction</label>
              <input pInputText [(ngModel)]="editJobTitle" placeholder="Ex. : Conducteur de travaux" />
            </div>
            <p-button label="Enregistrer" icon="pi pi-check" severity="success"
                      [disabled]="!profileChanged()" [loading]="savingProfile()"
                      (onClick)="saveProfile()" size="small"></p-button>
          </section>

          <!-- Sécurité -->
          <section class="card">
            <h2><i class="pi pi-shield"></i> Sécurité</h2>
            <div class="info-line">
              <i class="pi pi-clock"></i>
              <span>Dernière connexion : {{ lastLoginLabel() }}</span>
            </div>
            <div class="info-line">
              <i class="pi pi-key"></i>
              <span>Mot de passe modifié le {{ passwordChangedLabel() }}</span>
            </div>
            <div class="btn-col">
              <p-button label="Changer mon mot de passe" icon="pi pi-pencil" [outlined]="true"
                        (onClick)="showChangePw.set(true)" size="small"></p-button>
              <p-button label="Déconnecter mes autres appareils" icon="pi pi-times-circle"
                        severity="danger" [outlined]="true"
                        (onClick)="showRevoke.set(true)" size="small"></p-button>
            </div>
          </section>

          <!-- Mes accès -->
          <section class="card">
            <h2><i class="pi pi-id-card"></i> Mes accès</h2>
            <div class="roles">
              @for (r of profile()?.roles ?? []; track r.code) {
                <span class="role-chip" [style.--rc-color]="roleColor(r.code)"
                      [style.--rc-soft]="roleSoft(r.code)">
                  <i class="pi {{ roleIcon(r.code) }}"></i> {{ r.label }}
                </span>
              }
            </div>
            <div class="projects">
              @for (p of profile()?.projects ?? []; track p.id) {
                <div class="project-row">
                  <i class="pi pi-briefcase"></i>
                  <span class="proj-name">{{ p.name }}</span>
                  <span class="proj-role" [style.--prc-color]="projRoleColor(p.projectRole)">
                    {{ projRoleLabel(p.projectRole) }}
                  </span>
                </div>
              } @empty {
                <div class="empty">Aucun chantier assigné.</div>
              }
            </div>
            <p class="note">Pour changer vos accès, contactez le gérant.</p>
          </section>

          <!-- Absence -->
          <section class="card">
            <h2><i class="pi pi-calendar"></i> Absence</h2>
            @if (profile()?.absentUntil) {
              <div class="info-line absent">
                <i class="pi pi-user-minus"></i>
                <span>Vous êtes absent jusqu'au {{ absenceLabel() }}</span>
              </div>
            }
            <div class="field">
              <label>Je serai absent jusqu'au</label>
              <p-datepicker [ngModel]="absentUntil" (ngModelChange)="absentUntil = $event"
                            dateFormat="dd/mm/yy" [showIcon]="true" styleClass="w100"
                            placeholder="jj/mm/aa" />
            </div>
            <div class="btn-row">
              <p-button label="Enregistrer" icon="pi pi-check" severity="success"
                        [loading]="savingAbsence()" (onClick)="saveAbsence()" size="small"></p-button>
              @if (profile()?.absentUntil) {
                <p-button label="Je suis de retour" icon="pi pi-user-check" [text]="true"
                          (onClick)="clearAbsence()" size="small"></p-button>
              }
            </div>
          </section>
        </div>
      }
    </div>

    <!-- Change password dialog -->
    <p-dialog header="Changer mon mot de passe" [visible]="showChangePw()" (visibleChange)="showChangePw.set($event)"
              [modal]="true" [style]="{width: '420px'}">
      <div class="dlg">
        <div class="field">
          <label>Mot de passe actuel</label>
          <input pInputText type="password" autocomplete="current-password" [(ngModel)]="oldPw" />
        </div>
        <app-password-field [showConfirm]="true" [serverError]="pwServerError()"
                            (valueChange)="newPw = $event" />
      </div>
      <ng-template pTemplate="footer">
        <p-button label="Annuler" [text]="true" (onClick)="showChangePw.set(false)"></p-button>
        <p-button label="Enregistrer" icon="pi pi-check" severity="success"
                  [loading]="savingPw()" (onClick)="savePassword()"></p-button>
      </ng-template>
    </p-dialog>

    <!-- Revoke others confirm dialog -->
    <p-dialog header="Déconnecter les autres appareils" [visible]="showRevoke()" (visibleChange)="showRevoke.set($event)"
              [modal]="true" [style]="{width: '420px'}">
      <p>Vous allez être déconnecté de tous les autres appareils. Continuer ?</p>
      <ng-template pTemplate="footer">
        <p-button label="Annuler" [text]="true" (onClick)="showRevoke.set(false)"></p-button>
        <p-button label="Déconnecter" icon="pi pi-power-off" severity="danger"
                  [loading]="revoking()" (onClick)="confirmRevoke()"></p-button>
      </ng-template>
    </p-dialog>
  `,
  styles: [`
    .account-page { max-width: 900px; margin: 0 auto; padding: 20px; }
    h1 { font-size: 22px; color: #0f172a; margin: 0 0 20px; }
    .sections { display: grid; grid-template-columns: 1fr; gap: 16px; }
    @media (min-width: 1024px) {
      .sections { grid-template-columns: 1fr 1fr; }
    }
    .card { background: #fff; border-radius: 12px; padding: 20px; border: 1px solid #e2e8f0;
      display: flex; flex-direction: column; gap: 14px; }
    .card h2 { font-size: 16px; color: #0f172a; margin: 0; display: flex; align-items: center; gap: 8px; }
    .card h2 i { color: #0ea5e9; }
    .avatar-row { display: flex; align-items: center; gap: 14px; }
    .big-avatar { width: 56px; height: 56px; border-radius: 14px; display: flex; align-items: center;
      justify-content: center; color: #fff; font-weight: 800; font-size: 18px; }
    .avatar-info { flex: 1; min-width: 0; }
    .full-name { font-weight: 700; font-size: 16px; color: #0f172a; }
    .email-row { display: flex; flex-direction: column; }
    .email { font-size: 14px; color: #64748b; }
    .email-note { font-size: 11px; color: #94a3b8; }
    .field { display: flex; flex-direction: column; gap: 4px; }
    .field label { font-size: 13px; font-weight: 600; color: #475569; }
    .field input { font-size: 15px; }
    .info-line { display: flex; align-items: center; gap: 8px; font-size: 14px; color: #475569; }
    .info-line i { color: #64748b; }
    .info-line.absent { color: #b45309; }
    .info-line.absent i { color: #b45309; }
    .btn-col { display: flex; flex-direction: column; gap: 8px; align-items: flex-start; }
    .btn-row { display: flex; gap: 8px; align-items: center; }
    .roles { display: flex; flex-wrap: wrap; gap: 6px; }
    .role-chip { padding: 4px 10px; border-radius: 999px; font-size: 13px; font-weight: 600;
      color: var(--rc-color); background: var(--rc-soft); display: flex; align-items: center; gap: 4px; }
    .projects { display: flex; flex-direction: column; gap: 6px; }
    .project-row { display: flex; align-items: center; gap: 8px; font-size: 14px; color: #0f172a; }
    .project-row i { color: #64748b; }
    .proj-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .proj-role { font-size: 12px; font-weight: 600; color: var(--prc-color); }
    .note { font-size: 12px; color: #94a3b8; margin: 0; }
    .empty { font-size: 14px; color: #94a3b8; }
    .loading { text-align: center; color: #64748b; padding: 40px; }
    .app-load-error { display: flex; flex-direction: column; align-items: center; gap: 8px;
      color: #dc2626; padding: 40px; }
    .dlg { display: flex; flex-direction: column; gap: 12px; }
    :host ::ng-deep .w100 { width: 100%; }
  `]
})
export class MyAccountComponent implements OnInit {
  private account = inject(AccountService);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  profile = signal<MyProfile | null>(null);
  loading = signal(true);
  error = signal<string | null>(null);

  // Profil editing
  editFullName = '';
  editPhone = '';
  editJobTitle = '';
  savingProfile = signal(false);

  // Change password dialog
  showChangePw = signal(false);
  oldPw = '';
  newPw = '';
  savingPw = signal(false);
  pwServerError = signal<string | null>(null);

  // Revoke others dialog
  showRevoke = signal(false);
  revoking = signal(false);

  // Absence
  absentUntil: Date | null = null;
  savingAbsence = signal(false);

  avatarBg = computed(() => avatarGradient(this.profile()?.email ?? this.auth.me()?.email));
  avatarInitials = computed(() => initials(this.profile()?.fullName ?? this.auth.me()?.fullName));

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.error.set(null);
    this.account.myProfile().subscribe({
      next: (p) => {
        this.profile.set(p);
        this.editFullName = p.fullName;
        this.editPhone = p.phone;
        this.editJobTitle = p.jobTitle;
        if (p.absentUntil) {
          const d = new Date(p.absentUntil);
          if (!isNaN(d.getTime())) this.absentUntil = d;
        }
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('Impossible de charger votre profil.');
      }
    });
  }

  profileChanged(): boolean {
    const p = this.profile();
    if (!p) return false;
    return this.editFullName !== p.fullName
      || this.editPhone !== (p.phone ?? '')
      || this.editJobTitle !== (p.jobTitle ?? '');
  }

  saveProfile(): void {
    this.savingProfile.set(true);
    this.account.updateProfile(this.editFullName, this.editPhone, this.editJobTitle).subscribe({
      next: (p) => {
        this.profile.set(p);
        this.savingProfile.set(false);
        this.msgs.add({ severity: 'success', summary: 'Profil enregistré' });
      },
      error: (err) => {
        this.savingProfile.set(false);
        this.msgs.add({ severity: 'error', summary: 'Erreur',
          detail: err?.error?.message ?? 'Enregistrement impossible' });
      }
    });
  }

  savePassword(): void {
    this.savingPw.set(true);
    this.pwServerError.set(null);
    this.account.changePassword(this.oldPw, this.newPw).subscribe({
      next: () => {
        this.savingPw.set(false);
        this.showChangePw.set(false);
        this.oldPw = '';
        this.newPw = '';
        this.msgs.add({ severity: 'success', summary: 'Mot de passe modifié' });
      },
      error: (err) => {
        this.savingPw.set(false);
        if (err?.status === 400 && err?.error?.code === 'WEAK_PASSWORD') {
          this.pwServerError.set(err.error.message ?? 'Mot de passe trop faible');
        } else {
          this.pwServerError.set(err?.error?.message ?? 'Erreur');
        }
      }
    });
  }

  confirmRevoke(): void {
    this.revoking.set(true);
    this.account.revokeOthers().subscribe({
      next: () => {
        this.revoking.set(false);
        this.showRevoke.set(false);
        this.msgs.add({ severity: 'success', summary: 'Autres appareils déconnectés' });
      },
      error: (err) => {
        this.revoking.set(false);
        this.msgs.add({ severity: 'error', summary: 'Erreur',
          detail: err?.error?.message ?? 'Action impossible' });
      }
    });
  }

  saveAbsence(): void {
    this.savingAbsence.set(true);
    const dateStr = this.absentUntil ? this.toYmd(this.absentUntil) : null;
    this.account.setAbsence(dateStr).subscribe({
      next: () => {
        this.savingAbsence.set(false);
        // reload profile to reflect the change
        this.load();
        this.msgs.add({ severity: 'success', summary: 'Absence enregistrée' });
      },
      error: (err) => {
        this.savingAbsence.set(false);
        this.msgs.add({ severity: 'error', summary: 'Erreur',
          detail: err?.error?.message ?? 'Enregistrement impossible' });
      }
    });
  }

  clearAbsence(): void {
    this.absentUntil = null;
    this.saveAbsence();
  }

  // ── formatting helpers ─────────────────────────────────────────────
  lastLoginLabel(): string {
    const v = this.profile()?.lastLoginAt;
    if (!v) return '—';
    return this.relativeTime(v);
  }

  passwordChangedLabel(): string {
    const v = this.profile()?.passwordChangedAt;
    if (!v) return '—';
    const d = new Date(v);
    return isNaN(d.getTime()) ? '—' : d.toLocaleDateString('fr-FR');
  }

  absenceLabel(): string {
    const v = this.profile()?.absentUntil;
    if (!v) return '';
    const d = new Date(v);
    return isNaN(d.getTime()) ? '' : d.toLocaleDateString('fr-FR');
  }

  private relativeTime(iso: string): string {
    const d = new Date(iso);
    if (isNaN(d.getTime())) return '—';
    const diff = Date.now() - d.getTime();
    const h = Math.floor(diff / 3600000);
    if (h < 1) return "à l'instant";
    if (h < 24) return `il y a ${h} h`;
    const days = Math.floor(h / 24);
    if (days < 30) return `il y a ${days} j`;
    return d.toLocaleDateString('fr-FR');
  }

  private toYmd(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }

  // ── role visual helpers ─────────────────────────────────────────────
  roleColor(code: string): string { return roleVisual(code).color; }
  roleSoft(code: string): string { return roleVisual(code).soft; }
  roleIcon(code: string): string { return roleVisual(code).icon; }
  projRoleColor(role: string): string { return projectRoleVisual(role).color; }
  projRoleLabel(role: string): string { return projectRoleVisual(role).label; }
}
