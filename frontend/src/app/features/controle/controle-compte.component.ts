import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ControleStore } from './controle.store';
import { LinesTableComponent } from './lines-table.component';
import { CtlLine } from '@app/core/services/controle.service';
import { money, slug } from './controle-format';

/**
 * ControleCompteComponent — port of pageAccount(id) from the prototype.
 * id 'caisse-central' = virtual view (lines whose sheet is 'Caisse Central').
 */
@Component({
  selector: 'app-controle-compte',
  standalone: true,
  imports: [RouterLink, LinesTableComponent],
  template: `
    @if (found()) {
      <p class="crumb"><a routerLink="/controle/comptes">Comptes</a> / {{ accountName() }}</p>
      <div>
        <h1>{{ accountName() }}</h1>
        <p class="lead">{{ leadText() }}</p>
      </div>

      <!-- KPIs -->
      <div class="kpis">
        <div class="kpi"><span>Entrées</span><b class="pos">{{ money(kpis().e) }}</b><small>TND</small></div>
        <div class="kpi"><span>Sorties</span><b class="neg">{{ money(kpis().d) }}</b><small>TND</small></div>
        <div class="kpi"><span>Solde selon l'Excel</span><b [class.neg]="kpis().e - kpis().d < 0">{{ money(kpis().e - kpis().d) }}</b><small>à comparer au relevé ou au comptage</small></div>
        <div class="kpi"><span>Lignes</span><b>{{ kpis().n.toLocaleString('fr-FR') }}</b></div>
      </div>

      <!-- Par projet + Sorties par catégorie -->
      <div class="split">
        <div class="card">
          <h2>Par projet</h2>
          <div class="ctl-tw">
            <table>
              <thead>
                <tr>
                  <th>Projet</th>
                  <th class="num">Entrées</th>
                  <th class="num">Sorties</th>
                  <th class="num">Lignes</th>
                </tr>
              </thead>
              <tbody>
                @for (row of byProj(); track row.proj) {
                  <tr class="click" tabindex="0"
                      (click)="goProj(row.proj)" (keydown)="onKey($event, row.proj)">
                    <td>{{ row.proj }}</td>
                    <td class="num pos">{{ money(row.e) }}</td>
                    <td class="num neg">{{ money(row.d) }}</td>
                    <td class="num">{{ row.n }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>

        <div class="card">
          <h2>Sorties par catégorie</h2>
          <div class="ctl-tw">
            <table>
              <tbody>
                @for (row of byPoste(); track row.poste) {
                  <tr>
                    <td>{{ row.poste }}</td>
                    <td class="num">{{ money(row.total) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <!-- Lines table -->
      <ctl-lines-table [preset]="presetFn" [hideAcct]="!isVirtual()" title="Toutes les lignes du compte" />
    }
  `,
  styles: [`
    h1 { font-size: 22px; margin: 0 0 4px; color: var(--ctl-ink, #16212b); letter-spacing: -.015em; }
    .lead { color: var(--ctl-muted, #58656f); max-width: 80ch; font-size: 14px; margin: 0; }
    .crumb { font-size: 13px; color: var(--ctl-muted, #58656f); margin: 0; }
    .crumb a { color: var(--ctl-accent, #0d5c6e); text-decoration: none; }
    .num { font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums; }
    .pos { color: var(--ctl-ok, #1d7348); }
    .neg { color: var(--ctl-bad, #b42318); }

    .kpis { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(min(100%, 190px), 1fr)); }
    .kpi { background: var(--ctl-panel, #fff); border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); padding: 14px 16px; }
    .kpi span { display: block; font-size: 12px; color: var(--ctl-muted, #58656f); }
    .kpi b { display: block; font-family: var(--ctl-font-num); font-weight: 500; font-size: 21px;
      margin-top: 4px; font-variant-numeric: tabular-nums; }
    .kpi small { color: var(--ctl-muted, #58656f); font-size: 12px; }

    .card { background: var(--ctl-panel, #fff); border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); padding: 16px; display: flex; flex-direction: column; gap: 12px; min-width: 0; }
    h2 { font-size: 15px; margin: 0; }

    .split { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(min(100%, 420px), 1fr)); }

    .ctl-tw { overflow-x: auto; border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); background: var(--ctl-panel, #fff); }
    table { border-collapse: collapse; width: 100%; font-size: 13px; }
    th { text-align: left; font-weight: 600; color: var(--ctl-muted, #58656f); font-size: 12px;
      padding: 8px 10px; border-bottom: 1px solid var(--ctl-line, #dbe1df); background: var(--ctl-panel-2, #eef2f1); white-space: nowrap; }
    th.num, td.num { font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums; text-align: right; white-space: nowrap; }
    td { padding: 8px 10px; border-bottom: 1px solid var(--ctl-line, #dbe1df); }
    tr:last-child td { border-bottom: 0; }
    tbody tr.click { cursor: pointer; }
    tbody tr.click:hover td { background: var(--ctl-accent-soft, #e1eff2); }

    @media (max-width: 400px) {
      .kpi b { font-size: 18px; }
      h1 { font-size: 19px; }
      .split { grid-template-columns: 1fr; }
    }
  `]
})
export class ControleCompteComponent implements OnInit {
  protected store = inject(ControleStore);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  protected money = money;

  private id = signal<string>('');
  protected found = signal(false);

  // The preset function for the lines table
  presetFn = (l: CtlLine) => false; // set in ngOnInit

  isVirtual = computed(() => this.id() === 'caisse-central');

  accountName = computed(() => {
    if (this.isVirtual()) return 'Caisse Central';
    const a = this.store.acctById().get(this.id());
    return a?.name ?? '';
  });

  leadText = computed(() => {
    const scope = this.store.scopeName();
    if (this.isVirtual()) {
      return `Ce n'est pas un vrai compte : c'est la feuille qui réunit UIB SIGAT, UIB OMAR, UIB NGNET et la caisse SIGAT. Toutes les entrées et sorties de ce compte pour ${scope}.`;
    }
    const a = this.store.acctById().get(this.id());
    const note = a?.note ? a.note + ' ' : '';
    return `${note}Toutes les entrées et sorties de ce compte pour ${scope}.`;
  });

  private matchingLines = computed(() => {
    const lines = this.store.data()?.lines ?? [];
    const id = this.id();
    if (this.isVirtual()) {
      return lines.filter(l => l.sheet === 'Caisse Central' && this.store.inScope(l.proj));
    }
    return lines.filter(l => l.acct === id && this.store.inScope(l.proj));
  });

  kpis = computed(() => {
    const rows = this.matchingLines();
    let e = 0, d = 0;
    for (const l of rows) { e += l.e; d += l.d; }
    return { e, d, n: rows.length };
  });

  byProj = computed(() => {
    const m = new Map<string, { proj: string; e: number; d: number; n: number }>();
    for (const l of this.matchingLines()) {
      const o = m.get(l.proj) || { proj: l.proj, e: 0, d: 0, n: 0 };
      o.e += l.e;
      o.d += l.d;
      o.n++;
      m.set(l.proj, o);
    }
    return [...m.values()].sort((a, b) => b.d - a.d);
  });

  byPoste = computed(() => {
    const m = new Map<string, { poste: string; total: number }>();
    for (const l of this.matchingLines()) {
      if (!l.d) continue;
      const o = m.get(l.poste) || { poste: l.poste, total: 0 };
      o.total += l.d;
      m.set(l.poste, o);
    }
    return [...m.values()].sort((a, b) => b.total - a.total);
  });

  ngOnInit(): void {
    this.route.paramMap.subscribe(params => {
      const id = params.get('id');
      if (!id) { this.router.navigate(['/controle/comptes']); return; }
      if (id === 'caisse-central') {
        this.id.set(id);
        this.found.set(true);
        this.presetFn = (l: CtlLine) => l.sheet === 'Caisse Central' && this.store.inScope(l.proj);
      } else {
        const a = this.store.acctById().get(id);
        if (!a) { this.router.navigate(['/controle/comptes']); return; }
        this.id.set(id);
        this.found.set(true);
        this.presetFn = (l: CtlLine) => l.acct === id && this.store.inScope(l.proj);
      }
    });
  }

  goProj(p: string): void {
    this.router.navigate(['/controle/projets', slug(p)]);
  }

  onKey(ev: KeyboardEvent, p: string): void {
    if (ev.key === 'Enter') this.goProj(p);
  }
}
