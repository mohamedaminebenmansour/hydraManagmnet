import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { CommonModule, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { DatePickerModule } from 'primeng/datepicker';
import { CashService } from '@app/core/services/cash.service';
import { PartyService } from '@app/core/services/party.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import {
  CashMovement, CashPot, StatementLine,
  PaymentMode, PAYMENT_MODE_LABEL, PAYMENT_MODE_ICON, NATURE_LABEL,
  formatAmount, formatDate,
  movementTypeVisual, movementStatusVisual, movementPending, potTypeVisual,
} from '@app/core/models/cash.model';

/**
 * PotDetailComponent — /tresorerie/caisses/:potId
 *
 * Shows the caisse: name + big balance + movement history (card list) +
 * an Excel-like "État de caisse" table (TASK 4) calling the statement API
 * with optional from/to filters. Each row carries Date / Bon / Tiers /
 * Nature / Désignation / Mode / Montant / Statut — the same columns as a
 * paper caisse sheet. Status chips are French (Brouillon / En attente /
 * Comptabilisé / Annulé).
 */
@Component({
  selector: 'app-pot-detail',
  standalone: true,
  imports: [ButtonModule, RouterLink, DatePipe, CommonModule, FormsModule, DatePickerModule],
  styleUrl: './pot-detail.component.scss',
  template: `
    <section class="pot-detail">
      <nav class="pd-bc">
        <a routerLink="/tresorerie"><i class="pi pi-wallet"></i> Trésorerie</a>
        <i class="pi pi-angle-right"></i>
        <a routerLink="/tresorerie/caisses">Caisses</a>
        <i class="pi pi-angle-right"></i>
        <span>{{ pot()?.name || '…' }}</span>
      </nav>

      @if (loading()) {
        <div class="pd-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else {
        @let p = pot();
        @if (p) {
        <!-- Hero with big balance -->
        <header class="pd-hero"
                [style.--pot-color]="pv(p.type).color"
                [style.--pot-soft]="pv(p.type).soft">
          <div class="pd-icon"><i class="pi" [class]="pv(p.type).icon"></i></div>
          <div class="pd-text">
            <div class="pd-name">{{ p.name }}</div>
            <div class="pd-meta">
              <span class="pd-chip">{{ pv(p.type).label }}</span>
              @if (p.currency) { <span class="pd-cur">{{ p.currency }}</span> }
            </div>
          </div>
          <div class="pd-balance-wrap">
            <div class="pd-balance-label">Solde</div>
            <div class="pd-balance" [class.neg]="balance() < 0">{{ fmt(balance()) }}</div>
          </div>
        </header>

        <!-- CTAs -->
        @if (canDraft) {
          <div class="pd-actions">
            <button pButton label="Encaisser" icon="pi pi-arrow-circle-down" severity="success"
                    [routerLink]="['/tresorerie/operations/new']"
                    [queryParams]="{ toPotId: p.id, type: 'IN' }"></button>
            <button pButton label="Payer" icon="pi pi-arrow-circle-up" severity="danger"
                    [outlined]="true"
                    [routerLink]="['/tresorerie/operations/new']"
                    [queryParams]="{ fromPotId: p.id, type: 'OUT' }"></button>
            <button pButton label="Transférer" icon="pi pi-arrows-h" severity="info"
                    [outlined]="true"
                    [routerLink]="['/tresorerie/operations/new']"
                    [queryParams]="{ fromPotId: p.id, type: 'TRANSFER' }"></button>
          </div>
        }

        <!-- ÉTAT DE CAISSE (TASK 4) — Excel-like statement table -->
        <section class="pd-statement">
          <header class="pds-head">
            <h2><i class="pi pi-table"></i> État de caisse</h2>
            <div class="pds-filters">
              <p-datepicker [(ngModel)]="stmtFrom" dateFormat="dd/mm/yy"
                            [showIcon]="true" placeholder="Du" appendTo="body"
                            (onSelect)="reloadStatement()" (onClear)="reloadStatement()" />
              <p-datepicker [(ngModel)]="stmtTo" dateFormat="dd/mm/yy"
                            [showIcon]="true" placeholder="Au" appendTo="body"
                            (onSelect)="reloadStatement()" (onClear)="reloadStatement()" />
              <button pButton label="Actualiser" icon="pi pi-refresh" severity="secondary"
                      [text]="true" (click)="reloadStatement()"></button>
            </div>
          </header>

          @if (stmtLoading()) {
            <div class="pds-loading"><i class="pi pi-spin pi-spinner"></i> Chargement de l'état…</div>
          } @else if (statement().length === 0) {
            <div class="pds-empty">
              <i class="pi pi-inbox"></i>
              <p>Aucune opération sur la période sélectionnée.</p>
            </div>
          } @else {
            <div class="pds-table-wrap">
              <table class="pds-table">
                <thead>
                  <tr>
                    <th>Date</th>
                    <th>Bon</th>
                    <th>Tiers</th>
                    <th>Nature</th>
                    <th>Désignation</th>
                    <th>Mode</th>
                    <th class="num">Montant</th>
                    <th>Statut</th>
                  </tr>
                </thead>
                <tbody>
                  @for (s of statement(); track s.id) {
                    <tr [routerLink]="['/tresorerie/operations', s.id]"
                        [class.row-posted]="s.status === 'POSTED'"
                        [class.row-draft]="s.status === 'DRAFT'">
                      <td class="c-date">{{ formatDate(s.movementDate) }}</td>
                      <td class="c-bon">{{ s.voucherNo || '—' }}</td>
                      <td class="c-party">{{ s.partyName || '—' }}</td>
                      <td class="c-nat">
                        @if (s.nature) {
                          <span class="nat-chip">{{ natureLabel(s.nature) }}</span>
                        } @else { — }
                      </td>
                      <td class="c-label">{{ s.label || '—' }}</td>
                      <td class="c-mode">
                        @if (s.paymentMode) {
                          <span class="mode-chip" [title]="modeLabel(s.paymentMode)">
                            <i class="pi" [class]="modeIcon(s.paymentMode)"></i>
                            {{ modeLabel(s.paymentMode) }}
                          </span>
                        } @else { — }
                      </td>
                      <td class="c-amt num"
                          [class.in]="s.movementType === 'IN'"
                          [class.out]="s.movementType === 'OUT'">
                        {{ mvSign(s) }}{{ fmt(s.amount) }}
                      </td>
                      <td class="c-status">
                        <span class="mv-status" [style.--st-color]="sv(s.status, false).color"
                              [style.--st-soft]="sv(s.status, false).soft">
                          <i class="pi" [class]="sv(s.status, false).icon"></i>
                          {{ sv(s.status, false).label }}
                        </span>
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </section>

        <!-- Movement history (card list — quick scan) -->
        <section class="pd-movements">
          <header class="pdm-head">
            <h2><i class="pi pi-list"></i> Historique des opérations</h2>
            <span class="pdm-count">{{ movements().length }} opération(s)</span>
          </header>

          @if (movementsLoading()) {
            <div class="pdm-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
          } @else if (movements().length === 0) {
            <div class="pdm-empty">
              <i class="pi pi-inbox"></i>
              <p>Aucune opération sur cette caisse.</p>
              <small>Encaissez ou payez pour voir l'historique ici.</small>
            </div>
          } @else {
            <div class="pdm-list">
              @for (m of movements(); track m.id) {
                <a [routerLink]="['/tresorerie/operations', m.id]" class="mv-row"
                   [style.--mv-color]="tv(m.movementType).color">
                  <div class="mv-icon">
                    <i class="pi" [class]="tv(m.movementType).icon"></i>
                  </div>
                  <div class="mv-body">
                    <div class="mv-label">{{ m.label || tv(m.movementType).label }}</div>
                    <div class="mv-meta">
                      <span class="mv-date">{{ formatDate(m.movementDate) }}</span>
                      @if (partyName(m.partyId)) {
                        <span class="mv-party">
                          <i class="pi pi-user"></i> {{ partyName(m.partyId) }}
                        </span>
                      }
                      @if (potName(m.fromPotId) && m.fromPotId !== p.id) {
                        <span class="mv-pot">← {{ potName(m.fromPotId) }}</span>
                      }
                      @if (potName(m.toPotId) && m.toPotId !== p.id) {
                        <span class="mv-pot">→ {{ potName(m.toPotId) }}</span>
                      }
                    </div>
                  </div>
                  <div class="mv-right">
                    <div class="mv-amount" [class.in]="m.movementType === 'IN'" [class.out]="m.movementType === 'OUT'">
                      {{ mvSign(m) }}{{ fmt(m.amount) }}
                    </div>
                    <span class="mv-status" [style.--st-color]="sv(m.status, pd(m)).color" [style.--st-soft]="sv(m.status, pd(m)).soft">
                      <i class="pi" [class]="sv(m.status, pd(m)).icon"></i> {{ sv(m.status, pd(m)).label }}
                    </span>
                  </div>
                </a>
              }
            </div>
          }
        </section>
      }
      }
    </section>
  `,
})
export class PotDetailComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private cashService = inject(CashService);
  private partyService = inject(PartyService);
  private auth = inject(AuthService);

  // Route only requires CASH_READ — creating operations needs CASH_DRAFT
  canDraft = this.auth.can(PERM.CASH_DRAFT);

  pot = signal<CashPot | null>(null);
  loading = signal(true);
  balance = signal(0);

  movements = signal<CashMovement[]>([]);
  movementsLoading = signal(true);

  // TASK 4 — État de caisse
  statement = signal<StatementLine[]>([]);
  stmtLoading = signal(false);
  stmtFrom: Date | null = null;
  stmtTo: Date | null = null;

  potId = '';

  // Template helpers
  pv = potTypeVisual;
  tv = movementTypeVisual;
  sv = movementStatusVisual;
  pd = movementPending;
  fmt = (a: number) => formatAmount(a);
  formatDate = formatDate;

  ngOnInit(): void {
    this.potId = this.route.snapshot.paramMap.get('potId') ?? '';
    if (!this.potId) {
      this.loading.set(false);
      return;
    }

    // Load pot + balance + movements + parties (for party names)
    this.cashService.loadPots().subscribe(pots => {
      const found = pots.find(p => p.id === this.potId);
      this.pot.set(found ?? null);
      this.loading.set(false);
      if (found) {
        this.cashService.getBalance(found.id).subscribe(b => {
          this.balance.set(b ?? 0);
        });
      }
    });

    this.partyService.loadParties().subscribe();
    this.loadMovements();
    this.reloadStatement();
  }

  loadMovements(): void {
    this.movementsLoading.set(true);
    this.cashService.listMovements({ potId: this.potId }).subscribe(list => {
      this.movements.set(list);
      this.movementsLoading.set(false);
    });
  }

  /** (TASK 4) Load the état de caisse from /pots/{id}/statement with date filters. */
  reloadStatement(): void {
    if (!this.potId) return;
    this.stmtLoading.set(true);
    this.cashService.getStatement(this.potId, {
      from: this.stmtFrom ? this.toYmd(this.stmtFrom) : undefined,
      to:   this.stmtTo   ? this.toYmd(this.stmtTo)   : undefined,
    }).subscribe(lines => {
      this.statement.set(lines);
      this.stmtLoading.set(false);
    });
  }

  /** Sign prefix for the statement amount column (+ for IN, − for OUT/TRANSFER). */
  mvSign(s: { movementType: string }): string {
    return s.movementType === 'IN' ? '+' : '−';
  }

  // V26 label helpers for the statement table
  modeLabel(m: string | null | undefined): string {
    if (!m) return '';
    return PAYMENT_MODE_LABEL[m as PaymentMode] ?? m;
  }
  modeIcon(m: string | null | undefined): string {
    if (!m) return 'pi-circle';
    return PAYMENT_MODE_ICON[m as PaymentMode] ?? 'pi-circle';
  }
  natureLabel(n: string | null | undefined): string {
    if (!n) return '';
    return (NATURE_LABEL as any)[n] ?? n;
  }

  partyName(id: string | null | undefined): string {
    if (!id) return '';
    return this.partyService.partyById(id)?.name ?? '';
  }

  potName(id: string | null | undefined): string {
    if (!id) return '';
    return this.cashService.potById(id)?.name ?? '';
  }

  sign(m: CashMovement): string {
    return m.movementType === 'IN' ? '+' : '−';
  }

  private toYmd(d: Date): string {
    const y = d.getFullYear();
    const m = String(d.getMonth() + 1).padStart(2, '0');
    const day = String(d.getDate()).padStart(2, '0');
    return `${y}-${m}-${day}`;
  }
}
