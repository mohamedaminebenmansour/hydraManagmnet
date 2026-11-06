import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { HttpErrorResponse } from '@angular/common/http';
import { ControleService, CtlArticleRow } from '@app/core/services/controle.service';
import { ControleStore } from './controle.store';
import { downloadCsv } from './controle-format';

/**
 * ControleArticlesComponent — « Regroupement des articles ».
 *
 * One row per raw Excel text (raw_key): the gérant types the article and saves.
 * The server updates every line with that text in the same transaction. The
 * store is reloaded when the page is left, if something was saved.
 */
@Component({
  selector: 'app-controle-articles',
  standalone: true,
  imports: [FormsModule],
  template: `
    <div>
      <h1>Regroupement des articles</h1>
      <p class="lead">Chaque texte de la colonne « Destination / Sous-traitant » de l'Excel est rangé sous un article.
        Corrigez l'article d'un texte : toutes ses lignes changent, sans réimporter. Le prochain import garde vos choix.</p>
    </div>

    <div class="ctl-card">
      <div class="ctl-filters">
        <input type="search" placeholder="Chercher : texte de l'Excel ou article…"
               aria-label="Chercher un texte ou un article"
               [ngModel]="q()" (ngModelChange)="onSearch($event)" />
        <button class="ctl-btn" type="button" (click)="exportCsv()">Exporter (Excel)</button>
      </div>

      <div class="ctl-sumbar">
        <span><b>{{ filtered().length.toLocaleString('fr-FR') }}</b> textes</span>
        <span><b>{{ filteredLines().toLocaleString('fr-FR') }}</b> lignes</span>
      </div>

      @if (loading()) {
        <div class="ctl-empty">Chargement…</div>
      } @else if (loadError()) {
        <div class="ctl-empty">
          Impossible de charger les articles.
          <button class="ctl-btn" type="button" (click)="load()">Réessayer</button>
        </div>
      } @else if (filtered().length === 0) {
        <div class="ctl-empty">Aucun texte ne correspond à cette recherche.</div>
      } @else {
        <div class="ctl-tw">
          <table>
            <thead>
              <tr>
                <th>Texte dans l'Excel</th>
                <th class="num">Nombre de lignes</th>
                <th>Article</th>
                <th><span class="sr-only">Action</span></th>
              </tr>
            </thead>
            <tbody>
              @for (r of visible(); track r.rawKey) {
                <tr>
                  <td>{{ r.sampleText || '(vide)' }}</td>
                  <td class="num">{{ r.lineCount.toLocaleString('fr-FR') }}</td>
                  <td>
                    @if (r.rawKey) {
                      <input type="text" maxlength="80" [attr.aria-label]="'Article pour ' + (r.sampleText || r.rawKey)"
                             [ngModel]="draftOf(r)" (ngModelChange)="setDraft(r.rawKey, $event)" />
                    } @else {
                      <span class="muted">{{ r.article }} — à préciser ligne par ligne</span>
                    }
                    @if (rowError()[r.rawKey]; as err) {
                      <div class="err" role="alert">{{ err }}</div>
                    }
                  </td>
                  <td>
                    @if (r.rawKey) {
                      <button class="ctl-btn" type="button"
                              [disabled]="saving() === r.rawKey || !isChanged(r)"
                              (click)="save(r)">
                        {{ saving() === r.rawKey ? 'Enregistrement…' : 'Enregistrer' }}
                      </button>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }

      @if (filtered().length > shown()) {
        <div class="ctl-more">
          <button class="ctl-btn" type="button" (click)="shown.set(shown() + 100)">
            Afficher plus ({{ (filtered().length - shown()).toLocaleString('fr-FR') }} restants)
          </button>
        </div>
      }
    </div>
  `,
  styles: [`
    h1 { font-size: 22px; margin: 0 0 4px; color: var(--ctl-ink, #16212b); letter-spacing: -.015em; }
    .lead { color: var(--ctl-muted, #58656f); max-width: 80ch; font-size: 14px; margin: 0; }
    .ctl-card {
      background: var(--ctl-panel, #fff); border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); padding: 16px;
      display: flex; flex-direction: column; gap: 12px; min-width: 0;
    }
    .ctl-filters { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
    input {
      font: inherit; font-size: 13px; color: var(--ctl-ink, #16212b);
      background: var(--ctl-panel, #fff); border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: 8px; padding: 7px 10px; min-width: 0;
    }
    .ctl-filters input[type="search"] { flex: 1 1 220px; }
    td input { width: 100%; min-width: 180px; }
    .ctl-btn {
      font: inherit; font-size: 13px; font-weight: 600; border-radius: 8px;
      padding: 7px 14px; border: 1px solid var(--ctl-line, #dbe1df);
      background: var(--ctl-panel, #fff); color: var(--ctl-ink, #16212b); cursor: pointer; white-space: nowrap;
    }
    .ctl-btn:disabled { opacity: .5; cursor: default; }
    .ctl-sumbar { display: flex; flex-wrap: wrap; gap: 6px 18px; font-size: 13px; color: var(--ctl-muted, #58656f); }
    .ctl-sumbar b { color: var(--ctl-ink, #16212b); font-family: var(--ctl-font-num); font-weight: 500; }
    .ctl-tw { overflow-x: auto; border: 1px solid var(--ctl-line, #dbe1df); border-radius: var(--ctl-radius, 10px); }
    table { border-collapse: collapse; width: 100%; font-size: 13px; }
    th {
      text-align: left; font-weight: 600; color: var(--ctl-muted, #58656f); font-size: 12px;
      padding: 8px 10px; border-bottom: 1px solid var(--ctl-line, #dbe1df);
      background: var(--ctl-panel-2, #eef2f1); white-space: nowrap;
    }
    td { padding: 8px 10px; border-bottom: 1px solid var(--ctl-line, #dbe1df); vertical-align: top; }
    tr:last-child td { border-bottom: 0; }
    th.num, td.num { text-align: right; font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums; }
    .muted { color: var(--ctl-muted, #58656f); }
    .err { color: var(--ctl-bad, #b42318); font-size: 12px; margin-top: 4px; }
    .ctl-empty { padding: 24px; text-align: center; color: var(--ctl-muted, #58656f); }
    .ctl-more { display: flex; justify-content: center; padding: 10px; }
    .sr-only { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
  `]
})
export class ControleArticlesComponent implements OnInit, OnDestroy {
  private api = inject(ControleService);
  private store = inject(ControleStore);

  rows = signal<CtlArticleRow[]>([]);
  loading = signal(false);
  loadError = signal(false);
  q = signal('');
  shown = signal(100);
  drafts = signal<Record<string, string>>({});
  rowError = signal<Record<string, string>>({});
  saving = signal<string | null>(null);
  private dirty = false;
  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  /** Sorted by line count (server order), filtered by the search text. */
  filtered = computed(() => {
    const q = this.q();
    if (!q) return this.rows();
    return this.rows().filter(r =>
      (r.sampleText + ' ' + r.rawKey + ' ' + r.article).toLowerCase().includes(q));
  });
  filteredLines = computed(() => this.filtered().reduce((n, r) => n + r.lineCount, 0));
  visible = computed(() => this.filtered().slice(0, this.shown()));

  ngOnInit(): void {
    this.load();
  }

  ngOnDestroy(): void {
    // Other pages read the articles from the store: refresh it once, on leave.
    if (this.dirty) this.store.reload();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.api.getArticles().subscribe({
      next: rows => { this.rows.set(rows); this.loading.set(false); },
      error: () => { this.loadError.set(true); this.loading.set(false); },
    });
  }

  onSearch(value: string): void {
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => {
      this.q.set(value.trim().toLowerCase());
      this.shown.set(100);
    }, 180);
  }

  draftOf(r: CtlArticleRow): string {
    return this.drafts()[r.rawKey] ?? r.article;
  }

  setDraft(rawKey: string, value: string): void {
    this.drafts.update(d => ({ ...d, [rawKey]: value }));
  }

  isChanged(r: CtlArticleRow): boolean {
    const d = this.drafts()[r.rawKey];
    return d !== undefined && d.trim() !== r.article;
  }

  save(r: CtlArticleRow): void {
    const article = this.draftOf(r).trim();
    this.setError(r.rawKey, null);
    if (!article) { this.setError(r.rawKey, "Saisissez l'article."); return; }
    this.saving.set(r.rawKey);
    this.api.putArticleAlias({ rawKey: r.rawKey, article, version: r.version }).subscribe({
      next: saved => {
        this.rows.update(rows => rows.map(x => x.rawKey === saved.rawKey ? saved : x));
        this.drafts.update(d => { const n = { ...d }; delete n[r.rawKey]; return n; });
        this.saving.set(null);
        this.dirty = true;
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(null);
        if (e.status === 409) {
          this.setError(r.rawKey, "Quelqu'un d'autre a modifié ce regroupement. Rechargez la page.");
        } else if (e.status === 400 && e.error?.fields?.article) {
          this.setError(r.rawKey, e.error.fields.article);
        } else if (e.status === 404) {
          this.setError(r.rawKey, "Ce texte n'existe plus dans le dernier import. Rechargez la page.");
        } else {
          this.setError(r.rawKey, "L'enregistrement a échoué. Réessayez.");
        }
      },
    });
  }

  private setError(rawKey: string, msg: string | null): void {
    this.rowError.update(m => {
      const n = { ...m };
      if (msg) n[rawKey] = msg; else delete n[rawKey];
      return n;
    });
  }

  exportCsv(): void {
    downloadCsv('regroupement-articles',
      ["Texte dans l'Excel", 'Texte nettoyé', 'Nombre de lignes', 'Article'],
      this.filtered().map(r => [r.sampleText, r.rawKey, r.lineCount, r.article]));
  }
}
