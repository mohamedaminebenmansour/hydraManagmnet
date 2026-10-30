import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { InputNumberModule } from 'primeng/inputnumber';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';
import { DatePickerModule } from 'primeng/datepicker';
import { FileUploadModule } from 'primeng/fileupload';
import { ProgressBarModule } from 'primeng/progressbar';
import { MessageService } from 'primeng/api';
import { DocumentPickerDialogComponent } from './document-picker-dialog.component';

import { StockService } from '@app/core/services/stock.service';
import { ProjectService } from '@app/core/services/project.service';
import { DocumentService } from '@app/core/services/document.service';
import { AuthService } from '@app/core/auth.service';
import { PERM } from '@app/core/permissions';
import {
  StockItem, StockLocation, StockMoveType, StockDocKind,
  moveTypeVisual, STOCK_MOVE_TYPE_VISUAL, todayIso,
} from '@app/core/models/stock.model';

interface TypeOption { type: StockMoveType; label: string; icon: string; color: string; }

/**
 * MoveFormComponent — /stock/mouvements/new?type=IN
 *
 * Single form for all five stock_move types. The type is selected at the
 * top via 5 large tiles (Entrée / Sortie / Consommation / Transfert /
 * Ajustement). Field requirements are driven by the move-type matrix:
 *
 *   IN          → toLocationId required; fromLocationId null
 *   OUT         → fromLocationId required; toLocationId null
 *   DIRECT_USE  → same shape as OUT
 *   TRANSFER    → both required and distinct
 *   ADJUST      → fromLocationId required; qty may be NEGATIVE
 *
 * Optional document attach (BL / photo): shown when the move involves a
 * SITE location (so the file can be filed under the project's STOCK
 * folder). For CENTRAL-only moves, the picker is hidden (a hint tells
 * the user the file should be attached via the move history page later).
 *
 * URL query params (preselect from the Stock overview quick actions):
 *   ?type=IN&itemId=…&fromLocationId=…&toLocationId=…
 */
@Component({
  selector: 'app-move-form',
  standalone: true,
  imports: [
    FormsModule, RouterLink, ButtonModule, InputTextModule, InputNumberModule,
    SelectModule, TextareaModule, DatePickerModule, FileUploadModule, ProgressBarModule,
    DocumentPickerDialogComponent,
  ],
  template: `
    <section class="mf">
      <header class="mf-head">
        <div>
          <h1><i class="pi pi-plus-circle"></i> Nouveau mouvement</h1>
          <p>Saisie rapide : entrée, sortie, transfert ou ajustement.</p>
        </div>
        <a class="back-link" [routerLink]="'/stock/mouvements'"><i class="pi pi-arrow-left"></i> Historique</a>
      </header>

      <!-- Type picker -->
      <div class="type-strip">
        @for (opt of typeOptions; track opt.type) {
          <button type="button" class="type-tile"
                  [class.active]="moveType() === opt.type"
                  [style.--tt-color]="opt.color"
                  (click)="selectType(opt.type)">
            <i class="pi" [class]="opt.icon"></i>
            <span>{{ opt.label }}</span>
          </button>
        }
      </div>

      <!-- Form -->
      <div class="mf-card">
        <div class="mf-grid">
          <!-- Article -->
          <div class="field">
            <label>Article <span class="req">*</span></label>
            <p-select [options]="itemOptions()" [(ngModel)]="selectedItemId"
                      placeholder="Sélectionner un article"
                      optionLabel="label" optionValue="id"
                      filter="true" filterBy="label"
                      (onChange)="onItemChange()" styleClass="w-100" />
            @if (!selectedItemId && canAdmin()) {
              <small class="field-hint">
                <a [routerLink]="'/stock/articles'">Ajouter un article…</a>
              </small>
            }
          </div>

          <!-- Qté -->
          <div class="field">
            <label>
              Quantité <span class="req">*</span>
              @if (moveType() === 'ADJUST') {
                <small class="hint-inline">(peut être négative)</small>
              }
            </label>
            <p-inputNumber [(ngModel)]="qty" mode="decimal" [minFractionDigits]="0"
                           [maxFractionDigits]="3" [min]="minQty()"
                           placeholder="0" [showButtons]="true" styleClass="w-100"
                           inputId="qty" />
            @if (selectedItem()) {
              <small class="field-hint">Unité : {{ selectedItem()?.uom }}</small>
            }
          </div>

          <!-- Date -->
          <div class="field">
            <label>Date du mouvement</label>
            <p-datepicker [(ngModel)]="moveDate" dateFormat="dd/mm/yy"
                          [showIcon]="true" appendTo="body" styleClass="w-100" />
          </div>

          <!-- From location -->
          @if (needsFromLocation()) {
            <div class="field">
              <label>
                {{ moveType() === 'TRANSFER' ? 'Emplacement source' : 'Emplacement' }}
                <span class="req">*</span>
              </label>
              <p-select [options]="fromLocationOptions()" [(ngModel)]="fromLocationId"
                        placeholder="Sélectionner…"
                        optionLabel="label" optionValue="id"
                        (onChange)="onLocationChange()" styleClass="w-100" />
            </div>
          }

          <!-- To location -->
          @if (needsToLocation()) {
            <div class="field">
              <label>
                {{ moveType() === 'TRANSFER' ? 'Emplacement destination' : 'Emplacement' }}
                <span class="req">*</span>
              </label>
              <p-select [options]="toLocationOptions()" [(ngModel)]="toLocationId"
                        placeholder="Sélectionner…"
                        optionLabel="label" optionValue="id"
                        (onChange)="onLocationChange()" styleClass="w-100" />
            </div>
          }

          <!-- Note -->
          <div class="field field-wide">
            <label>Note (optionnel)</label>
            <textarea pTextarea [(ngModel)]="note" rows="2"
                      placeholder="Ex : BL n°…, fournisseur…"></textarea>
          </div>

          <!-- Document attach (optional) — visible for IN/TRANSFER with project context -->
          @if (canAttachDocument()) {
            <div class="field field-wide">
              <label>Justificatif — BL / photo <small class="hint-inline">(optionnel)</small></label>

              <!-- Project selector for CENTRAL-only IN (no SITE location) -->
              @if (needsProjectSelector()) {
                <div class="project-sel">
                  <p-select [options]="projectOptions()" [(ngModel)]="overrideProjectIdSignal"
                            placeholder="Sélectionnez le projet pour le justificatif…"
                            optionLabel="name" optionValue="id"
                            (onChange)="onOverrideProjectChange($event)"
                            styleClass="w-100" />
                  <small class="field-hint">
                    <i class="pi pi-info-circle"></i>
                    Le justificatif sera rangé dans le dossier <strong>STOCK / BL</strong> de ce projet.
                  </small>
                </div>
              }

              @if (!needsProjectSelector() || overrideProjectId()) {
                <div class="doc-attach">
                  @if (!selectedExistingDocId()) {
                    <p-fileUpload mode="basic" chooseLabel="Téléverser un fichier"
                                  [auto]="true" [customUpload]="true"
                                  (uploadHandler)="onPickFile($event)"
                                  accept=".pdf,.jpg,.jpeg,.png,.webp,.heic"
                                  [maxFileSize]="maxFileSize"
                                  styleClass="doc-upload"></p-fileUpload>
                  }
                  @if (pickedFile()) {
                    <div class="picked-file">
                      <i class="pi pi-paperclip"></i>
                      <span class="pf-name">{{ pickedFile()!.name }}</span>
                      <button type="button" pButton icon="pi pi-times" [text]="true" severity="danger"
                              (click)="clearFile()"></button>
                    </div>
                  }
                  @if (uploadProgress() !== null) {
                    <p-progressBar [value]="uploadProgress()"></p-progressBar>
                  }
                  @if (selectedExistingDocId()) {
                    <div class="picked-file">
                      <i class="pi pi-file-o"></i>
                      <span class="pf-name">Document existant sélectionné</span>
                      <button type="button" pButton icon="pi pi-times" [text]="true" severity="danger"
                              (click)="clearExistingDoc()"></button>
                    </div>
                  }
                  @if (!pickedFile() && !selectedExistingDocId()) {
                    <div class="or-divider">
                      <span>ou</span>
                    </div>
                    <button type="button" pButton label="Choisir un document existant"
                            icon="pi pi-folder-open" [text]="true" severity="info"
                            (click)="openPicker()"></button>
                  }
                  <small class="field-hint doc-hint">
                    <i class="pi pi-info-circle"></i>
                    Enregistré dans le dossier <strong>STOCK / BL</strong> du projet.
                  </small>
                </div>
              }
            </div>
          } @else if (moveType() === 'IN' || moveType() === 'TRANSFER') {
            <div class="field field-wide">
              <small class="hint-no-upload">
                <i class="pi pi-info-circle"></i>
                Sélectionnez d'abord un emplacement pour activer la pièce jointe.
                Pour un transfert central→central, ajoutez la pièce plus tard depuis l'historique.
              </small>
            </div>
          }

          <!-- Document picker dialog (scoped to STOCK/BL folder) -->
          @if (effectiveProjectId()) {
            <app-document-picker-dialog [projectId]="effectiveProjectId()!"
                                        [(visible)]="pickerVisibleSignal"
                                        (picked)="onDocPicked($event)" />
          }
        </div>

        <!-- Validation summary -->
        @if (validationError()) {
          <div class="mf-error">
            <i class="pi pi-exclamation-triangle"></i>
            {{ validationError() }}
          </div>
        }

        <!-- Submit -->
        <div class="mf-actions">
          <p-button label="Annuler" [text]="true" routerLink="/stock/mouvements" />
          <p-button [label]="submitLabel()" [icon]="mtv(moveType()).icon"
                    severity="success" [loading]="submitting()"
                    [disabled]="!canSubmit()"
                    (onClick)="submit()"></p-button>
        </div>
      </div>
    </section>
  `,
  styles: [`
    .mf { display: flex; flex-direction: column; gap: 18px; max-width: 900px; }

    .mf-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; }
    .mf-head h1 { margin: 0 0 4px; font-size: 22px; font-weight: 800; display: flex; align-items: center; gap: 10px; }
    .mf-head h1 i { color: #0d9488; }
    .mf-head p { margin: 0; color: #64748b; font-size: 14px; }
    .back-link { color: #475569; text-decoration: none; font-size: 13px; font-weight: 600;
      display: inline-flex; align-items: center; gap: 6px; }
    .back-link:hover { color: #0d9488; }

    /* Type picker */
    .type-strip {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
      gap: 10px;
    }
    .type-tile {
      background: #fff;
      border: 2px solid #e2e8f0;
      border-radius: 12px;
      padding: 14px 12px;
      cursor: pointer;
      transition: all .14s ease;
      display: flex; flex-direction: column; align-items: center; gap: 6px;
      color: #475569;
      --tt-color: #16a34a;
    }
    .type-tile:hover {
      border-color: var(--tt-color);
      transform: translateY(-1px);
    }
    .type-tile.active {
      background: color-mix(in srgb, var(--tt-color) 8%, white);
      border-color: var(--tt-color);
      color: var(--tt-color);
    }
    .type-tile i { font-size: 22px; }
    .type-tile span { font-weight: 700; font-size: 14px; }

    /* Form card */
    .mf-card {
      background: #fff;
      border: 1px solid #e2e8f0;
      border-radius: 14px;
      padding: 22px 24px;
      display: flex; flex-direction: column; gap: 18px;
    }
    .mf-grid {
      display: grid;
      grid-template-columns: repeat(2, 1fr);
      gap: 16px;
    }
    .field { display: flex; flex-direction: column; gap: 6px; }
    .field.field-wide { grid-column: 1 / -1; }
    .field label { font-size: 13px; font-weight: 600; color: #475569; display: flex; align-items: center; gap: 6px; }
    .field .req { color: #dc2626; }
    .field .hint-inline { font-weight: 400; color: #94a3b8; font-size: 12px; }
    .field-hint { font-size: 12px; color: #64748b; }
    .field-hint a { color: #0d9488; text-decoration: none; }
    .field-hint a:hover { text-decoration: underline; }
    :host ::ng-deep .w-100 { width: 100%; }
    :host ::ng-deep textarea { width: 100%; resize: vertical; font-family: inherit; font-size: 14px;
      padding: 10px 12px; border: 1px solid #cbd5e1; border-radius: 8px; }

    /* Document attach */
    .doc-attach { display: flex; flex-direction: column; gap: 8px; }
    .picked-file {
      display: flex; align-items: center; gap: 8px;
      background: #f0fdfa; border: 1px solid #99f6e4; border-radius: 8px;
      padding: 8px 12px;
      font-size: 13px;
    }
    .picked-file i { color: #0d9488; }
    .picked-file .pf-name { flex: 1; font-weight: 600; overflow: hidden;
      text-overflow: ellipsis; white-space: nowrap; }
    .doc-hint { display: flex; align-items: center; gap: 6px; }
    .doc-hint i { color: #94a3b8; }
    .hint-no-upload {
      display: flex; align-items: flex-start; gap: 6px;
      background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 8px;
      padding: 10px 12px; color: #64748b; font-size: 13px;
    }
    .hint-no-upload i { color: #94a3b8; margin-top: 2px; }

    /* Project selector for CENTRAL IN */
    .project-sel { display: flex; flex-direction: column; gap: 6px; margin-bottom: 10px; }
    .or-divider { display: flex; align-items: center; gap: 10px; margin: 6px 0; color: #94a3b8; font-size: 12px; }
    .or-divider::before, .or-divider::after { content: ''; flex: 1; height: 1px; background: #e2e8f0; }

    /* Validation error */
    .mf-error {
      background: #fef2f2; border: 1px solid #fecaca; border-radius: 8px;
      padding: 10px 14px;
      color: #991b1b; font-size: 13px; font-weight: 600;
      display: flex; align-items: center; gap: 8px;
    }
    .mf-error i { color: #dc2626; }

    /* Submit */
    .mf-actions { display: flex; justify-content: flex-end; gap: 8px; }

    @media (max-width: 640px) {
      .mf-grid { grid-template-columns: 1fr; }
    }
  `]
})
export class MoveFormComponent implements OnInit {
  private stock = inject(StockService);
  private documentService = inject(DocumentService);
  private projectService = inject(ProjectService);
  private auth = inject(AuthService);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private msgs = inject(MessageService);

  canAdmin = computed(() => this.auth.can(PERM.STOCK_ADMIN) || !!this.auth.me()?.isOwner);

  // ── Form state ──────────────────────────────────────────────────────
  moveType = signal<StockMoveType>('IN');
  selectedItemId: string | null = null;
  qty: number | null = null;
  moveDate: Date = new Date();
  fromLocationId: string | null = null;
  toLocationId: string | null = null;
  note = '';

  pickedFile = signal<File | null>(null);
  uploadProgress = signal<number | null>(null);

  // "Pick existing" path — set when the user picks a doc from the picker dialog.
  selectedExistingDocId = signal<string | null>(null);
  selectedExistingDocName = signal<string | null>(null);
  pickerVisible = signal(false);

  // Project selector for CENTRAL-only IN moves (when no SITE location is involved).
  // When set, this overrides derivedProjectId for the document-attach flow.
  overrideProjectId = signal<string | null>(null);

  submitting = signal(false);

  // 50 MB upload limit (matches documents.component.ts)
  readonly maxFileSize = 50 * 1024 * 1024;

  readonly typeOptions: TypeOption[] = [
    { type: 'IN',         label: 'Entrée',       icon: STOCK_MOVE_TYPE_VISUAL['IN'].icon,        color: STOCK_MOVE_TYPE_VISUAL['IN'].color },
    { type: 'OUT',        label: 'Sortie',       icon: STOCK_MOVE_TYPE_VISUAL['OUT'].icon,       color: STOCK_MOVE_TYPE_VISUAL['OUT'].color },
    { type: 'DIRECT_USE', label: 'Consommation', icon: STOCK_MOVE_TYPE_VISUAL['DIRECT_USE'].icon, color: STOCK_MOVE_TYPE_VISUAL['DIRECT_USE'].color },
    { type: 'TRANSFER',   label: 'Transfert / sortie dépôt',    icon: STOCK_MOVE_TYPE_VISUAL['TRANSFER'].icon,  color: STOCK_MOVE_TYPE_VISUAL['TRANSFER'].color },
    { type: 'ADJUST',     label: 'Ajustement',   icon: STOCK_MOVE_TYPE_VISUAL['ADJUST'].icon,    color: STOCK_MOVE_TYPE_VISUAL['ADJUST'].color },
  ];

  // ── Derived state ────────────────────────────────────────────────────
  itemOptions = computed(() => this.stock.items()
    .filter(i => i.active)
    .map(i => ({ id: i.id, label: `${i.name}${i.code ? ' (' + i.code + ')' : ''}` })));

  fromLocationOptions = computed(() => this.locationLabelList(this.availableLocations('from')));
  toLocationOptions = computed(() => this.locationLabelList(this.availableLocations('to')));

  selectedItem = computed<StockItem | undefined>(() =>
    this.stock.items().find(i => i.id === this.selectedItemId));

  /** Available locations for a given side, scoped by selected project (none here — all visible). */
  availableLocations(side: 'from' | 'to'): StockLocation[] {
    const locs = this.stock.locations().filter(l => l.active);
    if (this.moveType() === 'TRANSFER') {
      // For TRANSFER: from = any active loc, to = any active loc ≠ from
      if (side === 'from') return locs;
      return locs.filter(l => l.id !== this.fromLocationId);
    }
    // IN → to side only; OUT/DIRECT_USE/ADJUST → from side only
    return locs;
  }

  private locationLabelList(locs: StockLocation[]): { id: string; label: string }[] {
    return locs.map(l => ({
      id: l.id,
      label: `${l.name}${l.type === 'CENTRAL' ? ' (central)' : l.projectName ? ' · ' + l.projectName : ''}`,
    }));
  }

  needsFromLocation = computed(() =>
    ['OUT', 'DIRECT_USE', 'TRANSFER', 'ADJUST'].includes(this.moveType()));

  needsToLocation = computed(() =>
    ['IN', 'TRANSFER'].includes(this.moveType()));

  minQty = computed(() =>
    this.moveType() === 'ADJUST' ? -1000000 : 0.001);

  /** Project derived from the selected location (for document attach). */
  private derivedProjectId = computed<string | null>(() => {
    const from = this.stock.locationById(this.fromLocationId ?? '');
    const to = this.stock.locationById(this.toLocationId ?? '');
    if (from?.type === 'SITE' && from.projectId) return from.projectId;
    if (to?.type === 'SITE' && to.projectId) return to.projectId;
    return null;
  });

  /**
   * The document-attach section is shown for IN and TRANSFER moves when:
   *  - a SITE location is selected (projectId derived from it), OR
   *  - the user has manually selected a project (CENTRAL-only IN + overrideProjectId).
   * CENTRAL→CENTRAL TRANSFER is excluded (no project context possible).
   */
  canAttachDocument = computed(() => {
    if (this.moveType() !== 'IN' && this.moveType() !== 'TRANSFER') return false;
    // Block CENTRAL→CENTRAL TRANSFER (no project context)
    if (this.moveType() === 'TRANSFER') {
      const from = this.stock.locationById(this.fromLocationId ?? '');
      const to = this.stock.locationById(this.toLocationId ?? '');
      if (from?.type === 'CENTRAL' && to?.type === 'CENTRAL') return false;
    }
    return !!this.derivedProjectId() || !!this.overrideProjectId();
  });

  /** True when the move is IN at CENTRAL and a project must be manually selected. */
  needsProjectSelector = computed(() => {
    if (this.moveType() !== 'IN') return false;
    const to = this.stock.locationById(this.toLocationId ?? '');
    return to?.type === 'CENTRAL';
  });

  /** The projectId to use for document operations (derived from SITE OR user-selected). */
  effectiveProjectId = computed<string | null>(() =>
    this.derivedProjectId() ?? this.overrideProjectId());

  /** Writable signal for ngModel on the project selector (CENTRAL IN). */
  overrideProjectIdSignal = signal<string | null>(null);
  /** Two-way bound visible for the picker dialog (template uses this). */
  pickerVisibleSignal = signal(false);

  /** Sync the ngModel signal → the overrideProjectId computed chain. */
  onOverrideProjectChange(ev: any): void {
    const val = ev?.value ?? null;
    this.overrideProjectIdSignal.set(val);
    this.overrideProjectId.set(val);
  }

  /** Project options for the CENTRAL-IN selector (reuse ProjectService). */
  projectOptions = signal<{ id: string; name: string }[]>([]);
  private loadProjectOptions(): void {
    this.projectService.options().subscribe(opts => {
      this.projectOptions.set((opts ?? []).map(o => ({ id: o.id, name: o.name })));
    });
  }

  submitLabel = computed(() => {
    const v = this.mtv(this.moveType());
    return v.verb;
  });

  // ── Helpers ─────────────────────────────────────────────────────────
  mtv = moveTypeVisual;

  ngOnInit(): void {
    this.stock.loadItems().subscribe();
    this.stock.loadLocations().subscribe();
    this.loadProjectOptions();

    // Read query params (?type=IN&itemId=…&fromLocationId=…&toLocationId=…)
    this.route.queryParams.subscribe(p => {
      const t = String(p['type'] ?? 'IN').toUpperCase() as StockMoveType;
      if (['IN', 'OUT', 'DIRECT_USE', 'TRANSFER', 'ADJUST'].includes(t)) {
        this.moveType.set(t);
      }
      this.selectedItemId = p['itemId'] ?? null;
      this.fromLocationId = p['fromLocationId'] ?? null;
      this.toLocationId = p['toLocationId'] ?? null;
      // Auto-fill uom from selected item
      if (this.selectedItemId) {
        this.onItemChange();
      }
    });
  }

  selectType(t: StockMoveType): void {
    this.moveType.set(t);
    // Reset location fields that don't apply to the new type
    if (!this.needsFromLocation()) this.fromLocationId = null;
    if (!this.needsToLocation()) this.toLocationId = null;
  }

  onItemChange(): void {
    // No-op for now (uom is read off the item at submit time)
  }

  onLocationChange(): void {
    // Recompute canAttachDocument (computed signal — auto-updates)
  }

  onPickFile(ev: any): void {
    const f: File = ev?.files?.[0];
    if (f) this.pickedFile.set(f);
  }

  clearFile(): void {
    this.pickedFile.set(null);
    this.uploadProgress.set(null);
  }

  /** Open the existing-document picker (scoped to the project's STOCK/BL folder). */
  openPicker(): void {
    if (!this.effectiveProjectId()) {
      this.msgs.add({
        severity: 'warn', summary: 'Projet requis',
        detail: 'Sélectionnez d\'abord un projet pour le justificatif.',
      });
      return;
    }
    this.pickerVisible.set(true);
  }

  /** Called when the picker emits a selected documentId. */
  onDocPicked(docId: string): void {
    this.selectedExistingDocId.set(docId);
    // Clear any picked file (mutually exclusive)
    this.pickedFile.set(null);
    this.uploadProgress.set(null);
    // Try to show a friendly name — fetch is optional, id is enough for the API
    this.selectedExistingDocName.set(null);
    this.msgs.add({
      severity: 'info', summary: 'Document sélectionné',
      detail: 'Le document existant sera lié au mouvement à la création.',
    });
  }

  clearExistingDoc(): void {
    this.selectedExistingDocId.set(null);
    this.selectedExistingDocName.set(null);
  }

  // ── Validation ──────────────────────────────────────────────────────
  validationError = computed<string | null>(() => {
    if (!this.selectedItemId) return 'Veuillez sélectionner un article.';
    if (this.qty == null || this.qty === 0) return 'Veuillez saisir une quantité non nulle.';
    if (this.moveType() !== 'ADJUST' && this.qty <= 0) return 'La quantité doit être positive.';
    if (this.needsFromLocation() && !this.fromLocationId) {
      return 'Veuillez sélectionner l\'emplacement source.';
    }
    if (this.needsToLocation() && !this.toLocationId) {
      return 'Veuillez sélectionner l\'emplacement de destination.';
    }
    if (this.moveType() === 'TRANSFER' && this.fromLocationId === this.toLocationId) {
      return 'Les emplacements source et destination doivent être différents.';
    }
    return null;
  });

  canSubmit = computed(() =>
    !this.submitting() && this.validationError() === null);

  // ── Submit ──────────────────────────────────────────────────────────
  async submit(): Promise<void> {
    if (this.validationError()) return;
    this.submitting.set(true);

    const item = this.selectedItem();
    if (!item) {
      this.submitting.set(false);
      return;
    }

    let documentId: string | null = null;
    let uploadedDocProjectId: string | null = null;

    // Path A: user picked a file → upload it first
    if (this.canAttachDocument() && this.pickedFile() && this.effectiveProjectId()) {
      uploadedDocProjectId = this.effectiveProjectId()!;
      documentId = await this.uploadDocument(uploadedDocProjectId, this.pickedFile()!);
      if (!documentId) {
        this.submitting.set(false);
        return; // upload failed — toast already shown
      }
    }

    // Path B: user picked an existing document → use its id directly (no upload)
    if (this.canAttachDocument() && this.selectedExistingDocId() && !this.pickedFile()) {
      documentId = this.selectedExistingDocId();
    }

    const body = {
      moveType: this.moveType(),
      qty: this.qty!,
      uom: item.uom,
      moveDate: this.toIsoDate(this.moveDate),
      fromLocationId: this.fromLocationId ?? null,
      toLocationId: this.toLocationId ?? null,
      itemId: this.selectedItemId!,
      documentId,
      note: this.note.trim() || null,
    };

    this.stock.postMove(body).subscribe(move => {
      this.submitting.set(false);
      if (move) {
        this.msgs.add({
          severity: 'success',
          summary: this.mtv(move.moveType).label + ' enregistrée',
          detail: `${move.itemName} — ${move.qty} ${move.uom}`,
        });
        this.router.navigate(['/stock/mouvements']);
      } else if (uploadedDocProjectId && documentId) {
        // COMPENSATION: upload succeeded but postMove failed → orphaned document.
        // The doc is filed under the project's STOCK/BL folder and can be linked
        // later from the Historique. Be explicit — do not silently swallow.
        this.msgs.add({
          severity: 'warn',
          summary: 'Pièce téléversée mais le mouvement a échoué',
          detail: `La pièce « ${this.pickedFile()?.name ?? 'BL'} » est dans le dossier STOCK/BL du projet. Le mouvement n'a pas été créé. Vous pourrez lier cette pièce depuis l'historique une fois le mouvement créé.`,
          life: 12000,
        });
      }
    });
  }

  /** Resolve-or-create the STOCK/BL folder, then upload the file via the
   *  existing documents API. Returns the new documentId, or null on error. */
  private async uploadDocument(projectId: string, file: File): Promise<string | null> {
    this.uploadProgress.set(0);

    // Step 1: resolve-or-create the stock subfolder
    const folder = await this.stock.ensureStockFolder(projectId, 'BL' as StockDocKind).toPromise();
    if (!folder?.folderId) {
      this.uploadProgress.set(null);
      return null;
    }

    // Step 2: build FormData and upload via the existing document API
    const form = new FormData();
    form.append('file', file, file.name);
    form.append('title', `BL — ${file.name}`);
    form.append('docType', 'BON_LIVRAISON');

    return new Promise<string | null>(resolve => {
      this.documentService.upload(projectId, folder.folderId, form).subscribe({
        next: ev => {
          if (ev.type === 'progress') {
            this.uploadProgress.set(ev.percent);
          } else if (ev.type === 'done') {
            this.uploadProgress.set(100);
            this.msgs.add({
              severity: 'info', summary: 'Pièce jointe',
              detail: `BL enregistré dans le dossier STOCK du projet`,
            });
            resolve(ev.result?.id ?? null);
          }
        },
        error: () => {
          this.uploadProgress.set(null);
          this.msgs.add({
            severity: 'error', summary: 'Échec upload',
            detail: 'La pièce n\'a pas pu être enregistrée',
          });
          resolve(null);
        },
      });
    });
  }

  private toIsoDate(d: Date): string {
    return d.toISOString().slice(0, 10);
  }
}
