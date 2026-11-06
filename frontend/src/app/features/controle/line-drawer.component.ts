import { Component, HostListener, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { DrawerModule } from 'primeng/drawer';
import { MessageService } from 'primeng/api';
import { ControleStore } from './controle.store';
import { ControleDrawerService } from './controle-drawer.service';
import { ControleService, JustifBody } from '@app/core/services/controle.service';
import { AuthService } from '@app/core/auth.service';
import {
  STATUS, DOCS, FLAGS, money, dfmt, slug,
} from './controle-format';

/**
 * LineDrawerComponent — placed ONCE in ControleLayoutComponent. Port of
 * openLine() from the prototype: a right-side drawer showing a money line
 * or a subcontractor line, with its justification form.
 */
@Component({
  selector: 'ctl-line-drawer',
  standalone: true,
  imports: [FormsModule, ButtonModule, DrawerModule],
  template: `
    <!-- Only in the page while open: a closed drawer must not leave a mask that catches clicks -->
    @if (visible()) {
    <p-drawer [visible]="visible()" (visibleChange)="onVisibleChange($event)"
              position="right" [style]="{ width: 'min(560px, 100vw)' }">
      @if (line()) {
        <!-- Header -->
        <div class="dr-head">
          <div>
            <h2>{{ title() }}</h2>
            <p class="muted small">{{ money(amount()) }} TND</p>
          </div>
          <button class="ctl-btn" type="button" (click)="close()" aria-label="Fermer">Fermer</button>
        </div>

        <!-- Body -->
        <div class="dr-body">
          <!-- Details list -->
          <dl class="kv" (click)="onContentClick($event)">
            @for (item of details(); track item.label) {
              <dt>{{ item.label }}</dt>
              <dd [innerHTML]="item.html"></dd>
            }
          </dl>

          <!-- Flag banners -->
          @for (f of currentFlags(); track f) {
            <div class="banner">
              {{ FLAGS[f]?.[0] || f }} : {{ FLAGS[f]?.[1] || '' }}
            </div>
          }

          <!-- Justificatif section -->
          <h3>Justificatif</h3>

          @if (!canWrite()) {
            <div class="banner info">Lecture seule : votre accès ne permet pas de modifier.</div>
          }
          @if (staleError()) {
            <div class="banner warn">
              Quelqu'un d'autre a modifié ce justificatif.
              <button class="ctl-btn" type="button" (click)="reload()">Recharger</button>
            </div>
          }

          <!-- Status buttons -->
          <div class="statuses" role="group" aria-label="Statut du justificatif">
            @for (s of STATUS; track s[0]) {
              <button type="button" class="s-{{ s[0] }}"
                      [attr.aria-pressed]="statut() === s[0] ? 'true' : 'false'"
                      [disabled]="!canWrite()"
                      (click)="setStatus(s[0])">
                {{ s[1] }}
              </button>
            }
          </div>

          <!-- Form fields -->
          <div class="form">
            <label>
              Type de document
              <select [ngModel]="docType()" (ngModelChange)="docType.set($event)" [disabled]="!canWrite()">
                <option value="">— Choisir —</option>
                @for (d of DOCS; track d) { <option [value]="d">{{ d }}</option> }
              </select>
            </label>
            <label>
              Référence du document
              <input type="text" [ngModel]="reference()" (ngModelChange)="reference.set($event)"
                     placeholder="ex. Facture n° 2026/145" [disabled]="!canWrite()" />
            </label>
            <label>
              Date du document
              <input type="date" [ngModel]="docDate()" (ngModelChange)="docDate.set($event)" [disabled]="!canWrite()" />
            </label>
            <label>
              Montant du document (TND)
              <input type="text" inputmode="decimal"
                     [ngModel]="docAmount()" (ngModelChange)="onAmountChange($event)"
                     [placeholder]="money(amount())" [disabled]="!canWrite()" />
            </label>
            <div class="full">
              <p class="muted small">{{ diffText() }}</p>
            </div>
            <label class="full">
              Note
              <textarea rows="3" [ngModel]="note()" (ngModelChange)="note.set($event)"
                        placeholder="Ce que le sous-traitant a dit, ce qui manque…"
                        [disabled]="!canWrite()"></textarea>
            </label>
          </div>

          <!-- Field errors -->
          @if (fieldErrorEntries().length > 0) {
            <div class="field-errors">
              @for (e of fieldErrorEntries(); track e[0]) {
                <small class="field-error">{{ e[0] }} : {{ e[1] }}</small>
              }
            </div>
          }

          <!-- Save + meta -->
          <div class="validate">
            <button class="ctl-btn primary" type="button"
                    [disabled]="!canWrite() || saving()"
                    (click)="save()">
              {{ saving() ? 'Enregistrement…' : 'Enregistrer' }}
            </button>
            @if (updatedAtDisplay()) {
              <span class="muted small">Dernière mise à jour : {{ updatedAtDisplay() }}</span>
            }
          </div>
        </div>
      }
    </p-drawer>
    }
  `,
  styles: [`
    .dr-head { display: flex; justify-content: space-between; gap: 8px; align-items: flex-start;
      padding: 16px; border-bottom: 1px solid var(--ctl-line, #dbe1df); }
    .dr-body { overflow-y: auto; padding: 16px; display: flex; flex-direction: column; gap: 14px; }
    h2 { font-size: 15px; margin: 0; }
    h3 { font-size: 13px; margin: 0; text-transform: uppercase; letter-spacing: .06em;
      color: var(--ctl-muted, #58656f); font-weight: 600; }
    .muted { color: var(--ctl-muted, #58656f); }
    .small { font-size: 12px; }
    dl.kv { display: grid; grid-template-columns: max-content 1fr; gap: 6px 14px; margin: 0; font-size: 13px; }
    dl.kv dt { color: var(--ctl-muted, #58656f); }
    dl.kv dd { margin: 0; min-width: 0; overflow-wrap: anywhere; }
    .banner { border-radius: var(--ctl-radius, 10px); padding: 10px 14px; font-size: 13px;
      background: var(--ctl-warn-soft, #fbf0dc); color: var(--ctl-warn, #9a5b00);
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
    .s-justifie { background: var(--ctl-ok-soft, #e3f3ea); color: var(--ctl-ok, #1d7348); }
    .s-partiel { background: var(--ctl-warn-soft, #fbf0dc); color: var(--ctl-warn, #9a5b00); }
    .s-non-justifie { background: var(--ctl-bad-soft, #fbe7e5); color: var(--ctl-bad, #b42318); }
    .s-conteste { background: var(--ctl-vio-soft, #eeeafb); color: var(--ctl-vio, #5b3fb5); }
    .form { display: grid; gap: 10px; grid-template-columns: 1fr 1fr; }
    .form label { display: flex; flex-direction: column; gap: 4px; font-size: 12px;
      color: var(--ctl-muted, #58656f); font-weight: 600; }
    .form .full { grid-column: 1 / -1; }
    .form input, .form select, .form textarea {
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
    .field-errors { display: flex; flex-direction: column; gap: 4px; }
    .field-error { color: var(--ctl-bad, #b42318); font-size: 12px; }
    .num { font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums; }
    .link-btn { border: none; background: none; color: var(--ctl-accent, #0d5c6e);
      cursor: pointer; font: inherit; font-size: 13px; padding: 0; text-decoration: underline; }
    .chip.red { background: var(--ctl-bad-soft, #fbe7e5); color: var(--ctl-bad, #b42318); }
    @media (max-width: 400px) { .form { grid-template-columns: 1fr; } }
  `]
})
export class LineDrawerComponent {
  protected store = inject(ControleStore);
  private drawer = inject(ControleDrawerService);
  private api = inject(ControleService);
  protected auth = inject(AuthService);
  private msgs = inject(MessageService);
  private router = inject(Router);

  // Exposed to template
  protected STATUS = STATUS;
  protected DOCS = DOCS;
  protected FLAGS = FLAGS;
  protected money = money;

  protected visible = signal(false);
  protected saving = signal(false);
  protected staleError = signal(false);
  protected fieldErrors = signal<Record<string, string>>({});

  // Form signals
  protected statut = signal<JustifStatut>('none');
  protected docType = signal('');
  protected reference = signal('');
  protected docDate = signal('');
  protected docAmount = signal('');
  protected note = signal('');

  // The current line (money line or st line)
  protected line = signal<{ kind: 'line'; data: import('@app/core/services/controle.service').CtlLine }
    | { kind: 'st'; data: import('@app/core/services/controle.service').CtlStLine } | null>(null);

  private lineKey = signal<string | null>(null);

  // ── Computed ────────────────────────────────────────────────────────────
  protected title = computed(() => {
    const l = this.line();
    if (!l) return '';
    if (l.kind === 'line') return l.data.desig || 'Ligne sans désignation';
    return l.data.desig || 'Ligne du compte sous-traitant';
  });

  protected amount = computed(() => {
    const l = this.line();
    if (!l) return 0;
    if (l.kind === 'line') return l.data.d || l.data.e;
    return l.data.paye || l.data.travaux;
  });

  protected currentFlags = computed(() => {
    const l = this.line();
    if (!l) return [];
    return l.data.flags;
  });

  protected canWrite = computed(() => this.auth.can('CTL_JUSTIFY'));

  protected updatedAtDisplay = computed(() => {
    const key = this.lineKey();
    if (!key) return null;
    const j = this.store.jOf(key);
    if (!j?.updatedAt) return null;
    return new Date(j.updatedAt).toLocaleString('fr-FR');
  });

  protected diffText = computed(() => {
    const v = parseFloat(this.docAmount().replace(/\s/g, '').replace(',', '.'));
    if (isNaN(v)) return '';
    const amt = this.amount();
    if (Math.abs(v - amt) < 0.0005) return 'Le document couvre exactement la ligne.';
    return `Écart avec la ligne : ${money(v - amt)} TND`;
  });

  // Details list (dl) — computed from the line type
  protected details = computed<{ label: string; html: string }[]>(() => {
    const l = this.line();
    if (!l) return [];
    const items: { label: string; html: string }[] = [];
    if (l.kind === 'line') {
      const line = l.data;
      items.push({ label: 'Date', html: dfmt(line.date) });
      items.push({ label: 'Compte', html: this.escape(this.store.acctById().get(line.acct)?.name ?? 'Sans compte') });
      items.push({ label: 'Projet', html: this.escape(line.proj) });
      items.push({ label: 'Sens', html: this.escape(
        this.store.isTransfer(line) ? 'Transfert entre comptes'
        : (line.e && line.d ? 'Entrée et sortie (payé par le partenaire)'
           : (line.e ? 'Entrée' : 'Sortie'))) });
      items.push({ label: 'Montant', html: `<span class="num">${money(this.amount())} TND</span>` });
      if (line.mode) items.push({ label: 'Paiement', html: this.escape(line.mode) });
      if (line.poste) items.push({ label: 'Catégorie', html: this.escape(line.poste) });
      if (line.cat) items.push({ label: "Catégorie dans l'Excel", html: this.escape(line.cat) });
      if (line.party) items.push({ label: 'Sous-traitant', html: this.escape(line.party) });
      if (line.bon) items.push({ label: 'N° de bon', html: this.escape(line.bon) });
      if (line.fct) items.push({ label: 'Facture (Excel)', html: this.escape(line.fct) });
      if (line.obs) items.push({ label: 'Observation', html: this.escape(line.obs) });
      items.push({ label: "Dans l'Excel", html: this.escape(`Feuille « ${line.sheet} », ligne ${line.row}`) });
      // Linked subcontractor account
      if (line.stLink) {
        const stLine = this.store.stById().get(line.stLink);
        const stAcct = stLine ? this.store.stAcctById().get(stLine.st) : null;
        if (stAcct) {
          const href = `/controle/sous-traitants/${slug(stAcct.person)}`;
          items.push({ label: 'Compte sous-traitant',
            html: `<a href="${href}">${this.escape(`${stAcct.person} · ${stAcct.proj} (feuille « ${stAcct.sheet} », ligne ${stLine?.row})`)}</a>` });
        }
      }
    } else {
      const s = l.data;
      const a = this.store.stAcctById().get(s.st);
      items.push({ label: 'Date', html: dfmt(s.date) });
      if (a) items.push({ label: 'Sous-traitant', html: this.escape(`${a.person} · ${a.proj}`) });
      items.push({ label: 'Nature', html: this.escape(
        s.travaux ? 'Travaux (crédit du sous-traitant)' : 'Paiement au sous-traitant') });
      items.push({ label: 'Montant', html: `<span class="num">${money(this.amount())} TND</span>` });
      if (s.mode) items.push({ label: 'Paiement', html: this.escape(s.mode) });
      items.push({ label: "Dans l'Excel", html: this.escape(`Feuille « ${s.sheet} », ligne ${s.row}`) });
      // Payé depuis
      if (s.paye) {
        if (s.link) {
          const m = this.store.lineById().get(s.link);
          if (m) {
            const acctName = this.store.acctById().get(m.acct)?.name ?? 'Sans compte';
            items.push({ label: 'Payé depuis',
              html: `<button class="link-btn" onclick="void(0)" data-link="${m.id}">${this.escape(`${acctName} · ${m.sheet} ligne ${m.row}`)}</button>` });
          } else {
            items.push({ label: 'Payé depuis', html: '<span class="chip red">Non retrouvé dans les caisses</span>' });
          }
        } else {
          items.push({ label: 'Payé depuis', html: '<span class="chip red">Non retrouvé dans les caisses</span>' });
        }
      }
    }
    return items;
  });

  // Field error entries for template iteration
  protected fieldErrorEntries = computed(() => Object.entries(this.fieldErrors()));

  // ── Init ──────────────────────────────────────────────────────────────────
  constructor() {
    // React to drawer.openKey changes
    effect(() => {
      const key = this.drawer.openKey();
      if (key) {
        this.openForKey(key);
      } else {
        this.visible.set(false);
        this.line.set(null);
      }
    }, { allowSignalWrites: true });
  }

  @HostListener('document:keydown.escape')
  onEscape(): void {
    if (this.visible()) this.close();
  }

  private openForKey(key: string): void {
    const moneyLine = this.store.lineById().get(key);
    if (moneyLine) {
      this.line.set({ kind: 'line', data: moneyLine });
    } else {
      const stLine = this.store.stById().get(key);
      if (stLine) {
        this.line.set({ kind: 'st', data: stLine });
      } else {
        this.line.set(null);
        this.visible.set(false);
        return;
      }
    }
    this.lineKey.set(key);
    this.staleError.set(false);
    this.fieldErrors.set({});
    // Load justification values
    const j = this.store.jOf(key);
    this.statut.set((j?.statut ?? 'none') as JustifStatut);
    this.docType.set(j?.docType ?? '');
    this.reference.set(j?.reference ?? '');
    this.docDate.set(j?.docDate ?? '');
    this.docAmount.set(j?.docAmount != null ? String(j.docAmount).replace('.', ',') : '');
    this.note.set(j?.note ?? '');
    this.visible.set(true);
  }

  // ── Actions ───────────────────────────────────────────────────────────────
  onVisibleChange(v: boolean): void {
    if (!v) this.close();
  }

  close(): void {
    this.drawer.close();
  }

  reload(): void {
    this.staleError.set(false);
    this.store.reload();
    // After reload, the store data changes, re-open the drawer with the same key
    const key = this.lineKey();
    if (key) this.openForKey(key);
  }

  onAmountChange(value: string): void {
    this.docAmount.set(value);
  }

  setStatus(value: string): void {
    this.statut.set(value as JustifStatut);
  }

  // Handle clicks on dynamic content (the "Payé depuis" button)
  onContentClick(ev: MouseEvent): void {
    const target = ev.target as HTMLElement;
    const link = target?.dataset?.['link'];
    if (link) {
      ev.preventDefault();
      this.openForKey(link);
    }
  }

  save(): void {
    const key = this.lineKey();
    if (!key || !this.canWrite()) return;
    this.saving.set(true);
    this.staleError.set(false);
    this.fieldErrors.set({});

    const j = this.store.jOf(key);
    const version = j?.version ?? 0;
    const v = parseFloat(this.docAmount().replace(/\s/g, '').replace(',', '.'));

    // « Non vérifié » = always delete the justification row.
    const isEmpty = this.statut() === 'none';

    // If statut 'none' and no existing justification, just close the drawer.
    if (isEmpty && j == null) {
      this.saving.set(false);
      this.close();
      return;
    }

    if (isEmpty) {
      // Delete: send statut none with empty body, the backend deletes
      this.api.putJustification(key, {
        statut: 'none', docType: null, reference: null, docDate: null,
        docAmount: null, note: null, version,
      }).subscribe({
        next: () => {
          this.store.setJustif(key, null);
          this.msgs.add({ severity: 'success', summary: 'Justificatif enregistré.' });
          this.saving.set(false);
          this.close();
        },
        error: (err) => this.onError(err),
      });
    } else {
      const body: JustifBody = {
        statut: this.statut(),
        docType: this.docType() || null,
        reference: this.reference().trim() || null,
        docDate: this.docDate() || null,
        docAmount: isNaN(v) ? null : v,
        note: this.note().trim() || null,
        version,
      };
      this.api.putJustification(key, body).subscribe({
        next: (result) => {
          this.store.setJustif(key, result);
          this.msgs.add({ severity: 'success', summary: 'Justificatif enregistré.' });
          this.saving.set(false);
          this.close();
        },
        error: (err) => this.onError(err),
      });
    }
  }

  private onError(err: any): void {
    this.saving.set(false);
    if (err?.status === 400 && err?.error?.code === 'VALIDATION') {
      this.fieldErrors.set(err.error.fields ?? {});
    } else if (err?.status === 409) {
      this.staleError.set(true);
    } else {
      this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err?.error?.message ?? 'Enregistrement impossible.' });
    }
  }

  private escape(s: string): string {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }
}

type JustifStatut = 'none' | 'justifie' | 'partiel' | 'non-justifie' | 'conteste';
