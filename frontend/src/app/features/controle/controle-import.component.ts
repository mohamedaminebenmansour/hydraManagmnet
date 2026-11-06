import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { ConfirmationService, MessageService } from 'primeng/api';
import { ControleService, ImportResult, CtlImport } from '@app/core/services/controle.service';
import { ControleStore } from './controle.store';

/**
 * ControleImportComponent — CTL_IMPORT only (route guard handles permission).
 *
 * Explanation, file input (accept .xlsx and .json), « Importer » button with ConfirmDialog,
 * result display, errors, last 10 imports, store.reload() after success.
 */
@Component({
  selector: 'app-controle-import',
  standalone: true,
  imports: [FormsModule, ButtonModule, ConfirmDialogModule],
  providers: [ConfirmationService],
  template: `
    <div>
      <h1>Importer le fichier de caisse</h1>
      <p class="lead">L'archive du fichier Excel de caisse. Les justificatifs et validations déjà saisis sont gardés.</p>
    </div>

    <div class="card">
      <h2>Étapes</h2>
      <ol class="steps">
        <li>Choisissez le fichier Excel Caisse des projets (.xlsx).</li>
        <li>Cliquez « Importer ».</li>
      </ol>
      <p class="muted">Un export JSON de <code>convert.py</code> est aussi accepté. Les justificatifs et validations déjà saisis sont gardés.</p>

      <div class="upload">
        <label class="pick-btn">
          <input type="file" accept=".json,.xlsx" (change)="onFilePick($event)" hidden />
          <i class="pi pi-upload" aria-hidden="true"></i> Choisir le fichier (.json ou .xlsx)
        </label>
        @if (selectedFile()) {
          <span class="filename">{{ selectedFile()!.name }} ({{ (selectedFile()!.size / 1024).toFixed(0) }} Ko)</span>
        }
      </div>

      @if (selectedFile()) {
        <p-button label="Importer" icon="pi pi-check" severity="success" [loading]="importing()" (onClick)="confirmImport()" />
      }
    </div>

    <!-- Result -->
    @if (result()) {
      <div class="card result">
        <h2>Import réussi</h2>
        <div class="result-grid">
          <div><span>Lignes de caisse</span><b>{{ result()!.lines }}</b></div>
          <div><span>Lignes sous-traitants</span><b>{{ result()!.stLines }}</b></div>
          <div><span>Comptes</span><b>{{ result()!.accounts }}</b></div>
        </div>
        @if (result()!.orphanJustifications > 0) {
          <div class="banner warn">
            {{ result()!.orphanJustifications }} justificatif(s) ne correspondent plus à aucune ligne (ligne modifiée dans l'Excel).
          </div>
        }
      </div>
    }

    <!-- Error -->
    @if (error()) {
      <div class="card error">
        <h2>Échec de l'import</h2>
        <p class="err-msg">{{ errorMsg() }}</p>
        @if (errorProblems().length > 0) {
          <ul class="problems">
            @for (p of errorProblems(); track p) { <li>{{ p }}</li> }
          </ul>
        }
      </div>
    }

    <!-- Import history -->
    <div class="card">
      <h2>Historique des imports</h2>
      @if (history().length === 0) {
        <p class="muted">Aucun import pour le moment.</p>
      } @else {
        <div class="ctl-tw">
          <table>
            <thead>
              <tr>
                <th>Date</th>
                <th>Fichier</th>
                <th>Par</th>
                <th class="num">Lignes</th>
                <th class="num">ST</th>
              </tr>
            </thead>
            <tbody>
              @for (imp of history(); track imp.id) {
                <tr>
                  <td>{{ formatDate(imp.importedAt) }}</td>
                  <td>{{ imp.sourceName }}</td>
                  <td>{{ imp.importerName ?? '—' }}</td>
                  <td class="num">{{ imp.lineCount }}</td>
                  <td class="num">{{ imp.stLineCount }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    </div>

    <p-confirmdialog />
  `,
  styles: [`
    .pick-btn { display: inline-flex; align-items: center; gap: 8px; padding: 8px 16px; border-radius: 6px;
      background: var(--p-primary-color); color: #fff; cursor: pointer; font-weight: 500; }
    h1 { font-size: 22px; margin: 0 0 4px; color: var(--ctl-ink, #16212b); letter-spacing: -.015em; }
    .lead { color: var(--ctl-muted, #58656f); max-width: 80ch; font-size: 14px; margin: 0; }
    .muted { color: var(--ctl-muted, #58656f); }
    .num { font-family: var(--ctl-font-num); font-variant-numeric: tabular-nums; }
    .card { background: var(--ctl-panel, #fff); border: 1px solid var(--ctl-line, #dbe1df);
      border-radius: var(--ctl-radius, 10px); padding: 16px; display: flex; flex-direction: column;
      gap: 12px; min-width: 0; }
    h2 { font-size: 15px; margin: 0; }
    .steps { margin: 0; padding-left: 20px; font-size: 14px; line-height: 1.8; }
    .steps code { background: var(--ctl-panel-2, #eef2f1); padding: 1px 6px; border-radius: 4px;
      font-family: var(--ctl-font-num); font-size: 13px; }
    .upload { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
    .filename { font-size: 13px; color: var(--ctl-muted, #58656f); }
    .result-grid { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(min(100%, 160px), 1fr)); }
    .result-grid div { background: var(--ctl-panel-2, #eef2f1); border-radius: 8px; padding: 10px 14px; }
    .result-grid span { display: block; font-size: 12px; color: var(--ctl-muted, #58656f); }
    .result-grid b { display: block; font-family: var(--ctl-font-num); font-weight: 500; font-size: 21px;
      font-variant-numeric: tabular-nums; }
    .banner { border-radius: 8px; padding: 10px 14px; font-size: 13px; }
    .banner.warn { background: var(--ctl-warn-soft, #fbf0dc); color: var(--ctl-warn, #9a5b00); }
    .error { border-color: var(--ctl-bad, #b42318); }
    .err-msg { color: var(--ctl-bad, #b42318); font-weight: 600; margin: 0; }
    .problems { margin: 8px 0 0; padding-left: 20px; font-size: 13px; color: var(--ctl-ink, #16212b); }
    .problems li { margin-bottom: 4px; }
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
    @media (max-width: 400px) { .result-grid { grid-template-columns: 1fr; } }
  `]
})
export class ControleImportComponent implements OnInit {
  private api = inject(ControleService);
  private store = inject(ControleStore);
  private confirm = inject(ConfirmationService);
  private msgs = inject(MessageService);

  protected selectedFile = signal<File | null>(null);
  protected importing = signal(false);
  protected result = signal<ImportResult | null>(null);
  protected error = signal(false);
  protected errorMsg = signal('');
  protected errorProblems = signal<string[]>([]);
  protected history = signal<CtlImport[]>([]);

  ngOnInit(): void {
    this.loadHistory();
  }

  loadHistory(): void {
    this.api.imports().subscribe({
      next: (rows) => this.history.set(rows ?? []),
      error: () => this.history.set([]),
    });
  }

  onFilePick(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0) return;
    this.selectedFile.set(input.files[0]);
    // Reset so choosing the same file again fires (change)
    input.value = '';
    // Clear previous result/error
    this.result.set(null);
    this.error.set(false);
    this.errorProblems.set([]);
  }

  confirmImport(): void {
    const file = this.selectedFile();
    if (!file) return;
    this.confirm.confirm({
      header: "Confirmer l'import",
      message: "Remplacer les lignes actuelles par ce fichier ?",
      acceptLabel: "Oui, importer",
      rejectLabel: "Annuler",
      accept: () => this.doImport(file),
    });
  }

  private doImport(file: File): void {
    this.importing.set(true);
    this.error.set(false);
    this.result.set(null);
    this.api.importFile(file).subscribe({
      next: (r) => {
        this.importing.set(false);
        this.result.set(r);
        this.error.set(false);
        this.msgs.add({ severity: 'success', summary: 'Import réussi',
          detail: `${r.lines} lignes, ${r.stLines} lignes sous-traitants.` });
        // Reload the store so every page sees the new data
        this.store.reload();
        this.loadHistory();
      },
      error: (err) => {
        this.importing.set(false);
        this.error.set(true);
        const code = err?.error?.code;
        if (code === 'IMPORT_INVALID') {
          this.errorMsg.set(err?.error?.message ?? 'Le fichier contient des erreurs.');
          this.errorProblems.set(err?.error?.problems ?? []);
        } else if (code === 'IMPORT_FORMAT') {
          // the server names the missing sheet for an .xlsx
          this.errorMsg.set(err?.error?.message ?? "Le fichier n'est pas un export valide de convert.py.");
          this.errorProblems.set([]);
        } else if (code === 'IMPORT_TOO_LARGE') {
          this.errorMsg.set('Le fichier dépasse 20 Mo.');
          this.errorProblems.set([]);
        } else {
          this.errorMsg.set(err?.error?.message ?? 'Import impossible.');
          this.errorProblems.set([]);
        }
      },
    });
  }

  formatDate(iso: string): string {
    if (!iso) return '—';
    const d = new Date(iso);
    return d.toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  }
}
