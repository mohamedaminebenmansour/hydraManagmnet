import { Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { DropdownModule } from 'primeng/dropdown';
import { TagModule } from 'primeng/tag';
import { ChipModule } from 'primeng/chip';
import { MessageService } from 'primeng/api';
import { DocumentService } from '@app/core/services/document.service';
import {
  DocStatus, DocType, DOC_TYPE_OPTS, DOC_TYPE_LABELS,
  DOC_STATUS_LABELS, DOC_STATUS_SEVERITY, Document
} from '@app/core/models/document.model';
import { EmptyStateComponent } from '@app/shared/empty-state.component';

@Component({
  selector: 'app-register',
  standalone: true,
  imports: [
    FormsModule, ButtonModule, InputTextModule, DropdownModule, TagModule,
    ChipModule, EmptyStateComponent
  ],
  templateUrl: './register.component.html',
  styleUrl: './register.component.scss'
})
export class RegisterComponent implements OnInit {
  private documentService = inject(DocumentService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private msgs = inject(MessageService);

  documents = signal<Document[]>([]);
  loading = signal(true);
  total = signal(0);
  page = signal(0);
  size = 50;
  projectId: string | null = null;

  q = '';
  type: DocType | null = null;
  status: DocStatus | null = null;
  typeOpts = [{ label: 'Tous les types', value: null as any }, ...DOC_TYPE_OPTS];
  statusOpts = [
    { label: 'Tous les statuts', value: null as any },
    { label: 'Brouillon', value: 'DRAFT' as DocStatus },
    { label: 'En revue', value: 'IN_REVIEW' as DocStatus },
    { label: 'Approuvé', value: 'APPROVED' as DocStatus },
    { label: 'Rejeté', value: 'REJECTED' as DocStatus },
    { label: 'Obsolète', value: 'SUPERSEDED' as DocStatus },
    { label: 'Archivé', value: 'ARCHIVED' as DocStatus }
  ];

  ngOnInit(): void {
    this.projectId = this.route.snapshot.paramMap.get('id');
    if (!this.projectId) { this.loading.set(false); return; }
    this.load();
  }

  load(): void {
    if (!this.projectId) { return; }
    this.loading.set(true);
    this.documentService.search(this.projectId, {
      q: this.q.trim() || null,
      type: this.type,
      status: this.status,
      page: this.page(),
      size: this.size
    }).subscribe({
      next: page => {
        this.documents.set(page.items ?? []);
        this.total.set(page.total ?? 0);
        this.loading.set(false);
      },
      error: () => {
        this.documents.set([]);
        this.loading.set(false);
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Chargement impossible' });
      }
    });
  }

  applyFilters(): void { this.page.set(0); this.load(); }
  clearFilters(): void { this.q = ''; this.type = null; this.status = null; this.page.set(0); this.load(); }
  prev(): void { if (this.page() > 0) { this.page.set(this.page() - 1); this.load(); } }
  next(): void { if ((this.page() + 1) * this.size < this.total()) { this.page.set(this.page() + 1); this.load(); } }

  get pageCount(): number { return Math.max(1, Math.ceil(this.total() / this.size)); }

  typeLabel(t: string | null | undefined): string { return t ? (DOC_TYPE_LABELS[t] ?? t) : '—'; }
  statusLabel(s: string | null | undefined): string { return s ? (DOC_STATUS_LABELS[s] ?? s) : '—'; }
  statusSeverity(s: string | null | undefined): 'success'|'info'|'warn'|'danger'|'secondary'|'contrast' { return (s && DOC_STATUS_SEVERITY[s]) || 'secondary'; }

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
}
