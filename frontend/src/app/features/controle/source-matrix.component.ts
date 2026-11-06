import { Component, EventEmitter, Input, Output, computed, inject, signal } from '@angular/core';
import { ControleStore } from './controle.store';
import { CtlLine, CtlAccount } from '@app/core/services/controle.service';
import { money } from './controle-format';

/**
 * SourceMatrixComponent — port of sourceMatrix() from the prototype.
 *
 * Rows = groups (projects or categories), columns = accounts that have sorties,
 * last column « Total sorties », a total row at the bottom, rows sorted by total.
 * Clicking a row emits the group key.
 */
@Component({
  selector: 'ctl-source-matrix',
  standalone: true,
  template: `
    <div class="ctl-tw">
      <table>
        <thead>
          <tr>
            <th></th>
            @for (a of accounts(); track a.id) {
              <th class="num">{{ a.name }}</th>
            }
            <th class="num">Total sorties</th>
          </tr>
        </thead>
        <tbody>
          @for (row of rows(); track row.key) {
            <tr [class.click]="!!onClick" (click)="onRowClick(row.key)" tabindex="0"
                (keydown)="onKey($event, row.key)">
              <td>{{ row.key }}</td>
              @for (a of accounts(); track a.id) {
                <td class="num">{{ money(row.amts[a.id]) }}</td>
              }
              <td class="num bold">{{ money(row.total) }}</td>
            </tr>
          }
        </tbody>
        <tfoot>
          <tr>
            <td>Total</td>
            @for (a of accounts(); track a.id) {
              <td class="num">{{ money(foot()[a.id]) }}</td>
            }
            <td class="num">{{ money(foot()['t']) }}</td>
          </tr>
        </tfoot>
      </table>
    </div>
  `,
  styles: [`
    .ctl-tw { overflow-x: auto; border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); background: var(--ctl-panel, #fff); }
    table { border-collapse: collapse; width: 100%; font-size: 13px; }
    th { text-align: left; font-weight: 600; color: var(--ctl-muted, #58656f);
      font-size: 12px; padding: 8px 10px; border-bottom: 1px solid var(--ctl-line, #dbe1df);
      background: var(--ctl-panel-2, #eef2f1); position: sticky; top: 0; white-space: nowrap; }
    th.num, td.num { font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums;
      text-align: right; white-space: nowrap; }
    td { padding: 8px 10px; border-bottom: 1px solid var(--ctl-line, #dbe1df); }
    tr:last-child td { border-bottom: 0; }
    tbody tr.click { cursor: pointer; }
    tbody tr.click:hover td { background: var(--ctl-accent-soft, #e1eff2); }
    tfoot td { font-weight: 600; background: var(--ctl-panel-2, #eef2f1);
      border-top: 1px solid var(--ctl-line, #dbe1df); }
    .bold { font-weight: 600; }
  `]
})
export class SourceMatrixComponent {
  private store = inject(ControleStore);
  protected money = money;

  private _data = signal<CtlLine[]>([]);

  @Input({ required: true }) set data(value: CtlLine[]) { this._data.set(value); }
  @Input() groupBy: (l: CtlLine) => string = (l) => l.proj;
  @Input() onClick: ((key: string) => void) | null = null;

  @Output() rowClick = new EventEmitter<string>();

  /** Accounts that have sorties in the rows. */
  accounts = computed<CtlAccount[]>(() => {
    const data = this._data();
    const dataAccounts = this.store.data()?.accounts ?? [];
    return dataAccounts.filter(a =>
      data.some(l => l.acct === a.id && this.store.outAmt(l) > 0));
  });

  /** Grouped rows: key -> { amts: {acctId: amount}, total }. */
  rows = computed(() => {
    const data = this._data();
    const m = new Map<string, { amts: Record<string, number>; total: number }>();
    for (const l of data) {
      const k = this.groupBy(l);
      const entry = m.get(k) || { amts: {}, total: 0 };
      const v = this.store.outAmt(l);
      entry.amts[l.acct] = (entry.amts[l.acct] || 0) + v;
      entry.total += v;
      m.set(k, entry);
    }
    return [...m.entries()]
      .filter(([, e]) => e.total > 0)
      .sort(([, a], [, b]) => b.total - a.total)
      .map(([key, e]) => ({ key, amts: e.amts, total: e.total }));
  });

  /** Footer totals per account. */
  foot = computed(() => {
    const f: Record<string, number> = {};
    let t = 0;
    for (const row of this.rows()) {
      for (const [a, v] of Object.entries(row.amts)) {
        f[a] = (f[a] || 0) + v;
        t += v;
      }
    }
    f['t'] = t;
    return f;
  });

  onRowClick(key: string): void {
    if (this.onClick) this.onClick(key);
    this.rowClick.emit(key);
  }

  onKey(ev: KeyboardEvent, key: string): void {
    if (ev.key === 'Enter') this.onRowClick(key);
  }
}
