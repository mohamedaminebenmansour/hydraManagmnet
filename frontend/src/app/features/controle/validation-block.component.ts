import { Component, Input, OnInit, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { MessageService } from 'primeng/api';
import { ControleStore } from './controle.store';
import { ControleService, ValidBody, CtlValidStatut } from '@app/core/services/controle.service';
import { AuthService } from '@app/core/auth.service';
import { VSTATUS, VLABEL, money } from './controle-format';

/**
 * ValidationBlockComponent — port of validationBlock() from the prototype.
 *
 * Inputs: key, label, suggestedBalance.
 * 4 buttons (Pas encore vu, Validé avec lui, À revoir, Contesté).
 * « Solde accepté par les deux (TND) » (placeholder = suggested balance).
 * Note. « Enregistrer la validation » -> service.putValidation(key,
 * {statut, agreedBalance, note, version}); 409 -> banner + Recharger.
 * « Mis à jour le … par … ». Read-only without CTL_JUSTIFY.
 * « Pas encore vu » is not saved (button disabled when nothing was saved before).
 */
@Component({
  selector: 'ctl-validation-block',
  standalone: true,
  imports: [FormsModule, ButtonModule],
  template: `
    <div class="val-card">
      <h2>{{ label }}</h2>

      @if (!canWrite()) {
        <div class="banner info">Lecture seule : votre accès ne permet pas de modifier.</div>
      }
      @if (staleError()) {
        <div class="banner warn">
          Quelqu'un d'autre a modifié cette validation.
          <button class="ctl-btn" type="button" (click)="reload()">Recharger</button>
        </div>
      }

      <!-- Status buttons -->
      <div class="statuses" role="group" aria-label="Statut de la validation">
        @for (s of VSTATUS; track s[0]) {
          <button type="button" class="s-{{ s[0] }}"
                  [attr.aria-pressed]="statut() === s[0] ? 'true' : 'false'"
                  [disabled]="!canWrite() || isNoneDisabled(s[0])"
                  (click)="setStatus(s[0])">
            {{ s[1] }}
          </button>
        }
      </div>

      <!-- Form -->
      <div class="form">
        <label>
          Solde accepté par les deux (TND)
          <input type="text" inputmode="decimal"
                 [ngModel]="agreedBalance()"
                 (ngModelChange)="agreedBalance.set($event)"
                 [placeholder]="suggestedBalance != null ? money(suggestedBalance) : ''"
                 [disabled]="!canWrite()"
                 aria-label="Solde accepté" />
        </label>
        <label class="full">
          Note
          <textarea rows="2"
                    [ngModel]="note()"
                    (ngModelChange)="note.set($event)"
                    placeholder="Ce qui a été dit, ce qu'il doit apporter (factures, attachements)…"
                    [disabled]="!canWrite()"
                    aria-label="Note de la réunion"></textarea>
        </label>
      </div>

      <!-- Save + meta -->
      <div class="validate">
        <button class="ctl-btn primary" type="button"
                [disabled]="!canWrite() || saving()"
                (click)="save()">
          {{ saving() ? 'Enregistrement…' : 'Enregistrer la validation' }}
        </button>
        @if (metaText()) {
          <span class="muted small">{{ metaText() }}</span>
        }
      </div>
    </div>
  `,
  styles: [`
    .val-card {
      background: var(--ctl-panel-2, #eef2f1);
      border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); padding: 16px;
      display: flex; flex-direction: column; gap: 12px;
    }
    h2 { font-size: 15px; margin: 0; }
    .banner { border-radius: 8px; padding: 10px 14px; font-size: 13px;
      display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
    .banner.info { background: var(--ctl-accent-soft, #e1eff2); color: var(--ctl-accent, #0d5c6e); }
    .banner.warn { background: var(--ctl-warn-soft, #fbf0dc); color: var(--ctl-warn, #9a5b00); }
    .statuses { display: flex; flex-wrap: wrap; gap: 6px; }
    .statuses button { font: inherit; font-size: 12.5px; border: 1px solid var(--ctl-line, #dbe1df);
      background: var(--ctl-panel, #fff); color: var(--ctl-ink, #16212b);
      border-radius: 999px; padding: 5px 11px; cursor: pointer; }
    .statuses button[aria-pressed="true"] { border-color: currentColor; font-weight: 700; }
    .statuses button:disabled { opacity: .5; cursor: default; }
    .s-none { background: var(--ctl-panel-2, #eef2f1); color: var(--ctl-muted, #58656f); }
    .s-valide { background: var(--ctl-ok-soft, #e3f3ea); color: var(--ctl-ok, #1d7348); }
    .s-a-revoir { background: var(--ctl-warn-soft, #fbf0dc); color: var(--ctl-warn, #9a5b00); }
    .s-conteste { background: var(--ctl-vio-soft, #eeeafb); color: var(--ctl-vio, #5b3fb5); }
    .form { display: grid; gap: 10px; grid-template-columns: 1fr 1fr; }
    .form label { display: flex; flex-direction: column; gap: 4px; font-size: 12px;
      color: var(--ctl-muted, #58656f); font-weight: 600; }
    .form .full { grid-column: 1 / -1; }
    .form input, .form textarea {
      font: inherit; font-size: 13px; color: var(--ctl-ink, #16212b);
      background: var(--ctl-panel, #fff); border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: 8px; padding: 7px 10px; min-width: 0;
    }
    .form textarea { resize: vertical; }
    .ctl-btn { font: inherit; font-size: 13px; font-weight: 600; border-radius: 8px;
      padding: 7px 14px; border: 1px solid var(--ctl-line, #dbe1df);
      background: var(--ctl-panel, #fff); color: var(--ctl-ink, #16212b); cursor: pointer; }
    .ctl-btn.primary { background: var(--ctl-accent, #0d5c6e); color: #fff; border-color: var(--ctl-accent, #0d5c6e); }
    .ctl-btn:disabled { opacity: .5; cursor: default; }
    .validate { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
    .muted { color: var(--ctl-muted, #58656f); }
    .small { font-size: 12px; }
    @media (max-width: 400px) { .form { grid-template-columns: 1fr; } }
  `]
})
export class ValidationBlockComponent implements OnInit {
  private store = inject(ControleStore);
  private api = inject(ControleService);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  protected VSTATUS = VSTATUS;
  protected VLABEL = VLABEL;
  protected money = money;

  @Input({ required: true }) key!: string;
  @Input({ required: true }) label!: string;
  @Input() suggestedBalance: number | null = null;

  protected statut = signal<CtlValidStatut>('none');
  protected agreedBalance = signal('');
  protected note = signal('');
  protected saving = signal(false);
  protected staleError = signal(false);

  private currentValid = computed(() => this.store.valid().get(this.key));

  canWrite = computed(() => this.auth.can('CTL_JUSTIFY'));

  metaText = computed(() => {
    const v = this.currentValid();
    if (!v?.updatedAt) return null;
    const date = new Date(v.updatedAt).toLocaleString('fr-FR');
    const name = v.updatedByName ?? '';
    return name ? `Mis à jour le ${date} par ${name}` : `Mis à jour le ${date}`;
  });

  ngOnInit(): void {
    // Load current values when the store data changes
    effect(() => {
      const v = this.currentValid();
      this.statut.set((v?.statut ?? 'none') as CtlValidStatut);
      this.agreedBalance.set(v?.agreedBalance != null ? String(v.agreedBalance).replace('.', ',') : '');
      this.note.set(v?.note ?? '');
      this.staleError.set(false);
    });
  }

  /** "Pas encore vu" is disabled when nothing was saved before. */
  isNoneDisabled(code: string): boolean {
    return code === 'none' && this.currentValid() == null;
  }

  setStatus(code: string): void {
    this.statut.set(code as CtlValidStatut);
  }

  reload(): void {
    this.staleError.set(false);
    this.store.reload();
  }

  save(): void {
    if (!this.canWrite()) return;
    this.saving.set(true);
    this.staleError.set(false);

    const v = this.currentValid();
    const version = v?.version ?? 0;
    const n = parseFloat(this.agreedBalance().replace(/\s/g, '').replace(',', '.'));

    const body: ValidBody = {
      statut: this.statut(),
      agreedBalance: isNaN(n) ? null : n,
      note: this.note().trim() || null,
      version,
    };

    this.api.putValidation(this.key, body).subscribe({
      next: (result) => {
        this.store.setValid(this.key, result);
        this.msgs.add({ severity: 'success', summary: 'Validation enregistrée.' });
        this.saving.set(false);
      },
      error: (err) => {
        this.saving.set(false);
        if (err?.status === 409) {
          this.staleError.set(true);
        } else {
          this.msgs.add({ severity: 'error', summary: 'Erreur',
            detail: err?.error?.message ?? 'Enregistrement impossible.' });
        }
      },
    });
  }
}
