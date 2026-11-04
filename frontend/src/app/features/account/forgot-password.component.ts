import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { AccountService } from '@app/core/services/account.service';

@Component({
  selector: 'app-forgot-password',
  standalone: true,
  imports: [FormsModule, ButtonModule, InputTextModule, RouterLink],
  template: `
    <div class="auth-page">
      <div class="auth-card">
        <div class="logo"><i class="pi pi-shield"></i></div>
        <h2>HYDRA</h2>
        <p class="title">Mot de passe oublié</p>

        @if (mailEnabled()) {
          <div class="field">
            <label>Email</label>
            <input pInputText type="email" autocomplete="email"
                   [(ngModel)]="email" placeholder="Votre adresse email" />
          </div>
          <p-button label="Envoyer le lien" icon="pi pi-send" [loading]="sending()"
                    (onClick)="send()" styleClass="full-btn" />
          @if (message()) {
            <div class="info-msg">
              <i class="pi pi-info-circle"></i>
              <span>{{ message() }}</span>
            </div>
          }
          <p class="note">Pensez à vérifier vos courriers indésirables.</p>
        } @else {
          <p class="disabled-msg">
            Demandez à votre administrateur de réinitialiser votre mot de passe.
            Il vous donnera un mot de passe temporaire.
          </p>
        }

        <a class="back-link" routerLink="/login">← Retour à la connexion</a>
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
    .field label { font-size: 13px; font-weight: 700; color: #475569; }
    :host ::ng-deep .full-btn { width: 100%; }
    .info-msg { padding: 10px 14px; border-radius: 8px; background: #eff6ff; color: #1e40af;
      font-size: 14px; display: flex; gap: 8px; align-items: flex-start; }
    .note { font-size: 13px; color: #64748b; margin: 0; }
    .disabled-msg { color: #475569; font-size: 15px; line-height: 1.5; margin: 0; }
    .back-link { color: #0ea5e9; font-size: 14px; text-decoration: none; text-align: center; }
    .back-link:hover { text-decoration: underline; }
  `]
})
export class ForgotPasswordComponent implements OnInit {
  private account = inject(AccountService);
  private router = inject(Router);

  email = '';
  sending = signal(false);
  mailEnabled = signal(false);
  message = signal<string | null>(null);

  ngOnInit(): void {
    this.account.mailStatus().subscribe({
      next: s => this.mailEnabled.set(s.enabled),
      error: () => this.mailEnabled.set(false)
    });
  }

  send(): void {
    if (this.sending()) return;
    this.sending.set(true);
    this.message.set(null);
    this.account.forgotPassword(this.email).subscribe({
      next: (r) => {
        this.sending.set(false);
        this.message.set(r.message);
      },
      error: () => {
        this.sending.set(false);
        // Always show the neutral message, even on error
        this.message.set("Si un compte existe pour cette adresse, un lien vient d'y être envoyé.");
      }
    });
  }
}
