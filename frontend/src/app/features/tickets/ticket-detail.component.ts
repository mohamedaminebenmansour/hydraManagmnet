import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { DialogModule } from 'primeng/dialog';
import { MessageService } from 'primeng/api';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { TicketService, PhotoResponse, TimelineEntry } from '@app/core/services/ticket.service';
import { Ticket } from '@app/core/models/ticket.model';
import { EmptyStateComponent } from '@app/shared/empty-state.component';
import { LoadErrorComponent } from '@app/shared/load-error.component';
import { typeVisual, statusVisual } from './signalement-visual';
import { compressImage } from '@app/shared/image-compress';
import { roleVisual } from '@app/shared/hydra-design';

interface PhotoItem { file: File; url: string; }

@Component({
  selector: 'app-ticket-detail',
  standalone: true,
  imports: [FormsModule, ButtonModule, InputTextModule, TextareaModule, DialogModule,
    EmptyStateComponent, LoadErrorComponent],
  templateUrl: './ticket-detail.component.html',
  styleUrl: './ticket-detail.component.scss'
})
export class TicketDetailComponent implements OnInit {
  private ticketService = inject(TicketService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  ticket = signal<Ticket | null>(null);
  actions = signal<string[]>([]);
  timeline = signal<TimelineEntry[]>([]);
  photos = signal<PhotoResponse[]>([]);
  loading = signal(true);
  notFound = signal(false);
  ticketId: string | null = null;
  canWrite = computed(() => this.auth.can(PERM.TICKET_WRITE));

  // dialogs
  showResolve = signal(false);
  showVerify = signal(false);
  showReopen = signal(false);
  showCancel = signal(false);
  resolveNote = signal('');
  resolveAfterPhotos = signal<PhotoItem[]>([]);
  resolveCompressing = signal(false);
  reopenComment = signal('');
  cancelReason = signal('');
  commentBody = signal('');
  sending = signal(false);
  photoPreviewUrl = signal<string | null>(null);

  isVisa = computed(() => this.ticket()?.type === 'VISA_DOCUMENT');
  isOverdue = computed(() => this.ticket()?.overdue ?? false);

  primaryAction = computed(() => {
    if (this.isVisa()) { return null; }
    const a = this.actions();
    if (a.includes('VERIFY')) { return 'VERIFY'; }
    if (a.includes('RESOLVE')) { return 'RESOLVE'; }
    if (a.includes('START')) { return 'START'; }
    return null;
  });
  secondaryActions = computed(() => {
    if (this.isVisa()) { return []; }
    return this.actions().filter(x => x === 'REOPEN' || x === 'CANCEL');
  });
  visaTransitions = computed(() => this.isVisa() ? this.actions() : []);
  beforePhotos = computed(() => this.photos().filter(p => p.phase === 'BEFORE'));
  afterPhotos = computed(() => this.photos().filter(p => p.phase === 'AFTER'));
  canAddPhoto = computed(() => this.actions().includes('ADD_PHOTO'));
  creatorName = computed(() => this.timeline().find(e => e.kind === 'SYSTEM')?.authorName ?? null);

  readonly actionLabels: Record<string, string> = {
    START: 'Démarrer', RESOLVE: 'Marquer résolu', VERIFY: 'Vérifier et clore',
    REOPEN: 'Rouvrir', CANCEL: 'Annuler le signalement'
  };

  typeV = typeVisual;
  statusV = statusVisual;
  roleV = roleVisual;

  ngOnInit(): void {
    this.ticketId = this.route.snapshot.paramMap.get('id');
    if (!this.ticketId) { this.notFound.set(true); this.loading.set(false); return; }
    this.loadAll();
  }

  loadAll(): void {
    if (!this.ticketId) { return; }
    this.loading.set(true);
    this.notFound.set(false);
    this.ticketService.get(this.ticketId).subscribe({
      next: t => { this.ticket.set(t); this.loading.set(false); this.loadSecondary(); },
      error: err => {
        this.loading.set(false);
        if (err.status === 404 || err.status === 403) { this.notFound.set(true); }
        else { this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Chargement impossible' }); }
      }
    });
  }

  private loadSecondary(): void {
    if (!this.ticketId) { return; }
    this.ticketService.actions(this.ticketId).subscribe({ next: a => this.actions.set(a ?? []), error: () => this.actions.set([]) });
    this.ticketService.timeline(this.ticketId!).subscribe({ next: t => this.timeline.set(t ?? []), error: () => this.timeline.set([]) });
    this.ticketService.photos(this.ticketId!).subscribe({ next: p => this.photos.set(p ?? []), error: () => this.photos.set([]) });
  }

  doAction(code: string): void {
    if (!this.ticketId) { return; }
    if (this.isVisa()) {
      // VISA: code is a status code → setStatus
      this.ticketService.setStatus(this.ticketId, code).subscribe({
        next: t => { this.ticket.set(t); this.msgs.add({ severity: 'success', summary: 'Statut mis à jour' }); this.loadSecondary(); },
        error: err => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err.error?.message ?? 'Transition impossible' })
      });
      return;
    }
    switch (code) {
      case 'START':
        this.ticketService.start(this.ticketId).subscribe({
          next: () => { this.msgs.add({ severity: 'success', summary: 'Démarré' }); this.loadAll(); },
          error: err => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err.error?.message ?? 'Action impossible' })
        });
        break;
      case 'RESOLVE': this.showResolve.set(true); break;
      case 'VERIFY': this.showVerify.set(true); break;
      case 'REOPEN': this.showReopen.set(true); break;
      case 'CANCEL': this.showCancel.set(true); break;
    }
  }

  // ── resolve: upload after photos, then resolve ─────────────────────────
  async onPickAfterPhoto(event: Event): Promise<void> {
    const input = event.target as HTMLInputElement;
    if (!input.files?.length) { return; }
    this.resolveCompressing.set(true);
    try {
      for (let i = 0; i < input.files.length; i++) {
        const compressed = await compressImage(input.files[i]);
        this.resolveAfterPhotos.update(arr => [...arr, { file: compressed, url: URL.createObjectURL(compressed) }]);
      }
    } finally { this.resolveCompressing.set(false); input.value = ''; }
  }

  removeAfterPhoto(i: number): void {
    this.resolveAfterPhotos.update(arr => { URL.revokeObjectURL(arr[i]?.url ?? ''); return arr.filter((_, idx) => idx !== i); });
  }

  async confirmResolve(): Promise<void> {
    if (!this.ticketId || this.resolveNote().trim().length < 5) { return; }
    this.sending.set(true);
    // upload after photos first
    for (const p of this.resolveAfterPhotos()) {
      try {
        await this.ticketService.addPhoto(this.ticketId, p.file, 'AFTER').toPromise();
      } catch {
        this.sending.set(false);
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Upload de la photo échoué' });
        return;
      }
    }
    // then resolve
    this.ticketService.resolve(this.ticketId, this.resolveNote().trim()).subscribe({
      next: () => {
        this.sending.set(false); this.showResolve.set(false);
        this.resolveNote.set(''); this.resolveAfterPhotos.set([]);
        this.msgs.add({ severity: 'success', summary: 'Signalement résolu' });
        this.loadAll();
      },
      error: err => {
        this.sending.set(false);
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err.error?.message ?? 'Résolution impossible' });
        // photos stay attached; keep dialog open
      }
    });
  }

  confirmVerify(): void {
    if (!this.ticketId) { return; }
    this.sending.set(true);
    this.ticketService.verify(this.ticketId).subscribe({
      next: () => { this.sending.set(false); this.showVerify.set(false); this.msgs.add({ severity: 'success', summary: 'Signalement clos' }); this.loadAll(); },
      error: err => { this.sending.set(false); this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err.error?.message ?? 'Vérification impossible' }); }
    });
  }

  confirmReopen(): void {
    if (!this.ticketId || this.reopenComment().trim().length < 5) { return; }
    this.sending.set(true);
    this.ticketService.reopen(this.ticketId, this.reopenComment().trim()).subscribe({
      next: () => { this.sending.set(false); this.showReopen.set(false); this.reopenComment.set(''); this.msgs.add({ severity: 'success', summary: 'Rouvert' }); this.loadAll(); },
      error: err => { this.sending.set(false); this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err.error?.message ?? 'Réouverture impossible' }); }
    });
  }

  confirmCancel(): void {
    if (!this.ticketId || this.cancelReason().trim().length < 5) { return; }
    this.sending.set(true);
    this.ticketService.cancel(this.ticketId, this.cancelReason().trim()).subscribe({
      next: () => { this.sending.set(false); this.showCancel.set(false); this.cancelReason.set(''); this.msgs.add({ severity: 'success', summary: 'Signalement annulé' }); this.loadAll(); },
      error: err => { this.sending.set(false); this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err.error?.message ?? 'Annulation impossible' }); }
    });
  }

  sendComment(): void {
    if (!this.ticketId || !this.commentBody().trim()) { return; }
    this.ticketService.comment(this.ticketId, this.commentBody().trim()).subscribe({
      next: () => { this.commentBody.set(''); this.msgs.add({ severity: 'success', summary: 'Commentaire envoyé' });
        this.ticketService.timeline(this.ticketId!).subscribe({ next: t => this.timeline.set(t ?? []), error: () => {} }); },
      error: err => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err.error?.message ?? 'Envoi impossible' })
    });
  }

  onCommentKeydown(event: KeyboardEvent): void {
    if (event.ctrlKey && event.key === 'Enter') { this.sendComment(); }
  }

  async onAddPhoto(event: Event, phase: string): Promise<void> {
    const input = event.target as HTMLInputElement;
    if (!input.files?.length || !this.ticketId) { return; }
    try {
      const compressed = await compressImage(input.files[0]);
      this.ticketService.addPhoto(this.ticketId, compressed, phase).subscribe({
        next: () => { this.msgs.add({ severity: 'success', summary: 'Photo ajoutée' });
          this.ticketService.photos(this.ticketId!).subscribe({ next: p => this.photos.set(p ?? []), error: () => {} }); },
        error: err => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err.error?.message ?? 'Ajout impossible' })
      });
    } finally { input.value = ''; }
  }

  deletePhoto(attId: string): void {
    if (!this.ticketId) { return; }
    this.ticketService.deletePhoto(this.ticketId, attId).subscribe({
      next: () => { this.msgs.add({ severity: 'success', summary: 'Photo supprimée' });
        this.ticketService.photos(this.ticketId!).subscribe({ next: p => this.photos.set(p ?? []), error: () => {} }); },
      error: err => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err.error?.message ?? 'Suppression impossible' })
    });
  }

  // ── helpers ────────────────────────────────────────────────────────────
  back(): void { this.router.navigate(['/tickets']); }

  fmtDate(s: string | null | undefined): string {
    if (!s) { return '—'; }
    const d = new Date(s);
    if (isNaN(d.getTime())) { return s; }
    return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  relativeTime(iso: string): string {
    const d = new Date(iso); const now = Date.now();
    const diff = (now - d.getTime()) / 1000;
    if (diff < 60) { return "à l'instant"; }
    if (diff < 3600) { return `il y a ${Math.floor(diff / 60)} min`; }
    if (diff < 86400) { return `il y a ${Math.floor(diff / 3600)} h`; }
    if (diff < 86400 * 7) { return `il y a ${Math.floor(diff / 86400)} j`; }
    return this.fmtDate(iso);
  }

  relativeDue(dueAt: string | null | undefined, status: string | null | undefined): string {
    if (!dueAt) { return '—'; }
    const d = new Date(dueAt); const today = new Date(); today.setHours(0, 0, 0, 0);
    const diff = Math.round((d.getTime() - today.getTime()) / 86400000);
    if (status && ['CLOSED', 'CANCELLED', 'AS_BUILT'].includes(status)) { return this.fmtDate(dueAt); }
    if (diff < 0) { return `en retard de ${-diff} jour${-diff > 1 ? 's' : ''}`; }
    if (diff === 0) { return "aujourd'hui"; }
    return `dans ${diff} jour${diff > 1 ? 's' : ''}`;
  }

  isDueOverdue(dueAt: string | null | undefined, status: string | null | undefined): boolean {
    if (!dueAt || !status) { return false; }
    if (['CLOSED', 'CANCELLED', 'AS_BUILT'].includes(status)) { return false; }
    return new Date(dueAt) < new Date(new Date().toDateString());
  }

  timelineText(e: TimelineEntry): string {
    const name = e.authorName ?? 'Quelqu\'un';
    const role = e.authorRole ? ` (${this.roleV(e.authorRole).label})` : '';
    if (e.kind === 'STATUS_CHANGE' && e.toStatusLabel) {
      return `${name}${role} a passé en ${e.toStatusLabel}`;
    }
    if (e.kind === 'SYSTEM') { return e.body ?? ''; }
    return name + role;
  }
}
