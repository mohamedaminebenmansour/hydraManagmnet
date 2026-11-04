import { Component, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { TooltipModule } from 'primeng/tooltip';
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

@Component({
  selector: 'app-roles-matrix',
  standalone: true,
  imports: [
    FormsModule,
    ButtonModule,
    InputTextModule,
    TooltipModule,
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

      <!-- Role cards -->
      <div class="role-cards">
        @for (r of sortedRoles(); track r.id) {
          <button class="role-card" [class.selected]="selectedRoleId() === r.id"
                  [style.--rc]="visual(r.code).color"
                  (click)="selectedRoleId.set(r.id)">
            <span class="rc-dot" [style.background]="visual(r.code).color"></span>
            <span class="rc-label">{{ r.label }}</span>
            @if (r.isSystem) { <i class="pi pi-lock rc-lock"></i> }
            <span class="rc-count">{{ r.permissions?.length ?? 0 }}</span>
          </button>
        }
      </div>

      <!-- Selected role detail -->
      @if (selectedRole(); as sr) {
        <div class="detail-card" [style.--rc]="visual(sr.code).color">
          <div class="detail-header" [style.border-left-color]="visual(sr.code).color">
            <div class="detail-title">
              <span class="detail-dot" [style.background]="visual(sr.code).color"></span>
              <h2>{{ sr.label }}</h2>
              @if (sr.isSystem) { <span class="badge sys">Système</span> }
              @if (sr.userCount != null && sr.userCount > 0) {
                <span class="badge users" pTooltip="Nombre d'utilisateurs actifs">{{ sr.userCount }} utilisateur(s)</span>
              }
            </div>
            <div class="detail-stats">
              <span class="stat"><strong>{{ sr.permissions?.length ?? 0 }}</strong> permissions</span>
              <span class="stat"><strong>{{ totalPerms - (sr.permissions?.length ?? 0) }}</strong> non accordées</span>
            </div>
          </div>

          <!-- Permission modules -->
          <div class="modules">
            @for (g of groups; track g.key) {
              <div class="module" [class.collapsed]="collapsedModules().has(g.key)">
                <div class="module-head" (click)="toggleModule(g.key)">
                  <i [className]="g.icon"></i>
                  <span class="module-title">{{ g.title }}</span>
                  <span class="module-bar">
                    @for (p of g.ids; track p) {
                      <span class="bar-dot" [class.granted]="hasPerm(sr, p)"
                            [pTooltip]="sentence(p)"></span>
                    }
                  </span>
                  <span class="module-count-text">{{ countGranted(sr, g.ids) }}/{{ g.ids.length }}</span>
                  <i class="pi {{ collapsedModules().has(g.key) ? 'pi-chevron-right' : 'pi-chevron-down' }} chev"></i>
                </div>
                @if (!collapsedModules().has(g.key)) {
                  <div class="module-body">
                    @for (p of g.ids; track p) {
                      <div class="perm-row" [class.granted]="hasPerm(sr, p)">
                        <span class="perm-check">
                          @if (hasPerm(sr, p)) {
                            <i class="pi pi-check-circle ok"></i>
                          } @else {
                            <i class="pi pi-circle deny"></i>
                          }
                        </span>
                        <span class="perm-text">{{ sentence(p) }}</span>
                        <span class="perm-code">{{ p }}</span>
                      </div>
                    }
                  </div>
                }
              </div>
            }
          </div>
        </div>
      }

      <!-- States -->
      @if (loading()) {
        <div class="state"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      }
      @if (!loading() && loadError()) {
        <app-load-error message="Impossible de charger la matrice." (retry)="load()" />
      }
      @if (!loading() && !loadError() && sortedRoles().length === 0) {
        <app-empty-state icon="pi pi-th-large" message="Aucun rôle à afficher." />
      }

      <!-- Export + legend -->
      @if (!loading() && !loadError() && sortedRoles().length > 0) {
        <div class="footer-bar">
          <div class="legend">
            <span><i class="pi pi-check-circle ok"></i> Accordé</span>
            <span><i class="pi pi-circle deny"></i> Non accordé</span>
          </div>
          <p-button label="Exporter (CSV)" icon="pi pi-download" [outlined]="true" size="small" (onClick)="exportCsv()" />
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
  readonly selectedRoleId = signal<string | null>(null);

  readonly sortedRoles = computed<RoleRow[]>(() => {
    const list = this.roles();
    if (!list || list.length === 0) return [];
    return [...list].sort((a, b) => {
      if (a.isSystem !== b.isSystem) return a.isSystem ? -1 : 1;
      return (a.label ?? '').localeCompare(b.label ?? '', 'fr');
    });
  });

  readonly selectedRole = computed<RoleRow | null>(() => {
    const id = this.selectedRoleId();
    if (!id) return this.sortedRoles()[0] ?? null;
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
        if (first) this.selectedRoleId.set(first.id);
        this.loading.set(false);
      },
      error: (err) => {
        console.error('[RolesMatrix] Failed to load roles:', err);
        this.loadError.set(true);
        this.loading.set(false);
      },
    });
  }

  visual(code: string) { return roleVisual(code); }
  sentence(code: string): string { return PERM_SENTENCES[code] ?? code; }
  hasPerm(r: RoleRow, code: string): boolean { return (r.permissions ?? []).includes(code); }

  countGranted(r: RoleRow, ids: string[]): number {
    return ids.filter(p => this.hasPerm(r, p)).length;
  }

  toggleModule(key: string): void {
    const next = new Set(this.collapsedModules());
    if (next.has(key)) next.delete(key); else next.add(key);
    this.collapsedModules.set(next);
  }

  back(): void { this.router.navigate(['/roles']); }

  exportCsv(): void {
    const rs = this.sortedRoles();
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
