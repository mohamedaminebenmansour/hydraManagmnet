import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { DialogModule } from 'primeng/dialog';
import { TooltipModule } from 'primeng/tooltip';
import { MessageService } from 'primeng/api';

import { StockService } from '@app/core/services/stock.service';
import { ProjectService } from '@app/core/services/project.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import {
  StockLocation, StockLocationType,
  locationTypeVisual,
} from '@app/core/models/stock.model';

interface ProjectOption { id: string; name: string; projectType?: string | null; }

/**
 * LocationsAdminComponent — /stock/emplacements
 *
 * List of stock locations: one CENTRAL per org + N SITE locations
 * (one per project). Visible to anyone with STOCK_VIEW; create requires
 * STOCK_ADMIN or owner.
 *
 * Layout: hand-built HTML table + a create dialog. The dialog enforces
 * the CENTRAL/SITE choice: CENTRAL needs no project (one per org, the
 * backend rejects duplicates with 409 CENTRAL_EXISTS), SITE requires
 * a project selection.
 */
@Component({
  selector: 'app-locations-admin',
  standalone: true,
  imports: [FormsModule, RouterLink, ButtonModule, InputTextModule, SelectModule, DialogModule, TooltipModule],
  template: `
    <section class="la">
      <header class="la-head">
        <div>
          <h1><i class="pi pi-map-marker"></i> Emplacements</h1>
          <p>Dépôt central + un emplacement par chantier.</p>
        </div>
        <a class="back-link" [routerLink]="'/stock'"><i class="pi pi-arrow-left"></i> Hub stock</a>
      </header>

      @if (canAdmin()) {
        <div class="la-toolbar">
          <button pButton label="Nouvel emplacement" icon="pi pi-plus" severity="success" (click)="openAdd()"></button>
        </div>
      }

      @if (loading()) {
        <div class="la-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else if (locations().length === 0) {
        <div class="la-empty">
          <i class="pi pi-map-marker"></i>
          <h3>Aucun emplacement</h3>
          <p>Créez d'abord le dépôt central, puis un emplacement par chantier.</p>
          @if (canAdmin()) {
            <button pButton label="Créer" icon="pi pi-plus" severity="success" (click)="openAdd()"></button>
          }
        </div>
      } @else {
        <div class="la-table-wrap">
          <table class="la-table">
            <thead>
              <tr>
                <th>Type</th>
                <th>Nom</th>
                <th>Code</th>
                <th>Projet</th>
                <th>Statut</th>
              </tr>
            </thead>
            <tbody>
              @for (loc of locations(); track loc.id) {
                <tr [class.inactive]="!loc.active">
                  <td>
                    <span class="type-chip" [style.--lc-color]="ltv(loc.type).color">
                      <i class="pi" [class]="ltv(loc.type).icon"></i>
                      {{ ltv(loc.type).label }}
                    </span>
                  </td>
                  <td class="c-name">{{ loc.name }}</td>
                  <td class="c-code">{{ loc.code || '—' }}</td>
                  <td class="c-proj">
                    @if (loc.type === 'CENTRAL') {
                      <span class="proj-central">Entreprise (central)</span>
                    } @else {
                      <span>{{ loc.projectName ?? '—' }}</span>
                    }
                  </td>
                  <td>
                    <span class="status-chip" [class.active]="loc.active" [class.inactive]="!loc.active">
                      <i class="pi" [class]="loc.active ? 'pi-check-circle' : 'pi-ban'"></i>
                      {{ loc.active ? 'Actif' : 'Inactif' }}
                    </span>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }

      <!-- Add dialog -->
      <p-dialog header="Nouvel emplacement" [(visible)]="formVisible" [modal]="true" [style]="{ width: '460px' }">
        <div class="form-grid">
          <div class="field">
            <label>Type <span class="req">*</span></label>
            <div class="type-choice">
              <button type="button" class="type-pick"
                      [class.active]="formType === 'CENTRAL'"
                      [style.--tp-color]="ltv('CENTRAL').color"
                      (click)="pickType('CENTRAL')">
                <i class="pi" [class]="ltv('CENTRAL').icon"></i>
                <span>Dépôt central</span>
                <small>Un seul par entreprise</small>
              </button>
              <button type="button" class="type-pick"
                      [class.active]="formType === 'SITE'"
                      [style.--tp-color]="ltv('SITE').color"
                      (click)="pickType('SITE')">
                <i class="pi" [class]="ltv('SITE').icon"></i>
                <span>Chantier</span>
                <small>Lié à un projet</small>
              </button>
            </div>
          </div>

          @if (formType === 'SITE') {
            <div class="field">
              <label>Projet <span class="req">*</span></label>
              <p-select [options]="projectOptions()" [(ngModel)]="formProjectId"
                        placeholder="Sélectionner un chantier"
                        optionLabel="name" optionValue="id"
                        filter="true" filterBy="name" styleClass="w-100"
                        (onChange)="onProjectPicked()" />
              <small class="field-hint">L'emplacement sera visible par les membres du projet.</small>
            </div>
          }

          <div class="field">
            <label>Nom <span class="req">*</span></label>
            <input pInputText [(ngModel)]="formName" [placeholder]="formType === 'CENTRAL' ? 'Dépôt central' : 'Stock chantier'" />
          </div>

          <div class="field">
            <label>Code <small>(optionnel)</small></label>
            <input pInputText [(ngModel)]="formCode" placeholder="Ex : DEP-CENTRAL" />
            <small class="field-hint">Code court visible dans la liste des emplacements.</small>
          </div>
        </div>
        <ng-template pTemplate="footer">
          <p-button label="Annuler" [text]="true" (onClick)="formVisible.set(false)" />
          <p-button label="Créer" icon="pi pi-check" severity="success"
                    [disabled]="!canSave()" [loading]="saving()"
                    (onClick)="save()" />
        </ng-template>
      </p-dialog>
    </section>
  `,
  styles: [`
    .la { display: flex; flex-direction: column; gap: 14px; }
    .la-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; }
    .la-head h1 { margin: 0 0 4px; font-size: 22px; font-weight: 800; display: flex; align-items: center; gap: 10px; }
    .la-head h1 i { color: #0d9488; }
    .la-head p { margin: 0; color: #64748b; font-size: 14px; }
    .back-link { color: #475569; text-decoration: none; font-size: 13px; font-weight: 600;
      display: inline-flex; align-items: center; gap: 6px; }
    .back-link:hover { color: #0d9488; }

    .la-toolbar { display: flex; justify-content: flex-end; }

    .la-table-wrap { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden; }
    .la-table { width: 100%; border-collapse: collapse; font-size: 14px; }
    .la-table th {
      background: #f8fafc; color: #64748b; font-weight: 600; font-size: 12px;
      text-transform: uppercase; letter-spacing: .04em;
      text-align: left; padding: 12px 16px; border-bottom: 1px solid #e2e8f0;
    }
    .la-table td {
      padding: 12px 16px; border-bottom: 1px solid #f1f5f9; vertical-align: middle;
    }
    .la-table tr:last-child td { border-bottom: none; }
    .la-table tr:hover td { background: #f8fafc; }
    .la-table tr.inactive td { opacity: 0.6; }
    .c-name { font-weight: 600; }
    .c-code { font-family: 'Courier New', monospace; color: #475569; }
    .c-proj { color: #475569; }
    .proj-central { color: #b45309; font-weight: 600; }

    .type-chip {
      display: inline-flex; align-items: center; gap: 6px;
      padding: 4px 10px; border-radius: 999px;
      font-size: 12px; font-weight: 700;
      background: color-mix(in srgb, var(--lc-color, #0d9488) 12%, transparent);
      color: var(--lc-color, #0d9488);
    }
    .status-chip {
      display: inline-flex; align-items: center; gap: 5px;
      padding: 3px 10px; border-radius: 999px;
      font-size: 12px; font-weight: 700;
    }
    .status-chip.active  { background: #f0fdf4; color: #16a34a; }
    .status-chip.inactive { background: #f1f5f9; color: #94a3b8; }

    /* Form */
    .form-grid { display: flex; flex-direction: column; gap: 14px; }
    .field { display: flex; flex-direction: column; gap: 6px; }
    .field label { font-size: 13px; font-weight: 600; color: #475569; }
    .field .req { color: #dc2626; }
    .field small { font-weight: 400; color: #94a3b8; }
    .field-hint { font-size: 12px; color: #64748b; }
    :host ::ng-deep .w-100 { width: 100%; }

    .type-choice { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .type-pick {
      background: #fff; border: 2px solid #e2e8f0; border-radius: 12px;
      padding: 14px 10px; cursor: pointer; transition: all .14s ease;
      display: flex; flex-direction: column; align-items: center; gap: 4px;
      color: #475569; text-align: center;
      --tp-color: #0d9488;
    }
    .type-pick:hover { border-color: var(--tp-color); transform: translateY(-1px); }
    .type-pick.active {
      background: color-mix(in srgb, var(--tp-color) 8%, white);
      border-color: var(--tp-color); color: var(--tp-color);
    }
    .type-pick i { font-size: 22px; }
    .type-pick span { font-weight: 700; font-size: 14px; }
    .type-pick small { font-size: 11px; color: #94a3b8; }

    .la-loading, .la-empty {
      background: #fff; border: 1px solid #e2e8f0; border-radius: 12px;
      padding: 50px 20px; text-align: center; color: #64748b;
      display: flex; flex-direction: column; align-items: center; gap: 10px;
    }
    .la-loading i, .la-empty i { font-size: 2.4rem; color: #94a3b8; }
    .la-empty h3 { margin: 0; }
    .la-empty p { margin: 0 0 6px; }
  `]
})
export class LocationsAdminComponent implements OnInit {
  private stock = inject(StockService);
  private projectService = inject(ProjectService);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  canAdmin = computed(() => this.auth.can(PERM.STOCK_ADMIN) || !!this.auth.me()?.isOwner);

  loading = signal(true);
  locations = computed(() => this.stock.locations());

  projectOptions = signal<ProjectOption[]>([]);

  // Form dialog state
  formVisible = signal(false);
  formType: StockLocationType = 'CENTRAL';
  formProjectId: string | null = null;
  formName = '';
  formCode = '';
  saving = signal(false);

  // Visual helpers
  ltv = locationTypeVisual;

  ngOnInit(): void {
    this.stock.loadLocations().subscribe(() => this.loading.set(false));
    this.projectService.options().subscribe(opts => {
      this.projectOptions.set((opts ?? []).map(p => ({ id: p.id, name: p.name, projectType: p.projectType })));
    });
  }

  pickType(t: StockLocationType): void {
    this.formType = t;
    if (t === 'CENTRAL') {
      this.formProjectId = null;
      this.formName = this.formName || 'Dépôt central';
    } else {
      this.formName = '';
    }
  }

  onProjectPicked(): void {
    if (this.formType === 'SITE' && this.formProjectId) {
      const p = this.projectOptions().find(o => o.id === this.formProjectId);
      if (p && !this.formName) this.formName = `Stock ${p.name}`;
    }
  }

  canSave(): boolean {
    if (!this.formName.trim()) return false;
    if (this.formType === 'SITE' && !this.formProjectId) return false;
    return true;
  }

  save(): void {
    if (!this.canSave()) return;
    this.saving.set(true);
    this.stock.createLocation({
      type: this.formType,
      projectId: this.formType === 'SITE' ? this.formProjectId : null,
      name: this.formName.trim(),
      code: this.formCode.trim() || null,
    }).subscribe(loc => {
      this.saving.set(false);
      if (loc) {
        this.msgs.add({
          severity: 'success', summary: 'Emplacement créé',
          detail: `${loc.name} (${this.ltv(loc.type).label})`,
        });
        this.formVisible.set(false);
      }
    });
  }

  openAdd(): void {
    this.formType = this.locations().some(l => l.type === 'CENTRAL') ? 'SITE' : 'CENTRAL';
    this.formProjectId = null;
    this.formName = this.formType === 'CENTRAL' ? 'Dépôt central' : '';
    this.formCode = '';
    this.formVisible.set(true);
  }
}
