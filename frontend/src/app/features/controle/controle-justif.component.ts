import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { ControleStore } from './controle.store';
import { LinesTableComponent } from './lines-table.component';
import { CtlLine } from '@app/core/services/controle.service';
import { money, money0, pct } from './controle-format';

interface ProjKpi { name: string; dj: number; d: number; }

/**
 * ControleJustifComponent — port of pageJustif() from the prototype.
 *
 * KPIs (overall % and one per project), lines table of all sorties in scope
 * sorted by amount (largest first), default filter statut = « Non vérifié »,
 * unless the URL has ?flag=x, then filter flag = x and statut empty.
 */
@Component({
  selector: 'app-controle-justif',
  standalone: true,
  imports: [LinesTableComponent],
  template: `
    <div>
      <h1>Justificatifs</h1>
      <p class="lead">Chaque sortie doit avoir sa facture, son attachement ou son reçu. Les lignes sont classées par montant, les plus grosses d'abord. Cliquez une ligne pour noter son justificatif.</p>
    </div>

    <!-- KPIs -->
    <div class="kpis">
      <div class="kpi">
        <span>Sorties justifiées</span>
        <b>{{ totals().d ? pct(totals().dj / totals().d) : '—' }}</b>
        <small>{{ money(totals().dj) }} sur {{ money(totals().d) }} TND</small>
      </div>
      @for (p of projKpis(); track p.name) {
        <div class="kpi">
          <span>{{ p.name }}</span>
          <b>{{ p.d ? pct(p.dj / p.d) : '—' }}</b>
          <small>justifié sur {{ money0(p.d) }} TND</small>
        </div>
      }
    </div>

    <!-- Lines table: all sorties in scope, sorted by amount descending -->
    <ctl-lines-table
      [preset]="sortiesPreset"
      title="Sorties à justifier"
      [initialFilters]="initialFilters()"
      [sort]="amountSort" />
  `,
  styles: [`
    h1 { font-size: 22px; margin: 0 0 4px; color: var(--ctl-ink, #16212b); letter-spacing: -.015em; }
    .lead { color: var(--ctl-muted, #58656f); max-width: 80ch; font-size: 14px; margin: 0; }
    .kpis { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(min(100%, 190px), 1fr)); }
    .kpi { background: var(--ctl-panel, #fff); border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); padding: 14px 16px; }
    .kpi span { display: block; font-size: 12px; color: var(--ctl-muted, #58656f); }
    .kpi b { display: block; font-family: var(--ctl-font-num); font-weight: 500; font-size: 21px;
      margin-top: 4px; font-variant-numeric: tabular-nums; }
    .kpi small { color: var(--ctl-muted, #58656f); font-size: 12px; }
  `]
})
export class ControleJustifComponent implements OnInit {
  protected store = inject(ControleStore);
  private route = inject(ActivatedRoute);

  protected money = money;
  protected money0 = money0;
  protected pct = pct;

  private flagParam = signal<string | null>(null);

  // Preset: all sorties in scope (outAmt > 0)
  sortiesPreset = (l: CtlLine) => this.store.inScope(l.proj) && this.store.outAmt(l) > 0;

  // Sort by amount descending (largest first)
  amountSort = (a: CtlLine, b: CtlLine) => this.store.outAmt(b) - this.store.outAmt(a);

  scopeLines = computed(() => this.store.scopeLines());
  totals = computed(() => this.store.totals(this.scopeLines()));

  projKpis = computed<ProjKpi[]>(() => {
    const projects = this.store.projList();
    const lines = this.scopeLines();
    return projects.map(name => {
      const r = lines.filter(l => l.proj === name);
      const t = this.store.totals(r);
      return { name, dj: t.dj, d: t.d };
    });
  });

  initialFilters = computed(() => {
    const flag = this.flagParam();
    if (flag) {
      // URL has ?flag=x: filter by that flag, no statut filter
      return { flag, statut: '' };
    }
    // Default: statut = 'none' (Non vérifié)
    return { statut: 'none' };
  });

  ngOnInit(): void {
    this.route.queryParamMap.subscribe(params => {
      const flag = params.get('flag');
      this.flagParam.set(flag);
    });
  }
}
