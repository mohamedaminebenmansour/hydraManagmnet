import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { PartyService } from '@app/core/services/party.service';
import { CashService } from '@app/core/services/cash.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import {
  Party, CashSummary,
  PaymentMode, PAYMENT_MODE_LABEL, PAYMENT_MODE_ICON,
  formatAmount, formatDate,
  movementTypeVisual, movementStatusVisual, movementPending, partyTypeVisual,
} from '@app/core/models/cash.model';
import { avatarGradient, initials } from '@app/shared/hydra-design';

/**
 * PartyDetailComponent — /tresorerie/parties/:id
 * Fiche tiers: avatar + type + téléphone + "Payer ce sous-traitant" CTA
 * + total payé (POSTED OUT) + count + lastDate + table of recent movements
 * linked to the movement detail view.
 *
 * TASK 4 — uses the new /api/parties/{id}/cash-summary endpoint (single
 * round-trip: aggregate + 10 recent POSTED OUT). Falls back to listMovements
 * for the broader history view (showing more than 10 lines).
 */
@Component({
  selector: 'app-party-detail',
  standalone: true,
  imports: [ButtonModule, RouterLink, DatePipe],
  styleUrl: './party-detail.component.scss',
  template: `
    <section class="party-detail">
      <nav class="pd-bc">
        <a routerLink="/tresorerie"><i class="pi pi-wallet"></i> Trésorerie</a>
        <i class="pi pi-angle-right"></i>
        <a routerLink="/tresorerie/parties">Sous-traitants</a>
        <i class="pi pi-angle-right"></i>
        <span>{{ party()?.name || '…' }}</span>
      </nav>

      @if (loading()) {
        <div class="pd-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else {
        @let p = party();
        @if (p) {
        <!-- Hero -->
        <header class="pd-hero"
                [style.--p-color]="pv(p.type).color"
                [style.--p-soft]="pv(p.type).soft">
          <span class="pd-avatar" [style.background]="grad(p)">{{ init(p) }}</span>
          <div class="pd-text">
            <div class="pd-name">{{ p.name }}</div>
            <div class="pd-meta">
              <span class="pd-chip"><i class="pi" [class]="pv(p.type).icon"></i> {{ pv(p.type).label }}</span>
              @if (p.phone) { <span class="pd-phone"><i class="pi pi-phone"></i> {{ p.phone }}</span> }
            </div>
          </div>
          @if (canDraft) {
            <div class="pd-cta">
              <button pButton label="Payer ce sous-traitant" icon="pi pi-arrow-circle-up"
                      severity="danger"
                      [routerLink]="['/tresorerie/operations/new']"
                      [queryParams]="{ partyId: p.id, type: 'OUT' }"></button>
            </div>
          }
        </header>

        <!-- TASK 4 — total paid + count + lastDate (from cash-summary endpoint) -->
        @if (summary()) {
          @let s = summary()!;
          <div class="pd-stats">
            <div class="stat stat-paid">
              <span class="s-n">{{ fmt(s.totalPaid) }}</span>
              <span class="s-l">Total payé (comptabilisé)</span>
            </div>
            <div class="stat">
              <span class="s-n">{{ s.movementCount }}</span>
              <span class="s-l">Paiement(s)</span>
            </div>
            <div class="stat">
              <span class="s-n">{{ s.lastMovementDate ? formatDate(s.lastMovementDate) : '—' }}</span>
              <span class="s-l">Dernier paiement</span>
            </div>
          </div>
        }

        <!-- Movement history (full list — broader than cash-summary recent) -->
        <section class="pd-movements">
          <header class="pdm-head">
            <h2><i class="pi pi-list"></i> Historique des paiements</h2>
          </header>

          @if (movementsLoading()) {
            <div class="pdm-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
          } @else if (movements().length === 0) {
            <div class="pdm-empty">
              <i class="pi pi-inbox"></i>
              <p>Aucun paiement pour ce tiers.</p>
              <small>Utilisez « Payer ce sous-traitant » pour créer le premier.</small>
            </div>
          } @else {
            <div class="pdm-list">
              @for (m of movements(); track m.id) {
                <a [routerLink]="['/tresorerie/operations', m.id]" class="mv-row"
                   [style.--mv-color]="tv(m.movementType).color">
                  <div class="mv-icon"><i class="pi" [class]="tv(m.movementType).icon"></i></div>
                  <div class="mv-body">
                    <div class="mv-label">{{ m.label || tv(m.movementType).label }}</div>
                    <div class="mv-meta">
                      <span>{{ formatDate(m.movementDate) }}</span>
                      @if (potName(m.fromPotId)) {
                        <span><i class="pi pi-building"></i> {{ potName(m.fromPotId) }}</span>
                      }
                      @if (m.voucherNo) {
                        <span class="mv-bon"><i class="pi pi-ticket"></i> {{ m.voucherNo }}</span>
                      }
                      @if (m.paymentMode) {
                        <span class="mv-mode"><i class="pi" [class]="modeIcon(m.paymentMode)"></i> {{ modeLabel(m.paymentMode) }}</span>
                      }
                    </div>
                  </div>
                  <div class="mv-right">
                    <div class="mv-amount" [class.in]="m.movementType === 'IN'" [class.out]="m.movementType === 'OUT'">
                      {{ m.movementType === 'IN' ? '+' : '−' }}{{ fmt(m.amount) }}
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
export class PartyDetailComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private partyService = inject(PartyService);
  private cashService = inject(CashService);
  private auth = inject(AuthService);

  // Route only requires CASH_READ — creating operations needs CASH_DRAFT
  canDraft = this.auth.can(PERM.CASH_DRAFT);

  party = signal<Party | null>(null);
  summary = signal<CashSummary | null>(null);
  movements = signal<any[]>([]);
  loading = signal(true);
  movementsLoading = signal(true);

  private id = '';

  // Template helpers
  pv = partyTypeVisual;
  tv = movementTypeVisual;
  sv = movementStatusVisual;
  pd = movementPending;
  fmt = (a: number) => formatAmount(a);
  formatDate = formatDate;
  grad = (p: Party) => avatarGradient(p.id);
  init = (p: Party) => initials(p.name);

  // V26 label helpers
  modeLabel(m: string | null | undefined): string {
    if (!m) return '';
    return PAYMENT_MODE_LABEL[m as PaymentMode] ?? m;
  }
  modeIcon(m: string | null | undefined): string {
    if (!m) return 'pi-circle';
    return PAYMENT_MODE_ICON[m as PaymentMode] ?? 'pi-circle';
  }

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    if (!this.id) {
      this.loading.set(false);
      return;
    }
    this.partyService.getParty(this.id).subscribe(p => {
      this.party.set(p);
      this.loading.set(false);
    });

    // TASK 4 — load the cash-summary (total paid + count + last date +
    // 10 recent POSTED OUT). Single round-trip via the new endpoint.
    this.cashService.getCashSummary(this.id).subscribe(s => {
      this.summary.set(s);
    });

    // Also load the broader movement list (DRAFT + POSTED + VOID, all OUT
    // and IN to this party) for the full history view below the stats.
    this.cashService.listMovements({ partyId: this.id }).subscribe(list => {
      this.movements.set(list);
      this.movementsLoading.set(false);
    });

    this.cashService.loadPots().subscribe();
  }

  potName(id: string | null | undefined): string {
    if (!id) return '';
    return this.cashService.potById(id)?.name ?? '';
  }
}
