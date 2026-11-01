# HYDRA — map of the code

Use this file to find where things are. Open only the files a task needs.
Sizes are approximate line counts; files over 1,000 lines must be read by
section (grep the method, then read around it).

## Backend — `backend/src/main/java/tn/hydra/`

| Package | Main files | What it does |
|---------|-----------|--------------|
| `project` | `ProjectService` (~670), `ProjectController`, `OuvrageService`, `OuvrageController`, `OuvrageType`, `BordereauService`, `BordereauController`, `BordereauImportService` (~845) | Chantiers, their ouvrages, bordereau versions. `BordereauImportService.importExcel` imports an Excel bordereau (multi-sheet, warnings, specs, ouvrage suggestion, fourniture ↔ pose pairs saved both ways in `paired_line_id`) |
| `cash` | `CashService` (~2,000), `CashController`, `PaymentFacade`, `PartyService`, `PaymentMode`, `MovementNature` | Caisses, cash movements, parties (fournisseurs, sous-traitants), payment submission. `PaymentFacade.submitPayment` asks `ApprovalRuleService` which rule applies |
| `process` | `ProcessService` (~3,200, pure JdbcTemplate — never read it whole), `ProcessController`, `ApprovalRuleService` (~210), `RuleMatcher` (~225, pure Java) | Approval engine: templates (= rules with conditions, priority), steps (ANY/ALL, due days), instances, approval desk, my-tasks, per-chantier rules (opt-out), role assignees with substitute, absences |
| `purchase` | `PurchaseOrderService` (~900), `BonLivraisonService` (~780), `FactureService` (~700) + domain/, repository/ | BC → BL → facture, match overview |
| `stock` | `StockService` (~1,130), `StockController` + domain/, repository/ | Items, locations, moves, balances, reversals |
| `document` | service/`DocumentService`, `LocalFileStorage`, `S3FileStorage`, controller/, domain/, dto/ | Documents, revisions, folders, file storage |
| `security` | `PermissionService`, `FolderAccessService`, `ToxicPairService`, `HydraUserDetailsService`, `SessionRevoker` | Permissions, folder ACL, segregation of duties |
| `audit` | `EventLogService`, `AuditService`, `AuditController` | Append-only event log |
| `ticket` | `TicketService`, `TicketController` | Issue tracking |
| `user`, `role`, `auth` | `UserService`, `RoleService`, `AuthController` | Users (incl. absence `absent_until`), roles, login |
| `config` | `SecurityConfig`, `CorsConfig`, `BootstrapRunner`, `DemoDataRunner`, `DevDataSeedRunner` | Security, CORS, seeding |
| `common`, `exception` | `GlobalExceptionHandler`, `BusinessRuleException` | Errors → HTTP |

Migrations: `backend/src/main/resources/db/migration/`, last on main = **V44__app_user_absent_until.sql**.
Never edit an existing migration; the next one is V45.
Recent ones: V38 rule columns · V39 FINANCE_ONLY rule · V40 step approvals (ALL mode) ·
V41 project_member default · V42 role substitute · V43 opt-out seed · V44 absences.

Key behaviours to remember:
- A rule is enabled on a chantier unless `project_process_template.enabled = false` (opt-out).
- The last unconditional payment rule of a chantier can be neither disabled nor given a threshold.
- A role's substitute acts when the main person is inactive or absent (`absent_until >= today`).

Tests: `backend/src/test/java/tn/hydra/<package>/`. Test DB: `hydra_test` on
127.0.0.1:5432 (see `backend/src/test/resources/application.properties`).

## Frontend — `frontend/src/app/`

| Folder | Main files | What it does |
|--------|-----------|--------------|
| `core/models/` | `project.model.ts`, `ouvrage.model.ts`, `cash.model.ts` (~560), `stock.model.ts` (~740), `process.model.ts` (~490) | TypeScript types + mappers |
| `core/services/` | one service per backend module (`project`, `cash`, `stock`, `purchase`, `process`, `document`, …) | HTTP calls |
| `core/` | `auth.service.ts`, `perm.guard.ts`, `permissions.ts`, `api.interceptor.ts` | Auth, permission constants and guards |
| `features/projects/` | `projects.component` (list + creation wizard), `project-detail.component` (~535 ts; tabs 0 Dossiers, 3 Ouvrages, 1 Équipe, 2 Processus, 4 Règles, 5 Bordereau), `ouvrages-tab.component`, `bordereau-tab.component` (~305: Excel import + preview), `project-rules-tab.component` (~415: rôles + remplaçants + absences, règles actives, en attente), `project-role-assignees` (read-only), `folder-access-dialog` | Chantiers |
| `features/cash/` | `movement-form` (~875), `movement-detail`, `finance-hub`, `cash-hub`, pots, parties | Trésorerie |
| `features/stock/` | `move-form` (~720), `stock-list`, `stock-moves`, BC / BL / facture list-form-detail, `match-overview` | Stock and achats |
| `features/process/` | `process-builder` (~640: 4-screen rule builder Quoi/Quand/Qui/Aperçu), `process-hub` ("Modèles prêts"), `my-tasks` ("Remplace …"), `approval-desk`, `process-timeline`, `start-process` | Rules and approvals |
| `features/home/` | `home.component` (~510) | Home page |
| `features/shell/` | `shell.component.ts` | Sidebar menu (MENU array) and top bar |
| `shared/` | `hydra-design.ts`, `chantier-visual.ts`, `document-file-actions.component.ts`, `empty-state.component.ts` | Design system and shared components |
| root | `app.routes.ts`, `app.config.ts` | Routes and providers |

## Docs — `docs/`
- `AI-RULES.md` — rules for every change
- `MAP.md` — this file (update it when a module or big file is added)
- `BORDEREAU-ANALYSIS.md` — bordereau structure and import rules

## Never read
`frontend/node_modules`, `frontend/dist`, `frontend/.angular`, `backend/target`,
`backend/data`, `upload/`, `download/`, `scripts/`, `tool-results/`, PDFs,
`package-lock.json`, `worklog.md` (history only).
