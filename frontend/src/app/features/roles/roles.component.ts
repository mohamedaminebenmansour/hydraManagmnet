import { Component, OnInit, computed, inject } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { TagModule } from 'primeng/tag';
import { ChipModule } from 'primeng/chip';
import { DividerModule } from 'primeng/divider';
import { MessageService } from 'primeng/api';
import { AuthService } from '../../core/auth.service';
import { PERM } from '../../core/permissions';
import { PERM_GROUPS, PERM_LABELS } from '../../core/permissions';
import { EmptyStateComponent } from '../../shared/empty-state.component';

interface RoleRow {
  id: string;
  code: string;
  label: string;
  isSystem: boolean;
  permissions: string[];
}

interface PermRow { id: string; description: string | null; }

@Component({
  selector: 'app-roles',
  standalone: true,
  imports: [
    FormsModule,
    ButtonModule,
    InputTextModule,
    ToggleSwitchModule,
    TagModule,
    ChipModule,
    DividerModule,
    EmptyStateComponent
  ],
  template: `
    <div class="head">
      <h2><i class="pi pi-id-card"></i> Rôles</h2>
      @if (canManage()) {
        <p-button icon="pi pi-plus" label="Nouveau" (onClick)="showForm = !showForm" />
      }
    </div>

    @if (showForm && canManage()) {
      <div class="form">
        <input pInputText placeholder="Nom du rôle (ex. : Lecture seule)" [(ngModel)]="formLabel"
               (keyup.enter)="create()" />

        <div class="rb-title">Permissions</div>
        @for (g of groups; track g.key) {
          @if (!$first) { <p-divider /> }
          <div class="grp">
            <div class="grp-head"><i [className]="g.icon"></i> {{ g.title }}</div>
            @for (pid of g.ids; track pid) {
              @if (hasPerm(pid)) {
                <div class="perm-row" (click)="onRowClick($event, pid)">
                  <span class="perm-label">{{ permLabel(pid) }}</span>
                  <p-toggleswitch [ngModel]="selectedPerms.has(pid)"
                                  (onChange)="toggle(pid, $event.checked)" />
                </div>
              }
            }
          </div>
        }

        <div class="form-actions">
          <p-button label="Annuler" [text]="true" (onClick)="showForm = false" />
          <p-button label="Créer" icon="pi pi-check" (onClick)="create()"
                    [disabled]="!formLabel.trim()" />
        </div>
      </div>
    }

    <div class="grid">
      @for (r of roles; track r.id) {
        <div class="rcard" (click)="openDetail(r)" (keyup.enter)="openDetail(r)"
             role="button" tabindex="0" [attr.aria-label]="'Voir le rôle ' + r.label">
          <div class="rhead">
            <span class="rname">{{ r.label }}</span>
            <div class="rbadges">
              @if (r.isSystem) {
                <p-tag value="Système" severity="secondary" icon="pi pi-lock" />
              } @else {
                <p-tag value="Perso" severity="info" />
              }
            </div>
          </div>
          <div class="ptags">
            @for (p of firstPerms(r.permissions); track p) {
              <p-chip [label]="permLabel(p)" />
            } @empty {
              <span class="norole">Aucune permission</span>
            }
            @if (extraPerms(r.permissions) > 0) {
              <span class="ptag more">+{{ extraPerms(r.permissions) }}</span>
            }
          </div>
          <div class="ractions">
            <p-button label="Détail" icon="pi pi-pencil" size="small" severity="secondary"
                      (onClick)="openDetail(r); $event.stopPropagation()" />
          </div>
        </div>
      } @empty {
        <app-empty-state icon="pi pi-id-card" message="Aucun rôle."></app-empty-state>
      }
    </div>
  `,
  styles: [`
    .head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 18px; }
    .head h2 { margin: 0; display: flex; gap: 10px; align-items: center; }
    .form { display: flex; flex-direction: column; gap: 10px; max-width: 560px; margin-bottom: 24px;
      background: #fff; padding: 18px; border-radius: 12px; border: 1px solid #e2e8f0; }
    .form input { font-size: 17px; }
    .rb-title { font-weight: 700; margin-top: 4px; }
    .grp-head { display: flex; align-items: center; gap: 8px; margin: 8px 0 2px;
      font-size: 13px; font-weight: 700; color: #475569; text-transform: uppercase;
      letter-spacing: 0.03em; }
    .perm-row { display: flex; justify-content: space-between; align-items: center; gap: 12px;
      min-height: 44px; padding: 4px 8px; border-radius: 8px; cursor: pointer; }
    .perm-row:hover { background: #f8fafc; }
    .perm-label { font-size: 15px; color: #1e293b; }
    .form-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 10px; }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 14px; }
    .rcard { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 16px;
      display: flex; flex-direction: column; gap: 10px; align-items: flex-start;
      cursor: pointer; transition: box-shadow .15s; }
    .rcard:hover { box-shadow: 0 6px 20px rgba(15,23,42,.12); }
    .rhead { display: flex; justify-content: space-between; width: 100%; align-items: center; gap: 8px; }
    .rname { font-size: 18px; font-weight: 700; }
    .rbadges { display: flex; align-items: center; gap: 6px; }
    .ptags { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; font-size: 12px; }
    .ptag { background: #f1f5f9; color: #334155; font-size: 12px; font-weight: 600;
      padding: 3px 10px; border-radius: 999px; }
    .norole { color: #94a3b8; font-size: 12px; font-style: italic; }
    .ractions { width: 100%; display: flex; justify-content: flex-end; }
  `]
})
export class RolesComponent implements OnInit {
  private http = inject(HttpClient);
  private router = inject(Router);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  roles: RoleRow[] = [];
  readonly groups = PERM_GROUPS;

  showForm = false;
  formLabel = '';
  selectedPerms = new Set<string>();

  private permIds = new Set<string>();

  canManage = computed(() => this.auth.can(PERM.ROLE_MANAGE));

  ngOnInit(): void {
    this.load();
    this.http.get<PermRow[]>('/api/roles/permissions').subscribe({
      next: p => { this.permIds = new Set((p ?? []).map(x => x.id)); },
      error: () => { /* catalog optional: show all group ids */ }
    });
  }

  permLabel(id: string): string { return PERM_LABELS[id] ?? id; }

  hasPerm(id: string): boolean { return this.permIds.size === 0 || this.permIds.has(id); }

  toggle(id: string, on: boolean): void {
    if (on) { this.selectedPerms.add(id); } else { this.selectedPerms.delete(id); }
  }

  onRowClick(ev: Event, id: string): void {
    const target = ev.target as HTMLElement | null;
    if (target && target.closest('p-toggleswitch')) { return; }
    this.toggle(id, !this.selectedPerms.has(id));
  }

  firstPerms(perms: string[]): string[] { return perms.slice(0, 8); }

  extraPerms(perms: string[]): number { return Math.max(0, perms.length - 8); }

  openDetail(r: RoleRow): void { this.router.navigate(['/roles', r.id]); }

  create(): void {
    if (!this.formLabel.trim()) { return; }
    const body = { label: this.formLabel.trim(), permissionCodes: Array.from(this.selectedPerms) };
    this.http.post('/api/roles', body).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Rôle créé' });
        this.formLabel = '';
        this.selectedPerms.clear();
        this.showForm = false;
        this.load();
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err.error?.message ?? 'Création impossible' })
    });
  }

  load(): void {
    this.http.get<RoleRow[]>('/api/roles').subscribe(r => this.roles = r ?? []);
  }
}