import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { DatePipe } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { TextareaModule } from 'primeng/textarea';
import { MessageService } from 'primeng/api';
import { CashService } from '@app/core/services/cash.service';
import { PartyService } from '@app/core/services/party.service';
import {
  CashMovement, formatAmount, formatDate,
  movementTypeVisual, movementStatusVisual,
} from '@app/core/models/cash.model';

/**
 * MovementDetailComponent — /tresorerie/operations/:id
 *
 * Shows all fields + status chip.
 * DRAFT  → Valider (poster) + Modifier buttons
 * POSTED → Annuler (void) with required reason dialog
 * VOID   → read-only
 */
@Component({
  selector: 'app-movement-detail',
  standalone: true,
  imports: [ButtonModule, DialogModule, InputTextModule, TextareaModule, FormsModule, DatePipe, RouterLink],
  styleUrl: './movement-detail.component.scss',
  template: `
    <section class="mv-detail">
      <nav class="md-bc">
        <a routerLink="/tresorerie"><i class="pi pi-wallet"></i> Trésorerie</a>
        <i class="pi pi-angle-right"></i>
        @if (movement(); as m) {
          <span>{{ tv(m.movementType).label }} — {{ fmt(m.amount) }}</span>
        }
      </nav>

      @if (loading()) {
        <div class="md-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else {
        @let m = movement();
        @if (m) {
        <!-- Hero -->
        <header class="md-hero"
                [style.--mv-color]="tv(m.movementType).color"
                [style.--mv-soft]="tv(m.movementType).soft">
          <div class="md-icon"><i class="pi" [class]="tv(m.movementType).icon"></i></div>
          <div class="md-text">
            <div class="md-title">{{ m.label || tv(m.movementType).label }}</div>
            <div class="md-sub">{{ tv(m.movementType).label }} · {{ formatDate(m.movementDate) }}</div>
          </div>
          <div class="md-right">
            <div class="md-amount" [class.in]="m.movementType === 'IN'" [class.out]="m.movementType === 'OUT'">
              {{ m.movementType === 'IN' ? '+' : '−' }}{{ fmt(m.amount) }}
            </div>
            <span class="md-status" [style.--st-color]="sv(m.status).color" [style.--st-soft]="sv(m.status).soft">
              <i class="pi" [class]="sv(m.status).icon"></i> {{ sv(m.status).label }}
            </span>
          </div>
        </header>

        <!-- Details grid -->
        <div class="md-grid">
          <div class="md-field">
            <div class="mdf-label">Caisse source</div>
            <div class="mdf-value">
              @if (m.fromPotId) {
                <a [routerLink]="['/tresorerie/caisses', m.fromPotId]">{{ potName(m.fromPotId) || 'Caisse' }}</a>
              } @else { — }
            </div>
          </div>
          <div class="md-field">
            <div class="mdf-label">Caisse destination</div>
            <div class="mdf-value">
              @if (m.toPotId) {
                <a [routerLink]="['/tresorerie/caisses', m.toPotId]">{{ potName(m.toPotId) || 'Caisse' }}</a>
              } @else { — }
            </div>
          </div>
          <div class="md-field">
            <div class="mdf-label">Sous-traitant</div>
            <div class="mdf-value">
              @if (m.partyId) {
                <a [routerLink]="['/tresorerie/parties', m.partyId]">{{ partyName(m.partyId) || 'Tiers' }}</a>
              } @else { — }
            </div>
          </div>
          <div class="md-field">
            <div class="mdf-label">Devise</div>
            <div class="mdf-value">{{ m.currency || 'TND' }}</div>
          </div>
          <div class="md-field md-field-wide">
            <div class="mdf-label">Note</div>
            <div class="mdf-value">{{ m.note || '—' }}</div>
          </div>
          @if (m.processInstanceId) {
            <div class="md-field md-field-wide">
              <div class="mdf-label">Processus lié</div>
              <div class="mdf-value">
                <a [routerLink]="['/processes', m.processInstanceId]">
                  <i class="pi pi-sitemap"></i> Voir le workflow
                </a>
              </div>
            </div>
          }
          @if (m.status === 'VOID') {
            <div class="md-field md-field-wide">
              <div class="mdf-label">Motif d'annulation</div>
              <div class="mdf-value void-reason">{{ voidReason || '—' }}</div>
            </div>
          }
        </div>

        <!-- Actions -->
        <div class="md-actions">
          @if (m.status === 'DRAFT') {
            <button pButton label="Modifier" icon="pi pi-pencil" severity="info" [outlined]="true"
                    [routerLink]="['/tresorerie/operations', m.id, 'edit']"></button>
            <button pButton label="Valider (poster)" icon="pi pi-check" severity="success"
                    [loading]="acting()"
                    (click)="post()"></button>
          } @else if (m.status === 'POSTED') {
            <div class="md-note info">
              <i class="pi pi-lock"></i>
              Opération validée — le solde de la caisse est à jour. Utilisez « Annuler » pour la corriger.
            </div>
            <button pButton label="Annuler (void)" icon="pi pi-ban" severity="danger" [outlined]="true"
                    (click)="showVoidDialog()"></button>
          } @else {
            <div class="md-note muted">
              <i class="pi pi-ban"></i>
              Opération annulée — ignorée dans le calcul du solde. Créez une nouvelle opération si besoin.
            </div>
          }
        </div>
        } @else {
        <div class="md-empty">
          <i class="pi pi-exclamation-circle"></i>
          <p>Opération introuvable.</p>
          <button pButton label="Retour à la trésorerie" severity="info" routerLink="/tresorerie"></button>
        </div>
        }
      }

      <!-- Void dialog -->
      <p-dialog header="Annuler l'opération" [(visible)]="voidVisible" [modal]="true"
                [style]="{ width: '440px' }">
        <div class="void-form">
          <p class="void-warn">
            <i class="pi pi-exclamation-triangle"></i>
            L'opération sera marquée <strong>Annulée</strong> et ignorée du solde.
            Cette action est définitive.
          </p>
          <label>Motif (obligatoire)</label>
          <textarea pInputTextarea [(ngModel)]="voidReason" rows="3"
                    placeholder="Ex: erreur de saisie, paiement annulé…"></textarea>
        </div>
        <ng-template pTemplate="footer">
          <p-button label="Retour" [text]="true" (onClick)="voidVisible = false" />
          <p-button label="Confirmer l'annulation" icon="pi pi-ban" severity="danger"
                    [disabled]="!voidReason.trim()" [loading]="acting()"
                    (onClick)="confirmVoid()" />
        </ng-template>
      </p-dialog>
    </section>
  `,
})
export class MovementDetailComponent implements OnInit {
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private cashService = inject(CashService);
  private partyService = inject(PartyService);
  private msgs = inject(MessageService);

  movement = signal<CashMovement | null>(null);
  voidReason = '';
  loading = signal(true);
  acting = signal(false);
  voidVisible = false;

  // Template helpers
  tv = movementTypeVisual;
  sv = movementStatusVisual;
  fmt = (a: number) => formatAmount(a);
  formatDate = formatDate;

  private id = '';

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    this.load();
  }

  load(): void {
    if (!this.id) {
      this.loading.set(false);
      return;
    }
    this.cashService.getMovement(this.id).subscribe(m => {
      this.movement.set(m);
      this.voidReason = '';
      this.loading.set(false);
    });
    this.partyService.loadParties().subscribe();
    this.cashService.loadPots().subscribe();
  }

  potName(id: string | null | undefined): string {
    if (!id) return '';
    return this.cashService.potById(id)?.name ?? '';
  }

  partyName(id: string | null | undefined): string {
    if (!id) return '';
    return this.partyService.partyById(id)?.name ?? '';
  }

  post(): void {
    this.acting.set(true);
    this.cashService.postMovement(this.id).subscribe(res => {
      this.acting.set(false);
      if (res) {
        this.msgs.add({ severity: 'success', summary: 'Opération validée',
          detail: 'Le solde de la caisse est à jour.' });
        this.cashService.invalidatePots();
        this.load();
      }
    });
  }

  showVoidDialog(): void {
    this.voidReason = '';
    this.voidVisible = true;
  }

  confirmVoid(): void {
    const reason = this.voidReason.trim();
    if (!reason) return;
    this.acting.set(true);
    this.cashService.voidMovement(this.id, reason).subscribe(m => {
      this.acting.set(false);
      if (m) {
        this.msgs.add({ severity: 'warn', summary: 'Opération annulée',
          detail: 'Elle est ignorée dans le calcul du solde.' });
        this.voidVisible = false;
        this.cashService.invalidatePots();
        this.load();
      }
    });
  }
}
