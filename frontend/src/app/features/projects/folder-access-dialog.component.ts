import { Component, inject, Input, Output, signal, EventEmitter, SimpleChanges, SimpleChange, OnChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { first } from 'rxjs/operators';

import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { TooltipModule } from 'primeng/tooltip';
import { ChipModule } from 'primeng/chip';
import { BadgeModule } from 'primeng/badge';
import { MessageService } from 'primeng/api';

import { UserOptionDto, UserDto } from '@app/core/models/user.model';
import { UserService } from '@app/core/services/user.service';
import { FolderAccessService } from '@app/core/services/folder-access.service';

import { COMPANY_ROLE_OPTS, COMPANY_ROLE_LABELS, FolderAccess, FolderAccessGrant } from '@app/core/models/folder.model';
import { ProjectFolder } from '@app/core/models/project.model';
import { roleVisual, avatarGradient, initials } from '@app/shared/hydra-design';
import { LoadErrorComponent } from '@app/shared/load-error.component';

const D = [true, false, false, false, false];

@Component({
  selector: 'app-folder-access-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, DialogModule, InputTextModule, ButtonModule, TagModule, TooltipModule, ChipModule, BadgeModule, LoadErrorComponent],
  templateUrl: './folder-access-dialog.component.html',
  styleUrl: './folder-access-dialog.component.scss'
})
export class FolderAccessDialogComponent implements OnChanges {
  readonly roleOpts = COMPANY_ROLE_OPTS;
  private readonly fa = inject(FolderAccessService);
  private readonly us = inject(UserService);
  private readonly msgs = inject(MessageService);
  @Output() saved = new EventEmitter<string>();
  @Output() visibleChange = new EventEmitter<boolean>();
  @Input() visible = false;
  @Input() projectId = '';
  @Input() folder: ProjectFolder | null = null;
  readonly loading = signal(false);
  /** Rules failed to load: shown with retry, and saving is blocked (it would overwrite them). */
  readonly accessError = signal(false);
  readonly usersError = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly access = signal<FolderAccess[]>([]);
  readonly users = signal<UserDto[]>([]);
  readonly userResults = signal<UserDto[]>([]);
  readonly searchText = signal('');
  readonly selectedUserIds = signal<string[]>([]);
  readonly rolePerms = signal<Record<string, boolean[]>>({});

  readonly permIcons = ['pi pi-eye', 'pi pi-upload', 'pi pi-download', 'pi pi-check', 'pi pi-trash'];
  readonly permLabels = ['Lecture', 'Téléverser', 'Télécharger', 'Approuver', 'Supprimer'];

  ngOnChanges(changes: SimpleChanges): void {
    const ch = changes['visible'] as SimpleChange | undefined;
    if (ch && ch.currentValue === true) {
      this.reload();
    }
  }

  ngOnInit(): void { if (this.folder && this.visible) { this.reload(); } }

  private reload(): void {
    if (!this.folder || !this.projectId) { return; }
    this.loading.set(true);
    this.saveError.set(null);
    this.selectedUserIds.set([]);
    this.loadAccess();
    this.fetchUsers();
  }

  loadAccess(): void {
    if (!this.folder) { return; }
    this.loading.set(true);
    this.accessError.set(false);
    this.fa.list(this.projectId, this.folder.id).pipe(first()).subscribe({
      next: g => { this.access.set(g ?? []); this.hydrate(); this.loading.set(false); },
      error: () => { this.access.set([]); this.accessError.set(true); this.loading.set(false); }
    });
  }

  /** Fetch FULL user list (with roleCodes) — needed to display business roles. */
  fetchUsers(): void {
    this.usersError.set(false);
    this.us.list().pipe(first()).subscribe({
      next: l => {
        const list = l ?? [];
        this.users.set(list);
        this.userResults.set(list);
      },
      error: () => { this.users.set([]); this.userResults.set([]); this.usersError.set(true); }
    });
  }

  private hydrate(): void {
    const p: Record<string, boolean[]> = {};
    const userIds: string[] = [];
    for (const g of this.access()) {
      if (g.userId == null && g.roleCode) { p[g.roleCode] = [g.canRead, g.canUpload, g.canDownload, g.canApprove, g.canDelete]; }
      if (g.userId != null) { userIds.push(g.userId); }
    }
    this.rolePerms.set(p);
    this.selectedUserIds.set(userIds);
  }

  roleLabel(code: string): string { return COMPANY_ROLE_LABELS[code] ?? code; }
  roleIcon(code: string): string { return roleVisual(code).icon; }
  roleColor(code: string): string { return roleVisual(code).color; }
  roleSoft(code: string): string { return roleVisual(code).soft; }
  roleIsActive(code: string): boolean { return this.access().some(g => g.userId == null && g.roleCode === code); }
  rolePermsFor(code: string): boolean[] { return this.rolePerms()[code] ?? [...D]; }
  setRolePerm(code: string, idx: number, value: boolean): void {
    this.rolePerms.update(prev => { const next = [...(prev[code] ?? [...D])]; next[idx] = !!value; if (idx !== 0 && (next[1] || next[2] || next[3] || next[4])) { next[0] = true; } return { ...prev, [code]: next }; });
  }
  isFinanceFolder(): boolean { return !!(this.folder?.code ?? '').startsWith('06'); }
  roleBlocked(code: string): boolean { if (!this.isFinanceFolder()) { return false; } return code === 'SOUS_TRAITANT' || code === 'CHEF_CHANTIER' || code === 'CONDUCTEUR'; }
  financeWarning(): string | null {
    if (!this.isFinanceFolder()) { return null; }
    const has = this.access().some(a => a.userId == null && a.roleCode === 'SOUS_TRAITANT');
    return has ? 'Le dossier Finance est verrouillé : les accès par sous-traitant sont interdits.' : null;
  }
  searchUsers(): void {
    const q = this.searchText().trim().toLowerCase(); const list = this.users();
    if (!q) { this.userResults.set(list); return; }
    this.userResults.set(list.filter(u => (u.fullName ?? '').toLowerCase().includes(q) || (u.email ?? '').toLowerCase().includes(q)));
  }
  isSelected(userId: string): boolean { return this.selectedUserIds().includes(userId); }
  toggleUser(userId: string): void { this.selectedUserIds.update(ids => ids.includes(userId) ? ids.filter(i => i !== userId) : [...ids, userId]); }

  selectedUserChips(): UserDto[] {
    return this.selectedUserIds().map(id => this.users().find(u => u.id === id)).filter((u): u is UserDto => !!u);
  }

  /** Main role = first business role (priority OWNER > CHEF_PROJET > others). */
  userMainRole(u: UserDto): string {
    if (!u.roleCodes?.length) return '';
    const priority = ['OWNER', 'DIRECTION', 'CHEF_PROJET', 'CONDUCTEUR', 'INGENIEUR', 'BUREAU',
      'QA', 'DOC_CTRL', 'ACHAT', 'FINANCE', 'CHEF_CHANTIER', 'HSE', 'SOUS_TRAITANT'];
    for (const p of priority) {
      if (u.roleCodes.includes(p)) return p;
    }
    return u.roleCodes[0];
  }

  /** Avatar gradient for a user (deterministic from UUID). */
  userGradient(u: UserDto): string { return avatarGradient(u.id); }
  userInitials(u: UserDto): string { return initials(u.fullName); }

  save(): void {
    if (!this.folder) { return; }
    const folder = this.folder;
    this.loading.set(true); this.saveError.set(null);
    const existing = new Map<string, FolderAccess>();
    for (const g of this.access()) { existing.set(g.userId != null ? `user:${g.userId}` : `role:${g.roleCode}`, g); }
    const posts: any[] = []; const deletes: string[] = [];
    for (const code of COMPANY_ROLE_OPTS) {
      const p = this.rolePermsFor(code);
      if (p.some(v => v)) { posts.push(this.fa.grant(this.projectId, folder.id, { roleCode: code, userId: null, canRead: true, canUpload: p[1], canDownload: p[2], canApprove: p[3], canDelete: p[4] })); }
      else { const e = existing.get(`role:${code}`); if (e) { deletes.push(e.id); } }
    }
    for (const id of this.selectedUserIds()) { if (!existing.get(`user:${id}`)) { posts.push(this.fa.grant(this.projectId, folder.id, { userId: id, roleCode: null, canRead: true, canUpload: false, canDownload: true, canApprove: false, canDelete: false })); } }
    for (const g of this.access()) { if (g.userId != null && !this.selectedUserIds().includes(g.userId)) { deletes.push(g.id); } }

    const requests = [...posts, ...deletes.map(id => this.fa.revoke(this.projectId, folder.id, id) as any)];
    if (requests.length === 0) {
      this.msgs.add({ severity: 'info', summary: 'Aucun changement', detail: 'Aucun accès n\'a été modifié.' });
      this.loading.set(false);
      return;
    }
    forkJoin(requests).pipe(first()).subscribe({
      next: () => { this.loadAccess(); this.saved.emit(folder.id); this.loading.set(false); this.msgs.add({ severity: 'success', summary: 'Accès enregistrés', detail: 'Les droits d’accès ont été enregistrés.' }); },
      error: (err) => { const msg = err.error?.message ?? 'Échec de la sauvegarde des accès.'; this.saveError.set(msg); this.loading.set(false); this.msgs.add({ severity: 'error', summary: 'Erreur', detail: msg }); }
    });
  }
}
