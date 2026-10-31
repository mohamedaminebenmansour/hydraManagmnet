import { Component, Input, OnInit, computed, inject, signal } from '@angular/core';
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
      this.msgs.add({ severity: 'warn', summary: 'Aucune révision', detail: 'Le document n\'a pas encore de révision.' });
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
      this.msgs.add({ severity: 'warn', summary: 'Aucune révision', detail: 'Le document n\'a pas encore de révision.' });
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
