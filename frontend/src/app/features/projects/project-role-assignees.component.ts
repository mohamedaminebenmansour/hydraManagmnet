import { Component, Input, OnInit, inject, signal } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { ProcessService } from '@app/core/services/process.service';
import {
  RoleNeeded,
  roleLabel,
} from '@app/core/models/process.model';

/**
 * ProjectRoleAssigneesComponent — "Qui valide quoi" section, now READ-ONLY.
 *
 * Shows Rôle → Personne (→ Remplaçant if any). A link "Modifier dans l'onglet
 * Règles" opens the Règles tab. No save button, no write calls.
 */
@Component({
  selector: 'app-project-role-assignees',
  standalone: true,
  imports: [ButtonModule],
  template: `
    <section class="role-assignees">
      <header class="ra-head">
        <h2><i class="pi pi-user-edit"></i> Qui valide quoi</h2>
        <p class="ra-sub">
          Aperçu en lecture seule. Pour modifier les rôles et les remplaçants,
          utilisez l'onglet Règles.
        </p>
      </header>

      @if (loading()) {
        <div class="ra-loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
      } @else if (roles().length === 0) {
        <div class="ra-empty">
          <i class="pi pi-info-circle"></i>
          <p>Aucun rôle défini — les rôles apparaîtront dès qu'un modèle sera utilisé sur ce projet.</p>
        </div>
      } @else {
        <div class="ra-grid">
          @for (r of roles(); track r.roleCode) {
            <div class="ra-row">
              <div class="ra-role">
                <span class="ra-role-label">{{ roleLabel(r.roleCode) }}</span>
                <span class="ra-role-code">{{ r.roleCode }}</span>
              </div>
              <div class="ra-person">
                <span class="ra-person-name">{{ r.currentAssignee?.userName ?? '—' }}</span>
                @if (r.currentAssignee?.substituteUserName) {
                  <span class="ra-sub-name">→ {{ r.currentAssignee!.substituteUserName }}</span>
                }
              </div>
            </div>
          }
        </div>

        <div class="ra-actions">
          <p-button label="Modifier dans l'onglet Règles" icon="pi pi-arrow-right"
                  severity="info" [text]="true" (onClick)="goToRulesTab()" />
        </div>
      }
    </section>
  `,
  styles: [`
    .role-assignees {
      margin-top: 18px; background: #fff; border: 1px solid #e2e8f0;
      border-radius: 14px; padding: 16px 18px 18px;
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
    .ra-role-code { font-size: 10px; color: #94a3b8; }
    .ra-person { display: flex; align-items: center; gap: 8px; font-size: 14px; }
    .ra-person-name { color: #0f172a; }
    .ra-sub-name { color: #64748b; font-style: italic; }
    .ra-actions { margin-top: 14px; }
  `],
})
export class ProjectRoleAssigneesComponent implements OnInit {
  @Input({ required: true }) projectId!: string;

  private processService = inject(ProcessService);

  loading = signal(true);
  roles = signal<RoleNeeded[]>([]);
  roleLabel = roleLabel;

  /** Emits when the user clicks "Modifier dans l'onglet Règles". */
  goToRulesTab(): void {
    // Dispatch a custom event so the parent (project-detail) can switch tabs.
    window.dispatchEvent(new CustomEvent('open-rules-tab'));
  }

  ngOnInit(): void {
    this.loading.set(true);
    this.processService.listRolesNeeded(this.projectId).subscribe(roles => {
      this.roles.set(roles);
      this.loading.set(false);
    });
  }
}
