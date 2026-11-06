import { Component } from '@angular/core';
import { LinesTableComponent } from './lines-table.component';

/** Temporary: shows all lines so the table + drawer can be tried. P5 replaces this. */
@Component({
  selector: 'app-controle-home',
  standalone: true,
  imports: [LinesTableComponent],
  template: `
    <div>
      <h1>Vue d'ensemble</h1>
      <p class="muted">Page à venir. Ci-dessous, toutes les lignes pour tester le tableau et le tiroir.</p>
    </div>
    <ctl-lines-table [preset]="allPreset" title="Toutes les lignes (test)" />
  `,
  styles: [`
    h1 { font-size: 22px; margin: 0 0 4px; color: var(--ctl-ink, #16212b); }
    .muted { color: var(--ctl-muted, #58656f); font-size: 14px; margin: 0 0 16px; }
  `]
})
export class ControleHomeComponent {
  // Temporary: show every line. P5 will replace with scopeLines + KPIs + sourceMatrix.
  allPreset = () => true;
}
