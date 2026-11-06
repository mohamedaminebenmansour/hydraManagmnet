import { Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { ControleStore } from './controle.store';
import { SourceMatrixComponent } from './source-matrix.component';
import { LinesTableComponent } from './lines-table.component';
import { CtlLine } from '@app/core/services/controle.service';
import { GRP, FLAGS, money, money0, pct, slug } from './controle-format';

interface FlagCount { flag: string; count: number; }

interface AcctJustif {
  id: string; name: string;
  d: number; j: number; p: number; x: number;
}

/**
 * ControleHomeComponent — port of pageHome() + justifByAcct() from the prototype.
 * Everything recomputes when the scope switch changes (computed signals).
 */
@Component({
  selector: 'app-controle-home',
  standalone: true,
  imports: [SourceMatrixComponent],
  template: `
    <div>
      <h1>Où est l'argent de {{ store.scopeName() }}</h1>
      <p class="lead">Chaque ligne de chaque compte, avec la feuille et la ligne de l'Excel. Cliquez sur une ligne pour voir son détail et noter son justificatif (facture, attachement, reçu…).</p>
    </div>

    <!-- KPIs -->
    <div class="kpis">
      <div class="kpi"><span>Entrées</span><b class="pos">{{ money(totals().e) }}</b><small>TND, y compris l'argent mis par NGNET dans ses caisses</small></div>
      <div class="kpi"><span>Sorties</span><b class="neg">{{ money(totals().d) }}</b><small>TND, hors transferts entre comptes</small></div>
      <div class="kpi"><span>Lignes</span><b>{{ totals().n.toLocaleString('fr-FR') }}</b><small>dans tous les comptes</small></div>
      <div class="kpi"><span>Sorties justifiées</span><b>{{ totals().d ? pct(totals().dj / totals().d) : '—' }}</b><small>{{ money(totals().dj) }} TND justifiés</small></div>
    </div>

    <!-- Source matrix by project -->
    <div class="card">
      <h2>Qui a payé quoi, projet par projet</h2>
      <p class="muted">Les sorties de chaque compte pour chaque projet. Les alimentations de caisse chantier ne sont pas comptées ici : la dépense est comptée quand la caisse chantier paie.</p>
      <ctl-source-matrix [data]="scopeLines()" [groupBy]="groupByProj" [onClick]="goToProj" />
    </div>

    <!-- Split: À vérifier + Justificatifs par compte -->
    <div class="split">
      <!-- À vérifier en priorité -->
      <div class="card">
        <h2>À vérifier en priorité</h2>
        <div class="ctl-tw">
          <table>
            <tbody>
              @for (fc of flagCounts(); track fc.flag) {
                <tr class="click" tabindex="0" (click)="goToJustif(fc.flag)" (keydown)="onKey($event, fc.flag)">
                  <td><span class="chip flag">{{ FLAGS[fc.flag][0] }}</span></td>
                  <td class="muted">{{ FLAGS[fc.flag][1] }}</td>
                  <td class="num">{{ fc.count }} lignes</td>
                </tr>
              }
              <tr class="click" tabindex="0" (click)="goToSt()" (keydown)="onKeySt($event)">
                <td><span class="chip red">{{ FLAGS['non-retrouve'][0] }}</span></td>
                <td class="muted">{{ FLAGS['non-retrouve'][1] }}</td>
                <td class="num">{{ stNotFound() }} lignes</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Justificatifs par compte -->
      <div class="card">
        <h2>Justificatifs par compte</h2>
        <div class="legend">
          <span><i style="background:var(--ctl-ok)"></i> Justifié</span>
          <span><i style="background:var(--ctl-warn)"></i> Partiel</span>
          <span><i style="background:var(--ctl-bad)"></i> Non justifié ou contesté</span>
          <span><i style="background:var(--ctl-panel-2);border:1px solid var(--ctl-line)"></i> Pas encore vérifié</span>
        </div>
        @for (a of acctJustifs(); track a.id) {
          <div class="click acct-bar" tabindex="0" (click)="goToAcct(a.id)" (keydown)="onKeyAcct($event, a.id)">
            <div class="bar-row">
              <span>{{ a.name }}</span>
              <span class="muted num">{{ pct(a.j / a.d) }} de {{ money0(a.d) }} TND</span>
            </div>
            <div class="bar" role="img" [attr.aria-label]="a.name + ' : ' + pct(a.j / a.d) + ' justifié'">
              <i [style.width.%]="a.j / a.d * 100" [style.background]="'var(--ctl-ok)'"></i>
              <i [style.width.%]="a.p / a.d * 100" [style.background]="'var(--ctl-warn)'"></i>
              <i [style.width.%]="a.x / a.d * 100" [style.background]="'var(--ctl-bad)'"></i>
            </div>
          </div>
        }
      </div>
    </div>
  `,
  styles: [`
    h1 { font-size: 22px; margin: 0 0 4px; color: var(--ctl-ink, #16212b); letter-spacing: -.015em; }
    .lead { color: var(--ctl-muted, #58656f); max-width: 80ch; font-size: 14px; margin: 0; }
    .muted { color: var(--ctl-muted, #58656f); }
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

    .chip { display: inline-flex; align-items: center; gap: 4px; font-size: 11.5px; padding: 1px 7px;
      border-radius: 999px; background: var(--ctl-panel-2, #eef2f1); color: var(--ctl-muted, #58656f);
      border: 1px solid var(--ctl-line, #dbe1df); white-space: nowrap; }
    .chip.flag { background: var(--ctl-warn-soft, #fbf0dc); color: var(--ctl-warn, #9a5b00); border-color: transparent; }
    .chip.red { background: var(--ctl-bad-soft, #fbe7e5); color: var(--ctl-bad, #b42318); border-color: transparent; }

    .legend { display: flex; flex-wrap: wrap; gap: 4px 14px; font-size: 12px; color: var(--ctl-muted, #58656f); }
    .legend i { display: inline-block; width: 9px; height: 9px; border-radius: 2px; margin-right: 5px; vertical-align: -1px; }

    .acct-bar { cursor: pointer; }
    .acct-bar:hover { background: var(--ctl-accent-soft, #e1eff2); border-radius: 8px; }
    .bar-row { display: flex; justify-content: space-between; gap: 8px; font-size: 13px; }
    .bar { height: 8px; border-radius: 999px; background: var(--ctl-panel-2, #eef2f1); overflow: hidden; display: flex; margin-top: 4px; }
    .bar i { display: block; height: 100%; }

    @media (max-width: 400px) {
      .kpi b { font-size: 18px; }
      h1 { font-size: 19px; }
      .split { grid-template-columns: 1fr; }
    }
  `]
})
export class ControleHomeComponent {
  protected store = inject(ControleStore);
  private router = inject(Router);

  protected FLAGS = FLAGS;
  protected money = money;
  protected money0 = money0;
  protected pct = pct;

  // Group-by function for source matrix (by project)
  groupByProj = (l: CtlLine) => l.proj;

  // Navigation callbacks for source matrix
  goToProj = (p: string) => this.router.navigate(['/controle/projets', slug(p)]);

  // ── Computed data ────────────────────────────────────────────────────────
  scopeLines = computed(() => this.store.scopeLines());

  totals = computed(() => this.store.totals(this.scopeLines()));

  flagCounts = computed<FlagCount[]>(() => {
    const rows = this.scopeLines();
    const fcount: Record<string, number> = {};
    for (const l of rows) {
      for (const f of l.flags) {
        fcount[f] = (fcount[f] || 0) + 1;
      }
    }
    return Object.entries(fcount)
      .map(([flag, count]) => ({ flag, count }))
      .sort((a, b) => b.count - a.count);
  });

  stNotFound = computed(() => {
    const data = this.store.data();
    if (!data) return 0;
    return data.stLines.filter(s => {
      if (!s.flags.includes('non-retrouve')) return false;
      const a = this.store.stAcctById().get(s.st);
      return a ? this.store.inScope(a.proj) : false;
    }).length;
  });

  acctJustifs = computed<AcctJustif[]>(() => {
    const rows = this.scopeLines();
    const m = new Map<string, AcctJustif>();
    for (const l of rows) {
      const o = m.get(l.acct) || { id: l.acct, name: this.store.acctById().get(l.acct)?.name ?? l.acct, d: 0, j: 0, p: 0, x: 0 };
      const v = this.store.outAmt(l);
      o.d += v;
      const s = this.store.jStatus(l.id);
      if (s === 'justifie') o.j += v;
      else if (s === 'partiel') o.p += v;
      else if (s === 'non-justifie' || s === 'conteste') o.x += v;
      m.set(l.acct, o);
    }
    const accounts = this.store.data()?.accounts ?? [];
    return accounts
      .map(a => m.get(a.id))
      .filter((o): o is AcctJustif => !!o && o.d > 0);
  });

  // ── Navigation ───────────────────────────────────────────────────────────
  goToJustif(flag: string): void {
    this.router.navigate(['/controle/justificatifs'], { queryParams: { flag } });
  }

  goToSt(): void {
    this.router.navigate(['/controle/sous-traitants']);
  }

  goToAcct(id: string): void {
    this.router.navigate(['/controle/comptes', id]);
  }

  onKey(ev: KeyboardEvent, flag: string): void {
    if (ev.key === 'Enter') this.goToJustif(flag);
  }

  onKeySt(ev: KeyboardEvent): void {
    if (ev.key === 'Enter') this.goToSt();
  }

  onKeyAcct(ev: KeyboardEvent, id: string): void {
    if (ev.key === 'Enter') this.goToAcct(id);
  }
}
