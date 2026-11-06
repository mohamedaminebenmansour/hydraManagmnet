import { Injectable, computed, effect, inject, signal } from '@angular/core';
import { ControleService, CtlData, CtlLine, CtlStLine, CtlAccount, CtlStAccount, CtlJustif, CtlValid } from '@app/core/services/controle.service';
import { GRP } from './controle-format';

interface TotalsResult { e: number; d: number; dj: number; n: number; }
interface StTotalsResult { travaux: number; paye: number; solde: number; nonFound: number; }
interface PersonRow {
  person: string;
  accts: CtlStAccount[];
  travaux: number;
  paye: number;
  nf: number;
  supplier: boolean;
  hors?: number;
}

/**
 * ControleStore — the single source of truth for the Contrôle de trésorerie
 * pages. Holds the data loaded from GET /api/controle/data, the scope
 * ('grp' | 'all') remembered in localStorage, and the justifications /
 * validations maps.
 *
 * All prototype business-logic functions are ported here as store methods with
 * the SAME logic: inScope, jStatus, isTransfer, outAmt, inAmt, totals,
 * stTotals, persons, sourceOfSt, projList, scopeLines, scopeName.
 */
@Injectable({ providedIn: 'root' })
export class ControleStore {
  private api = inject(ControleService);

  // ── Signals ──────────────────────────────────────────────────────────────
  data = signal<CtlData | null>(null);
  loading = signal(false);
  error = signal(false);

  /** 'grp' = only the three SIGAT-NGNET projects; 'all' = every project. */
  scope = signal<'grp' | 'all'>('grp');

  /** lineKey → CtlJustif (mutated by setJustif after a save). */
  justif = signal<Map<string, CtlJustif>>(new Map());
  /** key → CtlValid (mutated by setValid after a save). */
  valid = signal<Map<string, CtlValid>>(new Map());

  // ── Maps by id (computed once after load) ────────────────────────────────
  lineById = computed<Map<string, CtlLine>>(() => {
    const m = new Map<string, CtlLine>();
    const lines = this.data()?.lines ?? [];
    for (const l of lines) m.set(l.id, l);
    return m;
  });

  stById = computed<Map<string, CtlStLine>>(() => {
    const m = new Map<string, CtlStLine>();
    const stLines = this.data()?.stLines ?? [];
    for (const s of stLines) m.set(s.id, s);
    return m;
  });

  acctById = computed<Map<string, CtlAccount>>(() => {
    const m = new Map<string, CtlAccount>();
    const accounts = this.data()?.accounts ?? [];
    for (const a of accounts) m.set(a.id, a);
    return m;
  });

  stAcctById = computed<Map<string, CtlStAccount>>(() => {
    const m = new Map<string, CtlStAccount>();
    const stAccounts = this.data()?.stAccounts ?? [];
    for (const a of stAccounts) m.set(a.id, a);
    return m;
  });

  // ── Constructor: restore scope from localStorage, persist changes ─────────
  constructor() {
    try {
      const saved = localStorage.getItem('controle-scope');
      if (saved === 'all' || saved === 'grp') this.scope.set(saved);
    } catch { /* localStorage unavailable */ }

    // Persist scope changes to localStorage
    effect(() => {
      const s = this.scope();
      try { localStorage.setItem('controle-scope', s); } catch { /* ignore */ }
    });
  }

  // ── Load ──────────────────────────────────────────────────────────────────
  load(): void {
    if (this.loading() || this.data()) return; // load once
    this.loading.set(true);
    this.error.set(false);
    this.api.getData().subscribe({
      next: (d) => {
        this.data.set(d);
        const j = new Map<string, CtlJustif>();
        for (const item of d.justif ?? []) j.set(item.lineKey, item);
        this.justif.set(j);
        const v = new Map<string, CtlValid>();
        for (const item of d.valid ?? []) v.set(item.key, item);
        this.valid.set(v);
        this.loading.set(false);
      },
      error: () => {
        this.error.set(true);
        this.loading.set(false);
      },
    });
  }

  reload(): void {
    this.data.set(null);
    this.load();
  }

  setScope(s: 'grp' | 'all'): void {
    this.scope.set(s);
  }

  setJustif(key: string, value: CtlJustif | null): void {
    const m = new Map(this.justif());
    if (value === null) m.delete(key);
    else m.set(key, value);
    this.justif.set(m);
  }

  setValid(key: string, value: CtlValid): void {
    const m = new Map(this.valid());
    m.set(key, value);
    this.valid.set(m);
  }

  // ── Prototype functions ported with the SAME logic ────────────────────────

  /** Whether a project is visible in the current scope. */
  inScope(proj: string): boolean {
    return this.scope() === 'all' || GRP.includes(proj);
  }

  /** Justification of a line (or undefined). */
  jOf(id: string): CtlJustif | undefined {
    return this.justif().get(id);
  }

  /** Justification status of a line, defaulting to 'none'. */
  jStatus(id: string): string {
    return this.jOf(id)?.statut ?? 'none';
  }

  /** Whether a line is a transfer between accounts. */
  isTransfer(l: CtlLine): boolean {
    return l.kind === 'transfert';
  }

  /** Sortie amount of a line (excludes transfers and partner entries). */
  outAmt(l: CtlLine): number {
    return l.kind === 'partenaire' ? l.d : (this.isTransfer(l) ? 0 : l.d);
  }

  /** Entrée amount of a line (excludes transfers and partner entries). */
  inAmt(l: CtlLine): number {
    return l.kind === 'partenaire' ? l.e : (this.isTransfer(l) ? 0 : l.e);
  }

  /** Totals for a set of lines: entrées, sorties, sorties justifiées, count. */
  totals(rows: CtlLine[]): TotalsResult {
    let e = 0, d = 0, dj = 0, n = 0;
    for (const l of rows) {
      e += this.inAmt(l);
      d += this.outAmt(l);
      if (this.jStatus(l.id) === 'justifie') dj += this.outAmt(l);
      n++;
    }
    return { e, d, dj, n };
  }

  /** Totals for a subcontractor account: travaux, payé, solde, nonFound count. */
  stTotals(sid: string): StTotalsResult {
    let w = 0, p = 0, nf = 0;
    const stLines = this.data()?.stLines ?? [];
    for (const s of stLines) {
      if (s.st === sid) {
        w += s.travaux;
        p += s.paye;
        if (s.flags.includes('non-retrouve')) nf++;
      }
    }
    return { travaux: w, paye: p, solde: w - p, nonFound: nf };
  }

  /** Lines visible in the current scope. */
  scopeLines(): CtlLine[] {
    return (this.data()?.lines ?? []).filter(l => this.inScope(l.proj));
  }

  /** Human-readable scope name for headings. */
  scopeName(): string {
    return this.scope() === 'grp'
      ? 'Hajeb, Hammed et Ouled Khalfallah'
      : 'tous les projets';
  }

  /** Sorted list of projects in scope (GRP projects first, then alphabetical). */
  projList(): string[] {
    const lines = this.data()?.lines ?? [];
    const set = new Set(lines.map(l => l.proj));
    return [...set].filter(p => this.inScope(p)).sort((a, b) =>
      (GRP.indexOf(a) + 1 || 99) - (GRP.indexOf(b) + 1 || 99) || a.localeCompare(b, 'fr'));
  }

  /** Account name that paid a subcontractor line, or null if not linked. */
  sourceOfSt(s: CtlStLine): string | null {
    if (!s.link) return null;
    const l = this.lineById().get(s.link);
    if (!l) return null;
    return this.acctById().get(l.acct)?.name ?? 'Sans compte';
  }

  /**
   * All subcontractor persons visible in the current scope, with their
   * accounts and totals. Same logic as the prototype:
   *   - include a person if any of their accounts is in scope, OR
   *     (scope === 'grp' AND the person has an account in a GRP project)
   *   - also pick up "hors-compte" payments (lines flagged 'hors-compte' with
   *     party === person) and sum them as `hors`.
   */
  persons(): PersonRow[] {
    const data = this.data();
    if (!data) return [];
    const m = new Map<string, PersonRow>();
    for (const a of data.stAccounts) {
      const inScopeAcct = this.inScope(a.proj);
      const inScopeViaGrp = this.scope() === 'grp' &&
        data.stAccounts.some(b => b.person === a.person && GRP.includes(b.proj));
      if (!inScopeAcct && !inScopeViaGrp) continue;
      const o = m.get(a.person) || { person: a.person, accts: [], travaux: 0, paye: 0, nf: 0, supplier: a.supplier };
      const t = this.stTotals(a.id);
      o.accts.push(a);
      o.travaux += t.travaux;
      o.paye += t.paye;
      o.nf += t.nonFound;
      m.set(a.person, o);
    }
    // hors-compte payments
    for (const l of data.lines) {
      if (l.party && l.flags.includes('hors-compte') && this.inScope(l.proj)) {
        const o = m.get(l.party) || { person: l.party, accts: [], travaux: 0, paye: 0, nf: 0, supplier: false, hors: 0 };
        o.hors = (o.hors ?? 0) + l.d;
        m.set(l.party, o);
      }
    }
    return [...m.values()].sort((a, b) => a.person.localeCompare(b.person, 'fr'));
  }
}
