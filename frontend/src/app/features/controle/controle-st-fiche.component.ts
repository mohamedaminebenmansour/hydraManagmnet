import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { ControleStore } from './controle.store';
import { ControleDrawerService } from './controle-drawer.service';
import { LinesTableComponent } from './lines-table.component';
import { ValidationBlockComponent } from './validation-block.component';
import { CtlStLine, CtlLine } from '@app/core/services/controle.service';
import { STATUS_LABEL, money, slug } from './controle-format';

interface StSheet {
  acct: { id: string; proj: string; sheet: string };
  travaux: number; paye: number; solde: number;
  workRows: CtlStLine[]; payRows: CtlStLine[];
}

/**
 * ControleStFicheComponent — port of pageSub(slug) from the prototype.
 *
 * Used in a meeting on a laptop: big balance (26 px), table text ≥ 13 px.
 * « Version imprimable » button calls window.print(), with @media print
 * hiding the menu, tabs, filters and buttons.
 */
@Component({
  selector: 'app-controle-st-fiche',
  standalone: true,
  imports: [RouterLink, ButtonModule, LinesTableComponent, ValidationBlockComponent],
  template: `
    @if (found()) {
      <div class="no-print">
        <button class="ctl-btn" type="button" (click)="print()">
          <i class="pi pi-print"></i> Version imprimable
        </button>
      </div>

      <p class="crumb"><a routerLink="/controle/sous-traitants">Sous-traitants</a> / {{ personName() }}</p>
      <div>
        <h1>{{ personName() }}</h1>
        <p class="lead">Fiche de réunion : chaque travail et chaque paiement, avec la caisse qui a payé. Cliquez une ligne pour noter la facture ou l'attachement qui la justifie.</p>
      </div>

      <!-- KPIs -->
      <div class="kpis">
        <div class="kpi"><span>Travaux enregistrés</span><b>{{ money(personTotals().travaux) }}</b><small>TND, tous projets</small></div>
        <div class="kpi"><span>Payé</span><b>{{ money(personTotals().paye) }}</b><small>TND, tous projets</small></div>
        <div class="kpi">
          <span>Solde</span>
          <b [class.neg]="personSolde() < -0.0005" [class.pos]="personSolde() > 0.0005">{{ money(personSolde()) }}</b>
          <small>{{ soldeSentence(personSolde()) }}</small>
        </div>
        <div class="kpi"><span>Payé hors compte</span><b>{{ money(personTotals().hors ?? 0) }}</b><small>trouvé dans les caisses, absent de ses feuilles</small></div>
      </div>

      <!-- One card per subcontractor sheet -->
      @for (sheet of sheets(); track sheet.acct.id) {
        <section class="card">
          <div class="sheet-head">
            <h2>{{ sheet.acct.proj }} — feuille « {{ sheet.acct.sheet }} »</h2>
            <span class="big-solde" [class.neg]="sheet.solde < -0.0005" [class.pos]="sheet.solde > 0.0005">
              {{ money(sheet.solde) }}
            </span>
          </div>
          <p>{{ soldeSentence(sheet.solde) }}</p>

          @if (isExactZero(sheet)) {
            <div class="banner">Travaux exactement égaux aux paiements : les travaux ont peut-être été recopiés des paiements. Vérifier avec les attachements signés.</div>
          }
          @if (sheet.travaux === 0 && sheet.paye > 0) {
            <div class="banner">Payé sans aucun travail enregistré : demander les attachements.</div>
          }

          <!-- Travaux table -->
          <h3>Travaux ({{ sheet.workRows.length }})</h3>
          @if (sheet.workRows.length > 0) {
            <div class="ctl-tw">
              <table>
                <thead>
                  <tr><th>Date</th><th>Désignation</th><th class="num">Montant</th><th>Justificatif</th><th>Ligne</th></tr>
                </thead>
                <tbody>
                  @for (s of sheet.workRows; track s.id) {
                    <tr class="click" tabindex="0"
                        (click)="openLine(s.id)" (keydown)="onRowKey($event, s.id)">
                      <td class="num left">{{ dfmt(s.date) }}</td>
                      <td class="desc">{{ s.desig || '—' }}</td>
                      <td class="num">{{ money(s.travaux) }}</td>
                      <td><span class="pill s-{{ jStat(s.id) }}">{{ jStatLabel(s.id) }}</span></td>
                      <td class="muted">L{{ s.row }}</td>
                    </tr>
                  }
                </tbody>
                <tfoot>
                  <tr><td>Total</td><td></td><td class="num">{{ money(sheet.travaux) }}</td><td></td><td></td></tr>
                </tfoot>
              </table>
            </div>
          } @else {
            <p class="empty">Aucun travail enregistré dans cette feuille.</p>
          }

          <!-- Paiements table -->
          <h3>Paiements ({{ sheet.payRows.length }})</h3>
          @if (sheet.payRows.length > 0) {
            <div class="ctl-tw">
              <table>
                <thead>
                  <tr><th>Date</th><th>Désignation</th><th>Paiement</th><th>Payé depuis</th><th class="num">Montant</th><th>Justificatif</th><th>Ligne</th></tr>
                </thead>
                <tbody>
                  @for (s of sheet.payRows; track s.id) {
                    <tr class="click" tabindex="0"
                        (click)="openLine(s.id)" (keydown)="onRowKey($event, s.id)">
                      <td class="num left">{{ dfmt(s.date) }}</td>
                      <td class="desc">{{ s.desig || '—' }}</td>
                      <td>{{ s.mode }}</td>
                      <td>
                        @if (sourceOf(s); as src) {
                          <span class="chip acc">{{ src }}</span>
                        } @else {
                          <span class="chip red">Non retrouvé</span>
                        }
                      </td>
                      <td class="num">{{ money(s.paye) }}</td>
                      <td><span class="pill s-{{ jStat(s.id) }}">{{ jStatLabel(s.id) }}</span></td>
                      <td class="muted">L{{ s.row }}</td>
                    </tr>
                  }
                </tbody>
                <tfoot>
                  <tr><td>Total</td><td></td><td></td><td></td><td class="num">{{ money(sheet.paye) }}</td><td></td><td></td></tr>
                </tfoot>
              </table>
            </div>
          } @else {
            <p class="empty">Aucun paiement dans cette feuille.</p>
          }

          <!-- Per-sheet validation -->
          <ctl-validation-block [key]="'st-' + sheet.acct.id"
                                 [label]="'Validation du compte ' + sheet.acct.proj"
                                 [suggestedBalance]="sheet.solde" />
        </section>
      }

      <!-- Hors-compte lines (if any) -->
      @if (horsLines().length > 0) {
        <ctl-lines-table [preset]="horsPreset" title="Payé dans les caisses mais absent de ses comptes" />
      }

      <!-- General validation -->
      <ctl-validation-block [key]="'person-' + slug(personName())"
                             [label]="'Validation générale avec ' + personName()"
                             [suggestedBalance]="personSolde()" />
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

    .no-print { display: flex; justify-content: flex-end; }
    .ctl-btn { font: inherit; font-size: 13px; font-weight: 600; border-radius: 8px;
      padding: 7px 14px; border: 1px solid var(--ctl-line, #dbe1df);
      background: var(--ctl-panel, #fff); color: var(--ctl-ink, #16212b); cursor: pointer;
      display: inline-flex; align-items: center; gap: 6px; }

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
    h3 { font-size: 13px; margin: 0; text-transform: uppercase; letter-spacing: .06em;
      color: var(--ctl-muted, #58656f); font-weight: 600; }
    .sheet-head { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 8px; align-items: baseline; }
    .big-solde { font-family: var(--ctl-font-num); font-size: 26px; font-weight: 500;
      font-variant-numeric: tabular-nums; }

    .banner { border-radius: var(--ctl-radius, 10px); padding: 10px 14px; font-size: 13px;
      background: var(--ctl-warn-soft, #fbf0dc); color: var(--ctl-warn, #9a5b00); }

    .ctl-tw { overflow-x: auto; border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); background: var(--ctl-panel, #fff); }
    table { border-collapse: collapse; width: 100%; font-size: 13px; }
    th { text-align: left; font-weight: 600; color: var(--ctl-muted, #58656f); font-size: 12px;
      padding: 8px 10px; border-bottom: 1px solid var(--ctl-line, #dbe1df);
      background: var(--ctl-panel-2, #eef2f1); white-space: nowrap; }
    th.num, td.num { font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums;
      text-align: right; white-space: nowrap; }
    td.num.left { text-align: left; }
    td { padding: 8px 10px; border-bottom: 1px solid var(--ctl-line, #dbe1df); }
    tr:last-child td { border-bottom: 0; }
    tbody tr.click { cursor: pointer; }
    tbody tr.click:hover td { background: var(--ctl-accent-soft, #e1eff2); }
    tfoot td { font-weight: 600; background: var(--ctl-panel-2, #eef2f1);
      border-top: 1px solid var(--ctl-line, #dbe1df); }
    .desc { min-width: 180px; max-width: 400px; }
    .chip { display: inline-flex; align-items: center; gap: 4px; font-size: 11.5px;
      padding: 1px 7px; border-radius: 999px; white-space: nowrap; }
    .chip.acc { background: var(--ctl-accent-soft, #e1eff2); color: var(--ctl-accent, #0d5c6e); }
    .chip.red { background: var(--ctl-bad-soft, #fbe7e5); color: var(--ctl-bad, #b42318); }
    .pill { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600;
      padding: 2px 9px; border-radius: 999px; white-space: nowrap; }
    .pill::before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
    .s-none { background: var(--ctl-panel-2, #eef2f1); color: var(--ctl-muted, #58656f); }
    .s-justifie, .s-valide { background: var(--ctl-ok-soft, #e3f3ea); color: var(--ctl-ok, #1d7348); }
    .s-partiel, .s-a-revoir { background: var(--ctl-warn-soft, #fbf0dc); color: var(--ctl-warn, #9a5b00); }
    .s-non-justifie { background: var(--ctl-bad-soft, #fbe7e5); color: var(--ctl-bad, #b42318); }
    .s-conteste { background: var(--ctl-vio-soft, #eeeafb); color: var(--ctl-vio, #5b3fb5); }
    .empty { color: var(--ctl-muted, #58656f); padding: 12px; text-align: center; }

    @media print {
      .no-print, app-controle-layout > .ctl-top, app-controle-layout > .ctl-tabs { display: none !important; }
      .ctl-filters, .ctl-more, .ctl-btn, button { display: none !important; }
      .card { break-inside: avoid; border: 1px solid #999; }
      body { background: #fff !important; }
    }
  `]
})
export class ControleStFicheComponent implements OnInit {
  protected store = inject(ControleStore);
  private drawer = inject(ControleDrawerService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);

  protected money = money;
  protected slug = slug;

  private slugSig = signal('');
  protected found = signal(false);

  // Preset for hors-compte lines table
  horsPreset = (l: CtlLine) => false;

  personName = computed(() => {
    const persons = this.store.persons();
    const sl = this.slugSig();
    const found = persons.find(p => slug(p.person) === sl);
    if (found) return found.person;
    // Fallback: look in stAccounts
    const stAccts = this.store.data()?.stAccounts ?? [];
    const a = stAccts.find(x => slug(x.person) === sl);
    return a?.person ?? '';
  });

  personTotals = computed(() => {
    const persons = this.store.persons();
    const sl = this.slugSig();
    const found = persons.find(p => slug(p.person) === sl);
    if (found) return found;
    return { person: '', accts: [], travaux: 0, paye: 0, nf: 0, supplier: false, hors: 0 };
  });

  personSolde = computed(() => this.personTotals().travaux - this.personTotals().paye);

  sheets = computed<StSheet[]>(() => {
    const person = this.personName();
    if (!person) return [];
    const stAccts = this.store.data()?.stAccounts ?? [];
    const stLines = this.store.data()?.stLines ?? [];
    return stAccts
      .filter(a => a.person === person)
      .map(a => {
        const ls = stLines
          .filter(s => s.st === a.id)
          .sort((x, y) => (x.date ?? '').localeCompare(y.date ?? '') || x.row - y.row);
        const t = this.store.stTotals(a.id);
        return {
          acct: { id: a.id, proj: a.proj, sheet: a.sheet },
          travaux: t.travaux, paye: t.paye, solde: t.solde,
          workRows: ls.filter(s => s.travaux),
          payRows: ls.filter(s => s.paye),
        };
      });
  });

  horsLines = computed(() => {
    const person = this.personName();
    if (!person) return [];
    const lines = this.store.data()?.lines ?? [];
    return lines.filter(l => l.party === person && l.flags.includes('hors-compte'));
  });

  ngOnInit(): void {
    this.route.paramMap.subscribe(params => {
      const sl = params.get('slug');
      if (!sl) { this.router.navigate(['/controle/sous-traitants']); return; }
      this.slugSig.set(sl);
      const persons = this.store.persons();
      const found = persons.find(p => slug(p.person) === sl);
      if (!found) {
        // Fallback: check stAccounts
        const stAccts = this.store.data()?.stAccounts ?? [];
        const a = stAccts.find(x => slug(x.person) === sl);
        if (!a) { this.router.navigate(['/controle/sous-traitants']); return; }
      }
      this.found.set(true);
      const person = this.personName();
      this.horsPreset = (l: CtlLine) => l.party === person && l.flags.includes('hors-compte');
    });
  }

  // ── Helpers ──────────────────────────────────────────────────────────────
  soldeSentence(sol: number): string {
    if (Math.abs(sol) < 0.0005) return 'Compte soldé : travaux = paiements.';
    return sol > 0
      ? `SIGAT lui doit encore ${money(sol)} TND.`
      : `Il a reçu ${money(-sol)} TND de plus que ses travaux enregistrés.`;
  }

  isExactZero(sheet: StSheet): boolean {
    return Math.abs(sheet.solde) < 0.0005 && sheet.travaux > 0;
  }

  jStat(id: string): string {
    return this.store.jStatus(id);
  }

  jStatLabel(id: string): string {
    return STATUS_LABEL[this.jStat(id)] ?? id;
  }

  sourceOf(s: CtlStLine): string | null {
    return this.store.sourceOfSt(s);
  }

  openLine(id: string): void {
    this.drawer.open(id);
  }

  onRowKey(ev: KeyboardEvent, id: string): void {
    if (ev.key === 'Enter') this.openLine(id);
  }

  print(): void {
    window.print();
  }

  // Expose dfmt to template
  dfmt(s: string | null): string {
    return s ? s.slice(8, 10) + '/' + s.slice(5, 7) + '/' + s.slice(0, 4) : '—';
  }
}
