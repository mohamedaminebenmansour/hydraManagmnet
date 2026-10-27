import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { CashService } from '@app/core/services/cash.service';
import { CashPot, formatAmount, potTypeVisual } from '@app/core/models/cash.model';

/**
 * PotListComponent — /tresorerie/caisses
 * Shows every caisse (COMPANY highlighted + PROJECT ones) with balances.
 */
@Component({
  selector: 'app-pot-list',
  standalone: true,
  imports: [ButtonModule, RouterLink],
  styleUrl: './pot-list.component.scss',
  template: `
    <section class="pot-list">
      <header class="pl-head">
        <div>
          <h1><i class="pi pi-building"></i> Caisses</h1>
          <p>Caisse centrale + caisses chantier. Cliquez pour voir l'historique.</p>
        </div>
        <button pButton label="Nouvelle opération" icon="pi pi-plus" severity="success"
                routerLink="/tresorerie/operations/new"></button>
      </header>

      @if (loading()) {
        <div class="pl-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else if (pots().length === 0) {
        <div class="pl-empty">
          <i class="pi pi-building"></i>
          <h3>Aucune caisse</h3>
          <p>Créez la caisse centrale de l'entreprise pour commencer.</p>
          <button pButton label="Créer la caisse centrale" icon="pi pi-plus"
                  severity="success" (click)="ensureCompany()"></button>
        </div>
      } @else {
        <div class="pl-grid">
          @for (p of pots(); track p.id) {
            <a [routerLink]="['/tresorerie/caisses', p.id]" class="pot-card"
               [class.company]="p.type === 'COMPANY'"
               [style.--pot-color]="pv(p.type).color"
               [style.--pot-soft]="pv(p.type).soft">
              <div class="pc-band"></div>
              <div class="pc-body">
                <div class="pc-icon"><i class="pi" [class]="pv(p.type).icon"></i></div>
                <div class="pc-info">
                  <div class="pc-name">{{ p.name }}</div>
                  <div class="pc-type">
                    <span class="pc-type-chip">{{ pv(p.type).label }}</span>
                    @if (p.currency) { <span class="pc-cur">{{ p.currency }}</span> }
                  </div>
                  <div class="pc-balance" [class.neg]="(balances()[p.id] ?? 0) < 0">
                    {{ fmt(balances()[p.id]) }}
                  </div>
                </div>
                <i class="pi pi-angle-right pc-arrow"></i>
              </div>
            </a>
          }
        </div>
      }
    </section>
  `,
})
export class PotListComponent implements OnInit {
  private cashService = inject(CashService);

  pots = this.cashService.pots;
  balances = this.cashService.balances;
  loading = signal(true);

  pv = potTypeVisual;
  fmt = (b: number | undefined) => formatAmount(b ?? 0);

  ngOnInit(): void {
    this.cashService.loadPots().subscribe(pots => {
      this.loading.set(false);
      // Async-load balances for each pot (updates shared signal → UI refreshes)
      for (const p of pots) {
        this.cashService.getBalance(p.id).subscribe();
      }
    });
  }

  ensureCompany(): void {
    this.cashService.ensureCompanyPot().subscribe(() => {
      this.cashService.loadPots(true).subscribe(pots => {
        this.loading.set(false);
        for (const p of pots) {
          this.cashService.getBalance(p.id).subscribe();
        }
      });
    });
  }
}
