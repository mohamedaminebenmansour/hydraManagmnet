import { Component, Input, OnInit, computed, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { ControleStore } from './controle.store';
import { ControleDrawerService } from './controle-drawer.service';
import { CtlLine } from '@app/core/services/controle.service';
import {
  STATUS, STATUS_LABEL, FLAGS, money, pct, dfmt, csvAmount, downloadCsv,
} from './controle-format';

interface FilterState {
  q: string;
  sens: string;
  mode: string;
  poste: string;
  statut: string;
  flag: string;
  acct: string;
  proj: string;
  party: string;
  article: string;
  piece: string;
  shown: number;
}

const SENS_LABELS: Record<string, string> = {
  in: 'Entrées seulement',
  out: 'Sorties seulement',
  tr: 'Transferts',
};

/**
 * LinesTableComponent — standalone, reusable. Port of linesTable() from the
 * prototype. A computed() of the store signals + filter signals drives the
 * rows, so a saved justification updates the pill without a redraw call.
 */
@Component({
  selector: 'ctl-lines-table',
  standalone: true,
  imports: [FormsModule, ButtonModule],
  template: `
    <div class="ctl-card">
      @if (title) {
        <h2>{{ title }}</h2>
      }

      <!-- Filters -->
      <div class="ctl-filters">
        <input type="search" placeholder="Chercher : nom, désignation, montant, n° de bon…"
               aria-label="Chercher dans les lignes"
               [ngModel]="filters().q" (ngModelChange)="onSearch($event)" />
        <select [ngModel]="filters().sens" (ngModelChange)="onFilter('sens', $event)" aria-label="Entrées et sorties">
          <option value="">Entrées et sorties</option>
          <option value="in">Entrées seulement</option>
          <option value="out">Sorties seulement</option>
          <option value="tr">Transferts</option>
        </select>
        @if (!hideAcct) {
          <select [ngModel]="filters().acct" (ngModelChange)="onFilter('acct', $event)" aria-label="Tous les comptes">
            <option value="">Tous les comptes</option>
            @for (x of acctOptions(); track x) {
              <option [value]="x">{{ acctName(x) }}</option>
            }
          </select>
        }
        @if (!hideProj) {
          <select [ngModel]="filters().proj" (ngModelChange)="onFilter('proj', $event)" aria-label="Tous les projets">
            <option value="">Tous les projets</option>
            @for (x of projOptions(); track x) {
              <option [value]="x">{{ x }}</option>
            }
          </select>
        }
        <select [ngModel]="filters().mode" (ngModelChange)="onFilter('mode', $event)" aria-label="Tous les paiements">
          <option value="">Tous les paiements</option>
          @for (x of modeOptions(); track x) {
            <option [value]="x">{{ x }}</option>
          }
        </select>
        <select [ngModel]="filters().poste" (ngModelChange)="onFilter('poste', $event)" aria-label="Toutes les catégories">
          <option value="">Toutes les catégories</option>
          @for (x of posteOptions(); track x) {
            <option [value]="x">{{ x }}</option>
          }
        </select>
        <select [ngModel]="filters().party" (ngModelChange)="onFilter('party', $event)" aria-label="Tous les sous-traitants">
          <option value="">Tous les sous-traitants</option>
          @for (x of partyOptions(); track x) {
            <option [value]="x">{{ x }}</option>
          }
        </select>
        <select [ngModel]="filters().article" (ngModelChange)="onFilter('article', $event)" aria-label="Tous les articles">
          <option value="">Tous les articles</option>
          @for (x of articleOptions(); track x) {
            <option [value]="x">{{ x }}</option>
          }
        </select>
        <select [ngModel]="filters().piece" (ngModelChange)="onFilter('piece', $event)" aria-label="Toutes les pièces">
          <option value="">Toutes les pièces</option>
          @for (x of pieceOptions(); track x) {
            <option [value]="x">{{ x }}</option>
          }
        </select>
        <select [ngModel]="filters().statut" (ngModelChange)="onFilter('statut', $event)" aria-label="Tous les justificatifs">
          <option value="">Tous les justificatifs</option>
          @for (s of STATUS; track s[0]) {
            <option [value]="s[0]">{{ s[1] }}</option>
          }
        </select>
        <select [ngModel]="filters().flag" (ngModelChange)="onFilter('flag', $event)" aria-label="Toutes les alertes">
          <option value="">Toutes les alertes</option>
          <option value="any">Avec une alerte</option>
          @for (f of flagKeys(); track f) {
            <option [value]="f">{{ FLAGS[f][0] }}</option>
          }
        </select>
        <button class="ctl-btn" type="button" (click)="exportCsv()">Exporter (Excel)</button>
      </div>

      <!-- Summary bar -->
      <div class="ctl-sumbar">
        <span><b>{{ summary().n.toLocaleString('fr-FR') }}</b> lignes</span>
        <span>Entrées <b class="pos">{{ money(summary().e) }}</b></span>
        <span>Sorties <b class="neg">{{ money(summary().d) }}</b></span>
        <span>Sorties justifiées <b>{{ summary().d ? pct(summary().dj / summary().d) : '—' }}</b></span>
      </div>

      <!-- Table -->
      @if (summary().n === 0) {
        <div class="ctl-empty">Aucune ligne ne correspond à ces filtres.</div>
      } @else {
        <div class="ctl-tw">
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Compte</th>
                @if (!hideProj) { <th>Projet</th> }
                <th>Désignation</th>
                <th>Article</th>
                <th>Pièce</th>
                <th>Paiement</th>
                <th class="num">Entrée</th>
                <th class="num">Sortie</th>
                <th>Justificatif</th>
                <th>Feuille · ligne</th>
              </tr>
            </thead>
            <tbody>
              @for (l of visibleRows(); track l.id) {
                <tr class="click" tabindex="0" [class.hl]="isHighlighted(l)"
                    (click)="openLine(l.id)"
                    (keydown)="onRowKey($event, l.id)">
                  <td class="num left">{{ dfmt(l.date) }}</td>
                  <td>{{ acctName(l.acct) }}</td>
                  @if (!hideProj) { <td>{{ l.proj }}</td> }
                  <td class="desc">
                    {{ l.desig || '—' }}
                    <div class="sub">
                      @if (l.poste) { <span class="chip acc">{{ l.poste }}</span> }
                      @if (l.party) { <span class="chip">{{ l.party }}</span> }
                      @if (store.isTransfer(l)) { <span class="chip">Transfert</span> }
                      @for (f of l.flags; track f) {
                        <span class="chip flag" [title]="FLAGS[f]?.[1] || f">{{ FLAGS[f]?.[0] || f }}</span>
                      }
                      @if (isHighlighted(l)) { <span class="chip red">{{ highlightLabel }}</span> }
                    </div>
                  </td>
                  <td>{{ l.article || '—' }}</td>
                  <td class="nowrap">{{ l.piece || '—' }}</td>
                  <td>{{ l.mode }}</td>
                  <td class="num pos">{{ money(l.e) }}</td>
                  <td class="num neg">{{ money(l.d) }}</td>
                  <td>
                    <span class="pill s-{{ store.jStatus(l.id) }}">{{ STATUS_LABEL[store.jStatus(l.id)] }}</span>
                  </td>
                  <td class="muted nowrap">{{ l.sheet }} · L{{ l.row }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }

      <!-- Show more -->
      @if (summary().n > filters().shown) {
        <div class="ctl-more">
          <button class="ctl-btn" type="button" (click)="showMore()">
            Afficher plus ({{ (summary().n - filters().shown).toLocaleString('fr-FR') }} restantes)
          </button>
        </div>
      }
    </div>
  `,
  styles: [`
    .ctl-card {
      background: var(--ctl-panel, #fff);
      border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px);
      padding: 16px; display: flex; flex-direction: column; gap: 12px; min-width: 0;
    }
    h2 { font-size: 15px; margin: 0; }
    .ctl-filters { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
    .ctl-filters input, .ctl-filters select {
      font: inherit; font-size: 13px; color: var(--ctl-ink, #16212b);
      background: var(--ctl-panel, #fff); border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: 8px; padding: 7px 10px; min-width: 0;
    }
    .ctl-filters input[type="search"] { flex: 1 1 220px; }
    .ctl-btn {
      font: inherit; font-size: 13px; font-weight: 600; border-radius: 8px;
      padding: 7px 14px; border: 1px solid var(--ctl-line, #dbe1df);
      background: var(--ctl-panel, #fff); color: var(--ctl-ink, #16212b); cursor: pointer;
    }
    .ctl-sumbar {
      display: flex; flex-wrap: wrap; gap: 6px 18px; font-size: 13px;
      color: var(--ctl-muted, #58656f); align-items: baseline;
    }
    .ctl-sumbar b { color: var(--ctl-ink, #16212b); font-family: var(--ctl-font-num); font-weight: 500; }
    .pos { color: var(--ctl-ok, #1d7348); }
    .neg { color: var(--ctl-bad, #b42318); }
    .ctl-tw {
      overflow-x: auto; border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); background: var(--ctl-panel, #fff);
    }
    table { border-collapse: collapse; width: 100%; font-size: 13px; }
    th {
      text-align: left; font-weight: 600; color: var(--ctl-muted, #58656f);
      font-size: 12px; padding: 8px 10px; border-bottom: 1px solid var(--ctl-line, #dbe1df);
      background: var(--ctl-panel-2, #eef2f1); position: sticky; top: 0; white-space: nowrap;
    }
    th.num, td.num {
      font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums;
      text-align: right; white-space: nowrap;
    }
    td.num.left { text-align: left; }
    td { padding: 8px 10px; border-bottom: 1px solid var(--ctl-line, #dbe1df); vertical-align: top; }
    tr:last-child td { border-bottom: 0; }
    tbody tr.click { cursor: pointer; }
    tbody tr.click:hover td { background: var(--ctl-accent-soft, #e1eff2); }
    tbody tr.hl td { background: var(--ctl-warn-soft, #fbf0dc); }
    .desc { min-width: 220px; max-width: 460px; }
    .desc .sub { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 3px; }
    .chip {
      display: inline-flex; align-items: center; gap: 4px; font-size: 11.5px;
      padding: 1px 7px; border-radius: 999px; background: var(--ctl-panel-2, #eef2f1);
      color: var(--ctl-muted, #58656f); border: 1px solid var(--ctl-line, #dbe1df); white-space: nowrap;
    }
    .chip.acc { background: var(--ctl-accent-soft, #e1eff2); color: var(--ctl-accent, #0d5c6e); border-color: transparent; }
    .chip.flag { background: var(--ctl-warn-soft, #fbf0dc); color: var(--ctl-warn, #9a5b00); border-color: transparent; }
    .chip.red { background: var(--ctl-bad-soft, #fbe7e5); color: var(--ctl-bad, #b42318); border-color: transparent; }
    .pill {
      display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600;
      padding: 2px 9px; border-radius: 999px; white-space: nowrap;
    }
    .pill::before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
    .s-none { background: var(--ctl-panel-2, #eef2f1); color: var(--ctl-muted, #58656f); }
    .s-justifie, .s-valide { background: var(--ctl-ok-soft, #e3f3ea); color: var(--ctl-ok, #1d7348); }
    .s-partiel, .s-a-revoir { background: var(--ctl-warn-soft, #fbf0dc); color: var(--ctl-warn, #9a5b00); }
    .s-non-justifie { background: var(--ctl-bad-soft, #fbe7e5); color: var(--ctl-bad, #b42318); }
    .s-conteste { background: var(--ctl-vio-soft, #eeeafb); color: var(--ctl-vio, #5b3fb5); }
    .muted { color: var(--ctl-muted, #58656f); }
    .nowrap { white-space: nowrap; }
    .ctl-empty { padding: 24px; text-align: center; color: var(--ctl-muted, #58656f); }
    .ctl-more { display: flex; justify-content: center; padding: 10px; }

    @media (max-width: 400px) {
      .ctl-filters { flex-direction: column; align-items: stretch; }
      .ctl-filters input, .ctl-filters select { width: 100%; }
      .desc { min-width: 140px; max-width: none; }
    }
  `]
})
export class LinesTableComponent implements OnInit {
  protected store = inject(ControleStore);
  private drawer = inject(ControleDrawerService);

  // Exposed to template
  protected STATUS = STATUS;
  protected STATUS_LABEL = STATUS_LABEL;
  protected FLAGS = FLAGS;
  protected money = money;
  protected pct = pct;
  protected dfmt = dfmt;

  // ── Inputs ──────────────────────────────────────────────────────────────
  preset = input.required<(l: CtlLine) => boolean>();
  @Input() title = '';
  @Input() hideAcct = false;
  @Input() hideProj = false;
  @Input() sort?: (a: CtlLine, b: CtlLine) => number;
  /** Optional: lines to highlight (row colour + chip {@link highlightLabel}, « Alerte » column in the export). */
  @Input() highlight?: (l: CtlLine) => boolean;
  @Input() highlightLabel = '';

  isHighlighted(l: CtlLine): boolean {
    return !!this.highlight && this.highlight(l);
  }

  private _initialFiltersValue: Partial<FilterState> = {};
  @Input()
  set initialFilters(value: Partial<FilterState> | undefined) {
    if (value) this._initialFiltersValue = value;
  }

  // ── Filter signals ──────────────────────────────────────────────────────
  protected filters = signal<FilterState>({
    q: '', sens: '', mode: '', poste: '', statut: '', flag: '',
    acct: '', proj: '', party: '', article: '', piece: '', shown: 150,
  });

  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  ngOnInit(): void {
    // Apply initial filters if any were set before init
    if (Object.keys(this._initialFiltersValue).length > 0) {
      this.filters.update((f: FilterState) => ({ ...f, ...this._initialFiltersValue, shown: 150 }));
    }
  }

  // ── Filter options (unique values from the pool) ─────────────────────────
  private pool = computed(() => {
    const data = this.store.data();
    if (!data) return [] as CtlLine[];
    return data.lines.filter(l => this.preset()(l));
  });

  private uniqValues = (fn: (l: CtlLine) => string | null | undefined): string[] => {
    const set = new Set<string>();
    for (const l of this.pool()) {
      const v = fn(l);
      if (v) set.add(v);
    }
    return [...set].sort((a, b) => a.localeCompare(b, 'fr'));
  };

  acctOptions = computed(() => this.uniqValues(l => l.acct));
  projOptions = computed(() => this.uniqValues(l => l.proj));
  modeOptions = computed(() => this.uniqValues(l => l.mode));
  posteOptions = computed(() => this.uniqValues(l => l.poste));
  partyOptions = computed(() => this.uniqValues(l => l.party));
  articleOptions = computed(() => this.uniqValues(l => l.article));
  pieceOptions = computed(() => this.uniqValues(l => l.piece));
  flagKeys = computed(() => Object.keys(FLAGS).filter(k => k !== 'non-retrouve'));

  // ── Filtered + sorted rows ───────────────────────────────────────────────
  private filteredRows = computed(() => {
    const st = this.filters();
    const pool = this.pool();
    return pool.filter(l => {
      if (st.sens === 'in' && !(this.store.inAmt(l) > 0)) return false;
      if (st.sens === 'out' && !(this.store.outAmt(l) > 0)) return false;
      if (st.sens === 'tr' && !this.store.isTransfer(l)) return false;
      if (st.acct && l.acct !== st.acct) return false;
      if (st.proj && l.proj !== st.proj) return false;
      if (st.mode && l.mode !== st.mode) return false;
      if (st.poste && l.poste !== st.poste) return false;
      if (st.party && l.party !== st.party) return false;
      if (st.article && l.article !== st.article) return false;
      if (st.piece && l.piece !== st.piece) return false;
      if (st.statut && this.store.jStatus(l.id) !== st.statut) return false;
      if (st.flag === 'any' && !l.flags.length) return false;
      if (st.flag && st.flag !== 'any' && !l.flags.includes(st.flag)) return false;
      if (st.q) {
        const j = this.store.jOf(l.id);
        const hay = (
          l.desig + ' ' + l.cat + ' ' + l.party + ' ' + l.poste + ' ' + l.article + ' ' +
          l.bon + ' ' + l.fct + ' ' + l.sheet + ' ' + l.row + ' ' +
          money(l.e) + ' ' + money(l.d) + ' ' +
          (j ? (j.reference ?? '') + ' ' + (j.note ?? '') : '')
        ).toLowerCase();
        if (!hay.includes(st.q) && !hay.replace(/\s/g, '').includes(st.q.replace(/\s/g, ''))) return false;
      }
      return true;
    }).sort(this.sort || ((a, b) =>
      (b.date ?? '').localeCompare(a.date ?? '') || a.row - b.row));
  });

  visibleRows = computed(() => this.filteredRows().slice(0, this.filters().shown));

  summary = computed(() => {
    const rows = this.filteredRows();
    let e = 0, d = 0, dj = 0;
    for (const l of rows) {
      e += this.store.inAmt(l);
      d += this.store.outAmt(l);
      if (this.store.jStatus(l.id) === 'justifie') dj += this.store.outAmt(l);
    }
    return { e, d, dj, n: rows.length };
  });

  // ── Filter change handlers ───────────────────────────────────────────────
  onSearch(value: string): void {
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => {
      this.filters.update(f => ({ ...f, q: value.trim().toLowerCase(), shown: 150 }));
    }, 180);
  }

  onFilter(key: keyof FilterState, value: string): void {
    this.filters.update(f => ({ ...f, [key]: value, shown: 150 }));
  }

  showMore(): void {
    this.filters.update((f: FilterState) => ({ ...f, shown: f.shown + 300 }));
  }

  // ── Row click ────────────────────────────────────────────────────────────
  openLine(id: string): void {
    this.drawer.open(id);
  }

  onRowKey(ev: KeyboardEvent, id: string): void {
    if (ev.key === 'Enter') this.openLine(id);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────
  acctName(id: string): string {
    return this.store.acctById().get(id)?.name ?? 'Sans compte';
  }

  // ── Export CSV for French Excel (Phase 3 replaces it with a real .xlsx) ───
  exportCsv(): void {
    const head = [
      'Date', 'Compte', 'Projet', 'Désignation', 'Catégorie', 'Sous-traitant',
      'Article', 'Pièce', 'Paiement', 'Entrée', 'Sortie', 'Bon', 'Facture (Excel)', 'Feuille',
      'Ligne Excel', 'Alertes', 'Justificatif', 'Type de document', 'Référence',
      'Date document', 'Montant document', 'Note',
    ];
    const rows = this.filteredRows().map(l => {
      const j = this.store.jOf(l.id);
      return [
        dfmt(l.date), this.acctName(l.acct), l.proj, l.desig, l.poste, l.party,
        l.article, l.piece, l.mode, csvAmount(l.e), csvAmount(l.d), l.bon, l.fct, l.sheet, l.row,
        [...l.flags.map(f => FLAGS[f]?.[0] || f), ...(this.isHighlighted(l) ? [this.highlightLabel] : [])].join(', '),
        STATUS_LABEL[j?.statut ?? 'none'],
        j?.docType ?? '', j?.reference ?? '',
        j?.docDate ? dfmt(j.docDate) : '',
        csvAmount(j?.docAmount), j?.note ?? '',
      ];
    });
    downloadCsv(this.title || 'lignes', head, rows);
  }
}
