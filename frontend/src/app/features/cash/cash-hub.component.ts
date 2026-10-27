import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { CashService } from '@app/core/services/cash.service';
import { PartyService } from '@app/core/services/party.service';
import { CashPot, formatAmount, potTypeVisual } from '@app/core/models/cash.model';

/**
 * CashHubComponent — /tresorerie
 *
 * The "home" of money in Hydra. Owner/finance lands here and immediately sees:
 *   - Caisses (centrale + chantiers) with balances
 *   - Big "Nouvelle opération" CTA
 *   - Sous-traitants directory
 *   - 3-step "how it works" strip (Saisir → Valider → Suivre)
 *
 * UX: one-screen-one-purpose, icon + color carry meaning, big touch targets.
 */
@Component({
  selector: 'app-cash-hub',
  standalone: true,
  imports: [ButtonModule, RouterLink],
  styleUrl: './cash-hub.component.scss',
  template: `
    <section class="cash-hub">
      <!-- Hero -->
      <header class="hub-hero">
        <div class="hero-icon"><i class="pi pi-wallet"></i></div>
        <div class="hero-text">
          <h1>Trésorerie</h1>
          <p>Caisses, paiements, sous-traitants — l'argent de l'entreprise en un coup d'œil.</p>
        </div>
      </header>

      <!-- Quick stats -->
      <div class="hub-stats">
        <div class="stat">
          <span class="s-n">{{ pots().length }}</span>
          <span class="s-l"><i class="pi pi-building"></i> Caisses</span>
        </div>
        <div class="stat">
          <span class="s-n">{{ totalBalance() }}</span>
          <span class="s-l"><i class="pi pi-euro" style="color: #0d9488"></i> Total TND</span>
        </div>
        <div class="stat">
          <span class="s-n">{{ parties().length }}</span>
          <span class="s-l"><i class="pi pi-users"></i> Sous-traitants</span>
        </div>
      </div>

      <!-- 3 main cards -->
      <div class="hub-cards">
        <a routerLink="/tresorerie/caisses" class="hcard caisses">
          <div class="hc-band"></div>
          <div class="hc-body">
            <div class="hc-icon"><i class="pi pi-building"></i></div>
            <div class="hc-text">
              <div class="hc-title">Caisses</div>
              <div class="hc-desc">Caisse centrale + caisses chantier avec soldes</div>
            </div>
            <i class="pi pi-angle-right hc-arrow"></i>
          </div>
        </a>

        <a routerLink="/tresorerie/operations/new" class="hcard new-op">
          <div class="hc-band"></div>
          <div class="hc-body">
            <div class="hc-icon"><i class="pi pi-plus-circle"></i></div>
            <div class="hc-text">
              <div class="hc-title">Nouvelle opération</div>
              <div class="hc-desc">Paiement, encaissement ou transfert entre caisses</div>
            </div>
            <i class="pi pi-angle-right hc-arrow"></i>
          </div>
        </a>

        <a routerLink="/tresorerie/parties" class="hcard parties">
          <div class="hc-band"></div>
          <div class="hc-body">
            <div class="hc-icon"><i class="pi pi-users"></i></div>
            <div class="hc-text">
              <div class="hc-title">Sous-traitants</div>
              <div class="hc-desc">Répertoire + historique des paiements</div>
            </div>
            <i class="pi pi-angle-right hc-arrow"></i>
          </div>
        </a>
      </div>

      <!-- Caisses preview (top 3) -->
      @if (pots().length > 0) {
        <section class="hub-preview">
          <header class="pv-head">
            <h2><i class="pi pi-building"></i> Mes caisses</h2>
            <a routerLink="/tresorerie/caisses" class="pv-link">Voir tout <i class="pi pi-angle-right"></i></a>
          </header>
          <div class="pv-grid">
            @for (p of topPots(); track p.id) {
              <a [routerLink]="['/tresorerie/caisses', p.id]" class="pot-chip"
                 [style.--pot-color]="pv(p.type).color"
                 [style.--pot-soft]="pv(p.type).soft">
                <i class="pi" [class]="pv(p.type).icon"></i>
                <div class="pot-info">
                  <div class="pot-name">{{ p.name }}</div>
                  <div class="pot-balance" [class.neg]="(balances()[p.id] ?? 0) < 0">
                    {{ formatBalance(balances()[p.id]) }}
                  </div>
                </div>
              </a>
            }
          </div>
        </section>
      }

      <!-- How it works -->
      <section class="hub-how">
        <h2><i class="pi pi-info-circle"></i> Comment ça marche</h2>
        <div class="how-steps">
          <div class="how-step">
            <div class="hs-num">1</div>
            <div class="hs-body">
              <div class="hs-title">Saisir</div>
              <div class="hs-desc">Créez une opération (paiement, encaissement, transfert) — elle reste en brouillon.</div>
            </div>
          </div>
          <i class="pi pi-arrow-right how-arrow"></i>
          <div class="how-step">
            <div class="hs-num">2</div>
            <div class="hs-body">
              <div class="hs-title">Valider</div>
              <div class="hs-desc">Validez l'opération — le solde de la caisse est mis à jour immédiatement.</div>
            </div>
          </div>
          <i class="pi pi-arrow-right how-arrow"></i>
          <div class="how-step">
            <div class="hs-num">3</div>
            <div class="hs-body">
              <div class="hs-title">Suivre</div>
              <div class="hs-desc">Retrouvez l'historique sur la caisse et sur la fiche du sous-traitant.</div>
            </div>
          </div>
        </div>
      </section>
    </section>
  `,
})
export class CashHubComponent implements OnInit {
  private cashService = inject(CashService);
  private partyService = inject(PartyService);

  pots = this.cashService.pots;
  balances = this.cashService.balances;
  parties = this.partyService.parties;

  // Helper exposed to template
  pv = potTypeVisual;
  formatBalance = (b: number | null | undefined) => formatAmount(b ?? 0);

  topPots = computed(() => this.pots().slice(0, 3));

  /** Sum of all pot balances (from the shared balances signal). */
  totalBalance = computed(() => {
    const total = this.pots().reduce((sum, p) => sum + (this.balances()[p.id] ?? 0), 0);
    return this.formatBalance(total);
  });

  ngOnInit(): void {
    // Load pots + parties into caches
    this.cashService.loadPots().subscribe(pots => {
      // Ensure the company pot exists (calm no-op if already there)
      if (pots.filter(p => p.type === 'COMPANY').length === 0) {
        this.cashService.ensureCompanyPot().subscribe();
      }
      // Async-load balances for the first 3 pots (updates the balances signal → UI refreshes)
      for (const p of this.topPots()) {
        this.cashService.getBalance(p.id).subscribe();
      }
    });
    this.partyService.loadParties().subscribe();
  }
}
