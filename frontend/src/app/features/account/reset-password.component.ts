import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { AccountService } from '@app/core/services/account.service';
import { PasswordFieldComponent } from '@app/shared/password-field.component';

@Component({
  selector: 'app-reset-password',
  standalone: true,
  imports: [FormsModule, ButtonModule, PasswordFieldComponent, RouterLink],
  template: `
    <div class="auth-page">
      <div class="auth-card">
        <div class="logo"><i class="pi pi-shield"></i></div>
        <h2>HYDRA</h2>

        @if (loading()) {
          <p class="title">Vérification du lien…</p>
        } @else if (!valid()) {
          <p class="title">Lien invalide</p>
          <div class="info-msg error">
            <i class="pi pi-times-circle"></i>
            <span>Ce lien n'est plus valide.</span>
          </div>
          <a class="back-link" routerLink="/mot-de-passe-oublie">Demander un nouveau lien</a>
        } @else {
          <p class="title">{{ title() }}</p>

          <div class="field">
            <app-password-field [showConfirm]="true" [serverError]="serverError()"
                                (valueChange)="newPassword = $event" />
          </div>

          <p-button label="Enregistrer" icon="pi pi-check" severity="success" [loading]="sending()"
                    (onClick)="submit()" styleClass="full-btn" />

          <a class="back-link" routerLink="/login">← Retour à la connexion</a>
        }
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
    .title { text-align: center; color: #475569; font-size: 16px; font-weight: 600; margin: 0 0 8px; }
    .field { display: flex; flex-direction: column; gap: 6px; }
    :host ::ng-deep .full-btn { width: 100%; }
    .info-msg { padding: 10px 14px; border-radius: 8px; font-size: 14px;
      display: flex; gap: 8px; align-items: flex-start; }
    .info-msg.error { background: #fef2f2; color: #991b1b; }
    .back-link { color: #0ea5e9; font-size: 14px; text-decoration: none; text-align: center; }
    .back-link:hover { text-decoration: underline; }
  `]
})
export class ResetPasswordComponent implements OnInit {
  private account = inject(AccountService);
  private router = inject(Router);

  token = '';
  newPassword = '';
  loading = signal(true);
  valid = signal(false);
  purpose = signal('');
  firstName = signal('');
  sending = signal(false);
  serverError = signal<string | null>(null);

  title = signal('Nouveau mot de passe');

  ngOnInit(): void {
    // Read the token from the query string then remove it from the address bar
    const params = new URLSearchParams(window.location.search);
    this.token = params.get('token') ?? '';
    if (this.token) {
      // Remove the token from the address bar right after reading it
      history.replaceState({}, '', '/reinitialiser');
    }

    if (!this.token) {
      this.loading.set(false);
      this.valid.set(false);
      return;
    }

    this.account.checkToken(this.token).subscribe({
      next: (r) => {
        this.loading.set(false);
        this.valid.set(r.valid);
        if (r.valid) {
          this.purpose.set(r.purpose ?? '');
          this.firstName.set(r.firstName ?? '');
          if (r.purpose === 'INVITE') {
            this.title.set(`Bienvenue ${r.firstName ?? ''}, créez votre mot de passe`);
          } else {
            this.title.set('Nouveau mot de passe');
          }
        }
      },
      error: () => {
        this.loading.set(false);
        this.valid.set(false);
      }
    });
  }

  submit(): void {
    if (this.sending()) return;
    this.sending.set(true);
    this.serverError.set(null);
    this.account.resetPassword(this.token, this.newPassword).subscribe({
      next: () => {
        this.sending.set(false);
        this.router.navigate(['/login'], { queryParams: { reset: 'ok' } });
      },
      error: (err) => {
        this.sending.set(false);
        if (err?.status === 400 && err?.error?.code === 'WEAK_PASSWORD') {
          this.serverError.set(err.error.message ?? 'Mot de passe trop faible');
        } else if (err?.status === 400 && err?.error?.code === 'TOKEN_INVALID') {
          // The token became invalid between the check and the submit
          this.valid.set(false);
        } else {
          this.serverError.set(err?.error?.message ?? 'Erreur lors de la réinitialisation');
        }
      }
    });
  }
}
