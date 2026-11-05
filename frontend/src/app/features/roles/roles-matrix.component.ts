import { Component, OnInit, computed, inject, signal, effect } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { TooltipModule } from 'primeng/tooltip';
import { SelectModule } from 'primeng/select';
import { PERM_GROUPS, PERM_SENTENCES } from '@app/core/permissions';
import { roleVisual } from '@app/shared/hydra-design';
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

interface FlatRow {
  isHeader: boolean;
  code?: string;
  group?: PermGroup;
}

interface SelectOption {
  label: string;
  value: string;
}

@Component({
  selector: 'app-roles-matrix',
  standalone: true,
  imports: [
    FormsModule,
    ButtonModule,
    InputTextModule,
    TooltipModule,
    SelectModule,
    EmptyStateComponent,
    LoadErrorComponent,
  ],
  template: `
    <div class="page">
      <header class="head">
        <div>
          <h1><i class="pi pi-th-large title-icon"></i> Matrice des permissions</h1>
          <p class="sub">Cliquez sur un rôle pour voir ses permissions en détail.</p>
        </div>
        <p-button label="Retour" icon="pi pi-arrow-left" [text]="true" size="small" (onClick)="back()" />
      </header>

      <!-- DEBUG INFO — visible on screen -->
      <div class="debug-info" style="background:#fef3c7;border:1px solid #f59e0b;padding:12px;border-radius:8px;font-size:13px;margin-bottom:12px;">
        <strong>DEBUG:</strong> loading={{loading()}} | loadError={{loadError()}} | rolesCount={{roles().length}} | sortedCount={{sortedRoles().length}} | flatRowsCount={{flatRows().length}} | groups={{groups.length}}
      </div>

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
                  (click)="toggleModule(g.key)">
            <i [className]="g.icon"></i>
            <span>{{ g.title }}</span>
          </button>
        }
      </div>

      @if (loading()) {
        <div class="state"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      }

      @if (!loading() && loadError()) {
        <app-load-error message="Impossible de charger la matrice." (retry)="load()" />
      }

      @if (!loading() && !loadError() && sortedRoles().length === 0) {
        <app-empty-state icon="pi pi-th-large" message="Aucun rôle à afficher (sortedRoles est vide)." />
      }

      @if (!loading() && !loadError() && sortedRoles().length > 0) {
        <div class="desktop-view">
          <div class="matrix-scroll">
            <table class="matrix-table">
              <thead>
                <tr>
                  <th class="corner">Permission</th>
                  @for (r of sortedRoles(); track r.id) {
                    <th class="role-col">
                      <div class="role-head">
                        <span class="role-dot" [style.background]="visual(r.code).color"></span>
                        <span class="role-head-label" [pTooltip]="r.label">{{ r.label }}</span>
                        @if (r.isSystem) { <i class="pi pi-lock tiny"></i> }
                      </div>
                    </th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (row of flatRows(); track $index) {
                  @if (row.isHeader) {
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
                  @if (!row.isHeader) {
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
              </tbody>
            </table>
          </div>
        </div>

        <div class="mobile-view">
          <div class="mobile-picker">
            <label class="field-label">Rôle</label>
            <p-select [options]="mobileRoleOptions()" [ngModel]="mobileRoleId()"
                      (ngModelChange)="mobileRoleId.set($event)"
                      optionLabel="label" optionValue="value"
                      placeholder="Choisir un rôle" [style]="{ 'width': '100%' }" />
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
                  @for (row of flatRows(); track $index) {
                    @if (row.isHeader) {
                      <tr class="module-row">
                        <th class="module-header">
                          <i [className]="row.group!.icon"></i>
                          <span>{{ row.group!.title }}</span>
                          <span class="module-count">{{ row.group!.ids.length }}</span>
                        </th>
                        <td class="module-spacer"></td>
                      </tr>
                    }
                    @if (!row.isHeader) {
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
                </tbody>
              </table>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    .page { display: flex; flex-direction: column; gap: 16px; }

    /* Header */
    .head { display: flex; justify-content: space-between; align-items: flex-start; flex-wrap: wrap; gap: 12px; }
    .head h1 { margin: 0; font-size: 24px; font-weight: 800; color: #0f172a; display: flex; align-items: center; gap: 10px; }
    .title-icon { color: #0ea5e9; }
    .sub { margin: 6px 0 0; color: #64748b; font-size: 14px; }

    /* Role selector cards */
    .role-cards { display: flex; flex-wrap: wrap; gap: 8px; }
    .role-card { display: flex; align-items: center; gap: 8px; padding: 10px 16px; border-radius: 10px;
      background: #fff; border: 2px solid #e2e8f0; cursor: pointer; font-family: inherit;
      font-size: 14px; font-weight: 600; color: #475569; transition: all .15s; }
    .role-card:hover { border-color: var(--rc, #0ea5e9); background: #f8fafc; }
    .role-card.selected { border-color: var(--rc, #0ea5e9); background: color-mix(in srgb, var(--rc, #0ea5e9) 8%, white); color: #0f172a; }
    .rc-dot { width: 10px; height: 10px; border-radius: 999px; flex-shrink: 0; }
    .rc-label { white-space: nowrap; }
    .rc-lock { font-size: 10px; color: #94a3b8; }
    .rc-count { background: #f1f5f9; color: #64748b; font-size: 11px; padding: 2px 8px; border-radius: 999px; font-weight: 700; }
    .role-card.selected .rc-count { background: var(--rc, #0ea5e9); color: #fff; }

    /* Detail card */
    .detail-card { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; overflow: hidden; }
    .detail-header { padding: 16px 20px; border-bottom: 1px solid #e2e8f0; border-left: 4px solid var(--rc, #0ea5e9);
      display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
    .detail-title { display: flex; align-items: center; gap: 10px; }
    .detail-dot { width: 14px; height: 14px; border-radius: 999px; }
    .detail-title h2 { margin: 0; font-size: 18px; font-weight: 700; color: #0f172a; }
    .badge { font-size: 11px; padding: 2px 10px; border-radius: 999px; font-weight: 600; }
    .badge.sys { background: #f1f5f9; color: #64748b; }
    .badge.users { background: #eff6ff; color: #2563eb; }
    .detail-stats { display: flex; gap: 16px; }
    .stat { font-size: 13px; color: #64748b; }
    .stat strong { color: #0f172a; font-weight: 700; font-size: 16px; }

    /* Modules */
    .modules { padding: 8px 0; }
    .module { border-bottom: 1px solid #f1f5f9; }
    .module:last-child { border-bottom: none; }
    .module-head { display: flex; align-items: center; gap: 10px; padding: 12px 20px; cursor: pointer; transition: background .1s; }
    .module-head:hover { background: #f8fafc; }
    .module-head i:first-child { font-size: 16px; color: #0ea5e9; width: 20px; text-align: center; }
    .module-title { font-size: 14px; font-weight: 700; color: #334155; flex-shrink: 0; }
    .module-bar { display: flex; gap: 3px; flex: 1; justify-content: center; min-width: 0; }
    .bar-dot { width: 8px; height: 8px; border-radius: 2px; background: #e2e8f0; flex-shrink: 0; }
    .bar-dot.granted { background: #16a34a; }
    .module-count-text { font-size: 12px; color: #64748b; font-weight: 600; flex-shrink: 0; min-width: 40px; text-align: right; }
    .chev { color: #94a3b8; font-size: 12px; flex-shrink: 0; }

    /* Permission rows */
    .module-body { padding: 4px 20px 12px; }
    .perm-row { display: flex; align-items: center; gap: 10px; padding: 8px 12px; border-radius: 8px; }
    .perm-row:hover { background: #f8fafc; }
    .perm-row.granted { background: #f0fdf4; }
    .perm-row.granted:hover { background: #dcfce7; }
    .perm-check { width: 20px; flex-shrink: 0; text-align: center; }
    .ok { color: #16a34a; font-size: 18px; }
    .deny { color: #d1d5db; font-size: 10px; }
    .perm-text { font-size: 14px; color: #1e293b; flex: 1; }
    .perm-row.granted .perm-text { font-weight: 500; }
    .perm-code { font-size: 10px; color: #cbd5e1; font-family: ui-monospace, SFMono-Regular, monospace; }

    /* Footer */
    .footer-bar { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
    .legend { display: flex; gap: 16px; font-size: 13px; color: #64748b; }
    .legend span { display: flex; align-items: center; gap: 6px; }
    .legend .ok { font-size: 14px; }
    .legend .deny { font-size: 8px; }

    .state { padding: 60px; text-align: center; color: #64748b; font-size: 16px; }

    /* Mobile */
    @media (max-width: 640px) {
      .role-cards { gap: 6px; }
      .role-card { padding: 8px 12px; font-size: 13px; }
      .rc-label { max-width: 100px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
      .detail-header { padding: 12px 16px; }
      .detail-stats { flex-direction: column; gap: 4px; }
      .module-head { padding: 10px 16px; }
      .module-bar { display: none; }
      .module-body { padding: 4px 16px 8px; }
    }
  `],
})
export class RolesMatrixComponent implements OnInit {
  private http = inject(HttpClient);
  private router = inject(Router);

  readonly groups: PermGroup[] = PERM_GROUPS;
  readonly totalPerms = PERM_GROUPS.reduce((sum, g) => sum + g.ids.length, 0);

  readonly roles = signal<RoleRow[]>([]);
  readonly loading = signal<boolean>(true);
  readonly loadError = signal<boolean>(false);
  readonly collapsedModules = signal<Set<string>>(new Set());
  readonly search = signal<string>('');
  readonly selectedRoleId = signal<string | null>(null);

  readonly sortedRoles = computed<RoleRow[]>(() => {
    const list = this.roles();
    console.log('[RolesMatrix] sortedRoles() evaluating, roles count:', list?.length);
    if (!list || list.length === 0) return [];
    const sorted = [...list].sort((a, b) => {
      if (a.isSystem !== b.isSystem) return a.isSystem ? -1 : 1;
      return (a.label ?? '').localeCompare(b.label ?? '', 'fr');
    });
    console.log('[RolesMatrix] sortedRoles() result:', sorted.length, 'roles, first:', sorted[0]?.code);
    return sorted;
  });

  readonly flatRows = computed<FlatRow[]>(() => {
    const q = this.search().trim().toLowerCase();
    const collapsed = this.collapsedModules();
    const out: FlatRow[] = [];
    console.log('[RolesMatrix] flatRows() evaluating, groups:', this.groups?.length, 'search:', q);
    for (const g of this.groups) {
      const visiblePerms = q
        ? g.ids.filter(p =>
            p.toLowerCase().includes(q) ||
            (PERM_SENTENCES[p] ?? '').toLowerCase().includes(q))
        : g.ids;
      if (visiblePerms.length === 0) continue;
      out.push({ isHeader: true, group: g });
      if (q || !collapsed.has(g.key)) {
        visiblePerms.forEach(p => out.push({ isHeader: false, code: p }));
      }
    }
    console.log('[RolesMatrix] flatRows() result:', out.length, 'rows');
    return out;
  });

  readonly mobileRoleOptions = computed<SelectOption[]>(() => {
    return this.sortedRoles().map(r => ({
      label: r.label + (r.isSystem ? ' (système)' : ''),
      value: r.id,
    }));
  });

  // Selected role id in the mobile <p-select> dropdown
  readonly mobileRoleId = signal<string | null>(null);

  readonly mobileRole = computed<RoleRow | null>(() => {
    const id = this.mobileRoleId();
    return this.sortedRoles().find(r => r.id === id) ?? null;
  });

  // Debug: log when any signal changes
  constructor() {
    effect(() => {
      console.log('[RolesMatrix] SIGNAL CHANGE: loading=', this.loading(), 'loadError=', this.loadError(),
        'roles=', this.roles().length, 'sortedRoles=', this.sortedRoles().length, 'flatRows=', this.flatRows().length);
    });
  }

  ngOnInit(): void {
    console.log('[RolesMatrix] ngOnInit, groups:', this.groups?.length, 'PERM_SENTENCES keys:', Object.keys(PERM_SENTENCES ?? {}).length);
    this.load();
  }

  load(): void {
    console.log('[RolesMatrix] load() called');
    this.loading.set(true);
    this.loadError.set(false);
    this.http.get<RoleRow[]>('/api/roles').subscribe({
      next: (list) => {
        console.log('[RolesMatrix] API success, raw response:', list);
        console.log('[RolesMatrix] API success, count:', list?.length);
        if (list && list.length > 0) {
          console.log('[RolesMatrix] First role:', JSON.stringify(list[0]));
          console.log('[RolesMatrix] First role permissions:', list[0]?.permissions);
        }
        this.roles.set(list ?? []);
        console.log('[RolesMatrix] After roles.set, roles() count:', this.roles().length);
        const first = this.sortedRoles()[0];
        if (first) this.selectedRoleId.set(first.id);
        this.loading.set(false);
        console.log('[RolesMatrix] After loading.set(false), loading:', this.loading());
      },
      error: (err) => {
        console.error('[RolesMatrix] API error:', err);
        this.loadError.set(true);
        this.loading.set(false);
      },
    });
  }

  visual(code: string) { return roleVisual(code); }

  sentence(code: string): string {
    const s = PERM_SENTENCES[code] ?? code;
    return s;
  }

  hasPerm(r: RoleRow, code: string): boolean {
    return (r.permissions ?? []).includes(code);
  }

  toggleModule(key: string): void {
    const next = new Set(this.collapsedModules());
    if (next.has(key)) next.delete(key); else next.add(key);
    this.collapsedModules.set(next);
  }

  back(): void { this.router.navigate(['/roles']); }

  exportCsv(): void {
    const rs = this.sortedRoles();
    console.log('[RolesMatrix] exportCsv, sortedRoles count:', rs.length);
    if (rs.length === 0) return;
    const lines: string[] = [];
    lines.push(['Permission', ...rs.map(r => r.label)].join(';'));
    for (const g of this.groups) {
      for (const p of g.ids) {
        lines.push([PERM_SENTENCES[p] ?? p, ...rs.map(r => this.hasPerm(r, p) ? 'X' : '')].join(';'));
      }
    }
    const blob = new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url; a.download = 'matrice-permissions.csv';
    document.body.appendChild(a); a.click(); document.body.removeChild(a);
    URL.revokeObjectURL(url);
  }
}
