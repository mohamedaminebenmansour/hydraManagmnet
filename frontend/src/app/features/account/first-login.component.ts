import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { AuthService } from '@app/core/auth.service';
import { AccountService } from '@app/core/services/account.service';
import { PasswordFieldComponent } from '@app/shared/password-field.component';

@Component({
  selector: 'app-first-login',
  standalone: true,
  imports: [FormsModule, ButtonModule, InputTextModule, PasswordFieldComponent],
  template: `
    <div class="auth-page">
      <div class="auth-card">
        <div class="logo"><i class="pi pi-shield"></i></div>
        <h2>HYDRA</h2>
        <p class="welcome">Bienvenue, {{ firstName() }}. Choisissez votre mot de passe personnel.</p>

        <div class="field">
          <label>Mot de passe temporaire (actuel)</label>
          <input pInputText type="password" autocomplete="current-password"
                 [(ngModel)]="oldPassword" placeholder="Votre mot de passe temporaire" />
        </div>

        <div class="field">
          <label>Nouveau mot de passe</label>
          <app-password-field [showConfirm]="true" [serverError]="serverError()"
                              (valueChange)="newPassword = $event" />
        </div>

        <p-button label="Enregistrer" icon="pi pi-check" severity="success" [loading]="sending()"
                  (onClick)="submit()" styleClass="full-btn" />

        <p-button label="Se déconnecter" icon="pi pi-power-off" [text]="true"
                  (onClick)="logout()" styleClass="full-btn" />
      </div>
    </div>
  `,
  styles: [`
    .auth-page { min-height: 100vh; display: flex; align-items: center; justify-content: center;
      background: linear-gradient(160deg, #0f172a, #1e293b); padding: 20px; }
    .auth-card { background: #fff; border-radius: 16px; padding: 36px; width: 420px; max-width: 100%;
      display: flex; flex-direction: column; gap: 16px; }
    .logo { width: 56px; height: 56px; margin: 0 auto; border-radius: 14px; background: #0ea5e9;
      color: #fff; display: flex; align-items: center; justify-content: center; }
    .logo i { font-size: 1.8rem; }
    h2 { margin: 4px 0 0; text-align: center; letter-spacing: 3px; color: #0f172a; }
    .welcome { text-align: center; color: #475569; font-size: 14px; margin: 0 0 8px; }
    .field { display: flex; flex-direction: column; gap: 6px; }
    .field label { font-size: 13px; font-weight: 700; color: #475569; }
    :host ::ng-deep .full-btn { width: 100%; }
  `]
})
export class FirstLoginComponent {
  private auth = inject(AuthService);
  private account = inject(AccountService);
  private router = inject(Router);

  oldPassword = '';
  newPassword = '';
  sending = signal(false);
  serverError = signal<string | null>(null);

  firstName = signal('');

  constructor() {
    // Get the user's first name from /me (the auth guard already loaded it)
    const me = this.auth.me();
    if (me?.fullName) {
      this.firstName.set(me.fullName.split(' ')[0]);
    }
    // If me is not loaded, try to load it
    if (!me) {
      this.auth.check().subscribe(() => {
        const m = this.auth.me();
        if (m?.fullName) this.firstName.set(m.fullName.split(' ')[0]);
      });
    }
  }

  submit(): void {
    if (this.sending()) return;
    this.sending.set(true);
    this.serverError.set(null);
    this.account.changePassword(this.oldPassword, this.newPassword).subscribe({
      next: () => {
        // reload /me to get the updated mustChangePassword=false
        this.auth.check().subscribe(() => {
          this.sending.set(false);
          this.router.navigate(['/']);
        });
      },
      error: (err) => {
        this.sending.set(false);
        if (err?.status === 400 && err?.error?.code === 'WEAK_PASSWORD') {
          this.serverError.set(err.error.message ?? 'Mot de passe trop faible');
        } else if (err?.status === 409) {
          this.serverError.set(err?.error?.message ?? 'Ancien mot de passe incorrect');
        } else {
          this.serverError.set(err?.error?.message ?? 'Erreur lors du changement de mot de passe');
        }
      }
    });
  }

  logout(): void {
    this.auth.logout();
  }
}
