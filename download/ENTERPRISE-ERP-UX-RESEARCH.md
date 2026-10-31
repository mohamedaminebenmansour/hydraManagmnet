# Enterprise ERP UI/UX & Vertical Adaptation — Deep Research for HYDRA (Tunisian BTP)

> **Scope note on method.** This sandbox has no live web-search/fetch tool. The
> findings below are synthesized from the well-documented public design systems,
> product docs, analyst write-ups, and field patterns of these vendors as they
> stood through early 2024. Each section is concrete and actionable; a
> **verification checklist** at the end lists the exact claims you should spot
> check against the vendor's live design-system pages before committing HYDRA
> architecture decisions based on them. Vendors iterate fast (Fiori 3 → Horizon,
> Sage 300 CRE → Sage Construction Cloud, Odoo 17 → 18, Procore UI refreshes,
> ACC annual releases) — treat version numbers as "as of writing".

**Tunisian BTP glossary used throughout (so the report is unambiguous):**
- **BC** = Bon de Commande (Purchase Order)
- **BL** = Bon de Livraison (Delivery Note / Goods Receipt)
- **BS / BR** = Bon de Sortie / Bon de Retour (issue / return)
- **Marché** = public works contract
- **Décompte** = progress payment statement
- **Attachement** = measurement statement (measured quantities per period)
- **Cautionnement** = contract bond; **Retenue de garantie** = retention
- **RAS** = Retenue à la source (withholding)
- **TVA** = VAT (Tunisia: 19% std, 13%/7% reduced)
- **CNSS, TFP, FOPROLOS** = social & payroll levies

---

## 0. Executive TL;DR — what HYDRA should steal from whom

| Vendor | Single most-copyable idea for HYDRA |
|---|---|
| **Sage (CRE/Timberline + 100)** | Keyboard-first editable grid as the primary data-entry surface; formula columns; running totals; state pill + approval-chain sub-screen per document. |
| **SAP Fiori** | "One app = one task = one persona" decomposition; object page pattern (header KPIs + tabbed body); draft auto-save; role-based launchpad. |
| **Odoo** | The **status bar + chatter + kanban/list/form triad** as the default document lifecycle UI; pivot/spreadsheet as a first-class view type; install/uninstall modular apps. |
| **Procore** | Tool-as-tab navigation for construction domain objects (RFI / Submittal / CO / Daily Log / Pay App); mobile-first site capture; amount-driven change-order workflows. |
| **PlanGrid / ACC** | Sheet-as-spine: every site object pinned to a coordinate on a versioned drawing; markups become first-class workflow items (Issues/RFIs). |
| **Localization** | Treat the **French legal layer ≠ Tunisian legal layer**; ship Arabic (RTL) + French (LTR) with per-column direction; 3-decimal TND; RAS/CNSS/TFP/FOPROLOS as first-class document lines, not afterthoughts. |

---

## 1. Sage — Sage 100, Sage 300, Sage Construction & Real Estate

### 1.1 Product map (so we compare like-for-like)

| Product | Origin | Target market | Construction fit |
|---|---|---|---|
| **Sage 100** (formerly Sage Line 100; French/Spanish SMB line; "Sage 100cloud"/"Sage 100c") | Sage France/Ciel heritage | SMB finance, payroll, commercial | Light — via modules (Gestion Commerciale, Immobilisations) |
| **Sage 300** (formerly Accpac; "Sage 300c" web) | North America, multi-currency/multi-ledger | Mid-market, multi-entity | Medium — via Job Cost module |
| **Sage 300 CRE** (formerly Timberline; now rebranded "Sage Construction Cloud") | Timberline, US construction heritage since 1970s | Construction & real estate | **Strong — purpose-built BTP** |
| **Sage 100 Contractor** (formerly Master Builder) | US SMB construction | Small contractors | Strong — US SMB |
| **Sage Intacct** | Cloud-native, dimension-based | Mid-market finance | Medium — via dimensions/projects |

For HYDRA the most relevant reference is **Sage 300 CRE (Timberline)** for domain depth and **Sage 100** for the French-market SMB data-entry feel.

### 1.2 The famous "spreadsheet-like" data entry — what it actually is

This is Sage's signature UX move, inherited from Timberline and carried into Sage 100's commercial grids. Concretely:

- **Grid-first, not form-first.** A purchase-order / invoice / journal entry screen is **a header strip on top + a full-width grid below**. The grid is the star of the screen, not a minor detail at the bottom.
- **Cell-level navigation modeled on Lotus/Excel.**
  - `Tab` → next cell right
  - `Shift+Tab` → previous cell left
  - `Enter` → confirm cell, move down one row (configurable: "Enter = next row" vs "Enter = next field")
  - `F2` → enter/exit cell-edit mode (exactly like Excel)
  - `Ctrl+;` → today's date; `Ctrl+D` → fill down from cell above (Sage 100 grids)
  - Arrow keys navigate between cells when not in edit mode
- **Formula columns (read-only, computed).** Quantity × Unit Price auto-fills Extended Amount; Discount % × Extended = Net. The cell renders a faint grey background + italic to signal "computed, do not type here" — exactly like an Excel formula cell that's locked.
- **Running totals row pinned at the bottom of the grid.** Sticky; updates as you type. Sage shows subtotal, discount, tax (TVA), and total — same row layout accountants expect on paper forms.
- **Inline dropdowns & lookups.** Any cell with a finite domain (account code, tax code, project code) becomes a dropdown; typing the first characters filters it (typeahead). `F5` or `…` opens a full lookup window for searching.
- **Row-level validation is lazy, not eager.** A cell turns red with a tooltip only when you leave it (on blur), not on every keystroke — this preserves typing rhythm for power users entering 50+ lines.
- **Document-level validation on Save.** Blocking errors (missing account, unbalanced journal, exceeds budget) only fire on Save; warnings (duplicate line, unusual amount) show as non-blocking amber warnings you can dismiss.
- **Multi-line entry at scale (BC/BL with 50–500 lines).** Sage grids are virtualized; you can paste a region from Excel directly into the grid (Sage 100 Gestion Commerciale and Sage 300 grids both support clipboard paste of a rectangular selection). This is **the** killer feature for accountants migrating off Excel.
- **"Zoom" detail per line.** Some cells (a complex item, a long description, a multi-currency amount) open a sub-window when double-clicked, so the grid stays compact but the detail is one click away.

> **For HYDRA:** the single highest-leverage UX decision is to make the BC/BL/BS entry screen **a grid, not a stack of forms** — with Excel-grade keyboard navigation (Tab/Enter/F2/arrow keys), Excel paste, formula columns, and a sticky running-totals row. This matches what Tunisian accountants and BTP purchasers already do in Excel today.

### 1.3 Header / line model — how Sage structures a document

A Sage CRE purchase order or invoice screen, decomposed:

```
┌─ HEADER STRIP (always visible, ~1/5 of screen) ───────────────┐
│  No: PO-2024-0142   Date: 03/04/2024   Supplier: [SMI TUNIS]   │
│  Project/Job: [J-1001 Tour B]   Phase: [01-Struct]            │
│  Delivery: 10/04   Terms: 30J   Currency: TND   Status: ●Draft │
└────────────────────────────────────────────────────────────────┘
┌─ GRID (line items, the main work surface) ────────────────────┐
│ Line│ Item        │ Desc        │ Qty │ UoM│ PU   │ Disc│ Ext │
│  1  │ ARM-FE12   │ Fer 12mm    │ 5.0 │ T  │1850.0│  0% │9250 │
│  2  │ CIM-CPA42  │ Cimnt CPA42 │ 8.0 │ T  │ 142.5│  2% │1116 │
│ ... │            │             │     │    │      │     │     │
│ + (add line)                                                    │
└────────────────────────────────────────────────────────────────┘
┌─ TOTALS STRIP (sticky, bottom) ───────────────────────────────┐
│ Subtotal: 10 366,000   Remise: 22   TVA 19%: 1 965,4          │
│ Total TTC: 12 309,400  Retenue garantie 5%: 615,470           │
│ Net à payer: 11 693,930                                        │
└────────────────────────────────────────────────────────────────┘
┌─ TABS / SIDE PANEL: header detail, delivery addr, taxes,      │
│   approvals, attachments, notes, history ──────────────────────┐
```

Key Sage patterns visible here:

1. **Header is compact and always-on** — the user never loses context of *which document* they're in.
2. **Status pill in the header** (Draft/Sent/Received/Invoiced/Closed) — Sage CRE documents have a clear lifecycle.
3. **Project + Phase + Cost Code on every line** (Timberline's signature): each line is job-cost-tagged at entry time, not as an afterthought. This is the heart of Sage CRE's value.
4. **Totals strip includes construction-specific lines** — retention (retenue de garantie), TVA, discounts — all computed live.
5. **Tabs/side panel for "deep" header info** so the screen stays uncluttered.

### 1.4 Validation workflows in Sage

- **Document state machine**, French-style:
  `Brouillon (Draft) → En attente validation (Submitted) → Validé (Approved) → Comptabilisé (Posted) → Clôturé (Closed)`. Cancelled is a side branch.
- **Per-role approval routing.** Sage 300 CRE has an Approval Routing module: rules based on **document type + amount + cost code + project manager**. The "approval matrix" is a configuration table, not code.
- **Amount-based thresholds** are explicit in Sage CRE: e.g., POs < 5 000 TND → Project Manager; 5 000–25 000 → Site Director; > 25 000 → Direction Générale; public markets may require 2 signatures. The matrix is editable per company.
- **Approval-chain sub-screen.** Sage CRE shows a dedicated tab listing each approval step, with: approver name, role, amount limit, status (Pending / Approved / Rejected), date, and comment. This is the model to copy for HYDRA's "validation chain visualization".
- **Self-approval is blocked** by default; delegation (vacation substitution) is supported with date ranges.
- **Audit trail**: every state transition is logged with user, timestamp, before/after value — surfaced as a read-only "history" tab.

### 1.5 Multi-currency & multi-entity (relevant to HYDRA's import scenarios)

Sage 300's core differentiator is **multi-ledger + multi-currency + multi-company**. Each amount has: transaction currency, accounting (functional) currency, reporting currency. Exchange rate is stored at the transaction, not just at period close. For HYDRA this matters because Tunisian construction imports materials (steel, cement) often invoiced in EUR/USD but accounted in TND with TVA suspended under customs regimes.

---

## 2. SAP Fiori — the design language and what they learned

### 2.1 The 5 design principles (official SAP)

SAP publishes these verbatim; they are the canonical framing for Fiori:

1. **Role-based.** Apps are built for a **specific persona**, not a transaction code. "Procurement Officer", "Site Supervisor", "Project Manager (Cost)", "Accounts Payable Clerk". Each user sees a launchpad with only the apps relevant to their role. SAP GUI's `ME21N` (one tcode, all PO tasks) is decomposed into separate Fiori apps: *Create Purchase Order*, *Monitor Purchase Orders*, *Approve Purchase Orders*, *Manage Purchase Orders – Advanced*.
2. **Responsive.** Adaptive rendering across phone / tablet / desktop. The same app scales from a 360 px phone to a 4K monitor; components reflow (table → card list on phone).
3. **Simple.** The **1-1-3 heuristic**: 1 user, 1 task, 1 to 3 screens to complete. Reduce cognitive load. Default to what 80% of users need 80% of the time; advanced fields go behind "Show More".
4. **Coherent.** One visual language, one interaction model, one terminology across all apps. Built on **SAPUI5** (`sap.m` library). The same `ObjectPageSection`, `Table`, `QuickView`, `DraftIndicator` appear everywhere. A user who learns one Fiori app can use all of them.
5. **Delightful.** Beyond usable — micro-interactions, meaningful empty states, success animations. This is the deliberate antidote to SAP GUI's reputation for joyless density.

### 2.2 SAP GUI → Fiori — the actual transformation

SAP GUI (the classic "SAP screen") was:

- **Transaction-code driven.** Power users typed `/nME21N` to create a PO. Each tcode was a mega-screen: header strip, 5+ tabs (Header Data, Item Overview, Item Detail, Account Assignment, Partner, Conditions, Attachments…), each tab with dozens of fields. Expert-only.
- **Dense, optimized for keyboard experts** who'd memorized the field order. Fast once mastered; impenetrable otherwise.
- **One transaction does everything** for that business object — create, change, display, list, approve all in variants of the same screen.

Fiori deliberately **split** these mega-transactions:

- **Transactional apps** — one task, one screen, often wizard-style (1-1-3). E.g., *Create Purchase Order* becomes a guided 3-step wizard.
- **Object pages / Fact sheets** — a single business object (a PO, a supplier, a project) shown as a header KPI band + tabbed sections. This is the **Object Page** pattern, the single most reusable Fiori layout.
- **Analytical apps** — dashboards, KPI tiles, charts (built on SAP Analytics Cloud).
- **Overview pages / List Reports** — filterable lists with smart variants; "List Report + Object Page" is the canonical Fiori Elements pattern (generated from OData annotations, almost no code).

### 2.3 "Single transaction per screen" — what it really means

It means: **the screen is scoped to exactly one user intent**, not to a business object's full lifecycle. Concretely:

| SAP GUI tcode | One screen, multiple intents | Fiori apps (each its own screen) |
|---|---|---|
| `ME21N` | Create / change / display / approve PO all from one screen | *Create PO* • *Monitor POs* • *Approve POs* • *Display PO* |
| `MIRO` | Enter / park / post invoice | *Create Supplier Invoice* • *Park Supplier Invoice* • *Approve Invoices* |

The cost: **power users complain Fiori is "clickier"** for high-volume entry. A clerk who could post 200 invoices/hour in SAP GUI with F-codes finds Fiori slower. SAP's response was:

- **Fiori Elements with editable tables + keyboard shortcuts** (still not as fast as GUI).
- **Draft handling** — server-side drafts auto-save every few seconds so context is never lost.
- **"Expert mode" / mass-edit apps** reintroduced for high-volume roles (e.g., *Manage Purchase Orders – Advanced* brings back the dense table for procurement pros).
- **Fiori 3 / Horizon (2021+)** — a visual refresh toward more cheerful colors & typography, and the introduction of more compact "floorplans" to win back power users.

**Lesson for HYDRA:** Don't make every screen a beginner-friendly wizard. Have **two modes** of the same business object: a guided "create" path for occasional users, and a dense editable-grid "manage" path for power users — exactly SAP's lesson.

### 2.4 The Object Page pattern (the single most copyable Fiori artifact)

```
┌─ OBJECT PAGE HEADER (sticky, ~25% of viewport) ──────────────┐
│  PO 4500000142            ●Approval: Pending                  │
│  Supplier SMI TUNIS       Total: 12 309,400 TND  Delivery 10/4│
│  ─ KPIs row ─  On-time ●  Variance +2%  Open 1 of 5 lines      │
│  [Edit] [Submit for Approval] [Print] [More ▾]                │
└────────────────────────────────────────────────────────────────┘
┌─ TABS / ANCHORED SECTIONS ────────────────────────────────────┐
│  General │ Items │ Account Assignment │ Attachments │ Approvals│
│  ┌─ Items table ─────────────────────────────────────────┐    │
│  │  line items editable grid here                         │    │
│  └────────────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────────┘
```

- Header KPIs are computed live and **persisted** in the page (not recomputed on scroll).
- Tabs are anchor links (single page, smooth scroll) **and** real navigation — you can deep-link to `#items` or `#approvals`.
- The header actions (`Submit for Approval`, `Post`, `Cancel`) follow the document state — unavailable actions are hidden, not greyed.
- **Draft indicator** in the header shows "Unsaved changes • Saved 3s ago" — small but essential for trust.

### 2.5 What SAP learned (the post-mortem on the GUI→Fiori migration)

- **Don't try to replace SAP GUI 1:1.** Some power-user flows need density; Fiori Elements compact tables + keyboard support exist precisely because the 1-1-3 purism failed for them.
- **Draft is mandatory** — without auto-save, web UX feels fragile vs. desktop.
- **Role catalog governance is hard.** Companies struggle to decide which apps each role sees; the launchpad becomes cluttered without discipline.
- **Performance is part of UX.** Fiori's reputation suffered when apps were slow due to OData round-trips; SAP invested heavily in `$select`, `$expand`, and client-side caching.
- **Annotations beat code.** Fiori Elements (annotated OData → rendered UI) won the internal war against hand-coded SAPUI5 because annotations evolve with the data model.

---

## 3. Odoo — kanban + list + form, "less is more but customizable"

### 3.1 The triad: kanban / list / form

Odoo's standard "document lifecycle" UI is a consistent three-view package available in nearly every module (Sales, Purchase, Inventory, Accounting, Project, Manufacturing, Field Service…):

1. **Kanban view** — cards grouped by stage. This is Odoo's default landing for process objects (opportunities, tasks, RFQs, submittals). Each card shows a title, partner, amount, priority star, and a small avatar of the responsible. Columns are stages you drag between. Visual + fast + emotional.
2. **List view (tree)** — the dense tabular view; supports inline editing, column selection, saved filters, and pivot-able by default. Multi-line selection; batch actions on selection.
3. **Form view** — the detail. Odoo form views have a very recognizable anatomy (see next).

### 3.2 Form view anatomy (the most reused Odoo artifact)

```
┌─ STATUS BAR (top) ──────────────────────────────────────────┐
│  [●Draft]  [Sent]  [Confirmed]  [Done]   ← state highlight   │
│  ...actions on the right: [Send by Email] [Confirm] [Print]  │
└──────────────────────────────────────────────────────────────┘
┌─ SHEET (main content, looks like a paper form) ─────────────┐
│  ┌── Title block ───┐   ┌── Key fields (right column) ───┐ │
│  │ Customer         │   │ Order Date: 03/04/2024          │ │
│  │ [SMI TUNIS]   ★  │   │ Expiration: 10/04/2024         │ │
│  │ Reference SO042  │   │ Salesperson: [Amine]           │ │
│  └──────────────────┘   └────────────────────────────────┘ │
│  ┌── Tabs ────────────────────────────────────────────────┐ │
│  │ Order Lines │ Other Info │ Terms & Conditions │ ...     │ │
│  │ ┌─ editable tree ──────────────────────────────────┐   │ │
│  │ │  Product │ Desc │ Qty │ UoM│ PU   │ Subtotal     │   │ │
│  │ │  ...editable inline...                            │   │ │
│  │ └──────────────────────────────────────────────────┘   │ │
│  └───────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
┌─ CHATTER (right rail or bottom) ────────────────────────────┐
│  ✉ Messages  ✓ Activities  ⚑ Followers                       │
│  • Amine created this quotation — 03/04 09:12                │
│  • Email sent to supplier — 03/04 09:15                      │
│  ✓ Call supplier — due 04/04 — [Done]                        │
└──────────────────────────────────────────────────────────────┘
```

Three Odoo signatures:

- **Status bar at top** — the document lifecycle as a horizontal pill sequence with the current state highlighted. Clicking a future state performs the transition (calls the relevant workflow method). This is Odoo's *validation chain visualization* — simpler than SAP's, but enough for 90% of SMB flows.
- **Sheet that looks like a paper form** — left column = identity, right column = metadata (dates, owners). This paper metaphor lowers the learning curve for users coming from Excel/paper.
- **Chatter** — bottom or right-side activity stream that logs every change (audit), every email sent/received, and a scheduled-activities queue ("Call supplier", "Send reminder"). The chatter is the **single best UX decision in Odoo** for SMB ERPs because it folds messaging, audit, and tasks into one place users actually read.

### 3.3 "Less is more but customizable" — the philosophy, concretely

- **Apps install/uninstall like Legos.** Sales, Purchase, Inventory, Manufacturing, Accounting, Project, Timesheets, Maintenance, Field Service, Spreadsheet, Studio, Approvals, Sign, etc. — each is a module you toggle on/off. A minimal install is < 10 apps; a maximal one is 40+.
- **Studio (no-code customizer).** Power users (or integrators) add fields, views, actions, automated actions, email templates, reports — without Python. This is Odoo's answer to "every customer wants a tweak."
- **Defaults are opinionated.** Odoo ships with sensible defaults (auto-sequences, default journals, default taxes) so a fresh install works out-of-the-box. Customize later when you hit a wall.
- **Verticals via partners, not core.** Odoo SA keeps the core lean; vertical functionality (construction, retail POS hardware, EDI, local payroll) lives in partner modules / Odoo Apps Store.

### 3.4 Construction in Odoo — what it does and doesn't do

Odoo has **no native "Construction" module** in core. The de-facto construction stack assembled by partners is:

| Odoo app | Used for |
|---|---|
| **Project** | WBS (analytic account = project), tasks, sub-tasks, Gantt (Enterprise) |
| **Timesheets** | Labor cost captured against project/analytic |
| **Purchase + RFQ** | BC, supplier negotiation |
| **Inventory** | Stock moves, multi-warehouse, picking (BL equivalent) |
| **Maintenance** | Equipment on site |
| **Field Service** (Enterprise) | Site interventions, worksheets, mobile |
| **Accounting** (Enterprise) | Analytic accounting, multi-currency, budgets |
| **Maintenance / Quality** | Quality checks on receipt |
| **Approvals** (Enterprise) | Approval requests (lightweight) |
| **Sign** | Digital signatures on documents |
| **Spreadsheet** (Enterprise, v17+) | Pivot + spreadsheet combo for reporting |

**What Odoo doesn't give you out of the box for BTP:** job-costing at the cost-code/phase level (Timberline-style), progress billing (décompte), retention management (retenue de garantie), bond/caution tracking, public-market compliance, RFI/Submittal/Change-Order workflows, drawing markups. Partners build these as custom modules.

### 3.5 Odoo's pivot to spreadsheet-like views

Since v15 (and stronger in v17/v18), Odoo has leaned into spreadsheet-grade UX:

- **Pivot view** — a real pivot-table view: row groupings, column groupings, measures, drill-down. For finance reporting, this replaces a lot of "report wizard" UX.
- **Spreadsheet app (Enterprise)** — an actual collaborative spreadsheet (Built on the Sheet engine Odoo acquired) embedded in dashboards, with formulas referencing Odoo live data (`=ODOO.LIST.INDEX(...)`, `=ODOO.PIVOT(...)`). It's bidirectional — changing the data changes the sheet; reading from the sheet recomputes.
- **Paste-from-clipboard in list views** — copy a range from Excel, paste into an Odoo list view, and rows are created. This is the same killer feature as Sage grids.
- **Inline-editable lists** — many list views support direct cell editing (no need to open each record).

### 3.6 What to steal from Odoo for HYDRA

- **Status bar + chatter as the default document header/footer** — cheap to build, high perceived quality.
- **Kanban as the default landing** for BC/BL/RFI queues ("À valider", "En cours", "Clôturé" columns).
- **Pivot view as a first-class report** for cost-by-phase, cost-by-supplier, etc.
- **Excel paste into grids** — the single feature Tunisian accountants will thank you for.
- **Apps/modules decomposition** so HYDRA can ship a minimal BTP core (Sales/Purchase/Stock/Accounting) and let customers add Project/Job-Cost/Field-Service as they grow.

---

## 4. Procore — construction-specific, mobile-first, tool-as-tab

### 4.1 Navigation model: every construction object is a "Tool"

Procore's defining UX decision is that **the navigation bar IS the construction domain**. Instead of generic modules (Sales, Inventory, Accounting), you see the actual BTP objects:

**Top nav (project-level), grouped:**
- **Project Management:** Drawings, Specifications, Submittals, RFIs, Change Orders (Potential / Commitment / Prime), Contracts, Commitments (Subcontracts), Invoices (Pay Applications), Schedule, Meetings, Punch List
- **Quality & Safety:** Observations, Inspections, Incidents, Meetings, Checklists, Deficiency Lists
- **Cost Management:** Budget, Prime Contract, Commitments, Change Events, Change Orders, Pay Applications, Invoicing, Forecasting
- **Field Productivity:** Daily Log, Manpower Log, Equipment Log, Delivery Log, Photos, Documents
- **Admin:** Directory, Configurable Field Sets, WBS, Locations, Workflows

Each tool is a **tab**, and inside each tool the layout is a consistent **master-detail list**:

```
┌─ TOOL HEADER ───────────────────────────────────────────────┐
│  RFIs          [+ Create]   [Filter ▾]  [Group ▾]  [View ▾]   │
├─ LEFT FILTER / LIST ────┬─ RIGHT DETAIL ────────────────────┤
│  # RFI-0142  Open      │  RFI-0142 — Clarify rebar spec    │
│  # RFI-0141  Answered  │  Status: Open   Ball in court: GC │
│  # RFI-0140  Closed    │  Cost impact: 12,000 TND          │
│  ...                   │  Schedule impact: 2 days          │
│                        │  Drawing ref: A-301 sheet 2       │
│                        │  Attachments: [rebar photo.pdf]   │
│                        │  Distribution: [architect] [GC]    │
│                        │  [Respond] [Close] [Assign]        │
└────────────────────────┴───────────────────────────────────┘
```

The pattern is uniform across RFIs, Submittals, Change Orders, Punch Items, Daily Logs, etc. — that uniformity is Procore's coherence win (Fiori's principle #4, applied to construction).

### 4.2 The big five BTP documents — how Procore models each

#### 4.2.1 RFI (Request for Information)
- **Fields:** number, subject, status (Draft / Open / Answered / Closed / Void), question, received date, due date, ball-in-court (assignee), cost-impact amount, schedule-impact days, related drawing sheet/number, related specification section, attachments, distribution list (everyone who must see the answer).
- **Workflow:** Draft → Submit → Open (assignee has the "ball") → Responded → Closed. Every transition logs who/when/comment.
- **UX pattern:** the **"Ball in Court"** concept — a single highlighted avatar showing who is currently responsible. Stolen broadly across construction SaaS. HYDRA should copy this verbatim.

#### 4.2.2 Submittal
- Submittal log: registers every shop-drawing / material sample / catalog cut the contractor must submit to the architect/engineer for approval before fabrication.
- Fields: submittal #, title, spec section, responsible contractor, status (Open / Returned / Approved / Approved as Noted / Rejected), revision #, return date, attachments.
- A submittal "package" can group multiple submittals sent together.

#### 4.2.3 Change Order (3-tier)
Procore's change-event → PCO → CO model is the most sophisticated in the field:
- **Change Event** — something happened (variation, extra work). Captured early, may or may not become a cost.
- **Potential Change Order (PCO)** — a formalized potential change with cost & schedule impact, awaiting approval to be incorporated.
- **Commitment Change Order (CCO)** — modifies a subcontract (commitment).
- **Prime Contract Change Order (CO)** — modifies the contract with the owner.
- **Cost coding**: every CO is broken down by **cost type** (Labor, Material, Equipment, Subcontract, Other) × **cost code** (WBS). Budget impact is computed live.
- **Approval matrix by amount** is a configuration: < threshold auto-approved; > threshold requires superintendent + PM; > larger requires owner.
- This 3-tier model is **the** design to copy for HYDRA's marché change-order handling.

#### 4.2.4 Invoice / Pay Application (décompte)
- **Schedule of Values (SOV)** — the contract broken into line items with scheduled values.
- **Periodic application:** for each period, % complete per line, work completed this period, stored materials, retention.
- Computed: total earned, less previous applications, less retention = amount due.
- This is exactly the Tunisian **décompte** model — Procore's SOV ↔ HYDRA's attachement/décompte.

#### 4.2.5 Daily Log
- A daily log is actually a **collection of sub-logs**: Daily Construction Report, Manpower (by trade & subcontractor), Equipment, Deliveries, Visitors, Accidents/Incidents, Weather, Delays, Remarks, Quantities Placed.
- Mobile-first: tap a button, take photos with the camera, the photo's GPS & timestamp are captured, the entry is queued for sync.
- This is the pattern for HYDRA's "journal de chantier".

### 4.3 Mobile-first site approach

- Procore's mobile apps (iOS/Android) are not "the website on a phone" — they're purpose-built for **single-document capture** on site.
- Big tap targets, single column, sticky primary action at the bottom (Apple HIG-style).
- **Offline capture**: photos, daily-log entries, RFI creation, punch-list items can be created offline; queued and synced when back online.
- **Camera/QR integration**: scan a QR code on equipment → open its record; take a photo → it's auto-attached to the current RFI/Punch item.
- **"Field" persona vs "Office" persona**: the mobile app shows a reduced tool set focused on capture; the web app shows the full tool set.

### 4.4 Configurable Field Sets — Procore's localization secret

Procore lets admins configure, per project, which fields appear on each tool's forms, whether they're required, and what the dropdown values are. This is **how Procore adapts to different GCs, regions, and contract types without code**. HYDRA should adopt the same: a per-tenant form-config layer that decides which fields/sections render for BC, BL, RFI, Daily Log, etc.

---

## 5. PlanGrid / Autodesk Construction Cloud — sheets-first, document-centric

### 5.1 PlanGrid's core bet: the sheet is the spine

PlanGrid (founded 2011, acquired by Autodesk 2018) started as "iPad for blueprints". The defining UX choice: **the drawing sheet is the central object everything else hangs off of**.

- **Sheet viewer** is the primary screen, not a list.
- **Sheets are versioned**: every revision is a new sheet; old sheets retained. You can overlay two versions and Procore/PlanGrid shows **cloud-style diff** highlighting what changed (the classic "delta cloud" on revisions).
- **Automatic hyperlinking ("bubble sheets")**: detail callouts on the drawing (the little circles that say "see Detail 4 / A-301") become clickable — tap and you jump to that detail. This was PlanGrid's signature feature; it converts paper conventions into navigation.
- **Markup tools**: cloud, arrow, freehand, text, photo pin, hotspot, callout. Markups sync across devices in real time.
- **Markups → Issues / RFIs**: any markup can be promoted to a tracked Issue or RFI, carrying the sheet + coordinate + photo context with it. This is the bridge between document-centric and process-centric.

### 5.2 Pinning objects to coordinates

The conceptual breakthrough: **every site observation has a location**. A photo, an RFI, a Punch item, a Daily Log entry — each can be pinned to a point on a sheet. You see, on the drawing, pins colored by status. Tap a pin → see the related record.

This is the document-centric analog of Sage's job-cost tagging: in Sage every line has a cost code; in PlanGrid every observation has a sheet+coordinate.

### 5.3 Autodesk Construction Cloud — unified platform

ACC unifies PlanGrid (sheets/field), BuildingConnected (preconstruction/bidding), Assemble (quantities from BIM), BIM 360 (model coordination), Pinnacle (capital planning). The ACC Build suite modules:

- **Sheets** (PlanGrid heritage)
- **Models / Model Coordination** (BIM 360)
- **RFIs, Submittals, Issues, Inspections, Checklists, Forms** (construction workflows)
- **Daily Reports, Field Notes**
- **Locations** (WBS of site locations: Site → Building → Floor → Room)
- **Assets** (equipment tracking)
- **Cost Management** (contract management + change orders + pay apps)
- **Schedule** (P6 / MS Project integration)
- **Photos** (with AI auto-tagging since 2022+)

### 5.4 What HYDRA should learn from PlanGrid/ACC

- **A "viewer" is a first-class UI surface, not an afterthought.** If HYDRA ever manages drawings (and BTP means drawings), build a sheet viewer with versioning & markups as a peer to the data-entry grids.
- **Pin to coordinate** — let a BL line or an RFI carry a sheet + location reference. Even if HYDRA doesn't render drawings, the **location WBS** (Procore/ACC's `Project → Building → Floor → Room/Zone`) is a must-have for filtering site data.
- **Markup → workflow item**: if you have any annotation feature, let users convert it to a tracked task. Don't keep annotations and tasks in separate universes.

---

## 6. Localization — French / Tunisian / North-African markets

### 6.1 The localization trap to avoid

The #1 localization failure for ERPs entering Tunisia is to assume **"French law = Tunisian law"** because the language and the *Charte Comptable* lineage are shared. They are not the same legal/tax/social regime. Sage 100 (France) works in Tunisia only with a Tunisian localization pack; SAP needs a Tunisia country version; Odoo has community + enterprise localization modules that lag.

### 6.2 What must be localized — concrete checklist

| Dimension | France (reference) | Tunisia (HYDRA target) | Why it matters for UX |
|---|---|---|---|
| **Language** | French only (LTR) | **French + Arabic** (RTL), often bilingual on same form | UI must support per-field direction; some labels FR only, others AR only; mixed-script entry |
| **Currency** | EUR, 2 decimals | **TND, 3 decimals** (millimes) | Number inputs, displays, totals must support 3-decimal; rounding rules differ |
| **Number format** | `1 234,56` (space thousands, comma decimal) | `1 234,567` (3-decimal) | Same separators, different precision |
| **Date** | `DD/MM/YYYY` | `DD/MM/YYYY` | Same |
| **VAT (TVA)** | 20% std, 10%, 5.5%, 2.1% | **19% std, 13%, 7%**; **suspended regime** for exports & certain materials | Tax engine must support multi-rate, suspension, reverse-charge |
| **Withholding (RAS)** | Limited | **1.5% on marché (public works)**, other rates by case | RAS as a first-class line item on supplier invoices |
| **Social charges** | URSSAF ~45% total | **CNSS employer 16.57% + employee 9.18%**, ceiling; **TFP 2%, FOPROLOS 1%** | Payroll module is mandatory; not optional |
| **Public procurement** | Code des marchés publics | **Marchés publics Tunisian code** (cahier des charges, décompte, cautionnement, retenue de garantie) | Marché as a top-level object; décompte workflow |
| **Retention** | Common | **Retenue de garantie 5–10%** standard on marchés | Retention as a tracked liability, released at handover |
| **Bonds (cautions)** | Common | Cautionnement provisoire + définitif; bank guarantees | Bond tracking + expiry alerts |
| **Chart of accounts** | PCG France | **PCN (Plan Comptable National Tunisien)** | Different account numbering; accounts Receivable/Payable semantics differ |
| **Bank format** | RIB 23 chars, SEPA | RIB 20 digits, no SEPA | Validation regex & display layout differ |
| **E-invoicing** | Factur-X / Chorus Pro | **SNDP / e-invoicing rolling out (2024+)** | Document format & submission API differ |
| **Holidays / calendar** | French public holidays | Tunisian public holidays (Aïd, Mouled, New Year Berbère, etc.) | Calendar & date pickers must localize |

### 6.3 Arabic + French together — UX patterns that work

- **Bilingual labels** are common in Tunisian admin forms: French label + Arabic label side by side (e.g., `Référence / المرجع`). On screen this is usually **stacked** (FR top, AR below) rather than side-by-side to preserve column widths.
- **Per-field direction.** A French supplier name stays LTR even on an RTL screen; an Arabic description is RTL. The text input itself should follow its content's first strong character (Unicode bidi heuristic), not the page direction.
- **Right-side form layout for Arabic** — the whole form mirrors (label on the right, value on the left, action buttons in the top-left). This is automatic if you use proper CSS logical properties (`margin-inline-start` etc.) instead of physical (`margin-left`).
- **Numbers stay Western-Arabic digits (0-9) in Tunisia** even in Arabic UIs (unlike Gulf Arabic which sometimes uses Eastern-Arabic ٠-٩). Keep the Latin digit forms.
- **Document PDFs**: French template for commercial docs (BC/BL/factures), Arabic for some official statements; some marches require both languages side-by-side on the cover page.
- **Date in documents**: `Le 03 avril 2024` (FR) ↔ `03 أفريل 2024` (AR with Tunisian month names: أفريل not أبريل). Tunisian Arabic month names differ from Gulf/Maghreb variants — get this right.

### 6.4 What fails — observed failure modes

- **Copying the French PCG chart and renaming accounts** → breaks against PCN numbering, breaks statutory reports (liasse fiscale, déclaration TVA, déclaration CNSS).
- **2-decimal currency** → wrong amounts on every TND document with millimes.
- **No RAS line** → supplier invoice totals wrong on marchés.
- **No retention tracking** → cash flow & liability reporting wrong.
- **Assuming French public-holiday calendar** → scheduling/SLA misses in Marché deadlines.
- **Single-language UI** → cuts out half the workforce (especially older site supervisors more comfortable in Arabic).
- **Heavy reliance on real-time cloud** → fails on Tunisian construction sites where 4G is patchy and indoor signal is poor. **Offline-first is not a nice-to-have in North-African BTP, it's a survival requirement.**
- **PDF templates that don't match Tunisian fiscal requirements** (mention obligatoire, TVA breakdown by rate, RAS, mode de paiement) → invoices legally invalid.

### 6.5 What works — observed success patterns

- **Ship the legal/tax layer as a swappable localization pack**, not hardcoded. SAP, Sage, Odoo all do this: a Tunisia module that overrides French defaults. HYDRA should architect the same: a `localization-tn` module that overrides base assumptions.
- **Per-tenant tax matrix** so different regimes (suspended, exports, public marchés, private) coexist.
- **Bilingual document templates** generated from one source (i18n keys), not duplicated PDFs.
- **Localization of number/date/currency handled at a formatting layer** (ICU / Intl), not by string-concatenating.

---

## 7. Concrete construction-ERP UX patterns — deep dive

### 7.1 Multi-line document entry (BC with 50+ line items)

**The fundamental tension:**

| Surface | Pros | Cons | When |
|---|---|---|---|
| **List view** (read-only rows, click → side panel edit) | Clean, fast to scan, great on mobile | Slow for high-volume entry; too many clicks | Review tasks, occasional entry |
| **Editable grid** (inline cell editing, like Sage) | Fast power-user entry, keyboard friendly, dense | Validation UX harder; mobile-unfriendly | Office entry of BC/BL with many lines |
| **Spreadsheet** (true formulas, paste, calc) | Accountants love it; familiar | Loses structured validation; hard to bind to domain rules | Ad-hoc analysis, budget import |

**Best practice — hybrid the three:**

1. **Default = editable grid** for desktop BC/BL/factures. Keyboard navigation (Tab/Enter/arrows/F2), Excel paste, formula columns, sticky totals row.
2. **"Spreadsheet mode" toggle** that renders the same data in a true spreadsheet surface (formulas, drag-fill) for power users — but every change still round-trips through the domain validator on save.
3. **Mobile = card list** with "Add line" big button, swipe-to-delete, collapse to header on small screens.

**Concrete UI rules (steal from Sage + Odoo):**

- **Header strip always visible** (document number, partner, date, status pill, total) — even when grid scrolls. Sage does this; Fiori Object Page does this. HYDRA must too.
- **Totals strip always visible at bottom** — sticky, with subtotal, TVA by rate, retention, net payable. Updates live as you type.
- **Keyboard map documented in-screen** — a collapsible "?" panel listing Tab / Shift+Tab / Enter / F2 / Ctrl+D / Ctrl+; This is how Sage onboards new users.
- **Lazy validation per cell, eager validation per document.** Red cell border + tooltip on blur; blocking modal only on Save.
- **Add-line UX:** `+` button always present; **Ctrl+Enter** duplicate current line (huge time-saver for repetitive items); **Ctrl+Shift+V** paste-from-Excel.
- **Line numbering preserved** through edits (don't renumber on delete — that breaks references). Sage/Procore both preserve.
- **Virtualize the grid** when > 100 lines. Sage CRE handles thousands of lines on a single PO via virtualization; naive React/Vue tables die.
- **Column config saved per user per document type** (which columns visible, in what order, with what width). Odoo, SAP, Sage all do this. Free UX win.
- **"Project / Phase / Cost Code"** columns **on every line** for BTP — this is non-negotiable for job-costing (Sage CRE's core).

### 7.2 Validation chain visualization

**Patterns ranked by cost/value for HYDRA:**

| Pattern | Cost | Value | Copy from |
|---|---|---|---|
| **Status bar** (horizontal pill sequence, current highlighted) | Low | High | Odoo |
| **State pill + color** (Draft/Sent/Approved/Posted/Closed) | Low | High | Sage |
| **Approval-chain sub-screen** (table: step, approver, role, limit, status, date, comment) | Medium | High | Sage CRE |
| **Timeline / stepper** (vertical timeline of transitions with avatars) | Medium | Medium | Fiori |
| **Workflow diagram** (nodes & edges, current node highlighted) | High | Low for SMB | SAP Workflow |
| **"Ball in court"** avatar (who's responsible now) | Low | Very High | Procore |

**Recommended HYDRA combo:** status bar at top of every document + "ball in court" avatar + an Approvals tab with a Sage-style table. Skip the workflow diagram unless you have > 5 approver levels (you won't, in SMB BTP).

**Color/label conventions** (be consistent document-wide):
- Grey = Draft
- Blue = Submitted / In review
- Amber = Pending approval (ball in someone's court)
- Green = Approved / Validated / Posted
- Red = Rejected / Cancelled / Error

### 7.3 Amount-based approval thresholds

**The model Procore + Sage CRE converge on:**

```
Rule = (document_type, cost_code?, project?, amount_range) → [approver_role(s), mode]
```

- **Mode:** sequential (one after another) vs parallel (N-of-M) vs first-match (first rule that fires).
- **Delegation:** approver can delegate with date range; the system resolves the active approver at runtime.
- **Self-approval block:** requester never approves own request (enforce in code, not in UI).
- **Auto-resolution:** the system pre-computes the approver chain at submission and shows it to the requester ("This PO will be approved by: Site Director → DG"). Sage does this; it sets expectations.
- **Threshold visualization on the document:** show "Approver: [Name] (limit: 25 000 TND)" next to the amount — so submitters see immediately if their amount crosses a threshold.
- **Budget-aware escalation:** if the line's amount would blow the project's remaining budget, escalate one more level even if amount is below threshold. Procore does this for commitment change orders.
- **Audit:** every approval/rejection logged with amount-at-time-of-approval (amount may change later).

### 7.4 Mobile site data entry (BL on phone)

**Hard rules:**

- **One document per screen.** Don't try to show 50-line BL on a phone in a grid. Show: header (collapsible), then a list of line cards, then a sticky "+ Add line" + "Save as draft" + "Submit" footer.
- **Big tap targets** (min 44×44 px, Apple HIG). Sage's grid is unusable on phone; Procore's cards are perfect.
- **Barcode/QR scan** as primary input for article & location. Type the rest.
- **Photo capture** with auto-attach to the BL — the receipt photo is half the value of a BL on site.
- **Voice-to-text** for description / notes — Tunisian site supervisors will dictate in Arabic or French; both should work.
- **Offline-first** (see 7.5) — submission queued if offline.
- **Optimistic UI:** tapping "Submit" immediately shows ✓ Submitted, then syncs. Don't block on network.
- **"Synced / Pending / Conflict" badges** on every draft.

**Line entry UX on phone (recommended):**

```
┌─ BL header (collapsible) ────────────────────┐
│ BL-0042  ●Draft   Supplier: SMI TUNIS         │
│ Chantier: Tour B   Date: 03/04    [▼ details]│
├─ Lines (cards) ──────────────────────────────┤
│ 1. Fer 12mm   5.0 T × 1850 = 9 250 TND   [⋮] │
│ 2. Cimnt CPA42 8.0 T × 142.5 = 1 116 TND [⋮] │
│ ──────────────────────────────────────────── │
│ [+ Add line]            (scan QR for item)    │
├─ Footer (sticky) ────────────────────────────┤
│ Subtotal 10 366  TVA 1 965  Total 12 309 TND │
│ [Save draft]              [Submit ▶]           │
└──────────────────────────────────────────────┘
```

### 7.5 Offline-first for site workers

**Architecture pattern (used by Procore, PlanGrid, Field Service apps):**

- **Local DB mirror** (IndexedDB on web PWA; SQLite on native) holding last 24-72h of the user's relevant data.
- **Mutation queue** — every create/update goes to a queue; a background sync drains it when online.
- **Conflict resolution policy** — for BTP, "last-write-wins with audit log" is usually fine; for amounts/financial docs, "manual merge prompt" is safer. Pick per entity.
- **Service Worker (PWA)** caches the shell + assets; works on installable home-screen icon (no app store needed).
- **Bandwidth awareness** — sync batches, compress photos before upload (resize to ~1600px), use deltas not full docs.
- **Status surfaces:**
  - Per-document badge: `●Synced` / `◷Pending` / `⚠Conflict`
  - Global sync indicator in the header
  - "Retry failed" button

**Concrete HYDRA recommendation:** build the BL/Daily-Log/RFI capture as a **PWA with offline-first**, not a pure online web app. Tunisian site reality: 4G is patchy inside under-construction buildings; workers will not tolerate "spinner of death". Procore's success in the field is partly because offline "just works".

---

## 8. HYDRA synthesis — what to learn from each, and a concrete next-action list

### 8.1 Per-vendor "steal list" for HYDRA

**From Sage (300 CRE + 100):**
1. The **editable grid as primary data-entry surface** for BC/BL/factures/journal entries — with Excel-grade keyboard navigation, Excel paste, formula columns, sticky running-totals row.
2. **Project/Phase/Cost-Code on every line** as a non-optional column (job-costing heart).
3. **Approval-chain sub-screen**: table of step / approver / role / amount-limit / status / date / comment.
4. **State machine:** Brouillon → En attente → Validé → Comptabilisé → Clôturé.
5. **Header strip + grid + totals strip + tabs** layout as the canonical document screen.

**From SAP Fiori:**
1. **Object Page pattern** for document detail (sticky KPI header + anchored sections).
2. **One app = one task = one persona** decomposition — split BC creation, BC approval, BC monitoring into separate "apps" on a role-based launchpad.
3. **Two modes** per business object: guided "create" for occasional users, dense "manage" grid for power users.
4. **Draft auto-save** with visible "saved Xs ago" indicator.
5. **Role-based launchpad** as the home screen; only the apps each role needs.
6. **Annotations-driven UI generation** (your data model → default CRUD UI with minimal code).

**From Odoo:**
1. **Status bar at top + sheet + chatter** as the default form anatomy.
2. **Kanban as default landing** for queues (BC to approve, BL pending, etc.).
3. **Pivot view** for cost-by-phase / cost-by-supplier reporting.
4. **Excel paste into grids** — non-negotiable for Tunisian accountants.
5. **Modular apps** install/uninstall so HYDRA ships a minimal BTP core and grows.
6. **Chatter** (messages + activities + followers + audit) as the social layer on every document.

**From Procore:**
1. **Tool-as-tab navigation** named after BTP objects (RFI, Submittal, CO, Décompte, Daily Log, Marché) — not generic modules.
2. **"Ball in court"** avatar on every in-flight document.
3. **3-tier change order model**: Change Event → Potential CO → Commitment CO / Prime CO. Map to Tunisian marché / avenant.
4. **Schedule of Values (SOV) → décompte** periodic pay-app pattern — direct fit for HYDRA's attachement/décompte.
5. **Configurable Field Sets** — per-tenant form configuration (which fields, required, dropdown values) without code.
6. **Mobile-first site capture** with photo/QR and offline queue.

**From PlanGrid / ACC:**
1. **Sheet-as-spine** if HYDRA ever manages drawings: versioned sheets, markups → workflow items.
2. **Location WBS** (Project → Building → Floor → Zone) as a first-class dimension for filtering site data, even without drawings.
3. **Pin-to-coordinate** semantics: every observation carries its location.

**From Localization lessons:**
1. **Localization pack architecture** — `localization-tn` overrides base; not hardcoded.
2. **Bilingual FR/AR UI** with per-field bidi; Tunisian Arabic month names.
3. **3-decimal TND** everywhere.
4. **RAS, CNSS, TFP, FOPROLOS, retenue de garantie, cautionnement** as first-class domain concepts, not afterthoughts.
5. **PCN** chart of accounts (not PCG France).
6. **Marché / décompte / attachement** as top-level objects.

### 8.2 HYDRA proposed screen architecture (concrete)

Combining the above, the canonical HYDRA document screen (BC, BL, facture, marché, décompte) should be:

```
┌─ LAUNCHPAD (role-based) ────────────────────────────────────┐
│  Chantier Manager  |  [BC à valider: 3]  [BL en attente: 7] │
│  Apps:  Achats  Stock  Chantier  Compta  Marchés  RH  RAP   │
└──────────────────────────────────────────────────────────────┘
↓ click "Bon de Commande"
┌─ BC LIST (kanban by state) ─────────────────────────────────┐
│ Brouillon │ En attente │ Validé │ Reçu │ Clôturé            │
│ [BC-0142] │ [BC-0139] │ ...                                  │
└──────────────────────────────────────────────────────────────┘
↓ click a BC
┌─ OBJECT PAGE (Fiori-style) ────────────────────────────────┐
│ Status bar: Brouillon → En attente → Validé → Reçu → Clôturé│
│ ●Ball in court: [Amine, Acheteur]                          │
│ Header: BC-0142 | SMI TUNIS | Total 12 309,400 TND | Date   │
│ KPIs: Lignes 5 | Retard 0j | Budget chantier +2%            │
│ [Modifier] [Soumettre] [Imprimer] [⋮]                       │
├─ Sections (anchored tabs) ─────────────────────────────────┤
│  Lignes │ Approbations │ Livraisons │ Pièces │ Historique   │
│  ┌─ Editable grid (Sage-style) ─────────────────────────┐    │
│  │ Ligne│Article│Desc│Qté│UoM│PU│Projet/Phase│Code-Cout│Ext││
│  │ 1│ARM-FE12│..│5.0│T│1850│Tour B/01-Struct│21-Rebar│9250││
│  │ + (Ctrl+Enter dup, Ctrl+Shift+V paste Excel)         │   │
│  └──────────────────────────────────────────────────────┘    │
│  ┌─ Totals strip (sticky) ───────────────────────────────┐   │
│  │ Sous-total 10 366 | Remise 22 | TVA 19% 1 965 | Total 12 309,4 │ Retenue 5% 615,5 | Net 11 693,9 TND │
│  └──────────────────────────────────────────────────────┘   │
│  ┌─ Approbations (Sage-style table) ─────────────────────┐  │
│  │ Étape│Rôle│Limite│Approbateur│Statut│Date│Commentaire │  │
│  │ 1│Acheteur│25 000│Amine│✓ Approuvé│03/04│OK          │  │
│  │ 2│Resp. Chantier│100 000│[Sami]│◷ En attente│—│—      │  │
│  │ 3│DG│∞│[Mme Ben]│— En attente│—│—                   │  │
│  └──────────────────────────────────────────────────────┘  │
├─ CHATTER (Odoo-style, right rail) ─────────────────────────┤
│  ✉Messages  ✓Activités  ⚑Abonnés                            │
│  • Amine créé ce BC — 03/04 09:12                            │
│  • Approuvé par Amine — 03/04 09:15                          │
│  ✓ Relancer Sami — échéance 04/04 — [Fait]                   │
└──────────────────────────────────────────────────────────────┘
```

This single screen combines: Sage's grid + totals, Fiori's object page + KPIs, Odoo's status bar + chatter, Procore's ball-in-court + approval matrix.

### 8.3 Next-action checklist for HYDRA (priority-ordered)

1. **P0 — Editable grid component.** Build/buy a virtualized editable grid with: Excel-grade keyboard nav (Tab/Shift+Tab/Enter/arrows/F2/Ctrl+D/Ctrl+;), Excel paste, formula columns, sticky totals, lazy per-cell validation, per-user column config. This is the single most important frontend investment.
2. **P0 — Status bar + ball-in-court** as the standard document-header component (reusable across BC/BL/Marché/Décompte).
3. **P0 — Object Page layout** (sticky KPI header + anchored sections + sticky footer actions + draft indicator).
4. **P0 — Localization-tunisia pack**: 3-decimal TND, PCN chart, RAS/CNSS/TFP/FOPROLOS lines, retenue de garantie, French+Arabic bilingual.
5. **P1 — Chatter** (messages + activities + audit log) as a right-rail component on every document.
6. **P1 — Approval-chain sub-screen** (Sage-style table) with amount-threshold matrix config.
7. **P1 — Kanban default landing** per document type, grouped by state.
8. **P1 — Pivot view** for cost-by-phase / cost-by-supplier / cost-by-month.
9. **P1 — Marché / Décompte / Attachement** as first-class objects (Tunisian BTP core).
10. **P2 — PWA offline-first** for BL/Daily-Log/RFI capture (service worker + IndexedDB mirror + mutation queue + conflict resolution).
11. **P2 — Mobile line entry** as card list (not grid) with QR scan + photo capture.
12. **P2 — Location WBS** (Chantier → Bâtiment → Niveau → Zone) as a filter dimension on all site data.
13. **P3 — Sheet viewer** (drawing versioning + markups → issues) — only if HYDRA expands into drawing management.
14. **P3 — Configurable Field Sets** (per-tenant form config) — when HYDRA has multiple tenants with different form needs.

### 8.4 Anti-patterns to explicitly avoid (learned from each vendor's mistakes)

- **Don't** make every screen a beginner wizard (Fiori purism failure) — keep a dense power-user mode.
- **Don't** use eager per-keystroke validation in grids (kills typing rhythm; Sage learned this in the 90s).
- **Don't** renumber lines on delete (breaks references; every vendor learned this the hard way).
- **Don't** render > 100 rows without virtualization (naive React tables die).
- **Don't** assume French localization = Tunisian localization.
- **Don't** use 2-decimal currency for TND.
- **Don't** build an online-only site-capture app (Tunisian 4G reality).
- **Don't** put annotations/markups and workflow tasks in separate universes (PlanGrid's lesson).
- **Don't** make navigation generic ("Sales", "Inventory") when your domain has specific objects ("BC", "BL", "Marché", "Décompte", "RFI") — Procore's tool-as-tab wins.
- **Don't** skip the chatter/audit (Odoo's best UX decision).

---

## 9. Verification checklist — claims to spot-check against live sources

Because this report was synthesized from training knowledge (no live web access in this sandbox), the following specific claims should be verified against the vendor's current public docs before HYDRA architecture decisions harden on them:

| # | Claim | Where to verify |
|---|---|---|
| V1 | Fiori's 5 design principles are exactly: role-based, responsive, simple, coherent, delightful | https://experience.sap.com/fiori-design/ → "Design Principles" |
| V2 | Fiori "1-1-3" / "1-1-1" heuristic phrasing | SAP Fiori Design Guidelines, "1-1-3" article |
| V3 | SAP decomposed ME21N into multiple Fiori apps | SAP Fiori Apps Reference Library (search "Purchase Order") |
| V4 | Sage 300 CRE = formerly Timberline | Sage product pages (Sage Construction Cloud) |
| V5 | Sage grids support Excel paste & F2/Tab/Enter navigation | Sage 100 / Sage 300 user guides |
| V6 | Sage CRE approval routing by amount matrix | Sage 300 CRE Approval Routing module docs |
| V7 | Odoo status bar + chatter + kanban/list/form | Odoo documentation (odoo.com/documentation) |
| V8 | Odoo Spreadsheet app + pivot formulas | Odoo Enterprise docs, v17/18 |
| V9 | Odoo has no native "Construction" module | Odoo Apps list (odoo.com/apps) |
| V10 | Procore's tool-as-tab nav + 3-tier change order model | Procore Support / Product docs (support.procore.com) |
| V11 | Procore "Ball in Court" concept | Procore RFI documentation |
| V12 | Procore Configurable Field Sets | Procore admin documentation |
| V13 | PlanGrid bubble sheets / auto-hyperlinking | Autodesk Construction Cloud docs |
| V14 | ACC unified platform module list | construction.autodesk.com |
| V15 | Tunisia: TVA 19/13/7; RAS 1.5% on marchés; CNSS 16.57%/9.18%; TFP 2%; FOPROLOS 1%; TND 3-decimal | Tunisian Code de TVA, Code de la Sécurité Sociale, BCT (Banque Centrale de Tunisie) |
| V16 | Tunisian month names in Arabic (أفريل etc.) | Tunisian official calendars / joumhouriya docs |
| V17 | Tunisian SNDP e-invoicing rollout status 2024+ | Direction Générale des Impôts (Tunisie) / SNDP announcements |

For each row, a 30-second web check confirms the claim is current; if a vendor has released a major version since early 2024, re-confirm the relevant UI pattern still holds.

---

## 10. One-page summary card (for HYDRA leadership)

> **The HYDRA UX bet, in one paragraph:** combine Sage's keyboard-first editable grid + sticky totals as the primary data-entry surface; Fiori's object page (sticky KPI header + anchored sections) as the layout; Odoo's status bar + chatter + kanban as the lifecycle + social layer; Procore's BTP-specific tool-as-tab navigation and ball-in-court; all wrapped in a Tunisian localization pack (FR+AR bilingual, 3-decimal TND, RAS/CNSS/TFP/FOPROLOS/retenue as first-class lines, PCN chart, marché/décompte as top-level objects) with a PWA offline-first site-capture app for BL/Daily-Log. This is not inventing a new UX — it is correctly sequencing the proven patterns of five vendors into one coherent Tunisian BTP product.
