import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { SelectModule } from 'primeng/select';
import { DatePickerModule } from 'primeng/datepicker';
import { DomainEvent, DomainEventService, DOMAIN_EVENT_TYPES } from '@app/core/services/domain-event.service';
import { ProjectService } from '@app/core/services/project.service';
import { EmptyStateComponent } from '@app/shared/empty-state.component';
import { LoadErrorComponent } from '@app/shared/load-error.component';

/** Owner-only "Journal des événements": business events (GET /api/events), newest first. */
@Component({
  selector: 'app-events',
  standalone: true,
  imports: [DatePipe, FormsModule, RouterLink, ButtonModule, SelectModule, DatePickerModule,
            EmptyStateComponent, LoadErrorComponent],
  template: `
    <div class="ev-head">
      <h2><i class="pi pi-bolt" aria-hidden="true"></i> Journal des événements</h2>
      <p class="ev-sub">Les actions métier enregistrées (paiements, réceptions, factures, bordereaux, documents…).</p>
    </div>

    <div class="ev-filters">
      <p-select [options]="typeOptions" [(ngModel)]="fType" optionLabel="label" optionValue="value"
                placeholder="Tous les types" [showClear]="true" styleClass="ev-f" ariaLabel="Type d'événement" />
      <p-select [options]="projectOptions()" [(ngModel)]="fProject" optionLabel="name" optionValue="id"
                placeholder="Tous les chantiers" [showClear]="true" [filter]="true" styleClass="ev-f"
                ariaLabel="Chantier" />
      <p-datepicker [(ngModel)]="fFrom" dateFormat="dd/mm/yy" placeholder="Du" [showIcon]="true"
                    styleClass="ev-f" inputId="ev-from" ariaLabel="Du" />
      <p-datepicker [(ngModel)]="fTo" dateFormat="dd/mm/yy" placeholder="Au" [showIcon]="true"
                    styleClass="ev-f" inputId="ev-to" ariaLabel="Au" />
      <p-button label="Filtrer" icon="pi pi-filter" (onClick)="load(0)" />
      <p-button label="Réinitialiser" icon="pi pi-times" [text]="true" (onClick)="reset()" />
    </div>

    @if (loading()) {
      <div class="ev-loading"><i class="pi pi-spin pi-spinner" aria-hidden="true"></i> Chargement…</div>
    } @else if (loadError()) {
      <app-load-error message="Impossible de charger le journal des événements." (retry)="load(page())" />
    } @else if (!events().length) {
      <app-empty-state icon="pi pi-bolt" message="Aucun événement pour ces critères." />
    } @else {
      <div class="ev-table" role="table" aria-label="Événements">
        <div class="ev-row ev-th" role="row">
          <span role="columnheader">Date</span>
          <span role="columnheader">Type</span>
          <span role="columnheader">Chantier</span>
          <span role="columnheader">Objet</span>
          <span role="columnheader">Utilisateur</span>
        </div>
        @for (e of events(); track e.id) {
          <div class="ev-row" role="row">
            <span role="cell" class="ev-date">{{ e.createdAt | date:'dd/MM/yyyy HH:mm' }}</span>
            <span role="cell" class="ev-type">{{ e.typeLabel }}</span>
            <span role="cell">{{ e.projectName ?? '—' }}</span>
            <span role="cell">
              @if (link(e); as l) {
                <a [routerLink]="l">{{ subjectLabel(e) }}</a>
              } @else {
                {{ subjectLabel(e) }}
              }
            </span>
            <span role="cell">{{ e.createdByName ?? 'Système' }}</span>
          </div>
        }
      </div>
      <div class="ev-pager">
        <p-button label="Précédent" icon="pi pi-angle-left" [text]="true" [disabled]="page() === 0"
                  (onClick)="load(page() - 1)" />
        <span>Page {{ page() + 1 }} / {{ pageCount() }} · {{ total() }} événement(s)</span>
        <p-button label="Suivant" icon="pi pi-angle-right" iconPos="right" [text]="true"
                  [disabled]="page() + 1 >= pageCount()" (onClick)="load(page() + 1)" />
      </div>
    }
  `,
  styles: [`
    .ev-head h2 { margin: 0 0 4px; display: flex; align-items: center; gap: 8px; }
    .ev-sub { margin: 0 0 16px; color: #64748b; }
    .ev-filters { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; margin-bottom: 16px; }
    :host ::ng-deep .ev-f { min-width: 200px; }
    .ev-loading { color: #64748b; padding: 30px; text-align: center; }
    .ev-table { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden; }
    .ev-row { display: grid; grid-template-columns: 140px 1.3fr 1fr 1.2fr 1fr; gap: 10px;
      padding: 10px 14px; border-bottom: 1px solid #f1f5f9; align-items: center; font-size: 14px; }
    .ev-row:last-child { border-bottom: none; }
    .ev-th { font-size: 12px; font-weight: 700; color: #94a3b8; text-transform: uppercase; background: #f8fafc; }
    .ev-type { font-weight: 600; color: #0f172a; }
    .ev-date { font-variant-numeric: tabular-nums; color: #475569; }
    .ev-pager { display: flex; align-items: center; justify-content: center; gap: 12px; margin-top: 12px; color: #64748b; }
    @media (max-width: 760px) {
      .ev-th { display: none; }
      .ev-row { grid-template-columns: 1fr; gap: 2px; }
      :host ::ng-deep .ev-f { min-width: 0; width: 100%; }
    }
  `]
})
export class EventsComponent implements OnInit {
  private eventsService = inject(DomainEventService);
  private projectService = inject(ProjectService);

  typeOptions = DOMAIN_EVENT_TYPES;
  projectOptions = signal<{ id: string; name: string }[]>([]);

  fType: string | null = null;
  fProject: string | null = null;
  fFrom: Date | null = null;
  fTo: Date | null = null;

  events = signal<DomainEvent[]>([]);
  total = signal(0);
  page = signal(0);
  pageSize = signal(50);
  loading = signal(true);
  loadError = signal(false);
  pageCount = computed(() => Math.max(1, Math.ceil(this.total() / this.pageSize())));

  ngOnInit(): void {
    this.projectService.options().subscribe({
      next: list => this.projectOptions.set((list ?? []).map(p => ({ id: p.id, name: p.name }))),
      error: () => this.projectOptions.set([]) // filter list only; the journal still loads
    });
    this.load(0);
  }

  load(page: number): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.eventsService.list({
      type: this.fType, projectId: this.fProject,
      from: this.isoDate(this.fFrom), to: this.isoDate(this.fTo), page,
    }).subscribe({
      next: p => {
        this.events.set(p.items); this.total.set(p.total); this.page.set(p.page); this.pageSize.set(p.size);
        this.loading.set(false);
      },
      error: () => { this.events.set([]); this.loadError.set(true); this.loading.set(false); }
    });
  }

  reset(): void {
    this.fType = null; this.fProject = null; this.fFrom = null; this.fTo = null;
    this.load(0);
  }

  /** Screen of the event's subject, when one exists. */
  link(e: DomainEvent): any[] | null {
    switch (e.subjectType) {
      case 'cash_movement': return ['/tresorerie/operations', e.subjectId];
      case 'purchase_order': return ['/stock/commandes', e.subjectId];
      case 'bon_livraison': return ['/stock/receptions', e.subjectId];
      case 'facture': return ['/stock/factures', e.subjectId];
      case 'document': return ['/documents', e.subjectId];
      case 'process_step': return e.payload?.['instanceId'] ? ['/processes', e.payload['instanceId']] : null;
      case 'bordereau_version': return e.projectId ? ['/projects', e.projectId] : null;
      default: return null;
    }
  }

  subjectLabel(e: DomainEvent): string {
    const labels: Record<string, string> = {
      cash_movement: 'Opération de caisse', purchase_order: 'Bon de commande', bon_livraison: 'Bon de livraison',
      facture: 'Facture', document: 'Document', process_step: 'Étape de processus',
      bordereau_version: 'Version de bordereau',
    };
    return labels[e.subjectType] ?? e.subjectType;
  }

  /** Local yyyy-mm-dd (the backend reads the day in Tunisia time). */
  private isoDate(d: Date | null): string | null {
    if (!d) return null;
    const p = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
  }
}
