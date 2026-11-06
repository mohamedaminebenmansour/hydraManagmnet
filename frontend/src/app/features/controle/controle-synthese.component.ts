import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { CtlAccount, CtlLine } from '@app/core/services/controle.service';
import { ControleStore } from './controle.store';
import { LinesTableComponent } from './lines-table.component';
import { csvAmount, downloadCsv, money, pct } from './controle-format';
import {
  DEFAULT_GROUPEMENT, FACTURE_LEVELS, FAMILLES_PAR_PROJET, FAMILLE_ORDER, FactureLevel, GROUPEMENTS,
  GroupementKey, MOUVEMENTS_INTERNES, RECAP_RUBRIQUES, SOURCES, factureLevel, groupementOf, isFacturee, projectsOf, sourceOf,
} from './controle-groups';

type Tab = 'synthese' | 'recap';

/** Totals of a set of lines, on the Synthèse page rules. */
export interface SynthTotals {
  e: number;              // entrées (transfers excluded)
  d: number;              // sorties (transfers excluded)
  fact: number;           // sorties facturées
  nonFact: number;        // sorties non facturées
  byLevel: Record<FactureLevel, number>;
  alimIn: number;         // transfer lines: e
  alimOut: number;        // transfer lines: d
  n: number;
}

interface Card { acct: CtlAccount; t: SynthTotals; }
interface SourceGroup { source: string; cards: Card[]; }

/** One row of the « Dépenses par famille » table (level 1 famille, 2 rubrique, 3 project). */
interface TreeRow {
  level: 1 | 2 | 3;
  key: string;
  label: string;
  famille: string;
  rubrique: string;
  proj: string;
  t: SynthTotals;
  expandable: boolean;
  open: boolean;
  internal: boolean;
}

interface ProjNode { proj: string; t: SynthTotals; }
interface RubNode { rubrique: string; t: SynthTotals; projs: ProjNode[]; }
interface FamNode { famille: string; t: SynthTotals; rubs: RubNode[]; }

const famOf = (l: CtlLine) => l.famille || 'Non classé';
const rubOf = (l: CtlLine) => l.rubrique || l.article || '(sans rubrique)';

/** The lines panel under the tables (one existing lines table, filtered). */
interface Panel {
  key: string;
  title: string;
  preset: (l: CtlLine) => boolean;
  highlight?: (l: CtlLine) => boolean;
  highlightLabel?: string;
  note?: string;
}

/** One cell of the comparison: one project × one rubrique. */
interface RecapCell { proj: string; recap: number; hydra: number; ecart: number; diff: boolean; }
interface RecapRow { rubrique: string; cells: RecapCell[]; }

/** |écart| from 0,0005 (half a millime) is an écart. */
const isDiff = (x: number) => Math.abs(x) >= 0.0005;

const LEVEL_CLASS: Record<FactureLevel, string> = {
  'Facture': 'lv-f', 'Bon': 'lv-b', 'Bon signé (S)': 'lv-s', 'Non facturée': 'lv-n',
};

/**
 * ControleSyntheseComponent — « Synthèse » of a groupement: one card per account,
 * and the comparison with the Excel recap. The groupement, the project and the
 * inner tab live in the URL (?g=ngnet&p=Hajeb&tab=recap).
 */
@Component({
  selector: 'app-controle-synthese',
  standalone: true,
  imports: [LinesTableComponent],
  template: `
    <div>
      <h1>Synthèse</h1>
      <p class="lead">Où va l'argent d'un groupement : par compte, par famille de dépenses, et comparé au
        « Récap des dépenses par projet » de l'Excel. Cette page a son propre choix de projets
        (le bouton en haut de l'écran ne s'applique pas ici).</p>
    </div>

    <!-- Selection -->
    <div class="sel">
      <div class="seg" role="group" aria-label="Groupement">
        @for (gr of GROUPEMENTS; track gr.key) {
          <button type="button" [attr.aria-pressed]="g() === gr.key" (click)="setGroupement(gr.key)">{{ gr.label }}</button>
        }
      </div>
      <label class="proj">
        Projet
        <select [value]="p()" (change)="setProject($any($event.target).value)">
          <option value="">Tous</option>
          @for (x of projects(); track x) {
            <option [value]="x" [selected]="x === p()">{{ x }}</option>
          }
        </select>
      </label>
    </div>

    <nav class="tabs" aria-label="Vues de la synthèse">
      <button type="button" [class.active]="tab() === 'synthese'" [attr.aria-pressed]="tab() === 'synthese'"
              (click)="setTab('synthese')">Synthèse</button>
      <button type="button" [class.active]="tab() === 'recap'" [attr.aria-pressed]="tab() === 'recap'"
              (click)="setTab('recap')">Comparaison avec le Récap Excel</button>
    </nav>

    @if (tab() === 'synthese') {
      <!-- Cards, grouped by source -->
      <section class="ctl-card">
        <div class="head">
          <h2>Comptes</h2>
          <button class="ctl-btn" type="button" (click)="exportCards()">Exporter (Excel)</button>
        </div>
        @if (sourceGroups().length === 0) {
          <div class="ctl-empty">Aucune ligne pour cette sélection.</div>
        }
        @for (sg of sourceGroups(); track sg.source) {
          <h3>{{ sg.source }}</h3>
          <div class="cards">
            @for (c of sg.cards; track c.acct.id) {
              <button type="button" class="acard" [class.on]="panel()?.key === 'acct:' + c.acct.id"
                      (click)="openAccount(c.acct)">
                <b class="name">{{ c.acct.name }}</b>
                <dl>
                  <dt>Entrées</dt><dd class="pos">{{ m(c.t.e) }}</dd>
                  <dt>Sorties</dt><dd class="neg">{{ m(c.t.d) }}</dd>
                  <dt>Alimentations reçues</dt><dd>{{ m(c.t.alimIn) }}</dd>
                  @if (c.t.alimOut > 0) { <dt>Alimentations envoyées</dt><dd>{{ m(c.t.alimOut) }}</dd> }
                  <dt>Facturée</dt><dd>{{ m(c.t.fact) }}</dd>
                  <dt>Non facturée</dt><dd>{{ m(c.t.nonFact) }}</dd>
                  <dt>% facturée</dt><dd>{{ c.t.d ? pct(c.t.fact / c.t.d) : '—' }}</dd>
                </dl>
                <span class="bar" aria-hidden="true">
                  @for (lv of LEVELS; track lv) {
                    @if (c.t.byLevel[lv] > 0 && c.t.d > 0) {
                      <i [class]="LEVEL_CLASS[lv]" [style.width.%]="100 * c.t.byLevel[lv] / c.t.d"
                         [title]="lv + ' : ' + m(c.t.byLevel[lv])"></i>
                    }
                  }
                </span>
                <small class="muted">{{ c.t.n.toLocaleString('fr-FR') }} lignes · voir les lignes</small>
              </button>
            }
          </div>
        }
        <p class="note">
          Facturée = sorties avec un N° de facture ou de bon (S compris). Les transferts (alimentations de caisse)
          ne sont ni des entrées ni des sorties.
          <span class="legend">
            @for (lv of LEVELS; track lv) { <span><i [class]="LEVEL_CLASS[lv]"></i>{{ lv }}</span> }
          </span>
        </p>
      </section>

      <!-- Dépenses par famille -->
      <section class="ctl-card">
        <div class="head">
          <h2>Dépenses par famille</h2>
          <button class="ctl-btn" type="button" (click)="exportTree()">Exporter (Excel)</button>
        </div>
        <div class="tw">
          <table>
            <thead>
              <tr>
                <th>Famille · rubrique · projet</th>
                <th class="num">Entrées</th>
                <th class="num">Sorties</th>
                <th class="num">Facturée</th>
                <th class="num">Non facturée</th>
                <th class="num">Lignes</th>
              </tr>
            </thead>
            <tbody>
              @for (r of treeRows(); track r.key) {
                <tr [class]="'lv' + r.level" [class.internal]="r.internal">
                  <td>
                    @if (r.expandable) {
                      <button type="button" class="tog" [attr.aria-expanded]="r.open" (click)="toggle(r.key)">
                        <span aria-hidden="true">{{ r.open ? '▾' : '▸' }}</span>
                        @if (r.level === 1) { {{ r.label }} }
                      </button>
                    }
                    @if (r.level > 1) {
                      <button type="button" class="link" (click)="openRubrique(r)"
                              [attr.aria-label]="'Voir les lignes : ' + r.label">{{ r.label }}</button>
                    }
                  </td>
                  <td class="num">{{ m(r.t.e) }}</td>
                  <td class="num">{{ m(r.t.d) }}</td>
                  <td class="num">{{ m(r.t.fact) }}</td>
                  <td class="num">{{ m(r.t.nonFact) }}</td>
                  <td class="num">{{ r.t.n.toLocaleString('fr-FR') }}</td>
                </tr>
              }
            </tbody>
            <tfoot>
              <tr>
                <th>Total (sans les mouvements internes)</th>
                <th class="num">{{ m(treeTotal().e) }}</th>
                <th class="num">{{ m(treeTotal().d) }}</th>
                <th class="num">{{ m(treeTotal().fact) }}</th>
                <th class="num">{{ m(treeTotal().nonFact) }}</th>
                <th class="num">{{ treeTotal().n.toLocaleString('fr-FR') }}</th>
              </tr>
            </tfoot>
          </table>
        </div>
        <p class="note">« Mouvements internes » (alimentations de caisse, encaissements des décomptes) est en gris,
          à la fin, et n'entre pas dans le total. Cliquez ▸ pour ouvrir une ligne, ▾ pour la fermer,
          et un nom de rubrique ou de projet pour voir ses lignes.</p>
      </section>
    }

    @if (tab() === 'recap') {
      <section class="ctl-card">
        <div class="head">
          <h2>Comparaison avec le « Récap des dépenses par projet » de l'Excel</h2>
          <button class="ctl-btn" type="button" (click)="exportRecap()">Exporter (Excel)</button>
        </div>
        <p class="note">HYDRA = total des débits de toutes les lignes du projet rangées sous la rubrique, tous comptes
          confondus. Écart = HYDRA − Récap Excel. Cliquez un écart en orange pour voir les lignes.</p>
        @if (!hasRecap()) {
          <div class="ctl-empty">Le récap Excel n'est pas dans le dernier import. Réimportez le fichier Excel.</div>
        } @else {
          <div class="tw">
            <table>
              <thead>
                <tr>
                  <th rowspan="2">Rubrique</th>
                  @for (p of shownProjects(); track p) { <th colspan="3" class="pj">{{ p }}</th> }
                </tr>
                <tr>
                  @for (p of shownProjects(); track p) {
                    <th class="num pj">Récap Excel</th><th class="num">HYDRA</th><th class="num">Écart</th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (row of recapRows(); track row.rubrique) {
                  <tr>
                    <td>{{ row.rubrique }}</td>
                    @for (c of row.cells; track c.proj) {
                      <td class="num pj">{{ m(c.recap) }}</td>
                      <td class="num">{{ m(c.hydra) }}</td>
                      <td class="num">
                        @if (c.diff) {
                          <button type="button" class="ecart" (click)="openEcart(row.rubrique, c.proj)"
                                  [attr.aria-label]="'Voir les lignes : ' + row.rubrique + ', ' + c.proj">{{ signed(c.ecart) }}</button>
                        } @else { <span class="muted">0</span> }
                      </td>
                    }
                  </tr>
                }
              </tbody>
              <tfoot>
                <tr>
                  <th>Total</th>
                  @for (c of recapTotals(); track c.proj) {
                    <th class="num pj">{{ m(c.recap) }}</th>
                    <th class="num">{{ m(c.hydra) }}</th>
                    <th class="num" [class.warn]="c.diff">{{ c.diff ? signed(c.ecart) : '0' }}</th>
                  }
                </tr>
              </tfoot>
            </table>
          </div>
        }
      </section>
    }

    <!-- Lines panel -->
    @for (pn of panelList(); track pn.key) {
      <div id="ctl-synth-lines">
        <div class="panel-head">
          @if (pn.note) { <p class="note">{{ pn.note }}</p> }
          <button class="ctl-btn" type="button" (click)="closePanel()">Fermer les lignes</button>
        </div>
        <ctl-lines-table [preset]="pn.preset" [title]="pn.title"
                         [highlight]="pn.highlight" [highlightLabel]="pn.highlightLabel ?? ''" />
      </div>
    }
  `,
  styles: [`
    h1 { font-size: 22px; margin: 0 0 4px; color: var(--ctl-ink, #16212b); letter-spacing: -.015em; }
    h2 { font-size: 15px; margin: 0; }
    h3 { font-size: 13px; margin: 6px 0 0; color: var(--ctl-muted, #58656f); font-weight: 600; }
    .lead { color: var(--ctl-muted, #58656f); max-width: 80ch; font-size: 14px; margin: 0; }
    .sel { display: flex; flex-wrap: wrap; gap: 10px 16px; align-items: center; }
    .seg { display: inline-flex; flex-wrap: wrap; border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: 999px; padding: 2px; background: var(--ctl-panel-2, #eef2f1); }
    .seg button { font: inherit; font-size: 13px; border: 0; background: none; color: var(--ctl-muted, #58656f);
      padding: 6px 14px; border-radius: 999px; cursor: pointer; }
    .seg button[aria-pressed="true"] { background: var(--ctl-panel, #fff); color: var(--ctl-ink, #16212b);
      font-weight: 600; box-shadow: 0 1px 2px rgba(16,33,43,.08); }
    .proj { display: inline-flex; gap: 8px; align-items: center; font-size: 13px; color: var(--ctl-muted, #58656f); }
    select { font: inherit; font-size: 13px; color: var(--ctl-ink, #16212b); background: var(--ctl-panel, #fff);
      border: 1px solid var(--ctl-line, #dbe1df); border-radius: 8px; padding: 6px 10px; }
    .tabs { display: flex; gap: 2px; border-bottom: 1px solid var(--ctl-line, #dbe1df); overflow-x: auto; }
    .tabs button { font: inherit; font-size: 14px; font-weight: 500; border: 0; background: none; cursor: pointer;
      color: var(--ctl-muted, #58656f); padding: 8px 12px; border-bottom: 2px solid transparent; white-space: nowrap; }
    .tabs button.active { color: var(--ctl-ink, #16212b); border-bottom-color: var(--ctl-accent, #0d5c6e); }
    .ctl-card { background: var(--ctl-panel, #fff); border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); padding: 16px; display: flex; flex-direction: column; gap: 10px; min-width: 0; }
    .head { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; justify-content: space-between; }
    .ctl-btn { font: inherit; font-size: 13px; font-weight: 600; border-radius: 8px; padding: 7px 14px;
      border: 1px solid var(--ctl-line, #dbe1df); background: var(--ctl-panel, #fff); color: var(--ctl-ink, #16212b);
      cursor: pointer; white-space: nowrap; }
    .cards { display: grid; gap: 10px; grid-template-columns: repeat(auto-fill, minmax(min(100%, 250px), 1fr)); }
    .acard { font: inherit; text-align: left; cursor: pointer; display: flex; flex-direction: column; gap: 8px;
      background: var(--ctl-panel, #fff); border: 1px solid var(--ctl-line, #dbe1df); border-radius: 10px;
      padding: 12px 14px; color: var(--ctl-ink, #16212b); }
    .acard:hover, .acard.on { border-color: var(--ctl-accent, #0d5c6e); background: var(--ctl-accent-soft, #e1eff2); }
    .acard .name { font-size: 14px; }
    dl { display: grid; grid-template-columns: auto 1fr; gap: 2px 10px; margin: 0; font-size: 12.5px; }
    dt { color: var(--ctl-muted, #58656f); }
    dd { margin: 0; text-align: right; font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums; }
    .pos { color: var(--ctl-ok, #1d7348); }
    .neg { color: var(--ctl-bad, #b42318); }
    .bar { display: flex; height: 6px; border-radius: 3px; overflow: hidden; background: var(--ctl-panel-2, #eef2f1); }
    .bar i { display: block; height: 100%; }
    .lv-f { background: #1d7348; } .lv-b { background: #4f9a74; } .lv-s { background: #9cc9ae; } .lv-n { background: #d9822b; }
    .legend { display: inline-flex; flex-wrap: wrap; gap: 4px 12px; margin-left: 8px; }
    .legend span { display: inline-flex; align-items: center; gap: 4px; }
    .legend i { display: inline-block; width: 10px; height: 10px; border-radius: 2px; }
    .note { font-size: 12.5px; color: var(--ctl-muted, #58656f); margin: 4px 0 0; }
    .muted { color: var(--ctl-muted, #58656f); }
    .ctl-empty { padding: 24px; text-align: center; color: var(--ctl-muted, #58656f); }
    .panel-head { display: flex; flex-wrap: wrap; gap: 8px 16px; align-items: flex-start; justify-content: flex-end; margin-bottom: 8px; }
    .panel-head .note { flex: 1 1 320px; margin: 0; }
    th.pj, td.pj { border-left: 1px solid var(--ctl-line, #dbe1df); }
    .ecart { font: inherit; font-family: var(--ctl-font-num); border: 0; border-radius: 6px; padding: 1px 6px; cursor: pointer;
      background: var(--ctl-warn-soft, #fbf0dc); color: var(--ctl-warn, #9a5b00); font-weight: 600; }
    th.warn { color: var(--ctl-warn, #9a5b00); }
    .tw { overflow-x: auto; border: 1px solid var(--ctl-line, #dbe1df); border-radius: var(--ctl-radius, 10px); }
    table { border-collapse: collapse; width: 100%; font-size: 13px; }
    th { text-align: left; font-weight: 600; color: var(--ctl-muted, #58656f); font-size: 12px; padding: 8px 10px;
      border-bottom: 1px solid var(--ctl-line, #dbe1df); background: var(--ctl-panel-2, #eef2f1); white-space: nowrap; }
    td { padding: 6px 10px; border-bottom: 1px solid var(--ctl-line, #dbe1df); vertical-align: top; }
    th.num, td.num { text-align: right; font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums; white-space: nowrap; }
    tfoot th { border-bottom: 0; border-top: 2px solid var(--ctl-line, #dbe1df); color: var(--ctl-ink, #16212b); }
    tr.lv1 td { font-weight: 600; background: var(--ctl-panel-2, #eef2f1); }
    tr.lv2 td:first-child { padding-left: 26px; }
    tr.lv3 td:first-child { padding-left: 52px; }
    tr.internal td { color: var(--ctl-muted, #58656f); opacity: .7; }
    .tog { font: inherit; font-weight: inherit; border: 0; background: none; padding: 0 4px 0 0; cursor: pointer; color: inherit; }
    .link { font: inherit; border: 0; background: none; padding: 0; cursor: pointer; text-align: left;
      color: var(--ctl-accent, #0d5c6e); text-decoration: underline; text-underline-offset: 2px; }
  `]
})
export class ControleSyntheseComponent {
  protected store = inject(ControleStore);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  protected GROUPEMENTS = GROUPEMENTS;
  protected LEVELS = FACTURE_LEVELS;
  protected LEVEL_CLASS = LEVEL_CLASS;
  protected pct = pct;

  // ── Selection (from the URL) ─────────────────────────────────────────────
  g = signal<GroupementKey>(DEFAULT_GROUPEMENT);
  p = signal('');
  tab = signal<Tab>('synthese');
  panel = signal<Panel | null>(null);
  /** The panel as a 0/1 list: a new key re-creates the lines table with fresh filters. */
  panelList = computed(() => { const pn = this.panel(); return pn ? [pn] : []; });

  constructor() {
    this.route.queryParamMap.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe(q => {
      this.g.set(groupementOf(q.get('g')).key);
      this.p.set(q.get('p') ?? '');
      this.tab.set(q.get('tab') === 'recap' ? 'recap' : 'synthese');
    });
  }

  private go(params: { g?: string; p?: string | null; tab?: Tab }): void {
    const next = { g: this.g(), p: this.p() || null, tab: this.tab(), ...params };
    this.panel.set(null);
    this.router.navigate([], {
      relativeTo: this.route, replaceUrl: true,
      queryParams: { g: next.g, p: next.p || null, tab: next.tab === 'recap' ? 'recap' : null },
    });
  }

  setGroupement(key: GroupementKey): void { this.go({ g: key, p: null }); }
  setProject(p: string): void { this.go({ p: p || null }); }
  setTab(t: Tab): void { this.go({ tab: t }); }

  // ── Projects ─────────────────────────────────────────────────────────────
  private allProjects = computed(() => {
    const d = this.store.data();
    return [...(d?.lines ?? []).map(l => l.proj), ...(d?.recap ?? []).map(r => r.proj)];
  });
  projects = computed(() => projectsOf(this.g(), this.allProjects()));
  /** Projects shown: the selected one (if it belongs to the groupement), else all of the groupement. */
  shownProjects = computed(() => {
    const p = this.p();
    return p && this.projects().includes(p) ? [p] : this.projects();
  });
  private shownSet = computed(() => new Set(this.shownProjects()));
  selLines = computed(() => {
    const s = this.shownSet();
    return (this.store.data()?.lines ?? []).filter(l => s.has(l.proj));
  });
  private selLabel = computed(() => {
    const p = this.p();
    return p && this.projects().includes(p) ? p : groupementOf(this.g()).label;
  });

  // ── Totals ───────────────────────────────────────────────────────────────
  totalsOf(rows: CtlLine[]): SynthTotals {
    const t: SynthTotals = { e: 0, d: 0, fact: 0, nonFact: 0, alimIn: 0, alimOut: 0, n: 0,
      byLevel: { 'Facture': 0, 'Bon': 0, 'Bon signé (S)': 0, 'Non facturée': 0 } };
    for (const l of rows) {
      const out = this.store.outAmt(l);
      t.e += this.store.inAmt(l);
      t.d += out;
      const lv = factureLevel(l);
      t.byLevel[lv] += out;
      if (isFacturee(lv)) t.fact += out; else t.nonFact += out;
      if (this.store.isTransfer(l)) { t.alimIn += l.e; t.alimOut += l.d; }
      t.n++;
    }
    return t;
  }

  sourceGroups = computed<SourceGroup[]>(() => {
    const byAcct = new Map<string, CtlLine[]>();
    for (const l of this.selLines()) {
      const list = byAcct.get(l.acct) ?? [];
      list.push(l);
      byAcct.set(l.acct, list);
    }
    const groups = new Map<string, Card[]>();
    for (const a of this.store.data()?.accounts ?? []) {
      const rows = byAcct.get(a.id);
      if (!rows) continue;
      const src = sourceOf(a.id);
      const list = groups.get(src) ?? [];
      list.push({ acct: a, t: this.totalsOf(rows) });
      groups.set(src, list);
    }
    return SOURCES.filter(s => groups.has(s)).map(s => ({ source: s, cards: groups.get(s)! }));
  });

  // ── Dépenses par famille ─────────────────────────────────────────────────
  private openKeys = signal<Set<string>>(new Set());

  toggle(key: string): void {
    this.openKeys.update(s => {
      const n = new Set(s);
      if (n.has(key)) n.delete(key); else n.add(key);
      return n;
    });
  }

  private famNodes = computed<FamNode[]>(() => {
    const fam = new Map<string, Map<string, Map<string, CtlLine[]>>>();
    for (const l of this.selLines()) {
      const f = famOf(l), r = rubOf(l);
      if (!fam.has(f)) fam.set(f, new Map());
      const rubs = fam.get(f)!;
      if (!rubs.has(r)) rubs.set(r, new Map());
      const projs = rubs.get(r)!;
      const list = projs.get(l.proj) ?? [];
      list.push(l);
      projs.set(l.proj, list);
    }
    const order = (f: string) => {
      if (f === MOUVEMENTS_INTERNES) return 999;
      const i = FAMILLE_ORDER.indexOf(f);
      return i < 0 ? 900 : i;
    };
    return [...fam.entries()]
      .sort((a, b) => order(a[0]) - order(b[0]) || a[0].localeCompare(b[0], 'fr'))
      .map(([famille, rubs]) => {
        const famLines: CtlLine[] = [];
        const rubNodes: RubNode[] = [...rubs.entries()].map(([rubrique, projs]) => {
          const rubLines = [...projs.values()].flat();
          famLines.push(...rubLines);
          const projNodes = [...projs.entries()]
            .map(([proj, rows]) => ({ proj, t: this.totalsOf(rows) }))
            .sort((a, b) => b.t.d - a.t.d || a.proj.localeCompare(b.proj, 'fr'));
          return { rubrique, t: this.totalsOf(rubLines), projs: projNodes };
        }).sort((a, b) => b.t.d - a.t.d || a.rubrique.localeCompare(b.rubrique, 'fr'));
        return { famille, t: this.totalsOf(famLines), rubs: rubNodes };
      });
  });

  treeRows = computed<TreeRow[]>(() => {
    const open = this.openKeys();
    const rows: TreeRow[] = [];
    for (const f of this.famNodes()) {
      const fk = 'f:' + f.famille;
      const internal = f.famille === MOUVEMENTS_INTERNES;
      rows.push({ level: 1, key: fk, label: f.famille, famille: f.famille, rubrique: '', proj: '', t: f.t,
        expandable: true, open: open.has(fk), internal });
      if (!open.has(fk)) continue;
      const perProj = FAMILLES_PAR_PROJET.has(f.famille);
      for (const r of f.rubs) {
        const rk = 'r:' + f.famille + '|' + r.rubrique;
        rows.push({ level: 2, key: rk, label: r.rubrique, famille: f.famille, rubrique: r.rubrique, proj: '', t: r.t,
          expandable: perProj, open: open.has(rk), internal });
        if (!perProj || !open.has(rk)) continue;
        for (const p of r.projs) {
          rows.push({ level: 3, key: rk + '|' + p.proj, label: p.proj, famille: f.famille, rubrique: r.rubrique,
            proj: p.proj, t: p.t, expandable: false, open: false, internal });
        }
      }
    }
    return rows;
  });

  /** Totals row: Mouvements internes left out. */
  treeTotal = computed(() => this.totalsOf(this.selLines().filter(l => famOf(l) !== MOUVEMENTS_INTERNES)));

  openRubrique(r: TreeRow): void {
    const s = this.shownSet();
    const { famille, rubrique, proj } = r;
    this.openPanel({
      key: 'rub:' + r.key,
      title: `Lignes — ${rubrique} — ${proj || this.selLabel()}`,
      preset: l => famOf(l) === famille && rubOf(l) === rubrique && (proj ? l.proj === proj : s.has(l.proj)),
    });
  }

  exportTree(): void {
    const rows: unknown[][] = [];
    const add = (niveau: string, f: string, r: string, p: string, t: SynthTotals) =>
      rows.push([niveau, f, r, p, csvAmount(t.e), csvAmount(t.d), csvAmount(t.fact), csvAmount(t.nonFact), t.n]);
    for (const f of this.famNodes()) {
      add('Famille', f.famille, '', '', f.t);
      for (const r of f.rubs) {
        add('Rubrique', f.famille, r.rubrique, '', r.t);
        if (FAMILLES_PAR_PROJET.has(f.famille)) {
          for (const p of r.projs) add('Projet', f.famille, r.rubrique, p.proj, p.t);
        }
      }
    }
    add('Total (sans les mouvements internes)', '', '', '', this.treeTotal());
    downloadCsv('synthese-familles-' + this.selLabel(),
      ['Niveau', 'Famille', 'Rubrique', 'Projet', 'Entrées', 'Sorties', 'Facturée', 'Non facturée', 'Lignes'], rows);
  }

  // ── Comparaison avec le Récap Excel ──────────────────────────────────────
  hasRecap = computed(() => (this.store.data()?.recap ?? []).length > 0);

  recapRows = computed<RecapRow[]>(() => {
    const projs = this.shownProjects();
    const s = this.shownSet();
    const recap = new Map<string, number>();
    for (const r of this.store.data()?.recap ?? []) {
      if (!s.has(r.proj)) continue;
      const k = r.proj + '|' + r.rubrique;
      recap.set(k, (recap.get(k) ?? 0) + r.amount);
    }
    const hydra = new Map<string, number>();
    for (const l of this.selLines()) {
      const k = l.proj + '|' + l.rubrique;
      hydra.set(k, (hydra.get(k) ?? 0) + l.d);
    }
    return RECAP_RUBRIQUES.map(rubrique => ({
      rubrique,
      cells: projs.map(proj => {
        const rc = recap.get(proj + '|' + rubrique) ?? 0;
        const hy = hydra.get(proj + '|' + rubrique) ?? 0;
        return { proj, recap: rc, hydra: hy, ecart: hy - rc, diff: isDiff(hy - rc) };
      }),
    })).filter(row => row.cells.some(c => c.recap || c.hydra));
  });

  recapTotals = computed<RecapCell[]>(() => this.shownProjects().map((proj, i) => {
    let rc = 0, hy = 0;
    for (const row of this.recapRows()) { rc += row.cells[i].recap; hy += row.cells[i].hydra; }
    return { proj, recap: rc, hydra: hy, ecart: hy - rc, diff: isDiff(hy - rc) };
  }));

  openEcart(rubrique: string, proj: string): void {
    const name = rubrique.toLowerCase();
    this.openPanel({
      key: 'recap:' + proj + '|' + rubrique,
      title: `Lignes — ${rubrique} — ${proj}`,
      preset: l => l.proj === proj && l.rubrique === rubrique,
      highlight: l => (l.cat ?? '').trim().toLowerCase() !== name,
      highlightLabel: "non compté par l'Excel",
      note: "L'Excel additionne seulement les lignes dont le texte est exactement le nom de la rubrique : "
        + "les lignes surlignées ont un autre texte. Un texte avec un espace en trop n'est pas compté non plus "
        + "par l'Excel, mais HYDRA ne voit pas encore ces espaces : ces lignes ne sont pas surlignées.",
    });
  }

  /** Écart with its sign. */
  signed(v: number): string { return (v > 0 ? '+' : '') + money(v); }

  exportRecap(): void {
    const projs = this.shownProjects();
    const head = ['Rubrique', ...projs.flatMap(p => [p + ' — Récap Excel', p + ' — HYDRA', p + ' — Écart'])];
    const cells = (cs: RecapCell[]) => cs.flatMap(c => [csvAmount(c.recap), csvAmount(c.hydra), csvAmount(c.ecart)]);
    const rows: unknown[][] = this.recapRows().map(r => [r.rubrique, ...cells(r.cells)]);
    rows.push(['Total', ...cells(this.recapTotals())]);
    downloadCsv('synthese-recap-excel-' + this.selLabel(), head, rows);
  }

  // ── Lines panel ──────────────────────────────────────────────────────────
  private openPanel(pn: Panel): void {
    this.panel.set(pn);
    setTimeout(() => document.getElementById('ctl-synth-lines')?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
  }

  closePanel(): void { this.panel.set(null); }

  openAccount(a: CtlAccount): void {
    const s = this.shownSet();
    this.openPanel({
      key: 'acct:' + a.id,
      title: `Lignes — ${a.name} — ${this.selLabel()}`,
      preset: l => l.acct === a.id && s.has(l.proj),
    });
  }

  // ── Format and export ────────────────────────────────────────────────────
  m(v: number): string { return money(v) || '—'; }

  exportCards(): void {
    const rows: unknown[][] = [];
    for (const sg of this.sourceGroups()) {
      for (const c of sg.cards) {
        rows.push([sg.source, c.acct.name, csvAmount(c.t.e), csvAmount(c.t.d), csvAmount(c.t.alimIn),
          csvAmount(c.t.alimOut), csvAmount(c.t.fact), csvAmount(c.t.nonFact),
          c.t.d ? (100 * c.t.fact / c.t.d).toFixed(1).replace('.', ',') : '',
          ...FACTURE_LEVELS.map(lv => csvAmount(c.t.byLevel[lv])), c.t.n]);
      }
    }
    downloadCsv('synthese-comptes-' + this.selLabel(),
      ['Source', 'Compte', 'Entrées', 'Sorties', 'Alimentations reçues', 'Alimentations envoyées',
        'Facturée', 'Non facturée', '% facturée', ...FACTURE_LEVELS.map(lv => 'Sorties — ' + lv), 'Lignes'],
      rows);
  }
}
