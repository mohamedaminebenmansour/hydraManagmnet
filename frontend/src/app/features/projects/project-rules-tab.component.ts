import { Component, Input, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { DatePickerModule } from 'primeng/datepicker';
import { MessageService } from 'primeng/api';
import { ProcessService } from '@app/core/services/process.service';
import { UserService } from '@app/core/services/user.service';
import { AuthService } from '@app/core/auth.service';
import {
  RoleNeeded, ProjectTemplateConfig, PendingApproval, roleLabel,
} from '@app/core/models/process.model';

/**
 * ProjectRulesTabComponent — the "Règles" tab on the chantier page.
 * Three blocks: Rôles (main + substitute + absence), Règles actives
 * (toggle + threshold with error revert), En attente (pending approvals).
 */
@Component({
  selector: 'app-project-rules-tab',
  standalone: true,
  imports: [FormsModule, ButtonModule, InputTextModule, SelectModule, ToggleSwitchModule, DatePickerModule],
  template: `
    <section class="rules-tab">
      <!-- ═══ 1. Rôles du chantier ═══ -->
      <div class="block">
        <h3><i class="pi pi-users"></i> Rôles du chantier</h3>
        <p class="hint">Pour chaque rôle, choisissez la personne responsable et un remplaçant.
          Le remplaçant agit quand la personne principale est absente.</p>
        @if (loadingRoles()) {
          <div class="loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
        } @else if (roles().length === 0) {
          <p class="empty">Aucun rôle défini pour ce chantier.</p>
        } @else {
          <table class="rt">
            <thead><tr><th>Rôle</th><th>Personne</th><th>Remplaçant</th></tr></thead>
            <tbody>
              @for (r of roles(); track r.roleCode) {
                <tr>
                  <td class="rt-role">{{ roleLabel(r.roleCode) }}</td>
                  <td>
                    <p-select [options]="selectOptions(r)" optionValue="userId" optionLabel="label"
                              [ngModel]="draftMain[r.roleCode]"
                              (ngModelChange)="draftMain[r.roleCode] = $event; rolesDirty.set(true)"
                              placeholder="Aucun" [showClear]="true" filter styleClass="rt-select"></p-select>
                    @if (mainAbsence(r)) {
                      <div class="absence-info">
                        <span class="absence-badge">Absent jusqu'au {{ fmtDate(mainAbsence(r)) }}</span>
                        <button pButton label="Fin d'absence" size="small" severity="warn" [text]="true"
                                (click)="endAbsence(r.currentAssignee?.userId || null)"></button>
                      </div>
                    } @else {
                      @if (r.currentAssignee?.userId) {
                        <button pButton label="Marquer absent" size="small" severity="warn" [text]="true"
                                (click)="openAbsencePicker(r.currentAssignee?.userId || '')"></button>
                      }
                    }
                  </td>
                  <td>
                    <p-select [options]="selectOptions(r)" optionValue="userId" optionLabel="label"
                              [ngModel]="draftSub[r.roleCode]"
                              (ngModelChange)="draftSub[r.roleCode] = $event; rolesDirty.set(true)"
                              placeholder="Aucun" [showClear]="true" filter styleClass="rt-select"></p-select>
                    @if (subAbsence(r)) {
                      <div class="absence-info">
                        <span class="absence-badge">Absent jusqu'au {{ fmtDate(subAbsence(r)) }}</span>
                        <button pButton label="Fin d'absence" size="small" severity="warn" [text]="true"
                                (click)="endAbsence(r.currentAssignee?.substituteUserId || null)"></button>
                      </div>
                    } @else {
                      @if (r.currentAssignee?.substituteUserId) {
                        <button pButton label="Marquer absent" size="small" severity="warn" [text]="true"
                                (click)="openAbsencePicker(r.currentAssignee?.substituteUserId || '')"></button>
                      }
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
          @if (canManage) {
            <button pButton label="Enregistrer les rôles" icon="pi pi-save" severity="success"
                    (click)="saveRoles()" [loading]="savingRoles()" [disabled]="!rolesDirty()"></button>
          }
        }
      </div>

      <!-- ═══ 2. Règles actives ═══ -->
      <div class="block">
        <h3><i class="pi pi-sitemap"></i> Règles actives</h3>
        <p class="hint">Activez les règles d'approbation pour ce chantier. Le seuil (TND) remplace
          le montant minimum de la règle pour ce chantier.</p>
        <p class="hint-extra">Une règle désactivée ne s'applique plus à ce chantier.</p>
        @if (ruleConfigs.length === 0) {
          <p class="empty">Aucune règle d'entreprise disponible.</p>
        } @else {
          @for (cfg of ruleConfigs; track cfg.templateId) {
            <div class="rule-row">
              <div class="rule-toggle">
                <p-toggleswitch [ngModel]="cfg.enabled"
                                (ngModelChange)="onRuleToggle(cfg, $event)" />
                <span class="rule-name">{{ cfg.templateName }}</span>
                <code class="rule-key">{{ cfg.templateKey }}</code>
              </div>
              <div class="rule-threshold">
                <label>Seuil (TND)</label>
                <input pInputText type="number" [ngModel]="cfg.amountThresholdOverride"
                       (ngModelChange)="cfg.amountThresholdOverride = toNum($event)"
                       (change)="saveThreshold(cfg)" placeholder="—" />
              </div>
              @if (ruleErrors[cfg.templateKey]) {
                <div class="rule-error">{{ ruleErrors[cfg.templateKey] }}</div>
              }
            </div>
          }
        }
      </div>

      <!-- ═══ 3. En attente ═══ -->
      <div class="block">
        <h3><i class="pi pi-clock"></i> En attente</h3>
        @if (loadingPending()) {
          <div class="loading"><i class="pi pi-spin pi-spinner"></i> Chargement…</div>
        } @else if (pending().length === 0) {
          <p class="empty">Aucune approbation en attente pour ce chantier.</p>
        } @else {
          <table class="pt">
            <thead><tr><th>Document</th><th>Étape</th><th>Qui</th><th>Depuis</th></tr></thead>
            <tbody>
              @for (p of pending(); track p.stepInstanceId) {
                <tr [class.late-row]="p.late">
                  <td>{{ p.subjectLabel ?? '—' }}
                    @if (p.subjectAmount != null) { <span class="amt">{{ p.subjectAmount }} TND</span> }
                  </td>
                  <td>{{ p.stepName }}</td>
                  <td>{{ p.assigneeName ?? roleLabel(p.assigneeRole) }}</td>
                  <td [class.late-text]="p.late">
                    {{ p.daysWaiting ?? 0 }} jour(s)
                    @if (p.late) { <i class="pi pi-exclamation-triangle" title="En retard"></i> }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        }
      </div>

      <!-- Absence date picker dialog -->
      @if (absencePickerUserId()) {
        <div class="absence-overlay" (click)="absencePickerUserId.set(null)">
          <div class="absence-dialog" (click)="$event.stopPropagation()">
            <h4>Marquer absent</h4>
            <p-datepicker [(ngModel)]="absenceDate" [minDate]="today" dateFormat="dd/mm/yy"
                          placeholder="Choisir une date" [inline]="true" />
            <div class="absence-actions">
              <button pButton label="Annuler" severity="secondary" [text]="true"
                      (click)="absencePickerUserId.set(null)"></button>
              <button pButton label="Confirmer" severity="warn"
                      (click)="confirmAbsence()" [disabled]="!absenceDate"></button>
            </div>
            @if (absenceError()) {
              <div class="absence-error">{{ absenceError() }}</div>
            }
          </div>
        </div>
      }
    </section>
  `,
  styles: [`
    .rules-tab { display: flex; flex-direction: column; gap: 20px; padding: 4px 0; }
    .block { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 16px 18px; }
    h3 { margin: 0 0 6px; font-size: 15px; color: #0f172a; display: inline-flex; align-items: center; gap: 8px;
         i { color: #7c3aed; } }
    .hint { font-size: 12px; color: #64748b; margin: 0 0 12px; }
    .hint-extra { font-size: 12px; color: #94a3b8; margin: 0 0 12px; font-style: italic; }
    .empty { color: #94a3b8; font-size: 13px; }
    .loading { color: #64748b; padding: 8px 0; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th { text-align: left; color: #64748b; font-weight: 600; padding: 6px 8px; border-bottom: 1px solid #e2e8f0; }
    td { padding: 8px; border-bottom: 1px solid #f1f5f9; vertical-align: middle; }
    .rt-role { font-weight: 600; color: #0f172a; }
    .rt-select { min-width: 180px; }
    .absence-info { margin-top: 4px; display: flex; align-items: center; gap: 6px; }
    .absence-badge { font-size: 11px; color: #d97706; font-weight: 600; background: #fffbeb; padding: 2px 6px; border-radius: 4px; }
    .rule-row { display: flex; align-items: center; justify-content: space-between; gap: 12px;
      padding: 10px 0; border-bottom: 1px solid #f1f5f9; flex-wrap: wrap; }
    .rule-toggle { display: flex; align-items: center; gap: 10px; }
    .rule-name { font-weight: 600; color: #0f172a; }
    .rule-key { font-size: 10px; color: #94a3b8; }
    .rule-threshold { display: flex; align-items: center; gap: 6px; font-size: 12px; color: #64748b;
      input { width: 90px; } }
    .rule-error { width: 100%; color: #dc2626; font-size: 12px; margin-top: 4px; }
    .late-row { background: #fef2f2; }
    .late-text { color: #dc2626; font-weight: 700; }
    .amt { color: #0d9488; font-weight: 600; margin-left: 4px; }
    .absence-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.4);
      z-index: 999; display: flex; align-items: center; justify-content: center; }
    .absence-dialog { background: #fff; border-radius: 12px; padding: 20px; min-width: 320px;
      h4 { margin: 0 0 12px; color: #0f172a; } }
    .absence-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 12px; }
    .absence-error { color: #dc2626; font-size: 12px; margin-top: 8px; }
    @media (max-width: 640px) { .rule-row { flex-direction: column; align-items: flex-start; } }
  `],
})
export class ProjectRulesTabComponent implements OnInit {
  @Input({ required: true }) projectId!: string;

  private processService = inject(ProcessService);
  private userService = inject(UserService);
  private auth = inject(AuthService);
  private msgs = inject(MessageService);

  canManage = false;
  roleLabel = roleLabel;

  // Block 1 — roles
  loadingRoles = signal(true);
  roles = signal<RoleNeeded[]>([]);
  draftMain: Record<string, string | null | undefined> = {};
  draftSub: Record<string, string | null | undefined> = {};
  rolesDirty = signal(false);
  savingRoles = signal(false);
  // Absence state: map userId → absentUntil date string (from the role assignee data)
  absenceMap: Record<string, string | null> = {};

  // Block 2 — active rules
  ruleConfigs: ProjectTemplateConfig[] = [];
  ruleErrors: Record<string, string> = {};

  // Block 3 — pending approvals
  loadingPending = signal(true);
  pending = signal<PendingApproval[]>([]);

  // Absence picker
  absencePickerUserId = signal<string | null>(null);
  absenceDate: Date | null = null;
  today = new Date();
  absenceError = signal('');

  ngOnInit(): void {
    this.canManage = !!this.auth.me()?.isOwner;
    this.loadRoles();
    this.loadRules();
    this.loadPending();
  }

  // ── Block 1 ──
  private loadRoles(): void {
    this.loadingRoles.set(true);
    this.processService.listRolesNeeded(this.projectId).subscribe(roles => {
      this.roles.set(roles);
      this.draftMain = {};
      this.draftSub = {};
      this.absenceMap = {};
      for (const r of roles) {
        this.draftMain[r.roleCode] = r.currentAssignee?.userId ?? null;
        this.draftSub[r.roleCode] = r.currentAssignee?.substituteUserId ?? null;
        // V44 — seed absence from the server: main person, substitute, and
        // every candidate. Null when not absent / absence already ended.
        const mainId = r.currentAssignee?.userId;
        if (mainId) this.absenceMap[mainId] = r.currentAssignee?.absentUntil ?? null;
        const subId = r.currentAssignee?.substituteUserId;
        if (subId) this.absenceMap[subId] = r.currentAssignee?.substituteAbsentUntil ?? null;
        for (const c of r.candidates ?? []) {
          this.absenceMap[c.userId] = c.absentUntil ?? null;
        }
      }
      this.rolesDirty.set(false);
      this.loadingRoles.set(false);
    });
  }

  selectOptions(r: RoleNeeded): { userId: string; label: string }[] {
    return (r.candidates ?? []).map(c => {
      const parts = [c.userName];
      if (c.isOwner) parts.push('(gérant)');
      // V44 — show the absence hint next to absent people in both dropdowns.
      if (c.absentUntil) parts.push(`(absent jusqu'au ${this.fmtDate(c.absentUntil)})`);
      return { userId: c.userId, label: parts.join(' ') };
    });
  }

  saveRoles(): void {
    const items = this.roles()
      .filter(r => this.draftMain[r.roleCode])
      .map(r => ({
        roleCode: r.roleCode,
        userId: this.draftMain[r.roleCode]!,
        substituteUserId: this.draftSub[r.roleCode] ?? null,
      }));
    if (items.length === 0) {
      this.msgs.add({ severity: 'warn', summary: 'Rien à enregistrer', detail: 'Sélectionnez au moins une personne.' });
      return;
    }
    this.savingRoles.set(true);
    this.processService.upsertRoleAssignees(this.projectId, { items }).subscribe(updated => {
      this.savingRoles.set(false);
      if (updated) {
        this.rolesDirty.set(false);
        this.msgs.add({ severity: 'success', summary: 'Rôles enregistrés', detail: 'Remplaçants mis à jour.' });
        this.loadRoles();
      }
    });
  }

  // ── Absence ──
  mainAbsence(r: RoleNeeded): string | null {
    return this.absenceMap[r.currentAssignee?.userId ?? ''] ?? null;
  }

  subAbsence(r: RoleNeeded): string | null {
    return this.absenceMap[r.currentAssignee?.substituteUserId ?? ''] ?? null;
  }

  fmtDate(iso: string | null): string {
    if (!iso) return '—';
    const d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    return d.toLocaleDateString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  openAbsencePicker(userId: string): void {
    this.absencePickerUserId.set(userId);
    this.absenceDate = null;
    this.absenceError.set('');
  }

  confirmAbsence(): void {
    const userId = this.absencePickerUserId();
    if (!userId || !this.absenceDate) return;
    const iso = this.absenceDate.toISOString().split('T')[0];
    this.userService.setAbsence(userId, iso).subscribe(result => {
      if (result.ok) {
        this.absencePickerUserId.set(null);
        this.msgs.add({ severity: 'success', summary: 'Absence enregistrée', detail: 'Le remplaçant prendra le relais.' });
        // V44 — reload roles from the server so the absence badge reflects
        // the persisted state (not only the in-memory map).
        this.loadRoles();
      } else {
        this.absenceError.set(result.error ?? 'Erreur');
      }
    });
  }

  endAbsence(userId: string | null): void {
    if (!userId) return;
    this.userService.setAbsence(userId, null).subscribe(result => {
      if (result.ok) {
        this.msgs.add({ severity: 'success', summary: 'Fin d\u2019absence', detail: 'La personne est de nouveau disponible.' });
        // V44 — reload roles from the server so the absence badge clears from
        // the persisted state (not only the in-memory map).
        this.loadRoles();
      } else {
        this.msgs.add({ severity: 'error', summary: 'Erreur', detail: result.error ?? 'Erreur' });
      }
    });
  }

  // ── Block 2 ──
  private loadRules(): void {
    this.processService.listProjectTemplateConfigs(this.projectId).subscribe(cfgs => {
      this.ruleConfigs = cfgs;
      this.ruleErrors = {};
    });
  }

  onRuleToggle(cfg: ProjectTemplateConfig, enabled: boolean): void {
    const prevEnabled = cfg.enabled;
    cfg.enabled = enabled;
    this.ruleErrors[cfg.templateKey] = '';
    this.saveRule(cfg, prevEnabled, null);
  }

  saveThreshold(cfg: ProjectTemplateConfig): void {
    const prevThreshold = cfg.amountThresholdOverride;
    this.ruleErrors[cfg.templateKey] = '';
    this.saveRule(cfg, null, prevThreshold);
  }

  private saveRule(cfg: ProjectTemplateConfig, prevEnabled: boolean | null, prevThreshold: number | null | undefined): void {
    this.processService.assignTemplate(this.projectId, {
      templateKey: cfg.templateKey,
      enabled: cfg.enabled,
      defaultForSubjectType: cfg.defaultForSubjectType ?? null,
      amountThresholdOverride: cfg.amountThresholdOverride ?? null,
    }).subscribe(result => {
      if (result.ok) {
        this.msgs.add({ severity: 'success', summary: 'Règle mise à jour', detail: cfg.templateName });
      } else {
        // Revert to previous values.
        if (prevEnabled !== null) cfg.enabled = prevEnabled;
        if (prevThreshold !== undefined) cfg.amountThresholdOverride = prevThreshold ?? null;
        this.ruleErrors[cfg.templateKey] = result.error ?? 'Erreur';
      }
    });
  }

  toNum(v: any): number | null {
    if (v === null || v === undefined || v === '') return null;
    const n = typeof v === 'number' ? v : Number(String(v).replace(',', '.'));
    return isNaN(n) ? null : n;
  }

  // ── Block 3 ──
  private loadPending(): void {
    this.loadingPending.set(true);
    this.processService.listPendingApprovals(this.projectId).subscribe(list => {
      this.pending.set(list);
      this.loadingPending.set(false);
    });
  }
}
