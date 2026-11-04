import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TooltipModule } from 'primeng/tooltip';
import { MessageService } from 'primeng/api';
import { CompanyService, CompanyProfile, Groupement, GroupementMember } from '@app/core/services/company.service';
import { EmptyStateComponent } from '@app/shared/empty-state.component';
import { LoadErrorComponent } from '@app/shared/load-error.component';

@Component({
  selector: 'app-entreprise',
  standalone: true,
  imports: [FormsModule, ButtonModule, InputTextModule, SelectModule, TooltipModule, EmptyStateComponent, LoadErrorComponent],
  template: `
    <div class="page">
      <header class="head">
        <div>
          <h1><i class="pi pi-building title-icon"></i> Entreprise</h1>
          <p class="sub">Les informations imprimées sur vos bons de commande, décomptes et factures.</p>
        </div>
        @if (profile()) {
          <span class="completeness-chip" [class.complete]="profile()!.complet">
            @if (profile()!.complet) {
              <i class="pi pi-check-circle"></i> Profil complet
            } @else {
              <i class="pi pi-exclamation-triangle"></i> {{ profile()!.manquants.length }} info(s) manquante(s)
            }
          </span>
        }
      </header>

      @if (loading()) {
        <div class="state"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      }
      @if (!loading() && loadError()) {
        <app-load-error message="Impossible de charger le profil." (retry)="load()" />
      }

      @if (profile()) {
        <!-- Header preview -->
        <div class="preview-card">
          <div class="preview-left">
            @if (profile()!.hasLogo) {
              <img [src]="logoUrl" alt="Logo" class="preview-logo" />
            } @else {
              <div class="logo-placeholder">Votre logo</div>
            }
          </div>
          <div class="preview-center">
            <div class="preview-raison">{{ profile()!.raisonSociale || '—' }}</div>
            @if (profile()!.formeJuridique) {
              <div class="preview-forme">{{ profile()!.formeJuridique }}
                @if (profile()!.capital) { « au capital de {{ profile()!.capital }} TND » }
              </div>
            }
            <div class="preview-adresse">{{ profile()!.adresse || '—' }}</div>
            <div class="preview-ville">{{ profile()!.codePostal }} {{ profile()!.ville || '—' }}</div>
          </div>
          <div class="preview-right">
            @if (profile()!.mfFormate) { <div class="preview-mf">MF : {{ profile()!.mfFormate }}</div> }
            @if (profile()!.rne) { <div class="preview-rne">RNE : {{ profile()!.rne }}</div> }
            @if (profile()!.telephone) { <div class="preview-tel">{{ profile()!.telephone }}</div> }
            @if (profile()!.email) { <div class="preview-email">{{ profile()!.email }}</div> }
          </div>
          <div class="preview-rib">
            @if (profile()!.ribFormate) {
              RIB : {{ profile()!.ribFormate }} @if (profile()!.banque) { — {{ profile()!.banque }} }
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
                <input pInputText [(ngModel)]="edit.raisonSociale" />
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
                <input pInputText [(ngModel)]="edit.matriculeFiscal" placeholder="1234567/A/B/M/000" />
                @if (edit.pays === 'TN') { <span class="hint">Format : 1234567/A/B/M/000</span> }
              </div>
              <div class="field">
                <label>RNE</label>
                <input pInputText [(ngModel)]="edit.rne" />
              </div>
              <div class="field">
                <label>Capital (TND)</label>
                <input pInputText type="text" [(ngModel)]="edit.capital" />
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
              <div class="field"><label>Adresse</label><input pInputText [(ngModel)]="edit.adresse" /></div>
              <div class="field"><label>Code postal</label><input pInputText [(ngModel)]="edit.codePostal" /></div>
              <div class="field"><label>Ville</label><input pInputText [(ngModel)]="edit.ville" /></div>
              <div class="field">
                <label>Gouvernorat</label>
                @if (edit.pays === 'TN') {
                  <p-select [options]="gouvernoratOptions" [(ngModel)]="edit.gouvernorat" styleClass="w100" [showClear]="true" />
                } @else {
                  <input pInputText [(ngModel)]="edit.gouvernorat" />
                }
              </div>
              <div class="field"><label>Téléphone</label><input pInputText type="tel" [(ngModel)]="edit.telephone" /></div>
              <div class="field"><label>E-mail</label><input pInputText type="email" [(ngModel)]="edit.email" /></div>
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
                <label>RIB</label>
                <input pInputText [(ngModel)]="edit.rib" (ngModelChange)="onRibChange($event)" />
                @if (ribFormated()) { <span class="rib-formated">{{ ribFormated() }}</span> }
                @if (ribValid() === true) {
                  <span class="rib-ok">✓ Clé RIB correcte</span>
                  @if (ibanDisplay()) { <span class="iban">IBAN : {{ ibanDisplay() }}</span> }
                } @else if (ribValid() === false) {
                  <span class="rib-err">Clé RIB incorrecte : vérifiez le numéro</span>
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
                  <p-button label="Retirer le logo" icon="pi pi-trash" severity="danger" [outlined]="true" (onClick)="deleteLogo()" />
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
              <p-button label="+ Nouveau groupement" icon="pi pi-plus" severity="success" size="small" (onClick)="showGroupementPanel.set(true)" />
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
                    <div class="grp-shares">
                      @for (m of g.membres; track m.id ?? m.raisonSociale) {
                        <div class="share-row">
                          <span class="share-name">{{ m.raisonSociale }}{{ m.isSelf ? ' (nous)' : '' }}</span>
                          <span class="share-pct">{{ m.partPct }}%</span>
                          @if (m.role === 'MANDATAIRE') { <span class="mandataire-badge">Mandataire</span> }
                        </div>
                      }
                    </div>
                    @if (!g.archived) {
                      <p-button label="Archiver" icon="pi pi-archive" [text]="true" size="small" severity="warn" (onClick)="archiveGroupement(g.id)" />
                    }
                  </div>
                }
              </div>
            }
          </div>
        }
      }
    </div>
  `,
  styles: [`
    .page { display: flex; flex-direction: column; gap: 16px; }
    .head { display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 12px; }
    .head h1 { margin: 0; font-size: 24px; font-weight: 800; color: #0f172a; display: flex; align-items: center; gap: 10px; }
    .title-icon { color: #0f766e; }
    .sub { margin: 6px 0 0; color: #64748b; font-size: 14px; }
    .completeness-chip { padding: 8px 16px; border-radius: 999px; font-size: 13px; font-weight: 600; display: flex; align-items: center; gap: 6px; background: #fff7ed; color: #ea580c; }
    .completeness-chip.complete { background: #f0fdf4; color: #16a34a; }
    .state { padding: 60px; text-align: center; color: #64748b; }

    .preview-card { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 20px;
      display: grid; grid-template-columns: auto 1fr auto; gap: 16px; max-width: 820px; }
    .preview-left { display: flex; align-items: flex-start; }
    .preview-logo { max-height: 64px; border-radius: 4px; }
    .logo-placeholder { width: 120px; height: 64px; border: 2px dashed #cbd5e1; border-radius: 8px;
      display: flex; align-items: center; justify-content: center; color: #94a3b8; font-size: 13px; }
    .preview-center { display: flex; flex-direction: column; gap: 4px; }
    .preview-raison { font-size: 18px; font-weight: 700; color: #0f172a; }
    .preview-forme { font-size: 13px; color: #64748b; }
    .preview-adresse, .preview-ville { font-size: 13px; color: #475569; }
    .preview-right { text-align: right; font-size: 12px; color: #64748b; }
    .preview-rib { grid-column: 1 / -1; font-size: 12px; color: #94a3b8; border-top: 1px solid #f1f5f9; padding-top: 8px; }

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

    .rib-formated { display: block; font-family: monospace; font-size: 13px; color: #475569; margin-top: 4px; }
    .rib-ok { display: block; color: #16a34a; font-size: 13px; margin-top: 4px; }
    .rib-err { display: block; color: #dc2626; font-size: 13px; margin-top: 4px; }
    .iban { display: block; color: #2563eb; font-size: 13px; }

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
    .grp-shares { margin: 8px 0; }
    .share-row { display: flex; align-items: center; gap: 8px; padding: 4px 0; font-size: 13px; }
    .share-name { flex: 1; }
    .share-pct { font-weight: 700; }
    .mandataire-badge { background: #fef3c7; color: #b45309; font-size: 11px; padding: 1px 8px; border-radius: 999px; }
    .empty { color: #94a3b8; }
  `]
})
export class EntrepriseComponent implements OnInit {
  private company = inject(CompanyService);
  private msgs = inject(MessageService);

  profile = signal<CompanyProfile | null>(null);
  loading = signal(true);
  loadError = signal(false);
  saving = signal(false);
  tab = signal('identite');

  logoUrl = '/api/company/logo';

  // Edit model
  edit: any = {};

  // RIB live validation
  ribFormated = signal<string | null>(null);
  ribValid = signal<boolean | null>(null);
  ibanDisplay = signal<string | null>(null);

  // Groupements
  groupements = signal<Groupement[]>([]);
  showArchived = false;
  showGroupementPanel = signal(false);

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

  ngOnInit(): void {
    this.load();
    this.loadGroupements();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.company.getProfile().subscribe({
      next: (p) => {
        this.profile.set(p);
        this.edit = {
          pays: p.pays,
          raisonSociale: p.raisonSociale,
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

  saveProfile(): void {
    this.saving.set(true);
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
        this.msgs.add({ severity: 'success', summary: 'Profil enregistré' });
      },
      error: (err) => {
        this.saving.set(false);
        if (err?.status === 400 && err?.error?.code === 'VALIDATION') {
          const fields = err.error.fields;
          const firstErr = Object.values(fields)[0] as string;
          this.msgs.add({ severity: 'error', summary: 'Erreur de validation', detail: firstErr });
        } else if (err?.status === 409) {
          this.msgs.add({ severity: 'warn', summary: 'Conflit', detail: err?.error?.message ?? 'Le profil a été modifié par quelqu\'un d\'autre.' });
        } else {
          this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err?.error?.message ?? 'Enregistrement impossible.' });
        }
      },
    });
  }

  onRibChange(value: string): void {
    const v = (value || '').replace(/[\s.-]/g, '');
    if (v.length === 20 && /^\d{20}$/.test(v)) {
      // Validate with BigInt
      const first18 = BigInt(v.substring(0, 18));
      const key = parseInt(v.substring(18, 20), 10);
      const mod = Number(first18 * 100n % 97n);
      const expected = 97 - mod;
      this.ribValid.set(expected === key);
      if (expected === key) {
        this.ribFormated.set(this.formatRib(v));
        this.ibanDisplay.set(this.formatIban('TN59' + v));
      }
    } else if (v.length > 0) {
      this.ribValid.set(false);
      this.ribFormated.set(null);
      this.ibanDisplay.set(null);
    } else {
      this.ribValid.set(null);
      this.ribFormated.set(null);
      this.ibanDisplay.set(null);
    }
  }

  private formatRib(rib: string): string {
    return rib.substring(0, 2) + ' ' + rib.substring(2, 5) + ' ' + rib.substring(5, 18) + ' ' + rib.substring(18, 20);
  }

  private formatIban(iban: string): string {
    return iban.replace(/(.{4})/g, '$1 ').trim();
  }

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
        // Force logo refresh by adding a cache-buster
        this.logoUrl = '/api/company/logo?t=' + Date.now();
      },
      error: (err) => {
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: err?.error?.message ?? 'Upload impossible.' });
      },
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

  archiveGroupement(id: string): void {
    this.company.archiveGroupement(id).subscribe({
      next: () => {
        this.msgs.add({ severity: 'warn', summary: 'Groupement archivé' });
        this.loadGroupements();
      },
      error: () => this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Action impossible.' }),
    });
  }
}
