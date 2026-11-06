import { Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { ControleStore } from './controle.store';
import { CtlLine } from '@app/core/services/controle.service';
import { money, pct, slug } from './controle-format';

interface ProjCard {
  name: string;
  rows: CtlLine[];
  totals: { e: number; d: number; dj: number; n: number };
  payers: string[];
}

/**
 * ControleProjetsComponent — port of pageProjects() from the prototype.
 * One card per project of store.projList() (groupement projects first:
 * Hajeb, Hammed, Ouled Khalfallah), with Sorties, Entrées, Justifié %,
 * « Payé depuis : <account names> ». Click -> /controle/projets/<slug>.
 */
@Component({
  selector: 'app-controle-projets',
  standalone: true,
  template: `
    <div>
      <h1>Les projets</h1>
      <p class="lead">Pour chaque projet : tout ce qui est entré et sorti, quel que soit le compte qui a payé.</p>
    </div>
    <div class="grid">
      @for (p of projects(); track p.name) {
        <div class="card acct-card" tabindex="0"
             (click)="goProj(p.name)" (keydown)="onKey($event, p.name)">
          <h2>{{ p.name }}</h2>
          <div class="row"><span>Sorties</span><b class="num neg">{{ money(p.totals.d) }}</b></div>
          <div class="row"><span>Entrées</span><b class="num pos">{{ money(p.totals.e) }}</b></div>
          <div class="row"><span>Justifié</span><b class="num">{{ p.totals.d ? pct(p.totals.dj / p.totals.d) : '—' }}</b></div>
          @if (p.payers.length) {
            <p class="muted small">Payé depuis : {{ p.payers.join(', ') }}</p>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    h1 { font-size: 22px; margin: 0 0 4px; color: var(--ctl-ink, #16212b); letter-spacing: -.015em; }
    .lead { color: var(--ctl-muted, #58656f); max-width: 80ch; font-size: 14px; margin: 0; }
    .muted { color: var(--ctl-muted, #58656f); }
    .small { font-size: 12px; }
    .num { font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums; }
    .pos { color: var(--ctl-ok, #1d7348); }
    .neg { color: var(--ctl-bad, #b42318); }
    .grid { display: grid; gap: 12px; grid-template-columns: repeat(auto-fill, minmax(min(100%, 280px), 1fr)); }
    .card { background: var(--ctl-panel, #fff); border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); padding: 16px; display: flex; flex-direction: column;
      gap: 8px; min-width: 0; }
    .acct-card { cursor: pointer; }
    .acct-card:hover { border-color: var(--ctl-accent, #0d5c6e); }
    h2 { font-size: 15px; margin: 0; }
    .row { display: flex; justify-content: space-between; gap: 8px; font-size: 13px; }
    .row span { color: var(--ctl-muted, #58656f); }
    @media (max-width: 400px) { .grid { grid-template-columns: 1fr; } h1 { font-size: 19px; } }
  `]
})
export class ControleProjetsComponent {
  protected store = inject(ControleStore);
  private router = inject(Router);

  protected money = money;
  protected pct = pct;

  projects = computed<ProjCard[]>(() => {
    const projList = this.store.projList();
    const lines = this.store.data()?.lines ?? [];
    return projList.map(name => {
      const rows = lines.filter(l => l.proj === name);
      const totals = this.store.totals(rows);
      const payerSet = new Set<string>();
      for (const l of rows) {
        if (l.d) {
          const acct = this.store.acctById().get(l.acct)?.name;
          if (acct) payerSet.add(acct);
        }
      }
      return { name, rows, totals, payers: [...payerSet] };
    });
  });

  goProj(name: string): void {
    this.router.navigate(['/controle/projets', slug(name)]);
  }

  onKey(ev: KeyboardEvent, name: string): void {
    if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); this.goProj(name); }
  }
}
