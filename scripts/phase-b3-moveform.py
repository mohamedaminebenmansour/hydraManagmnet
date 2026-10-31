"""Phase B3-B6 — move-form.component.ts surgical edits.

1. Fix docType bug: 'BL' → 'BON_LIVRAISON'
2. Add compensation toast for orphaned uploads (upload OK + postMove fail)
3. Add project selector for CENTRAL IN moves
4. Add "pick existing document" link + picker dialog integration
5. Relax canAttachDocument() to allow CENTRAL IN (with project selector)
6. Update submit() to handle pick-existing path
"""
from pathlib import Path

p = Path('/tmp/my-project/frontend/src/app/features/stock/move-form.component.ts')
s = p.read_text(encoding='utf-8')

# ─── Edit 1: Add DocumentPickerDialogComponent to imports ────────────────
old_imports = """import { ProgressBarModule } from 'primeng/progressbar';
import { MessageService } from 'primeng/api';"""
new_imports = """import { ProgressBarModule } from 'primeng/progressbar';
import { MessageService } from 'primeng/api';
import { DocumentPickerDialogComponent } from './document-picker-dialog.component';"""
assert old_imports in s, "imports anchor not found"
s = s.replace(old_imports, new_imports, 1)

# Add to @Component imports array
old_comp_imports = """  imports: [
    FormsModule, RouterLink, ButtonModule, InputTextModule, InputNumberModule,
    SelectModule, TextareaModule, DatePickerModule, FileUploadModule, ProgressBarModule,
  ],"""
new_comp_imports = """  imports: [
    FormsModule, RouterLink, ButtonModule, InputTextModule, InputNumberModule,
    SelectModule, TextareaModule, DatePickerModule, FileUploadModule, ProgressBarModule,
    DocumentPickerDialogComponent,
  ],"""
assert old_comp_imports in s, "@Component imports not found"
s = s.replace(old_comp_imports, new_comp_imports, 1)

# ─── Edit 2: Add new signals (after pickedFile/uploadProgress) ──────────
old_signals = """  pickedFile = signal<File | null>(null);
  uploadProgress = signal<number | null>(null);

  submitting = signal(false);"""
new_signals = """  pickedFile = signal<File | null>(null);
  uploadProgress = signal<number | null>(null);

  // "Pick existing" path — set when the user picks a doc from the picker dialog.
  selectedExistingDocId = signal<string | null>(null);
  selectedExistingDocName = signal<string | null>(null);
  pickerVisible = signal(false);

  // Project selector for CENTRAL-only IN moves (when no SITE location is involved).
  // When set, this overrides derivedProjectId for the document-attach flow.
  overrideProjectId = signal<string | null>(null);

  submitting = signal(false);"""
assert old_signals in s, "signals anchor not found"
s = s.replace(old_signals, new_signals, 1)

# ─── Edit 3: Relax canAttachDocument + add effectiveProjectId ────────────
old_can_attach = """  canAttachDocument = computed(() =>
    (this.moveType() === 'IN' || this.moveType() === 'TRANSFER') &&
    !!this.derivedProjectId());"""
new_can_attach = """  /**
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
  private effectiveProjectId = computed<string | null>(() =>
    this.derivedProjectId() ?? this.overrideProjectId());"""
assert old_can_attach in s, "canAttachDocument not found"
s = s.replace(old_can_attach, new_can_attach, 1)

# ─── Edit 4: Fix docType bug in uploadDocument ──────────────────────────
old_doctype = "    form.append('docType', 'BL');"
new_doctype = "    form.append('docType', 'BON_LIVRAISON');"
assert old_doctype in s, "docType line not found"
s = s.replace(old_doctype, new_doctype, 1)

# ─── Edit 5: Rewrite submit() with pick-existing + compensation ─────────
old_submit = """  async submit(): Promise<void> {
    if (this.validationError()) return;
    this.submitting.set(true);

    const item = this.selectedItem();
    if (!item) {
      this.submitting.set(false);
      return;
    }

    let documentId: string | null = null;

    // Optional document attach (only on IN/TRANSFER with project context)
    if (this.canAttachDocument() && this.pickedFile() && this.derivedProjectId()) {
      documentId = await this.uploadDocument(this.derivedProjectId()!, this.pickedFile()!);
      if (!documentId) {
        this.submitting.set(false);
        return; // toast already shown
      }
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
      }
    });
  }"""
new_submit = """  async submit(): Promise<void> {
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
  }"""
assert old_submit in s, "submit() not found"
s = s.replace(old_submit, new_submit, 1)

# ─── Edit 6: Add picker methods (after clearFile) ───────────────────────
old_clear = """  clearFile(): void {
    this.pickedFile.set(null);
    this.uploadProgress.set(null);
  }"""
new_clear = """  clearFile(): void {
    this.pickedFile.set(null);
    this.uploadProgress.set(null);
  }

  /** Open the existing-document picker (scoped to the project's STOCK/BL folder). */
  openPicker(): void {
    if (!this.effectiveProjectId()) {
      this.msgs.add({
        severity: 'warn', summary: 'Projet requis',
        detail: 'Sélectionnez d\\'abord un projet pour le justificatif.',
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
  }"""
assert old_clear in s, "clearFile not found"
s = s.replace(old_clear, new_clear, 1)

# ─── Edit 7: Replace the doc-attach template section ────────────────────
# Find the existing doc-attach @if block and replace it with an enriched version
# that includes: project selector (for CENTRAL IN), upload OR pick-existing
old_template = """          <!-- Document attach (optional, only when project context exists) -->
          @if (canAttachDocument()) {
            <div class="field field-wide">
              <label>Pièce jointe — BL / photo <small class="hint-inline">(optionnel)</small></label>
              <div class="doc-attach">
                <p-fileUpload mode="basic" chooseLabel="Choisir un fichier"
                              [auto]="true" [customUpload]="true"
                              (uploadHandler)="onPickFile($event)"
                              accept=".pdf,.jpg,.jpeg,.png,.webp,.heic"
                              [maxFileSize]="maxFileSize"
                              styleClass="doc-upload"></p-fileUpload>
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
                <small class="field-hint doc-hint">
                  <i class="pi pi-info-circle"></i>
                  Enregistré dans le dossier <strong>STOCK / BL</strong> du projet.
                </small>
              </div>
            </div>
          } @else if (moveType() === 'IN' || moveType() === 'TRANSFER') {
            <div class="field field-wide">
              <small class="hint-no-upload">
                <i class="pi pi-info-circle"></i>
                La pièce jointe n'est disponible que pour les mouvements sur un chantier.
                Pour le dépôt central, ajoutez la pièce plus tard depuis l'historique.
              </small>
            </div>
          }"""
new_template = """          <!-- Document attach (optional) — visible for IN/TRANSFER with project context -->
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
          }"""
assert old_template in s, "doc-attach template not found"
s = s.replace(old_template, new_template, 1)

# ─── Edit 8: Add the overrideProjectId computed signal + projectOptions ──
# Add after the canAttachDocument / effectiveProjectId block (insert before submitLabel)
old_submit_label = """  submitLabel = computed(() => {
    const v = this.mtv(this.moveType());
    return v.verb;
  });"""
new_submit_label = """  /** Writable signal for ngModel on the project selector (CENTRAL IN). */
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
  projectOptions = computed(() => this.projectService.options());

  submitLabel = computed(() => {
    const v = this.mtv(this.moveType());
    return v.verb;
  });"""
assert old_submit_label in s, "submitLabel anchor not found"
s = s.replace(old_submit_label, new_submit_label, 1)

# ─── Edit 9: Add ProjectService import + inject ──────────────────────────
old_proj_import = """import { StockService } from '@app/core/services/stock.service';"""
new_proj_import = """import { StockService } from '@app/core/services/stock.service';
import { ProjectService } from '@app/core/services/project.service';"""
assert old_proj_import in s, "StockService import not found"
s = s.replace(old_proj_import, new_proj_import, 1)

# Add the inject
old_inject = """  private stock = inject(StockService);
  private documentService = inject(DocumentService);"""
new_inject = """  private stock = inject(StockService);
  private documentService = inject(DocumentService);
  private projectService = inject(ProjectService);"""
assert old_inject in s, "inject block not found"
s = s.replace(old_inject, new_inject, 1)

# ─── Edit 10: Add styles for the new elements ───────────────────────────
old_style_end = """    .hint-no-upload i { color: #94a3b8; margin-top: 2px; }

    /* Validation error */"""
new_style_end = """    .hint-no-upload i { color: #94a3b8; margin-top: 2px; }

    /* Project selector for CENTRAL IN */
    .project-sel { display: flex; flex-direction: column; gap: 6px; margin-bottom: 10px; }
    .or-divider { display: flex; align-items: center; gap: 10px; margin: 6px 0; color: #94a3b8; font-size: 12px; }
    .or-divider::before, .or-divider::after { content: ''; flex: 1; height: 1px; background: #e2e8f0; }

    /* Validation error */"""
assert old_style_end in s, "style end anchor not found"
s = s.replace(old_style_end, new_style_end, 1)

p.write_text(s, encoding='utf-8')
print(f"OK  move-form.component.ts — 10 surgical edits applied ({len(s)} chars)")
