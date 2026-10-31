#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Phase B (DOC-ACTIONS-UX) — fix design conflicts on documents list + document detail.

Problems:
  C1 — documents.component: .c-actions column = 132px, but the shared component
       renders TWO text buttons ("Ouvrir" + "Télécharger") → ~300px content in
       132px → row overflow, grid breaks.
  C2 — document-detail.component: .act column = 44px (sized for ONE icon button),
       now holds the shared component (two text buttons, ~140px) → massive overflow.
  C3 — Visual inconsistency: shared component buttons have text labels + size="small",
       surrounding buttons are icon-only [rounded] [text]. Mixed styles look messy.

Fix:
  1) Shared component: add @Input() variant: 'compact' | 'full' (default 'full').
     - compact → icon-only buttons with tooltips (for dense table rows)
     - full → text + icon buttons (for detail pages with space)
  2) Documents list: use variant="compact", widen .c-actions from 132px → 150px,
     add title attributes to remaining icon buttons (Voir, Refresh, Delete).
  3) Document detail: use variant="compact", widen .act from 44px → 80px.

Idempotent: refuses to re-patch files that already contain the sentinel
`// PHASE-B-DOC-ACTIONS-UX`.
"""
from pathlib import Path
import sys

ROOT = Path('/tmp/my-project')
FRONT = ROOT / 'frontend/src/app'
SENTINEL = '// PHASE-B-DOC-ACTIONS-UX'

def read(p): return p.read_text(encoding='utf-8')
def write(p, s): p.write_text(s, encoding='utf-8')
def patch_once(p, find, replace, label):
    src = read(p)
    if SENTINEL in src:
        print(f"[SKIP] {label}: already patched ({p.relative_to(ROOT)})")
        return
    if find not in src:
        print(f"[FAIL] {label}: pattern not found in {p.relative_to(ROOT)}", file=sys.stderr)
        sys.exit(1)
    src = src.replace(find, replace, 1)
    write(p, src)
    print(f"[OK]   {label}: patched ({p.relative_to(ROOT)})")

# ============================================================================
# 1) SHARED COMPONENT — add variant input (compact vs full)
# ============================================================================
SHARED_NEW = '''import { Component, Input, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ButtonModule } from 'primeng/button';
import { TooltipModule } from 'primeng/tooltip';
import { MessageService } from 'primeng/api';
import { DocumentService, OpenOutcome, isPreviewable } from '@app/core/services/document.service';

/**
 * PHASE-B-DOC-ACTIONS — Shared document open/download actions.
 * PHASE-B-DOC-ACTIONS-UX — added variant input (compact vs full).
 *
 * Two visual variants:
 *   - variant="compact" (default for table rows): icon-only buttons with
 *     tooltips. Fits in a narrow grid column (80–150px). Used by the
 *     documents list and the document-detail revision history.
 *   - variant="full" (default for detail pages): text + icon buttons.
 *     Used by facture/BL/BC/cash linked-doc blocks where there is space.
 *
 * Both variants share the same behavior:
 *   - type="button" (never form-submit)
 *   - "Ouvrir" disabled when not previewable, with a tooltip explaining why
 *   - "Télécharger" always enabled when documentId is present
 *   - loading spinner on click
 *   - French error toasts via global <p-toast>
 *   - JSON-error blob guard (never saves JSON as .pdf)
 *   - popup-blocked fallback to download
 */
@Component({
  selector: 'app-document-file-actions',
  standalone: true,
  imports: [CommonModule, ButtonModule, TooltipModule],
  template: `
    <span class="dfa" role="group" aria-label="Actions sur le fichier">
      @if (variant === 'compact') {
        <!-- COMPACT: icon-only buttons with tooltips — for dense table rows -->
        <button pButton type="button"
                class="dfa-btn dfa-open"
                icon="pi pi-external-link"
                [text]="true" [rounded]="true" size="small"
                severity="info"
                [disabled]="!previewable() || loading()"
                [loading]="loading() && opening()"
                [pTooltip]="openTooltip()"
                tooltipPosition="top"
                (click)="open()">
        </button>
        <button pButton type="button"
                class="dfa-btn dfa-download"
                icon="pi pi-download"
                [text]="true" [rounded]="true" size="small"
                [disabled]="!documentId || loading()"
                [loading]="loading() && downloading()"
                pTooltip="Télécharger le fichier"
                tooltipPosition="top"
                (click)="download()">
        </button>
      } @else {
        <!-- FULL: text + icon buttons — for detail pages with space -->
        <button pButton type="button"
                class="dfa-btn dfa-open"
                label="Ouvrir"
                icon="pi pi-external-link"
                severity="info" [text]="true" size="small"
                [disabled]="!previewable() || loading()"
                [loading]="loading() && opening()"
                [pTooltip]="openTooltip()"
                tooltipPosition="top"
                (click)="open()">
        </button>
        <button pButton type="button"
                class="dfa-btn dfa-download"
                label="Télécharger"
                icon="pi pi-download"
                [text]="true" size="small"
                [disabled]="!documentId || loading()"
                [loading]="loading() && downloading()"
                (click)="download()">
        </button>
      }
    </span>
  `,
  styles: [`
    .dfa { display: inline-flex; align-items: center; gap: 2px; white-space: nowrap; }
    .dfa-btn { font-size: 13px; font-weight: 600; }
    :host { display: inline-flex; }
  `]
})
export class DocumentFileActionsComponent implements OnInit {
  private documentService = inject(DocumentService);
  private msgs = inject(MessageService);

  /** The document the actions target. Optional — when null/undefined, both buttons are disabled. */
  @Input() documentId?: string | null;
  /** Optional: a specific revision. If omitted, the component loads the latest. */
  @Input() revisionId?: string | null;
  /** Optional: file name hint (used for mime inference + download filename). */
  @Input() fileName?: string | null;
  /** Optional: mime type hint. If null/blank/octet-stream, inferred from fileName. */
  @Input() mimeType?: string | null;
  /** When true (default), loads the latest revision on init if revisionId is not provided. */
  @Input() autoLoadLatest = true;
  /** PHASE-B-DOC-ACTIONS-UX: visual variant. 'compact' = icon-only (table rows), 'full' = text+icon (detail pages). */
  @Input() variant: 'compact' | 'full' = 'full';

  private _latestRevId = signal<string | null>(null);
  private _latestFileName = signal<string | null>(null);
  private _latestMime = signal<string | null>(null);

  loading = signal(false);
  opening = signal(false);
  downloading = signal(false);

  /** Effective revision id (explicit input wins, otherwise the loaded latest). */
  effectiveRevId = computed(() => this.revisionId ?? this._latestRevId());
  effectiveFileName = computed(() => this.fileName ?? this._latestFileName() ?? 'document');
  effectiveMime = computed(() => this.resolveMime());
  previewable = computed(() => isPreviewable(this.effectiveMime()));

  ngOnInit(): void {
    if (this.documentId && !this.revisionId && this.autoLoadLatest) {
      this.loadLatest();
    }
  }

  private loadLatest(): void {
    if (!this.documentId) { return; }
    this.loading.set(true);
    this.documentService.revisions(this.documentId).subscribe({
      next: list => {
        const latest = list && list.length ? list[list.length - 1] : null;
        if (latest) {
          this._latestRevId.set(latest.id);
          this._latestFileName.set(latest.fileName ?? null);
          this._latestMime.set(latest.mimeType ?? null);
        }
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        // Don't toast on init — the buttons stay disabled with a tooltip.
      }
    });
  }

  /** Resolve mime: explicit input → inferred from fileName → null. */
  private resolveMime(): string | null {
    const explicit = (this.mimeType ?? this._latestMime() ?? '').trim().toLowerCase();
    if (explicit && explicit !== 'application/octet-stream' && explicit !== 'application/x-www-form-urlencoded') {
      return explicit;
    }
    const name = this.effectiveFileName();
    return inferMimeFromExtension(name);
  }

  openTooltip(): string {
    if (this.loading()) return 'Chargement…';
    if (!this.documentId) return 'Aucun document lié.';
    if (!this.previewable()) {
      return 'Aperçu non disponible pour ce type (ex. PSD, Excel). Utilisez Télécharger.';
    }
    return 'Ouvrir dans un nouvel onglet';
  }

  open(): void {
    if (!this.documentId || !this.previewable() || this.loading()) return;
    const revId = this.effectiveRevId();
    if (!revId) {
      this.msgs.add({ severity: 'warn', summary: 'Aucune révision', detail: 'Le document n\\'a pas encore de révision.' });
      return;
    }
    this.opening.set(true);
    this.loading.set(true);
    this.documentService.openRevision(this.documentId, revId, this.effectiveFileName()).subscribe({
      next: o => {
        this.opening.set(false);
        this.loading.set(false);
        this.onOutcome(o, 'Ouverture');
      },
      error: () => {
        this.opening.set(false);
        this.loading.set(false);
        this.msgs.add({ severity: 'error', summary: 'Ouverture impossible', detail: 'Erreur inattendue.' });
      }
    });
  }

  download(): void {
    if (!this.documentId || this.loading()) return;
    const revId = this.effectiveRevId();
    if (!revId) {
      this.msgs.add({ severity: 'warn', summary: 'Aucune révision', detail: 'Le document n\\'a pas encore de révision.' });
      return;
    }
    this.downloading.set(true);
    this.loading.set(true);
    this.documentService.downloadRevision(this.documentId, revId).subscribe({
      next: blob => {
        // JSON-error blob guard: if the API answered 4xx/5xx with JSON, the blob's
        // type will be application/json (or empty). Never save that as a .pdf.
        const mime = (blob.type || '').toLowerCase();
        if (mime.startsWith('application/json') || mime === 'text/json' || mime === '') {
          blob.text().then(txt => {
            let msg = 'Fichier introuvable ou accès refusé.';
            try { const j = JSON.parse(txt); msg = j?.message ?? j?.detail ?? j?.code ?? msg; } catch { /* default */ }
            this.msgs.add({ severity: 'error', summary: 'Téléchargement impossible', detail: msg });
          });
          this.downloading.set(false);
          this.loading.set(false);
          return;
        }
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = this.effectiveFileName() || 'document';
        a.rel = 'noopener';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        setTimeout(() => URL.revokeObjectURL(url), 5_000);
        this.downloading.set(false);
        this.loading.set(false);
      },
      error: err => {
        this.downloading.set(false);
        this.loading.set(false);
        const body = err?.error;
        let msg = '';
        if (typeof body === 'string' && body.trim()) msg = body;
        else if (body && typeof body === 'object') msg = body.message ?? body.detail ?? body.code ?? '';
        if (!msg) {
          const s = err?.status;
          msg = s === 404 ? 'Fichier introuvable.'
              : s === 403 ? 'Accès refusé.'
              : s === 401 ? 'Session expirée, veuillez vous reconnecter.'
              : 'Téléchargement impossible.';
        }
        this.msgs.add({ severity: 'error', summary: 'Téléchargement impossible', detail: msg });
      }
    });
  }

  private onOutcome(o: OpenOutcome, verb: 'Ouverture' | 'Téléchargement'): void {
    if (o.kind === 'error') {
      this.msgs.add({ severity: 'error', summary: verb + ' impossible', detail: o.message });
    } else if (o.kind === 'downloaded' && o.reason === 'popup-blocked') {
      this.msgs.add({
        severity: 'info', summary: 'Fenêtre bloquée',
        detail: 'Le fichier a été téléchargé à la place.'
      });
    }
  }
}

/**
 * Local filename-extension → MIME inference. Mirrors the backend's
 * resolveContentType so the Ouvrir button's enabled state matches what
 * the server will actually serve. PSD/Excel/etc. → null (not previewable).
 */
function inferMimeFromExtension(fileName: string | null | undefined): string | null {
  if (!fileName) return null;
  const lower = fileName.toLowerCase();
  if (lower.endsWith('.pdf'))  return 'application/pdf';
  if (lower.endsWith('.png'))  return 'image/png';
  if (lower.endsWith('.jpg') || lower.endsWith('.jpeg')) return 'image/jpeg';
  if (lower.endsWith('.gif'))  return 'image/gif';
  if (lower.endsWith('.webp')) return 'image/webp';
  if (lower.endsWith('.heic') || lower.endsWith('.heif')) return 'image/heic';
  // PSD, Excel, Word, DWG, etc. → null (browser cannot inline-preview them).
  return null;
}
'''

def patch_shared_component():
    p = FRONT / 'shared/document-file-actions.component.ts'
    src = read(p)
    if SENTINEL in src:
        print(f"[SKIP] shared component: already patched with variant")
        return
    write(p, SHARED_NEW)
    print(f"[OK]   shared component: rewritten with variant input ({p.relative_to(ROOT)})")

# ============================================================================
# 2) DOCUMENTS LIST — use variant="compact" + widen .c-actions + add titles
# ============================================================================
def patch_documents_list():
    html = FRONT / 'features/document/documents.component.html'
    scss = FRONT / 'features/document/documents.component.scss'

    # HTML: add variant="compact" to the shared component + title attrs to icons
    src_html = read(html)
    if SENTINEL in src_html:
        print(f"[SKIP] documents.component.html: already patched")
    else:
        old = '''              <span class="c-actions">
                <p-button icon="pi pi-eye" [rounded]="true" [text]="true"
                          (onClick)="view(d)" [attr.aria-label]="'Voir ' + d.reference" />
                <!-- PHASE-B-DOC-ACTIONS: shared Ouvrir/Télécharger (loads latest rev on init) -->
                <app-document-file-actions [documentId]="d.id" [autoLoadLatest]="true" />
                @if (canUpload()) {
                  <p-button icon="pi pi-refresh" [rounded]="true" [text]="true"
                            (onClick)="openRev(d)" [attr.aria-label]="'Nouvelle révision ' + d.reference" />
                }
                @if (canDelete()) {
                  <p-button icon="pi pi-trash" [rounded]="true" [text]="true" severity="danger"
                            (onClick)="confirmRemove(d)" [attr.aria-label]="'Supprimer ' + d.reference" />
                }
              </span>'''
        new = '''              <span class="c-actions">
                <!-- PHASE-B-DOC-ACTIONS-UX: compact variant for the table row -->
                <p-button icon="pi pi-eye" [rounded]="true" [text]="true" size="small"
                          (onClick)="view(d)" [attr.aria-label]="'Voir ' + d.reference"
                          pTooltip="Voir les détails" tooltipPosition="top" />
                <app-document-file-actions [documentId]="d.id" [autoLoadLatest]="true"
                                           variant="compact" />
                @if (canUpload()) {
                  <p-button icon="pi pi-refresh" [rounded]="true" [text]="true" size="small"
                            (onClick)="openRev(d)" [attr.aria-label]="'Nouvelle révision ' + d.reference"
                            pTooltip="Nouvelle révision" tooltipPosition="top" />
                }
                @if (canDelete()) {
                  <p-button icon="pi pi-trash" [rounded]="true" [text]="true" size="small" severity="danger"
                            (onClick)="confirmRemove(d)" [attr.aria-label]="'Supprimer ' + d.reference"
                            pTooltip="Supprimer" tooltipPosition="top" />
                }
              </span>'''
        if old not in src_html:
            print(f"[FAIL] documents.component.html c-actions block not found", file=sys.stderr)
            sys.exit(1)
        src_html = src_html.replace(old, new, 1)
        # Also add TooltipModule to the imports
        if "TooltipModule" not in src_html:
            # The imports are in the .ts file, not .html. We'll patch the .ts below.
            pass
        write(html, src_html)
        print(f"[OK]   documents.component.html: patched ({html.relative_to(ROOT)})")

    # TS: add TooltipModule to imports
    ts = FRONT / 'features/document/documents.component.ts'
    src_ts = read(ts)
    if 'TooltipModule' not in src_ts:
        old_import = "import { ChipModule } from 'primeng/chip';"
        new_import = "import { ChipModule } from 'primeng/chip';\nimport { TooltipModule } from 'primeng/tooltip'; // PHASE-B-DOC-ACTIONS-UX"
        if old_import not in src_ts:
            print(f"[FAIL] documents.component.ts ChipModule import not found", file=sys.stderr)
            sys.exit(1)
        src_ts = src_ts.replace(old_import, new_import, 1)
        old_arr = "DialogModule, TagModule, ChipModule, MultiSelectModule, FileUploadModule,"
        new_arr = "DialogModule, TagModule, ChipModule, MultiSelectModule, FileUploadModule,\n    TooltipModule,"
        if old_arr in src_ts:
            src_ts = src_ts.replace(old_arr, new_arr, 1)
        write(ts, src_ts)
        print(f"[OK]   documents.component.ts: added TooltipModule ({ts.relative_to(ROOT)})")

    # SCSS: widen .c-actions from 132px → 150px
    src_scss = read(scss)
    if '150px 1.1fr' in src_scss or 'grid-template-columns: 130px 2.2fr 1fr 90px 120px 1.1fr 110px 150px' in src_scss:
        print(f"[SKIP] documents.component.scss: already widened")
    else:
        old_grid = 'grid-template-columns: 130px 2.2fr 1fr 90px 120px 1.1fr 110px 132px;'
        new_grid = 'grid-template-columns: 130px 2.2fr 1fr 90px 120px 1.1fr 110px 150px; /* PHASE-B-DOC-ACTIONS-UX: widened for compact shared component */'
        if old_grid not in src_scss:
            print(f"[FAIL] documents.component.scss grid template not found", file=sys.stderr)
            sys.exit(1)
        src_scss = src_scss.replace(old_grid, new_grid, 1)
        write(scss, src_scss)
        print(f"[OK]   documents.component.scss: widened .c-actions 132px → 150px ({scss.relative_to(ROOT)})")

# ============================================================================
# 3) DOCUMENT DETAIL — use variant="compact" + widen .act column
# ============================================================================
def patch_document_detail():
    html = FRONT / 'features/document/document-detail.component.html'
    scss = FRONT / 'features/document/document-detail.component.scss'
    ts   = FRONT / 'features/document/document-detail.component.ts'

    # HTML: add variant="compact"
    src_html = read(html)
    if SENTINEL in src_html:
        print(f"[SKIP] document-detail.component.html: already patched")
    else:
        old = '''              <span class="act">
                <!-- PHASE-B-DOC-ACTIONS: shared Ouvrir/Télécharger per revision -->
                <app-document-file-actions
                  [documentId]="docId!"
                  [revisionId]="r.id"
                  [fileName]="r.fileName"
                  [mimeType]="r.mimeType"
                  [autoLoadLatest]="false" />
              </span>'''
        new = '''              <span class="act">
                <!-- PHASE-B-DOC-ACTIONS-UX: compact variant for the revision row -->
                <app-document-file-actions
                  [documentId]="docId!"
                  [revisionId]="r.id"
                  [fileName]="r.fileName"
                  [mimeType]="r.mimeType"
                  [autoLoadLatest]="false"
                  variant="compact" />
              </span>'''
        if old not in src_html:
            print(f"[FAIL] document-detail.component.html act block not found", file=sys.stderr)
            sys.exit(1)
        src_html = src_html.replace(old, new, 1)
        write(html, src_html)
        print(f"[OK]   document-detail.component.html: patched ({html.relative_to(ROOT)})")

    # SCSS: widen .act from 44px → 80px (fits two compact icon buttons)
    src_scss = read(scss)
    if 'grid-template-columns: 110px 1.6fr 90px 1.2fr 110px 80px' in src_scss:
        print(f"[SKIP] document-detail.component.scss: already widened")
    else:
        old_grid = 'grid-template-columns: 110px 1.6fr 90px 1.2fr 110px 44px;'
        new_grid = 'grid-template-columns: 110px 1.6fr 90px 1.2fr 110px 80px; /* PHASE-B-DOC-ACTIONS-UX: widened for compact shared component */'
        if old_grid not in src_scss:
            print(f"[FAIL] document-detail.component.scss grid template not found", file=sys.stderr)
            sys.exit(1)
        src_scss = src_scss.replace(old_grid, new_grid, 1)
        # Also fix the mobile responsive breakpoint
        old_mobile = '.rrow { grid-template-columns: 100px 1fr 44px; }'
        new_mobile = '.rrow { grid-template-columns: 100px 1fr 80px; } /* PHASE-B-DOC-ACTIONS-UX: widened for compact shared component */'
        if old_mobile in src_scss:
            src_scss = src_scss.replace(old_mobile, new_mobile, 1)
        write(scss, src_scss)
        print(f"[OK]   document-detail.component.scss: widened .act 44px → 80px ({scss.relative_to(ROOT)})")

# ============================================================================
def main():
    patch_shared_component()
    patch_documents_list()
    patch_document_detail()
    print("\nAll Phase B (DOC-ACTIONS-UX) patches applied.")

if __name__ == '__main__':
    main()
