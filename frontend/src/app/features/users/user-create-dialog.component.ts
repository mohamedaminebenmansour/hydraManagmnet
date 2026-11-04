import { Component, EventEmitter, Output, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { DialogModule } from 'primeng/dialog';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { MessageService } from 'primeng/api';
import { UserService } from '@app/core/services/user.service';
import { AccountService } from '@app/core/services/account.service';
import { ROLE_OPTIONS } from '@app/shared/hydra-design';
import { UserDto } from '@app/core/models/user.model';

@Component({
  selector: 'app-user-create-dialog',
  standalone: true,
  imports: [FormsModule, ButtonModule, InputTextModule, DialogModule, ToggleSwitchModule],
  template: `
    <p-dialog header="Nouvel utilisateur" [visible]="visible()" (visibleChange)="visible.set($event)"
              [modal]="true" [style]="{width: '480px'}" [dismissableMask]="true">
      <div class="dlg">
        <div class="field">
          <label>Nom complet*</label>
          <input pInputText [(ngModel)]="fullName" placeholder="Ex. : Ahmed Ben Salah" />
        </div>
        <div class="field">
          <label>Email*</label>
          <input pInputText type="email" [(ngModel)]="email" placeholder="ahmed@example.com" />
        </div>
        <div class="field-row">
          <div class="field">
            <label>Téléphone</label>
            <input pInputText type="tel" [(ngModel)]="phone" placeholder="+216 …" />
          </div>
          <div class="field">
            <label>Fonction</label>
            <input pInputText [(ngModel)]="jobTitle" placeholder="Ex. : Conducteur" />
          </div>
        </div>
        <div class="field">
          <label>Rôles*</label>
          <div class="role-list">
            @for (r of roleOptions; track r.code) {
              <label class="role-item" [style.--rc-color]="r.color" [style.--rc-soft]="r.soft">
                <input type="checkbox" [checked]="selectedRoles.has(r.code)" (change)="toggleRole(r.code)" />
                <i class="pi {{ r.icon }}"></i>
                <span>{{ r.label }}</span>
              </label>
            }
          </div>
        </div>
        @if (mailEnabled()) {
          <div class="invite-toggle">
            <label class="toggle-row">
              <p-toggleswitch [(ngModel)]="sendInvitation" />
              <span>Envoyer une invitation par email</span>
            </label>
            <p class="toggle-hint">L'utilisateur recevra un lien pour choisir son mot de passe.</p>
          </div>
        } @else {
          <p class="no-mail">L'email n'est pas configuré : un mot de passe temporaire sera généré.</p>
        }
        @if (error()) {
          <div class="err-msg"><i class="pi pi-exclamation-circle"></i> {{ error() }}</div>
        }
      </div>
      <ng-template pTemplate="footer">
        <p-button label="Annuler" [text]="true" (onClick)="close()"></p-button>
        <p-button label="Créer" icon="pi pi-check" severity="success" [loading]="saving()"
                  (onClick)="submit()"></p-button>
      </ng-template>
    </p-dialog>

    <!-- Result dialog (temp password) -->
    <p-dialog header="Compte créé" [visible]="resultVisible()" (visibleChange)="resultVisible.set($event)"
              [modal]="true" [style]="{width: '460px'}" [closable]="false">
      @if (resultName()) {
        <div class="result-dlg">
          <p>Compte créé pour <strong>{{ resultName() }}</strong></p>
          <div class="pw-box">
            <code class="pw-code">{{ tempPw() }}</code>
            <button class="copy-btn" (click)="copyPw()" aria-label="Copier le mot de passe">
              <i class="pi pi-copy"></i> Copier
            </button>
          </div>
          <div class="warning">
            <i class="pi pi-exclamation-triangle"></i>
            Ce mot de passe ne sera plus affiché. Transmettez-le en main propre ou par téléphone.
            {{ resultName() }} devra le changer à sa première connexion.
          </div>
        </div>
      }
      <ng-template pTemplate="footer">
        <p-button label="J'ai transmis le mot de passe" icon="pi pi-check" severity="success"
                  (onClick)="closeResult()"></p-button>
      </ng-template>
    </p-dialog>
  `,
  styles: [`
    .dlg { display: flex; flex-direction: column; gap: 12px; }
    .field { display: flex; flex-direction: column; gap: 4px; }
    .field label { font-size: 13px; font-weight: 600; color: #475569; }
    .field-row { display: flex; gap: 12px; }
    .field-row .field { flex: 1; }
    .role-list { display: flex; flex-wrap: wrap; gap: 6px; }
    .role-item { display: flex; align-items: center; gap: 6px; padding: 6px 10px; border-radius: 999px;
      font-size: 13px; font-weight: 600; cursor: pointer; border: 1px solid #e2e8f0;
      color: var(--rc-color); background: var(--rc-soft); }
    .role-item input { accent-color: var(--rc-color); }
    .role-item i { font-size: 14px; }
    .invite-toggle { padding: 12px; border-radius: 8px; background: #f8fafc; }
    .toggle-row { display: flex; align-items: center; gap: 8px; font-size: 14px; font-weight: 600; }
    .toggle-hint { font-size: 12px; color: #64748b; margin: 4px 0 0; }
    .no-mail { font-size: 13px; color: #b45309; padding: 8px 12px; background: #fffbeb; border-radius: 8px; }
    .err-msg { color: #dc2626; font-size: 13px; display: flex; gap: 6px; align-items: center; }
    .result-dlg { display: flex; flex-direction: column; gap: 12px; }
    .pw-box { display: flex; align-items: center; gap: 8px; padding: 12px; border-radius: 8px;
      background: #0f172a; }
    .pw-code { font-family: monospace; font-size: 20px; color: #22d3ee; flex: 1; word-break: break-all; }
    .copy-btn { background: #1e293b; border: none; color: #e2e8f0; padding: 6px 12px; border-radius: 6px;
      cursor: pointer; display: flex; align-items: center; gap: 4px; font-size: 13px; }
    .copy-btn:hover { background: #334155; }
    .warning { font-size: 13px; color: #b45309; padding: 10px 12px; background: #fffbeb; border-radius: 8px;
      display: flex; gap: 8px; align-items: flex-start; line-height: 1.4; }
  `]
})
export class UserCreateDialogComponent {
  private userService = inject(UserService);
  private accountService = inject(AccountService);
  private msgs = inject(MessageService);

  visible = signal(false);
  @Output() created = new EventEmitter<UserDto>();

  fullName = '';
  email = '';
  phone = '';
  jobTitle = '';
  sendInvitation = true;
  selectedRoles = new Set<string>();
  saving = signal(false);
  error = signal<string | null>(null);
  mailEnabled = signal(false);

  // result dialog
  resultVisible = signal(false);
  resultName = signal('');
  tempPw = signal('');

  roleOptions = ROLE_OPTIONS;

  show(): void { this.visible.set(true); }

  constructor() {
    this.accountService.mailStatus().subscribe({
      next: s => { this.mailEnabled.set(s.enabled); this.sendInvitation = s.enabled; },
      error: () => this.mailEnabled.set(false)
    });
  }

  toggleRole(code: string): void {
    if (this.selectedRoles.has(code)) this.selectedRoles.delete(code);
    else this.selectedRoles.add(code);
  }

  submit(): void {
    if (this.saving()) return;
    if (!this.fullName.trim() || !this.email.trim()) {
      this.error.set('Nom et email sont obligatoires');
      return;
    }
    if (this.selectedRoles.size === 0) {
      this.error.set('Sélectionnez au moins un rôle');
      return;
    }
    this.saving.set(true);
    this.error.set(null);

    this.userService.create({
      fullName: this.fullName.trim(),
      email: this.email.trim(),
      password: '', // blank → temp generated or invitation
      roleCodes: [...this.selectedRoles],
      sendInvitation: this.mailEnabled() && this.sendInvitation
    }).subscribe({
      next: (u) => {
        this.saving.set(false);
        this.visible.set(false);
        this.created.emit(u);
        if (u.temporaryPassword) {
          // show the result dialog with the temp password
          this.resultName.set(u.fullName);
          this.tempPw.set(u.temporaryPassword);
          this.resultVisible.set(true);
        } else if (u.invitationSent) {
          this.msgs.add({ severity: 'success', summary: 'Invitation envoyée', detail: `à ${u.email}` });
        } else {
          this.msgs.add({ severity: 'success', summary: 'Utilisateur créé' });
        }
        this.resetForm();
      },
      error: (err) => {
        this.saving.set(false);
        this.error.set(err?.error?.message ?? 'Création impossible');
      }
    });
  }

  /** Show the result dialog with a temp password (called from reset-password flow). */
  showTempPassword(name: string, pw: string): void {
    this.resultName.set(name);
    this.tempPw.set(pw);
    this.resultVisible.set(true);
  }

  copyPw(): void {
    navigator.clipboard.writeText(this.tempPw()).then(() => {
      this.msgs.add({ severity: 'info', summary: 'Copié', detail: 'Mot de passe copié dans le presse-papiers' });
    });
  }

  closeResult(): void {
    this.resultVisible.set(false);
    // Never keep the temporary password in any variable after the dialog closes
    this.tempPw.set('');
    this.resultName.set('');
  }

  close(): void {
    this.visible.set(false);
    this.resetForm();
  }

  private resetForm(): void {
    this.fullName = '';
    this.email = '';
    this.phone = '';
    this.jobTitle = '';
    this.selectedRoles.clear();
    this.error.set(null);
  }
}
