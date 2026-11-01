import { Component, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { DropdownModule } from 'primeng/dropdown';
import { DialogModule } from 'primeng/dialog';
import { TagModule } from 'primeng/tag';
import { ChipModule } from 'primeng/chip';
import { TooltipModule } from 'primeng/tooltip'; // PHASE-B-DOC-ACTIONS-UX
import { MultiSelectModule } from 'primeng/multiselect';
import { FileUpload, FileUploadModule } from 'primeng/fileupload';
import { ProgressBarModule } from 'primeng/progressbar';
import { MessageService } from 'primeng/api';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { DocumentService } from '@app/core/services/document.service';
import { FolderService } from '@app/core/services/folder.service';
import { ProjectService } from '@app/core/services/project.service';
import { TicketService } from '@app/core/services/ticket.service';
import {
  DocType, DOC_TYPE_OPTS, DOC_TYPE_LABELS,
  DOC_STATUS_LABELS, DOC_STATUS_SEVERITY, Document, Revision
} from '@app/core/models/document.model';
import { OpenOutcome, isPreviewable } from '@app/core/services/document.service';
import { Ticket } from '@app/core/models/ticket.model';
import { Folder, childFolders, folderTotalCount, folderTreeOptions } from '@app/core/models/folder.model';
import { EmptyStateComponent } from '@app/shared/empty-state.component';
import { DocumentFileActionsComponent } from '@app/shared/document-file-actions.component'; // PHASE-B-DOC-ACTIONS
import { docTypeVisual, docStatusVisual } from '@app/shared/chantier-visual';

@Component({
  selector: 'app-documents',
  standalone: true,
  imports: [
    FormsModule, ButtonModule, InputTextModule, TextareaModule, DropdownModule,
    DialogModule, TagModule, ChipModule, MultiSelectModule, FileUploadModule,
    TooltipModule,
    ProgressBarModule, EmptyStateComponent,
    DocumentFileActionsComponent // PHASE-B-DOC-ACTIONS
  ],
  templateUrl: './documents.component.html',
  styleUrl: './documents.component.scss'
})
export class DocumentsComponent implements OnInit {
  private documentService = inject(DocumentService);
  private folderService = inject(FolderService);
  private projectService = inject(ProjectService);
  private ticketService = inject(TicketService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  folder = signal<Folder | null>(null);
  /** All folders of the chantier the user can see (for sub-folders, breadcrumb, picker). */
  allFolders = signal<Folder[]>([]);
  parentFolder = computed(() => {
    const pid = this.folder()?.parentId;
    return pid ? this.allFolders().find(f => f.id === pid) ?? null : null;
  });
  subFolders = computed(() => {
    const f = this.folder();
    return f ? childFolders(this.allFolders(), f.id) : [];
  });
  /** Upload target picker: main folder, then indented sub-folders. */
  folderOptions = computed(() => folderTreeOptions(this.allFolders()));
  uploadFolderId: string | null = null;
  documents = signal<Document[]>([]);
  loading = signal(true);
  notFound = signal(false);

  showUploadDialog = false;
  uploadTitle = '';
  uploadDescription = '';
  uploadDocType: DocType | null = null;
  uploadRevision = '';
  uploadTicketId: string | null = null;
  uploadTags: string[] = [];
  tagOptions = signal<string[]>([]);
  ticketOptions = signal<Ticket[]>([]);
  docTypeOpts = DOC_TYPE_OPTS;

  showRevDialog = false;
  revTarget: Document | null = null;
  revLabel = '';
  revNote = '';

  uploadProgress = signal(0);
  uploading = signal(false);
  @ViewChild('fileUpload') fileUpload?: FileUpload;
  @ViewChild('revFileUpload') revFileUpload?: FileUpload;

  showRemoveDialog = false;
  removeTarget: Document | null = null;

  projectId: string | null = null;
  folderId: string | null = null;

  // Defaults to true so the button shows optimistically; refined by /me.
  // A 403/network error keeps it true — the backend still enforces on upload.
  projectCanUpload = signal(true);

  canUpload = computed(() => this.auth.can(PERM.DOCUMENT_UPLOAD) && this.projectCanUpload());
  canDelete = computed(() => this.auth.can(PERM.DOCUMENT_DELETE));

  ngOnInit(): void {
    // paramMap (not snapshot): opening a sub-folder reuses this component.
    this.route.paramMap.subscribe(params => {
      const projectChanged = params.get('projectId') !== this.projectId;
      this.projectId = params.get('projectId');
      this.folderId = params.get('folderId');
      if (!this.folderId || !this.projectId) {
        this.notFound.set(true); this.loading.set(false); return;
      }
      this.notFound.set(false);
      this.loadFolders();
      this.loadDocuments();
      if (projectChanged) {
        this.projectService.myPermissions(this.projectId).subscribe({
          next: p => this.projectCanUpload.set(!!p?.canUpload),
          error: () => this.projectCanUpload.set(false) // non-member → cannot upload
        });
        this.loadOptions();
      }
    });
  }

  private loadFolders(): void {
    if (!this.projectId) { return; }
    this.folderService.listByProject(this.projectId).subscribe({
      next: list => {
        this.allFolders.set(list ?? []);
        const found = (list ?? []).find(f => f.id === this.folderId) ?? null;
        this.folder.set(found);
        if (!found) { this.notFound.set(true); }
      },
      error: (err) => {
        this.notFound.set(true);
        if (err.status !== 403 && err.status !== 404) {
          this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Chargement impossible' });
        }
      }
    });
  }

  openFolder(f: Folder): void {
    if (this.projectId) { this.router.navigate(['/projects', this.projectId, 'folders', f.id]); }
  }

  subFolderCount(f: Folder): number {
    return folderTotalCount(this.allFolders(), f);
  }

  private loadDocuments(): void {
    if (!this.projectId) { return; }
    this.loading.set(true);
    this.documentService.search(this.projectId, { folderId: this.folderId, size: 200 }).subscribe({
      next: page => { this.documents.set(page.items ?? []); this.loading.set(false); },
      error: err => {
        // List failure (incl. 500): keep an empty list (never crash) but show
        // the API's message when the body carries one.
        this.documents.set([]);
        this.loading.set(false);
        this.msgs.add({ severity: 'error', summary: 'Erreur',
          detail: this.apiErrMsg(err, 'Impossible de charger les documents') });
      }
    });
  }

  /**
   * Extract the human-readable message from an API error body.
   * The backend always answers JSON (GlobalExceptionHandler):
   * { code, message } — older paths may carry detail instead.
   */
  private apiErrMsg(err: any, fallback: string): string {
    const b = err?.error;
    if (typeof b === 'string' && b.trim()) { return b; }
    const m = b?.message ?? b?.detail ?? b?.code ?? err?.message;
    return (typeof m === 'string' && m.trim()) ? m : fallback;
  }

  private loadOptions(): void {
    this.documentService.tags().subscribe({
      next: t => this.tagOptions.set((t ?? []).map(x => x.name)),
      error: () => this.tagOptions.set([])
    });
    if (this.projectId) {
      this.ticketService.list({ projectId: this.projectId }).subscribe({
        next: list => this.ticketOptions.set(list ?? []),
        error: () => this.ticketOptions.set([])
      });
    }
  }

  typeLabel(t: string | null | undefined): string {
    return t ? (DOC_TYPE_LABELS[t] ?? t) : '—';
  }

  statusLabel(s: string | null | undefined): string {
    return s ? (DOC_STATUS_LABELS[s] ?? s) : '—';
  }

  statusSeverity(s: string | null | undefined): 'success'|'info'|'warn'|'danger'|'secondary'|'contrast' {
    return (s && DOC_STATUS_SEVERITY[s]) || 'secondary';
  }

  // Aides visuelles (langage icône + couleur)
  dv = docTypeVisual;
  dsv = docStatusVisual;

  fmtDate(iso: string | null | undefined): string {
    if (!iso) { return '—'; }
    const d = new Date(iso);
    if (isNaN(d.getTime())) { return iso; }
    return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  back(): void {
    this.router.navigate(this.projectId ? ['/projects', this.projectId] : ['/projects']);
  }

  view(d: Document): void {
    this.router.navigate(['/documents', d.id]);
  }

  download(d: Document): void {
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
  }

  private doDownload(d: Document, revId: string, name?: string | null): void {
    this.documentService.downloadRevision(d.id, revId).subscribe({
      next: blob => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = name || d.reference || 'document';
        a.click();
        URL.revokeObjectURL(url);
      },
      error: () => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Téléchargement impossible' })
    });
  }

  // ── upload (document + first revision) ─────────────────────────────
  openUpload(): void {
    this.uploadTitle = '';
    this.uploadDescription = '';
    this.uploadDocType = null;
    this.uploadRevision = '';
    this.uploadTicketId = null;
    this.uploadTags = [];
    this.uploadFolderId = this.folderId; // default: the open folder
    this.showUploadDialog = true;
  }

  private validateFile(file: File): string | null {
    if (file.size === 0) { return 'Le fichier sélectionné est vide.'; }
    const maxSize = 20 * 1024 * 1024; // 20 Mo
    if (file.size > maxSize) { return 'Fichier trop volumineux (max 20 Mo).'; }
    const allowed = ['.pdf', '.doc', '.docx', '.xls', '.xlsx', '.png', '.jpg', '.jpeg', '.gif', '.webp'];
    const ext = '.' + file.name.split('.').pop()?.toLowerCase();
    if (!allowed.includes(ext)) { return 'Type de fichier non autorisé (' + allowed.join(', ') + ').'; }
    return null;
  }

  onUploadFile(event: { files: File[] }): void {
    const file = event?.files?.[0];
    const targetFolderId = this.uploadFolderId ?? this.folderId;
    if (!file || !this.projectId || !targetFolderId) { return; }

    const validationErr = this.validateFile(file);
    if (validationErr) {
      this.msgs.add({ severity: 'error', summary: 'Fichier invalide', detail: validationErr });
      this.fileUpload?.clear();
      return;
    }

    if (!this.uploadTitle.trim() || !this.uploadDocType) {
      this.msgs.add({ severity: 'warn', summary: 'Titre et type requis' });
      this.fileUpload?.clear();
      return;
    }
    this.uploading.set(true);
    this.uploadProgress.set(0);

    const form = new FormData();
    form.append('file', file, file.name);
    form.append('title', this.uploadTitle.trim());
    form.append('docType', this.uploadDocType);
    if (this.uploadRevision.trim()) { form.append('revision', this.uploadRevision.trim()); }
    if (this.uploadTicketId) { form.append('ticketId', this.uploadTicketId); }
    if (this.uploadDescription.trim()) { form.append('description', this.uploadDescription.trim()); }
    this.uploadTags.forEach(t => form.append('tagNames', t));

    this.documentService.upload(this.projectId, targetFolderId, form).subscribe({
      next: ev => {
        if (ev.type === 'progress') { this.uploadProgress.set(ev.percent); }
        else if (ev.type === 'done') {
          this.uploadProgress.set(100);
          const elsewhere = targetFolderId !== this.folderId
            ? ' — dans « ' + (this.allFolders().find(f => f.id === targetFolderId)?.name ?? 'autre dossier') + ' »'
            : '';
          this.msgs.add({ severity: 'success', summary: 'Document téléversé', detail: ev.result.reference + elsewhere });
          this.uploading.set(false);
          this.fileUpload?.clear();
          this.showUploadDialog = false;
          this.loadDocuments();
          this.loadFolders(); // counts
      },
      error: err => {
        this.uploading.set(false);
        this.fileUpload?.clear();
        this.msgs.add({ severity: 'error', summary: 'Erreur',
          detail: this.apiErrMsg(err, err.status === 413
            ? 'Fichier trop volumineux (max 50 Mo)'
            : 'Téléversement impossible') });
      }
    });
  }

  // ── new revision ──────────────────────────────────────────────────
  openRev(d: Document): void {
    this.revTarget = d;
    this.revLabel = '';
    this.revNote = '';
    this.showRevDialog = true;
  }

  onRevFile(event: { files: File[] }): void {
    const file = event?.files?.[0];
    if (!file || !this.revTarget) { return; }

    const validationErr = this.validateFile(file);
    if (validationErr) {
      this.msgs.add({ severity: 'error', summary: 'Fichier invalide', detail: validationErr });
      this.revFileUpload?.clear();
      return;
    }

    this.uploading.set(true);
    this.uploadProgress.set(0);

    const form = new FormData();
    form.append('file', file, file.name);
    if (this.revLabel.trim()) { form.append('revision', this.revLabel.trim()); }
    if (this.revNote.trim()) { form.append('changeNote', this.revNote.trim()); }

    this.documentService.uploadRevision(this.revTarget.id, form).subscribe({
      next: ev => {
        if (ev.type === 'progress') { this.uploadProgress.set(ev.percent); }
        else if (ev.type === 'done') {
          this.uploadProgress.set(100);
          this.msgs.add({ severity: 'success', summary: 'Révision téléversée', detail: ev.result.revLabel });
          this.uploading.set(false);
          this.revFileUpload?.clear();
          this.showRevDialog = false;
          this.loadDocuments();
        }
      },
      error: err => {
        this.uploading.set(false);
        this.revFileUpload?.clear();
        this.msgs.add({ severity: 'error', summary: 'Erreur',
          detail: this.apiErrMsg(err, err.status === 413
            ? 'Fichier trop volumineux (max 50 Mo)'
            : 'Révision impossible') });
      }
    });
  }

  // ── delete ────────────────────────────────────────────────────────
  confirmRemove(d: Document): void {
    this.removeTarget = d;
    this.showRemoveDialog = true;
  }

  doRemove(): void {
    if (!this.removeTarget) { return; }
    this.documentService.remove(this.removeTarget.id).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Document supprimé' });
        this.showRemoveDialog = false;
        this.loadDocuments();
      },
      error: err => this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: this.apiErrMsg(err, 'Suppression impossible') })
    });
  }
}

