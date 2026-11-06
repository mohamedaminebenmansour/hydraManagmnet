import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ControleStore } from './controle.store';
import { SourceMatrixComponent } from './source-matrix.component';
import { LinesTableComponent } from './lines-table.component';
import { CtlLine } from '@app/core/services/controle.service';
import { GRP, money, pct, slug } from './controle-format';

interface EntryRow { label: string; amount: number; }
interface PartyRow { name: string; amount: number; }

/**
 * ControleProjetComponent — port of pageProject(slug) from the prototype.
 *
 * Clicking a category in the source matrix sets the lines table filter
 * poste=<category> and scrolls to it (smooth). The lines table is recreated
 * via a @for track key when the filter changes.
 */
@Component({
  selector: 'app-controle-projet',
  standalone: true,
  imports: [RouterLink, SourceMatrixComponent, LinesTableComponent],
  template: `
    @if (found()) {
      <p class="crumb"><a routerLink="/controle/projets">Projets</a> / {{ projectName() }}</p>
      <div>
        <h1>{{ projectName() }}</h1>
        <p class="lead">{{ leadText() }}</p>
      </div>

      <!-- KPIs -->
      <div class="kpis">
        <div class="kpi"><span>Sorties (coût)</span><b class="neg">{{ money(totals().d) }}</b><small>TND, tous comptes</small></div>
        <div class="kpi"><span>Entrées</span><b class="pos">{{ money(totals().e) }}</b><small>TND</small></div>
        <div class="kpi"><span>Sorties justifiées</span><b>{{ totals().d ? pct(totals().dj / totals().d) : '—' }}</b><small>{{ money(totals().dj) }} TND</small></div>
        <div class="kpi"><span>Lignes</span><b>{{ totals().n.toLocaleString('fr-FR') }}</b></div>
      </div>

      <!-- Source matrix by category -->
      <div class="card">
        <h2>Sorties par catégorie et par compte</h2>
        <p class="muted">Cliquez une catégorie pour voir ses lignes.</p>
        <ctl-source-matrix [data]="projectLines()" [groupBy]="groupByPoste" [onClick]="onCategoryClick" />
      </div>

      <!-- Split: entrées + sous-traitants -->
      <div class="split">
        <div class="card">
          <h2>D'où viennent les entrées</h2>
          <div class="ctl-tw">
            <table>
              <tbody>
                @for (row of entries(); track row.label) {
                  <tr><td>{{ row.label }}</td><td class="num pos">{{ money(row.amount) }}</td></tr>
                }
              </tbody>
            </table>
          </div>
        </div>
        <div class="card">
          <h2>Payé par sous-traitant (toutes caisses)</h2>
          <div class="ctl-tw">
            <table>
              <tbody>
                @for (row of parties(); track row.name) {
                  <tr class="click" tabindex="0"
                      (click)="goSt(row.name)" (keydown)="onStKey($event, row.name)">
                    <td>{{ row.name }}</td>
                    <td class="num">{{ money(row.amount) }}</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        </div>
      </div>

      <!-- Lines table (recreated when filterPoste changes via @for track) -->
      <div class="lines-anchor">
        @for (key of [filterKey()]; track key) {
          <ctl-lines-table
            [preset]="presetFn"
            [hideProj]="true"
            [initialFilters]="currentFilters()"
            title="Toutes les lignes du projet" />
        }
      </div>
    }
  `,
  styles: [`
    h1 { font-size: 22px; margin: 0 0 4px; color: var(--ctl-ink, #16212b); letter-spacing: -.015em; }
    .lead { color: var(--ctl-muted, #58656f); max-width: 80ch; font-size: 14px; margin: 0; }
    .crumb { font-size: 13px; color: var(--ctl-muted, #58656f); margin: 0; }
    .crumb a { color: var(--ctl-accent, #0d5c6e); text-decoration: none; }
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
      border-radius: var(--ctl-radius, 10px); padding: 16px; display: flex; flex-direction: column;
      gap: 12px; min-width: 0; }
    h2 { font-size: 15px; margin: 0; }
    .split { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(min(100%, 420px), 1fr)); }
    .ctl-tw { overflow-x: auto; border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); background: var(--ctl-panel, #fff); }
    table { border-collapse: collapse; width: 100%; font-size: 13px; }
    td { padding: 8px 10px; border-bottom: 1px solid var(--ctl-line, #dbe1df); }
    tr:last-child td { border-bottom: 0; }
    td.num { font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums; text-align: right; white-space: nowrap; }
    tbody tr.click { cursor: pointer; }
    tbody tr.click:hover td { background: var(--ctl-accent-soft, #e1eff2); }
    .lines-anchor { scroll-margin-top: 80px; }
    @media (max-width: 400px) {
      .kpi b { font-size: 18px; } h1 { font-size: 19px; } .split { grid-template-columns: 1fr; }
    }
  `]
})
export class ControleProjetComponent implements OnInit {
  protected store = inject(ControleStore);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  protected money = money;
  protected pct = pct;

  private slug = signal('');
  protected found = signal(false);
  protected filterPoste = signal('');

  // Preset function for the lines table
  presetFn = (l: CtlLine) => false;

  // Group-by function for source matrix (by category/poste)
  groupByPoste = (l: CtlLine) => l.poste;

  // Category click handler — sets the filter and scrolls to the table
  onCategoryClick = (poste: string) => {
    this.filterPoste.set(poste);
    setTimeout(() => {
      document.querySelector('.lines-anchor')?.scrollIntoView({ behavior: 'smooth' });
    }, 50);
  };

  projectName = computed(() => {
    const lines = this.store.data()?.lines ?? [];
    const sl = this.slug();
    return [...new Set(lines.map(l => l.proj))].find(p => slug(p) === sl) ?? '';
  });

  leadText = computed(() => {
    const p = this.projectName();
    return GRP.includes(p)
      ? 'Projet du groupement SIGAT – NGNET. Le coût vient des comptes UIB, de la caisse chantier et de la caisse NGNET du projet.'
      : 'Toutes les entrées et sorties de ce projet.';
  });

  projectLines = computed(() => {
    const p = this.projectName();
    if (!p) return [] as CtlLine[];
    const lines = this.store.data()?.lines ?? [];
    return lines.filter(l => l.proj === p);
  });

  totals = computed(() => this.store.totals(this.projectLines()));

  entries = computed<EntryRow[]>(() => {
    const m = new Map<string, number>();
    for (const l of this.projectLines()) {
      const v = this.store.inAmt(l);
      if (v > 0) {
        const acctName = this.store.acctById().get(l.acct)?.name ?? 'Sans compte';
        const k = acctName + ' · ' + l.poste;
        m.set(k, (m.get(k) ?? 0) + v);
      }
    }
    return [...m.entries()]
      .map(([label, amount]) => ({ label, amount }))
      .sort((a, b) => b.amount - a.amount);
  });

  parties = computed<PartyRow[]>(() => {
    const m = new Map<string, number>();
    for (const l of this.projectLines()) {
      if (l.party) {
        const v = this.store.outAmt(l);
        if (v > 0) {
          m.set(l.party, (m.get(l.party) ?? 0) + v);
        }
      }
    }
    return [...m.entries()]
      .map(([name, amount]) => ({ name, amount }))
      .sort((a, b) => b.amount - a.amount);
  });

  // The filter key for the @for track — changes when filterPoste changes,
  // causing Angular to recreate the lines table with the new initialFilters.
  filterKey = computed(() => this.filterPoste());

  currentFilters = computed(() => {
    const poste = this.filterPoste();
    return poste ? { poste } : {};
  });

  ngOnInit(): void {
    this.route.paramMap.subscribe(params => {
      const sl = params.get('slug');
      if (!sl) { this.router.navigate(['/controle/projets']); return; }
      this.slug.set(sl);
      const lines = this.store.data()?.lines ?? [];
      const p = [...new Set(lines.map(l => l.proj))].find(x => slug(x) === sl);
      if (!p) { this.router.navigate(['/controle/projets']); return; }
      this.found.set(true);
      this.presetFn = (l: CtlLine) => l.proj === p;
    });
  }

  goSt(name: string): void {
    this.router.navigate(['/controle/sous-traitants', slug(name)]);
  }

  onStKey(ev: KeyboardEvent, name: string): void {
    if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); this.goSt(name); }
  }
}
