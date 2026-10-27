import { Component, OnInit, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { PartyService } from '@app/core/services/party.service';
import { CashService } from '@app/core/services/cash.service';
import {
  Party, formatAmount, formatDate,
  movementTypeVisual, movementStatusVisual, partyTypeVisual,
} from '@app/core/models/cash.model';
import { avatarGradient, initials } from '@app/shared/hydra-design';

/**
 * PartyDetailComponent — /tresorerie/parties/:id
 * Fiche tiers: avatar + type + téléphone + "Payer ce sous-traitant" CTA
 * + historique de tous les mouvements liés (GET movements?partyId=).
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
          <div class="pd-cta">
            <button pButton label="Payer ce sous-traitant" icon="pi pi-arrow-circle-up"
                    severity="danger"
                    [routerLink]="['/tresorerie/operations/new']"
                    [queryParams]="{ partyId: p.id, type: 'OUT' }"></button>
          </div>
        </header>

        <!-- Total paid -->
        <div class="pd-stats">
          <div class="stat">
            <span class="s-n">{{ fmt(totalPaid()) }}</span>
            <span class="s-l">Total payé (hors annulations)</span>
          </div>
          <div class="stat">
            <span class="s-n">{{ movements().length }}</span>
            <span class="s-l">Opération(s)</span>
          </div>
        </div>

        <!-- Movement history -->
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
                    </div>
                  </div>
                  <div class="mv-right">
                    <div class="mv-amount" [class.in]="m.movementType === 'IN'" [class.out]="m.movementType === 'OUT'">
                      {{ m.movementType === 'IN' ? '+' : '−' }}{{ fmt(m.amount) }}
                    </div>
                    <span class="mv-status" [style.--st-color]="sv(m.status).color" [style.--st-soft]="sv(m.status).soft">
                      <i class="pi" [class]="sv(m.status).icon"></i> {{ sv(m.status).label }}
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

  party = signal<Party | null>(null);
  movements = signal<any[]>([]);
  loading = signal(true);
  movementsLoading = signal(true);

  private id = '';

  // Template helpers
  pv = partyTypeVisual;
  tv = movementTypeVisual;
  sv = movementStatusVisual;
  fmt = (a: number) => formatAmount(a);
  formatDate = formatDate;
  grad = (p: Party) => avatarGradient(p.id);
  init = (p: Party) => initials(p.name);

  totalPaid = signal(0);

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

    // Load movements for this party + compute total paid (POSTED OUT only)
    this.cashService.listMovements({ partyId: this.id }).subscribe(list => {
      this.movements.set(list);
      this.movementsLoading.set(false);
      const total = list
        .filter(m => m.status === 'POSTED' && m.movementType === 'OUT')
        .reduce((sum, m) => sum + (m.amount ?? 0), 0);
      this.totalPaid.set(total);
    });

    this.cashService.loadPots().subscribe();
  }

  potName(id: string | null | undefined): string {
    if (!id) return '';
    return this.cashService.potById(id)?.name ?? '';
  }
}
