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
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import {
  StockItem, ItemAlias, mapAlias,
} from '@app/core/models/stock.model';

const UOM_OPTIONS = [
  { value: 'sac',  label: 'sac' },
  { value: 'kg',   label: 'kg' },
  { value: 'm³',   label: 'm³ (mètre cube)' },
  { value: 'm²',   label: 'm² (mètre carré)' },
  { value: 'mL',   label: 'mL (mètre linéaire)' },
  { value: 'pièce', label: 'pièce' },
  { value: 'L',    label: 'L (litre)' },
  { value: 'baril', label: 'baril' },
  { value: 'rouleau', label: 'rouleau' },
  { value: 'palette', label: 'palette' },
];

/**
 * ItemsAdminComponent — /stock/articles
 *
 * Répertoire des articles (lightweight catalog: name + uom + optional code).
 * Visible to anyone with STOCK_VIEW; write actions (create / edit / alias
 * management) require STOCK_ADMIN or owner.
 *
 * Layout: hand-built HTML table (default for HYDRA lists). One "add" dialog
 * covers create + edit. Aliases are managed in a secondary dialog.
 */
@Component({
  selector: 'app-items-admin',
  standalone: true,
  imports: [FormsModule, RouterLink, ButtonModule, InputTextModule, SelectModule, DialogModule, TooltipModule],
  template: `
    <section class="ia">
      <header class="ia-head">
        <div>
          <h1><i class="pi pi-tags"></i> Articles</h1>
          <p>Répertoire des articles stockés. Ajoutez le nom, l'unité et un code optionnel.</p>
        </div>
        <a class="back-link" [routerLink]="'/stock'"><i class="pi pi-arrow-left"></i> Hub stock</a>
      </header>

      @if (canAdmin()) {
        <div class="ia-toolbar">
          <button pButton label="Nouvel article" icon="pi pi-plus" severity="success" (click)="openAdd()"></button>
        </div>
      }

      @if (loading()) {
        <div class="ia-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else if (items().length === 0) {
        <div class="ia-empty">
          <i class="pi pi-tags"></i>
          <h3>Aucun article</h3>
          <p>Le répertoire est vide. Ajoutez votre premier article pour commencer.</p>
          @if (canAdmin()) {
            <button pButton label="Ajouter" icon="pi pi-plus" severity="success" (click)="openAdd()"></button>
          }
        </div>
      } @else {
        <div class="ia-table-wrap">
          <table class="ia-table">
            <thead>
              <tr>
                <th>Article</th>
                <th>Code</th>
                <th>Unité</th>
                <th>Alias</th>
                <th>Statut</th>
                @if (canAdmin()) { <th class="col-actions">Actions</th> }
              </tr>
            </thead>
            <tbody>
              @for (it of items(); track it.id) {
                <tr [class.inactive]="!it.active">
                  <td class="c-name">{{ it.name }}</td>
                  <td class="c-code">{{ it.code || '—' }}</td>
                  <td class="c-uom">{{ it.uom }}</td>
                  <td>
                    @if (aliasCounts()[it.id] > 0) {
                      <button pButton [text]="true" icon="pi pi-tags" [label]="aliasCounts()[it.id].toString()"
                              pTooltip="Voir les alias" tooltipPosition="left"
                              (click)="openAliases(it)"></button>
                    } @else {
                      <span class="c-noalias">—</span>
                      @if (canAdmin()) {
                        <button pButton icon="pi pi-plus" [text]="true" severity="info"
                                pTooltip="Ajouter un alias" tooltipPosition="left"
                                (click)="openAliases(it)"></button>
                      }
                    }
                  </td>
                  <td>
                    <span class="status-chip" [class.active]="it.active" [class.inactive]="!it.active">
                      <i class="pi" [class]="it.active ? 'pi-check-circle' : 'pi-ban'"></i>
                      {{ it.active ? 'Actif' : 'Inactif' }}
                    </span>
                  </td>
                  @if (canAdmin()) {
                    <td class="col-actions">
                      <button pButton icon="pi pi-pencil" [text]="true"
                              pTooltip="Modifier" tooltipPosition="left"
                              (click)="openEdit(it)"></button>
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
      }

      <!-- Add / Edit dialog -->
      <p-dialog [header]="editing() ? 'Modifier l\\'article' : 'Nouvel article'"
                [(visible)]="formVisible" [modal]="true" [style]="{ width: '440px' }">
        <div class="form-grid">
          <div class="field">
            <label>Nom <span class="req">*</span></label>
            <input pInputText [(ngModel)]="formName" placeholder="Ex : Ciment 25kg" />
          </div>
          <div class="field">
            <label>Code <small>(optionnel)</small></label>
            <input pInputText [(ngModel)]="formCode" placeholder="Ex : CIM25" />
            <small class="field-hint">Code interne court (unique par entreprise).</small>
          </div>
          <div class="field">
            <label>Unité <span class="req">*</span></label>
            <p-select [options]="uomOptions" [(ngModel)]="formUom"
                      optionLabel="label" optionValue="value"
                      [editable]="true" styleClass="w-100" />
          </div>
          @if (editing()) {
            <div class="field field-checkbox">
              <label>Statut</label>
              <p-select [options]="statusOptions" [(ngModel)]="formActive"
                        optionLabel="label" optionValue="value" styleClass="w-100" />
              <small class="field-hint">Inactif = désactivé (n'apparaît plus dans les sélecteurs).</small>
            </div>
          }
        </div>
        <ng-template pTemplate="footer">
          <p-button label="Annuler" [text]="true" (onClick)="formVisible.set(false)" />
          <p-button [label]="editing() ? 'Enregistrer' : 'Créer'" icon="pi pi-check" severity="success"
                    [disabled]="!formName.trim() || !formUom.trim()" [loading]="saving()"
                    (onClick)="save()" />
        </ng-template>
      </p-dialog>

      <!-- Aliases dialog -->
      <p-dialog [header]="'Alias — ' + (aliasTarget()?.name ?? '')"
                [(visible)]="aliasVisible" [modal]="true" [style]="{ width: '480px' }">
        <div class="alias-hint">
          <i class="pi pi-info-circle"></i>
          Un alias est un nom alternatif (ex : le nom du fournisseur). Permet de retrouver
          l'article rapidement lors de la saisie.
        </div>
        <div class="alias-add">
          <input pInputText [(ngModel)]="newAlias" placeholder="Ex : gris foncé"
                 (keyup.enter)="addAlias()" />
          <button pButton icon="pi pi-plus" severity="success" [disabled]="!newAlias.trim()"
                  (click)="addAlias()"></button>
        </div>
        @if (aliases().length === 0) {
          <div class="alias-empty">Aucun alias pour le moment.</div>
        } @else {
          <ul class="alias-list">
            @for (a of aliases(); track a.id) {
              <li>
                <span class="alias-name">{{ a.alias }}</span>
                <button pButton icon="pi pi-trash" [text]="true" severity="danger"
                        (click)="removeAlias(a)"></button>
              </li>
            }
          </ul>
        }
        <ng-template pTemplate="footer">
          <p-button label="Fermer" [text]="true" (onClick)="aliasVisible.set(false)" />
        </ng-template>
      </p-dialog>
    </section>
  `,
  styles: [`
    .ia { display: flex; flex-direction: column; gap: 14px; }
    .ia-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; }
    .ia-head h1 { margin: 0 0 4px; font-size: 22px; font-weight: 800; display: flex; align-items: center; gap: 10px; }
    .ia-head h1 i { color: #0d9488; }
    .ia-head p { margin: 0; color: #64748b; font-size: 14px; }
    .back-link { color: #475569; text-decoration: none; font-size: 13px; font-weight: 600;
      display: inline-flex; align-items: center; gap: 6px; }
    .back-link:hover { color: #0d9488; }

    .ia-toolbar { display: flex; justify-content: flex-end; }

    .ia-table-wrap { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden; }
    .ia-table { width: 100%; border-collapse: collapse; font-size: 14px; }
    .ia-table th {
      background: #f8fafc; color: #64748b; font-weight: 600; font-size: 12px;
      text-transform: uppercase; letter-spacing: .04em;
      text-align: left; padding: 12px 16px; border-bottom: 1px solid #e2e8f0;
    }
    .ia-table th.col-actions { text-align: center; width: 80px; }
    .ia-table td {
      padding: 12px 16px; border-bottom: 1px solid #f1f5f9; vertical-align: middle;
    }
    .ia-table td.col-actions { text-align: center; white-space: nowrap; }
    .ia-table tr:last-child td { border-bottom: none; }
    .ia-table tr:hover td { background: #f8fafc; }
    .ia-table tr.inactive td { opacity: 0.6; }
    .c-name { font-weight: 600; }
    .c-code { font-family: 'Courier New', monospace; color: #475569; }
    .c-uom { color: #475569; }
    .c-noalias { color: #cbd5e1; margin-right: 4px; }

    .status-chip {
      display: inline-flex; align-items: center; gap: 5px;
      padding: 3px 10px; border-radius: 999px;
      font-size: 12px; font-weight: 700;
    }
    .status-chip.active  { background: #f0fdf4; color: #16a34a; }
    .status-chip.inactive { background: #f1f5f9; color: #94a3b8; }

    .form-grid { display: flex; flex-direction: column; gap: 14px; }
    .field { display: flex; flex-direction: column; gap: 6px; }
    .field label { font-size: 13px; font-weight: 600; color: #475569; }
    .field .req { color: #dc2626; }
    .field small { font-weight: 400; color: #94a3b8; }
    .field-hint { font-size: 12px; color: #64748b; }
    :host ::ng-deep .w-100 { width: 100%; }

    .alias-hint {
      background: #f0fdfa; border: 1px solid #99f6e4; border-radius: 8px;
      padding: 10px 12px; font-size: 13px; color: #134e4a;
      display: flex; align-items: flex-start; gap: 8px; margin-bottom: 12px;
    }
    .alias-hint i { color: #0d9488; margin-top: 2px; }
    .alias-add { display: flex; gap: 8px; margin-bottom: 12px; }
    .alias-add input { flex: 1; }
    .alias-empty { text-align: center; color: #64748b; padding: 20px; }
    .alias-list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 6px; }
    .alias-list li {
      display: flex; align-items: center; justify-content: space-between;
      background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px;
      padding: 8px 12px;
    }
    .alias-name { font-weight: 600; }

    .ia-loading, .ia-empty {
      background: #fff; border: 1px solid #e2e8f0; border-radius: 12px;
      padding: 50px 20px; text-align: center; color: #64748b;
      display: flex; flex-direction: column; align-items: center; gap: 10px;
    }
    .ia-loading i, .ia-empty i { font-size: 2.4rem; color: #94a3b8; }
    .ia-empty h3 { margin: 0; }
    .ia-empty p { margin: 0 0 6px; }
  `]
})
export class ItemsAdminComponent implements OnInit {
  private stock = inject(StockService);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  canAdmin = computed(() => this.auth.can(PERM.STOCK_ADMIN) || !!this.auth.me()?.isOwner);

  loading = signal(true);
  items = computed(() => this.stock.items());

  // Form dialog state
  formVisible = signal(false);
  editing = signal(false);
  editingId: string | null = null;
  formName = '';
  formCode = '';
  formUom = 'sac';
  formActive = true;
  saving = signal(false);

  // Aliases dialog state
  aliasVisible = signal(false);
  aliasTarget = signal<StockItem | null>(null);
  aliases = signal<ItemAlias[]>([]);
  newAlias = '';

  aliasCounts = signal<Record<string, number>>({});

  readonly uomOptions = UOM_OPTIONS;
  readonly statusOptions = [
    { value: true,  label: 'Actif' },
    { value: false, label: 'Inactif' },
  ];

  ngOnInit(): void {
    this.stock.loadItems(false, false).subscribe(items => {
      this.loading.set(false);
      // Preload alias counts so the table badges show without a click
      for (const it of items) {
        this.stock.listAliases(it.id).subscribe(list => {
          this.aliasCounts.update(m => ({ ...m, [it.id]: list.length }));
        });
      }
    });
  }

  openAdd(): void {
    this.editing.set(false);
    this.editingId = null;
    this.formName = '';
    this.formCode = '';
    this.formUom = 'sac';
    this.formActive = true;
    this.formVisible.set(true);
  }

  openEdit(it: StockItem): void {
    this.editing.set(true);
    this.editingId = it.id;
    this.formName = it.name;
    this.formCode = it.code ?? '';
    this.formUom = it.uom;
    this.formActive = it.active;
    this.formVisible.set(true);
  }

  save(): void {
    if (!this.formName.trim() || !this.formUom.trim()) return;
    this.saving.set(true);

    if (this.editing()) {
      this.stock.updateItem(this.editingId!, {
        name: this.formName.trim(),
        code: this.formCode.trim() || null,
        uom: this.formUom.trim(),
        active: this.formActive,
      }).subscribe(item => {
        this.saving.set(false);
        if (item) {
          this.msgs.add({ severity: 'success', summary: 'Article modifié', detail: item.name });
          this.formVisible.set(false);
        }
      });
    } else {
      this.stock.createItem({
        name: this.formName.trim(),
        code: this.formCode.trim() || null,
        uom: this.formUom.trim(),
      }).subscribe(item => {
        this.saving.set(false);
        if (item) {
          this.msgs.add({ severity: 'success', summary: 'Article créé', detail: item.name });
          this.formVisible.set(false);
        }
      });
    }
  }

  openAliases(it: StockItem): void {
    this.aliasTarget.set(it);
    this.newAlias = '';
    this.aliasVisible.set(true);
    this.stock.listAliases(it.id).subscribe(list => this.aliases.set(list));
  }

  addAlias(): void {
    const target = this.aliasTarget();
    if (!target || !this.newAlias.trim()) return;
    this.stock.createAlias(target.id, { alias: this.newAlias.trim() }).subscribe(a => {
      if (a) {
        this.aliases.update(list => [...list, a]);
        this.aliasCounts.update(m => ({ ...m, [target.id]: (m[target.id] ?? 0) + 1 }));
        this.newAlias = '';
      }
    });
  }

  removeAlias(a: ItemAlias): void {
    const target = this.aliasTarget();
    if (!target) return;
    this.stock.deleteAlias(target.id, a.id).subscribe(ok => {
      if (ok) {
        this.aliases.update(list => list.filter(x => x.id !== a.id));
        this.aliasCounts.update(m => ({ ...m, [target.id]: Math.max(0, (m[target.id] ?? 0) - 1) }));
      }
    });
  }
}
