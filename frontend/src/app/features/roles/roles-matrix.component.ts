import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { TooltipModule } from 'primeng/tooltip';
import { PERM_GROUPS, PERM_SENTENCES } from '@app/core/permissions';
import { roleVisual, type RoleVisual } from '@app/shared/hydra-design';
import { EmptyStateComponent } from '@app/shared/empty-state.component';
import { LoadErrorComponent } from '@app/shared/load-error.component';

interface RoleRow {
  id: string;
  code: string;
  label: string;
  isSystem: boolean;
  permissions: string[];
  userCount?: number;
  moduleKeys?: string[];
}

interface PermGroup {
  key: string;
  icon: string;
  title: string;
  ids: string[];
}

interface MatrixRow {
  type: 'header' | 'perm';
  group?: PermGroup;
  code?: string;
}

interface SelectOption { label: string; value: string; }

@Component({
  selector: 'app-roles-matrix',
  standalone: true,
  imports: [
    FormsModule,
    ButtonModule,
    InputTextModule,
    SelectModule,
    TooltipModule,
    EmptyStateComponent,
    LoadErrorComponent,
  ],
  template: `
    <div class="page">
      <header class="head">
        <div class="head-text">
          <h1>Matrice des permissions</h1>
          <p class="sub">Vue croisée permissions × rôles (lecture seule)</p>
        </div>
        <p-button label="Retour aux rôles" icon="pi pi-arrow-left" [text]="true" (onClick)="back()" />
      </header>

      <div class="toolbar">
        <span class="p-input-icon-left search">
          <i class="pi pi-search"></i>
          <input pInputText type="text" [ngModel]="search()" (ngModelChange)="search.set($event)"
                 placeholder="Rechercher une permission (libellé ou code)" />
        </span>
        <p-button label="Exporter (CSV)" icon="pi pi-download" [outlined]="true"
                  (onClick)="exportCsv()" [disabled]="sortedRoles().length === 0" />
      </div>

      <div class="chips">
        @for (g of groups; track g.key) {
          <button class="chip" [class.active]="!collapsedModules().has(g.key)"
                  (click)="toggleModule(g.key)"
                  [attr.aria-label]="(collapsedModules().has(g.key) ? 'Afficher' : 'Masquer') + ' le module ' + g.title">
            <i [className]="g.icon"></i>
            <span>{{ g.title }}</span>
            @if (collapsedModules().has(g.key)) {
              <i class="pi pi-eye-slash muted"></i>
            }
          </button>
        }
      </div>

      @if (loading()) {
        <div class="state"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else {
        @if (loadError()) {
          <app-load-error message="Impossible de charger la matrice. Vérifiez que le serveur est accessible." (retry)="load()" />
        } @else {
          @if (sortedRoles().length === 0) {
            <app-empty-state icon="pi pi-th-large" message="Aucun rôle à afficher. Créez d'abord des rôles depuis la page Rôles." />
          } @else {
            <!-- Desktop view -->
            <div class="desktop-view">
              <div class="matrix-scroll">
                <table class="matrix-table">
                  <thead>
                    <tr>
                      <th class="corner">Permission</th>
                      @for (r of sortedRoles(); track r.id) {
                        <th class="role-col">
                          <div class="role-head">
                            <span class="role-dot" [style.background]="visual(r.code).color"
                                  [pTooltip]="r.isSystem ? 'Rôle système' : 'Rôle personnalisé'"></span>
                            <span class="role-head-label" [pTooltip]="r.label">{{ r.label }}</span>
                            @if (r.isSystem) {
                              <i class="pi pi-lock tiny"></i>
                            }
                          </div>
                        </th>
                      }
                    </tr>
                  </thead>
                  <tbody>
                    @for (row of rows(); track row.type + (row.code ?? row.group?.key ?? '')) {
                      @if (row.type === 'header') {
                        @if (row.group) {
                          <tr class="module-row" (click)="toggleModule(row.group!.key)">
                            <th class="module-header">
                              <i [className]="row.group!.icon"></i>
                              <span>{{ row.group!.title }}</span>
                              <span class="module-count">{{ row.group!.ids.length }}</span>
                              <i class="pi {{ collapsedModules().has(row.group!.key) ? 'pi-chevron-right' : 'pi-chevron-down' }} chev"></i>
                            </th>
                            <td [attr.colspan]="sortedRoles().length" class="module-spacer"></td>
                          </tr>
                        }
                      } @else {
                        @if (row.code) {
                          <tr>
                            <th class="perm-name">
                              <span class="perm-sentence">{{ sentence(row.code!) }}</span>
                              <span class="perm-code">{{ row.code! }}</span>
                            </th>
                            @for (r of sortedRoles(); track r.id) {
                              <td class="cell">
                                @if (hasPerm(r, row.code!)) {
                                  <i class="pi pi-check-circle check" aria-label="Accordé"></i>
                                }
                              </td>
                            }
                          </tr>
                        }
                      }
                    } @empty {
                      <tr>
                        <th class="perm-name empty">Aucune permission ne correspond à votre recherche.</th>
                        <td [attr.colspan]="sortedRoles().length"></td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            </div>

            <!-- Mobile view (< 768px) -->
            <div class="mobile-view">
              <div class="mobile-picker">
                <label class="field-label">Rôle</label>
                <p-select [options]="mobileRoleOptions()" [ngModel]="mobileRoleId()"
                          (ngModelChange)="mobileRoleId.set($event)"
                          optionLabel="label" optionValue="value"
                          placeholder="Choisir un rôle"
                          [style]="{ 'width': '100%' }" />
              </div>
              @if (mobileRole(); as mr) {
                <div class="matrix-scroll">
                  <table class="matrix-table mobile">
                    <thead>
                      <tr>
                        <th class="corner">Permission</th>
                        <th class="role-col">
                          <div class="role-head">
                            <span class="role-dot" [style.background]="visual(mr.code).color"></span>
                            <span class="role-head-label">{{ mr.label }}</span>
                          </div>
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (row of rows(); track row.type + (row.code ?? row.group?.key ?? '')) {
                        @if (row.type === 'header') {
                          @if (row.group) {
                            <tr class="module-row">
                              <th class="module-header">
                                <i [className]="row.group!.icon"></i>
                                <span>{{ row.group!.title }}</span>
                                <span class="module-count">{{ row.group!.ids.length }}</span>
                              </th>
                              <td class="module-spacer"></td>
                            </tr>
                          }
                        } @else {
                          @if (row.code) {
                            <tr>
                              <th class="perm-name">
                                <span class="perm-sentence">{{ sentence(row.code!) }}</span>
                                <span class="perm-code">{{ row.code! }}</span>
                              </th>
                              <td class="cell">
                                @if (hasPerm(mr, row.code!)) {
                                  <i class="pi pi-check-circle check" aria-label="Accordé"></i>
                                }
                              </td>
                            </tr>
                          }
                        }
                      } @empty {
                        <tr>
                          <th class="perm-name empty">Aucune permission ne correspond.</th>
                          <td></td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              }
            </div>
          }
        }
      }
    </div>
  `,
  styles: [`
    .page { display: flex; flex-direction: column; gap: 14px; }
    .head { display: flex; justify-content: space-between; align-items: flex-end;
      gap: 12px; flex-wrap: wrap; }
    .head-text h1 { margin: 0; font-size: 22px; font-weight: 700; color: #0f172a; }
    .sub { margin: 4px 0 0; color: #64748b; font-size: 13px; }

    .toolbar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
    .search { flex: 1; min-width: 220px; }
    .search input { width: 100%; padding-left: 32px; }

    .chips { display: flex; flex-wrap: wrap; gap: 6px; }
    .chip {
      display: inline-flex; align-items: center; gap: 6px;
      padding: 6px 12px; border-radius: 999px;
      font-size: 13px; font-weight: 600; cursor: pointer;
      background: #fff; color: #475569; border: 1px solid #cbd5e1;
      font-family: inherit;
    }
    .chip:hover { background: #f8fafc; }
    .chip.active { background: #0f172a; color: #fff; border-color: #0f172a; }
    .chip .muted { opacity: .6; }

    .state { padding: 40px; text-align: center; color: #64748b; font-size: 15px; }

    .desktop-view { display: block; }
    .mobile-view { display: none; }
    @media (max-width: 767px) {
      .desktop-view { display: none; }
      .mobile-view { display: block; }
    }

    .matrix-scroll { overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 10px;
      max-height: calc(100vh - 280px); overflow-y: auto; }

    .matrix-table { border-collapse: separate; border-spacing: 0; width: 100%;
      font-size: 13px; }

    .matrix-table thead th {
      position: sticky; top: 0; z-index: 2;
      background: #f8fafc; color: #475569;
      padding: 10px 8px; font-weight: 700; font-size: 12px;
      text-transform: uppercase; letter-spacing: .03em;
      border-bottom: 1px solid #e2e8f0; text-align: center;
    }
    .matrix-table thead th.corner {
      position: sticky; top: 0; left: 0; z-index: 4;
      min-width: 240px; max-width: 280px; text-align: left;
    }
    .matrix-table thead th.role-col { min-width: 70px; max-width: 110px; }

    .role-head { display: flex; flex-direction: column; align-items: center; gap: 4px; }
    .role-dot { width: 8px; height: 8px; border-radius: 999px; }
    .role-head-label {
      font-size: 12px; font-weight: 600; color: #0f172a; text-transform: none;
      letter-spacing: 0; max-width: 100px; overflow: hidden;
      text-overflow: ellipsis; white-space: nowrap;
    }
    .role-head .tiny { font-size: 9px; color: #94a3b8; }

    .matrix-table tbody .module-row { cursor: pointer; }
    .matrix-table tbody .module-row:hover .module-header { background: #f1f5f9; }
    .matrix-table tbody .module-header {
      position: sticky; left: 0; z-index: 1;
      background: #fff; padding: 8px 12px;
      font-size: 12px; font-weight: 700; color: #475569;
      text-transform: uppercase; letter-spacing: .04em; text-align: left;
      border-bottom: 1px solid #f1f5f9;
      display: flex; align-items: center; gap: 8px;
      min-width: 240px; max-width: 280px;
    }
    .module-header .module-count {
      background: #e2e8f0; color: #475569; font-size: 10px; font-weight: 700;
      padding: 1px 6px; border-radius: 999px; text-transform: none;
    }
    .module-header .chev { margin-left: auto; font-size: 11px; color: #94a3b8; }
    .module-spacer { background: #fff; border-bottom: 1px solid #f1f5f9; }

    .matrix-table tbody .perm-name {
      position: sticky; left: 0; z-index: 1;
      background: #fff; padding: 8px 12px; text-align: left;
      border-bottom: 1px solid #f1f5f9;
      min-width: 240px; max-width: 280px;
    }
    .matrix-table tbody tr:hover .perm-name { background: #f8fafc; }
    .perm-sentence { display: block; font-size: 13px; font-weight: 500; color: #1e293b; }
    .perm-code { display: block; font-size: 10px; color: #94a3b8;
      font-family: ui-monospace, SFMono-Regular, monospace; }
    .perm-name.empty { color: #94a3b8; font-style: italic; }

    .matrix-table tbody td.cell {
      text-align: center; padding: 8px; border-bottom: 1px solid #f1f5f9;
      color: #1e293b;
    }
    .matrix-table tbody tr:hover td.cell { background: #f8fafc; }
    .check { color: #16a34a; font-size: 16px; }

    .matrix-table.mobile thead th.corner { min-width: 180px; max-width: 220px; }
    .matrix-table.mobile .perm-name { min-width: 180px; max-width: 220px; }
    .matrix-table.mobile thead th.role-col { min-width: 80px; }

    .mobile-picker { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
    .field-label { font-size: 12px; font-weight: 700; color: #475569;
      text-transform: uppercase; letter-spacing: .04em; }
  `],
})
export class RolesMatrixComponent implements OnInit {
  private http = inject(HttpClient);
  private router = inject(Router);

  readonly groups: PermGroup[] = PERM_GROUPS;

  readonly roles = signal<RoleRow[]>([]);
  readonly loading = signal<boolean>(true);
  readonly loadError = signal<boolean>(false);

  readonly search = signal<string>('');
  readonly collapsedModules = signal<Set<string>>(new Set());
  readonly mobileRoleId = signal<string | null>(null);

  readonly sortedRoles = computed<RoleRow[]>(() => {
    const list = this.roles();
    if (!list || list.length === 0) return [];
    return [...list].sort((a, b) => {
      if (a.isSystem !== b.isSystem) return a.isSystem ? -1 : 1;
      return (a.label ?? '').localeCompare(b.label ?? '', 'fr');
    });
  });

  readonly rows = computed<MatrixRow[]>(() => {
    const q = this.search().trim().toLowerCase();
    const collapsed = this.collapsedModules();
    const out: MatrixRow[] = [];
    for (const g of this.groups) {
      const visiblePerms = q
        ? g.ids.filter(p =>
            p.toLowerCase().includes(q) ||
            (PERM_SENTENCES[p] ?? '').toLowerCase().includes(q))
        : g.ids;
      if (visiblePerms.length === 0) continue;
      out.push({ type: 'header', group: g });
      if (q || !collapsed.has(g.key)) {
        visiblePerms.forEach(p => out.push({ type: 'perm', code: p }));
      }
    }
    return out;
  });

  readonly mobileRoleOptions = computed<SelectOption[]>(() => {
    return this.sortedRoles().map(r => ({
      label: r.label + (r.isSystem ? ' (système)' : ''),
      value: r.id,
    }));
  });

  readonly mobileRole = computed<RoleRow | null>(() => {
    const id = this.mobileRoleId();
    return this.sortedRoles().find(r => r.id === id) ?? null;
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading.set(true);
    this.loadError.set(false);
    this.http.get<RoleRow[]>('/api/roles').subscribe({
      next: (list) => {
        this.roles.set(list ?? []);
        const first = this.sortedRoles()[0];
        if (first) this.mobileRoleId.set(first.id);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('[RolesMatrix] Failed to load roles:', err);
        this.loadError.set(true);
        this.loading.set(false);
      },
    });
  }

  visual(code: string): RoleVisual { return roleVisual(code); }

  sentence(code: string): string {
    return PERM_SENTENCES[code] ?? code;
  }

  hasPerm(r: RoleRow, code: string): boolean {
    return (r.permissions ?? []).includes(code);
  }

  toggleModule(key: string): void {
    const next = new Set(this.collapsedModules());
    if (next.has(key)) next.delete(key); else next.add(key);
    this.collapsedModules.set(next);
  }

  back(): void {
    this.router.navigate(['/roles']);
  }

  exportCsv(): void {
    const rs = this.sortedRoles();
    if (rs.length === 0) return;
    const lines: string[] = [];
    const header = ['Permission', ...rs.map(r => r.label)].join(';');
    lines.push(header);
    for (const g of this.groups) {
      for (const p of g.ids) {
        const sentence = PERM_SENTENCES[p] ?? p;
        const cells = rs.map(r => (this.hasPerm(r, p) ? 'X' : ''));
        lines.push([sentence, ...cells].join(';'));
      }
    }
    const csv = '\uFEFF' + lines.join('\r\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'matrice-permissions.csv';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }
}
