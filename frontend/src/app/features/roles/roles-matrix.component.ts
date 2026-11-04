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

interface FlatRow {
  isHeader: boolean;
  group?: PermGroup;
  code?: string;
  moduleIndex?: number;
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
        <div>
          <h1><i class="pi pi-th-large title-icon"></i> Matrice des permissions</h1>
          <p class="sub">Qui peut faire quoi ? Cochez vert = la permission est accordée.</p>
        </div>
        <div class="head-actions">
          <p-button label="Exporter (CSV)" icon="pi pi-download" [outlined]="true" size="small" (onClick)="exportCsv()" [disabled]="sortedRoles().length === 0" />
          <p-button label="Retour" icon="pi pi-arrow-left" [text]="true" size="small" (onClick)="back()" />
        </div>
      </header>

      <div class="toolbar">
        <span class="p-input-icon-left search">
          <i class="pi pi-search"></i>
          <input pInputText type="text" [ngModel]="search()" (ngModelChange)="search.set($event)"
                 placeholder="Rechercher une permission…" />
        </span>
        <div class="legend">
          <span class="legend-item"><i class="pi pi-check-circle" style="color:#16a34a"></i> Accordé</span>
          <span class="legend-item"><i class="pi pi-circle" style="color:#d1d5db"></i> Non accordé</span>
        </div>
      </div>

      <div class="chips">
        @for (g of groups; track g.key) {
          <button class="chip" [class.active]="!collapsedModules().has(g.key)"
                  (click)="toggleModule(g.key)">
            <i [className]="g.icon"></i>
            <span>{{ g.title }}</span>
            <span class="chip-count">{{ g.ids.length }}</span>
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
        <app-empty-state icon="pi pi-th-large" message="Aucun rôle à afficher." />
      }

      @if (!loading() && !loadError() && sortedRoles().length > 0) {
        <!-- Desktop view -->
        <div class="desktop-view">
          <div class="matrix-scroll">
            <table class="matrix-table">
              <thead>
                <tr>
                  <th class="corner">
                    <span class="corner-label">Permission</span>
                    <span class="corner-count">{{ totalPerms }} permissions</span>
                  </th>
                  @for (r of sortedRoles(); track r.id) {
                    <th class="role-col" [style.--role-color]="visual(r.code).color">
                      <div class="role-head">
                        <span class="role-dot" [style.background]="visual(r.code).color"></span>
                        <span class="role-head-label" [pTooltip]="r.label + ' (' + (r.permissions?.length ?? 0) + ' permissions)'">{{ r.label }}</span>
                        @if (r.isSystem) {
                          <i class="pi pi-lock tiny" pTooltip="Rôle système"></i>
                        }
                      </div>
                    </th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (row of flatRows(); track $index) {
                  @if (row.isHeader) {
                    <tr class="module-row" [class.module-odd]="row.moduleIndex! % 2 === 1"
                        (click)="toggleModule(row.group!.key)">
                      <th class="module-header" [class.module-odd]="row.moduleIndex! % 2 === 1">
                        <i [className]="row.group!.icon"></i>
                        <span>{{ row.group!.title }}</span>
                        <span class="module-count">{{ row.group!.ids.length }}</span>
                        <i class="pi {{ collapsedModules().has(row.group!.key) ? 'pi-chevron-right' : 'pi-chevron-down' }} chev"></i>
                      </th>
                      <td [attr.colspan]="sortedRoles().length" class="module-spacer"
                          [class.module-odd]="row.moduleIndex! % 2 === 1"></td>
                    </tr>
                  }
                  @if (!row.isHeader) {
                    <tr [class.row-odd]="row.moduleIndex! % 2 === 1">
                      <th class="perm-name" [class.perm-odd]="row.moduleIndex! % 2 === 1">
                        <span class="perm-sentence">{{ sentence(row.code!) }}</span>
                        <span class="perm-code">{{ row.code! }}</span>
                      </th>
                      @for (r of sortedRoles(); track r.id) {
                        <td class="cell" [class.cell-odd]="row.moduleIndex! % 2 === 1"
                            [class.cell-granted]="hasPerm(r, row.code!)">
                          @if (hasPerm(r, row.code!)) {
                            <i class="pi pi-check-circle check" aria-label="Accordé"></i>
                          } @else {
                            <span class="dot-empty"></span>
                          }
                        </td>
                      }
                    </tr>
                  }
                }
              </tbody>
              <tfoot>
                <tr class="summary-row">
                  <th class="summary-label">Total permissions</th>
                  @for (r of sortedRoles(); track r.id) {
                    <td class="summary-cell">{{ r.permissions?.length ?? 0 }}</td>
                  }
                </tr>
              </tfoot>
            </table>
          </div>
        </div>

        <!-- Mobile view -->
        <div class="mobile-view">
          <div class="mobile-picker">
            <label class="field-label">Choisir un rôle</label>
            <p-select [options]="mobileRoleOptions()" [ngModel]="mobileRoleId()"
                      (ngModelChange)="mobileRoleId.set($event)"
                      optionLabel="label" optionValue="value"
                      placeholder="Choisir un rôle" [style]="{ 'width': '100%' }" />
          </div>
          @if (mobileRole(); as mr) {
            <div class="mobile-role-header" [style.--role-color]="visual(mr.code).color">
              <span class="role-dot large"></span>
              <span class="mobile-role-name">{{ mr.label }}</span>
              <span class="mobile-role-count">{{ mr.permissions?.length ?? 0 }} permissions</span>
            </div>
            <div class="matrix-scroll">
              <table class="matrix-table mobile">
                <thead>
                  <tr>
                    <th class="corner">Permission</th>
                    <th class="role-col mobile-col">
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
                        <th class="module-header" colspan="2">
                          <i [className]="row.group!.icon"></i>
                          <span>{{ row.group!.title }}</span>
                          <span class="module-count">{{ row.group!.ids.length }}</span>
                        </th>
                      </tr>
                    }
                    @if (!row.isHeader) {
                      <tr>
                        <th class="perm-name">
                          <span class="perm-sentence">{{ sentence(row.code!) }}</span>
                        </th>
                        <td class="cell" [class.cell-granted]="hasPerm(mr, row.code!)">
                          @if (hasPerm(mr, row.code!)) {
                            <i class="pi pi-check-circle check"></i>
                          } @else {
                            <span class="dot-empty"></span>
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

    /* ── Header ── */
    .head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
    .head h1 { margin: 0; font-size: 24px; font-weight: 800; color: #0f172a; display: flex; align-items: center; gap: 10px; }
    .title-icon { color: #0ea5e9; font-size: 22px; }
    .sub { margin: 6px 0 0; color: #64748b; font-size: 14px; }
    .head-actions { display: flex; gap: 8px; align-items: center; }

    /* ── Toolbar ── */
    .toolbar { display: flex; gap: 16px; align-items: center; flex-wrap: wrap; justify-content: space-between; }
    .search { flex: 1; min-width: 240px; }
    .search input { width: 100%; padding-left: 36px; font-size: 14px; height: 38px; }
    .legend { display: flex; gap: 16px; font-size: 12px; color: #64748b; }
    .legend-item { display: flex; align-items: center; gap: 6px; }

    /* ── Module chips ── */
    .chips { display: flex; flex-wrap: wrap; gap: 8px; }
    .chip { display: inline-flex; align-items: center; gap: 6px; padding: 8px 14px; border-radius: 999px;
      font-size: 13px; font-weight: 600; cursor: pointer; background: #fff; color: #475569;
      border: 1px solid #e2e8f0; font-family: inherit; transition: all .15s; }
    .chip:hover { background: #f8fafc; border-color: #cbd5e1; }
    .chip.active { background: #0f172a; color: #fff; border-color: #0f172a; }
    .chip-count { background: rgba(255,255,255,0.2); font-size: 11px; padding: 1px 6px; border-radius: 999px; }
    .chip:not(.active) .chip-count { background: #f1f5f9; color: #64748b; }

    .state { padding: 60px; text-align: center; color: #64748b; font-size: 16px; }

    /* ── Desktop / Mobile ── */
    .desktop-view { display: block; }
    .mobile-view { display: none; }
    @media (max-width: 767px) { .desktop-view { display: none; } .mobile-view { display: block; } }

    /* ── Matrix scroll container ── */
    .matrix-scroll { overflow-x: auto; border: 1px solid #e2e8f0; border-radius: 12px;
      max-height: calc(100vh - 320px); overflow-y: auto; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }

    .matrix-table { border-collapse: separate; border-spacing: 0; width: 100%; font-size: 13px; }

    /* ── Header row ── */
    .matrix-table thead th { position: sticky; top: 0; z-index: 2;
      background: #0f172a; color: #94a3b8; padding: 14px 8px; font-weight: 700; font-size: 11px;
      text-transform: uppercase; letter-spacing: .04em; border-bottom: 2px solid #1e293b; text-align: center; }
    .corner { position: sticky; top: 0; left: 0; z-index: 5; min-width: 260px; max-width: 300px; text-align: left;
      display: flex; flex-direction: column; gap: 2px; }
    .corner-label { color: #f1f5f9; font-size: 13px; text-transform: none; letter-spacing: 0; font-weight: 700; }
    .corner-count { font-size: 10px; color: #64748b; text-transform: none; letter-spacing: 0; }
    .role-col { min-width: 72px; max-width: 110px; }

    .role-head { display: flex; flex-direction: column; align-items: center; gap: 6px; }
    .role-dot { width: 10px; height: 10px; border-radius: 999px; flex-shrink: 0; }
    .role-dot.large { width: 14px; height: 14px; }
    .role-head-label { font-size: 11px; font-weight: 600; color: #e2e8f0; text-transform: none; letter-spacing: 0;
      max-width: 90px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; line-height: 1.3; }
    .tiny { font-size: 8px; color: #64748b; }

    /* ── Module headers ── */
    .module-row { cursor: pointer; }
    .module-row:hover .module-header, .module-row:hover .module-spacer { background: #eef2ff; }
    .module-header { position: sticky; left: 0; z-index: 3; background: #f8fafc; padding: 10px 14px;
      font-size: 12px; font-weight: 700; color: #334155; text-transform: uppercase; letter-spacing: .05em;
      text-align: left; border-bottom: 2px solid #e2e8f0; display: flex; align-items: center; gap: 8px;
      min-width: 260px; max-width: 300px; }
    .module-header i:first-child { font-size: 14px; color: #0ea5e9; }
    .module-count { background: #e2e8f0; color: #475569; font-size: 10px; font-weight: 700;
      padding: 2px 8px; border-radius: 999px; text-transform: none; letter-spacing: 0; }
    .chev { margin-left: auto; font-size: 12px; color: #94a3b8; }
    .module-spacer { background: #f8fafc; border-bottom: 2px solid #e2e8f0; }

    /* ── Odd module backgrounds (zebra) ── */
    .module-header.module-odd, .module-spacer.module-odd { background: #fffbeb; }
    .module-row:hover .module-header.module-odd, .module-row:hover .module-spacer.module-odd { background: #fef3c7; }
    .perm-name.perm-odd { background: #fffdf7; }
    .cell.cell-odd { background: #fffdf7; }
    .row-odd:hover .perm-name.perm-odd { background: #fef9e7; }
    .row-odd:hover .cell.cell-odd { background: #fef9e7; }

    /* ── Permission rows ── */
    .perm-name { position: sticky; left: 0; z-index: 1; background: #fff; padding: 10px 14px;
      text-align: left; border-bottom: 1px solid #f1f5f9; min-width: 260px; max-width: 300px; }
    tr:hover .perm-name { background: #f8fafc; }
    .perm-sentence { display: block; font-size: 13px; font-weight: 500; color: #1e293b; line-height: 1.4; }
    .perm-code { display: block; font-size: 9px; color: #cbd5e1; font-family: ui-monospace, SFMono-Regular, monospace;
      margin-top: 2px; letter-spacing: .02em; }

    /* ── Cells ── */
    .cell { text-align: center; padding: 10px 4px; border-bottom: 1px solid #f1f5f9; transition: background .1s; }
    tr:hover .cell { background: #f8fafc; }
    .cell-granted { background: #f0fdf4; }
    tr:hover .cell-granted { background: #dcfce7; }
    .check { color: #16a34a; font-size: 18px; }
    .dot-empty { display: inline-block; width: 6px; height: 6px; border-radius: 999px; background: #e2e8f0; }

    /* ── Summary row (footer) ── */
    .summary-row { position: sticky; bottom: 0; z-index: 2; }
    .summary-label { position: sticky; left: 0; z-index: 3; background: #0f172a; color: #f1f5f9;
      padding: 10px 14px; font-size: 12px; font-weight: 700; text-align: left; min-width: 260px; max-width: 300px;
      border-top: 2px solid #1e293b; }
    .summary-cell { background: #0f172a; color: #94a3b8; text-align: center; padding: 10px 4px;
      font-size: 12px; font-weight: 600; border-top: 2px solid #1e293b; }

    /* ── Mobile ── */
    .mobile-picker { margin-bottom: 12px; }
    .field-label { display: block; font-size: 12px; font-weight: 700; color: #475569; margin-bottom: 6px; }
    .mobile-role-header { display: flex; align-items: center; gap: 10px; padding: 12px 16px;
      background: var(--role-color, #0ea5e9); color: #fff; border-radius: 10px; margin-bottom: 12px; }
    .mobile-role-name { font-weight: 700; font-size: 16px; flex: 1; }
    .mobile-role-count { font-size: 12px; opacity: .8; }
    .matrix-table.mobile .corner { min-width: 200px; max-width: 240px; }
    .matrix-table.mobile .perm-name { min-width: 200px; max-width: 240px; }
    .matrix-table.mobile .mobile-col { min-width: 80px; }
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

  readonly flatRows = computed<FlatRow[]>(() => {
    const q = this.search().trim().toLowerCase();
    const collapsed = this.collapsedModules();
    const out: FlatRow[] = [];
    let moduleIdx = 0;
    for (const g of this.groups) {
      const visiblePerms = q
        ? g.ids.filter(p =>
            p.toLowerCase().includes(q) ||
            (PERM_SENTENCES[p] ?? '').toLowerCase().includes(q))
        : g.ids;
      if (visiblePerms.length === 0) continue;
      out.push({ isHeader: true, group: g, moduleIndex: moduleIdx });
      if (q || !collapsed.has(g.key)) {
        visiblePerms.forEach(p => out.push({ isHeader: false, code: p, moduleIndex: moduleIdx }));
      }
      moduleIdx++;
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
