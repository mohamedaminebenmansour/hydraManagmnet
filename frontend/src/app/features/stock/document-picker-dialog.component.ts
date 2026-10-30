import { Component, computed, inject, signal, Input, Output, EventEmitter, OnChanges } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { DialogModule } from 'primeng/dialog';
import { MessageService } from 'primeng/api';

import { DocumentService } from '@app/core/services/document.service';
import { StockService } from '@app/core/services/stock.service';
import { Document, mapDocument } from '@app/core/models/document.model';
import { StockDocKind } from '@app/core/models/stock.model';

/**
 * DocumentPickerDialogComponent — reusable picker for existing project documents.
 *
 * Scope: lists documents in the project's STOCK/BL folder (05.01 BL) only.
 * The caller passes a projectId; the dialog resolves-or-creates the BL folder
 * via GET /api/stock/doc-folder?projectId=&kind=BL, then lists documents in
 * that folder via GET /api/projects/{projectId}/documents?folderId=.
 *
 * Emits `picked` with the selected documentId. The caller is responsible for
 * calling POST /api/stock/moves (with documentId) or POST /moves/{id}/documents
 * (junction link) depending on the use case.
 *
 * Pattern mirrors folder-access-dialog.component.ts (@Input/@Output shape).
 */
@Component({
  selector: 'app-document-picker-dialog',
  standalone: true,
  imports: [FormsModule, ButtonModule, InputTextModule, DialogModule],
  template: `
    <p-dialog header="Choisir un document existant" [(visible)]="visible" [modal]="true"
              [style]="{ width: '600px' }" (onHide)="onHide()">
      <div class="dpd">
        @if (loading()) {
          <div class="dpd-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
        } @else if (error()) {
          <div class="dpd-error">
            <i class="pi pi-exclamation-triangle"></i>
            {{ error() }}
          </div>
        } @else if (documents().length === 0) {
          <div class="dpd-empty">
            <i class="pi pi-folder-open"></i>
            <p>Aucun document dans le dossier <strong>STOCK / {{ kind }}</strong> de ce projet.</p>
            <small>Téléversez un nouveau fichier depuis le formulaire de mouvement, ou ajoutez d'abord un document dans le dossier du projet.</small>
          </div>
        } @else {
          <div class="dpd-search">
            <i class="pi pi-search"></i>
            <input pInputText [(ngModel)]="search" placeholder="Filtrer par titre ou référence…" />
          </div>
          <ul class="dpd-list">
            @for (d of filtered(); track d.id) {
              <li (click)="pick(d)" [class.selected]="selectedId() === d.id">
                <span class="dpd-icon"><i class="pi pi-file-o"></i></span>
                <div class="dpd-info">
                  <div class="dpd-title">{{ d.title || 'Sans titre' }}</div>
                  <div class="dpd-meta">
                    @if (d.reference) { <span class="dpd-ref">{{ d.reference }}</span> }
                    <span class="dpd-type">{{ d.docType }}</span>
                  </div>
                </div>
                <i class="pi pi-check dpd-check"></i>
              </li>
            }
          </ul>
        }
      </div>
      <ng-template pTemplate="footer">
        <p-button label="Annuler" [text]="true" (onClick)="cancel()" />
        <p-button label="Lier ce document" icon="pi pi-link" severity="success"
                  [disabled]="!selectedId()" (onClick)="confirm()" />
      </ng-template>
    </p-dialog>
  `,
  styles: [`
    .dpd { display: flex; flex-direction: column; gap: 12px; min-height: 200px; }
    .dpd-loading, .dpd-empty, .dpd-error {
      text-align: center; padding: 30px 20px; color: #64748b;
      display: flex; flex-direction: column; align-items: center; gap: 8px;
    }
    .dpd-loading i, .dpd-empty i { font-size: 2rem; color: #94a3b8; }
    .dpd-error { color: #991b1b; }
    .dpd-error i { color: #dc2626; }
    .dpd-empty small { color: #94a3b8; }
    .dpd-search {
      display: flex; align-items: center; gap: 8px;
      background: #f8fafc; border: 1px solid #e2e8f0;
      border-radius: 8px; padding: 0 12px;
    }
    .dpd-search i { color: #94a3b8; }
    .dpd-search input { border: none; background: transparent; padding: 9px 0; width: 100%; outline: none; }
    .dpd-list { list-style: none; padding: 0; margin: 0; max-height: 320px; overflow-y: auto;
      display: flex; flex-direction: column; gap: 6px; }
    .dpd-list li {
      display: flex; align-items: center; gap: 12px;
      padding: 10px 12px; border: 1px solid #e2e8f0; border-radius: 10px;
      cursor: pointer; transition: all .12s ease;
    }
    .dpd-list li:hover { border-color: #0d9488; background: #f0fdfa; }
    .dpd-list li.selected { border-color: #0d9488; background: #ccfbf1; }
    .dpd-icon {
      width: 36px; height: 36px; border-radius: 8px;
      background: #f1f5f9; color: #0d9488;
      display: grid; place-items: center; font-size: 16px; flex-shrink: 0;
    }
    .dpd-info { flex: 1; min-width: 0; }
    .dpd-title { font-weight: 600; font-size: 14px; overflow: hidden;
      text-overflow: ellipsis; white-space: nowrap; }
    .dpd-meta { display: flex; gap: 8px; font-size: 12px; color: #64748b; margin-top: 2px; }
    .dpd-ref { font-family: 'Courier New', monospace; }
    .dpd-type { color: #0d9488; font-weight: 600; }
    .dpd-check { color: #0d9488; opacity: 0; }
    .dpd-list li.selected .dpd-check { opacity: 1; }
  `]
})
export class DocumentPickerDialogComponent implements OnChanges {
  private documentService = inject(DocumentService);
  private stock = inject(StockService);
  private msgs = inject(MessageService);

  /** Project whose STOCK/BL folder will be listed. */
  @Input({ required: true }) projectId!: string;
  /** Folder kind: 'BL' (default), 'BC', 'FACTURE', 'AUTRE'. Controls which subfolder is listed. */
  @Input() kind: string = 'BL';
  /** Two-way bound visibility (p-dialog pattern). */
  @Input() visible: boolean = false;
  /** Emitted when the user confirms a selection. */
  @Output() picked = new EventEmitter<string>();
  /** Emitted when the dialog closes without a selection. */
  @Output() visibleChange = new EventEmitter<boolean>();

  loading = signal(true);
  error = signal<string | null>(null);
  documents = signal<Document[]>([]);
  selectedId = signal<string | null>(null);
  search = '';

  filtered = computed(() => {
    const q = this.search.trim().toLowerCase();
    return this.documents().filter(d => {
      if (!q) return true;
      return (d.title ?? '').toLowerCase().includes(q) ||
             (d.reference ?? '').toLowerCase().includes(q);
    });
  });

  ngOnChanges(): void {
    if (this.visible && this.projectId) {
      this.loadDocuments();
    }
  }

  /** Resolve-or-create the STOCK/BL folder, then list its documents. */
  private loadDocuments(): void {
    this.loading.set(true);
    this.error.set(null);
    this.documents.set([]);
    this.selectedId.set(null);
    this.search = '';

    this.stock.ensureStockFolder(this.projectId, (this.kind || 'BL') as StockDocKind).subscribe(folder => {
      if (!folder?.folderId) {
        this.loading.set(false);
        this.error.set('Impossible de résoudre le dossier STOCK / ' + (this.kind || 'BL') + ' du projet.');
        return;
      }
      // List documents in that folder (size 50 — enough for a picker)
      this.documentService.search(this.projectId, {
        folderId: folder.folderId,
        size: 50,
      }).subscribe({
        next: page => {
          this.documents.set(page.items ?? []);
          this.loading.set(false);
        },
        error: () => {
          this.loading.set(false);
          this.error.set('Impossible de charger les documents du dossier STOCK / ' + (this.kind || 'BL') + '.');
        },
      });
    });
  }

  pick(d: Document): void {
    this.selectedId.set(d.id);
  }

  confirm(): void {
    const id = this.selectedId();
    if (!id) return;
    this.picked.emit(id);
    this.visible = false;
    this.visibleChange.emit(false);
  }

  cancel(): void {
    this.visible = false;
    this.visibleChange.emit(false);
  }

  onHide(): void {
    this.visible = false;
    this.visibleChange.emit(false);
  }
}
