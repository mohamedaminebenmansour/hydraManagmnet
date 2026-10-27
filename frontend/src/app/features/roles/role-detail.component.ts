import { Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { HttpClient } from '@angular/common/http';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { TagModule } from 'primeng/tag';
import { DividerModule } from 'primeng/divider';
import { MessageService } from 'primeng/api';
import { PERM_GROUPS, PERM_LABELS } from '../../core/permissions';

interface RoleRow {
  id: string;
  code: string;
  label: string;
  isSystem: boolean;
  permissions: string[];
}

interface PermRow { id: string; description: string | null; }

@Component({
  selector: 'app-role-detail',
  standalone: true,
  imports: [
    FormsModule,
    ButtonModule,
    InputTextModule,
    ToggleSwitchModule,
    TagModule,
    DividerModule
  ],
  template: `
    <div class="head">
      <p-button icon="pi pi-arrow-left" label="Retour" [text]="true" (onClick)="back()" />
      <h2><i class="pi pi-id-card"></i> {{ label || 'Rôle' }}</h2>
      <div class="head-badge">
        @if (isSystem) {
          <p-tag value="Système — lecture seule" severity="secondary" icon="pi pi-lock" />
        }
      </div>
    </div>

    @if (loading) {
      <div class="loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
    }

    @if (notFound) {
      <div class="loading">Rôle introuvable.</div>
    }

    @if (!loading && !notFound) {
      <div class="panel">
        <div class="name-block">
          <label class="name-label">Nom du rôle</label>
          <input pInputText class="role-name" placeholder="Nom du rôle"
                 [(ngModel)]="label" [readonly]="isSystem"
                 (keyup.enter)="save()" />
        </div>

        <div class="perms-title">Permissions</div>
        @for (g of groups; track g.key) {
          @if (!$first) { <p-divider /> }
          <div class="grp">
            <div class="grp-head"><i [className]="g.icon"></i> {{ g.title }}</div>
            @for (pid of g.ids; track pid) {
              @if (hasPerm(pid)) {
                <div class="perm-row" (click)="onRowClick($event, pid)">
                  <span class="perm-label">{{ permLabel(pid) }}</span>
                  <p-toggleswitch [ngModel]="selected.has(pid)"
                                  [disabled]="isSystem"
                                  (onChange)="toggle(pid, $event.checked)" />
                </div>
              }
            }
          </div>
        }
      </div>

      @if (!isSystem) {
        <div class="actions">
          <p-button label="Annuler" [text]="true" (onClick)="back()" />
          <p-button label="Enregistrer" icon="pi pi-check"
                    (onClick)="save()" [disabled]="!label.trim()" />
        </div>
      }
    }
  `,
  styles: [`
    .head { display: flex; justify-content: space-between; align-items: center;
      gap: 12px; margin-bottom: 18px; }
    .head h2 { margin: 0; display: flex; gap: 10px; align-items: center; font-size: 22px; }
    .head-badge { min-width: 40px; display: flex; justify-content: flex-end; }
    .loading { color: #64748b; padding: 24px; text-align: center; font-size: 16px; }
    .panel { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px;
      padding: 18px; max-width: 620px; }
    .name-block { display: flex; flex-direction: column; gap: 6px; margin-bottom: 10px; }
    .name-label { font-size: 13px; font-weight: 700; color: #475569;
      text-transform: uppercase; letter-spacing: 0.03em; }
    .role-name { font-size: 18px; padding: 12px 14px; width: 100%; box-sizing: border-box; }
    .perms-title { font-size: 13px; font-weight: 700; color: #475569; margin: 6px 0 2px;
      text-transform: uppercase; letter-spacing: 0.03em; }
    .grp-head { display: flex; align-items: center; gap: 8px; margin: 8px 0 2px;
      font-size: 13px; font-weight: 700; color: #475569; text-transform: uppercase;
      letter-spacing: 0.03em; }
    .perm-row { display: flex; justify-content: space-between; align-items: center; gap: 12px;
      min-height: 52px; padding: 6px 8px; border-radius: 8px; cursor: pointer; }
    .perm-row:hover { background: #f8fafc; }
    .perm-label { font-size: 16px; color: #1e293b; }
    .actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
  `]
})
export class RoleDetailComponent implements OnInit {
  private http = inject(HttpClient);
  private route = inject(ActivatedRoute);
  private router = inject(Router);
  private msgs = inject(MessageService);

  readonly groups = PERM_GROUPS;

  isSystem = false;
  loading = true;
  notFound = false;
  label = '';
  selected = new Set<string>();

  private roleId: string | null = null;
  private permIds = new Set<string>();

  ngOnInit(): void {
    this.roleId = this.route.snapshot.paramMap.get('id');

    this.http.get<PermRow[]>('/api/roles/permissions').subscribe({
      next: p => { this.permIds = new Set((p ?? []).map(x => x.id)); },
      error: () => { /* catalog optional: leave toggles empty */ }
    });

    this.http.get<RoleRow[]>('/api/roles').subscribe({
      next: list => {
        const r = (list ?? []).find(x => x.id === this.roleId);
        if (!r) { this.notFound = true; this.loading = false; return; }
        this.label = r.label;
        this.isSystem = r.isSystem;
        this.selected = new Set(r.permissions ?? []);
        this.loading = false;
      },
      error: () => {
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: 'Chargement impossible' });
        this.loading = false;
        this.notFound = true;
      }
    });
  }

  permLabel(id: string): string { return PERM_LABELS[id] ?? id; }

  hasPerm(id: string): boolean { return this.permIds.has(id); }

  toggle(id: string, on: boolean): void {
    if (on) { this.selected.add(id); } else { this.selected.delete(id); }
  }

  onRowClick(ev: Event, id: string): void {
    if (this.isSystem) { return; }
    const target = ev.target as HTMLElement | null;
    if (target && target.closest('p-toggleswitch')) { return; }
    this.toggle(id, !this.selected.has(id));
  }

  save(): void {
    if (this.isSystem || !this.roleId) { return; }
    const body = { label: this.label, permissionCodes: [...this.selected] };
    this.http.put(`/api/roles/${this.roleId}`, body).subscribe({
      next: () => {
        this.msgs.add({ severity: 'success', summary: 'Rôle modifié' });
        this.router.navigate(['/roles']);
      },
      error: (err) => this.msgs.add({ severity: 'error', summary: 'Erreur',
        detail: err.error?.message ?? 'Modification impossible' })
    });
  }

  back(): void { this.router.navigate(['/roles']); }
}