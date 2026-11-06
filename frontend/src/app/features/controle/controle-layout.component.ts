import { Component, OnInit, effect, inject, signal } from '@angular/core';
import { Router, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { ButtonModule } from 'primeng/button';
import { AuthService } from '@app/core/auth.service';
import { ControleStore } from './controle.store';
import { LineDrawerComponent } from './line-drawer.component';
import { EmptyStateComponent } from '@app/shared/empty-state.component';
import { LoadErrorComponent } from '@app/shared/load-error.component';
import { dfmt } from './controle-format';

@Component({
  selector: 'app-controle-layout',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive, ButtonModule, EmptyStateComponent, LoadErrorComponent, LineDrawerComponent],
  template: `
    <div class="ctl-page">
      <!-- Header (like the prototype top bar) -->
      <header class="ctl-top">
        <div class="ctl-brand">
          <b>Contrôle de trésorerie</b>
          @if (metaLine()) {
            <small>{{ metaLine() }}</small>
          }
        </div>
        <div class="ctl-seg" role="group" aria-label="Projets affichés">
          <button type="button" [attr.aria-pressed]="store.scope() === 'grp'"
                  (click)="store.setScope('grp')">
            Groupement NGNET · 3 projets
          </button>
          <button type="button" [attr.aria-pressed]="store.scope() === 'all'"
                  (click)="store.setScope('all')">
            Tous les projets
          </button>
        </div>
      </header>

      <!-- Tabs -->
      <nav class="ctl-tabs" aria-label="Pages">
        <a routerLink="" routerLinkActive="active" [routerLinkActiveOptions]="{exact: true}">Vue d'ensemble</a>
        <a routerLink="comptes" routerLinkActive="active">Comptes</a>
        <a routerLink="projets" routerLinkActive="active">Projets</a>
        <a routerLink="sous-traitants" routerLinkActive="active">Sous-traitants</a>
        <a routerLink="ngnet" routerLinkActive="active">Réunion NGNET</a>
        <a routerLink="justificatifs" routerLinkActive="active">Justificatifs</a>
        @if (auth.can('CTL_IMPORT')) {
          <a routerLink="import" routerLinkActive="active">Importer</a>
        }
      </nav>

      <!-- Content -->
      <main class="ctl-main">
        @if (store.loading()) {
          <div class="ctl-loading">Chargement des lignes…</div>
        } @else if (store.error()) {
          <app-load-error message="Impossible de charger les données du contrôle." (retry)="store.reload()" />
        } @else if (store.data()?.imported === null) {
          <app-empty-state icon="pi pi-upload" message="Aucun fichier importé"
                           buttonLabel="Importer le fichier"
                           (buttonAction)="goImport()">
            <p class="ctl-empty-text">Importez le fichier produit par convert.py pour voir les comptes.</p>
          </app-empty-state>
        } @else {
          <router-outlet />
        }
      </main>

      <!-- Line drawer (placed once, opened by any page via ControleDrawerService) -->
      <ctl-line-drawer />
    </div>
  `,
  styles: [`
    :host {
      /* Colors defined ONCE here, used by every child page via var(--ctl-*) */
      --ctl-accent: #0d5c6e;
      --ctl-accent-soft: #e1eff2;
      --ctl-ok: #1d7348; --ctl-ok-soft: #e3f3ea;
      --ctl-warn: #9a5b00; --ctl-warn-soft: #fbf0dc;
      --ctl-bad: #b42318; --ctl-bad-soft: #fbe7e5;
      --ctl-vio: #5b3fb5; --ctl-vio-soft: #eeeafb;
      --ctl-line: #dbe1df;
      --ctl-muted: #58656f;
      --ctl-bg: #f4f6f5;
      --ctl-panel: #ffffff;
      --ctl-panel-2: #eef2f1;
      --ctl-ink: #16212b;
      --ctl-radius: 10px;
      --ctl-font-num: "IBM Plex Mono", ui-monospace, Menlo, Consolas, monospace;
    }

    .ctl-page {
      display: flex; flex-direction: column; min-height: 100%;
      background: var(--ctl-bg); color: var(--ctl-ink);
      font-family: "Public Sans", "Segoe UI", Roboto, Arial, sans-serif;
    }

    .ctl-top {
      background: var(--ctl-panel);
      border-bottom: 1px solid var(--ctl-line);
      display: flex; flex-wrap: wrap; gap: 8px 16px;
      align-items: center; padding: 12px 16px;
    }
    .ctl-brand { display: flex; flex-direction: column; margin-right: auto; min-width: 0; }
    .ctl-brand b { font-size: 17px; letter-spacing: -.01em; }
    .ctl-brand small { color: var(--ctl-muted); font-size: 12px; }

    .ctl-seg {
      display: inline-flex; border: 1px solid var(--ctl-line);
      border-radius: 999px; padding: 2px; background: var(--ctl-panel-2);
    }
    .ctl-seg button {
      font: inherit; font-size: 12.5px; border: 0; background: none;
      color: var(--ctl-muted); padding: 5px 12px; border-radius: 999px; cursor: pointer;
    }
    .ctl-seg button[aria-pressed="true"] {
      background: var(--ctl-panel); color: var(--ctl-ink);
      box-shadow: 0 1px 2px rgba(16,33,43,.06), 0 4px 16px rgba(16,33,43,.06);
      font-weight: 600;
    }

    .ctl-tabs {
      display: flex; gap: 2px; overflow-x: auto; scrollbar-width: none;
      background: var(--ctl-panel); border-bottom: 1px solid var(--ctl-line);
      padding: 0 16px;
    }
    .ctl-tabs a {
      color: var(--ctl-muted); text-decoration: none;
      padding: 9px 12px; border-bottom: 2px solid transparent;
      white-space: nowrap; font-weight: 500; font-size: 14px;
    }
    .ctl-tabs a.active { color: var(--ctl-ink); border-bottom-color: var(--ctl-accent); }
    .ctl-tabs::-webkit-scrollbar { display: none; }

    .ctl-main { flex: 1; padding: 20px 16px 48px; display: flex; flex-direction: column; gap: 16px; }
    .ctl-loading { padding: 60px 16px; text-align: center; color: var(--ctl-muted); }
    .ctl-empty-text { color: var(--ctl-muted); font-size: 14px; max-width: 60ch; text-align: center; }

    @media (max-width: 400px) {
      .ctl-top { flex-direction: column; align-items: stretch; }
      .ctl-seg { align-self: flex-start; }
      .ctl-tabs a { padding: 9px 10px; font-size: 13px; }
      .ctl-main { padding: 16px 12px 32px; }
    }
  `]
})
export class ControleLayoutComponent implements OnInit {
  protected store = inject(ControleStore);
  protected auth = inject(AuthService);
  private router = inject(Router);

  metaLine = signal<string | null>(null);

  constructor() {
    // Update the meta line when data arrives or scope changes
    effect(() => {
      const d = this.store.data();
      if (d?.imported) {
        const imp = d.imported;
        const date = imp.importedAt ? new Date(imp.importedAt) : null;
        const dateStr = date ? dfmt(date.toISOString().slice(0, 10)) : '—';
        const lines = (d.meta?.lines ?? imp.lineCount).toLocaleString('fr-FR');
        const stLines = (d.meta?.stLines ?? imp.stLineCount).toLocaleString('fr-FR');
        this.metaLine.set(
          `Fichier « ${imp.sourceName} » · importé le ${dateStr} par ${imp.importerName ?? '—'} · ${lines} lignes de caisse, ${stLines} lignes sous-traitants`);
      } else {
        this.metaLine.set(null);
      }
    }, { allowSignalWrites: true });
  }

  ngOnInit(): void {
    this.store.load();
  }

  goImport(): void {
    this.router.navigate(['/controle/import']);
  }
}
