import { Component, Input, Output, EventEmitter, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { AccountService } from '@app/core/services/account.service';

/**
 * Reusable password field (U3):
 * - input type password with an eye toggle (aria-label « Afficher le mot de passe »)
 * - autocomplete="new-password", paste allowed
 * - live checklist from GET /api/auth/password-policy (green tick / grey dot)
 * - hint: « Astuce : une phrase de 3 ou 4 mots est facile à retenir et difficile à deviner. »
 * - server messages (WEAK_PASSWORD) shown under the field in red
 *
 * Usage: <app-password-field [(value)]="pw" [showConfirm]="true" />
 */
@Component({
  selector: 'app-password-field',
  standalone: true,
  imports: [FormsModule, ButtonModule],
  template: `
    <div class="pw-field">
      <div class="input-row">
        <input [type]="visible() ? 'text' : 'password'"
               [placeholder]="placeholder()"
               autocomplete="new-password"
               [ngModel]="value()" (ngModelChange)="onInput($event)" />
        <button type="button" class="eye-btn" (click)="visible.set(!visible())"
                [attr.aria-label]="visible() ? 'Masquer le mot de passe' : 'Afficher le mot de passe'">
          <i class="pi" [class.pi-eye]="!visible()" [class.pi-eye-slash]="visible()"></i>
        </button>
      </div>

      @if (showConfirm) {
        <div class="input-row">
          <input [type]="visible() ? 'text' : 'password'"
                 placeholder="Confirmer le mot de passe"
                 autocomplete="new-password"
                 [ngModel]="confirm()" (ngModelChange)="confirm.set($event)" />
        </div>
      }

      <div class="hint">Astuce : une phrase de 3 ou 4 mots est facile à retenir et difficile à deviner.</div>

      <ul class="rules">
        <li [class.ok]="longEnough()">
          <i class="pi" [class.pi-check-circle]="longEnough()" [class.pi-circle]="!longEnough()"></i>
          {{ minLength() }} caractères minimum
        </li>
        <li class="deferred">
          <i class="pi pi-circle"></i>
          Pas un mot de passe courant <span class="deferred-note">(vérifié à l'enregistrement)</span>
        </li>
        @if (showConfirm) {
          <li [class.ok]="confirmMatch()">
            <i class="pi" [class.pi-check-circle]="confirmMatch()" [class.pi-circle]="!confirmMatch()"></i>
            Les deux saisies sont identiques
          </li>
        }
      </ul>

      @if (serverError) {
        <div class="server-err"><i class="pi pi-exclamation-circle"></i> {{ serverError }}</div>
      }
    </div>
  `,
  styles: [`
    .pw-field { display: flex; flex-direction: column; gap: 8px; }
    .input-row { position: relative; display: flex; align-items: center; }
    .input-row input {
      width: 100%; font-size: 16px; padding: 10px 40px 10px 12px;
      border: 1px solid #cbd5e1; border-radius: 8px; box-sizing: border-box;
    }
    .input-row input:focus { outline: none; border-color: #0ea5e9; }
    .eye-btn {
      position: absolute; right: 6px; background: none; border: none; cursor: pointer;
      color: #64748b; padding: 4px; display: flex; align-items: center;
    }
    .eye-btn:hover { color: #0f172a; }
    .hint { font-size: 12px; color: #64748b; }
    .rules { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 4px; }
    .rules li { font-size: 13px; color: #94a3b8; display: flex; align-items: center; gap: 6px; }
    .rules li.ok { color: #16a34a; }
    .rules li.deferred .deferred-note { color: #94a3b8; font-style: italic; }
    .pi-circle { font-size: 10px; }
    .server-err { color: #dc2626; font-size: 13px; font-weight: 600; display: flex; gap: 6px; align-items: center; }
  `]
})
export class PasswordFieldComponent implements OnInit {
  private account = inject(AccountService);

  @Input() showConfirm = false;
  @Input() serverError: string | null = null;
  @Output() valueChange = new EventEmitter<string>();

  value = signal('');
  confirm = signal('');
  visible = signal(false);
  minLength = signal(12);
  maxLength = signal(128);

  longEnough = computed(() => this.value().length >= this.minLength());
  confirmMatch = computed(() => this.showConfirm && this.value().length > 0 && this.value() === this.confirm());

  ngOnInit(): void {
    this.account.passwordPolicy().subscribe({
      next: p => { this.minLength.set(p.minLength); this.maxLength.set(p.maxLength); },
      error: () => {}
    });
  }

  onInput(v: string): void {
    this.value.set(v);
    this.valueChange.emit(v);
  }

  placeholder(): string {
    return 'Mot de passe';
  }
}
