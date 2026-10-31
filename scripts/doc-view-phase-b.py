#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Phase B (DOC-VIEW) — file patcher for HYDRA at /tmp/my-project.

Patches:
  1) Backend  DocumentController.download  — Content-Disposition inline for pdf/image.
  2) Frontend DocumentService              — add openRevision + openDocument + downloadDocument
                                              (JSON-error blob guard + popup-blocked fallback).
  3) documents.component.{ts,html}         — Ouvrir (pdf/image) + Télécharger buttons.
  4) document-detail.component.{ts,html}   — Ouvrir (pdf/image) per revision.
  5) facture-detail.component.ts            — replace routerLink with Ouvrir/Télécharger.
  6) movement-detail.component.ts           — replace routerLink with Ouvrir/Télécharger.
  7) bl-detail.component.ts                 — add Ouvrir/Télécharger when documentId present.
  8) bc-detail.component.ts                 — add Ouvrir/Télécharger when documentId present.

This script is IDEMPOTENT: it refuses to re-patch an already-patched file (it
searches for the sentinel marker `// PHASE-B-DOC-VIEW` / `// Phase B (DOC-VIEW)`).
"""
from pathlib import Path
import sys, re

ROOT = Path('/tmp/my-project')
FRONT = ROOT / 'frontend/src/app'
BACK  = ROOT / 'backend/src/main/java/tn/hydra'

SENTINEL_BE  = '// Phase B (DOC-VIEW)'
SENTINEL_FE  = '// PHASE-B-DOC-VIEW'

# ----------------------------------------------------------------------------
# helpers
# ----------------------------------------------------------------------------
def read(p: Path) -> str:
    return p.read_text(encoding='utf-8')

def write(p: Path, content: str) -> None:
    p.write_text(content, encoding='utf-8')

def patch_once(p: Path, find: str, replace: str, sentinel: str, label: str) -> None:
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

# ----------------------------------------------------------------------------
# 1) Backend — DocumentController.download
# ----------------------------------------------------------------------------
def patch_backend():
    p = BACK / 'document/controller/DocumentController.java'
    find = '''    @GetMapping("/api/documents/{id}/revisions/{revId}/download")
    @PreAuthorize("hasAnyAuthority('PERM_DOCUMENT_READ','PERM_DOCUMENT_DOWNLOAD')")
    public ResponseEntity<Resource> download(@PathVariable UUID id,
                                             @PathVariable UUID revId,
                                             Authentication auth) {
        DownloadFile file = revisions.download(auth, id, revId);
        String encoded = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .contentType(file.contentType() == null
                        ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(file.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .body(file.resource());
    }
}'''
    replace = '''    @GetMapping("/api/documents/{id}/revisions/{revId}/download")
    @PreAuthorize("hasAnyAuthority('PERM_DOCUMENT_READ','PERM_DOCUMENT_DOWNLOAD')")
    public ResponseEntity<Resource> download(@PathVariable UUID id,
                                             @PathVariable UUID revId,
                                             Authentication auth) {
        DownloadFile file = revisions.download(auth, id, revId);
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
    patch_once(p, find, replace, SENTINEL_BE, 'BE DocumentController.download')

# ----------------------------------------------------------------------------
# 2) Frontend — DocumentService.openRevision + openDocument + downloadDocument
# ----------------------------------------------------------------------------
FE_SERVICE_NEW_METHODS = '''
  /**
   * PHASE-B-DOC-VIEW — Open or download a revision in the most natural way:
   *
   *  - PDF or image (jpg/png/gif/webp)  → createObjectURL + window.open (new tab)
   *  - everything else (xlsx, docx, …)  → trigger a real <a download> (saved by browser)
   *
   * GUARDS:
   *  - JSON-error blob: if the API answered 4xx/5xx with a JSON body (GlobalExceptionHandler),
   *    the responseType:'blob' will silently capture that JSON as the "file". We sniff the
   *    blob.type and, when it's JSON, parse the message and surface an error — never saving
   *    a .pdf that is actually `{"code":"DOCUMENT_NOT_FOUND","message":"…"}`.
   *  - Popup blocked: if window.open returns null (browser blocker / cross-origin),
   *    fall back to <a download> and report `kind:'downloaded'` so the caller can toast.
   *
   * Returns an OpenOutcome so the caller decides the toast wording (FR).
   */
  openRevision(id: string, revId: string, hintName?: string | null): Observable<OpenOutcome> {
    return this.downloadRevision(id, revId).pipe(
      map((blob: Blob): OpenOutcome => {
        // 1) JSON-error guard — the API answered with JSON despite responseType:'blob'.
        if (isJsonErrorBlob(blob)) {
          // Read the JSON text synchronously-best-effort: text() is async, so we
          // re-read via FileReader. To keep this synchronous-ish, we instead
          // return a 'error-pending' marker and let the caller's subscriber read.
          // Simplest robust approach: kick the async parse via a side observable.
          // Implementation below uses a synchronous throw caught by catchError.
          throw jsonErrorOutcome(blob);
        }
        // 2) Previewable → open in a new tab; fall back to download if blocked.
        if (isPreviewable(blob.type)) {
          const url = URL.createObjectURL(blob);
          const win = window.open(url, '_blank', 'noopener');
          if (win) {
            // Revoke after 60s — long enough for the browser to load the blob,
            // short enough not to leak memory on a forgotten tab.
            setTimeout(() => URL.revokeObjectURL(url), 60_000);
            return { kind: 'opened' };
          }
          // Popup blocked → fall through to download.
          triggerBrowserDownload(url, hintName ?? 'document');
          setTimeout(() => URL.revokeObjectURL(url), 5_000);
          return { kind: 'downloaded', reason: 'popup-blocked' };
        }
        // 3) Not previewable → always download.
        const url = URL.createObjectURL(blob);
        triggerBrowserDownload(url, hintName ?? 'document');
        setTimeout(() => URL.revokeObjectURL(url), 5_000);
        return { kind: 'downloaded' };
      }),
      catchError((err: any) => of(toErrorOutcome(err)))
    );
  }

  /**
   * PHASE-B-DOC-VIEW — Convenience: fetch the latest revision of a document,
   * then open (preview) it. Used by facture/BL/BC/cash screens that only know
   * the documentId, not the revision id. Returns the same OpenOutcome shape.
   */
  openDocument(docId: string, hintName?: string | null): Observable<OpenOutcome> {
    return this.revisions(docId).pipe(
      switchMap((revs: Revision[]) => {
        const latest = revs && revs.length ? revs[revs.length - 1] : null;
        if (!latest) {
          return of<OpenOutcome>({ kind: 'error', message: 'Aucune révision disponible.' });
        }
        return this.openRevision(docId, latest.id, hintName ?? latest.fileName ?? latest.revLabel);
      }),
      catchError((err: any) => of(toErrorOutcome(err)))
    );
  }

  /**
   * PHASE-B-DOC-VIEW — Convenience: fetch the latest revision then download it
   * (forced attachment, regardless of mime). Same JSON-error guard as openRevision.
   */
  downloadDocument(docId: string, hintName?: string | null): Observable<OpenOutcome> {
    return this.revisions(docId).pipe(
      switchMap((revs: Revision[]) => {
        const latest = revs && revs.length ? revs[revs.length - 1] : null;
        if (!latest) {
          return of<OpenOutcome>({ kind: 'error', message: 'Aucune révision disponible.' });
        }
        return this.downloadRevision(docId, latest.id).pipe(
          map((blob: Blob): OpenOutcome => {
            if (isJsonErrorBlob(blob)) {
              throw jsonErrorOutcome(blob);
            }
            const url = URL.createObjectURL(blob);
            triggerBrowserDownload(url, hintName ?? latest.fileName ?? latest.revLabel ?? 'document');
            setTimeout(() => URL.revokeObjectURL(url), 5_000);
            return { kind: 'downloaded' };
          })
        );
      }),
      catchError((err: any) => of(toErrorOutcome(err)))
    );
  }
'''

FE_SERVICE_NEW_IMPORTS = '''import { Observable, catchError, filter, map, of, switchMap } from 'rxjs';'''

# types and helpers to insert near the top of document.service.ts (after imports, before class)
FE_SERVICE_TYPES = '''
// PHASE-B-DOC-VIEW — shared open/download outcome type used by every screen that
// surfaces a document (document list, document detail, facture/BL/BC/cash detail).
export type OpenOutcome =
  | { kind: 'opened' }                                    // new tab opened (preview)
  | { kind: 'downloaded'; reason?: 'popup-blocked' }     // browser saved the file
  | { kind: 'error'; message: string };                   // fetch failed or JSON-error blob

/** PDF + raster images can be opened in a new browser tab via createObjectURL. */
export function isPreviewable(mimeType: string | null | undefined): boolean {
  if (!mimeType) return false;
  const ct = mimeType.toLowerCase().split(';')[0].trim();
  return ct === 'application/pdf'
      || ct === 'application/x-pdf'
      || ct.startsWith('image/');
}

/**
 * Detect the JSON-error-as-blob trap: when the API returns 4xx/5xx with a JSON
 * body (the standard GlobalExceptionHandler envelope), Angular responseType:'blob'
 * will silently capture that JSON as the "file". Sniff by content-type so we never
 * save e.g. a .pdf whose content is actually `{"code":"DOCUMENT_NOT_FOUND",...}`.
 */
export function isJsonErrorBlob(blob: Blob | null | undefined): boolean {
  if (!blob) return false;
  const ct = (blob.type || '').toLowerCase().split(';')[0].trim();
  return ct === 'application/json' || ct === 'text/json' || ct === '';
}

/** Read a JSON-error blob synchronously-best-effort and return an OpenOutcome.error. */
export function jsonErrorOutcome(blob: Blob): OpenOutcome {
  // FileReader is async; we do a best-effort sync message and let the caller surface it.
  // The actual JSON parsing happens via the AsyncJsonError guard below; this fn is
  // only used in the synchronous map() path, where we fall back to a generic message.
  return { kind: 'error', message: 'Fichier introuvable ou accès refusé.' };
}

/** Trigger a real browser download via a synthetic <a download>. */
export function triggerBrowserDownload(url: string, filename: string): void {
  const a = document.createElement('a');
  a.href = url;
  a.download = filename || 'document';
  a.rel = 'noopener';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
}

/** Normalize any error (sync throw or HTTP error) into an OpenOutcome.error with FR message. */
export function toErrorOutcome(err: any): OpenOutcome {
  // The JSON-error-blob case: the blob body carries the API envelope
  // {code, message}. We try to read it synchronously (not possible); instead,
  // we degrade gracefully to a French message that includes the status.
  if (err && typeof err === 'object' && err.kind === 'error' && typeof err.message === 'string') {
    return err; // already an OpenOutcome
  }
  const status = err?.status;
  const body = err?.error;
  let msg = '';
  if (typeof body === 'string' && body.trim()) {
    msg = body;
  } else if (body && typeof body === 'object') {
    msg = body.message ?? body.detail ?? body.code ?? '';
  }
  if (!msg && typeof err?.message === 'string') {
    msg = err.message;
  }
  if (!msg) {
    msg = status === 404 ? 'Fichier introuvable.'
       : status === 403 ? 'Accès refusé.'
       : status === 401 ? 'Session expirée, veuillez vous reconnecter.'
       : 'Téléchargement impossible.';
  }
  return { kind: 'error', message: msg };
}
'''

# Async JSON-error guard: a separate helper that the openRevision map can't call
# synchronously. To keep the API simple, we wrap the whole openRevision flow in
# a flatMap over an async JSON-sniff. We re-implement openRevision to first read
# the blob as text if it's a JSON-error candidate.
FE_SERVICE_ASYNC_HELPER = '''
  /**
   * PHASE-B-DOC-VIEW — Internal: fetch blob, then if the content-type signals a
   * JSON error, parse the message and return an OpenOutcome.error. Otherwise
   * hand the (valid) blob to the consumer callback.
   */
  private fetchBlobGuarded(
    id: string, revId: string,
    onBlob: (blob: Blob, mime: string) => OpenOutcome
  ): Observable<OpenOutcome> {
    return this.downloadRevision(id, revId).pipe(
      switchMap((blob: Blob) => {
        const mime = (blob.type || '').toLowerCase();
        if (mime.startsWith('application/json') || mime === 'text/json' || mime === '') {
          // Read the JSON text and surface the API message.
          return new Observable<OpenOutcome>(sub => {
            blob.text().then(txt => {
              let msg = 'Fichier introuvable ou accès refusé.';
              try {
                const j = JSON.parse(txt);
                msg = j?.message ?? j?.detail ?? j?.code ?? msg;
              } catch { /* keep default */ }
              sub.next({ kind: 'error', message: msg });
              sub.complete();
            }).catch(() => {
              sub.next({ kind: 'error', message: 'Lecture du fichier impossible.' });
              sub.complete();
            });
          });
        }
        return of(onBlob(blob, mime));
      }),
      catchError((err: any) => of(toErrorOutcome(err)))
    );
  }
'''

def patch_fe_service():
    p = FRONT / 'core/services/document.service.ts'
    src = read(p)
    if SENTINEL_FE in src:
        print(f"[SKIP] FE DocumentService: already patched ({p.relative_to(ROOT)})")
        return
    # 1) Replace the rxjs import line to add catchError/switchMap/of
    old_imports = "import { Observable, filter, map } from 'rxjs';"
    if old_imports not in src:
        print(f"[FAIL] FE DocumentService imports pattern not found", file=sys.stderr)
        sys.exit(1)
    src = src.replace(old_imports, FE_SERVICE_NEW_IMPORTS, 1)

    # 2) Insert the OpenOutcome types + helpers right BEFORE the @Injectable line
    inject_marker = '@Injectable({ providedIn: \'root\' })'
    if inject_marker not in src:
        print(f"[FAIL] FE DocumentService @Injectable marker not found", file=sys.stderr)
        sys.exit(1)
    src = src.replace(inject_marker, FE_SERVICE_TYPES.strip() + '\n\n' + inject_marker, 1)

    # 3) Insert openRevision/openDocument/downloadDocument + fetchBlobGuarded right
    #    BEFORE the "// ── helpers ──" line (the existing private helpers section)
    helpers_marker = '  // ── helpers ──────────────────────────────────────────────────────────'
    if helpers_marker not in src:
        print(f"[FAIL] FE DocumentService helpers marker not found", file=sys.stderr)
        sys.exit(1)
    # Replace the synchronous openRevision stub (above) with the guarded async one.
    new_methods = FE_SERVICE_NEW_METHODS.replace(
        "  openRevision(id: string, revId: string, hintName?: string | null): Observable<OpenOutcome> {\n"
        "    return this.downloadRevision(id, revId).pipe(\n"
        "      map((blob: Blob): OpenOutcome => {\n"
        "        // 1) JSON-error guard — the API answered with JSON despite responseType:'blob'.\n"
        "        if (isJsonErrorBlob(blob)) {\n"
        "          // Read the JSON text synchronously-best-effort: text() is async, so we\n"
        "          // re-read via FileReader. To keep this synchronous-ish, we instead\n"
        "          // return a 'error-pending' marker and let the caller's subscriber read.\n"
        "          // Simplest robust approach: kick the async parse via a side observable.\n"
        "          // Implementation below uses a synchronous throw caught by catchError.\n"
        "          throw jsonErrorOutcome(blob);\n"
        "        }\n"
        "        // 2) Previewable → open in a new tab; fall back to download if blocked.\n"
        "        if (isPreviewable(blob.type)) {\n"
        "          const url = URL.createObjectURL(blob);\n"
        "          const win = window.open(url, '_blank', 'noopener');\n"
        "          if (win) {\n"
        "            // Revoke after 60s — long enough for the browser to load the blob,\n"
        "            // short enough not to leak memory on a forgotten tab.\n"
        "            setTimeout(() => URL.revokeObjectURL(url), 60_000);\n"
        "            return { kind: 'opened' };\n"
        "          }\n"
        "          // Popup blocked → fall through to download.\n"
        "          triggerBrowserDownload(url, hintName ?? 'document');\n"
        "          setTimeout(() => URL.revokeObjectURL(url), 5_000);\n"
        "          return { kind: 'downloaded', reason: 'popup-blocked' };\n"
        "        }\n"
        "        // 3) Not previewable → always download.\n"
        "        const url = URL.createObjectURL(blob);\n"
        "        triggerBrowserDownload(url, hintName ?? 'document');\n"
        "        setTimeout(() => URL.revokeObjectURL(url), 5_000);\n"
        "        return { kind: 'downloaded' };\n"
        "      }),\n"
        "      catchError((err: any) => of(toErrorOutcome(err)))\n"
        "    );\n"
        "  }",
        "  openRevision(id: string, revId: string, hintName?: string | null): Observable<OpenOutcome> {\n"
        "    return this.fetchBlobGuarded(id, revId, (blob, mime) => {\n"
        "      if (isPreviewable(mime)) {\n"
        "        const url = URL.createObjectURL(blob);\n"
        "        const win = window.open(url, '_blank', 'noopener');\n"
        "        if (win) {\n"
        "          setTimeout(() => URL.revokeObjectURL(url), 60_000);\n"
        "          return { kind: 'opened' };\n"
        "        }\n"
        "        triggerBrowserDownload(url, hintName ?? 'document');\n"
        "        setTimeout(() => URL.revokeObjectURL(url), 5_000);\n"
        "        return { kind: 'downloaded', reason: 'popup-blocked' };\n"
        "      }\n"
        "      const url = URL.createObjectURL(blob);\n"
        "      triggerBrowserDownload(url, hintName ?? 'document');\n"
        "      setTimeout(() => URL.revokeObjectURL(url), 5_000);\n"
        "      return { kind: 'downloaded' };\n"
        "    });\n"
        "  }"
    )
    # Replace the downloadDocument map() that throws jsonErrorOutcome with the guarded version
    new_methods = new_methods.replace(
        "        return this.downloadRevision(docId, latest.id).pipe(\n"
        "          map((blob: Blob): OpenOutcome => {\n"
        "            if (isJsonErrorBlob(blob)) {\n"
        "              throw jsonErrorOutcome(blob);\n"
        "            }\n"
        "            const url = URL.createObjectURL(blob);\n"
        "            triggerBrowserDownload(url, hintName ?? latest.fileName ?? latest.revLabel ?? 'document');\n"
        "            setTimeout(() => URL.revokeObjectURL(url), 5_000);\n"
        "            return { kind: 'downloaded' };\n"
        "          })\n"
        "        );",
        "        return this.fetchBlobGuarded(docId, latest.id, (blob, _mime) => {\n"
        "          const url = URL.createObjectURL(blob);\n"
        "          triggerBrowserDownload(url, hintName ?? latest.fileName ?? latest.revLabel ?? 'document');\n"
        "          setTimeout(() => URL.revokeObjectURL(url), 5_000);\n"
        "          return { kind: 'downloaded' };\n"
        "        });"
    )

    src = src.replace(helpers_marker,
                      new_methods.strip() + '\n\n' + FE_SERVICE_ASYNC_HELPER.strip() + '\n\n' + helpers_marker,
                      1)
    write(p, src)
    print(f"[OK]   FE DocumentService: patched ({p.relative_to(ROOT)})")

# ----------------------------------------------------------------------------
# 3) documents.component — Ouvrir (pdf/image) + Télécharger buttons
# ----------------------------------------------------------------------------
def patch_documents_list():
    ts = FRONT / 'features/document/documents.component.ts'
    html = FRONT / 'features/document/documents.component.html'

    # ── TS: add openDoc() next to download()
    src_ts = read(ts)
    if SENTINEL_FE in src_ts:
        print(f"[SKIP] FE documents.component.ts: already patched")
    else:
        # add Revision import to the existing model import line
        if "Revision" not in src_ts.split("from '@app/core/models/document.model';")[0]:
            src_ts = src_ts.replace(
                "import {\n  DocType, DOC_TYPE_OPTS, DOC_TYPE_LABELS,\n  DOC_STATUS_LABELS, DOC_STATUS_SEVERITY, Document\n} from '@app/core/models/document.model';",
                "import {\n  DocType, DOC_TYPE_OPTS, DOC_TYPE_LABELS,\n  DOC_STATUS_LABELS, DOC_STATUS_SEVERITY, Document, Revision, isPreviewable\n} from '@app/core/models/document.model';\nimport { OpenOutcome, toErrorOutcome } from '@app/core/services/document.service';",
                1,
            )
        # add latestRev signal + openDoc() method
        find_method = """  download(d: Document): void {
    this.documentService.revisions(d.id).subscribe({
      next: revs => {
        const latest = revs && revs.length ? revs[revs.length - 1] : null;
        if (!latest) { this.msgs.add({ severity: 'warn', summary: 'Aucune révision' }); return; }
        this.doDownload(d, latest.id, latest.fileName);
      },
      error: () => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Téléchargement impossible' })
    });
  }"""
        replace_method = """  download(d: Document): void {
    this.documentService.revisions(d.id).subscribe({
      next: revs => {
        const latest = revs && revs.length ? revs[revs.length - 1] : null;
        if (!latest) { this.msgs.add({ severity: 'warn', summary: 'Aucune révision' }); return; }
        this.doDownload(d, latest.id, latest.fileName);
      },
      error: () => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Téléchargement impossible' })
    });
  }

  // PHASE-B-DOC-VIEW — Ouvrir (preview pdf/image in new tab) with French toasts.
  latestRevByDoc = signal<Record<string, Revision>>({});
  openDoc(d: Document): void {
    this.documentService.revisions(d.id).subscribe({
      next: revs => {
        const latest = revs && revs.length ? revs[revs.length - 1] : null;
        if (!latest) { this.msgs.add({ severity: 'warn', summary: 'Aucune révision' }); return; }
        this.latestRevByDoc.update(m => ({ ...m, [d.id]: latest }));
        this.documentService.openRevision(d.id, latest.id, latest.fileName ?? d.reference).subscribe(o => this.onOpenOutcome(o));
      },
      error: () => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Ouverture impossible' })
    });
  }
  canPreview(d: Document): boolean {
    const r = this.latestRevByDoc()[d.id];
    return !!r && isPreviewable(r.mimeType);
  }
  onOpenOutcome(o: OpenOutcome): void {
    if (o.kind === 'error') {
      this.msgs.add({ severity: 'error', summary: 'Ouverture impossible', detail: o.message });
    } else if (o.kind === 'downloaded' && o.reason === 'popup-blocked') {
      this.msgs.add({ severity: 'info', summary: 'Fenêtre bloquée', detail: 'Le fichier a été téléchargé à la place.' });
    }
  }"""
        if find_method not in src_ts:
            print(f"[FAIL] FE documents.component.ts download() pattern not found", file=sys.stderr)
            sys.exit(1)
        src_ts = src_ts.replace(find_method, replace_method, 1)
        write(ts, src_ts)
        print(f"[OK]   FE documents.component.ts: patched ({ts.relative_to(ROOT)})")

    # ── HTML: add an Ouvrir button (pi-external-link) before the Télécharger button
    src_html = read(html)
    if SENTINEL_FE in src_html:
        print(f"[SKIP] FE documents.component.html: already patched")
        return
    find_html = """                <p-button icon="pi pi-download" [rounded]="true" [text]="true"
                          (onClick)="download(d)" [attr.aria-label]="'Télécharger ' + d.reference" />"""
    replace_html = """                <!-- PHASE-B-DOC-VIEW: Ouvrir (preview pdf/image in new tab) -->
                <p-button icon="pi pi-external-link" [rounded]="true" [text]="true"
                          severity="info" (onClick)="openDoc(d)"
                          [attr.aria-label]="'Ouvrir ' + d.reference"
                          [disabled]="!canPreview(d)" />
                <p-button icon="pi pi-download" [rounded]="true" [text]="true"
                          (onClick)="download(d)" [attr.aria-label]="'Télécharger ' + d.reference" />"""
    if find_html not in src_html:
        print(f"[FAIL] FE documents.component.html pattern not found", file=sys.stderr)
        sys.exit(1)
    src_html = src_html.replace(find_html, replace_html, 1)
    write(html, src_html)
    print(f"[OK]   FE documents.component.html: patched ({html.relative_to(ROOT)})")

# ----------------------------------------------------------------------------
# 4) document-detail.component — Ouvrir per revision
# ----------------------------------------------------------------------------
def patch_document_detail():
    ts = FRONT / 'features/document/document-detail.component.ts'
    html = FRONT / 'features/document/document-detail.component.html'

    src_ts = read(ts)
    if SENTINEL_FE in src_ts:
        print(f"[SKIP] FE document-detail.component.ts: already patched")
    else:
        # add OpenOutcome import
        if "import { DocumentService } from '@app/core/services/document.service';" in src_ts:
            src_ts = src_ts.replace(
                "import { DocumentService } from '@app/core/services/document.service';",
                "import { DocumentService, OpenOutcome, isPreviewable } from '@app/core/services/document.service';",
                1,
            )
        # add open(r) method + canPreview(r) helper after download(r)
        find_method = """  download(r: Revision): void {
    if (!this.docId) { return; }
    this.documentService.downloadRevision(this.docId, r.id).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = r.fileName || r.revLabel || 'revision';
        a.click();
        URL.revokeObjectURL(url);
      },
      error: () => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Téléchargement impossible' })
    });
  }"""
        replace_method = """  download(r: Revision): void {
    if (!this.docId) { return; }
    this.documentService.downloadRevision(this.docId, r.id).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = r.fileName || r.revLabel || 'revision';
        a.click();
        URL.revokeObjectURL(url);
      },
      error: () => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Téléchargement impossible' })
    });
  }

  // PHASE-B-DOC-VIEW — Ouvrir (preview pdf/image in new tab) with French toasts.
  open(r: Revision): void {
    if (!this.docId) { return; }
    this.documentService.openRevision(this.docId, r.id, r.fileName ?? r.revLabel).subscribe(o => {
      if (o.kind === 'error') this.msgs.add({ severity: 'error', summary: 'Ouverture impossible', detail: o.message });
      else if (o.kind === 'downloaded' && o.reason === 'popup-blocked')
        this.msgs.add({ severity: 'info', summary: 'Fenêtre bloquée', detail: 'Le fichier a été téléchargé à la place.' });
    });
  }
  canPreview(r: Revision): boolean { return isPreviewable(r.mimeType); }"""
        if find_method not in src_ts:
            print(f"[FAIL] FE document-detail.component.ts download() pattern not found", file=sys.stderr)
            sys.exit(1)
        src_ts = src_ts.replace(find_method, replace_method, 1)
        write(ts, src_ts)
        print(f"[OK]   FE document-detail.component.ts: patched ({ts.relative_to(ROOT)})")

    src_html = read(html)
    if SENTINEL_FE in src_html:
        print(f"[SKIP] FE document-detail.component.html: already patched")
        return
    find_html = """              <span class="act">
                <p-button icon="pi pi-download" [rounded]="true" [text]="true"
                          (onClick)="download(r)" [attr.aria-label]="'Télécharger ' + r.revLabel" />
              </span>"""
    replace_html = """              <span class="act">
                <!-- PHASE-B-DOC-VIEW: Ouvrir (preview pdf/image in new tab) -->
                <p-button icon="pi pi-external-link" [rounded]="true" [text]="true" severity="info"
                          (onClick)="open(r)" [attr.aria-label]="'Ouvrir ' + r.revLabel"
                          [disabled]="!canPreview(r)" />
                <p-button icon="pi pi-download" [rounded]="true" [text]="true"
                          (onClick)="download(r)" [attr.aria-label]="'Télécharger ' + r.revLabel" />
              </span>"""
    if find_html not in src_html:
        print(f"[FAIL] FE document-detail.component.html pattern not found", file=sys.stderr)
        sys.exit(1)
    src_html = src_html.replace(find_html, replace_html, 1)
    write(html, src_html)
    print(f"[OK]   FE document-detail.component.html: patched ({html.relative_to(ROOT)})")

# ----------------------------------------------------------------------------
# 5) facture-detail — replace routerLink with Ouvrir/Télécharger
# ----------------------------------------------------------------------------
def patch_facture_detail():
    ts = FRONT / 'features/stock/facture-detail.component.ts'
    src = read(ts)
    if SENTINEL_FE in src:
        print(f"[SKIP] FE facture-detail.component.ts: already patched")
        return
    # add imports
    if "import { DocumentService, OpenOutcome } from '@app/core/services/document.service';" not in src:
        src = src.replace(
            "import { PurchaseService } from '@app/core/services/purchase.service';",
            "import { PurchaseService } from '@app/core/services/purchase.service';\nimport { DocumentService, OpenOutcome } from '@app/core/services/document.service';",
            1,
        )
    # inject documentService + openProof() + downloadProof()
    find_inject = "  private purchase = inject(PurchaseService);"
    replace_inject = "  private purchase = inject(PurchaseService);\n  private documentService = inject(DocumentService); // PHASE-B-DOC-VIEW"
    if find_inject not in src:
        print(f"[FAIL] FE facture-detail inject marker not found", file=sys.stderr)
        sys.exit(1)
    src = src.replace(find_inject, replace_inject, 1)

    # add openProof() / downloadProof() right after onDocPicked()
    find_method = """  onDocPicked(docId: string): void {
    const f = this.fac(); if (!f) return;
    this.purchase.linkFactureDocument(f.id, docId).subscribe(ok => {
      if (ok) {
        this.msgs.add({ severity: 'success', summary: 'Justificatif lié', detail: 'Document rattaché à la facture.' });
        this.purchase.getFacture(f.id).subscribe(refreshed => { if (refreshed) this.fac.set(refreshed); });
      }
    });
  }"""
    replace_method = """  onDocPicked(docId: string): void {
    const f = this.fac(); if (!f) return;
    this.purchase.linkFactureDocument(f.id, docId).subscribe(ok => {
      if (ok) {
        this.msgs.add({ severity: 'success', summary: 'Justificatif lié', detail: 'Document rattaché à la facture.' });
        this.purchase.getFacture(f.id).subscribe(refreshed => { if (refreshed) this.fac.set(refreshed); });
      }
    });
  }

  // PHASE-B-DOC-VIEW — Ouvrir / Télécharger the linked justificatif (latest revision).
  onProofOutcome(o: OpenOutcome, verb: 'Ouverture' | 'Téléchargement'): void {
    if (o.kind === 'error') this.msgs.add({ severity: 'error', summary: verb + ' impossible', detail: o.message });
    else if (o.kind === 'downloaded' && o.reason === 'popup-blocked')
      this.msgs.add({ severity: 'info', summary: 'Fenêtre bloquée', detail: 'Le fichier a été téléchargé à la place.' });
  }
  openProof(): void {
    const f = this.fac(); if (!f?.documentId) return;
    this.documentService.openDocument(f.documentId, f.reference).subscribe(o => this.onProofOutcome(o, 'Ouverture'));
  }
  downloadProof(): void {
    const f = this.fac(); if (!f?.documentId) return;
    this.documentService.downloadDocument(f.documentId, f.reference).subscribe(o => this.onProofOutcome(o, 'Téléchargement'));
  }"""
    if find_method not in src:
        print(f"[FAIL] FE facture-detail onDocPicked pattern not found", file=sys.stderr)
        sys.exit(1)
    src = src.replace(find_method, replace_method, 1)

    # replace the static routerLink "Voir le justificatif (PDF)" with two buttons
    find_html = """          @if (fac()?.documentId) {
            <div class="doc-link">
              <i class="pi pi-file-pdf"></i>
              <a [routerLink]="'/documents/' + fac()?.documentId" class="doc-open">Voir le justificatif (PDF)</a>
            </div>
          } @else if (canMove()) {"""
    replace_html = """          @if (fac()?.documentId) {
            <div class="doc-link">
              <i class="pi pi-file-pdf"></i>
              <span class="doc-label">Justificatif lié</span>
              <button pButton label="Ouvrir" icon="pi pi-external-link" [text]="true" severity="info" (click)="openProof()"></button>
              <button pButton label="Télécharger" icon="pi pi-download" [text]="true" (click)="downloadProof()"></button>
              <!-- PHASE-B-DOC-VIEW -->
            </div>
          } @else if (canMove()) {"""
    if find_html not in src:
        print(f"[FAIL] FE facture-detail doc-link pattern not found", file=sys.stderr)
        sys.exit(1)
    src = src.replace(find_html, replace_html, 1)

    # add minimal style for .doc-label
    find_style = "    .doc-open { color: #2563eb; text-decoration: none; font-weight: 600; }"
    replace_style = "    .doc-open { color: #2563eb; text-decoration: none; font-weight: 600; }\n    .doc-label { font-weight: 600; color: #475569; margin-right: 4px; }"
    if find_style in src:
        src = src.replace(find_style, replace_style, 1)

    write(ts, src)
    print(f"[OK]   FE facture-detail.component.ts: patched ({ts.relative_to(ROOT)})")

# ----------------------------------------------------------------------------
# 6) movement-detail (cash) — replace routerLink with Ouvrir/Télécharger
# ----------------------------------------------------------------------------
def patch_movement_detail():
    ts = FRONT / 'features/cash/movement-detail.component.ts'
    src = read(ts)
    if SENTINEL_FE in src:
        print(f"[SKIP] FE movement-detail.component.ts: already patched")
        return

    # add imports
    if "import { DocumentService, OpenOutcome } from '@app/core/services/document.service';" not in src:
        # find a stable anchor — look for the first existing import line of any service
        anchor = "import { PurchaseService } from '@app/core/services/purchase.service';"
        if anchor not in src:
            # fallback: inject before the first 'import {' that ends with .service';
            m = re.search(r"(import \{[^}]+\} from '@app/core/services/[^']+';)", src)
            if not m:
                print(f"[FAIL] FE movement-detail no service import anchor", file=sys.stderr)
                sys.exit(1)
            anchor = m.group(1)
        src = src.replace(anchor, anchor + "\nimport { DocumentService, OpenOutcome } from '@app/core/services/document.service';", 1)

    # find existing inject line for one of the services (heuristic)
    m = re.search(r"(  private \w+ = inject\([A-Z]\w+\);\n)", src)
    if not m:
        print(f"[FAIL] FE movement-detail inject pattern not found", file=sys.stderr)
        sys.exit(1)
    inject_anchor = m.group(1)
    inject_new = "  private documentService = inject(DocumentService); // PHASE-B-DOC-VIEW\n"
    # only inject if not already there
    if "inject(DocumentService)" not in src:
        src = src.replace(inject_anchor, inject_anchor + inject_new, 1)

    # add openProof / downloadProof + onProofOutcome — insert just before the closing '}' of the class.
    # Heuristic: find the last '}' line in the file and inject before it.
    last_brace = src.rfind('}')
    if last_brace < 0:
        print(f"[FAIL] FE movement-detail no closing brace", file=sys.stderr)
        sys.exit(1)
    methods_block = """

  // PHASE-B-DOC-VIEW — Ouvrir / Télécharger the proof document (latest revision).
  onProofOutcome(o: OpenOutcome, verb: 'Ouverture' | 'Téléchargement'): void {
    if (o.kind === 'error') this.msgs.add({ severity: 'error', summary: verb + ' impossible', detail: o.message });
    else if (o.kind === 'downloaded' && o.reason === 'popup-blocked')
      this.msgs.add({ severity: 'info', summary: 'Fenêtre bloquée', detail: 'Le fichier a été téléchargé à la place.' });
  }
  openProof(): void {
    const id = this.proofDocumentId(); if (!id) return;
    this.documentService.openDocument(id, this.proofDocumentLabel()).subscribe(o => this.onProofOutcome(o, 'Ouverture'));
  }
  downloadProof(): void {
    const id = this.proofDocumentId(); if (!id) return;
    this.documentService.downloadDocument(id, this.proofDocumentLabel()).subscribe(o => this.onProofOutcome(o, 'Téléchargement'));
  }
"""
    src = src[:last_brace] + methods_block + src[last_brace:]

    # replace the static routerLink "proof-btn" with two buttons
    # We don't know the exact template string, so we search for the anchor and replace.
    find_html = '                  <a class="proof-btn" [routerLink]="[\'/documents\', proofDocumentId()]">'
    if find_html not in src:
        # try a relaxed search for the proof anchor
        m = re.search(r"<a class=\"proof-btn\"[^>]*>\s*<i class=\"pi[^\"]*\"></i>\s*([^<]+)\s*</a>", src)
        if not m:
            print(f"[FAIL] FE movement-detail proof-btn anchor not found", file=sys.stderr)
            sys.exit(1)
        original_label = m.group(1).strip()
        old_block = m.group(0)
        new_block = (
            '<span class="proof-btn">'
            '<i class="pi pi-file-pdf"></i>'
            f'<span class="proof-label">{original_label}</span>'
            '<button pButton label="Ouvrir" icon="pi pi-external-link" [text]="true" severity="info" (click)="openProof()"></button>'
            '<button pButton label="Télécharger" icon="pi pi-download" [text]="true" (click)="downloadProof()"></button>'
            '<!-- PHASE-B-DOC-VIEW -->'
            '</span>'
        )
        src = src.replace(old_block, new_block, 1)
    else:
        # we know the exact original string — replace with two buttons + label
        # we need to also remove the closing </a> which sits after the icon+label.
        # Find the matching </a> after find_html's offset.
        start = src.index(find_html)
        end = src.index('</a>', start) + len('</a>')
        old_block = src[start:end]
        new_block = (
            '<span class="proof-btn">'
            '<i class="pi pi-file-pdf"></i>'
            '<span class="proof-label">Voir la pièce justificative</span>'
            '<button pButton label="Ouvrir" icon="pi pi-external-link" [text]="true" severity="info" (click)="openProof()"></button>'
            '<button pButton label="Télécharger" icon="pi pi-download" [text]="true" (click)="downloadProof()"></button>'
            '<!-- PHASE-B-DOC-VIEW -->'
            '</span>'
        )
        src = src[:start] + new_block + src[end:]

    # add a tiny style for .proof-label
    src = src.replace(".proof-btn {",
                      ".proof-label { font-weight: 600; color: #475569; margin: 0 8px; }\n    .proof-btn {", 1)

    write(ts, src)
    print(f"[OK]   FE movement-detail.component.ts: patched ({ts.relative_to(ROOT)})")

# ----------------------------------------------------------------------------
# 7) bl-detail — add Ouvrir/Télécharger when documentId present
# ----------------------------------------------------------------------------
def patch_bl_detail():
    ts = FRONT / 'features/stock/bl-detail.component.ts'
    src = read(ts)
    if SENTINEL_FE in src:
        print(f"[SKIP] FE bl-detail.component.ts: already patched")
        return

    # add imports
    if "import { DocumentService, OpenOutcome } from '@app/core/services/document.service';" not in src:
        src = src.replace(
            "import { PurchaseService } from '@app/core/services/purchase.service';",
            "import { PurchaseService } from '@app/core/services/purchase.service';\nimport { DocumentService, OpenOutcome } from '@app/core/services/document.service';",
            1,
        )
    # inject documentService
    find_inject = "  private purchase = inject(PurchaseService);"
    if find_inject not in src:
        print(f"[FAIL] FE bl-detail inject marker not found", file=sys.stderr)
        sys.exit(1)
    src = src.replace(find_inject,
                      find_inject + "\n  private documentService = inject(DocumentService); // PHASE-B-DOC-VIEW", 1)

    # add the openProof / downloadProof methods at end of class
    last_brace = src.rfind('}')
    methods_block = """

  // PHASE-B-DOC-VIEW — Ouvrir / Télécharger the linked BL document (latest revision).
  onProofOutcome(o: OpenOutcome, verb: 'Ouverture' | 'Téléchargement'): void {
    if (o.kind === 'error') this.msgs.add({ severity: 'error', summary: verb + ' impossible', detail: o.message });
    else if (o.kind === 'downloaded' && o.reason === 'popup-blocked')
      this.msgs.add({ severity: 'info', summary: 'Fenêtre bloquée', detail: 'Le fichier a été téléchargé à la place.' });
  }
  openProof(): void {
    const bl = this.bl(); if (!bl?.documentId) return;
    this.documentService.openDocument(bl.documentId, bl.reference).subscribe(o => this.onProofOutcome(o, 'Ouverture'));
  }
  downloadProof(): void {
    const bl = this.bl(); if (!bl?.documentId) return;
    this.documentService.downloadDocument(bl.documentId, bl.reference).subscribe(o => this.onProofOutcome(o, 'Téléchargement'));
  }
"""
    src = src[:last_brace] + methods_block + src[last_brace:]

    # add UI: a doc-link block in the template (after the info-grid)
    find_html = """          <table class="lines-table">"""
    replace_html = """          @if (bl()?.documentId) {
            <div class="doc-link">
              <i class="pi pi-file-pdf"></i>
              <span class="doc-label">BL numérisé</span>
              <button pButton label="Ouvrir" icon="pi pi-external-link" [text]="true" severity="info" (click)="openProof()"></button>
              <button pButton label="Télécharger" icon="pi pi-download" [text]="true" (click)="downloadProof()"></button>
              <!-- PHASE-B-DOC-VIEW -->
            </div>
          }
          <table class="lines-table">"""
    if find_html not in src:
        print(f"[FAIL] FE bl-detail lines-table anchor not found", file=sys.stderr)
        sys.exit(1)
    src = src.replace(find_html, replace_html, 1)

    # add styles for doc-link + doc-label
    style_anchor = ".actions { display: flex; gap: 8px; border-top: 1px solid #e2e8f0; padding-top: 16px; }"
    if style_anchor in src:
        src = src.replace(style_anchor, style_anchor + "\n    .doc-link { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; padding: 10px 12px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; font-size: 14px; }\n    .doc-link i { color: #dc2626; }\n    .doc-label { font-weight: 600; color: #475569; margin-right: 4px; }", 1)

    write(ts, src)
    print(f"[OK]   FE bl-detail.component.ts: patched ({ts.relative_to(ROOT)})")

# ----------------------------------------------------------------------------
# 8) bc-detail — add Ouvrir/Télécharger when documentId present
# ----------------------------------------------------------------------------
def patch_bc_detail():
    ts = FRONT / 'features/stock/bc-detail.component.ts'
    src = read(ts)
    if SENTINEL_FE in src:
        print(f"[SKIP] FE bc-detail.component.ts: already patched")
        return

    if "import { DocumentService, OpenOutcome } from '@app/core/services/document.service';" not in src:
        src = src.replace(
            "import { PurchaseService } from '@app/core/services/purchase.service';",
            "import { PurchaseService } from '@app/core/services/purchase.service';\nimport { DocumentService, OpenOutcome } from '@app/core/services/document.service';",
            1,
        )
    find_inject = "  private purchase = inject(PurchaseService);"
    if find_inject not in src:
        print(f"[FAIL] FE bc-detail inject marker not found", file=sys.stderr)
        sys.exit(1)
    src = src.replace(find_inject,
                      find_inject + "\n  private documentService = inject(DocumentService); // PHASE-B-DOC-VIEW", 1)

    last_brace = src.rfind('}')
    methods_block = """

  // PHASE-B-DOC-VIEW — Ouvrir / Télécharger the linked BC document (latest revision).
  onProofOutcome(o: OpenOutcome, verb: 'Ouverture' | 'Téléchargement'): void {
    if (o.kind === 'error') this.msgs.add({ severity: 'error', summary: verb + ' impossible', detail: o.message });
    else if (o.kind === 'downloaded' && o.reason === 'popup-blocked')
      this.msgs.add({ severity: 'info', summary: 'Fenêtre bloquée', detail: 'Le fichier a été téléchargé à la place.' });
  }
  openProof(): void {
    const bc = this.bc(); if (!bc?.documentId) return;
    this.documentService.openDocument(bc.documentId, bc.reference).subscribe(o => this.onProofOutcome(o, 'Ouverture'));
  }
  downloadProof(): void {
    const bc = this.bc(); if (!bc?.documentId) return;
    this.documentService.downloadDocument(bc.documentId, bc.reference).subscribe(o => this.onProofOutcome(o, 'Téléchargement'));
  }
"""
    src = src[:last_brace] + methods_block + src[last_brace:]

    find_html = """          <table class="lines-table">"""
    replace_html = """          @if (bc()?.documentId) {
            <div class="doc-link">
              <i class="pi pi-file-pdf"></i>
              <span class="doc-label">BC numérisé</span>
              <button pButton label="Ouvrir" icon="pi pi-external-link" [text]="true" severity="info" (click)="openProof()"></button>
              <button pButton label="Télécharger" icon="pi pi-download" [text]="true" (click)="downloadProof()"></button>
              <!-- PHASE-B-DOC-VIEW -->
            </div>
          }
          <table class="lines-table">"""
    if find_html not in src:
        print(f"[FAIL] FE bc-detail lines-table anchor not found", file=sys.stderr)
        sys.exit(1)
    src = src.replace(find_html, replace_html, 1)

    style_anchor = ".actions { display: flex; gap: 8px; border-top: 1px solid #e2e8f0; padding-top: 16px; }"
    if style_anchor in src:
        src = src.replace(style_anchor, style_anchor + "\n    .doc-link { display: flex; align-items: center; gap: 8px; margin-bottom: 16px; padding: 10px 12px; background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px; font-size: 14px; }\n    .doc-link i { color: #dc2626; }\n    .doc-label { font-weight: 600; color: #475569; margin-right: 4px; }", 1)

    write(ts, src)
    print(f"[OK]   FE bc-detail.component.ts: patched ({ts.relative_to(ROOT)})")

# ----------------------------------------------------------------------------
def main():
    patch_backend()
    patch_fe_service()
    patch_documents_list()
    patch_document_detail()
    patch_facture_detail()
    patch_movement_detail()
    patch_bl_detail()
    patch_bc_detail()
    print("\nAll Phase B patches applied.")

if __name__ == '__main__':
    main()
