import { Routes } from '@angular/router';
import { authGuard } from '@app/core/auth.guard';
import { permGuard, ownerGuard } from '@app/core/perm.guard';
import { PERM } from '@app/core/permissions';

export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./features/login/login.component').then(m => m.LoginComponent) },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () => import('./features/shell/shell.component').then(m => m.ShellComponent),
    children: [
      { path: '', loadComponent: () => import('./features/home/home.component').then(m => m.HomeComponent) },
      { path: 'tickets', canActivate: [permGuard(PERM.TICKET_READ)],
        loadComponent: () => import('./features/tickets/tickets.component').then(m => m.TicketsComponent) },
      { path: 'tickets/:id', canActivate: [permGuard(PERM.TICKET_READ)],
        loadComponent: () => import('./features/tickets/ticket-detail.component').then(m => m.TicketDetailComponent) },
      { path: 'projects', canActivate: [permGuard(PERM.PROJECT_READ)],
        loadComponent: () => import('./features/projects/projects.component').then(m => m.ProjectsComponent) },
      { path: 'projects/:id', canActivate: [permGuard(PERM.PROJECT_READ)],
        loadComponent: () => import('./features/projects/project-detail.component').then(m => m.ProjectDetailComponent) },
      { path: 'projects/:projectId/folders/:folderId', canActivate: [permGuard(PERM.DOCUMENT_READ)],
        loadComponent: () => import('./features/document/documents.component').then(m => m.DocumentsComponent) },
      { path: 'projects/:id/register', canActivate: [permGuard(PERM.DOCUMENT_READ)],
        loadComponent: () => import('./features/document/register.component').then(m => m.RegisterComponent) },
      { path: 'documents/:id', canActivate: [permGuard(PERM.DOCUMENT_READ)],
        loadComponent: () => import('./features/document/document-detail.component').then(m => m.DocumentDetailComponent) },
      { path: 'users', canActivate: [permGuard(PERM.USER_CREATE)],
        loadComponent: () => import('./features/users/users.component').then(m => m.UsersComponent) },
      { path: 'roles', canActivate: [permGuard(PERM.ROLE_MANAGE)],
        loadComponent: () => import('./features/roles/roles.component').then(m => m.RolesComponent) },
      { path: 'roles/:id', canActivate: [permGuard(PERM.ROLE_MANAGE)],
        loadComponent: () => import('./features/roles/role-detail.component').then(m => m.RoleDetailComponent) },
      { path: 'audit', canActivate: [permGuard(PERM.AUDIT_VIEW)],
        loadComponent: () => import('./features/audit/audit.component').then(m => m.AuditComponent) },
      { path: 'processus',
        loadComponent: () => import('./features/process/process-hub.component').then(m => m.ProcessHubComponent) },
      { path: 'mes-taches',
        loadComponent: () => import('./features/process/my-tasks.component').then(m => m.MyTasksComponent) },
      { path: 'processes/start',
        loadComponent: () => import('./features/process/start-process.component').then(m => m.StartProcessComponent) },
      { path: 'processes/builder', canActivate: [ownerGuard],
        loadComponent: () => import('./features/process/process-builder.component').then(m => m.ProcessBuilderComponent) },
      { path: 'processes/builder/:id', canActivate: [ownerGuard],
        loadComponent: () => import('./features/process/process-builder.component').then(m => m.ProcessBuilderComponent) },
      { path: 'processes/:id',
        loadComponent: () => import('./features/process/process-timeline.component').then(m => m.ProcessTimelineComponent) },
      // TASK 2 — Approval desk: validation screen for a CASH_MOVEMENT step.
      // Reached from Mes tâches → "Voir le dossier". Single round-trip.
      { path: 'processus/etapes/:stepInstanceId/validation',
        loadComponent: () => import('./features/process/approval-desk.component').then(m => m.ApprovalDeskComponent) },
      // Trésorerie — read access required (write actions re-checked in components:
      // CASH_DRAFT to save drafts, CASH_POST to post, CASH_VOID to void, PARTY_MANAGE for parties)
      { path: 'tresorerie', canActivate: [permGuard(PERM.CASH_READ)],
        loadComponent: () => import('./features/cash/cash-hub.component').then(m => m.CashHubComponent) },
      // TASK 5 — Espace Finance: caisses + pending + monthly CSV export.
      // Visible to anyone with CASH_READ (FINANCE + OWNER both have it).
      { path: 'tresorerie/finance', canActivate: [permGuard(PERM.CASH_READ)],
        loadComponent: () => import('./features/cash/finance-hub.component').then(m => m.FinanceHubComponent) },
      { path: 'tresorerie/caisses', canActivate: [permGuard(PERM.CASH_READ)],
        loadComponent: () => import('./features/cash/pot-list.component').then(m => m.PotListComponent) },
      { path: 'tresorerie/caisses/:potId', canActivate: [permGuard(PERM.CASH_READ)],
        loadComponent: () => import('./features/cash/pot-detail.component').then(m => m.PotDetailComponent) },
      { path: 'tresorerie/operations/new', canActivate: [permGuard(PERM.CASH_READ)],
        loadComponent: () => import('./features/cash/movement-form.component').then(m => m.MovementFormComponent) },
      { path: 'tresorerie/operations/:id', canActivate: [permGuard(PERM.CASH_READ)],
        loadComponent: () => import('./features/cash/movement-detail.component').then(m => m.MovementDetailComponent) },
      { path: 'tresorerie/operations/:id/edit', canActivate: [permGuard(PERM.CASH_READ)],
        loadComponent: () => import('./features/cash/movement-form.component').then(m => m.MovementFormComponent) },
      { path: 'tresorerie/parties', canActivate: [permGuard(PERM.CASH_READ)],
        loadComponent: () => import('./features/cash/party-list.component').then(m => m.PartyListComponent) },
      { path: 'tresorerie/parties/:id', canActivate: [permGuard(PERM.CASH_READ)],
        loadComponent: () => import('./features/cash/party-detail.component').then(m => m.PartyDetailComponent) },
    ]
  },
  { path: '**', redirectTo: '' }
];
