import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { HttpClient, HttpParams } from '@angular/common/http';
import { DatePipe } from '@angular/common';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { DatePickerModule } from 'primeng/datepicker';
import { SelectModule } from 'primeng/select';
import { MessageService } from 'primeng/api';
import { AuthService } from '../../core/auth.service';
import { PERM } from '../../core/permissions';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import {
  roleVisual, avatarGradient, initials,
} from '../../shared/hydra-design';

/** Matches backend AuditService.EventResponse (Jackson names). */
interface BackendEvent {
  id: number | string;
  createdAt: string | null;
  userId: string | null;
  userEmail: string | null;
  userFullName: string | null;
  action: string | null;
  entityType: string | null;
  entityId: string | null;
  details: string | null;
}

interface AuditEvent {
  id: string;
  occurredAt: string;
  actorName: string;
  actorInitials: string;
  actorGradient: string;
  userId: string | null;
  action: string;
  entityType: string;
  entityId: string | null;
  details: string;
}

interface FiltersResponse {
  actions: string[];
  entityTypes: string[];
  totalCount: number;
}

interface UserOption { id: string; fullName: string; }

// Entity type visual mapping — color + icon per category
const ENTITY_VISUAL: Record<string, { icon: string; color: string; soft: string; label: string }> = {
  app_user:               { icon: 'pi-user',          color: '#2563eb', soft: '#eff6ff', label: 'Utilisateur' },
  doc_folder:             { icon: 'pi-folder',        color: '#64748b', soft: '#f1f5f9', label: 'Dossier' },
  document:               { icon: 'pi-file',          color: '#7c3aed', soft: '#f5f3ff', label: 'Document' },
  organization:           { icon: 'pi-building',      color: '#0891b2', soft: '#ecfeff', label: 'Organisation' },
  process_instance:       { icon: 'pi-sitemap',       color: '#0d9488', soft: '#f0fdfa', label: 'Processus' },
  process_step_instance:  { icon: 'pi-list-check',    color: '#0d9488', soft: '#f0fdfa', label: 'Étape processus' },
  process_template:       { icon: 'pi-sitemap',       color: '#7c3aed', soft: '#f5f3ff', label: 'Modèle processus' },
  process_template_step:  { icon: 'pi-list',          color: '#7c3aed', soft: '#f5f3ff', label: 'Étape modèle' },
  project:                { icon: 'pi-briefcase',    color: '#0d9488', soft: '#f0fdfa', label: 'Chantier' },
};

function entityVisual(t: string | null | undefined) {
  if (!t) return { icon: 'pi-info', color: '#64748b', soft: '#f1f5f9', label: '—' };
  return ENTITY_VISUAL[t] || { icon: 'pi-info', color: '#64748b', soft: '#f1f5f9', label: t };
}

// Action visual mapping — group actions into semantic families
const ACTION_FAMILY: Record<string, { icon: string; color: string; soft: string; label: string }> = {
  LOGIN_SUCCESS:           { icon: 'pi-sign-in',     color: '#16a34a', soft: '#f0fdf4', label: 'Connexion' },
  ORG_BOOTSTRAPPED:         { icon: 'pi-cog',         color: '#0891b2', soft: '#ecfeff', label: 'Bootstrap' },
  USER_CREATED:            { icon: 'pi-user-plus',   color: '#2563eb', soft: '#eff6ff', label: 'Utilisateur créé' },
  PROJECT_CREATED:         { icon: 'pi-plus-circle', color: '#16a34a', soft: '#f0fdf4', label: 'Chantier créé' },
  PROJECT_UPDATED:         { icon: 'pi-pencil',      color: '#f59e0b', soft: '#fff7ed', label: 'Chantier modifié' },
  DOCUMENT_UPLOADED:        { icon: 'pi-upload',      color: '#7c3aed', soft: '#f5f3ff', label: 'Document uploadé' },
  DOCUMENT_REVISION_UPLOADED: { icon: 'pi-history',  color: '#7c3aed', soft: '#f5f3ff', label: 'Révision uploadée' },
  FOLDER_ACCESS_GRANTED:   { icon: 'pi-lock-open',   color: '#b45309', soft: '#fffbeb', label: 'Accès accordé' },
  PROCESS_TEMPLATE_CREATED:{ icon: 'pi-sitemap',    color: '#7c3aed', soft: '#f5f3ff', label: 'Modèle créé' },
  PROCESS_STEP_ADDED:     { icon: 'pi-plus',         color: '#7c3aed', soft: '#f5f3ff', label: 'Étape ajoutée' },
  PROCESS_STEP_DELETED:   { icon: 'pi-trash',        color: '#dc2626', soft: '#fef2f2', label: 'Étape supprimée' },
  PROCESS_STARTED:        { icon: 'pi-play',         color: '#0d9488', soft: '#f0fdfa', label: 'Processus démarré' },
  STEP_STARTED:           { icon: 'pi-play',         color: '#0d9488', soft: '#f0fdfa', label: 'Étape démarrée' },
  STEP_COMPLETED:         { icon: 'pi-check',        color: '#16a34a', soft: '#f0fdf4', label: 'Étape validée' },
};

function actionVisual(a: string | null | undefined) {
  if (!a) return { icon: 'pi-info', color: '#64748b', soft: '#f1f5f9', label: '—' };
  return ACTION_FAMILY[a] || { icon: 'pi-info', color: '#64748b', soft: '#f1f5f9', label: a };
}

@Component({
  selector: 'app-audit',
  standalone: true,
  imports: [
    FormsModule,
    ButtonModule,
    InputTextModule,
    DatePickerModule,
    SelectModule,
    DatePipe,
    RouterLink,
    EmptyStateComponent,
  ],
  template: `
    <section class="audit">
      <!-- Header -->
      <header class="a-head">
        <div>
          <h1><i class="pi pi-history"></i> Journal d'activité</h1>
          <p class="a-sub">Toutes les actions effectuées dans l'entreprise — filter par utilisateur, type, ou date.</p>
        </div>
        @if (totalCount() > 0) {
          <span class="a-total"><i class="pi pi-database"></i> {{ totalCount() }} événements au total</span>
        }
      </header>

      <!-- Quick category chips (one-click filter by entity type) -->
      <div class="a-chips">
        <button type="button" class="chip" [class.active]="entityType === null"
                (click)="setEntityType(null)">
          <i class="pi pi-th-large"></i> Tout
        </button>
        @for (et of entityTypes(); track et) {
          <button type="button" class="chip" [class.active]="entityType === et"
                  [style.--chip-color]="evV(et).color"
                  [style.--chip-soft]="evV(et).soft"
                  (click)="setEntityType(et === entityType ? null : et)">
            <i class="pi" [class]="evV(et).icon"></i> {{ evV(et).label }}
          </button>
        }
      </div>

      <!-- Filters row -->
      <div class="a-filters">
        @if (canSeeUsers()) {
          <div class="filter-field">
            <label><i class="pi pi-user"></i> Utilisateur</label>
            <p-select [options]="userOptions()" [(ngModel)]="userId"
                      placeholder="Tous les utilisateurs" [showClear]="true"
                      optionLabel="label" optionValue="value"
                      (onChange)="apply()" />
          </div>
        }
        <div class="filter-field">
          <label><i class="pi pi-bolt"></i> Action</label>
          <p-select [options]="actionOptions()" [(ngModel)]="action"
                    placeholder="Toutes les actions" [showClear]="true"
                    (onChange)="apply()" />
        </div>
        <div class="filter-field">
          <label><i class="pi pi-calendar"></i> Du</label>
          <p-datepicker [(ngModel)]="fromDate" [showTime]="false"
                        dateFormat="dd/mm/yy" placeholder="Date de début"
                        (onSelect)="apply()" [showButtonBar]="true" />
        </div>
        <div class="filter-field">
          <label><i class="pi pi-calendar"></i> Au</label>
          <p-datepicker [(ngModel)]="toDate" [showTime]="false"
                        dateFormat="dd/mm/yy" placeholder="Date de fin"
                        (onSelect)="apply()" [showButtonBar]="true" />
        </div>
        <div class="filter-actions">
          <button pButton label="Aujourd'hui" icon="pi pi-bolt" severity="info" [outlined]="true"
                  (click)="setToday()"></button>
          <button pButton label="7 jours" icon="pi pi-calendar" severity="info" [outlined]="true"
                  (click)="setLast7()"></button>
          <button pButton label="Réinitialiser" icon="pi pi-times" severity="secondary" [text]="true"
                  (click)="reset()"></button>
        </div>
      </div>

      <!-- Active filters summary (chips with X) -->
      @if (hasActiveFilters()) {
        <div class="a-active">
          <span class="a-active-label">Filtres actifs :</span>
          @if (userId) { <span class="a-active-chip"><i class="pi pi-user"></i> {{ userName(userId) }} <button (click)="clearUser()"><i class="pi pi-times"></i></button></span> }
          @if (action) { <span class="a-active-chip"><i class="pi pi-bolt"></i> {{ action }} <button (click)="clearAction()"><i class="pi pi-times"></i></button></span> }
          @if (entityType) { <span class="a-active-chip" [style.--chip-color]="evV(entityType).color"><i class="pi" [class]="evV(entityType).icon"></i> {{ evV(entityType).label }} <button (click)="clearEntityType()"><i class="pi pi-times"></i></button></span> }
          @if (fromDate) { <span class="a-active-chip"><i class="pi pi-calendar"></i> Du {{ fromDate | date:'dd/MM/yyyy' }} <button (click)="clearFrom()"><i class="pi pi-times"></i></button></span> }
          @if (toDate) { <span class="a-active-chip"><i class="pi pi-calendar"></i> Au {{ toDate | date:'dd/MM/yyyy' }} <button (click)="clearTo()"><i class="pi pi-times"></i></button></span> }
        </div>
      }

      <!-- Events timeline -->
      @if (loading()) {
        <div class="a-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else if (events.length === 0) {
        <div class="a-empty">
          <i class="pi pi-history"></i>
          <h3>Aucun événement</h3>
          <p>Aucune activité ne correspond à ces filtres. Modifiez les filtres ou réinitialisez.</p>
          @if (hasActiveFilters()) {
            <button pButton label="Réinitialiser les filtres" icon="pi pi-times" severity="info" [text]="true"
                    (click)="reset()"></button>
          }
        </div>
      } @else {
        <div class="a-events">
          @for (e of events; track e.id) {
            <article class="event-card" [style.--ev-color]="actV(e.action).color">
              <div class="ev-left">
                <span class="ev-avatar" [style.background]="e.actorGradient">{{ e.actorInitials }}</span>
                <span class="ev-action-icon" [style.background]="actV(e.action).soft"
                      [style.color]="actV(e.action).color">
                  <i class="pi" [class]="actV(e.action).icon"></i>
                </span>
              </div>
              <div class="ev-body">
                <header class="ev-head">
                  <div class="ev-who">
                    <span class="ev-name">{{ e.actorName }}</span>
                    <span class="ev-action-text" [style.color]="actV(e.action).color">
                                      {{ actV(e.action).label }}
                    </span>
                  </div>
                  <span class="ev-time" [title]="e.occurredAt">{{ e.occurredAt | date:'dd/MM/yyyy HH:mm' }}</span>
                </header>
                <div class="ev-meta">
                  <span class="ev-entity" [style.background]="evV(e.entityType).soft"
                        [style.color]="evV(e.entityType).color">
                    <i class="pi" [class]="evV(e.entityType).icon"></i> {{ evV(e.entityType).label }}
                  </span>
                  @if (e.details) {
                    <span class="ev-details">{{ e.details }}</span>
                  }
                </div>
              </div>
            </article>
          }
        </div>

        <!-- Load more -->
        @if (events.length >= 50) {
          <div class="a-more">
            <button pButton label="Charger plus" icon="pi pi-angle-down" severity="info" [outlined]="true"
                    [loading]="loading()" (click)="loadMore()"></button>
          </div>
        }
      }
    </section>
  `,
  styleUrl: './audit.component.scss',
})
export class AuditComponent implements OnInit {
  private http = inject(HttpClient);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  // State (signals for reactivity)
  events: AuditEvent[] = [];
  loading = signal(false);
  totalCount = signal(0);
  currentPage = 0;

  // Filters
  userId: string | null = null;
  fromDate: Date | null = null;
  toDate: Date | null = null;
  action: string | null = null;
  entityType: string | null = null;

  // Filter metadata (from /api/audit/filters) — signals so template can iterate
  actions = signal<string[]>([]);
  entityTypes = signal<string[]>([]);

  // Users list (for the user filter)
  private users: UserOption[] = [];

  // Permission to see the user filter
  canSeeUsers = computed(() =>
    this.auth.can(PERM.USER_CREATE) ||
    this.auth.can(PERM.USER_UPDATE) ||
    this.auth.can(PERM.ROLE_ASSIGN));

  userOptions = computed(() =>
    this.users.map(u => ({ label: u.fullName, value: u.id })));

  actionOptions = computed(() =>
    this.actions().map(a => ({ label: a, value: a })));

  // Helpers
  evV = entityVisual;
  actV = actionVisual;

  hasActiveFilters(): boolean {
    return !!(this.userId || this.fromDate || this.toDate || this.action || this.entityType);
  }

  userName(id: string): string {
    return this.users.find(u => u.id === id)?.fullName ?? id;
  }

  ngOnInit(): void {
    // Load filter metadata first
    this.http.get<FiltersResponse>('/api/audit/filters').subscribe({
      next: r => {
        this.actions.set(r?.actions ?? []);
        this.entityTypes.set(r?.entityTypes ?? []);
        this.totalCount.set(r?.totalCount ?? 0);
      },
      error: () => { /* keep empty */ },
    });

    if (this.canSeeUsers()) {
      this.http.get<any[]>('/api/users').subscribe({
        next: list => {
          this.users = (list ?? []).map(d => ({
            id: d.id,
            fullName: d.fullName ?? d.email ?? d.id,
          }));
        },
        error: () => { this.users = []; },
      });
    }

    this.apply();
  }

  apply(): void {
    this.currentPage = 0;
    this.events = [];
    this.fetchEvents(0);
  }

  loadMore(): void {
    this.fetchEvents(this.currentPage + 1);
  }

  private fetchEvents(page: number): void {
    let params = new HttpParams().set('page', String(page)).set('size', '50');
    if (this.userId) params = params.set('userId', this.userId);
    if (this.fromDate) params = params.set('from', this.toYmd(this.fromDate));
    if (this.toDate) params = params.set('to', this.toYmd(this.toDate));
    if (this.action) params = params.set('action', this.action);
    if (this.entityType) params = params.set('entityType', this.entityType);

    this.loading.set(true);
    this.http.get<BackendEvent[]>('/api/audit/events', { params }).subscribe({
      next: list => {
        const mapped = (list ?? []).map(e => this.mapEvent(e));
        if (page === 0) this.events = mapped;
        else this.events = [...this.events, ...mapped];
        this.currentPage = page;
        this.loading.set(false);
      },
      error: (err) => {
        this.loading.set(false);
        const detail = err?.error?.message
          ?? (err?.status === 403 ? 'Permission AUDIT_VIEW requise'
              : 'Chargement du journal impossible');
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail });
      },
    });
  }

  setEntityType(t: string | null): void {
    this.entityType = t;
    this.apply();
  }

  clearEntityType(): void { this.entityType = null; this.apply(); }
  clearAction(): void { this.action = null; this.apply(); }
  clearUser(): void { this.userId = null; this.apply(); }
  clearFrom(): void { this.fromDate = null; this.apply(); }
  clearTo(): void { this.toDate = null; this.apply(); }

  setToday(): void {
    const d = new Date();
    this.fromDate = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    this.toDate = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    this.apply();
  }

  setLast7(): void {
    const d = new Date();
    this.toDate = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    this.fromDate = new Date(d.getFullYear(), d.getMonth(), d.getDate() - 6);
    this.apply();
  }

  reset(): void {
    this.userId = null;
    this.fromDate = null;
    this.toDate = null;
    this.action = null;
    this.entityType = null;
    this.apply();
  }

  private mapEvent(e: BackendEvent | any): AuditEvent {
    const name = e.userFullName ?? e.actorName ?? e.userName ?? e.userEmail ?? e.email ?? '—';
    const when = e.createdAt ?? e.occurredAt ?? e.timestamp ?? '';
    let details = e.details ?? e.description ?? '';
    if (details && typeof details === 'object') {
      try { details = JSON.stringify(details); } catch { details = String(details); }
    }
    return {
      id: String(e.id ?? when ?? Math.random()),
      occurredAt: when,
      actorName: name,
      actorInitials: initials(name),
      actorGradient: avatarGradient(e.userId ?? name),
      userId: e.userId ?? null,
      action: e.action ?? '—',
      entityType: e.entityType ?? e.entity ?? '—',
      entityId: e.entityId ?? null,
      details: details == null ? '' : String(details),
    };
  }

  private toYmd(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }
}
