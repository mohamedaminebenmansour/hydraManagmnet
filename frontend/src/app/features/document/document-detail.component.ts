import { Component, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { SelectModule } from 'primeng/select';
import { TagModule } from 'primeng/tag';
import { ChipModule } from 'primeng/chip';
import { FileUpload, FileUploadModule } from 'primeng/fileupload';
import { ProgressBarModule } from 'primeng/progressbar';
import { MessageService } from 'primeng/api';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { DocumentService, OpenOutcome, isPreviewable } from '@app/core/services/document.service';
import { TicketService } from '@app/core/services/ticket.service';
import {
  DOC_TYPE_LABELS, DOC_STATUS_LABELS, DOC_STATUS_SEVERITY, Document, Revision
} from '@app/core/models/document.model';
import { Ticket, TICKET_STATUS_LABELS, TICKET_STATUS_SEVERITY } from '@app/core/models/ticket.model';
import { EmptyStateComponent } from '@app/shared/empty-state.component';
import { DocumentFileActionsComponent } from '@app/shared/document-file-actions.component'; // PHASE-B-DOC-ACTIONS

@Component({
  selector: 'app-document-detail',
  standalone: true,
  imports: [
    CommonModule, FormsModule, ButtonModule, DialogModule, SelectModule, TagModule, ChipModule,
    FileUploadModule, ProgressBarModule, EmptyStateComponent,
    DocumentFileActionsComponent // PHASE-B-DOC-ACTIONS
  ],
  templateUrl: './document-detail.component.html',
  styleUrl: './document-detail.component.scss'
})
export class DocumentDetailComponent implements OnInit {
  private documentService = inject(DocumentService);
  private ticketService = inject(TicketService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  doc = signal<Document | null>(null);
  revisions = signal<Revision[]>([]);
  loading = signal(true);
  notFound = signal(false);

  showRevDialog = false;
  revLabel = '';
  revNote = '';
  uploadProgress = signal(0);
  uploading = signal(false);
  @ViewChild('revFileUpload') revFileUpload?: FileUpload;

  docId: string | null = null;
  canUpload = computed(() => this.auth.can(PERM.DOCUMENT_UPLOAD));
  canLink = computed(() => this.auth.can(PERM.DOCUMENT_UPLOAD) || this.auth.can(PERM.TICKET_WRITE));

  // ── link / unlink ticket ──────────────────────────────────────────
  showLinkDialog = false;
  linking = signal(false);
  ticketOptions = signal<Ticket[]>([]);
  selectedTicketId: string | null = null;

  ngOnInit(): void {
    this.docId = this.route.snapshot.paramMap.get('id');
    if (!this.docId) { this.notFound.set(true); this.loading.set(false); return; }
    this.load();
  }

  private load(): void {
    if (!this.docId) { return; }
    this.loading.set(true);
    this.documentService.get(this.docId).subscribe({
      next: d => { this.doc.set(d); this.loading.set(false); this.loadRevisions(); },
      error: err => {
        this.loading.set(false);
        if (err.status === 404 || err.status === 403) { this.notFound.set(true); }
        else { this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Chargement impossible' }); }
      }
    });
  }

  private loadRevisions(): void {
    if (!this.docId) { return; }
    this.documentService.revisions(this.docId).subscribe({
      next: list => this.revisions.set(list ?? []),
      error: () => this.revisions.set([])
    });
  }

  typeLabel(t: string | null | undefined): string { return t ? (DOC_TYPE_LABELS[t] ?? t) : '—'; }
  statusLabel(s: string | null | undefined): string { return s ? (DOC_STATUS_LABELS[s] ?? s) : '—'; }
  statusSeverity(s: string | null | undefined): 'success'|'info'|'warn'|'danger'|'secondary'|'contrast' { return (s && DOC_STATUS_SEVERITY[s]) || 'secondary'; }

  fmtDate(iso: string | null | undefined): string {
    if (!iso) { return '—'; }
    const d = new Date(iso);
    if (isNaN(d.getTime())) { return iso; }
    return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  fmtSize(bytes: number | null | undefined): string {
    if (bytes === null || bytes === undefined) { return '—'; }
    if (bytes < 1024) { return bytes + ' o'; }
    if (bytes < 1024 * 1024) { return (bytes / 1024).toFixed(1) + ' Ko'; }
    return (bytes / (1024 * 1024)).toFixed(1) + ' Mo';
  }

  back(): void {
    const d = this.doc();
    if (d?.projectId && d?.folderId) { this.router.navigate(['/projects', d.projectId, 'folders', d.folderId]); }
    else if (d?.projectId) { this.router.navigate(['/projects', d.projectId]); }
    else { this.router.navigate(['/projects']); }
  }

  download(r: Revision): void {
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
  canPreview(r: Revision): boolean { return isPreviewable(r.mimeType); }

  openRev(): void { this.revLabel = ''; this.revNote = ''; this.showRevDialog = true; }

  onRevFile(event: { files: File[] }): void {
    const file = event?.files?.[0];
    if (!file || !this.docId) { return; }
    this.uploading.set(true);
    this.uploadProgress.set(0);
    const form = new FormData();
    form.append('file', file, file.name);
    if (this.revLabel.trim()) { form.append('revision', this.revLabel.trim()); }
    if (this.revNote.trim()) { form.append('changeNote', this.revNote.trim()); }

    this.documentService.uploadRevision(this.docId, form).subscribe({
      next: ev => {
        if (ev.type === 'progress') { this.uploadProgress.set(ev.percent); }
        else if (ev.type === 'done') {
          this.uploadProgress.set(100);
          this.msgs.add({ severity: 'success', summary: 'Révision téléversée', detail: ev.result.revLabel });
          this.uploading.set(false);
          this.revFileUpload?.clear();
          this.showRevDialog = false;
          this.load();
        }
      },
      error: err => {
        this.uploading.set(false);
        this.revFileUpload?.clear();
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err.error?.message ?? 'Révision impossible' });
      }
    });
  }

  // ── link / unlink ticket (document → ticket) ────────────────────────
  openLink(): void {
    const d = this.doc();
    this.selectedTicketId = d?.ticketId ?? null;
    this.showLinkDialog = true;
    // Load candidate tickets for this document's project.
    if (d?.projectId) {
      this.ticketService.list({ projectId: d.projectId }).subscribe({
        next: list => this.ticketOptions.set(list ?? []),
        error: () => this.ticketOptions.set([])
      });
    } else {
      this.ticketOptions.set([]);
    }
  }

  ticketLabel(t: Ticket): string {
    return `${TICKET_STATUS_LABELS[t.status] ?? t.status} — ${t.title}`;
  }

  saveLink(): void {
    if (!this.docId) { return; }
    this.linking.set(true);
    // selectedTicketId === null → unlink (backend treats null ticketId as clear).
    this.documentService.linkTicket(this.docId, this.selectedTicketId).subscribe({
      next: () => {
        this.linking.set(false);
        this.showLinkDialog = false;
        this.msgs.add({
          severity: 'success',
          summary: this.selectedTicketId ? 'Ticket lié' : 'Ticket délié'
        });
        this.load(); // refresh ticketId on the document
      },
      error: err => {
        this.linking.set(false);
        this.msgs.add({ severity: 'error', summary: 'Erreur',
          detail: err.error?.message ?? 'Liaison impossible' });
      }
    });
  }

  unlinkTicket(): void {
    if (!this.docId) { return; }
    this.linking.set(true);
    this.documentService.linkTicket(this.docId, null).subscribe({
      next: () => {
        this.linking.set(false);
        this.msgs.add({ severity: 'success', summary: 'Ticket délié' });
        this.load();
      },
      error: err => {
        this.linking.set(false);
        this.msgs.add({ severity: 'error', summary: 'Erreur',
          detail: err.error?.message ?? 'Déliaison impossible' });
      }
    });
  }

  ticketStatusLabel(s: string): string { return TICKET_STATUS_LABELS[s] ?? s; }
  ticketStatusSeverity(s: string): 'success'|'info'|'warn'|'danger'|'secondary'|'contrast' {
    return TICKET_STATUS_SEVERITY[s] ?? 'secondary';
  }

  openTicket(): void {
    const d = this.doc();
    if (d?.ticketId) { this.router.navigate(['/tickets', d.ticketId]); }
  }
}
