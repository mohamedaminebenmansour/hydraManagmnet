import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { MessageService } from 'primeng/api';
import { PartyService } from '@app/core/services/party.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import { Party, PartyType, partyTypeVisual } from '@app/core/models/cash.model';
import { avatarGradient, initials } from '@app/shared/hydra-design';

/**
 * PartyListComponent — /tresorerie/parties
 * Répertoire des sous-traitants + fournisseurs. Search + add dialog.
 */
@Component({
  selector: 'app-party-list',
  standalone: true,
  imports: [FormsModule, ButtonModule, DialogModule, InputTextModule, SelectModule, RouterLink],
  styleUrl: './party-list.component.scss',
  template: `
    <section class="party-list">
      <header class="pl-head">
        <div>
          <h1><i class="pi pi-users"></i> Sous-traitants</h1>
          <p>Répertoire des tiers + historique des paiements par personne.</p>
        </div>
        @if (canManageParty) {
          <button pButton label="Nouveau sous-traitant" icon="pi pi-user-plus"
                  severity="success" (click)="openAdd()"></button>
        }
      </header>

      <!-- Search + type filter -->
      <div class="pl-filters">
        <div class="search">
          <i class="pi pi-search"></i>
          <input pInputText placeholder="Rechercher par nom…" [(ngModel)]="search"
                 (ngModelChange)="refresh.set(refresh() + 1)" />
        </div>
        <p-select [options]="typeOptions" [(ngModel)]="typeFilter"
                  placeholder="Tous les types" [showClear]="true"
                  optionLabel="label" optionValue="value"
                  (onChange)="refresh.set(refresh() + 1)" styleClass="type-sel" />
      </div>

      @if (loading()) {
        <div class="pl-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else if (filtered().length === 0) {
        <div class="pl-empty">
          <i class="pi pi-users"></i>
          <h3>Aucun sous-traitant</h3>
          <p>Ajoutez votre premier tiers pour suivre les paiements.</p>
          @if (canManageParty) {
            <button pButton label="Ajouter" icon="pi pi-plus" severity="success" (click)="openAdd()"></button>
          }
        </div>
      } @else {
        <div class="pl-grid">
          @for (p of filtered(); track p.id) {
            <a [routerLink]="['/tresorerie/parties', p.id]" class="party-card"
               [style.--p-color]="pv(p.type).color" [style.--p-soft]="pv(p.type).soft">
              <span class="pc-avatar" [style.background]="grad(p)">{{ init(p) }}</span>
              <div class="pc-body">
                <div class="pc-name">{{ p.name }}</div>
                <div class="pc-meta">
                  <span class="pc-type-chip">
                    <i class="pi" [class]="pv(p.type).icon"></i> {{ pv(p.type).label }}
                  </span>
                  @if (p.phone) { <span class="pc-phone">{{ p.phone }}</span> }
                </div>
              </div>
              <i class="pi pi-angle-right pc-arrow"></i>
            </a>
          }
        </div>
      }

      <!-- Add dialog -->
      <p-dialog header="Nouveau sous-traitant" [(visible)]="addVisible" [modal]="true"
                [style]="{ width: '420px' }">
        <div class="add-form">
          <div class="field">
            <label>Nom <span class="req">*</span></label>
            <input pInputText [(ngModel)]="addName" placeholder="Ex: Pipetech SARL" />
          </div>
          <div class="field">
            <label>Type</label>
            <p-select [options]="typeOptions" [(ngModel)]="addType"
                      optionLabel="label" optionValue="value" styleClass="w-100" />
          </div>
          <div class="field">
            <label>Téléphone</label>
            <input pInputText [(ngModel)]="addPhone" placeholder="+216 …" />
          </div>
        </div>
        <ng-template pTemplate="footer">
          <p-button label="Annuler" [text]="true" (onClick)="addVisible = false" />
          <p-button label="Créer" icon="pi pi-check" severity="success"
                    [disabled]="!addName.trim()" [loading]="saving()"
                    (onClick)="saveAdd()" />
        </ng-template>
      </p-dialog>
    </section>
  `,
})
export class PartyListComponent implements OnInit {
  private partyService = inject(PartyService);
  private msgs = inject(MessageService);
  private auth = inject(AuthService);

  // Route only requires CASH_READ — party creation needs PARTY_MANAGE
  canManageParty = this.auth.can(PERM.PARTY_MANAGE);

  parties = this.partyService.parties;
  loading = signal(true);
  saving = signal(false);

  search = '';
  typeFilter: PartyType | null = null;
  refresh = signal(0);

  addVisible = false;
  addName = '';
  addType: PartyType = 'SUB';
  addPhone = '';

  pv = partyTypeVisual;
  grad = (p: Party) => avatarGradient(p.id);
  init = (p: Party) => initials(p.name);

  readonly typeOptions: { label: string; value: PartyType }[] = [
    { label: 'Sous-traitant', value: 'SUB' },
    { label: 'Fournisseur', value: 'SUPPLIER' },
    { label: 'Autre', value: 'OTHER' },
  ];

  filtered = computed(() => {
    this.refresh(); // register dependency for the plain string filters
    const q = this.search.trim().toLowerCase();
    return this.parties().filter(p => {
      if (this.typeFilter && p.type !== this.typeFilter) return false;
      if (q && !p.name.toLowerCase().includes(q)) return false;
      return true;
    });
  });

  ngOnInit(): void {
    this.partyService.loadParties().subscribe(() => this.loading.set(false));
  }

  openAdd(): void {
    this.addName = '';
    this.addType = 'SUB';
    this.addPhone = '';
    this.addVisible = true;
  }

  saveAdd(): void {
    if (!this.addName.trim()) return;
    this.saving.set(true);
    this.partyService.createParty({
      name: this.addName.trim(),
      type: this.addType,
      phone: this.addPhone.trim() || undefined,
    }).subscribe(p => {
      this.saving.set(false);
      if (p) {
        this.msgs.add({ severity: 'success', summary: 'Sous-traitant créé', detail: p.name });
        this.addVisible = false;
      }
    });
  }
}
