import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { DatePickerModule } from 'primeng/datepicker';
import { SelectModule } from 'primeng/select';
import { DialogModule } from 'primeng/dialog';
import { MessageService } from 'primeng/api';
import { CashService } from '@app/core/services/cash.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import {
  CashMovement, formatAmount, formatDate,
  movementTypeVisual, movementStatusVisual, movementPending, potTypeVisual,
} from '@app/core/models/cash.model';

/**
 * FinanceHubComponent — /tresorerie/finance
 *
 * The FINANCE role's home (also visible to OWNER). One screen with:
 *  - Cards: each pot name + computed balance (existing APIs)
 *  - Block: paiements en attente (DRAFT movements with a linked process —
 *    i.e. submitted for approval but not yet posted)
 *  - Button: « Exporter la période » → opens a dialog with date range +
 *    optional pot filter → calls the export API → downloads a CSV.
 *
 * Out of scope (STANDING RULE 9): no "écriture", no "journal général",
 * no accounting jargon — just caisses, pending, and monthly export.
 */
@Component({
  selector: 'app-finance-hub',
  standalone: true,
  imports: [
    CommonModule, FormsModule, RouterLink,
    ButtonModule, DatePickerModule, SelectModule, DialogModule,
  ],
  styleUrl: './finance-hub.component.scss',
  template: `
    <section class="finance-hub">
      <nav class="fh-bc">
        <a routerLink="/tresorerie"><i class="pi pi-wallet"></i> Trésorerie</a>
        <i class="pi pi-angle-right"></i>
        <span>Espace Finance</span>
      </nav>

      <!-- Hero -->
      <header class="fh-hero">
        <div class="hero-icon"><i class="pi pi-briefcase"></i></div>
        <div class="hero-text">
          <h1>Espace Finance</h1>
          <p>Caisses, paiements en attente, export mensuel pour le cabinet.</p>
        </div>
        <div class="hero-cta">
          <button pButton label="Exporter la période" icon="pi pi-download"
                  severity="success" (click)="openExport()"></button>
        </div>
      </header>

      <!-- Caisses cards -->
      <section class="fh-pots">
        <header class="fhp-head">
          <h2><i class="pi pi-building"></i> Caisses</h2>
          <a routerLink="/tresorerie/caisses" class="fhp-link">
            Voir tout <i class="pi pi-angle-right"></i>
          </a>
        </header>
        @if (pots().length === 0) {
          <div class="fhp-empty">
            <i class="pi pi-inbox"></i>
            <p>Aucune caisse — créez d'abord la caisse centrale.</p>
          </div>
        } @else {
          <div class="fhp-grid">
            @for (p of pots(); track p.id) {
              <a [routerLink]="['/tresorerie/caisses', p.id]" class="pot-card"
                 [style.--pot-color]="pv(p.type).color"
                 [style.--pot-soft]="pv(p.type).soft">
                <div class="pc-head">
                  <i class="pi" [class]="pv(p.type).icon"></i>
                  <span class="pc-type">{{ pv(p.type).label }}</span>
                </div>
                <div class="pc-name">{{ p.name }}</div>
                <div class="pc-balance" [class.neg]="(balances()[p.id] || 0) < 0">
                  {{ fmt(balances()[p.id] || 0) }}
                </div>
                <div class="pc-currency">{{ p.currency || 'TND' }}</div>
              </a>
            }
          </div>
        }
      </section>

      <!-- Pending payments -->
      <section class="fh-pending">
        <header class="fhpen-head">
          <h2><i class="pi pi-clock"></i> Paiements en attente</h2>
          <span class="fhpen-count">{{ pending().length }}</span>
        </header>
        @if (pending().length === 0) {
          <div class="fhpen-empty">
            <i class="pi pi-check-circle"></i>
            <p>Aucun paiement en attente de validation.</p>
          </div>
        } @else {
          <div class="fhpen-list">
            @for (m of pending(); track m.id) {
              <a [routerLink]="['/tresorerie/operations', m.id]" class="pen-row"
                 [style.--mv-color]="tv(m.movementType).color">
                <div class="pen-icon"><i class="pi" [class]="tv(m.movementType).icon"></i></div>
                <div class="pen-body">
                  <div class="pen-label">{{ m.label || 'Paiement sans libellé' }}</div>
                  <div class="pen-meta">
                    <span>{{ formatDate(m.movementDate) }}</span>
                    @if (m.voucherNo) { <span>· Bon {{ m.voucherNo }}</span> }
                  </div>
                </div>
                <div class="pen-right">
                  <div class="pen-amount">{{ fmt(m.amount) }}</div>
                  <span class="pen-status" [style.--st-color]="sv(m.status, true).color"
                        [style.--st-soft]="sv(m.status, true).soft">
                    <i class="pi" [class]="sv(m.status, true).icon"></i>
                    {{ sv(m.status, true).label }}
                  </span>
                </div>
              </a>
            }
          </div>
        }
        <div class="fhpen-link-row">
          <a routerLink="/mes-taches" class="fhpen-tasks-link">
            <i class="pi pi-check-square"></i> Voir Mes tâches pour les validations
          </a>
        </div>
      </section>

      <!-- Export dialog -->
      <p-dialog header="Exporter la période" [(visible)]="exportOpen"
                [modal]="true" [style]="{ width: '480px' }" [dismissableMask]="true">
        <div class="exp-form">
          <div class="exp-row">
            <div class="exp-field">
              <label>Du <small>(facultatif)</small></label>
              <p-datepicker [(ngModel)]="expFrom" dateFormat="dd/mm/yy"
                            [showIcon]="true" placeholder="Début de période" appendTo="body" />
            </div>
            <div class="exp-field">
              <label>Au <small>(facultatif)</small></label>
              <p-datepicker [(ngModel)]="expTo" dateFormat="dd/mm/yy"
                            [showIcon]="true" placeholder="Fin de période" appendTo="body" />
            </div>
          </div>
          <div class="exp-field">
            <label>Caisse <small>(facultatif — toutes si vide)</small></label>
            <p-select [options]="potOptions()" optionValue="id" optionLabel="name"
                      [(ngModel)]="expPotId" placeholder="Toutes les caisses"
                      filter styleClass="w-100" [showClear]="true" />
          </div>
          <div class="exp-field">
            <label>Statut</label>
            <p-select [options]="statusOptions" optionValue="value" optionLabel="label"
                      [(ngModel)]="expStatus" placeholder="Tous les statuts"
                      styleClass="w-100" [showClear]="true" />
          </div>
          <small class="exp-hint">
            Le CSV s'ouvre dans Excel avec les colonnes : Date, Bon, Tiers, Nature,
            Désignation, Type, Mode, Réf, Facture, Montant, Devise, Statut, Projet.
          </small>
        </div>
        <ng-template pTemplate="footer">
          <button pButton label="Annuler" severity="secondary" [text]="true"
                  (click)="exportOpen = false"></button>
          <button pButton label="Exporter CSV" icon="pi pi-download" severity="success"
                  [loading]="exporting()" (click)="runExport()"></button>
        </ng-template>
      </p-dialog>
    </section>
  `,
})
export class FinanceHubComponent implements OnInit {
  private cashService = inject(CashService);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  // Visible to FINANCE + OWNER (route guard enforces CASH_READ).
  canDraft = this.auth.can(PERM.CASH_DRAFT);

  pots = this.cashService.pots;
  balances = this.cashService.balances;

  // Pending payments = DRAFT movements with a linked process_instance_id
  pending = signal<CashMovement[]>([]);
  pendingLoading = signal(false);

  // Export dialog state (PrimeNG <p-dialog [(visible)]> needs a plain property,
  // not a signal — it does two-way binding via assignment)
  exportOpen = false;
  exporting = signal(false);
  expFrom: Date | null = null;
  expTo: Date | null = null;
  expPotId: string | null = null;
  expStatus: string | null = null;

  // Template helpers
  pv = potTypeVisual;
  tv = movementTypeVisual;
  sv = movementStatusVisual;
  pd = movementPending;
  fmt = (a: number) => formatAmount(a);
  formatDate = formatDate;

  readonly statusOptions = [
    { value: 'DRAFT',  label: 'Brouillon' },
    { value: 'POSTED', label: 'Comptabilisé' },
    { value: 'VOID',   label: 'Annulé' },
  ];

  potOptions = computed(() => this.pots().map(p => ({
    id: p.id, name: p.name,
  })));

  ngOnInit(): void {
    this.cashService.loadPots().subscribe(pots => {
      // Async-load balances for every pot (so the cards show real numbers)
      for (const p of pots) {
        this.cashService.getBalance(p.id).subscribe();
      }
    });
    this.loadPending();
  }

  /** Load DRAFT movements that have a linked process (= submitted for approval). */
  loadPending(): void {
    this.pendingLoading.set(true);
    this.cashService.listMovements({ status: 'DRAFT' }).subscribe(list => {
      // PENDING = DRAFT + process_instance_id set (running approval workflow).
      // Other DRAFTs (saved but not submitted) are NOT pending — they're just
      // unfinished drafts the user can still edit.
      this.pending.set(list.filter(m => !!m.processInstanceId));
      this.pendingLoading.set(false);
    });
  }

  openExport(): void {
    this.exportOpen = true;
  }

  /** Trigger the CSV download via the export API + browser Blob. */
  runExport(): void {
    this.exporting.set(true);
    this.cashService.exportMovementsCsv({
      from:   this.expFrom ? this.toYmd(this.expFrom) : undefined,
      to:     this.expTo   ? this.toYmd(this.expTo)   : undefined,
      potId:  this.expPotId ?? undefined,
      status: this.expStatus ?? undefined,
    }).subscribe(csv => {
      this.exporting.set(false);
      if (csv != null) {
        const filename = this.exportFilename();
        CashService.downloadCsv(csv, filename);
        this.msgs.add({
          severity: 'success', summary: 'Export prêt',
          detail: `${filename} téléchargé. Ouvrez-le dans Excel ou LibreOffice.`,
        });
        this.exportOpen = false;
      }
    });
  }

  /** Build a filename like 'hydra-caisse-2026-10.csv' from the date range. */
  private exportFilename(): string {
    const today = new Date();
    const y = today.getFullYear();
    const m = String(today.getMonth() + 1).padStart(2, '0');
    return `hydra-caisse-${y}-${m}.csv`;
  }

  private toYmd(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }
}
