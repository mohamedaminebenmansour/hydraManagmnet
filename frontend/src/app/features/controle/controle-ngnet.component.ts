import { Component, computed, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { ControleStore } from './controle.store';
import { LinesTableComponent } from './lines-table.component';
import { ValidationBlockComponent } from './validation-block.component';
import { CtlLine, CtlAccount } from '@app/core/services/controle.service';
import { GRP, money, pct, slug } from './controle-format';

interface NgnetSection {
  project: string;
  box: CtlAccount;
  ngTotal: number;
  sigOut: number;
  encT: number;
  byPoste: { poste: string; total: number }[];
  entries: { label: string; amount: number }[];
  ngPreset: (l: CtlLine) => boolean;
  uibngPreset: ((l: CtlLine) => boolean) | null;
}

/**
 * ControleNgnetComponent — port of pageNgnet() from the prototype.
 * For each GRP project (Hajeb, Hammed, Ouled Khalfallah): KPIs, category
 * breakdown, entries, lines table of the partner box (sens=out), UIB NGNET
 * lines, and a validation block.
 */
@Component({
  selector: 'app-controle-ngnet',
  standalone: true,
  imports: [RouterLink, ButtonModule, LinesTableComponent, ValidationBlockComponent],
  template: `
    <div class="no-print">
      <button class="ctl-btn" type="button" (click)="print()">
        <i class="pi pi-print"></i> Version imprimable
      </button>
    </div>
    <div>
      <h1>Réunion avec NGNET</h1>
      <p class="lead">Pour chaque projet du groupement : ce que NGNET a payé directement, ce que SIGAT a payé, et ce qui est entré sur les comptes. Les décomptes payés par le client ne sont pas dans l'Excel : la marge et la part de chacun restent à établir avec NGNET.</p>
    </div>
    @for (s of sections(); track s.project) {
      <section class="card">
        <div class="sheet-head">
          <h2>{{ s.project }}</h2>
          <a routerLink="/controle/projets" [queryParams]="{ slug: slug(s.project) }">Voir tout le projet</a>
        </div>

        <!-- KPIs -->
        <div class="kpis">
          <div class="kpi"><span>Payé par NGNET (caisse NGNET)</span><b>{{ money(s.ngTotal) }}</b><small>TND</small></div>
          <div class="kpi"><span>Payé depuis les comptes UIB, caisse SIGAT et caisse chantier</span><b>{{ money(s.sigOut) }}</b><small>TND</small></div>
          <div class="kpi"><span>Coût total</span><b>{{ money(s.ngTotal + s.sigOut) }}</b><small>{{ pct(s.ngTotal / (s.ngTotal + s.sigOut || 1)) }} payé par NGNET</small></div>
          <div class="kpi"><span>Reçu sur les comptes SIGAT</span><b class="pos">{{ money(s.encT) }}</b><small>encaissements du projet</small></div>
        </div>

        <!-- Split: category breakdown + entries -->
        <div class="split">
          <div>
            <h3>Ce que NGNET a payé, par catégorie</h3>
            <div class="ctl-tw">
              <table>
                <tbody>
                  @for (row of s.byPoste; track row.poste) {
                    <tr><td>{{ row.poste }}</td><td class="num">{{ money(row.total) }}</td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
          <div>
            <h3>Ce qui est entré pour ce projet</h3>
            <div class="ctl-tw">
              <table>
                <tbody>
                  @for (row of s.entries; track row.label) {
                    <tr><td>{{ row.label }}</td><td class="num pos">{{ money(row.amount) }}</td></tr>
                  }
                </tbody>
              </table>
            </div>
          </div>
        </div>

        <!-- NGNET box lines (sorties only) -->
        <ctl-lines-table [preset]="s.ngPreset" [hideAcct]="true" [hideProj]="true"
                         title="Lignes de la caisse NGNET {{ s.project }}"
                         [initialFilters]="{ sens: 'out' }" />

        <!-- UIB NGNET lines (if any) -->
        @if (s.uibngPreset) {
          <ctl-lines-table [preset]="s.uibngPreset" [hideAcct]="true" [hideProj]="true"
                           title="Lignes UIB NGNET pour {{ s.project }}" />
        }

        <!-- Validation -->
        <ctl-validation-block [key]="'ngnet-' + slug(s.project)"
                               [label]="'Validation avec NGNET — ' + s.project"
                               [suggestedBalance]="null" />
      </section>
    }
    @if (sections().length === 0) {
      <div class="empty">Aucun projet du groupement NGNET (Hajeb, Hammed, Ouled Khalfallah) dans les données importées.</div>
    }
  `,
  styles: [`
    h1 { font-size: 22px; margin: 0 0 4px; color: var(--ctl-ink, #16212b); letter-spacing: -.015em; }
    .lead { color: var(--ctl-muted, #58656f); max-width: 80ch; font-size: 14px; margin: 0; }
    .muted { color: var(--ctl-muted, #58656f); }
    .num { font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums; }
    .pos { color: var(--ctl-ok, #1d7348); }
    .no-print { display: flex; justify-content: flex-end; }
    .ctl-btn { font: inherit; font-size: 13px; font-weight: 600; border-radius: 8px;
      padding: 7px 14px; border: 1px solid var(--ctl-line, #dbe1df);
      background: var(--ctl-panel, #fff); color: var(--ctl-ink, #16212b); cursor: pointer;
      display: inline-flex; align-items: center; gap: 6px; }
    .card { background: var(--ctl-panel, #fff); border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); padding: 16px; display: flex; flex-direction: column;
      gap: 12px; min-width: 0; }
    h2 { font-size: 15px; margin: 0; }
    h3 { font-size: 13px; margin: 0 0 8px; text-transform: uppercase; letter-spacing: .06em;
      color: var(--ctl-muted, #58656f); font-weight: 600; }
    .sheet-head { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; align-items: baseline; }
    .sheet-head a { color: var(--ctl-accent, #0d5c6e); text-decoration: none; font-size: 13px; }
    .kpis { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(min(100%, 190px), 1fr)); }
    .kpi { background: var(--ctl-panel, #fff); border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); padding: 14px 16px; }
    .kpi span { display: block; font-size: 12px; color: var(--ctl-muted, #58656f); }
    .kpi b { display: block; font-family: var(--ctl-font-num); font-weight: 500; font-size: 21px;
      margin-top: 4px; font-variant-numeric: tabular-nums; }
    .kpi small { color: var(--ctl-muted, #58656f); font-size: 12px; }
    .split { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(min(100%, 420px), 1fr)); }
    .ctl-tw { overflow-x: auto; border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); background: var(--ctl-panel, #fff); }
    table { border-collapse: collapse; width: 100%; font-size: 13px; }
    td { padding: 8px 10px; border-bottom: 1px solid var(--ctl-line, #dbe1df); }
    tr:last-child td { border-bottom: 0; }
    td.num { font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums; text-align: right; white-space: nowrap; }
    .empty { padding: 24px; text-align: center; color: var(--ctl-muted, #58656f); }
    @media print {
      .no-print, .ctl-filters, .ctl-more, .ctl-btn, button { display: none !important; }
    }
  `]
})
export class ControleNgnetComponent {
  protected store = inject(ControleStore);

  protected money = money;
  protected pct = pct;
  protected slug = slug;

  sections = computed<NgnetSection[]>(() => {
    const data = this.store.data();
    if (!data) return [];
    const lines = data.lines;
    const accounts = data.accounts;

    // Find bank accounts whose name contains 'NGNET' (for the UIB NGNET table)
    const uibNgAcctIds = accounts
      .filter(a => a.kind === 'banque' && a.name.toUpperCase().includes('NGNET'))
      .map(a => a.id);

    return GRP.map(project => {
      // Find the partner (NGNET) box for this project
      const box = accounts.find(a => a.kind === 'partenaire' && a.project === project);
      if (!box) return null;

      const ng = lines.filter(l => l.acct === box.id);
      const sig = lines.filter(l => l.proj === project && l.acct !== box.id);
      const enc = sig.filter(l => this.store.inAmt(l) > 0);

      // NGNET payments by category
      const byPosteMap = new Map<string, number>();
      for (const l of ng) {
        byPosteMap.set(l.poste, (byPosteMap.get(l.poste) ?? 0) + l.d);
      }

      // Entries
      const encMap = new Map<string, number>();
      for (const l of enc) {
        const acctName = this.store.acctById().get(l.acct)?.name ?? 'Sans compte';
        const k = acctName + ' · ' + l.poste;
        encMap.set(k, (encMap.get(k) ?? 0) + this.store.inAmt(l));
      }

      const ngTotal = ng.reduce((s, l) => s + l.d, 0);
      const sigOut = this.store.totals(sig).d;
      const encT = enc.reduce((s, l) => s + this.store.inAmt(l), 0);

      // UIB NGNET preset (if any bank accounts with 'NGNET' in the name exist)
      const uibngPreset = uibNgAcctIds.length > 0
        ? ((l: CtlLine) => uibNgAcctIds.includes(l.acct) && l.proj === project)
        : null;

      return {
        project,
        box,
        ngTotal,
        sigOut,
        encT,
        byPoste: [...byPosteMap.entries()].map(([poste, total]) => ({ poste, total })).sort((a, b) => b.total - a.total),
        entries: [...encMap.entries()].map(([label, amount]) => ({ label, amount })).sort((a, b) => b.amount - a.amount),
        ngPreset: (l: CtlLine) => l.acct === box.id,
        uibngPreset,
      };
    }).filter((s): s is NgnetSection => s !== null);
  });

  print(): void {
    window.print();
  }
}
