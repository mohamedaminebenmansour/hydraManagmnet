# HYDRA — Complete Project Handoff Document

> **Purpose:** Give another AI/chat (or developer) the full picture of what HYDRA is, how it's built, what every module does, and where the recent work landed. Read this top-to-bottom and you'll be operational on the codebase.
>
> **Last updated:** 2026-10-31
> **Latest commit on main:** `8407a41 fix(build): retry loop bug + Aliyun mirror`

---

## Table of Contents

1. [What HYDRA is](#1-what-hydra-is)
2. [Tech stack](#2-tech-stack)
3. [Repository layout](#3-repository-layout)
4. [Backend architecture](#4-backend-architecture)
5. [Frontend architecture](#5-frontend-architecture)
6. [Database schema](#6-database-schema)
7. [Business domains & flows](#7-business-domains--flows)
8. [Security & permissions model](#8-security--permissions-model)
9. [Process engine](#9-process-engine)
10. [Design system](#10-design-system)
11. [Deployment](#11-deployment)
12. [Recent work (Phase B — doc-actions + build resilience)](#12-recent-work)
13. [Known issues & limitations](#13-known-issues--limitations)
14. [Operational recipes](#14-operational-recipes)
15. [Glossary (French BTP terms)](#15-glossary)

---

## 1. What HYDRA is

**HYDRA** is a multi-tenant construction ERP ("Bâtiment et Travaux Publics" / BTP) aimed at **Tunisian construction companies** — specifically those doing hydraulic infrastructure work (water adduction, irrigation, forage, pumping stations, assainissement, bassins, rehabilitation). The name HYDRA reflects the hydraulic domain.

**Target user:** A small-to-medium Tunisian BTP company (5–50 employees) that today runs on Excel + WhatsApp + paper. The product replaces the Excel shadow-IT with a structured, audited, multi-user system while keeping the speed and familiarity of a spreadsheet-like UI.

**Multi-tenant:** Each `organization` (company) is isolated by `org_id`. Row-Level Security (RLS) on PostgreSQL enforces isolation at the database level. Users belong to one org; the org OWNER has all permissions.

**Pilot stage:** As of October 2026, HYDRA is in pilot — deployed to Render (backend) + Vercel (frontend) + Supabase (PostgreSQL), with a single Tunisian BTP company testing it on real chantiers.

---

## 2. Tech stack

| Layer | Technology | Version |
|---|---|---|
| **Backend** | Spring Boot | 4.1.1 (latest, Java 21) |
| **ORM** | Spring Data JPA (Hibernate) + JdbcTemplate for the process engine | — |
| **Database** | PostgreSQL 16 (Supabase in prod, local PG in dev) | — |
| **Migrations** | Flyway | V1 → V35 |
| **Auth** | Spring Security 7 + BCrypt + Spring Session JDBC (12h timeout) + CSRF cookie | — |
| **API style** | REST, JSON, camelCase (Jackson default), multipart for file uploads | — |
| **Build** | Maven 3.9 (via Docker image; `mvnw` wrapper for local dev) | — |
| **MapStruct** | 1.6.3 (DTO mapping, with Lombok binding) | — |
| **Frontend** | Angular 18.2 (standalone components, signals) | — |
| **UI library** | PrimeNG 18 + PrimeIcons (Aura theme) | — |
| **HTTP** | Angular HttpClient with `withFetch()` + XSRF interceptor | — |
| **File storage** | Pluggable: `LocalFileStorage` (dev/pilot) or `S3FileStorage` (Supabase S3 compat) | — |
| **Deploy** | Render (backend Docker) + Vercel (frontend) + Supabase (DB) | — |

**LOC:** ~18,000 Java + ~27,000 TypeScript/HTML/SCSS = ~45,000 lines total.

---

## 3. Repository layout

```
hydraManagmnet/  (single GitHub repo — note the typo in the name, kept for continuity)
├── backend/                    # Spring Boot 4.1 backend
│   ├── Dockerfile             # ← THE Dockerfile Render uses (Root Directory: backend)
│   ├── pom.xml                # Maven config (Spring Boot 4.1.1, Java 21, MapStruct, Lombok, AWS S3 SDK)
│   ├── .mvn/settings.xml      # Maven settings with Aliyun mirror + 429 retry config (build resilience)
│   ├── src/main/java/tn/hydra/
│   │   ├── HydraApplication.java
│   │   ├── audit/             # AuditController, AuditService, EventLogService
│   │   ├── auth/              # AuthController (login/logout/me/change-password)
│   │   ├── cash/              # CashController, CashService, PartyService, PaymentFacade (8 files)
│   │   ├── common/            # PageResponse, BusinessRuleException, GlobalExceptionHandler, AssignedUuid
│   │   ├── config/            # SecurityConfig, CorsConfig, CsrfCookieFilter, BootstrapRunner, AuditingConfig
│   │   ├── document/          # 39 files: controller, domain, dto, repository, service
│   │   ├── exception/         # ResourceNotFoundException, BadRequestException, FileStorageException
│   │   ├── health/            # HealthController (/health)
│   │   ├── process/            # ProcessController, ProcessService (single-file, pure JdbcTemplate)
│   │   ├── project/           # ProjectController, ProjectService
│   │   ├── purchase/          # 21 files: BC, BL, Facture + repositories + domain
│   │   ├── role/              # RoleController, RoleService
│   │   ├── security/          # PermissionService, FolderAccessService, HydraUserDetailsService, ToxicPairService, SessionRevoker
│   │   ├── stock/             # StockController, StockService + 4 domain + 4 repository
│   │   ├── ticket/            # Ticket, TicketController, TicketService, TicketRepository, TicketStatus
│   │   └── user/              # AppUser, AppUserRepository, UserController, UserService
│   ├── src/main/resources/
│   │   ├── application.properties
│   │   └── db/migration/       # V1__core.sql → V35__cash_commercial_links.sql (35 migrations)
│   └── src/test/java/         # ~108 tests (101 passing; 7 known mismatches in TicketServiceTest/GlobalExceptionHandlerTest)
├── frontend/                   # Angular 18 frontend
│   ├── Dockerfile             # NOT used by Render (frontend goes to Vercel)
│   ├── angular.json
│   ├── package.json           # Angular 18.2, PrimeNG 18, PrimeIcons
│   ├── proxy.conf.json        # Dev proxy → https://hydra-0gjc.onrender.com (prod) or localhost:8080
│   └── src/app/
│       ├── app.component.{html,ts,scss}  # Root: <router-outlet> + global <p-toast>
│       ├── app.config.ts      # provideRouter, provideHttpClient(withXsrf, withFetch), providePrimeNG(Aura), MessageService
│       ├── app.routes.ts      # All routes (see §5.2)
│       ├── core/
│       │   ├── auth.service.ts, auth.guard.ts
│       │   ├── api.interceptor.ts  # Adds XSRF-TOKEN header + handles 401
│       │   ├── perm.guard.ts       # permGuard(PERM.X) + ownerGuard
│       │   ├── permissions.ts      # PERM constants + French labels + groups
│       │   ├── services/           # 11 services (cash, document, folder, folder-access, party, process, project, purchase, stock, ticket, user)
│       │   └── models/             # 8 model files (cash, document, folder, process, project, select-option, stock, ticket, user)
│       ├── features/
│       │   ├── audit/         # Audit log viewer
│       │   ├── cash/          # 11 components: cash-hub, finance-hub, pot-list/detail, party-list/detail, movement-form/detail, movement-detail, party-detail
│       │   ├── document/      # documents (list), document-detail, register (master register)
│       │   ├── home/          # Dashboard / landing
│       │   ├── login/         # Login form
│       │   ├── process/       # process-hub, my-tasks, start-process, process-builder, process-timeline, approval-desk
│       │   ├── projects/      # projects (list + 3-step wizard), project-detail (hero + tabs), folder-access-dialog, project-role-assignees
│       │   ├── roles/         # roles (list), role-detail
│       │   ├── shell/         # App shell (sidebar + topbar)
│       │   ├── stock/         # 14 components: stock-hub, stock-list, stock-moves, move-form, items-admin, locations-admin, match-overview, bc-list/form/detail, bl-list/form/detail, facture-list/form/detail, document-picker-dialog
│       │   ├── tickets/       # tickets (list), ticket-detail
│       │   └── users/         # users (list)
│       └── shared/
│           ├── hydra-design.ts             # Design system: ROLE_VISUAL, PROJECT_ROLE_VISUAL, PROCESS_STATUS_VISUAL, DOC_TYPE_VISUAL, etc.
│           ├── chantier-visual.ts          # Chantier types (ADDUCTION, IRRIGATION, FORAGE, etc.) + folder icon heuristics
│           ├── process-visual.ts           # Process type visuals
│           ├── document-file-actions.component.ts  # NEW (Phase B): shared Ouvrir/Télécharger component
│           └── empty-state.component.ts
├── Dockerfile                  # Root Dockerfile (NOT used by Render — kept for symmetry)
├── render.yaml                 # Render service config
├── README-DEPLOY.md            # Step-by-step deploy guide (Supabase + Render + Vercel)
└── worklog.md                  # Multi-agent worklog (history of all changes)
```

---

## 4. Backend architecture

### 4.1 Layered structure (every domain follows the same pattern)

```
domain/     ← JPA entities (Auditable base, @Entity, @Table)
repository/ ← Spring Data JPA repositories (interface-based, custom @Query)
dto/        ← Records for request/response (CreateXRequest, XResponse, UpdateXRequest)
service/    ← @Service classes: business logic, @Transactional, throws BusinessRuleException
controller/ ← @RestController, @PreAuthorize, delegates to service
```

**Exception:** the `process` engine uses **pure JdbcTemplate** (not JPA) to avoid Hibernate flush ordering issues in the multi-step state machine.

### 4.2 Modules (13 packages)

| Module | Files | Purpose | Key endpoints |
|---|---|---|---|
| **auth** | 1 | Login, logout, `/me`, change-password | `POST /api/auth/login`, `GET /api/auth/me` |
| **user** | 4 | User CRUD, activate/deactivate | `GET/POST/PUT /api/users` |
| **role** | 2 | Role CRUD, assign permissions, toxic-pair checks | `GET/POST /api/roles` |
| **project** | 2 | Chantier (project) CRUD, auto-folders, team membership | `GET/POST /api/projects` |
| **document** | 39 | Documents + revisions + folders + tags + file storage (local/S3) | `POST /api/.../documents/upload`, `GET /api/documents/{id}/revisions/{revId}/download` |
| **ticket** | 5 | Issue tracking (OUVERT → RESOLU), link to documents | `GET/POST /api/tickets` |
| **stock** | 12 | Items, locations, moves (IN/OUT/DIRECT_USE/TRANSFER/ADJUST), reversals | `POST /api/stock/moves`, `GET /api/stock/balances` |
| **purchase** | 21 | BC (Bon de Commande) → BL (Bon de Livraison) → Facture, match overview | `POST /api/stock/bc`, `POST /api/stock/bl`, `GET /api/stock/match` |
| **cash** | 8 | Pots (caisses), movements, parties (sous-traitants), statement, payment facade | `POST /api/cash/movements/submit-payment`, `GET /api/cash/pots/{id}/statement` |
| **process** | 2 | Workflow engine: templates, instances, steps, approval desk | `POST /api/processes/start`, `POST /api/processes/{id}/steps/{sid}/complete` |
| **audit** | 3 | Event log viewer (append-only, trigger-protected) | `GET /api/audit/events` |
| **security** | 7 | PermissionService, FolderAccessService (ACL), ToxicPairService, SessionRevoker | `GET /api/folders/{id}/access` |
| **config** | 7 | SecurityConfig, CorsConfig, CsrfCookieFilter, BootstrapRunner (seeds owner), DemoDataRunner, AuditingConfig | — |

### 4.3 Key design decisions

1. **Multi-tenant via `org_id` + RLS** — every table has `org_id`; PostgreSQL RLS policies enforce isolation. Even if a query forgets the `WHERE org_id = ?`, the DB blocks cross-org reads.
2. **`Auditable` base entity** — `createdBy`, `createdAt`, `updatedBy`, `updatedAt` auto-populated by `AuditorAwareImpl` (reads from Spring Security context).
3. **`AssignedUuid`** — UUIDs are assigned client-side (not DB-generated) so the same entity can be referenced before persist. Custom `AssignedUuidGenerator` + `Persistable<UUID>` interface.
4. **`BusinessRuleException`** → HTTP 400 with `{code, message}`. `ResourceNotFoundException` → 404. `BadRequestException` → 400. `FileStorageException` → 500. All handled by `GlobalExceptionHandler`.
5. **`event_log` is append-only** — a PostgreSQL trigger `block_event_log_mutation()` raises an exception on any UPDATE/DELETE.
6. **CSRF cookie mode** — `CookieCsrfTokenRepository.withHttpOnlyFalse()` so Angular's HttpClient can read the cookie and send it as `X-XSRF-TOKEN` header. The `CsrfCookieFilter` ensures the cookie is set on the first response.
7. **`@PreAuthorize` on every controller method** — defense in depth: even if a route is reachable, the method-level check blocks unauthorized access.
8. **File storage is pluggable** — `FileStorage` interface with `LocalFileStorage` (default, dev/pilot) and `S3FileStorage` (Supabase S3-compatible). Swapped via `hydra.storage.type` env var, no code change.

### 4.4 The process engine (the most complex piece)

`ProcessService` is a single ~1700-line file using **pure JdbcTemplate** (not JPA). Why? The state machine transitions are delicate — Hibernate's dirty checking + flush ordering caused subtle bugs in early development. Pure JDBC gives full control over SQL order.

**Concepts:**
- **`process_template`** — reusable workflow definition (e.g., `PAYMENT_APPROVAL`). System templates have `org_id = NULL`; org-scoped templates have it set.
- **`process_template_step`** — ordered steps in a template. Each step has an `assignee_role` (e.g., `FINANCE`, `OWNER`).
- **`process_instance`** — a running workflow on a subject (e.g., a cash movement). Status: `RUNNING` → `COMPLETED` / `REJECTED` / `CANCELLED`.
- **`process_step_instance`** — per-step execution. Status: `PENDING` → `DONE` / `REJECTED` / `SKIPPED`.
- **`process_step_document`** — optional attachments per step (e.g., proof of payment).

**Permission model (clear contract):**
- **Org OWNER**: may do everything in their org.
- **Project member**: may start a process on that project.
- **Step assignee**: may complete/reject their own PENDING step (`assignee_user_id = me` OR my `project_role` matches the step's `assignee_role`).
- **Fallback**: if `assignee_user_id` is null, any project member whose `project_role` matches may act.

**Default template seeded (V17):** `PAYMENT_APPROVAL` with 3 steps:
1. `DRAFT` — FINANCE creates/confirms the payment intent
2. `APPROVE` — OWNER approves/rejects
3. `POST` — FINANCE marks completed (triggers `CashService.onProcessCompletedPostMovement()` which actually moves the money)

**Approval desk:** `GET /api/process/steps/{stepInstanceId}/approval-desk` returns a rich payload (movement details + timeline + role assignees) so the frontend can render the decision screen in one round-trip.

---

## 5. Frontend architecture

### 5.1 Standalone components + signals

Angular 18, **standalone components** (no NgModules), **signals** for state (not RxJS subjects). Lazy-loaded routes via `loadComponent`. PrimeNG 18 components throughout.

### 5.2 Routes (full list)

```
/login                              # Public
/                                   # Home (auth required)
/tickets, /tickets/:id              # Issue tracking
/projects, /projects/:id            # Chantiers (3-step creation wizard + hero detail)
/projects/:pid/folders/:fid          # Documents in a folder
/projects/:id/register              # Master document register
/documents/:id                      # Document detail (metadata + revision history)
/users                              # User admin
/roles, /roles/:id                  # Role admin
/audit                              # Audit log
/processus                          # Process hub
/mes-taches                         # My pending tasks (personalized)
/processes/start                    # Start a new process
/processes/builder, /builder/:id    # Template builder (owner-only)
/processes/:id                      # Process timeline
/processus/etapes/:sid/validation  # Approval desk (decision screen)
/tresorerie                         # Cash hub
/tresorerie/finance                 # Finance cockpit (caisses + pending + CSV export)
/tresorerie/caisses, /:potId        # Pots
/tresorerie/operations/new, /:id, /:id/edit  # Cash movements
/tresorerie/parties, /:id           # Sous-traitants / suppliers
/stock                              # Stock hub
/stock/stocks                       # Stock levels by item × location
/stock/mouvements, /new             # Stock moves
/stock/articles, /emplacements      # Items + locations admin
/stock/commandes, /new, /:id        # BC (Bon de Commande)
/stock/receptions, /new, /:id       # BL (Bon de Livraison)
/stock/factures, /new, /:id         # Factures
/stock/match                        # BC ↔ BL ↔ Facture match overview
```

### 5.3 Core services (11)

| Service | Responsibility |
|---|---|
| `AuthService` | Login, logout, current user signal, `can(PERM)` helper |
| `UserService` | User CRUD |
| `RoleService` | Role CRUD |
| `ProjectService` | Chantier CRUD, my-permissions, team |
| `FolderService` | Folder tree |
| `FolderAccessService` | ACL: who can read/upload/download per folder |
| `DocumentService` | Documents, revisions, upload (multipart + progress), **openRevision/openDocument/downloadDocument** (Phase B) with JSON-error blob guard |
| `TicketService` | Tickets |
| `StockService` | Items, locations, moves, balances, match overview, ensure-stock-folder |
| `PurchaseService` | BC, BL, Facture, link-document |
| `CashService` | Pots, movements, parties, statement, CSV export, submit-payment |
| `ProcessService` | Templates, start, complete/reject step, timeline, my-tasks, approval-desk, role-assignees |

### 5.4 Guards

- `authGuard` — redirects to `/login` if not authenticated.
- `permGuard(PERM.X)` — blocks route if user lacks permission; redirects to home.
- `ownerGuard` — blocks route unless user is org OWNER (for template builder).

### 5.5 Design system (`shared/hydra-design.ts`)

Single source of truth for all visual language:
- **`ROLE_VISUAL`** — 13 business roles (OWNER, DIRECTION, CHEF_PROJET, CONDUCTEUR, INGENIEUR, BUREAU, QA, DOC_CTRL, ACHAT, FINANCE, CHEF_CHANTIER, HSE, SOUS_TRAITANT) each with icon + color + soft-bg + French label.
- **`PROJECT_ROLE_VISUAL`** — 4 permission roles (ADMIN/MANAGER/ENGINEER/VIEWER).
- **`PROCESS_STATUS_VISUAL`** + **`STEP_STATUS_VISUAL`** — feu tricolore (green=done, orange=pending, red=rejected, grey=skipped).
- **`CHANTIER_TYPE_VISUAL`** — hydraulic domain types (ADDUCTION, IRRIGATION, VANNE, FORAGE, POMPAGE, ASSAINISSEMENT, REHABILITATION, BASSIN) — see `chantier-visual.ts`.
- **`PROCESS_TYPE_VISUAL`** — PAYMENT, MATERIAL, SUBCONTRACT, SITE, HSE, DOC, OTHER with gradients.
- **`DOC_TYPE_VISUAL`** — 12 doc types (APD, ETUDE, PLAN, PV_RECEPTION, MARCHE, FACTURE, SITUATION, BON_LIVRAISON, RAPPORT, PHOTO, HSE, AUTRE).
- **`ACTION_TONE_VISUAL`** — semantic button colors.
- **`folderIcon(name)`** — heuristic: "Plans" → `pi-map`, "Photos" → `pi-camera`, "PV" → `pi-verified`, etc.
- **`avatarGradient(seed)`** — deterministic gradient from UUID for user avatars.

### 5.6 Shared components

- **`DocumentFileActionsComponent`** (NEW Phase B) — the shared Ouvrir/Télécharger component. Used on every screen that surfaces a document. Handles JSON-error blob guard, popup-blocked fallback, French toasts, mime inference.
- **`EmptyStateComponent`** — icon + message for empty lists.

---

## 6. Database schema

### 6.1 Flyway migrations (V1 → V35)

| Migration | What it adds |
|---|---|
| V1 | Core: `organization`, `app_user`, `permission`, `role`, `role_permission`, `user_role`, `incompatible_role_pair`, `toxic_pair_override`, `ticket`, `event_log` (append-only trigger) + RLS |
| V2 | Seed data (default permissions, system roles) |
| V3 | User admin refinements |
| V4 | Permissions phase 1 (TICKET_*, USER_*, ROLE_*, PROJECT_*, AUDIT_VIEW) |
| V5 | `project` (chantier) table |
| V6 | Ticket v2 (assignee, priority, tags) |
| V7 | Ticket ↔ document link |
| V8 | Project workspace (folder template) |
| V9 | Folder template fix |
| V10 | Documents (`document`, `document_revision`, `doc_folder`, `tag`) |
| V11 | Document permissions (DOCUMENT_READ/UPLOAD/DOWNLOAD/DELETE) |
| V12 | Document reference + description |
| V13 | Ticket workflow + project roles (ADMIN/MANAGER/ENGINEER/VIEWER on `project_member`) |
| V14 | Company roles + folder permissions |
| V15 | Folder access ACL (`folder_access` table) |
| V16 | RLS fix on document tables |
| V17 | **Process engine**: `process_template`, `process_template_step`, `process_instance`, `process_step_instance`, `process_step_document` + seed `PAYMENT_APPROVAL` template |
| V18 | Process ↔ project config (`project_process_template`) |
| V19 | Process engine completions (reassign, cancel, skip) |
| V20 | **Cash ledger**: `cash_pot`, `cash_movement` |
| V22 | Cash permissions (CASH_READ/DRAFT/SUBMIT/POST/VOID, PARTY_MANAGE) |
| V23 | Ticket ↔ process instance link |
| V24 | Payment claimed vs assessed (variance tracking) |
| V25 | Payment attachment (proof document) |
| V26 | Cash movement enriched (Tunisian état de caisse: paymentMode, paymentRef, voucherNo, nature, invoiceRef) |
| V27 | Project role assignee (`project_role_assignee` — who acts as FINANCE/OWNER for this project) |
| V28 | Void request columns (soft-void workflow) |
| V29 | Process template description |
| V30 | **Stock virtual v1**: `stock_item`, `stock_location`, `stock_move`, `item_alias` + STOCK_VIEW/MOVE/ADMIN perms |
| V31 | Stock slice 1.2 support |
| V32 | **Purchase orders (BC)**: `purchase_order`, `purchase_order_line` |
| V33 | BL lineage columns (`bon_livraison.document_id`, `bc_line_id` on BL lines) |
| V34 | **Factures**: `facture`, `facture_line` |
| V35 | Cash commercial links (cash_movement ↔ bc_id, facture_id) |

### 6.2 Key tables (high-level)

```
organization (id, name)
  └─ app_user (id, org_id, email, password_hash, full_name, is_owner, is_active)
       └─ user_role ─ role (id, org_id, code, label, scope_type)
                         └─ role_permission ─ permission (id, description)
       └─ toxic_pair_override (override incompatible role pairs per user)

project (id, org_id, code, name, type, status, start_date, end_date, contract_amount)
  └─ project_member (user_id, project_id, project_role)  ← ADMIN/MANAGER/ENGINEER/VIEWER
  └─ project_role_assignee (project_id, role_code, user_id)  ← who acts as FINANCE/OWNER for this project
  └─ doc_folder (id, project_id, code, name, parent_id)  ← tree, auto-created: Administratif/Technique/Exécution/Qualité-Réception/Achats/Finance/HSE
       └─ folder_access (folder_id, user_id, action)  ← ACL: READ/UPLOAD/DOWNLOAD per user
       └─ document (id, folder_id, doc_type, status, reference, title, current_rev)
            └─ document_revision (id, document_id, rev_label, file_name, storage_key, mime_type, size_bytes, checksum_sha256, change_note)

ticket (id, org_id, title, status, created_by)  ← OUVERT/RESOLU, link to document + process_instance

stock_item (id, org_id, code, name, uom)  + item_alias (alias → item)
stock_location (id, org_id, type=CENTRAL|SITE, project_id)
stock_move (id, org_id, move_type=IN|OUT|DIRECT_USE|TRANSFER|ADJUST, qty, uom, item_id, from_location_id, to_location_id, document_id, bc_line_id, bl_line_id)

purchase_order (id, org_id, project_id, status=DRAFT|CONFIRMED|CANCELLED|CLOSED, document_id)
  └─ purchase_order_line (id, purchase_order_id, item_id, qty_ordered, uom, unit_price)
bon_livraison (id, org_id, project_id, bc_id, status=DRAFT|POSTED|CANCELLED, document_id)
  └─ bon_livraison_line (id, bl_id, bc_line_id, item_id, qty_received)
facture (id, org_id, project_id, bc_id, bl_id, status=DRAFT|VALIDATED|CANCELLED, document_id, amount_ttc)
  └─ facture_line (id, facture_id, description, qty, uom, unit_price, line_ht)

cash_pot (id, org_id, type=COMPANY|PROJECT, project_id, name, currency)
cash_movement (id, org_id, movement_type=IN|OUT|TRANSFER, amount, from_pot_id, to_pot_id, project_id, party_id,
               status=DRAFT|POSTED|VOID, process_instance_id, claimed_amount, assessed_amount, variance_reason,
               payment_mode, payment_ref, voucher_no, nature, invoice_ref, bc_id, facture_id, document_id)

process_template (id, key, name, org_id, active)  ← system templates have org_id=NULL
  └─ process_template_step (id, template_id, step_order, step_key, name, assignee_role, requires_attachment)
process_instance (id, org_id, project_id, template_id, subject_type, subject_id, status, started_by)
  └─ process_step_instance (id, instance_id, step_order, step_key, assignee_user_id, status, started_at, completed_at, comment)
       └─ process_step_document (step_instance_id, document_id)

event_log (id, org_id, user_id, action, entity_type, entity_id, details jsonb, created_at)  ← append-only trigger
```

---

## 7. Business domains & flows

### 7.1 Purchase-to-Pay (P2P): BD → BC → BL → Facture → Payment

The core construction procurement flow:

```
1. BD (Bon de Demande)      ← site need (not yet a separate entity — captured as a ticket or directly as a BC draft)
2. BC (Bon de Commande)     ← PurchaseOrder: DRAFT → CONFIRMED → CLOSED
   • Created by ACHAT role, linked to a project + supplier (party)
   • Lines: item × qty_ordered × uom × unit_price
3. BL (Bon de Livraison)    ← BonLivraison: DRAFT → POSTED (irreversible)
   • Created when goods arrive, linked to BC + reception location
   • On POST: stock_move IN is created automatically (BonLivraisonService.postMove)
4. Facture                  ← Facture: DRAFT → VALIDATED
   • Linked to BC + BL, has amount_ttc
5. Cash Movement (OUT)      ← CashService.submit-payment
   • Linked to facture (optional), goes through PAYMENT_APPROVAL process
   • On process COMPLETED: CashService.onProcessCompletedPostMovement moves the money
```

**Match overview** (`GET /api/stock/match`): a per-BC-line table showing qty ordered vs received vs invoiced, with status `MATCHED` / `PARTIAL_RECEIVE` / `PARTIAL_INVOICE` / `OVER_INVOICE` / `MISSING_BL` / `MISSING_FACTURE` / `QTY_MISMATCH`.

### 7.2 Stock

5 move types, each with strict location rules:
- **IN** — `toLocationId` required, `fromLocationId` null (reception)
- **OUT** — `fromLocationId` required, `toLocationId` null (sortie/consommation)
- **DIRECT_USE** — same shape as OUT (carburant case — used on site without stocking)
- **TRANSFER** — both `from` and `to` required, must differ
- **ADJUST** — `fromLocationId` required (variance correction)

**Reversal:** every posted move can be reversed (`POST /api/stock/moves/{id}/reverse`) with a reason — creates a compensating move. Used for error correction without deleting history.

**Balance computation:** `stock_move` is the ledger; balances are computed by summing moves per `(item_id, location_id)`.

### 7.3 Cash / Trésorerie

- **Pots (caisses):** `COMPANY` (central) or `PROJECT` (chantier). Auto-created per project via `ensureProjectPot`.
- **Movements:** `IN` / `OUT` / `TRANSFER`. DRAFT → POSTED (irreversible) → VOID (soft, via `requestVoid` workflow).
- **Payment facade** (`PaymentFacade` + `CashService.submit-payment`): creates a DRAFT movement + starts a `PAYMENT_APPROVAL` process. Money only moves when the process COMPLETED hook fires.
- **Tunisian état de caisse fields** (V26): `paymentMode` (ESPECE/CHEQUE/VIREMENT/VERSEMENT/MANDAT/AUTRE), `paymentRef`, `voucherNo`, `nature` (ALIM_CAISSE/SOUS_TRAITANCE/MATERIEL/TRANSPORT/MAIN_OEUVRE/LOCATION/LABO/CARBURANT/DIVERS), `invoiceRef`.
- **Claimed vs assessed** (V24): tracks variance — what the party asked vs what we pay. `varianceReason` required when they differ.
- **Statement** (`GET /api/cash/pots/{id}/statement`): monthly statement with opening/closing balance + lines.
- **CSV export** (`CashService.exportCsv`): frontend downloads the statement as CSV for Excel.

### 7.4 Documents

- **Document** = metadata (title, type, status, reference) + **Revisions** (immutable file versions, Rev.0 → Rev.A → Rev.B → ...).
- **Folders** are project-scoped, auto-created on project creation: `01-Administratif`, `02-Technique`, `03-Exécution`, `04-Qualité-Réception`, `05-Achats`, `06-Finance`, `07-HSE`.
- **Folder ACL** (`folder_access`): per-user READ/UPLOAD/DOWNLOAD permissions. Project role implies default access (ADMIN=all, MANAGER=read+upload, etc.).
- **File storage:** pluggable. `LocalFileStorage` writes to `HYDRA_STORAGE_ROOT` (default `./data/files`, prod `/opt/data/files` on Render Disk). `S3FileStorage` talks to Supabase S3-compatible API.
- **Filename convention:** `FilenameBuilder` builds `{projectCode}__{docType}__{title}__{revLabel}__{original}`.
- **Download endpoint** (`GET /api/documents/{id}/revisions/{revId}/download`): sets `Content-Type` from revision mimeType (with fallback inference from filename — Phase B DOC-ACTIONS), `Content-Disposition: inline` for pdf/image, `attachment` otherwise.

### 7.5 Tickets

Simple issue tracker: `OUVERT` → `RESOLU`. Can be linked to a document (e.g., "this photo shows the defect") and to a process instance (e.g., "this ticket triggered a payment approval").

### 7.6 Projects (Chantiers)

- Hydraulic domain types: `ADDUCTION`, `IRRIGATION`, `VANNE`, `FORAGE`, `POMPAGE`, `ASSAINISSEMENT`, `REHABILITATION`, `BASSIN`, plus generic `RESERVOIR`, `AUTRE`.
- 3-step creation wizard: (1) Identité + type, (2) Calendrier + équipe, (3) Contrat optionnel.
- Hero detail page: type gradient banner + stats (dossiers/membres/processus).
- Tabs: Dossiers (default) / Équipe / Processus.

---

## 8. Security & permissions model

### 8.1 Auth flow

1. `POST /api/auth/login` with `{email, password}` → BCrypt verifies → Spring Session JDBC creates a session → `HYDRA_SID` cookie set (HttpOnly, SameSite=Lax, 12h).
2. CSRF: first GET to any endpoint sets `XSRF-TOKEN` cookie (HttpOnly=false so Angular can read it). Angular's `HttpClient` with `withXsrfConfiguration({cookieName: 'XSRF-TOKEN'})` auto-sends the `X-XSRF-TOKEN` header on every mutating request.
3. Every subsequent request is authenticated by the session cookie + CSRF token.

### 8.2 Permissions (28 total)

Grouped into 7 categories (see `frontend/src/app/core/permissions.ts`):

| Group | Permissions |
|---|---|
| Users | `USER_CREATE`, `USER_UPDATE`, `USER_DEACTIVATE`, `ROLE_ASSIGN`, `ROLE_MANAGE` |
| Documents | `DOCUMENT_READ`, `DOCUMENT_UPLOAD`, `DOCUMENT_DOWNLOAD`, `DOCUMENT_DELETE` |
| Projects | `PROJECT_READ`, `PROJECT_WRITE`, `PROJECT_ASSIGN`, `PROJECT_MANAGE` |
| Cash | `CASH_READ`, `CASH_DRAFT`, `CASH_SUBMIT`, `CASH_POST`, `CASH_VOID`, `PARTY_MANAGE` |
| Stock | `STOCK_VIEW`, `STOCK_MOVE`, `STOCK_ADMIN` |
| Tickets | `TICKET_READ`, `TICKET_WRITE`, `TICKET_ASSIGN` |
| Other | `AUDIT_VIEW`, `FOLDER_MANAGE` |

**Enforcement:** three layers:
1. **Route guard** (`permGuard(PERM.X)`) — blocks the route in the frontend.
2. **`@PreAuthorize("hasAuthority('PERM_X')")`** on the controller method.
3. **`assertCan(auth, PERM.X)`** in the service layer (defense in depth).

**OWNER bypass:** org OWNER gets all permissions via `PermissionService.allPermissions()`.

### 8.3 Toxic role pairs (SoD — Segregation of Duties)

`incompatible_role_pair` table defines forbidden role combinations (e.g., ACHAT + FINANCE — can't both buy and pay). `ToxicPairService` checks on role assignment. Overrides possible via `toxic_pair_override` (granted by OWNER with a reason).

### 8.4 Folder ACL

Per-folder, per-user permissions: `READ`, `UPLOAD`, `DOWNLOAD`. `FolderAccessService.assertAccess(userId, folderId, action)` is called by every document service method. Project role implies defaults:
- `ADMIN` → all actions on all folders
- `MANAGER` → read + upload + download
- `ENGINEER` → read + upload
- `VIEWER` → read only

---

## 9. Process engine (deep dive)

### 9.1 The PAYMENT_APPROVAL template (default, seeded in V17)

```
Step 1: DRAFT     — assignee_role=FINANCE — "Create/confirm payment intent"
Step 2: APPROVE   — assignee_role=OWNER   — "Approve/reject payment"
Step 3: POST      — assignee_role=FINANCE — "Marks completed (hook for caisse post)"
```

### 9.2 The flow

1. **CashService.submit-payment** creates a DRAFT `cash_movement` (OUT) + calls `ProcessService.start(...)` with `subjectType=CASH_MOVEMENT`, `subjectId=movement.id`, `templateKey=PAYMENT_APPROVAL`.
2. Process engine creates a `process_instance` (RUNNING) + 3 `process_step_instance` (step 1 PENDING, steps 2-3 not-yet-pending).
3. FINANCE user completes step 1 (with optional comment + documents) → step 2 becomes PENDING.
4. OWNER sees the task in `/mes-taches`, opens the **approval desk** (`/processus/etapes/{sid}/validation`), reviews the movement details + timeline, then **completes** or **rejects** step 2.
   - **Complete** → step 3 becomes PENDING.
   - **Reject** → instance status becomes REJECTED, `CashService.handleProcessRejected()` marks the movement as rejected (stays DRAFT, money never moved).
5. FINANCE completes step 3 → instance COMPLETED → `CashService.onProcessCompletedPostMovement(movementId, postedBy)` fires → actually posts the movement (status POSTED, balances updated).

### 9.3 Approval desk payload

`GET /api/process/steps/{stepInstanceId}/approval-desk` returns everything the decision screen needs in one call:
- The movement details (amount, party, pot, nature, payment mode, claimed vs assessed)
- The full timeline (all events: created, submitted, step transitions)
- The role assignees (who can act as FINANCE/OWNER for this project)

### 9.4 Reassign + cancel

- **Reassign** (`POST /api/processes/{id}/steps/{sid}/reassign`) — OWNER can reassign a step to a specific user (with comment).
- **Cancel** (`POST /api/processes/{id}/cancel`) — aborts the whole instance with a reason.

---

## 10. Design system

The design system is documented in `frontend/src/app/shared/hydra-design.ts` (see §5.5). Six principles:

1. **ONE-SCREEN-ONE-PURPOSE** — each screen says what it does at a glance.
2. **COLOR + ICON = SENS** — not decoration. Red = danger/blocked, green = OK, orange = pending, grey = inactive.
3. **PROGRESSIVE DISCLOSURE** — show what's useful now, reveal the rest on click.
4. **PERSONALIZATION** — "Mes tâches" before "Toutes les tâches".
5. **VISUAL HIERARCHY** — primary button big + colored, secondary small + outline.
6. **ROLE-BADGE EVERYWHERE** — when you see a person, you immediately see their business role (color + icon).

The design system explicitly draws from Instagram/TikTok/Facebook (strong visual hierarchy, color+icon carry meaning) rather than from SAP/Odoo (database-centric, too many fields, confusing navigation). The research backing this is in `download/ENTERPRISE-ERP-UX-RESEARCH.md` and `download/HYDRA_Recherche_UX_Redesign.pdf`.

---

## 11. Deployment

### 11.1 Architecture

```
GitHub (push to main)
   ├──→ Vercel  (frontend Angular, free tier, auto-deploy on webhook)
   └──→ Render  (backend Java, Docker, free/starter tier)
            ├── Dockerfile at backend/Dockerfile (Root Directory: backend)
            ├── Build: mvn package -DskipTests (with Aliyun mirror + retry loop)
            └── Run: java -jar app.jar (eclipse-temurin:21-jre-alpine)

Supabase (PostgreSQL 16, free tier)
   ├── Connection string in DB_URL env var
   └── Storage (S3-compatible) optional via HYDRA_STORAGE_TYPE=s3
```

### 11.2 URLs

| Service | URL |
|---|---|
| Frontend (Vercel) | `https://hydra-managmnet.vercel.app` (or similar) |
| Backend (Render) | `https://hydra-0gjc.onrender.com` |
| Health check | `GET https://hydra-0gjc.onrender.com/health` → `{"status":"ok","database":"connected"}` |

### 11.3 Environment variables (Render)

| Key | Value |
|---|---|
| `DB_URL` | `jdbc:postgresql://db.<supabase-ref>.supabase.co:5432/postgres` |
| `DB_USER` | `postgres` |
| `DB_PASSWORD` | `<supabase-password>` |
| `OWNER_EMAIL` | `owner@hydra.tn` |
| `OWNER_PASSWORD` | `HydraOwner2026!` |
| `HYDRA_STORAGE_TYPE` | `local` |
| `HYDRA_STORAGE_ROOT` | `/opt/data/files` |
| `JAVA_OPTS` | `-Xmx512m -Xss256k` |

### 11.4 Build resilience (Phase B)

`backend/Dockerfile` + `backend/.mvn/settings.xml` implement two layers of 429 defense:
1. **Aliyun Maven mirror** — routes all central requests through `maven.aliyun.com/repository/public` (full Maven Central sync, not rate-limited).
2. **Maven Wagon retry** — 10 attempts with exponential backoff (4s → 30s) on 429/5xx.
3. **Outer bash loop** — 3 full-build attempts with 15s sleep, proper failure detection (no more silent success → jar-not-found).

---

## 12. Recent work

### 12.1 Phase B — DOC-VIEW (commit `4ae3d04`)

First attempt at document preview/download. Added:
- `DocumentService.openRevision(id, revId)` + `openDocument` + `downloadDocument`
- JSON-error blob guard
- `Content-Disposition: inline` for pdf/image on the backend

**Bug:** Icon-only buttons, no global `<p-toast>`, chicken-and-egg disabled state on documents list, Télécharger bypassed the JSON-error guard.

### 12.2 Phase B — DOC-ACTIONS (commit `1dbb121`)

Fixed all 6 root causes from DOC-VIEW:
- **R1:** Added global `<p-toast>` to `app.component.html` — all error toasts now render.
- **R2:** New shared `DocumentFileActionsComponent` auto-loads latest revision on init — no more chicken-and-egg disabled state.
- **R3:** Télécharger now goes through the JSON-error blob guard.
- **R4:** French text labels "Ouvrir" / "Télécharger" (icons beside text).
- **R5:** Loading spinners on click.
- **R6:** Backend `resolveContentType()` infers mime from filename extension when stored mime is null/octet-stream.

Wired into 5 screens: documents list, document-detail, facture/BL/BC/cash detail.

### 12.3 Build resilience (commits `537529c`, `a051628`, `8407a41`)

- `537529c` — patched root `/Dockerfile` (WRONG — Render uses `backend/Dockerfile`).
- `a051628` — patched the RIGHT Dockerfile (`backend/Dockerfile`) with retry loop.
- `8407a41` — fixed retry loop bug (silent success → jar-not-found) + added Aliyun mirror.

### 12.4 Reports

All reports in `backend/docs/`:
- `REPORT-DOC-VIEW.md` — Phase B DOC-VIEW test plan
- `REPORT-DOC-ACTIONS.md` — Phase B DOC-ACTIONS root cause + C1-C5 PASS/FAIL
- `REPORT-BUILD-429-RESILIENCE.md` — Maven Central 429 fix

---

## 13. Known issues & limitations

1. **7 failing tests** in `TicketServiceTest` (6) and `GlobalExceptionHandlerTest` (1) — test debt, the service SQL evolved without updating tests. Not blocking.
2. **No mobile app** — the #1 problem in Tunisian BTP (chefs de chantier have no ERP access on site). PWA/offline-first is the next big phase.
3. **No attachement entry** for `situation de chantier` — the most painful monthly task (50–200 line items × 5 fields per site). Next priority.
4. **No BTP-specific accounting** — `retenue de garantie` (5–10%), `précompte`, `RAS 1.5%`, `CNSS`, `TFP`, `FOPROLOS` not automated. Currently manual in Excel.
5. **Auto-load on documents list** — each row's `<app-document-file-actions>` fires a `GET /api/documents/{id}/revisions` call on init. For 50 documents = 50 background calls. Can be batched later.
6. **In-app preview panel** — Phase B opens in a new tab, not an in-app `<iframe>`. Simpler and reliable; can add a preview panel later.
7. **GitHub PAT exposed in chat log** — the token used for pushes was pasted in plaintext. Saved to `/home/z/.config/hydra/gh-token` (perms 600, outside git repo) but should be rotated.
8. **Two Dockerfiles** — both `/Dockerfile` and `backend/Dockerfile` exist. Render uses `backend/Dockerfile` (Root Directory: backend). The root one is kept for symmetry but unused.
9. **`groupId` mismatch** — pom.xml uses `com.example` but the package is `tn.hydra`. Cosmetic, not blocking.

---

## 14. Operational recipes

### 14.1 Push a new change to GitHub + auto-deploy

```bash
# Token is saved at /home/z/.config/hydra/gh-token (perms 600, outside repo).
# The push script auto-loads it.
/tmp/my-project/scripts/push-to-github.sh "feat(scope): message"   # stage + commit + push
/tmp/my-project/scripts/push-to-github.sh                            # push pending commits only
```

### 14.2 Run locally

```bash
# Backend
cd /tmp/my-project/backend
./mvnw spring-boot:run   # needs JDK 21 + PostgreSQL at localhost:5432

# Frontend
cd /tmp/my-project/frontend
npm start   # ng serve --proxy-config proxy.conf.json --host 0.0.0.0 --port 3000
```

### 14.3 Check Render deploy health

```bash
curl -sS --max-time 30 https://hydra-0gjc.onrender.com/health
# Expected: {"status":"ok","database":"connected"}
```

### 14.4 Verify the latest commit on GitHub

```bash
git -C /tmp/my-project log --oneline origin/main -3
```

### 14.5 Run ng build to verify no TS errors

```bash
cd /tmp/my-project/frontend && node_modules/.bin/ng build 2>&1 | grep -E "ERROR|✘" | head
# Should be empty (0 errors). 6 pre-existing warnings are normal.
```

---

## 15. Glossary (French BTP terms)

| Term | Meaning |
|---|---|
| **BTP** | Bâtiment et Travaux Publics (construction) |
| **Chantier** | Construction site / project |
| **BD** | Bon de Demande (purchase request) |
| **BC** | Bon de Commande (purchase order) |
| **BL** | Bon de Livraison (delivery note) |
| **Facture** | Invoice |
| **Situation de chantier** | Monthly progress billing (attachement → situation → décompte) |
| **Attachement** | Line-by-line measurement of work done on site (50–200 lines per chantier per month) |
| **Décompte** | Final statement |
| **Bordereau** | Price schedule (bordereau de prix unitaires) |
| **Caisse** | Cash pot (company central or project site) |
| **Sous-traitant** | Subcontractor |
| **TND** | Tunisian Dinar (3 decimals — millimes) |
| **RAS** | Retenue à la source (1.5% on marchés) |
| **CNSS** | Caisse Nationale de Sécurité Sociale (16.57% / 9.18%) |
| **TFP** | Taxe pour la Formation Professionnelle (2%) |
| **FOPROLOS** | Fonds de Promotion du Logement Social (1%) |
| **PV de réception** | Procès-verbal de réception (acceptance certificate) |
| **APD** | Avant-Projet Détaillé (detailed preliminary project) |

---

## End of handoff

This document should give any AI or developer a complete operational understanding of HYDRA. For the deepest detail on any module, read the source — every package has focused, well-commented code. The `worklog.md` at the repo root has the full history of every change.
