import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { AuthService } from '../../core/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [FormsModule, ButtonModule, InputTextModule],
  template: `
    <div class="login-page">
      <div class="login-card">
        <div class="logo"><i class="pi pi-shield"></i></div>
        <h2>HYDRA</h2>
        <p class="sub">Suivi des chantiers</p>
        <input pInputText type="email" placeholder="Email" [(ngModel)]="email" autofocus />
        <input pInputText type="password" placeholder="Mot de passe"
               [(ngModel)]="password" (keyup.enter)="submit()" />
        <p-button label="Se connecter" icon="pi pi-sign-in" [loading]="loading"
                  (onClick)="submit()" styleClass="full-btn" />
        @if (error) {
          <div class="error"><i class="pi pi-exclamation-circle"></i> {{ error }}</div>
        }
      </div>
    </div>
  `,
  styles: [`
    .login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center;
      background: linear-gradient(160deg, #0f172a, #1e293b); }
    .login-card { background: #fff; border-radius: 16px; padding: 40px 36px; width: 380px;
      display: flex; flex-direction: column; gap: 14px; text-align: center; }
    .logo { width: 64px; height: 64px; margin: 0 auto; border-radius: 16px; background: #0ea5e9;
      color: #fff; display: flex; align-items: center; justify-content: center; }
    .logo i { font-size: 2rem; }
    h2 { margin: 4px 0 0; letter-spacing: 4px; }
    .sub { margin: 0 0 10px; color: #64748b; }
    input { font-size: 18px; width: 100%; }
    :host ::ng-deep .full-btn { width: 100%; }
    .error { color: #dc2626; font-weight: 600; display: flex; gap: 8px; justify-content: center; }
  `]
})
export class LoginComponent {
  email = ''; password = ''; error = ''; loading = false;
  constructor(private auth: AuthService, private router: Router) {}

  submit(): void {
    if (this.loading) return;
    this.loading = true;
    this.error = '';
    this.auth.login(this.email, this.password).subscribe({
      next: () => this.router.navigate(['/']),
      error: (err) => {
        // 429 = too many attempts: show the server's French message.
        this.error = err?.status === 429 && err?.error?.message
          ? err.error.message : 'Email ou mot de passe incorrect';
        this.loading = false;
      }
    });
  }
}