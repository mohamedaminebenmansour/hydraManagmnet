"""Phase B7 — stock-moves.component.ts surgical edits.

1. Replace the `—` in the Pièce column with "Aucun justificatif" muted chip.
2. Remove the N+1 `preloadDocCounts()` loop — use `m.linkedDocCount` from the
   enriched MoveResponse instead.
3. Add "Ajouter un justificatif" button in the docs dialog (gated by STOCK_MOVE).
4. Wire the document picker dialog to the "Ajouter" button → linkDocument.
"""
from pathlib import Path

p = Path('/tmp/my-project/frontend/src/app/features/stock/stock-moves.component.ts')
s = p.read_text(encoding='utf-8')

# ─── Edit 1: Add DocumentPickerDialogComponent import ───────────────────
old_import = """import { TooltipModule } from 'primeng/tooltip';

import { StockService } from '@app/core/services/stock.service';"""
new_import = """import { TooltipModule } from 'primeng/tooltip';

import { StockService } from '@app/core/services/stock.service';
import { DocumentPickerDialogComponent } from './document-picker-dialog.component';"""
assert old_import in s, "import block not found"
s = s.replace(old_import, new_import, 1)

# Add to @Component imports array
old_comp = """  imports: [FormsModule, RouterLink, ButtonModule, SelectModule, DialogModule, TooltipModule],"""
new_comp = """  imports: [FormsModule, RouterLink, ButtonModule, SelectModule, DialogModule, TooltipModule, DocumentPickerDialogComponent],"""
assert old_comp in s, "@Component imports not found"
s = s.replace(old_comp, new_comp, 1)

# ─── Edit 2: Replace the Pièce column ──────────────────────────────────
# Use m.linkedDocCount from the enriched response instead of docCounts()
old_col = """                  <td>
                    @if (m.documentId || docCounts()[m.id]) {
                      <button pButton [text]="true" icon="pi pi-paperclip" severity="info"
                              [label]="(docCounts()[m.id] ?? (m.documentId ? 1 : 0)).toString()"
                              pTooltip="Voir les pièces jointes" tooltipPosition="left"
                              (click)="showDocs(m)"></button>
                    } @else {
                      <span class="c-nodoc">—</span>
                    }
                  </td>"""
new_col = """                  <td>
                    @if ((m.linkedDocCount ?? 0) > 0 || m.documentId) {
                      <button pButton [text]="true" icon="pi pi-paperclip" severity="info"
                              [label]="((m.linkedDocCount ?? 0) + (m.documentId ? 1 : 0)).toString()"
                              pTooltip="Voir les pièces jointes" tooltipPosition="left"
                              (click)="showDocs(m)"></button>
                    } @else {
                      <span class="c-nodoc" pTooltip="Aucun justificatif" tooltipPosition="left">
                        <i class="pi pi-minus-circle"></i>
                      </span>
                    }
                  </td>"""
assert old_col in s, "Pièce column not found"
s = s.replace(old_col, new_col, 1)

# ─── Edit 3: Add "Ajouter un justificatif" button in the docs dialog ────
# Find the docs dialog footer and add the button before it
old_dialog_footer = """        <ng-template pTemplate="footer">
          <p-button label="Fermer" [text]="true" (onClick)="docsVisible.set(false)" />
        </ng-template>
      </p-dialog>

      <!-- Documents dialog -->"""
new_dialog_footer = """        @if (canMove()) {
          <div class="dialog-add-action">
            <button pButton label="Ajouter un justificatif" icon="pi pi-plus"
                    severity="success" [text]="true"
                    (click)="openAddDocPicker()"></button>
          </div>
        }
        <ng-template pTemplate="footer">
          <p-button label="Fermer" [text]="true" (onClick)="docsVisible.set(false)" />
        </ng-template>
      </p-dialog>

      <!-- Document picker for attaching an existing doc to a move (junction link) -->
      @if (docsTargetMove()) {
        <app-document-picker-dialog [projectId]="docsTargetMove()!.projectId ?? ''"
                                    [(visible)]="docPickerVisible"
                                    (picked)="onAddDocPicked($event)" />
      }"""
assert old_dialog_footer in s, "dialog footer not found"
s = s.replace(old_dialog_footer, new_dialog_footer, 1)

# ─── Edit 4: Add new signals + methods for the add-doc picker ──────────
old_signals = """  // Reverse dialog state
  reverseVisible = signal(false);
  reverseTarget = signal<StockMove | null>(null);
  reverseReason = '';
  reversing = signal(false);

  // Documents dialog state
  docsVisible = signal(false);
  docsLoading = signal(false);
  linkedDocs = signal<LinkedDocument[]>([]);
  // Cache counts per move so the badge stays cheap across pages
  docCounts = signal<Record<string, number>>({});"""
new_signals = """  // Reverse dialog state
  reverseVisible = signal(false);
  reverseTarget = signal<StockMove | null>(null);
  reverseReason = '';
  reversing = signal(false);

  // Documents dialog state
  docsVisible = signal(false);
  docsLoading = signal(false);
  linkedDocs = signal<LinkedDocument[]>([]);

  // Add-document picker state (for linking an existing doc to a move via junction)
  docPickerVisible = signal(false);
  docsTargetMove = signal<StockMove | null>(null);"""
assert old_signals in s, "signals block not found"
s = s.replace(old_signals, new_signals, 1)

# ─── Edit 5: Remove the N+1 preloadDocCounts method ─────────────────────
old_preload = """  /** Pre-fetch document counts for the current page (so badges show without a click). */
  private preloadDocCounts(): void {
    for (const m of this.moves()) {
      if (this.docCounts()[m.id] != null) continue;
      this.stock.documentsOf(m.id).subscribe(docs => {
        this.docCounts.update(m2 => ({ ...m2, [m.id]: docs.length }));
      });
    }
  }
}"""
new_preload = """  /**
   * Open the document picker to attach an existing doc to the move currently
   * shown in the docs dialog. Requires STOCK_MOVE perm (UI-gated) and a
   * projectId on the move (SITE moves have one; CENTRAL moves may not).
   */
  openAddDocPicker(): void {
    const m = this.docsTargetMove();
    if (!m?.projectId) {
      this.msgs.add({
        severity: 'warn', summary: 'Projet requis',
        detail: 'Ce mouvement n\\'est pas rattaché à un projet. Impossible d\\'ajouter un justificatif ici.',
      });
      return;
    }
    this.docPickerVisible.set(true);
  }

  /** Called when the picker emits a selected documentId → link via junction. */
  onAddDocPicked(docId: string): void {
    const m = this.docsTargetMove();
    if (!m) return;
    this.stock.linkDocument(m.id, { documentId: docId }).subscribe(ok => {
      if (ok) {
        this.msgs.add({
          severity: 'success', summary: 'Justificatif lié',
          detail: 'Le document a été rattaché au mouvement.',
        });
        // Refresh the docs list so the new link appears immediately
        this.showDocs(m);
      }
    });
  }
}"""
assert old_preload in s, "preloadDocCounts not found"
s = s.replace(old_preload, new_preload, 1)

# ─── Edit 6: Update showDocs to set docsTargetMove ──────────────────────
old_show = """  /** Show linked documents for a move (fetches lazily). */
  showDocs(m: StockMove): void {
    this.linkedDocs.set([]);
    this.docsVisible.set(true);
    this.docsLoading.set(true);
    this.stock.documentsOf(m.id).subscribe(docs => {
      this.linkedDocs.set(docs);
      this.docCounts.update(m2 => ({ ...m2, [m.id]: docs.length }));
      this.docsLoading.set(false);
    });
  }"""
new_show = """  /** Show linked documents for a move (fetches lazily). */
  showDocs(m: StockMove): void {
    this.docsTargetMove.set(m);
    this.linkedDocs.set([]);
    this.docsVisible.set(true);
    this.docsLoading.set(true);
    this.stock.documentsOf(m.id).subscribe(docs => {
      this.linkedDocs.set(docs);
      this.docsLoading.set(false);
    });
  }"""
assert old_show in s, "showDocs not found"
s = s.replace(old_show, new_show, 1)

# ─── Edit 7: Remove the preloadDocCounts() call in reload() ─────────────
old_reload = """    this.stock.history({
      itemId: this.itemFilter ?? undefined,
      locationId: this.locationFilter ?? undefined,
      page: this.page(),
      size: this.pageSize,
    }).subscribe(p => {
      this.pageData.set(p);
      this.loading.set(false);
      // Pre-fetch document counts for the visible moves (cheap)
      this.preloadDocCounts();
    });
  }"""
new_reload = """    this.stock.history({
      itemId: this.itemFilter ?? undefined,
      locationId: this.locationFilter ?? undefined,
      page: this.page(),
      size: this.pageSize,
    }).subscribe(p => {
      this.pageData.set(p);
      this.loading.set(false);
      // No N+1: linkedDocCount comes from the enriched history() SQL response.
    });
  }"""
assert old_reload in s, "reload() not found"
s = s.replace(old_reload, new_reload, 1)

# ─── Edit 8: Add styles for the "Ajouter un justificatif" action ────────
old_style = """    .docs-loading, .docs-empty {
      text-align: center; padding: 30px 20px; color: #64748b;
      display: flex; flex-direction: column; align-items: center; gap: 8px;
    }"""
new_style = """    .docs-loading, .docs-empty {
      text-align: center; padding: 30px 20px; color: #64748b;
      display: flex; flex-direction: column; align-items: center; gap: 8px;
    }
    .dialog-add-action {
      padding: 8px 0 12px;
      border-top: 1px solid #f1f5f9;
      margin-top: 8px;
    }
    .c-nodoc i { font-size: 12px; }"""
assert old_style in s, "docs-loading style not found"
s = s.replace(old_style, new_style, 1)

# ─── Edit 9: Add MessageService import (for the toast in onAddDocPicked) ─
old_msg_import = """import { StockService } from '@app/core/services/stock.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';"""
new_msg_import = """import { StockService } from '@app/core/services/stock.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { MessageService } from 'primeng/api';"""
assert old_msg_import in s, "stock import block not found"
s = s.replace(old_msg_import, new_msg_import, 1)

# Add the inject
old_inject = """  private stock = inject(StockService);
  private auth = inject(AuthService);"""
new_inject = """  private stock = inject(StockService);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);"""
assert old_inject in s, "inject block not found"
s = s.replace(old_inject, new_inject, 1)

p.write_text(s, encoding='utf-8')
print(f"OK  stock-moves.component.ts — 9 surgical edits applied ({len(s)} chars)")
