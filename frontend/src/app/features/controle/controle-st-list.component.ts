import { Component, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { ControleStore } from './controle.store';
import { VLABEL, money, slug } from './controle-format';

/**
 * ControleStListComponent — port of pageSubs() from the prototype.
 */
@Component({
  selector: 'app-controle-st-list',
  standalone: true,
  template: `
    <div>
      <h1>Les sous-traitants</h1>
      <p class="lead">Un sous-traitant peut travailler sur plusieurs projets : sa fiche réunit tous ses comptes. Ouvrez la fiche pendant la réunion pour valider avec lui chaque travail et chaque paiement.</p>
    </div>
    <div class="ctl-tw">
      <table>
        <thead>
          <tr>
            <th>Sous-traitant</th>
            <th>Projets</th>
            <th class="num">Travaux</th>
            <th class="num">Payé</th>
            <th class="num">Solde</th>
            <th class="num">Payé hors compte</th>
            <th class="num">Paiements non retrouvés</th>
            <th>Réunion</th>
          </tr>
        </thead>
        <tbody>
          @for (o of persons(); track o.person) {
            <tr class="click" tabindex="0"
                (click)="goFiche(o.person)" (keydown)="onKey($event, o.person)">
              <td><b>{{ o.person }}</b></td>
              <td>{{ projList(o) }}</td>
              <td class="num">{{ money(o.travaux) }}</td>
              <td class="num">{{ money(o.paye) }}</td>
              <td class="num" [class.neg]="solde(o) < -0.0005" [class.pos]="solde(o) > 0.0005">
                {{ o.accts.length ? money(solde(o)) : '—' }}
              </td>
              <td class="num">{{ money(o.hors) }}</td>
              <td class="num">{{ o.nf || '' }}</td>
              <td>
                <span class="pill s-{{ vStat(o.person) }}">{{ vLabel(o.person) }}</span>
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    <p class="muted small">Solde positif : SIGAT lui doit encore. Négatif : il a reçu plus que ses travaux enregistrés.</p>
  `,
  styles: [`
    h1 { font-size: 22px; margin: 0 0 4px; color: var(--ctl-ink, #16212b); letter-spacing: -.015em; }
    .lead { color: var(--ctl-muted, #58656f); max-width: 80ch; font-size: 14px; margin: 0; }
    .muted { color: var(--ctl-muted, #58656f); }
    .small { font-size: 12px; }
    .ctl-tw { overflow-x: auto; border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); background: var(--ctl-panel, #fff); }
    table { border-collapse: collapse; width: 100%; font-size: 13px; }
    th { text-align: left; font-weight: 600; color: var(--ctl-muted, #58656f); font-size: 12px;
      padding: 8px 10px; border-bottom: 1px solid var(--ctl-line, #dbe1df);
      background: var(--ctl-panel-2, #eef2f1); white-space: nowrap; }
    th.num, td.num { font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums;
      text-align: right; white-space: nowrap; }
    td { padding: 8px 10px; border-bottom: 1px solid var(--ctl-line, #dbe1df); }
    tr:last-child td { border-bottom: 0; }
    tbody tr.click { cursor: pointer; }
    tbody tr.click:hover td { background: var(--ctl-accent-soft, #e1eff2); }
    .pos { color: var(--ctl-ok, #1d7348); }
    .neg { color: var(--ctl-bad, #b42318); }
    .pill { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 600;
      padding: 2px 9px; border-radius: 999px; white-space: nowrap; }
    .pill::before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
    .s-none { background: var(--ctl-panel-2, #eef2f1); color: var(--ctl-muted, #58656f); }
    .s-valide { background: var(--ctl-ok-soft, #e3f3ea); color: var(--ctl-ok, #1d7348); }
    .s-a-revoir { background: var(--ctl-warn-soft, #fbf0dc); color: var(--ctl-warn, #9a5b00); }
    .s-conteste { background: var(--ctl-vio-soft, #eeeafb); color: var(--ctl-vio, #5b3fb5); }
  `]
})
export class ControleStListComponent {
  protected store = inject(ControleStore);
  private router = inject(Router);

  protected money = money;

  persons = computed(() => this.store.persons());

  solde(o: { travaux: number; paye: number }): number {
    return o.travaux - o.paye;
  }

  projList(o: { accts: { proj: string }[] }): string {
    const set = new Set(o.accts.map(a => a.proj));
    return [...set].join(', ') || '—';
  }

  vKey(person: string): string {
    return 'person-' + slug(person);
  }

  vStat(person: string): string {
    return this.store.valid().get(this.vKey(person))?.statut ?? 'none';
  }

  vLabel(person: string): string {
    return VLABEL[this.vStat(person)] ?? this.vStat(person);
  }

  goFiche(person: string): void {
    this.router.navigate(['/controle/sous-traitants', slug(person)]);
  }

  onKey(ev: KeyboardEvent, person: string): void {
    if (ev.key === 'Enter') this.goFiche(person);
  }
}
