import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { ChipModule } from 'primeng/chip';
import { MessageService } from 'primeng/api';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { DocumentService } from '@app/core/services/document.service';
import { TicketService } from '@app/core/services/ticket.service';
import {
  Ticket, TicketDocument, TicketStatus,
  TICKET_STATUS_LABELS, TICKET_STATUS_SEVERITY, TICKET_STATUS_FLOW,
  TICKET_PRIORITY_LABELS
} from '@app/core/models/ticket.model';
import { EmptyStateComponent } from '@app/shared/empty-state.component';
import { LoadErrorComponent } from '@app/shared/load-error.component';

@Component({
  selector: 'app-ticket-detail',
  standalone: true,
  imports: [ButtonModule, TagModule, ChipModule, EmptyStateComponent, LoadErrorComponent],
  templateUrl: './ticket-detail.component.html',
  styleUrl: './ticket-detail.component.scss'
})
export class TicketDetailComponent implements OnInit {
  private ticketService = inject(TicketService);
  private documentService = inject(DocumentService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  ticket = signal<Ticket | null>(null);
  docs = signal<TicketDocument[]>([]);
  docsLoading = signal(true);
  docsError = signal(false);
  transitions = signal<string[]>([]);
  loading = signal(true);
  notFound = signal(false);
  unlinking = signal<string | null>(null);

  ticketId: string | null = null;
  canWrite = computed(() => this.auth.can(PERM.TICKET_WRITE));

  ngOnInit(): void {
    this.ticketId = this.route.snapshot.paramMap.get('id');
    if (!this.ticketId) { this.notFound.set(true); this.loading.set(false); return; }
    this.load();
  }

  private load(): void {
    if (!this.ticketId) { return; }
    this.loading.set(true);
    this.ticketService.get(this.ticketId).subscribe({
      next: t => {
        this.ticket.set(t);
        this.loading.set(false);
        this.loadDocs();
        this.loadTransitions();
      },
      error: err => {
        this.loading.set(false);
        if (err.status === 404 || err.status === 403) { this.notFound.set(true); }
        else { this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Chargement impossible' }); }
      }
    });
  }

  loadDocs(): void {
    if (!this.ticketId) { return; }
    this.docsLoading.set(true);
    this.docsError.set(false);
    this.ticketService.documents(this.ticketId).subscribe({
      next: list => { this.docs.set(list ?? []); this.docsLoading.set(false); },
      error: () => { this.docs.set([]); this.docsError.set(true); this.docsLoading.set(false); }
    });
  }

  private loadTransitions(): void {
    if (!this.ticketId) { return; }
    this.ticketService.transitions(this.ticketId).subscribe({
      next: list => this.transitions.set(list ?? this.localTransitions()),
      error: () => this.transitions.set(this.localTransitions())
    });
  }

  /** Fallback if the backend transitions endpoint is unavailable. */
  private localTransitions(): TicketStatus[] {
    const t = this.ticket();
    if (!t) { return []; }
    return TICKET_STATUS_FLOW[(t.status as TicketStatus)] ?? [];
  }

  statusLabel(s: string | null | undefined): string { return s ? (TICKET_STATUS_LABELS[s] ?? s) : '—'; }
  statusSeverity(s: string | null | undefined): 'success'|'info'|'warn'|'danger'|'secondary'|'contrast' {
    return (s && TICKET_STATUS_SEVERITY[s]) || 'secondary';
  }
  priorityLabel(p: string | null | undefined): string { return p ? (TICKET_PRIORITY_LABELS[p] ?? p) : '—'; }

  fmtDate(s: string | null | undefined): string {
    if (!s) { return '—'; }
    const d = new Date(s);
    if (isNaN(d.getTime())) { return s; }
    return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  back(): void { this.router.navigate(['/tickets']); }

  advance(next: string): void {
    if (!this.ticketId) { return; }
    this.ticketService.setStatus(this.ticketId, next).subscribe({
      next: t => {
        this.ticket.set(t);
        this.msgs.add({ severity: 'success', summary: 'Statut mis à jour',
          detail: TICKET_STATUS_LABELS[next] ?? next });
        this.loadTransitions();
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err.error?.message ?? 'Transition impossible' })
    });
  }

  openDoc(d: TicketDocument): void { this.router.navigate(['/documents', d.id]); }

  unlink(d: TicketDocument): void {
    this.unlinking.set(d.id);
    this.documentService.linkTicket(d.id, null).subscribe({
      next: () => {
        this.unlinking.set(null);
        this.msgs.add({ severity: 'success', summary: 'Document dissocié' });
        this.loadDocs();
      },
      error: (err) => {
        this.unlinking.set(null);
        this.msgs.add({ severity: 'error', summary: 'Erreur',
          detail: err.error?.message ?? 'Dissociation impossible' });
      }
    });
  }
}
