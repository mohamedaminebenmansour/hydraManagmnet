# HYDRA — map of the code

Use this file to find where things are. Open only the files a task needs.
Sizes are approximate line counts; files over 1,000 lines must be read by
section (grep the method, then read around it).

## Backend — `backend/src/main/java/tn/hydra/`

| Package | Main files | What it does |
|---------|-----------|--------------|
| `project` | `ProjectService` (~670), `ProjectController`, `OuvrageService`, `OuvrageController`, `OuvrageType`, `BordereauService` (~650), `BordereauController`, `BordereauImportService` (~1,075 — read by section) | Chantiers, their ouvrages, bordereau versions. `BordereauImportService.importExcel` imports an Excel bordereau (multi-sheet, warnings, specs, ouvrage suggestion — réservoir by `capacite_m3`, brise-charge by `sous_type`, other types only if exactly one — fourniture ↔ pose pairs saved both ways in `paired_line_id`, one fourniture per pose). The Excel file is kept as ONE document "Bordereau des prix" (ETUDE) in folder `02_TECH.BORDEREAUX` (via `FolderFilingService`), one revision per import, via `DocumentService.upload` / `RevisionService.uploadNewRevision`; storage failure cancels the import. `PUT /api/projects/{id}/bordereau/versions/{vid}/lines/ouvrage` = `BordereauService.assignOuvrage` (bulk ouvrage on DRAFT lines) |
| `cash` | `CashService` (~2,000), `CashController`, `PaymentFacade`, `PartyService`, `PaymentMode`, `MovementNature` | Caisses, cash movements, parties (fournisseurs, sous-traitants), payment submission. `PaymentFacade.submitPayment` asks `ApprovalRuleService` which rule applies |
| `process` | `ProcessService` (~3,200, pure JdbcTemplate — never read it whole), `ProcessController`, `ApprovalRuleService` (~210), `RuleMatcher` (~225, pure Java) | Approval engine: templates (= rules with conditions, priority), steps (ANY/ALL, due days), instances, approval desk, my-tasks, per-chantier rules (opt-out), role assignees with substitute, absences |
| `purchase` | `PurchaseOrderService` (~900), `BonLivraisonService` (~780), `FactureService` (~700) + domain/, repository/ | BC → BL → facture, match overview |
| `stock` | `StockService` (~1,130), `StockController` + domain/, repository/ | Items, locations, moves, balances, reversals |
| `document` | service/`DocumentService`, `FolderFilingService`, `LocalFileStorage`, `S3FileStorage`, controller/, domain/, dto/ | Documents, revisions, folders, file storage. Folders = 2 levels by stable code (`02_TECH`, `02_TECH.BORDEREAUX`); `FolderFilingService.folderFor(projectId, code)` finds or creates a chantier folder from the template. `GET /api/system/storage` (owner, `StorageStatusService`) = storage type + persistent (shell shows a red banner when not) |
| `mail` | `MailService` (interface), `MailServiceSmtp`, `MailServiceDisabled`, `MailConfig` | Optional email: SMTP when `HYDRA_MAIL_HOST` + `HYDRA_MAIL_FROM` set, disabled otherwise. `sendAfterCommit` defers to after-commit. 3 templates (reset/invite/changed). |
| `security` | `PermissionService`, `FolderAccessService`, `ToxicPairService`, `HydraUserDetailsService`, `SessionRevoker`, `PasswordPolicy` (U1), `TemporaryPasswordGenerator` (U1), `PasswordChangeRequiredFilter` (U1) | Permissions, folder ACL, segregation of duties, password policy (min 12, blocklist, personal info), temp password generation, forced change at first login |
| `audit` | `EventLogService`, `AuditService`, `AuditController` | Append-only event log |
| `event` | `DomainEventService`, `EventType`, `DomainEventController` | Domain events (table domain_event, V47): one row per business action, written in the action's transaction by `DomainEventService.publish` (payment submitted, cash posted, step completed, BC created, BL received, facture recorded, bordereau imported / activated, document uploaded). Nothing consumes them yet. `GET /api/events` (owner) feeds the "Journal des événements" page. Not the audit log |
| `organization` | `TunisianIds` (pure Java MF/RIB validators), `CompanyProfileService`, `CompanyProfileController`, `GroupementService`, `GroupementController` | Company profile (legal identity, bank, logo) + groupements (joint ventures with members and shares). `GET/PUT /api/company`, `POST/GET/DELETE /api/company/logo`, `GET/POST/PUT /api/company/groupements`. Permission `ORG_SETTINGS` (OWNER only). Migration V54. |
| `controle` | `ControleImportFile` (records matching convert.py JSON), `ControleImportService` (~430), `ControleImportController` (~80), `ControleExcelReader` (~780, Java port of `tools/controle/convert.py`: .xlsx → `ImportFile`, same ids), `ControleArticleRules` (pure Java port of analyse.py plain/article/piece), `ControleArticleService` + `ControleArticleController` | Archive of the company's cash Excel. `POST /api/controle/import` (PERM_CTL_IMPORT, multipart part "file", 20 MB max) parses + validates + persists a convert.py JSON export in ONE transaction (deletes the previous import's rows for the org, then re-inserts; NEVER touches `ctl_justification` / `ctl_validation`). `GET /api/controle/imports` (PERM_CTL_READ) — last 10 imports of my org. **Never touches `cash_pot` / `cash_movement`.** Tables: `ctl_import`, `ctl_account`, `ctl_line`, `ctl_st_account`, `ctl_st_line`, `ctl_justification` (no FK to `ctl_line` on purpose — a justification must survive a re-import), `ctl_validation`. Permissions `CTL_READ`, `CTL_JUSTIFY`, `CTL_IMPORT` (OWNER only). Migration V55. Articles (V56): at import every ctl_line gets raw_key = plain(cat), article = org alias (`ctl_article_alias`) or the analyse.py rule, piece = rule; `GET /api/controle/articles` (PERM_CTL_READ), `PUT /api/controle/articles/aliases` {rawKey, article, version} (PERM_CTL_JUSTIFY, 409 STALE, updates ctl_line.article in the same transaction). Tests: `ControleImportServiceTest` (CI-01..CI-08), `ControleExcelReaderTest` (CX-01..CX-08, builds a fake workbook in code), `ControleArticleRulesTest` (CA-01, CA-02, CA-07), `ControleArticleServiceTest` (CA-03..CA-06). |
| `ticket` | `TicketService` (~1070), `TicketController` (~260), `TicketPhotoService` (~340, photos + deliverable files), `TicketType`, `TicketWorkflow` | Signalements: 5 types (PROBLEME/RESERVE/DEMANDE/TACHE/VISA_DOCUMENT), categories, origine (RESERVE), ouvrage/party links; universal lifecycle OPEN→IN_PROGRESS→RESOLVED→CLOSED (+CANCELLED) for the first four; VISA keeps DRAFT→…→AS_BUILT. TACHE = office task, project optional (null = « Bureau »), is_private (creator+assignee only). Central visibility rule `canRead` + `readVisibilityFragment` (A4) used by get/list/search/summary/actions/timeline/comments/photos/files. Deliverable files (POST /{id}/files, phase DELIVERABLE) filed by category. `GET /api/tickets/catalog` / `/search` / `/summary` / `/{id}/actions` / `/{id}/timeline` / `/{id}/photos` / `/{id}/files`. |
| `mail` | `MailService` (interface), `MailServiceSmtp`, `MailServiceDisabled`, `MailConfig` | Optional email: SMTP when `HYDRA_MAIL_HOST` + `HYDRA_MAIL_FROM` set, disabled otherwise. `sendAfterCommit` defers to after-commit. 3 templates (reset/invite/changed). |
| `security` | `PermissionService`, `FolderAccessService`, `ToxicPairService`, `HydraUserDetailsService`, `SessionRevoker`, `PasswordPolicy` (U1), `TemporaryPasswordGenerator` (U1), `PasswordChangeRequiredFilter` (U1) | Permissions, folder ACL, segregation of duties, password policy (min 12, blocklist, personal info), temp password generation, forced change at first login |
| `user`, `role`, `auth` | `UserService` (~690), `UserController`, `MyProfileController` (U1, `/api/me`), `RoleService` (~250), `RoleController`, `AuthController` (login + last_login_at/first_login_at + password-policy + mail-status), `PasswordTokenService` (U2), `PasswordResetController` (U2), `ForgotPasswordRateLimiter` (U2) | Users (incl. absence, phone, job_title, last_login_at, password_changed_at, first_login_at), roles (with userCount + moduleKeys + confirm-impact + duplicate), login, my profile, forgotten password + invitation tokens. See `backend/docs/users.md`. |
| `config` | `SecurityConfig`, `CorsConfig`, `BootstrapRunner`, `DemoDataRunner` (off by default since T9), `DevDataSeedRunner`, `config/demo/` (`DemoScenarioRunner`, `DemoTachesScenario`, `DemoScenario`, `InMemoryMultipartFile`) | Security, CORS, seeding. `@Profile("demo")` demo pack creates the « HYDRA Démo » company + 9 users + 1 chantier + 11 signalements; never touches the real company. See `docs/DEMO.md`. |
| `common`, `exception` | `GlobalExceptionHandler`, `BusinessRuleException` | Errors → HTTP |

Migrations: `backend/src/main/resources/db/migration/`, last = **V56__controle_articles.sql**.
Never edit an existing migration; the next one is V57.
Recent ones: V38 rule columns · V39 FINANCE_ONLY rule · V40 step approvals (ALL mode) ·
V41 project_member default · V42 role substitute · V43 opt-out seed · V44 absences ·
V45 bordereau_version.source_document_id / source_revision · V46 two-level folder tree
(doc_folder_template.parent_code, sub-folder codes '<main>.<KEY>', 08_SST, backfill function
`hydra_backfill_folder_tree()`, unique (project_id, code) when no duplicates) · V47 domain_event ·
V48 ticket types (type/category/origin/location_text/ouvrage_id/responsible_party_id/
resolution_note/closed_at/closed_by/cancel_reason; type-conditional status check;
indexes on org+project+type+status, org+assignee+status, org+due_at) ·
V49 ticket_attachment (photos: ticket_id/document_id/phase BEFORE|AFTER|OTHER,
unique(ticket_id, document_id), on delete cascade, index org+ticket) ·
V50 ticket_comment (timeline: author_id/kind COMMENT|STATUS_CHANGE|SYSTEM/body/from_status/to_status;
index org+ticket+created_at) ·
V51 ticket TACHE type (type+status check add TACHE; is_private boolean;
ticket_attachment phase DELIVERABLE; project_id already nullable — no change) ·
V52 password_token (id/org_id/user_id/purpose RESET|INVITE/token_hash unique/expires_at/used_at/created_ip;
index user_id+purpose where used_at is null) ·
V53 user_profile_security (app_user: phone, job_title, last_login_at, password_changed_at, first_login_at) ·
V54 company_profile (company_profile: legal identity, bank, logo; groupement + groupement_member with shares;
ORG_SETTINGS permission granted to OWNER; RLS enabled on all 3 tables) ·
V55 controle_tresorerie (ctl_import / ctl_account / ctl_line / ctl_st_account / ctl_st_line /
ctl_justification / ctl_validation; archive of the company's cash Excel; never touches
cash_pot / cash_movement; CTL_READ / CTL_JUSTIFY / CTL_IMPORT permissions granted to OWNER;
RLS enabled on all 7 tables) ·
V56 controle_articles (ctl_line: article / piece / raw_key + index org+raw_key;
ctl_article_alias (org_id, raw_key) → article with version; RLS enabled).

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
| `features/projects/` | `projects.component` (list + creation wizard), `project-detail.component` (~535 ts; tabs 0 Dossiers, 3 Ouvrages, 1 Équipe, 2 Processus, 4 Règles, 5 Bordereau), `ouvrages-tab.component`, `bordereau-tab.component` (~390: Excel import + preview, Ouvrage column: dropdown + "Affecter à…" while DRAFT, read-only name otherwise), `project-rules-tab.component` (~415: rôles + remplaçants + absences, règles actives, en attente), `project-role-assignees` (read-only), `folder-access-dialog` | Chantiers |
| `features/cash/` | `movement-form` (~875), `movement-detail`, `finance-hub`, `cash-hub`, pots, parties | Trésorerie |
| `features/controle/` | `controle-layout` (tabs), `lines-table` (~440, columns Article / Pièce), `controle-articles` (« Regroupement des articles », `/controle/articles-regroupement`, CTL_JUSTIFY), import, comptes, projets, sous-traitants, justificatifs; behaviour reference in `docs/controle/prototype.html` | Contrôle de trésorerie — archive of the company's cash Excel |
| `features/stock/` | `move-form` (~720), `stock-list`, `stock-moves`, BC / BL / facture list-form-detail, `match-overview` | Stock and achats |
| `features/process/` | `process-builder` (~640: 4-screen rule builder Quoi/Quand/Qui/Aperçu), `process-hub` ("Modèles prêts"), `my-tasks` ("Remplace …"), `approval-desk`, `process-timeline`, `start-process` | Rules and approvals |
| `features/home/` | `home.component` (~510) | Home page |
| `features/events/` | `events.component` | "Journal des événements" (owner only, `/evenements`) |
| `features/shell/` | `shell.component.ts` | Sidebar menu (MENU array) and top bar |
| `shared/` | `hydra-design.ts`, `chantier-visual.ts`, `document-file-actions.component.ts`, `empty-state.component.ts` | Design system and shared components |
| root | `app.routes.ts`, `app.config.ts` | Routes and providers |

## Docs — `docs/`
- `AI-RULES.md` — rules for every change
- `MAP.md` — this file (update it when a module or big file is added)
- `BORDEREAU-ANALYSIS.md` — bordereau structure and import rules
- `SECURITY.md` — what is protected and the owner's security checklist
- `backend/docs/users.md` — user accounts: password policy, statuses, endpoints
- `DEMO.md` — how to switch on the « HYDRA Démo » company + scenario pack
- `TEST-CONTROLE.md` — manual test script for the Contrôle de trésorerie module
- `controle/PLAN-V2.md` — Contrôle v2 plan: business rules, screens, phases
- `controle/prototype.html`, `controle/sample.json` — Contrôle UI prototype and FAKE import sample

## Tools — `tools/controle/`
- `convert.py` — converts the company's cash Excel to the import JSON (line ids = sha1 of sheet|date|e|d|designation)
- `analyse.py` — reference for Contrôle business rules; the app must give the same numbers
- `compare.py` — compares convert.py's JSON with the Java reader's (`ControleExcelDumpTest`, local only, skipped in CI)
- `README.md` — how to run the scripts and the Java/Python comparison

## Never read
`frontend/node_modules`, `frontend/dist`, `frontend/.angular`, `backend/target`,
`backend/data`, `upload/`, `download/`, `scripts/`, `tool-results/`, PDFs,
`package-lock.json`, `worklog.md` (history only).
