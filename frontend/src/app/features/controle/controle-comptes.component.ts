import { Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { ControleStore } from './controle.store';
import { CtlAccount, CtlLine } from '@app/core/services/controle.service';
import { ACCT_GROUP, money, pct } from './controle-format';

interface AcctGroup { kind: string; title: string; accounts: { acct: CtlAccount; rows: CtlLine[] }[]; }

/**
 * ControleComptesComponent — port of pageAccounts() from the prototype.
 * Shows the « Caisse Central » virtual card first, then one section per
 * kind with a card per account. Only lines inScope are counted.
 */
@Component({
  selector: 'app-controle-comptes',
  standalone: true,
  template: `
    <div>
      <h1>Les comptes</h1>
      <p class="lead">Les trois comptes UIB, la caisse SIGAT, les caisses chantier et les caisses NGNET de chaque projet. Les chiffres comptent seulement les lignes de {{ store.scopeName() }}. « Caisse Central » n'est pas un compte : c'est la somme des comptes UIB et de la caisse SIGAT.</p>
    </div>

    <!-- Caisse Central (virtual view) -->
    <div class="card acct-card" tabindex="0" (click)="goCaisseCentral()" (keydown)="onCcKey($event)">
      <div class="card-head">
        <h2>Caisse Central (vue réunie)</h2>
        <span class="badge">Vue</span>
      </div>
      <p class="muted small">La feuille « Caisse Central » de l'Excel : UIB SIGAT + UIB OMAR + UIB NGNET + caisse SIGAT, avec toutes ses lignes.</p>
      <div class="row"><span>Entrées</span><b class="num pos">{{ money(ccTotals().e) }}</b></div>
      <div class="row"><span>Sorties</span><b class="num neg">{{ money(ccTotals().d) }}</b></div>
      <div class="row"><span>Lignes</span><b class="num">{{ ccTotals().n.toLocaleString('fr-FR') }}</b></div>
    </div>

    <!-- Sections by kind -->
    @for (grp of groups(); track grp.kind) {
      <section class="grp-section">
        <h3>{{ grp.title }}</h3>
        <div class="grid">
          @for (item of grp.accounts; track item.acct.id) {
            <div class="card acct-card" tabindex="0"
                 (click)="goAccount(item.acct.id)"
                 (keydown)="onAcctKey($event, item.acct.id)">
              <div class="card-head">
                <h2>{{ item.acct.name }}</h2>
                <span class="badge">{{ item.acct.owner }}</span>
              </div>
              @if (item.acct.note) { <p class="muted small">{{ item.acct.note }}</p> }
              <div class="row"><span>Entrées</span><b class="num pos">{{ money(acctTotals(item).e) }}</b></div>
              <div class="row"><span>Sorties</span><b class="num neg">{{ money(acctTotals(item).d) }}</b></div>
              <div class="row"><span>Solde (selon l'Excel)</span><b class="num" [class.neg]="acctSolde(item) < 0">{{ money(acctSolde(item)) }}</b></div>
              <div class="row"><span>Sorties justifiées</span><b class="num">{{ acctTotals(item).d ? pct(acctTotals(item).dj / acctTotals(item).d) : '—' }}</b></div>
              <div class="row"><span>Lignes</span><b class="num">{{ item.rows.length.toLocaleString('fr-FR') }}</b></div>
            </div>
          }
        </div>
      </section>
    }
  `,
  styles: [`
    h1 { font-size: 22px; margin: 0 0 4px; color: var(--ctl-ink, #16212b); letter-spacing: -.015em; }
    .lead { color: var(--ctl-muted, #58656f); max-width: 80ch; font-size: 14px; margin: 0; }
    .muted { color: var(--ctl-muted, #58656f); }
    .small { font-size: 12px; }
    .num { font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums; }
    .pos { color: var(--ctl-ok, #1d7348); }
    .neg { color: var(--ctl-bad, #b42318); }

    .card { background: var(--ctl-panel, #fff); border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); padding: 16px; display: flex; flex-direction: column;
      gap: 8px; min-width: 0; }
    .acct-card { cursor: pointer; }
    .acct-card:hover { border-color: var(--ctl-accent, #0d5c6e); }
    h2 { font-size: 15px; margin: 0; }
    h3 { font-size: 13px; margin: 0 0 8px; text-transform: uppercase; letter-spacing: .06em;
      color: var(--ctl-muted, #58656f); font-weight: 600; }
    .card-head { display: flex; justify-content: space-between; gap: 8px; align-items: baseline; }
    .badge { font-size: 11px; font-weight: 600; text-transform: uppercase; letter-spacing: .05em;
      color: var(--ctl-accent, #0d5c6e); }
    .row { display: flex; justify-content: space-between; gap: 8px; font-size: 13px; }
    .row span { color: var(--ctl-muted, #58656f); }

    .grp-section { display: flex; flex-direction: column; gap: 10px; }
    .grid { display: grid; gap: 12px; grid-template-columns: repeat(auto-fill, minmax(min(100%, 280px), 1fr)); }

    @media (max-width: 400px) {
      h1 { font-size: 19px; }
      .grid { grid-template-columns: 1fr; }
    }
  `]
})
export class ControleComptesComponent {
  protected store = inject(ControleStore);
  private router = inject(Router);

  protected money = money;
  protected pct = pct;

  /** Caisse Central lines (sheet === 'Caisse Central' + in scope). */
  private ccLines = computed(() => {
    const lines = this.store.data()?.lines ?? [];
    return lines.filter(l => l.sheet === 'Caisse Central' && this.store.inScope(l.proj));
  });

  ccTotals = computed(() => {
    const rows = this.ccLines();
    let e = 0, d = 0;
    for (const l of rows) { e += l.e; d += l.d; }
    return { e, d, n: rows.length };
  });

  /** Accounts grouped by kind, with their in-scope lines. */
  groups = computed<AcctGroup[]>(() => {
    const accounts = this.store.data()?.accounts ?? [];
    const lines = this.store.data()?.lines ?? [];
    const m = new Map<string, { acct: CtlAccount; rows: CtlLine[] }[]>();
    for (const a of accounts) {
      const rows = lines.filter(l => l.acct === a.id && this.store.inScope(l.proj));
      if (rows.length === 0) continue;
      const list = m.get(a.kind) || [];
      list.push({ acct: a, rows });
      m.set(a.kind, list);
    }
    // Order: banque, especes, chantier, partenaire (ACCT_GROUP order)
    const orderedKinds = Object.keys(ACCT_GROUP);
    return orderedKinds
      .filter(k => m.has(k))
      .map(k => ({ kind: k, title: ACCT_GROUP[k] || k, accounts: m.get(k)! }));
  });

  acctTotals(item: { acct: CtlAccount; rows: CtlLine[] }) {
    return this.store.totals(item.rows);
  }

  acctSolde(item: { acct: CtlAccount; rows: CtlLine[] }): number {
    return item.rows.reduce((s, l) => s + l.e - l.d, 0);
  }

  goCaisseCentral(): void {
    this.router.navigate(['/controle/comptes', 'caisse-central']);
  }

  goAccount(id: string): void {
    this.router.navigate(['/controle/comptes', id]);
  }

  onCcKey(ev: KeyboardEvent): void {
    if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); this.goCaisseCentral(); }
  }

  onAcctKey(ev: KeyboardEvent, id: string): void {
    if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); this.goAccount(id); }
  }
}
