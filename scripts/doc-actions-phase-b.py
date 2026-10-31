#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Phase B (DOC-ACTIONS) — file patcher for HYDRA at /tmp/my-project.

Fixes the production document open/download bugs:

  R1 — No <p-toast> anywhere → all error toasts silent.
        FIX: add <p-toast> + ToastModule to app.component.{html,ts}.

  R2 — Documents-list "Ouvrir" button [disabled] on first render
       (canPreview reads a signal that's only populated inside openDoc,
       which can't fire because the button is disabled).
       FIX: shared component loads latest revision on init (background),
       then enables/disables Ouvrir based on the actual mime type.

  R3 — Télécharger calls downloadRevision directly, bypassing the
       JSON-error blob guard → silently saves JSON as .pdf on API error.
       FIX: shared component uses DocumentService.downloadDocument /
       downloadRevision through a guarded path.

  R4 — Buttons are icon-only, no French labels.
       FIX: shared component shows text labels "Ouvrir" + "Télécharger".

  R5 — No loading state.
       FIX: shared component shows [loading] on click.

  R6 — Backend serves null/octet-stream Content-Type for legacy uploads
       → window.open(blobUrl) triggers download instead of inline preview.
       FIX: DocumentController infers Content-Type from fileName extension
       when revision.mimeType is null/blank/octet-stream.

Idempotent: refuses to re-patch files that already contain the sentinel
`// PHASE-B-DOC-ACTIONS` (FE) or `// Phase B (DOC-ACTIONS)` (BE).
"""
from pathlib import Path
import sys, re

ROOT = Path('/tmp/my-project')
FRONT = ROOT / 'frontend/src/app'
BACK  = ROOT / 'backend/src/main/java/tn/hydra'

SENTINEL_BE  = '// Phase B (DOC-ACTIONS)'
SENTINEL_FE  = '// PHASE-B-DOC-ACTIONS'

def read(p):  return p.read_text(encoding='utf-8')
def write(p, s): p.write_text(s, encoding='utf-8')
def patch_once(p, find, replace, sentinel, label):
    src = read(p)
    if sentinel in src:
        print(f"[SKIP] {label}: already patched ({p.relative_to(ROOT)})")
        return
    if find not in src:
        print(f"[FAIL] {label}: pattern not found in {p.relative_to(ROOT)}", file=sys.stderr)
        sys.exit(1)
    src = src.replace(find, replace, 1)
    write(p, src)
    print(f"[OK]   {label}: patched ({p.relative_to(ROOT)})")

# ============================================================================
# 1) BACKEND — DocumentController: infer Content-Type from fileName
# ============================================================================
def patch_backend():
    p = BACK / 'document/controller/DocumentController.java'
    src = read(p)
    if SENTINEL_BE in src:
        print(f"[SKIP] BE DocumentController: already patched")
        return

    # Replace the existing download() body to use effectiveContentType
    old_download = '''        DownloadFile file = revisions.download(auth, id, revId);
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        // Phase B (DOC-VIEW): inline for browser-previewable types (PDF + images)
        // so a new-tab/window.open() shows the file; attachment otherwise so the
        // browser saves it with the correct filename. Filename header is RFC 5987.
        String disposition = isInlinePreviewable(file.contentType())
                ? "inline; filename*=UTF-8''" + encoded
                : "attachment; filename*=UTF-8''" + encoded;
        return ResponseEntity.ok()
                .contentType(file.contentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(file.resource());
    }

    /** PDF and any raster image type can be inlined by the browser. */
    private static boolean isInlinePreviewable(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.toLowerCase().trim();
        int semi = ct.indexOf(';');
        if (semi >= 0) { ct = ct.substring(0, semi).trim(); }
        return ct.equals("application/pdf")
                || ct.equals("application/x-pdf")
                || ct.startsWith("image/");
    }
}'''

    new_download = '''        DownloadFile file = revisions.download(auth, id, revId);
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        // Phase B (DOC-ACTIONS): if revision.mimeType is null/blank/octet-stream
        // (common for legacy/curl uploads), infer the real Content-Type from the
        // fileName extension. Without this, a PDF served as octet-stream triggers
        // a download instead of inline preview even when window.open(blobUrl) is used.
        String effectiveContentType = resolveContentType(file.contentType(), file.fileName());
        // inline for browser-previewable types (PDF + images) so a new-tab/window.open()
        // shows the file; attachment otherwise so the browser saves it with the correct
        // filename. Filename header is RFC 5987.
        String disposition = isInlinePreviewable(effectiveContentType)
                ? "inline; filename*=UTF-8''" + encoded
                : "attachment; filename*=UTF-8''" + encoded;
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(effectiveContentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(file.resource());
    }

    /** PDF and any raster image type can be inlined by the browser. */
    private static boolean isInlinePreviewable(String contentType) {
        if (contentType == null) return false;
        String ct = contentType.toLowerCase().trim();
        int semi = ct.indexOf(';');
        if (semi >= 0) { ct = ct.substring(0, semi).trim(); }
        return ct.equals("application/pdf")
                || ct.equals("application/x-pdf")
                || ct.startsWith("image/");
    }

    /**
     * Phase B (DOC-ACTIONS): resolve the effective Content-Type.
     * Returns the stored mimeType when it is informative; otherwise falls back
     * to a filename-extension lookup. Always returns a non-null, non-blank
     * MIME (defaults to {@code application/octet-stream} when nothing fits).
     */
    private static String resolveContentType(String stored, String fileName) {
        if (stored != null) {
            String ct = stored.toLowerCase().trim();
            int semi = ct.indexOf(';');
            if (semi >= 0) { ct = ct.substring(0, semi).trim(); }
            if (!ct.isEmpty() && !ct.equals("application/octet-stream")
                    && !ct.equals("application/x-www-form-urlencoded")
                    && !ct.equals("multipart/form-data")) {
                return ct;
            }
        }
        String inferred = inferContentTypeFromExtension(fileName);
        return inferred != null ? inferred : MediaType.APPLICATION_OCTET_STREAM_VALUE;
    }

    /** Filename-extension → MIME map for the types HYDRA accepts on upload. */
    private static String inferContentTypeFromExtension(String fileName) {
        if (fileName == null) return null;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) return "image/heic";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".xls"))  return "application/vnd.ms-excel";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".doc"))  return "application/msword";
        if (lower.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (lower.endsWith(".ppt"))  return "application/vnd.ms-powerpoint";
        if (lower.endsWith(".csv"))  return "text/csv";
        if (lower.endsWith(".txt"))  return "text/plain";
        if (lower.endsWith(".zip"))  return "application/zip";
        if (lower.endsWith(".msg") || lower.endsWith(".eml")) return "application/vnd.ms-outlook";
        if (lower.endsWith(".dwg") || lower.endsWith(".dxf")) return "application/acad";
        if (lower.endsWith(".ifc"))  return "application/x-step";
        if (lower.endsWith(".psd"))   return "image/vnd.adobe.photoshop";
        return null;
    }
}'''

    if old_download not in src:
        print(f"[FAIL] BE DocumentController download() pattern not found", file=sys.stderr)
        sys.exit(1)
    src = src.replace(old_download, new_download, 1)
    write(p, src)
    print(f"[OK]   BE DocumentController: patched ({p.relative_to(ROOT)})")

# ============================================================================
# 2) FRONTEND — global <p-toast> in app.component
# ============================================================================
def patch_app_component():
    html = FRONT / 'app.component.html'
    ts   = FRONT / 'app.component.ts'

    src_html = read(html)
    if SENTINEL_FE in src_html:
        print(f"[SKIP] app.component.html: already patched")
    else:
        # Replace the lone <router-outlet /> with router-outlet + global toast
        old = '<router-outlet />'
        new = ('<router-outlet />\n'
               '<!-- PHASE-B-DOC-ACTIONS: global toast so MessageService.add() renders app-wide. -->\n'
               '<p-toast position="top-right" />\n')
        if old not in src_html:
            print(f"[FAIL] app.component.html: <router-outlet /> not found", file=sys.stderr)
            sys.exit(1)
        src_html = src_html.replace(old, new, 1)
        write(html, src_html)
        print(f"[OK]   app.component.html: patched ({html.relative_to(ROOT)})")

    src_ts = read(ts)
    if SENTINEL_FE in src_ts:
        print(f"[SKIP] app.component.ts: already patched")
        return
    old_imports = "import { RouterOutlet } from '@angular/router';"
    new_imports = ("import { RouterOutlet } from '@angular/router';\n"
                   "import { ToastModule } from 'primeng/toast'; // PHASE-B-DOC-ACTIONS")
    if old_imports not in src_ts:
        print(f"[FAIL] app.component.ts: RouterOutlet import not found", file=sys.stderr)
        sys.exit(1)
    src_ts = src_ts.replace(old_imports, new_imports, 1)
    old_arr = "imports: [RouterOutlet],"
    new_arr = "imports: [RouterOutlet, ToastModule], // PHASE-B-DOC-ACTIONS"
    if old_arr not in src_ts:
        print(f"[FAIL] app.component.ts: imports array not found", file=sys.stderr)
        sys.exit(1)
    src_ts = src_ts.replace(old_arr, new_arr, 1)
    write(ts, src_ts)
    print(f"[OK]   app.component.ts: patched ({ts.relative_to(ROOT)})")

# ============================================================================
# 3) SHARED COMPONENT — DocumentFileActionsComponent (NEW FILE)
# ============================================================================
SHARED_COMPONENT = '''import { Component, Input, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { ButtonModule } from 'primeng/button';
import { TooltipModule } from 'primeng/tooltip';
import { MessageService } from 'primeng/api';
import { DocumentService, OpenOutcome, isPreviewable } from '@app/core/services/document.service';

/**
 * PHASE-B-DOC-ACTIONS — Shared document open/download actions.
 *
 * Replaces the icon-only buttons that were broken in production (R1-R6).
 * Used by every screen that surfaces a document:
 *   - documents list (latest revision, auto-load on init)
 *   - document detail (one component per revision row)
 *   - facture / BL / BC / cash movement detail (documentId only → latest rev)
 *
 * UX:
 *   - Always shows TEXT labels "Ouvrir" / "Télécharger" (icons beside text).
 *   - Both buttons are type="button" (never form-submit).
 *   - "Ouvrir" is enabled only when the file is browser-previewable
 *     (PDF + image/*). When disabled, a tooltip explains why:
 *     "Aperçu non disponible pour ce type (ex. PSD, Excel). Utilisez Télécharger."
 *   - "Télécharger" is always enabled when a documentId is present.
 *   - Both show a loading spinner while the HTTP call is in flight.
 *   - Errors surface as French toasts via the global <p-toast> (added to
 *     app.component). No silent failures.
 *
 * Mime-type resolution (so a real PDF stored as octet-stream still previews):
 *   1. Caller passes mimeType? → use it directly.
 *   2. mimeType is null/blank/'application/octet-stream' AND fileName has a
 *      known extension → infer locally (.pdf → application/pdf, .png → image/png…).
 *   3. Otherwise treat as non-previewable → Ouvrir disabled with tooltip.
 *
 * Revision resolution:
 *   - Caller passes revisionId → use it directly (no extra HTTP call).
 *   - revisionId not passed → component auto-loads the latest revision on
 *     init via GET /api/documents/{id}/revisions (one HTTP call). Used by
 *     the documents list and by linked-doc surfaces that only know the
 *     documentId.
 */
@Component({
  selector: 'app-document-file-actions',
  standalone: true,
  imports: [CommonModule, ButtonModule, TooltipModule],
  template: `
    <span class="dfa" role="group" aria-label="Actions sur le fichier">
      <button pButton type="button"
              class="dfa-btn dfa-open"
              [label]="previewable() ? 'Ouvrir' : 'Ouvrir'"
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
    </span>
  `,
  styles: [`
    .dfa { display: inline-flex; align-items: center; gap: 4px; white-space: nowrap; }
    .dfa-btn { font-size: 13px; font-weight: 600; }
    :host { display: inline-flex; }
  `]
})
export class DocumentFileActionsComponent implements OnInit {
  private documentService = inject(DocumentService);
  private msgs = inject(MessageService);

  /** The document the actions target. Required. */
  @Input({ required: true }) documentId!: string;
  /** Optional: a specific revision. If omitted, the component loads the latest. */
  @Input() revisionId?: string | null;
  /** Optional: file name hint (used for mime inference + download filename). */
  @Input() fileName?: string | null;
  /** Optional: mime type hint. If null/blank/octet-stream, inferred from fileName. */
  @Input() mimeType?: string | null;
  /** When true (default), loads the latest revision on init if revisionId is not provided. */
  @Input() autoLoadLatest = true;

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
    if (!this.revisionId && this.autoLoadLatest) {
      this.loadLatest();
    }
  }

  private loadLatest(): void {
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

def create_shared_component():
    p = FRONT / 'shared/document-file-actions.component.ts'
    p.parent.mkdir(parents=True, exist_ok=True)
    if p.exists() and SENTINEL_FE in read(p):
        print(f"[SKIP] shared component: already exists")
        return
    write(p, SHARED_COMPONENT)
    print(f"[OK]   shared component: created ({p.relative_to(ROOT)})")

# ============================================================================
# 4) WIRE — documents list (replace inline buttons with <app-document-file-actions>)
# ============================================================================
def patch_documents_list():
    html = FRONT / 'features/document/documents.component.html'
    ts   = FRONT / 'features/document/documents.component.ts'

    src_html = read(html)
    if SENTINEL_FE in src_html:
        print(f"[SKIP] documents.component.html: already patched")
    else:
        old = '''                <!-- PHASE-B-DOC-VIEW: Ouvrir (preview pdf/image in new tab) -->
                <p-button icon="pi pi-external-link" [rounded]="true" [text]="true"
                          severity="info" (onClick)="openDoc(d)"
                          [attr.aria-label]="'Ouvrir ' + d.reference"
                          [disabled]="!canPreview(d)" />
                <p-button icon="pi pi-download" [rounded]="true" [text]="true"
                          (onClick)="download(d)" [attr.aria-label]="'Télécharger ' + d.reference" />'''
        new = '''                <!-- PHASE-B-DOC-ACTIONS: shared Ouvrir/Télécharger (loads latest rev on init) -->
                <app-document-file-actions [documentId]="d.id" [autoLoadLatest]="true" />'''
        if old not in src_html:
            print(f"[FAIL] documents.component.html buttons not found", file=sys.stderr)
            sys.exit(1)
        src_html = src_html.replace(old, new, 1)
        write(html, src_html)
        print(f"[OK]   documents.component.html: patched ({html.relative_to(ROOT)})")

    src_ts = read(ts)
    if SENTINEL_FE in src_ts:
        print(f"[SKIP] documents.component.ts: already patched")
        return
    # Add the import for the shared component
    old_imports_block = "import { EmptyStateComponent } from '@app/shared/empty-state.component';"
    new_imports_block = ("import { EmptyStateComponent } from '@app/shared/empty-state.component';\n"
                         "import { DocumentFileActionsComponent } from '@app/shared/document-file-actions.component'; // PHASE-B-DOC-ACTIONS")
    if old_imports_block not in src_ts:
        print(f"[FAIL] documents.component.ts EmptyState import not found", file=sys.stderr)
        sys.exit(1)
    src_ts = src_ts.replace(old_imports_block, new_imports_block, 1)

    # Add DocumentFileActionsComponent to the standalone imports array
    old_arr = "    ProgressBarModule, EmptyStateComponent\n  ],"
    new_arr = "    ProgressBarModule, EmptyStateComponent,\n    DocumentFileActionsComponent // PHASE-B-DOC-ACTIONS\n  ],"
    if old_arr not in src_ts:
        print(f"[FAIL] documents.component.ts imports array not found", file=sys.stderr)
        sys.exit(1)
    src_ts = src_ts.replace(old_arr, new_arr, 1)
    write(ts, src_ts)
    print(f"[OK]   documents.component.ts: patched ({ts.relative_to(ROOT)})")

# ============================================================================
# 5) WIRE — document-detail (replace per-revision buttons)
# ============================================================================
def patch_document_detail():
    html = FRONT / 'features/document/document-detail.component.html'
    ts   = FRONT / 'features/document/document-detail.component.ts'

    src_html = read(html)
    if SENTINEL_FE in src_html:
        print(f"[SKIP] document-detail.component.html: already patched")
    else:
        old = '''              <span class="act">
                <!-- PHASE-B-DOC-VIEW: Ouvrir (preview pdf/image in new tab) -->
                <p-button icon="pi pi-external-link" [rounded]="true" [text]="true" severity="info"
                          (onClick)="open(r)" [attr.aria-label]="'Ouvrir ' + r.revLabel"
                          [disabled]="!canPreview(r)" />
                <p-button icon="pi pi-download" [rounded]="true" [text]="true"
                          (onClick)="download(r)" [attr.aria-label]="'Télécharger ' + r.revLabel" />
              </span>'''
        new = '''              <span class="act">
                <!-- PHASE-B-DOC-ACTIONS: shared Ouvrir/Télécharger per revision -->
                <app-document-file-actions
                  [documentId]="docId!"
                  [revisionId]="r.id"
                  [fileName]="r.fileName"
                  [mimeType]="r.mimeType"
                  [autoLoadLatest]="false" />
              </span>'''
        if old not in src_html:
            print(f"[FAIL] document-detail.component.html buttons not found", file=sys.stderr)
            sys.exit(1)
        src_html = src_html.replace(old, new, 1)
        write(html, src_html)
        print(f"[OK]   document-detail.component.html: patched ({html.relative_to(ROOT)})")

    src_ts = read(ts)
    if SENTINEL_FE in src_ts:
        print(f"[SKIP] document-detail.component.ts: already patched")
        return
    old_imports_block = "import { EmptyStateComponent } from '@app/shared/empty-state.component';"
    new_imports_block = ("import { EmptyStateComponent } from '@app/shared/empty-state.component';\n"
                         "import { DocumentFileActionsComponent } from '@app/shared/document-file-actions.component'; // PHASE-B-DOC-ACTIONS")
    if old_imports_block not in src_ts:
        print(f"[FAIL] document-detail.component.ts EmptyState import not found", file=sys.stderr)
        sys.exit(1)
    src_ts = src_ts.replace(old_imports_block, new_imports_block, 1)
    old_arr = "    FileUploadModule, ProgressBarModule, EmptyStateComponent\n  ],"
    new_arr = "    FileUploadModule, ProgressBarModule, EmptyStateComponent,\n    DocumentFileActionsComponent // PHASE-B-DOC-ACTIONS\n  ],"
    if old_arr not in src_ts:
        print(f"[FAIL] document-detail.component.ts imports array not found", file=sys.stderr)
        sys.exit(1)
    src_ts = src_ts.replace(old_arr, new_arr, 1)
    write(ts, src_ts)
    print(f"[OK]   document-detail.component.ts: patched ({ts.relative_to(ROOT)})")

# ============================================================================
# 6) WIRE — facture / BL / BC / cash detail screens
#    Replace the inline Ouvrir/Télécharger blocks from Phase B (DOC-VIEW)
#    with the shared component.
# ============================================================================
def _wire_inline_block(file_path: Path, find_html: str, replace_html: str, label: str,
                       ts_import_anchor: str, ts_imports_array: str | None = None) -> None:
    """Generic helper: replace an inline HTML block + add the shared import."""
    src = read(file_path)
    if SENTINEL_FE in src:
        print(f"[SKIP] {label}: already patched")
        return

    if find_html not in src:
        print(f"[FAIL] {label}: HTML anchor not found", file=sys.stderr)
        sys.exit(1)
    src = src.replace(find_html, replace_html, 1)

    # Add import line if a stable anchor is provided
    if ts_import_anchor:
        new_import = (ts_import_anchor + "\n"
                      "import { DocumentFileActionsComponent } from '@app/shared/document-file-actions.component'; // PHASE-B-DOC-ACTIONS")
        if ts_import_anchor not in src:
            print(f"[FAIL] {label}: TS import anchor not found", file=sys.stderr)
            sys.exit(1)
        src = src.replace(ts_import_anchor, new_import, 1)

    # Add to imports array if pattern provided
    if ts_imports_array:
        old_arr, new_arr = ts_imports_array
        if old_arr not in src:
            print(f"[FAIL] {label}: imports array pattern not found", file=sys.stderr)
            sys.exit(1)
        src = src.replace(old_arr, new_arr, 1)

    write(file_path, src)
    print(f"[OK]   {label}: patched ({file_path.relative_to(ROOT)})")

def patch_facture_detail():
    p = FRONT / 'features/stock/facture-detail.component.ts'
    find_html = """            <div class="doc-link">
              <i class="pi pi-file-pdf"></i>
              <span class="doc-label">Justificatif lié</span>
              <button pButton label="Ouvrir" icon="pi pi-external-link" [text]="true" severity="info" (click)="openProof()"></button>
              <button pButton label="Télécharger" icon="pi pi-download" [text]="true" (click)="downloadProof()"></button>
              <!-- PHASE-B-DOC-VIEW -->
            </div>"""
    replace_html = """            <div class="doc-link">
              <i class="pi pi-file-pdf"></i>
              <span class="doc-label">Justificatif lié</span>
              <!-- PHASE-B-DOC-ACTIONS: shared Ouvrir/Télécharger -->
              <app-document-file-actions [documentId]="fac()?.documentId" [autoLoadLatest]="true" />
            </div>"""
    # facture-detail uses RouterLink + ButtonModule + DocumentPickerDialogComponent as imports
    ts_import_anchor = "import { DocumentPickerDialogComponent } from './document-picker-dialog.component';"
    ts_imports_array = ("imports: [RouterLink, ButtonModule, DocumentPickerDialogComponent],",
                        "imports: [RouterLink, ButtonModule, DocumentPickerDialogComponent,\n  DocumentFileActionsComponent], // PHASE-B-DOC-ACTIONS")
    _wire_inline_block(p, find_html, replace_html, "facture-detail",
                       ts_import_anchor, ts_imports_array)

def patch_bl_detail():
    p = FRONT / 'features/stock/bl-detail.component.ts'
    find_html = """            <div class="doc-link">
              <i class="pi pi-file-pdf"></i>
              <span class="doc-label">BL numérisé</span>
              <button pButton label="Ouvrir" icon="pi pi-external-link" [text]="true" severity="info" (click)="openProof()"></button>
              <button pButton label="Télécharger" icon="pi pi-download" [text]="true" (click)="downloadProof()"></button>
              <!-- PHASE-B-DOC-VIEW -->
            </div>"""
    replace_html = """            <div class="doc-link">
              <i class="pi pi-file-pdf"></i>
              <span class="doc-label">BL numérisé</span>
              <!-- PHASE-B-DOC-ACTIONS: shared Ouvrir/Télécharger -->
              <app-document-file-actions [documentId]="bl()?.documentId" [autoLoadLatest]="true" />
            </div>"""
    ts_import_anchor = "import { ButtonModule } from 'primeng/button';"
    ts_imports_array = ("imports: [RouterLink, ButtonModule],",
                        "imports: [RouterLink, ButtonModule, DocumentFileActionsComponent], // PHASE-B-DOC-ACTIONS")
    _wire_inline_block(p, find_html, replace_html, "bl-detail",
                       ts_import_anchor, ts_imports_array)

def patch_bc_detail():
    p = FRONT / 'features/stock/bc-detail.component.ts'
    find_html = """            <div class="doc-link">
              <i class="pi pi-file-pdf"></i>
              <span class="doc-label">BC numérisé</span>
              <button pButton label="Ouvrir" icon="pi pi-external-link" [text]="true" severity="info" (click)="openProof()"></button>
              <button pButton label="Télécharger" icon="pi pi-download" [text]="true" (click)="downloadProof()"></button>
              <!-- PHASE-B-DOC-VIEW -->
            </div>"""
    replace_html = """            <div class="doc-link">
              <i class="pi pi-file-pdf"></i>
              <span class="doc-label">BC numérisé</span>
              <!-- PHASE-B-DOC-ACTIONS: shared Ouvrir/Télécharger -->
              <app-document-file-actions [documentId]="bc()?.documentId" [autoLoadLatest]="true" />
            </div>"""
    ts_import_anchor = "import { ButtonModule } from 'primeng/button';"
    ts_imports_array = ("imports: [RouterLink, ButtonModule, DialogModule],",
                        "imports: [RouterLink, ButtonModule, DialogModule,\n  DocumentFileActionsComponent], // PHASE-B-DOC-ACTIONS")
    _wire_inline_block(p, find_html, replace_html, "bc-detail",
                       ts_import_anchor, ts_imports_array)

def patch_movement_detail():
    p = FRONT / 'features/cash/movement-detail.component.ts'
    src = read(p)
    if SENTINEL_FE in src:
        print(f"[SKIP] movement-detail.component.ts: already patched")
        return

    # The cash movement-detail uses a regex-friendly block. Find the proof-btn span.
    # The Phase B (DOC-VIEW) patch wrapped the original <a class="proof-btn"> into a <span class="proof-btn">
    # with two <button pButton> elements. We replace that whole span with the shared component.
    pattern = re.compile(
        r"<span class=\"proof-btn\">\s*"
        r"<i class=\"pi pi-file-pdf\"></i>\s*"
        r"<span class=\"proof-label\">[^<]*</span>\s*"
        r"<button pButton label=\"Ouvrir\"[^>]*></button>\s*"
        r"<button pButton label=\"Télécharger\"[^>]*></button>\s*"
        r"(?:<!-- PHASE-B-DOC-VIEW -->\s*)?"
        r"</span>"
    )
    m = pattern.search(src)
    if not m:
        print(f"[FAIL] movement-detail.component.ts proof-btn block not found", file=sys.stderr)
        sys.exit(1)
    replace = (
        '<span class="proof-btn">\n'
        '                  <i class="pi pi-file-pdf"></i>\n'
        '                  <span class="proof-label">{{ proofDocumentName() }}</span>\n'
        '                  <!-- PHASE-B-DOC-ACTIONS: shared Ouvrir/Télécharger -->\n'
        '                  <app-document-file-actions [documentId]="proofDocumentId()" [autoLoadLatest]="true" />\n'
        '                </span>'
    )
    src = src[:m.start()] + replace + src[m.end():]

    # Add the import (anchor: the existing DocumentService import from Phase B)
    anchor = "import { DocumentService, OpenOutcome } from '@app/core/services/document.service';"
    new_import = ("import { DocumentService, OpenOutcome } from '@app/core/services/document.service';\n"
                 "import { DocumentFileActionsComponent } from '@app/shared/document-file-actions.component'; // PHASE-B-DOC-ACTIONS")
    if anchor in src:
        src = src.replace(anchor, new_import, 1)
    else:
        # Fallback: add after the first existing 'import {' line that ends with .component';
        m2 = re.search(r"(import \{[^}]+\} from '@app/[a-z/]+';\n)", src)
        if m2:
            src = src[:m2.end()] + "import { DocumentFileActionsComponent } from '@app/shared/document-file-actions.component';\n" + src[m2.end():]

    # Add to imports array — find the @Component imports: array
    arr_match = re.search(r"imports:\s*\[([^\]]+)\],", src)
    if arr_match:
        old_arr = arr_match.group(0)
        new_arr = old_arr.replace("],", ",\n  DocumentFileActionsComponent], // PHASE-B-DOC-ACTIONS")
        src = src.replace(old_arr, new_arr, 1)

    write(p, src)
    print(f"[OK]   movement-detail.component.ts: patched ({p.relative_to(ROOT)})")

# ============================================================================
def main():
    patch_backend()
    patch_app_component()
    create_shared_component()
    patch_documents_list()
    patch_document_detail()
    patch_facture_detail()
    patch_bl_detail()
    patch_bc_detail()
    patch_movement_detail()
    print("\nAll Phase B (DOC-ACTIONS) patches applied.")

if __name__ == '__main__':
    main()
