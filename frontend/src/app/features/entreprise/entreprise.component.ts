import { Component, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TooltipModule } from 'primeng/tooltip';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { ConfirmationService, MessageService } from 'primeng/api';
import { CompanyService, CompanyProfile, Groupement } from '@app/core/services/company.service';
import { EmptyStateComponent } from '@app/shared/empty-state.component';
import { LoadErrorComponent } from '@app/shared/load-error.component';
import { GroupementPanelComponent } from './groupement-panel.component';
import {
  normalizeMf, validateMf, formatMf,
  normalizeRib, validateRib, formatRib, ibanFromRib, formatIban
} from '@app/shared/tunisian-ids';

@Component({
  selector: 'app-entreprise',
  standalone: true,
  imports: [
    FormsModule, ButtonModule, InputTextModule, SelectModule, TooltipModule,
    ConfirmDialogModule, EmptyStateComponent, LoadErrorComponent, GroupementPanelComponent
  ],
  providers: [ConfirmationService],
  template: `
    <div class="page">
      <header class="head">
        <div>
          <h1><i class="pi pi-building title-icon"></i> Entreprise</h1>
          <p class="sub">Les informations imprimées sur vos bons de commande, décomptes et factures.</p>
        </div>
        @if (profile()) {
          <button class="completeness-chip" [class.complete]="profile()!.complet" (click)="goToFirstMissing()">
            @if (profile()!.complet) {
              <i class="pi pi-check-circle"></i> Profil complet
            } @else {
              <i class="pi pi-exclamation-triangle"></i> {{ missingCount() }} information(s) manquante(s)
            }
          </button>
        }
      </header>

      @if (loading()) {
        <div class="state"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      }
      @if (!loading() && loadError()) {
        <app-load-error message="Impossible de charger le profil." (retry)="load()" />
      }

      @if (staleProfile()) {
        <div class="banner banner-warn">
          <i class="pi pi-exclamation-triangle"></i> Le profil a été modifié par quelqu'un d'autre.
          <p-button label="Recharger" size="small" (onClick)="load()" />
        </div>
      }

      @if (profile()) {
        <!-- Live preview (reads the EDIT model, not the saved profile) -->
        <div class="preview-card">
          <div class="preview-left">
            @if (profile()!.hasLogo) {
              <img [src]="logoUrl" alt="Logo" class="preview-logo" />
            } @else {
              <div class="logo-placeholder">Votre logo</div>
            }
          </div>
          <div class="preview-center">
            <div class="preview-raison" [class.missing]="!edit.raisonSociale"
                 [attr.title]="!edit.raisonSociale ? 'Champ obligatoire sur les documents' : null">
              {{ edit.raisonSociale || '—' }}
            </div>
            @if (edit.formeJuridique || edit.capital) {
              <div class="preview-forme">
                {{ edit.formeJuridique || '' }}
                @if (edit.capital) {
                  <span class="capital-line">au capital de {{ formatCapital(edit.capital) }} TND</span>
                }
              </div>
            }
            <div class="preview-adresse" [class.missing]="!edit.adresse"
                 [attr.title]="!edit.adresse ? 'Champ obligatoire sur les documents' : null">
              {{ edit.adresse || '—' }}
            </div>
            <div class="preview-ville" [class.missing]="!edit.ville"
                 [attr.title]="!edit.ville ? 'Champ obligatoire sur les documents' : null">
              {{ edit.codePostal }} {{ edit.ville || '—' }}
            </div>
          </div>
          <div class="preview-right">
            @if (mfFormattedPreview()) {
              <div class="preview-mf">MF : {{ mfFormattedPreview() }}</div>
            } @else {
              <div class="preview-mf missing" [attr.title]="!edit.matriculeFiscal ? 'Champ obligatoire sur les documents' : null">
                MF : {{ edit.matriculeFiscal ? edit.matriculeFiscal : '—' }}
              </div>
            }
            @if (edit.rne) { <div class="preview-rne">RNE : {{ edit.rne }}</div> }
            <div class="preview-tel" [class.missing]="!edit.telephone"
                 [attr.title]="!edit.telephone ? 'Champ obligatoire sur les documents' : null">
              {{ edit.telephone || '—' }}
            </div>
            @if (edit.email) { <div class="preview-email">{{ edit.email }}</div> }
          </div>
          <div class="preview-rib" [class.missing]="!edit.rib"
               [attr.title]="!edit.rib ? 'Champ obligatoire sur les documents' : null">
            @if (ribFormattedPreview()) {
              RIB : {{ ribFormattedPreview() }} @if (edit.banque) { — {{ edit.banque }} }
            } @else {
              RIB : {{ edit.rib || '—' }}
            }
          </div>
        </div>

        <!-- Tabs -->
        <div class="tabs">
          <button class="tab" [class.active]="tab() === 'identite'" (click)="tab.set('identite')">Identité</button>
          <button class="tab" [class.active]="tab() === 'adresse'" (click)="tab.set('adresse')">Adresse et contact</button>
          <button class="tab" [class.active]="tab() === 'banque'" (click)="tab.set('banque')">Banque</button>
          <button class="tab" [class.active]="tab() === 'logo'" (click)="tab.set('logo')">Logo</button>
          <button class="tab" [class.active]="tab() === 'groupements'" (click)="tab.set('groupements')">Groupements</button>
        </div>

        <!-- Identité tab -->
        @if (tab() === 'identite') {
          <div class="tab-content">
            <div class="fields">
              <div class="field">
                <label>Pays</label>
                <p-select [options]="paysOptions" [(ngModel)]="edit.pays" optionLabel="label" optionValue="value" styleClass="w100" />
              </div>
              <div class="field">
                <label>Raison sociale *</label>
                <input pInputText [(ngModel)]="edit.raisonSociale" (ngModelChange)="clearFieldError('raisonSociale')" />
                @if (fieldErrors()['raisonSociale']) { <small class="field-error">{{ fieldErrors()['raisonSociale'] }}</small> }
              </div>
              <div class="field">
                <label>Nom commercial</label>
                <input pInputText [(ngModel)]="edit.nomCommercial" />
              </div>
              <div class="field">
                <label>Forme juridique</label>
                <p-select [options]="formeOptions" [(ngModel)]="edit.formeJuridique" optionLabel="label" optionValue="value"
                          styleClass="w100" [showClear]="true" />
              </div>
              <div class="field">
                <label>Matricule fiscal</label>
                <input pInputText [(ngModel)]="edit.matriculeFiscal"
                       (ngModelChange)="onMfChange($event)"
                       (blur)="onMfBlur()" placeholder="1234567/A/B/M/000" />
                @if (mfError()) { <small class="field-error">{{ mfError() }}</small> }
                @else if (fieldErrors()['matriculeFiscal']) { <small class="field-error">{{ fieldErrors()['matriculeFiscal'] }}</small> }
                @if (edit.pays === 'TN') { <span class="hint">Format : 1234567/A/B/M/000</span> }
              </div>
              <div class="field">
                <label>RNE</label>
                <input pInputText [(ngModel)]="edit.rne" />
              </div>
              <div class="field">
                <label>Capital (TND)</label>
                <input pInputText type="text" inputmode="decimal" [(ngModel)]="edit.capital" />
              </div>
              <div class="field">
                <label>Assujetti à la TVA</label>
                <label class="toggle"><input type="checkbox" [(ngModel)]="edit.assujettiTva" /> Assujetti</label>
              </div>
            </div>
            <p-button label="Enregistrer" icon="pi pi-check" severity="success" [loading]="saving()" (onClick)="saveProfile()" />
          </div>
        }

        <!-- Adresse tab -->
        @if (tab() === 'adresse') {
          <div class="tab-content">
            <div class="fields">
              <div class="field">
                <label>Adresse *</label>
                <input pInputText [(ngModel)]="edit.adresse" (ngModelChange)="clearFieldError('adresse')" />
                @if (fieldErrors()['adresse']) { <small class="field-error">{{ fieldErrors()['adresse'] }}</small> }
              </div>
              <div class="field"><label>Code postal</label><input pInputText [(ngModel)]="edit.codePostal" /></div>
              <div class="field">
                <label>Ville *</label>
                <input pInputText [(ngModel)]="edit.ville" (ngModelChange)="clearFieldError('ville')" />
                @if (fieldErrors()['ville']) { <small class="field-error">{{ fieldErrors()['ville'] }}</small> }
              </div>
              <div class="field">
                <label>Gouvernorat</label>
                @if (edit.pays === 'TN') {
                  <p-select [options]="gouvernoratOptions" [(ngModel)]="edit.gouvernorat" styleClass="w100" [showClear]="true" />
                } @else {
                  <input pInputText [(ngModel)]="edit.gouvernorat" />
                }
              </div>
              <div class="field">
                <label>Téléphone *</label>
                <input pInputText type="tel" [(ngModel)]="edit.telephone" (ngModelChange)="clearFieldError('telephone')" />
                @if (fieldErrors()['telephone']) { <small class="field-error">{{ fieldErrors()['telephone'] }}</small> }
              </div>
              <div class="field">
                <label>E-mail</label>
                <input pInputText type="email" [(ngModel)]="edit.email" (ngModelChange)="clearFieldError('email')" />
                @if (fieldErrors()['email']) { <small class="field-error">{{ fieldErrors()['email'] }}</small> }
              </div>
              <div class="field"><label>Site web</label><input pInputText [(ngModel)]="edit.siteWeb" /></div>
            </div>
            <p-button label="Enregistrer" icon="pi pi-check" severity="success" [loading]="saving()" (onClick)="saveProfile()" />
          </div>
        }

        <!-- Banque tab -->
        @if (tab() === 'banque') {
          <div class="tab-content">
            <div class="fields">
              <div class="field"><label>Banque</label><input pInputText [(ngModel)]="edit.banque" /></div>
              <div class="field">
                <label>RIB *</label>
                <input pInputText [(ngModel)]="edit.rib" (ngModelChange)="onRibChange($event)" (blur)="onRibBlur()" />
                @if (ribFormatted()) { <span class="rib-formated">{{ ribFormatted() }}</span> }
                @if (ribError()) { <small class="field-error">{{ ribError() }}</small> }
                @else if (ribValid() === true) {
                  <span class="rib-ok"><i class="pi pi-check-circle"></i> Clé RIB correcte</span>
                  @if (ibanDisplay()) {
                    <span class="iban">IBAN : {{ ibanDisplay() }}</span>
                    <p-button label="Copier l'IBAN" icon="pi pi-copy" size="small" [text]="true" (onClick)="copyIban()" />
                  }
                }
              </div>
            </div>
            <p-button label="Enregistrer" icon="pi pi-check" severity="success" [loading]="saving()" (onClick)="saveProfile()" />
          </div>
        }

        <!-- Logo tab -->
        @if (tab() === 'logo') {
          <div class="tab-content">
            <div class="logo-tab">
              <div class="logo-sizes">
                @if (profile()!.hasLogo) {
                  <div class="logo-size"><img [src]="logoUrl" alt="Logo 64px" style="max-height:64px" /></div>
                  <div class="logo-size"><img [src]="logoUrl" alt="Logo 32px" style="max-height:32px" /></div>
                  <p-button label="Retirer le logo" icon="pi pi-trash" severity="danger" [outlined]="true" (onClick)="confirmDeleteLogo()" />
                } @else {
                  <p>Logo absent.</p>
                }
              </div>
              <div class="upload-zone">
                <p>PNG ou JPEG, 1 Mo maximum. Fond transparent conseillé.</p>
                <input type="file" accept="image/png,image/jpeg" (change)="onLogoPick($event)" style="display:none" #fileInput />
                <p-button label="Choisir un fichier" icon="pi pi-upload" (onClick)="fileInput.click()" />
              </div>
            </div>
          </div>
        }

        <!-- Groupements tab -->
        @if (tab() === 'groupements') {
          <div class="tab-content">
            <div class="grp-header">
              <h3>Groupements</h3>
              <p-button label="+ Nouveau groupement" icon="pi pi-plus" severity="success" size="small" (onClick)="panel.openNew()" />
            </div>
            <label class="toggle"><input type="checkbox" [(ngModel)]="showArchived" (ngModelChange)="loadGroupements()" /> Afficher les archivés</label>

            @if (groupements().length === 0) {
              <p class="empty">Aucun groupement.</p>
            } @else {
              <div class="grp-cards">
                @for (g of groupements(); track g.id) {
                  <div class="grp-card" [class.archived]="g.archived">
                    <div class="grp-card-head">
                      <span class="grp-name">{{ g.nom }}</span>
                      <span class="grp-type-badge" [class.conjoint]="g.type === 'CONJOINT'">{{ g.type === 'CONJOINT' ? 'Conjoint' : 'Solidaire' }}</span>
                    </div>
                    @if (g.referenceMarche) { <div class="grp-ref">Réf : {{ g.referenceMarche }}</div> }
                    <div class="grp-bar">
                      @for (m of g.membres; track m.id ?? m.raisonSociale) {
                        <div class="bar-segment" [style.width.%]="m.partPct" [style.background]="memberColor($index, g.membres.length)">
                          <span class="bar-label">{{ m.partPct }}%</span>
                        </div>
                      }
                    </div>
                    <div class="bar-legend">
                      @for (m of g.membres; track m.id ?? m.raisonSociale) {
                        <div class="legend-item">
                          <span class="legend-dot" [style.background]="memberColor($index, g.membres.length)"></span>
                          <span class="legend-name">{{ m.raisonSociale }}{{ m.isSelf ? ' (nous)' : '' }} — {{ m.partPct }}%</span>
                          @if (m.role === 'MANDATAIRE') { <span class="mandataire-badge">Mandataire</span> }
                        </div>
                      }
                    </div>
                    <div class="grp-actions">
                      @if (!g.archived) {
                        <p-button label="Modifier" icon="pi pi-pencil" [text]="true" size="small" (onClick)="editGroupement(g)" />
                        <p-button label="Archiver" icon="pi pi-archive" [text]="true" size="small" severity="warn" (onClick)="confirmArchive(g)" />
                      }
                    </div>
                  </div>
                }
              </div>
            }
          </div>
        }
      }
    </div>

    <app-groupement-panel #panel [profile]="profile()" (saved)="loadGroupements()" />
    <p-confirmdialog />
  `,
  styles: [`
    .page { display: flex; flex-direction: column; gap: 16px; }
    .head { display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 12px; }
    .head h1 { margin: 0; font-size: 24px; font-weight: 800; color: #0f172a; display: flex; align-items: center; gap: 10px; }
    .title-icon { color: #0f766e; }
    .sub { margin: 6px 0 0; color: #64748b; font-size: 14px; }
    .completeness-chip { padding: 8px 16px; border-radius: 999px; font-size: 13px; font-weight: 600; display: flex; align-items: center; gap: 6px; background: #fff7ed; color: #ea580c; border: none; cursor: pointer; }
    .completeness-chip.complete { background: #f0fdf4; color: #16a34a; }
    .state { padding: 60px; text-align: center; color: #64748b; }

    .banner { padding: 10px 12px; border-radius: 8px; display: flex; align-items: center; gap: 8px; font-size: 13px; }
    .banner-warn { background: #fffbeb; color: #b45309; }

    .preview-card { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 20px;
      display: grid; grid-template-columns: auto 1fr auto; gap: 16px; max-width: 820px; }
    .preview-left { display: flex; align-items: flex-start; }
    .preview-logo { max-height: 64px; border-radius: 4px; }
    .logo-placeholder { width: 120px; height: 64px; border: 2px dashed #cbd5e1; border-radius: 8px;
      display: flex; align-items: center; justify-content: center; color: #94a3b8; font-size: 13px; }
    .preview-center { display: flex; flex-direction: column; gap: 4px; }
    .preview-raison { font-size: 18px; font-weight: 700; color: #0f172a; }
    .preview-forme { font-size: 13px; color: #64748b; }
    .preview-forme .capital-line { font-style: normal; }
    .preview-adresse, .preview-ville { font-size: 13px; color: #475569; }
    .preview-right { text-align: right; font-size: 12px; color: #64748b; display: flex; flex-direction: column; gap: 2px; }
    .preview-rib { grid-column: 1 / -1; font-size: 12px; color: #94a3b8; border-top: 1px solid #f1f5f9; padding-top: 8px; }
    .missing { color: #c2410c; }

    .tabs { display: flex; gap: 2px; border-bottom: 1px solid #e2e8f0; overflow-x: auto; }
    .tab { padding: 10px 16px; border: none; background: none; cursor: pointer; font-size: 14px; font-weight: 600;
      color: #64748b; white-space: nowrap; border-bottom: 2px solid transparent; }
    .tab.active { color: #0f766e; border-bottom-color: #0f766e; }

    .tab-content { background: #fff; border: 1px solid #e2e8f0; border-radius: 0 0 12px 12px; padding: 20px; }
    .fields { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
    @media (max-width: 640px) { .fields { grid-template-columns: 1fr; } }
    .field { display: flex; flex-direction: column; gap: 4px; }
    .field label { font-size: 13px; font-weight: 600; color: #475569; }
    .field input { font-size: 15px; }
    :host ::ng-deep .w100 { width: 100%; }
    .hint { font-size: 12px; color: #94a3b8; }
    .toggle { display: flex; align-items: center; gap: 8px; font-size: 14px; }
    .field-error { color: #dc2626; font-size: 12px; margin-top: 2px; display: block; }

    .rib-formated { display: block; font-family: monospace; font-size: 13px; color: #475569; margin-top: 4px; }
    .rib-ok { display: inline-flex; align-items: center; gap: 4px; color: #16a34a; font-size: 13px; margin-top: 4px; }
    .iban { display: block; color: #2563eb; font-size: 13px; margin-top: 4px; }

    .logo-tab { display: flex; flex-direction: column; gap: 16px; }
    .logo-sizes { display: flex; gap: 16px; align-items: center; }
    .logo-size { background: #f8fafc; padding: 8px; border-radius: 8px; border: 1px solid #e2e8f0; }
    .upload-zone { padding: 16px; border: 2px dashed #cbd5e1; border-radius: 8px; text-align: center; }
    .upload-zone p { color: #64748b; font-size: 13px; margin: 0 0 8px; }

    .grp-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
    .grp-header h3 { margin: 0; font-size: 16px; }
    .grp-cards { display: flex; flex-direction: column; gap: 12px; }
    .grp-card { background: #f8fafc; border: 1px solid #e2e8f0; border-radius: 10px; padding: 14px; }
    .grp-card.archived { opacity: 0.6; }
    .grp-card-head { display: flex; justify-content: space-between; align-items: center; }
    .grp-name { font-weight: 700; font-size: 15px; }
    .grp-type-badge { padding: 2px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; background: #eff6ff; color: #2563eb; }
    .grp-type-badge.conjoint { background: #f0fdf4; color: #16a34a; }
    .grp-ref { font-size: 13px; color: #64748b; margin: 4px 0; }
    .grp-bar { display: flex; height: 20px; border-radius: 4px; overflow: hidden; margin: 8px 0 4px; }
    .bar-segment { display: flex; align-items: center; justify-content: center; font-size: 11px; color: #fff; font-weight: 700; min-width: 30px; }
    .bar-legend { display: flex; flex-direction: column; gap: 4px; }
    .legend-item { display: flex; align-items: center; gap: 6px; font-size: 13px; }
    .legend-dot { width: 10px; height: 10px; border-radius: 2px; }
    .legend-name { color: #475569; }
    .mandataire-badge { background: #fef3c7; color: #b45309; font-size: 11px; padding: 1px 8px; border-radius: 999px; }
    .grp-actions { display: flex; gap: 4px; margin-top: 8px; }
    .empty { color: #94a3b8; }
  `]
})
export class EntrepriseComponent implements OnInit {
  private company = inject(CompanyService);
  private msgs = inject(MessageService);
  private confirm = inject(ConfirmationService);

  profile = signal<CompanyProfile | null>(null);
  loading = signal(true);
  loadError = signal(false);
  saving = signal(false);
  staleProfile = signal(false);
  tab = signal('identite');

  logoUrl = '/api/company/logo';

  // Edit model — kept separate so the preview can read it live while typing
  edit: any = {};

  // Field errors from server (400 VALIDATION)
  fieldErrors = signal<Record<string, string>>({});

  // MF live validation
  mfError = signal<string | null>(null);

  // RIB live validation
  ribValid = signal<boolean | null>(null);
  ribError = signal<string | null>(null);
  ribFormatted = signal<string | null>(null);
  ibanDisplay = signal<string | null>(null);

  // Groupements
  groupements = signal<Groupement[]>([]);
  showArchived = false;

  paysOptions = [
    { label: 'Tunisie', value: 'TN' },
    { label: 'Algérie', value: 'DZ' },
    { label: 'Maroc', value: 'MA' },
    { label: 'Libye', value: 'LY' },
    { label: 'Sénégal', value: 'SN' },
    { label: "Côte d'Ivoire", value: 'CI' },
    { label: 'Mauritanie', value: 'MR' },
    { label: 'Autre', value: 'XX' },
  ];

  formeOptions = [
    { label: 'SARL', value: 'SARL' },
    { label: 'SUARL', value: 'SUARL' },
    { label: 'SA', value: 'SA' },
    { label: 'SNC', value: 'SNC' },
    { label: 'SCS', value: 'SCS' },
    { label: 'Entreprise individuelle', value: 'EI' },
    { label: 'Autre', value: 'AUTRE' },
  ];

  gouvernoratOptions = [
    'Ariana', 'Béja', 'Ben Arous', 'Bizerte', 'Gabès', 'Gafsa', 'Jendouba',
    'Kairouan', 'Kasserine', 'Kébili', 'Le Kef', 'Mahdia', 'La Manouba',
    'Médenine', 'Monastir', 'Nabeul', 'Sfax', 'Sidi Bouzid', 'Siliana',
    'Sousse', 'Tataouine', 'Tozeur', 'Tunis', 'Zaghouan',
  ].map(g => ({ label: g, value: g }));

  missingCount = computed(() => this.profile()?.manquants?.length ?? 0);

  // Child panel reference (template #panel)
  @ViewChild('panel') panel!: GroupementPanelComponent;

  ngOnInit(): void {
    this.load();
    this.loadGroupements();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.staleProfile.set(false);
    this.company.getProfile().subscribe({
      next: (p) => {
        this.profile.set(p);
        this.edit = {
          pays: p.pays,
          raisonSociale: p.raisonSociale ?? '',
          nomCommercial: p.nomCommercial ?? '',
          formeJuridique: p.formeJuridique ?? null,
          matriculeFiscal: p.matriculeFiscal ?? '',
          rne: p.rne ?? '',
          capital: p.capital ?? '',
          assujettiTva: p.assujettiTva,
          adresse: p.adresse ?? '',
          codePostal: p.codePostal ?? '',
          ville: p.ville ?? '',
          gouvernorat: p.gouvernorat ?? '',
          telephone: p.telephone ?? '',
          email: p.email ?? '',
          siteWeb: p.siteWeb ?? '',
          banque: p.banque ?? '',
          rib: p.rib ?? '',
          version: p.version,
        };
        // Run RIB check once after load so an existing RIB shows its status
        this.onRibChange(this.edit.rib);
        this.loading.set(false);
      },
      error: () => { this.loadError.set(true); this.loading.set(false); },
    });
  }

  loadGroupements(): void {
    this.company.listGroupements(this.showArchived).subscribe({
      next: (g) => this.groupements.set(g ?? []),
      error: () => this.groupements.set([]),
    });
  }

  goToFirstMissing(): void {
    const p = this.profile();
    if (!p || p.complet) return;
    const first = p.manquants[0];
    if (first?.includes('raison') || first?.includes('matricule')) this.tab.set('identite');
    else if (first?.includes('adresse') || first?.includes('ville') || first?.includes('téléphone')) this.tab.set('adresse');
    else if (first?.includes('RIB')) this.tab.set('banque');
  }

  clearFieldError(field: string): void {
    if (this.fieldErrors()[field]) {
      const next = { ...this.fieldErrors() };
      delete next[field];
      this.fieldErrors.set(next);
    }
  }

  // ── MF ────────────────────────────────────────────────────────────────

  onMfChange(value: string): void {
    if (this.edit.pays !== 'TN') { this.mfError.set(null); return; }
    if (!value || !value.trim()) { this.mfError.set(null); return; }
    this.mfError.set(validateMf(value));
  }

  onMfBlur(): void {
    if (this.edit.pays !== 'TN') return;
    const err = validateMf(this.edit.matriculeFiscal);
    if (err) return;
    // Reformat with slashes when valid
    const normalized = normalizeMf(this.edit.matriculeFiscal);
    if (normalized) {
      const formatted = formatMf(normalized);
      if (formatted) this.edit.matriculeFiscal = formatted;
    }
  }

  // ── RIB ───────────────────────────────────────────────────────────────

  onRibChange(value: string): void {
    const v = normalizeRib(value);
    if (v.length === 20) {
      const err = validateRib(v);
      if (err) {
        this.ribValid.set(false);
        this.ribError.set(err);
        this.ribFormatted.set(null);
        this.ibanDisplay.set(null);
      } else {
        this.ribValid.set(true);
        this.ribError.set(null);
        this.ribFormatted.set(formatRib(v));
        const iban = ibanFromRib(v);
        this.ibanDisplay.set(formatIban(iban));
      }
    } else if (v.length > 0) {
      this.ribValid.set(false);
      this.ribError.set(null); // don't show "incorrect" while typing; only when 20 digits
      this.ribFormatted.set(null);
      this.ibanDisplay.set(null);
    } else {
      this.ribValid.set(null);
      this.ribError.set(null);
      this.ribFormatted.set(null);
      this.ibanDisplay.set(null);
    }
  }

  onRibBlur(): void {
    // Re-check on blur; show the error if invalid
    const v = normalizeRib(this.edit.rib);
    if (v.length === 20) {
      const err = validateRib(v);
      if (err) {
        this.ribValid.set(false);
        this.ribError.set(err);
      }
    }
  }

  // ── Live preview computed values ──────────────────────────────────────

  mfFormattedPreview = computed(() => {
    // Compute from edit.matriculeFiscal (live), not the saved profile
    const raw = this.edit?.matriculeFiscal;
    if (!raw) return null;
    if (this.edit?.pays !== 'TN') return null;
    const err = validateMf(raw);
    if (err) return null;
    return formatMf(normalizeMf(raw));
  });

  ribFormattedPreview = computed(() => {
    const raw = this.edit?.rib;
    if (!raw) return null;
    const v = normalizeRib(raw);
    if (v.length !== 20) return null;
    return formatRib(v);
  });

  formatCapital(cap: string): string {
    // Format with fr-TN: 3 decimals, comma as decimal separator, space as thousands
    try {
      const n = Number(String(cap).replace(',', '.'));
      if (isNaN(n)) return String(cap);
      return new Intl.NumberFormat('fr-TN', {
        minimumFractionDigits: 3,
        maximumFractionDigits: 3
      }).format(n);
    } catch {
      return String(cap);
    }
  }

  copyIban(): void {
    const v = normalizeRib(this.edit.rib);
    if (v.length !== 20) return;
    if (validateRib(v)) return;
    const iban = ibanFromRib(v); // "TN59..."
    navigator.clipboard.writeText(iban).then(() => {
      this.msgs.add({ severity: 'success', summary: 'IBAN copié' });
    }).catch(() => {
      this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Copie impossible.' });
    });
  }

  // ── Profile save ──────────────────────────────────────────────────────

  saveProfile(): void {
    this.saving.set(true);
    this.staleProfile.set(false);
    const body: any = { ...this.edit };
    // Clean empty strings → undefined
    for (const key of Object.keys(body)) {
      if (body[key] === '') body[key] = undefined;
    }
    this.company.updateProfile(body).subscribe({
      next: (p) => {
        this.profile.set(p);
        this.edit.version = p.version;
        this.saving.set(false);
        this.fieldErrors.set({});
        this.msgs.add({ severity: 'success', summary: 'Profil enregistré' });
      },
      error: (err) => {
        this.saving.set(false);
        if (err?.status === 400 && err?.error?.code === 'VALIDATION') {
          const fields = err.error.fields ?? {};
          this.fieldErrors.set(fields);
          // Switch to the tab of the first field in error
          const first = Object.keys(fields)[0];
          if (first) this.switchToTabOf(first);
          const firstErr = Object.values(fields)[0] as string;
          this.msgs.add({ severity: 'error', summary: 'Erreur de validation', detail: firstErr });
        } else if (err?.status === 409) {
          this.staleProfile.set(true);
          this.msgs.add({ severity: 'warn', summary: 'Conflit',
            detail: err?.error?.message ?? "Le profil a été modifié par quelqu'un d'autre." });
        } else {
          this.msgs.add({ severity: 'error', summary: 'Erreur',
            detail: err?.error?.message ?? 'Enregistrement impossible.' });
        }
      },
    });
  }

  private switchToTabOf(field: string): void {
    const idFields = ['raisonSociale', 'nomCommercial', 'formeJuridique', 'matriculeFiscal', 'rne', 'capital'];
    const addrFields = ['adresse', 'codePostal', 'ville', 'gouvernorat', 'telephone', 'email', 'siteWeb'];
    const banqueFields = ['banque', 'rib'];
    if (idFields.includes(field)) this.tab.set('identite');
    else if (addrFields.includes(field)) this.tab.set('adresse');
    else if (banqueFields.includes(field)) this.tab.set('banque');
  }

  // ── Logo ──────────────────────────────────────────────────────────────

  onLogoPick(event: Event): void {
    const input = event.target as HTMLInputElement;
    if (!input.files || input.files.length === 0) return;
    const file = input.files[0];
    if (file.size > 1_048_576) {
      this.msgs.add({ severity: 'error', summary: 'Logo trop volumineux', detail: 'Le logo dépasse 1 Mo.' });
      return;
    }
    this.company.uploadLogo(file).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Logo enregistré' });
        this.load();
        this.logoUrl = '/api/company/logo?t=' + Date.now();
      },
      error: (err) => {
        const code = err?.error?.code;
        if (code === 'LOGO_TOO_LARGE') {
          this.msgs.add({ severity: 'error', summary: 'Logo trop volumineux', detail: 'Le logo dépasse 1 Mo.' });
        } else if (code === 'LOGO_TYPE') {
          this.msgs.add({ severity: 'error', summary: 'Format refusé', detail: 'Format accepté : PNG ou JPEG.' });
        } else {
          this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err?.error?.message ?? 'Upload impossible.' });
        }
      },
    });
  }

  confirmDeleteLogo(): void {
    this.confirm.confirm({
      header: 'Retirer le logo',
      message: 'Voulez-vous vraiment retirer le logo ?',
      acceptLabel: 'Oui, retirer',
      rejectLabel: 'Annuler',
      accept: () => this.deleteLogo(),
    });
  }

  deleteLogo(): void {
    this.company.deleteLogo().subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Logo retiré' });
        this.load();
      },
      error: () => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Suppression impossible.' }),
    });
  }

  // ── Groupements ───────────────────────────────────────────────────────

  editGroupement(g: Groupement): void {
    this.panel.openEdit(g);
  }

  confirmArchive(g: Groupement): void {
    this.confirm.confirm({
      header: 'Archiver le groupement',
      message: `Voulez-vous vraiment archiver « ${g.nom} » ?`,
      acceptLabel: 'Oui, archiver',
      rejectLabel: 'Annuler',
      accept: () => {
        this.company.archiveGroupement(g.id).subscribe({
          next: () => {
            this.msgs.add({ severity: 'warn', summary: 'Groupement archivé' });
            this.loadGroupements();
          },
          error: () => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Action impossible.' }),
        });
      },
    });
  }

  // ── Groupement card share bar colors ──────────────────────────────────
  // Color is never the only signal: each segment also has a percentage label
  // inside, and the legend below shows "Name — N%" + a Mandataire badge.
  private static readonly CARD_COLORS = ['#0f766e', '#2563eb', '#9333ea', '#ea580c', '#0891b2', '#ca8a04'];
  memberColor(index: number, _total: number): string {
    return EntrepriseComponent.CARD_COLORS[index % EntrepriseComponent.CARD_COLORS.length];
  }
}
