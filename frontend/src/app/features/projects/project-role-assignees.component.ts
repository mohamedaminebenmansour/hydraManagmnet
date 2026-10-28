import { Component, Input, OnInit, inject, signal, computed } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { SelectModule } from 'primeng/select';
import { MessageService } from 'primeng/api';
import { ProcessService } from '@app/core/services/process.service';
import { AuthService } from '@app/core/auth.service';
import {
  RoleNeeded, RoleAssignee,
  roleLabel,
} from '@app/core/models/process.model';

/**
 * ProjectRoleAssigneesComponent — "Qui valide quoi" section embedded in the
 * Processus tab of the project detail page (TASK 2 — STANDING RULES).
 *
 * For each business role used by the project's enabled process templates,
 * shows a French label + a PrimeNG Select to pick the user among candidates
 * (project_members, plus the org owner for the OWNER role). One "Enregistrer"
 * button batches all changes into a single PUT.
 *
 * Empty state: "Aucun processus activé — les rôles apparaissent quand un
 * modèle est utilisé sur ce projet." (When the API still returns
 * PAYMENT_APPROVAL roles even without explicit enablement — V18 default —
 * they show up here.)
 *
 * Auth: the section is read-only for non-admin viewers (Save button hidden);
 * backend requires org owner OR project ADMIN to write.
 */
@Component({
  selector: 'app-project-role-assignees',
  standalone: true,
  imports: [CommonModule, FormsModule, ButtonModule, SelectModule],
  template: `
    <section class="role-assignees">
      <header class="ra-head">
        <h2><i class="pi pi-user-edit"></i> Qui valide quoi</h2>
        <p class="ra-sub">
          Pour chaque rôle utilisé par les processus de ce projet, choisissez la personne responsable.
          Les étapes seront alors automatiquement attribuées à ces utilisateurs (Mes tâches).
        </p>
      </header>

      @if (loading()) {
        <div class="ra-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else if (roles().length === 0) {
        <div class="ra-empty">
          <i class="pi pi-info-circle"></i>
          <p>Aucun processus activé — les rôles apparaîtront dès qu'un modèle sera utilisé sur ce projet.</p>
        </div>
      } @else {
        <div class="ra-grid">
          @for (r of roles(); track r.roleCode) {
            <div class="ra-row">
              <div class="ra-role">
                <span class="ra-role-label">{{ roleLabel(r.roleCode) }}</span>
                <span class="ra-role-code" [title]="'Code rôle: ' + r.roleCode">{{ r.roleCode }}</span>
              </div>
              <div class="ra-select">
                <p-select
                  [options]="selectOptions(r)"
                  optionValue="userId"
                  optionLabel="label"
                  [(ngModel)]="draftMap[r.roleCode]"
                  [placeholder]="r.currentAssignee ? r.currentAssignee.userName : 'Aucun'"
                  filter
                  filterPlaceholder="Rechercher"
                  styleClass="w-100"
                  [showClear]="true" />
                @if (r.currentAssignee && draftMap[r.roleCode] !== r.currentAssignee.userId) {
                  <small class="ra-current-hint">
                    Actuel : <strong>{{ r.currentAssignee.userName }}</strong>
                  </small>
                }
              </div>
            </div>
          }
        </div>

        @if (canManage) {
          <div class="ra-actions">
            <button pButton label="Enregistrer" icon="pi pi-save" severity="success"
                    [loading]="saving()"
                    [disabled]="!hasChanges()"
                    (click)="save()"></button>
            @if (savedAt()) {
              <span class="ra-saved"><i class="pi pi-check-circle"></i> Enregistré</span>
            }
          </div>
        }
      }
    </section>
  `,
  styles: [`
    .role-assignees {
      margin-top: 18px;
      background: #ffffff;
      border: 1px solid #e2e8f0;
      border-radius: 14px;
      padding: 16px 18px 18px;
    }
    .ra-head { margin-bottom: 12px;
      h2 { font-size: 16px; font-weight: 700; color: #0f172a; margin: 0 0 4px;
           display: inline-flex; align-items: center; gap: 8px;
           i { color: #7c3aed; font-size: 18px; } }
      .ra-sub { font-size: 12px; color: #64748b; margin: 0; line-height: 1.4; }
    }
    .ra-loading, .ra-empty { padding: 18px; text-align: center; color: #64748b;
      i { font-size: 22px; color: #94a3b8; display: block; margin-bottom: 6px; }
      p { margin: 0; font-size: 13px; } }
    .ra-grid { display: flex; flex-direction: column; gap: 10px; }
    .ra-row {
      display: grid; grid-template-columns: 220px 1fr; gap: 12px;
      align-items: center; padding: 10px 12px;
      background: #f8fafc; border-radius: 10px;
      @media (max-width: 640px) { grid-template-columns: 1fr; }
    }
    .ra-role { display: flex; flex-direction: column; gap: 2px; }
    .ra-role-label { font-size: 14px; font-weight: 700; color: #0f172a; }
    .ra-role-code {
      font-size: 10px; font-family: 'Sarasa Mono SC', monospace; color: #94a3b8;
      letter-spacing: 0.03em;
    }
    .ra-select { display: flex; flex-direction: column; gap: 2px;
      .ra-current-hint { font-size: 11px; color: #d97706; padding-left: 2px; } }
    .ra-actions {
      margin-top: 14px; display: flex; align-items: center; gap: 12px;
      .ra-saved { font-size: 12px; color: #16a34a; font-weight: 600;
                  display: inline-flex; align-items: center; gap: 4px;
                  i { font-size: 11px; } }
    }
  `],
})
export class ProjectRoleAssigneesComponent implements OnInit {
  @Input({ required: true }) projectId!: string;

  private processService = inject(ProcessService);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  loading = signal(true);
  saving = signal(false);
  savedAt = signal<number | null>(null);

  roles = signal<RoleNeeded[]>([]);
  /**
   * Whether the current user can write. The org owner always can; for v1
   * we keep it simple and only enable Save for the org owner (project ADMIN
   * can still read + view; backend re-checks on PUT).
   */
  canManage = false;

  /**
   * Draft mapping (mutated by the PrimeNG Selects): roleCode → userId | null.
   * Compared against the original currentAssignee to compute hasChanges().
   */
  draftMap: Record<string, string | null | undefined> = {};

  /** Expose the roleLabel helper to the template (French label per role code). */
  roleLabel = roleLabel;

  ngOnInit(): void {
    this.canManage = !!this.auth.me()?.isOwner;
    this.load();
  }

  private load(): void {
    this.loading.set(true);
    this.processService.listRolesNeeded(this.projectId).subscribe(roles => {
      this.roles.set(roles);
      // Initialize draft from current assignees
      this.draftMap = {};
      for (const r of roles) {
        this.draftMap[r.roleCode] = r.currentAssignee?.userId ?? null;
      }
      this.loading.set(false);
    });
  }

  /** Build PrimeNG options for a role's <p-select> from its candidates list. */
  selectOptions(r: RoleNeeded): { userId: string; label: string }[] {
    return (r.candidates ?? []).map(c => ({
      userId: c.userId,
      label: `${c.userName}${c.isOwner ? ' (gérant)' : ''}`,
    }));
  }

  /** True if any role's draft differs from the original mapping. */
  hasChanges(): boolean {
    for (const r of this.roles()) {
      const draft = this.draftMap[r.roleCode] ?? null;
      const original = r.currentAssignee?.userId ?? null;
      if ((draft ?? null) !== (original ?? null)) return true;
    }
    return false;
  }

  /** Save: collect changed items + PUT batch. */
  save(): void {
    const items: { roleCode: string; userId: string }[] = [];
    for (const r of this.roles()) {
      const userId = this.draftMap[r.roleCode];
      // Skip empty selections (no userId) — those will be DELETEd separately
      // via the trash button on each row. We don't auto-delete on Save because
      // the user might be editing one role while leaving others untouched.
      if (userId) {
        items.push({ roleCode: r.roleCode, userId });
      }
    }
    if (items.length === 0) {
      this.msgs.add({
        severity: 'warn', summary: 'Rien à enregistrer',
        detail: 'Aucun utilisateur sélectionné. Utilisez la corbeille pour retirer un mappage.',
      });
      return;
    }
    this.saving.set(true);
    this.processService.upsertRoleAssignees(this.projectId, { items }).subscribe(updated => {
      this.saving.set(false);
      if (updated) {
        // Re-seed the draft from the server's response so hasChanges() resets.
        this.draftMap = {};
        for (const r of this.roles()) {
          const found = updated.find(u => u.roleCode === r.roleCode);
          this.draftMap[r.roleCode] = found?.userId ?? null;
        }
        // Refresh the cached roles() so currentAssignee reflects what the server now has
        this.refreshCurrentAssignees(updated);
        this.savedAt.set(Date.now());
        this.msgs.add({
          severity: 'success', summary: 'Affectations enregistrées',
          detail: 'Les prochaines étapes seront attribuées à ces utilisateurs.',
        });
      }
    });
  }

  /** Update the local roles() snapshot with the server's authoritative list. */
  private refreshCurrentAssignees(updated: RoleAssignee[]): void {
    const list = this.roles().map(r => {
      const found = updated.find(u => u.roleCode === r.roleCode);
      return found ? { ...r, currentAssignee: found } : r;
    });
    this.roles.set(list);
  }
}
