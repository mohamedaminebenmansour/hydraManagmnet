import { Component, OnInit, inject, signal } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { TableModule } from 'primeng/table';
import { ButtonModule } from 'primeng/button';
import { TooltipModule } from 'primeng/tooltip';
import { MessageService } from 'primeng/api';
import { roleVisual, type RoleVisual } from '../../shared/hydra-design';
import { EmptyStateComponent } from '../../shared/empty-state.component';
import { LoadErrorComponent } from '../../shared/load-error.component';

interface RoleRow {
  id: string;
  code: string;
  label: string;
  isSystem: boolean;
  permissions: string[];
  userCount: number;
  moduleKeys: string[];
}

interface ModuleIcon { icon: string; label: string; }

const MODULE_ICONS: Record<string, ModuleIcon> = {
  tickets:   { icon: 'pi-exclamation-triangle', label: 'Signalements' },
  projects:  { icon: 'pi-briefcase',             label: 'Chantiers' },
  documents: { icon: 'pi-file',                  label: 'Documents' },
  cash:      { icon: 'pi-wallet',                label: 'Trésorerie' },
  stock:     { icon: 'pi-box',                   label: 'Stock et achats' },
  process:   { icon: 'pi-sitemap',               label: 'Processus' },
  admin:     { icon: 'pi-shield',                label: 'Administration' },
};

@Component({
  selector: 'app-roles',
  standalone: true,
  imports: [
    TableModule,
    ButtonModule,
    RouterLink,
    TooltipModule,
    EmptyStateComponent,
    LoadErrorComponent,
  ],
  template: `
    <div class="page">
      <header class="head">
        <div class="head-text">
          <h1>Rôles</h1>
          <p class="sub">Gérez les rôles et leurs permissions</p>
        </div>
        <div class="head-actions">
          <a routerLink="/roles/matrice" class="ghost-btn" pTooltip="Vue croisée permissions × rôles">
            <i class="pi pi-th-large"></i> Vue matrice
          </a>
          <button class="primary-btn" disabled pTooltip="Création de rôle non disponible ici — ouvrez un rôle existant et dupliquez-le.">
            <i class="pi pi-plus"></i> Nouveau rôle
          </button>
        </div>
      </header>

      @if (loading()) {
        <div class="state"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else if (loadError()) {
        <app-load-error message="Impossible de charger les rôles." (retry)="load()" />
      } @else if (roles().length === 0) {
        <app-empty-state icon="pi pi-id-card" message="Aucun rôle." />
      } @else {
        <p-table [value]="roles()" styleClass="roles-table" [tableStyle]="{ 'min-width': '860px' }">
          <ng-template #header>
            <tr>
              <th>Rôle</th>
              <th>Type</th>
              <th class="num">Personnes</th>
              <th class="num">Permissions</th>
              <th>Modules</th>
              <th class="actions-col">Actions</th>
            </tr>
          </ng-template>
          <ng-template #body let-r>
            <tr>
              <td>
                <div class="role-cell">
                  <span class="role-dot" [style.background]="visual(r.code).color"></span>
                  <i class="pi {{ visual(r.code).icon }}" [style.color]="visual(r.code).color"></i>
                  <span class="role-label">{{ r.label }}</span>
                </div>
              </td>
              <td>
                @if (r.isSystem) {
                  <span class="type-tag system"><i class="pi pi-lock"></i> Système</span>
                } @else {
                  <span class="type-tag custom">Personnalisé</span>
                }
              </td>
              <td class="num">
                <a [routerLink]="['/users']" [queryParams]="{ role: r.code }" class="count-link"
                   [class.zero]="r.userCount === 0">
                  {{ r.userCount }}
                </a>
              </td>
              <td class="num">{{ r.permissions.length }}</td>
              <td>
                <div class="modules">
                  @for (m of r.moduleKeys; track m) {
                    @if (moduleIcon(m)) {
                      <i class="pi {{ moduleIcon(m)!.icon }}"
                         [pTooltip]="moduleIcon(m)!.label"
                         [style.color]="'#475569'"></i>
                    }
                  }
                  @if (!r.moduleKeys || r.moduleKeys.length === 0) {
                    <span class="no-mod">—</span>
                  }
                </div>
              </td>
              <td>
                <div class="actions">
                  <a [routerLink]="['/roles', r.id]" class="act-btn">
                    <i class="pi pi-folder-open"></i> Ouvrir
                  </a>
                  <button class="act-btn ghost" (click)="duplicate(r)"
                          [disabled]="duplicatingId() === r.id"
                          aria-label="Dupliquer le rôle">
                    @if (duplicatingId() === r.id) {
                      <i class="pi pi-spin pi-spinner"></i>
                    } @else {
                      <i class="pi pi-copy"></i>
                    }
                    Dupliquer
                  </button>
                </div>
              </td>
            </tr>
          </ng-template>
        </p-table>
      }
    </div>
  `,
  styles: [`
    .page { display: flex; flex-direction: column; gap: 18px; }
    .head { display: flex; justify-content: space-between; align-items: flex-end;
      gap: 16px; flex-wrap: wrap; }
    .head-text h1 { margin: 0; font-size: 24px; font-weight: 700; color: #0f172a; }
    .sub { margin: 4px 0 0; color: #64748b; font-size: 14px; }
    .head-actions { display: flex; gap: 8px; flex-wrap: wrap; }

    .ghost-btn, .primary-btn, .act-btn {
      display: inline-flex; align-items: center; gap: 6px;
      padding: 8px 14px; border-radius: 8px; font-size: 14px; font-weight: 600;
      cursor: pointer; text-decoration: none; border: 1px solid transparent;
      font-family: inherit;
    }
    .ghost-btn { background: #fff; color: #334155; border-color: #cbd5e1; }
    .ghost-btn:hover { background: #f8fafc; }
    .primary-btn { background: #0f172a; color: #fff; }
    .primary-btn:disabled { opacity: .5; cursor: not-allowed; }

    .state { padding: 40px; text-align: center; color: #64748b; font-size: 15px; }
    .state i { margin-right: 6px; }

    :host ::ng-deep .roles-table {
      .p-datatable-table { border-collapse: separate; border-spacing: 0; }
      thead th {
        background: #f8fafc; color: #475569; font-size: 12px; font-weight: 700;
        text-transform: uppercase; letter-spacing: .04em;
        text-align: left; padding: 10px 14px;
        border-bottom: 1px solid #e2e8f0;
      }
      thead th.num { text-align: right; }
      thead th.actions-col { text-align: right; }
      tbody td {
        padding: 12px 14px; border-bottom: 1px solid #f1f5f9;
        font-size: 14px; color: #1e293b; vertical-align: middle;
      }
      tbody td.num { text-align: right; font-variant-numeric: tabular-nums; }
      tbody tr:hover td { background: #fafbfc; }
    }

    .role-cell { display: inline-flex; align-items: center; gap: 10px; }
    .role-dot { width: 10px; height: 10px; border-radius: 999px; flex: none; }
    .role-cell .pi { font-size: 14px; }
    .role-label { font-weight: 600; color: #0f172a; }

    .type-tag {
      display: inline-flex; align-items: center; gap: 4px;
      padding: 3px 10px; border-radius: 999px; font-size: 12px; font-weight: 600;
    }
    .type-tag.system { background: #f1f5f9; color: #475569; }
    .type-tag.custom { background: #ecfeff; color: #0891b2; }

    .count-link { color: #0ea5e9; font-weight: 700; text-decoration: none; }
    .count-link:hover { text-decoration: underline; }
    .count-link.zero { color: #94a3b8; font-weight: 600; }

    .modules { display: inline-flex; gap: 10px; align-items: center; }
    .modules .pi { font-size: 14px; }
    .no-mod { color: #cbd5e1; }

    .actions { display: inline-flex; gap: 8px; justify-content: flex-end; }
    .act-btn {
      display: inline-flex; align-items: center; gap: 6px;
      padding: 6px 12px; border-radius: 8px; font-size: 13px; font-weight: 600;
      cursor: pointer; text-decoration: none; border: 1px solid transparent;
      background: #0f172a; color: #fff;
    }
    .act-btn:hover { background: #1e293b; }
    .act-btn.ghost { background: #fff; color: #334155; border-color: #cbd5e1; }
    .act-btn.ghost:hover { background: #f8fafc; }
    .act-btn.ghost:disabled { opacity: .6; cursor: wait; }
    .act-btn .pi { font-size: 12px; }
  `],
})
export class RolesComponent implements OnInit {
  private http = inject(HttpClient);
  private router = inject(Router);
  private msgs = inject(MessageService);

  readonly roles = signal<RoleRow[]>([]);
  readonly loading = signal<boolean>(true);
  readonly loadError = signal<boolean>(false);
  readonly duplicatingId = signal<string | null>(null);

  readonly moduleIcons = MODULE_ICONS;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.http.get<RoleRow[]>('/api/roles').subscribe({
      next: r => { this.roles.set(r ?? []); this.loading.set(false); },
      error: () => { this.loadError.set(true); this.loading.set(false); },
    });
  }

  visual(code: string): RoleVisual { return roleVisual(code); }

  moduleIcon(key: string): ModuleIcon | null {
    return MODULE_ICONS[key] ?? null;
  }

  duplicate(r: RoleRow): void {
    const suggestion = r.label + ' (copie)';
    const label = window.prompt("Libellé du nouveau rôle :", suggestion);
    if (label === null) { return; }
    const trimmed = label.trim();
    if (!trimmed) {
      this.msgs.add({ severity: 'warn', summary: 'Libellé requis' });
      return;
    }
    this.duplicatingId.set(r.id);
    this.http.post<RoleRow>(`/api/roles/${r.id}/duplicate`, { label: trimmed }).subscribe({
      next: created => {
        this.duplicatingId.set(null);
        this.msgs.add({ severity: 'success', summary: 'Rôle dupliqué' });
        this.router.navigate(['/roles', created.id]);
      },
      error: err => {
        this.duplicatingId.set(null);
        this.msgs.add({
          severity: 'error', summary: 'Erreur',
          detail: err?.error?.message ?? 'Duplication impossible',
        });
      },
    });
  }
}
