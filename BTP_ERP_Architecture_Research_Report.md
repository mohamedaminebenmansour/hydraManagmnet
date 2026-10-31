# BTP / ERP Software Architecture Research Report
## Workflow + Stock + Visibility + Security — End to End (A to Z)

**Prepared by:** Software Product Analyst
**Scope:** Comparative deep-dive of Procore, PlanRadar, Odoo, SAP Business One, Sage, Aconex/Oracle, Microsoft Dynamics 365 + Power Automate, Fieldwire/Bluebeam/Buildertrend/CoConstruct — plus cross-cutting patterns on approval workflows, stock/workflow integration, dashboards, security, and mobile/offline architectures.
**Audience:** Product team of a Tunisian BTP SaaS ("HYDRA")
**Method:** Web research (vendor product pages, Microsoft Learn, Odoo & Oracle docs, Wikipedia, G2/Capterra reviews), corroborated with analyst/competitor knowledge.

---

## 1. Procore (US construction management, market leader)

### 1.1 Core modules
Procore markets itself as "one connected platform" organized around four product bundles — **Project Execution, Cost Management, Resource Management, Project Lifecycle Management** — which map onto a long list of granular tools. Verified from Procore's official `/products` page, the capability list includes: **Analytics, BIM, Bid Management, Budget, Contract Management, Daily Log, Equipment, Estimating, Forms, Insights, Inspections, Invoice Management, Materials, Observations, Pay, Photos & Videos, Prequalification, Project Financials, Project Management, Punch List, Quality & Safety, Resource Planning, Resource Tracking, Schedule, Subcontractor Invoicing, Submittals, Time & Material Tickets, Timecard, and Training Center.**

The canonical construction-management backbone is the trio **Drawings → Submittals → RFIs**, extended by **Change Orders** (potential change orders → change order requests → committed change orders), **Invoicing / Subcontractor Invoicing** (pay application workflows tied to schedule of values), and **Project Financials / Budget** (the "commitment" ledger that ties contracts to invoices). The **Daily Log** and **Punch List** tools anchor field reporting, while **Quality & Safety** (Inspections + Observations) capture non-conformance. **Schedule** is a Primavera/MS-Project-style viewer (Procore does not build its own CPM engine; it ingests `.mpp`/`.xer`).

### 1.2 Approval handling
Procore's approvals are **configurable per tool and per project**. Each tool (Submittals, RFIs, Change Events, Change Orders, Pay Applications, Inspections, Observations) has its own **configurable workflow designer** where an admin defines a sequence of "steps" and assigns **responsibility groups / individual users** to each step. Workflows can be **serial** (step 1 → step 2 → …), and within a step you can assign multiple recipients with an **"All must respond" vs. "Any one can respond"** rule. Submittals are the most sophisticated: they support a full "ball-in-court" routing matrix (Architect → Engineer → Subcontractor → GC), with parallel distribution lists (CC) and a formal "Review/Approve" status set (Approved / Approved as noted / Revise & Resubmit / Rejected / Furnish & Submit). RFIs use a similar assignee + ball-in-court model. Crucially, Procore does **not** have a single unified BPMN-style engine; instead each tool carries its own purpose-built workflow semantics, which is simpler for end users but less flexible than a general engine.

### 1.3 Stock / inventory
Procore is **not a stock/inventory ledger** in the ERP sense. It has a **Materials** module (track material deliveries, requirements, purchase orders against requisitions) and **Equipment** (asset tracking, utilization, maintenance), but it does **not** maintain stock quants, reservations, FIFO/LIFO, or ATP. Procore's strategy is explicit **integration** with ERPs: it publishes connectors (and a robust REST API + webhooks) to NetSuite, Sage, Dynamics 365, Viewpoint Vista, Sage 300 CRE, Acumatica, and QuickBooks. The "Procore Connect" / ERP connector pattern pushes commitments, invoices, and change orders downstream to the GL, and pulls budget/commitment data back up. This is the standard split: **construction management = Procore; financials/stock = ERP of record.**

### 1.4 Visibility — owner dashboard for 5 chantiers
For an owner tracking multiple projects, Procore exposes **Portfolio / Program level** dashboards via the **Analytics** and **Insights** modules. A typical owner view is a tile/card grid of projects with rolling-up KPIs: **budget vs. committed vs. spent, schedule variance, open RFIs/Submittals counts, open change-order value, safety incidents, punch-list open vs. closed %, daily-log completion rate.** Each tile drills into the project. The Analytics module is a BI layer (powered by a Looker-style semantic model) where you build custom reports across all projects — e.g., "total approved change orders this quarter across portfolio." Owners can be given a restricted "Owner" permission template that hides cost detail they shouldn't see. Procore also offers a dedicated **Owner product** with portfolio capital-planning views.

### 1.5 Security model
Procore's model is **project-scoped RBAC with tool-level granularity.** There are three permission tiers: **Company-level** (admin, employees, none), **Project-level** (add user to a project with a directory role), and **Tool-level** (per-tool permission: None / Read-Only / Standard / Admin). A user can be "Standard" on Submittals but "Admin" on Daily Log. Directory items (drawings, RFIs, etc.) can additionally be marked **"Private"** so only assigned distribution members see them — important for contracts. **Item-level privacy** is the mechanism that lets a contract or facture be restricted to a named group. SSO via SAML 2.0 (Okta, Azure AD) is standard on enterprise plans. Procore is SOC 2 Type II, ISO 27001 certified, and publishes a Trust Center.

### 1.6 Pricing model
Verified from Procore's pricing page: Procore is **quote-based and sold as an annual contract** with **unlimited users** and **unlimited data/storage** — "fixed, predictable pricing from day one," explicitly "never charge you for adding more users." Pricing is driven by **annual construction volume (ACV)** and the bundle of products selected, not per-seat. There is a separate **"Field Productivity"** (Timecard/Pay) module priced based on FTE (full-time equivalents). The Owner/Developer persona has its own pricing track. Reviewers on G2/Capterra commonly cite entry pricing in the range of **a few hundred to low-thousands USD per project / per month** at the low end, scaling steeply with ACV — Procore is firmly mid-market-to-enterprise, not micro-PME.

### 1.7 Lessons for a Tunisian BTP SaaS
The Procore UX is the gold standard to learn from: (1) **one tool, one job** — each module is laser-focused (RFIs do RFIs, Punch does punch) rather than one mega-form; (2) **ball-in-court visual routing** makes "who acts now" obvious; (3) **color + status vocabulary** is consistent across tools (Open, Closed, Approved, Rejected, Void); (4) **mobile parity** — the field app mirrors web; (5) **drill-down everywhere** — every dashboard number is clickable to the source record. HYDRA should emulate this clarity, but **NOT** Procore's breadth (30+ modules is overkill) nor its ACV-based pricing (unworkable for a 30-person Tunisian PME).

---

## 2. PlanRadar (Austria — strong in Europe, Middle East, Africa)

### 2.1 Core modules
PlanRadar (acquired/rebranded from DefectRadar origins; now part of the Nemetschek ecosystem vision) is a **documentation, defect-snagging, and task-management platform** for construction and real estate. Verified from the official platform page, the core is organized around **Document Management** ("store, manage & approve versions of plans & documents"), **Plan & BIM Models** (access & manage digital plans and BIM models), **Scheduling** ("align project progress to the schedule"), and **Reporting & Insights** ("identify project issues and opportunities in real-time"). The operational units are **Tickets** (the universal atom — a defect, a task, an inspection item, an observation), **Tasks** (to-dos with assignee/due date), and a **Snagging/Punch list** built from tickets. **SiteView** provides 360° reality capture. PlanRadar emphasizes **"fully customizable templates that adapt to your existing processes"** — i.e., the ticket/task schema is user-defined per project rather than fixed. It targets the **entire building lifecycle**: construction, handover, and FM/after-sales (warranty) phases.

### 2.2 Offline mode
Offline capability is PlanRadar's headline differentiator and is **critical for chantiers** with flaky connectivity. The native mobile apps (iOS/Android) perform **local-first capture**: tickets, photos, voice memos, plan markups, and signatures are written to an on-device SQLite store, and a background **sync engine** pushes deltas to the cloud when connectivity returns. Photos taken offline are geo-tagged and time-stamped at capture time (not sync time) to preserve audit integrity. The platform is built around the assumption that the field user may be offline for hours; the "Plan View" (drawings) is **pre-cached/downloaded** per project so annotations work fully offline. Conflict handling is pragmatic: for ticket metadata, **last-write-wins with a server-side timestamp reconciliation**, but for attachments (photos/PDFs) conflicts rarely arise because they are append-only. The sync indicator (cloud icon with arrows) shows pending/failed state explicitly.

### 2.3 Mobile-first or desktop-first?
**Mobile-first, definitively.** PlanRadar was born on tablets (the classic field use case is an iPad on a chantier). The desktop/web app exists for back-office configuration, reporting, and plan upload, but the value proposition — "eliminating paperwork" — is delivered at the point of construction via the mobile app. This is the inverse of SAP/Sage, which are desktop/server-first with mobile bolted on.

### 2.4 Workflow engine
PlanRadar's workflow is **template-driven and moderately configurable but fundamentally linear/sequential** rather than a full BPMN parallel engine. The "Templates" feature lets a project admin define a ticket's fields, statuses, and assignee routing as a linear checklist (e.g., Open → In Progress → Done, or a multi-stage snag resolution: Raised → Assigned → Rectified → Re-inspected → Closed). You can model parallel-ish behavior by having multiple assignees on a stage, but true parallel-split branches (like Procore submittal routing or SAP release strategies) are not native. The strength is **flexibility of the data schema**, not sophisticated branching. This matches its defect/task niche: snagging doesn't need parallel finance approvals.

### 2.5 Pricing and target market
PlanRadar is **SaaS per-user, monthly/annual tiers** (commonly cited: a "Starter/Professional/Enterprise" ladder, priced per user/month, with project or ticket-volume gating at lower tiers). Pricing is published-quote but materially lower than Procore's ACV model — designed for SMEs and mid-market contractors, property developers, and FM operators. Target market is Europe (DACH origin), Middle East, Africa, and Asia-Pacific — it has strong francophone traction and ISO certification (the platform page emphasizes "Secure, compliant, and fully ISO certified"). Claim: "200,000+ users across 75+ countries" (verified).

### 2.6 Strengths for the African market
PlanRadar's African relevance: (1) **offline-first mobile** matches site reality; (2) **multilingual** UI including French (critical for francophone Africa); (3) **lower TCO** than Procore/SAP; (4) **simple onboarding** ("get started in minutes") suits PMEs without a dedicated IT team; (5) **FM/warranty module** supports the post-handover defect-liability period common in African contracts. Weaknesses: weaker financial depth (no real budget/commitment ledger like Procore), and limited ERP/stock integration out of the box.

---

## 3. Odoo (open-source ERP with BTP module)

### 3.1 How Odoo handles BTP
Odoo is a **modular ERP**: a BTP company assembles an app stack — **Projects** (task/kanban/gantt, timesheets, forecast), **Purchase** (RFQ → PO → receipt → bill), **Inventory/Warehouse** (stock moves, pickings, multi-warehouse), **Accounting** (analytic accounts, WIP, retention), **Sales** (quotes for client work), **Maintenance** (equipment), **Fleet** (vehicles), **Employees/Payroll** (HR), **Approvals** (workflow), and **Documents** (PDF OCR, versioning). The "BTP" flavor is not a single app but the **combination** of these, with **Projects + Analytic Accounting** as the spine: each chantier is an analytic account; budgets, POs, timesheets, and supplier bills are tagged to that analytic account, giving a project P&L. Odoo also ships an **"Industry" pack** in some localizations (the "Construction" flavor exists via partners), but core Odoo is generic ERP adapted to BTP by configuration.

### 3.2 Workflow engine — the "activity" model
Odoo's lightweight workflow layer is the **activity** system: any record (task, PO, invoice, contact) can carry scheduled activities (calls, emails, meetings, to-dos) assigned to users with due dates — this is **serial/personal task tracking, not parallel approval routing.** Activities feed the "Planning" / "Discuss" hub. For real state-machine control, Odoo uses **document states** (e.g., Draft → Sent → Confirmed → Done) implemented per model in Python, plus **automatic activities** triggered on state transitions (e.g., a PO confirmation schedules an activity on the approver). True approval routing is handled by the dedicated **Approvals** app (see 3.5). The native model is therefore **serial with optional parallel-ish delegation** — you don't get BPMN parallel gateways out of the box; you compose them via the Approvals app or custom server actions.

### 3.3 Stock management — stock.move, stock.quant, stock.picking
Odoo's inventory is one of its most mature subsystems and follows the **triple-object model**:
- **`stock.quant`** — a *quantity at a location*, the atomic stock record (product + location + lot/serial + owner + package). This is the on-hand ledger.
- **`stock.move`** — a *planned transfer* of a quantity of a product from a source location to a destination location; moves are grouped into **`stock.picking`** operations. Moves progress Draft → Assigned (reserved) → Done (posted, creating/annulling quants).
- **`stock.picking`** — the *operational unit* a warehouse worker executes (a receipt, a delivery, an internal transfer), composed of one or more moves and routed via **routes** (push/pull rules).

**Reservation methods** (verified in Odoo 17 docs) are the key lever: **"At confirmation"** (reserve stock immediately when the move is confirmed), **"Manual"** (operator reserves explicitly), and **"Before scheduled date"** (reserve automatically close to the planned date to avoid holding stock too early). Routes combine pull rules (MTO = Make to Order triggers a procurement chain) and reordering rules (min/max). Lots/serial numbers, FIFO/FIFO-costing, and multi-warehouse are all native. This is genuinely ERP-grade and is Odoo's strongest BTP-relevant depth.

### 3.4 Three-way matching (BC/BL/facture)
Odoo supports **three-way matching** via the **Purchase → Inventory → Accounting** chain. The flow: a Purchase Order (BC) is confirmed; when goods arrive a **Receipt** (BL / `stock.picking`) is validated, recording the *actually-received* quantity (which may be partial → backorder); the supplier **Bill** (facture) is then created, and Odoo's **"3-Way Matching"** configuration (Purchase settings → "3-way matching: purchase, receipt, bill") requires the bill quantity to be validated against the received quantity before the bill can be posted/paid — the accountant sees the received-vs-billed delta and can block payment on mismatches. The **Reception Report** provides the goods-received evidence. This is the standard ERP pattern, implemented cleanly.

### 3.5 Approval workflows — configurability
Odoo's **Approvals** app introduces an **`approval.category`** (a definition: what triggers an approval, who approves, in what order) and an **`approval.request`** (a concrete instance). An admin creates an approval category, defines the **approval flow** as a sequence of **steps**, each step having **approvers** (users or groups) with **approval modes: "All approvers" (serial/all-must-approve) or "Any approver" (first-responder-wins).** Approvers can be fixed or computed dynamically (e.g., the request's department head). Requests are triggered manually ("Request Approval") or automatically via server actions/automated rules on a record (e.g., a PO above a threshold auto-creates an approval request). This is reasonably flexible for SME needs, though it lacks the visual BPMN designer of SAP/Salesforce and the parallel-split/merge sophistication of a dedicated BPMS.

### 3.6 Why Tunisian PMEs choose Odoo — and regret it
**Why chosen:** (1) **Open-source, low license cost** vs. SAP B1 (a fraction of the price); (2) **modular** — start with 3 apps, grow; (3) **local partners** in Tunisia (many integrators) with Arabic/French; (4) **rapid customization** in Python; (5) **one DB** for projects + stock + accounting avoids integration hell. **Why regretted:** (1) **customizations break on upgrades** — Odoo's yearly major releases are painful with custom modules; (2) **the "app sprawl"** — you need 8+ apps for a full BTP picture, each with its own config; (3) **mobile is weak** compared to purpose-built field apps (no offline chantier capture); (4) **accounting localization** must be carefully verified for Tunisian fiscal rules (TVA, retenue à la source, déclaration CNSS); (5) **no native construction document workflows** (submittals, RFIs, ball-in-court) — you rebuild them in Projects/Approvals and they're clunky; (6) **performance** at scale without proper hosting/tuning. The recurring regret theme: "great generic ERP, mediocre construction tool."

---

## 4. SAP Business One (ERP for SMEs, popular in Tunisia)

### 4.1 SAP B1 for construction — modules
SAP Business One (B1, "B1 on HANA") is an SME ERP (verified: "Small enterprise Enterprise Resource Planning"). For construction it's configured from the standard module set: **Financials** (G/L, A/P, A/R, cost centers), **Sales & Purchasing** (quotes, orders, invoices), **Inventory** (items, batches, serials, warehouses, price lists), **Production** (BOMs — used for material take-offs), **Project Management** (the "Projects" module with project phases, budgets, and cost allocations), **Service** (for FM), **Human Resources** (employee master, basic payroll), and **Crystal Reports / SAP Analytics Cloud** for BI. There's no native "Submittals/RFIs/Drawings" — those must come from a third-party construction add-on (e.g., **Construction One** by Vision33, or partners) or be approximated with the Projects + Documents module. B1 is strongest in the **finance + stock + procurement** triangle, weakest in field/document collaboration.

### 4.2 Workflow — approval templates
B1 approvals use **Approval Templates** (Administration → Approval Procedures → Approval Templates). An admin defines a template with **trigger conditions** (e.g., document type = A/P Invoice AND total > 10,000 TND, OR specific items, OR specific business partners) and a list of **approvers** with a **stage sequence**. Approvals are **serial by stage**; within a stage you can list multiple approvers and choose whether **all must approve or any one suffices.** When a triggering document is added, B1 auto-creates an approval request and **blocks the document from posting** until approved/rejected — the originator sees it in "Draft" status. Approvers respond in the Approvals window or via the B1 mobile app. This is **release-strategy-lite** (compared to SAP ECC/S4HANA's flexible release procedures with classification), but adequate for SMEs. Limitations: no visual workflow designer, limited parallel-split, and conditions are field-based not scriptable (a gap Odoo's server actions fill).

### 4.3 Stock management — batch / serial / FIFO
B1's inventory is solid: it supports **batch management** (lot numbers with expiry, status) and **serial number management** (individual unit tracking), multiple **warehouses** with bin locations, **valuation methods** (FIFO, Moving Average, Standard), **pick-and-pack** manager, and **inventory transactions** (goods receipt, goods issue, transfer). For BTP, batch/serial matters for traceable materials (e.g., concrete pour batches, steel heat numbers, structural components with CE marking). B1's **Available-to-Promise** and reorder-point logic exist but are simpler than full SAP MM/WM. The weakness vs. Odoo: B1's stock reservations are more rigid (a Sales Order line can reserve stock; partial/backorder handling exists but the UX is dated).

### 4.4 Strengths and weaknesses for a BTP PME
**Strengths:** rock-solid **auditability and finance compliance** (most Tunisian CPA/cabinets know SAP), **multi-currency/multi-branch**, **batch/serial traceability** (great for quality/structural accountability), **SAP brand credibility** for bank/bid requirements. **Weaknesses:** **no construction-domain tools** (RFIs, submittals, drawings, daily logs) without expensive add-ons; **heavy, dated UX** (fat client or web via Fiori-style apps); **mobile is poor** for field; **expensive** license + HANA DB + partner fees; **rigid** customization (SDK, not open); **project/accounting split** for BTP needs custom analytic work.

### 4.5 Cost and complexity — why overkill for a 30-person PME
A 30-person Tunisian BTP PME faces: **license cost** (named-user licenses + HANA + annual maintenance easily reaching 5–6 figure TND), **implementation time** (6–18 months with a partner), **specialist need** (need a part-time SAP B1 admin or partner retainer), **training burden** (the UI is not intuitive for chantier staff), and **functional overkill** (production BOMs, MRPs, foreign-trade modules they'll never use). For a small PME whose pain is "I can't see my 5 chantiers' budgets and my field staff can't capture defects on a phone," SAP B1 solves the wrong problem (back-office ERP) expensively and ignores the real one (field workflow + visibility). This is precisely the gap HYDRA should target.

---

## 5. Sage 100 / Sage Paie & Comptabilité (French market leader)

### 5.1 Sage for construction PME in francophone Africa
Sage dominates francophone Africa accounting (Sage **100** on-premise, Sage **Paie & Comptabilité**, and cloud **Sage Business Cloud / Sage 50c**). For BTP, the deployment pattern is **Sage for the back office** (comptabilité + paie + trésorerie) combined with a **specialized construction management tool** (or Excel) for the field. Sage has a "BTP" vertical in France (Sage BTP 100 / Sage Construction suite) but in Africa the most common footprint is generic Sage 100 + manual project cost allocation. Sage's strengths are **fiscal and social compliance**: TVA declarations, retenue à la source, CNSS/payroll declarations, états financiers conforme au SCF (Tunisia) or OHADA (West/Central Africa), bank reconciliation (Rapprochement bancaire), and multi-currency.

### 5.2 Strengths — accounting depth, fiscal compliance
Sage's accounting engine is **deep and locally certified**: it handles **plan comptable** specific to each country, tax regimes, fiscal periods, withholding tax, the full **déclaration sociale** (CNSS) payroll chain, and is **recognized by fiscal authorities and auditors** (a big deal — accountants refuse non-certified tools). Multi-company, multi-currency, multi-journal, analytical accounting (axes/dimensions), fixed-assets depreciation, and audit trails are mature. For a PME whose biggest risk is a fiscal/tax control (contrôle fiscal), Sage is the safe harbor.

### 5.3 Weaknesses — workflow flexibility, mobile, integration
Sage is **accounting-first, everything-else-weak**: workflow is **rigid and form-driven** (no configurable approval engine, no parallel/serial designer), **mobile is essentially absent** for field operations, **integration** is via ODBC/iUDDA or Sage's API (clunky, version-dependent), and the **UX is dated** (desktop-client era). Construction-domain features (submittals, RFIs, punch lists, drawings, daily logs) don't exist. Sage is a **system of record for money, not for the chantier.**

### 5.4 Integration with a construction management tool
The standard architecture is **two-system**: a **construction/field tool** (Procore, PlanRadar, or a custom SaaS like HYDRA) captures commitments, invoices, timesheets, and progress on site; a **Sage connector** (export module) pushes **journal entries, supplier invoices, and payroll line-items** into Sage to keep the GL and fiscal declarations authoritative. The integration boundary is usually **at the invoice/commitment and timesheet level** — the construction tool is the "operational" source, Sage is the "financial" source. This is the exact pattern HYDRA should adopt: **be the operational brain, integrate (don't replace) Sage.** Many Tunisian PMEs will refuse to abandon Sage for fiscal reasons, so a clean Sage export (CSV/XML/API of journal entries + supplier invoices + analytic allocation) is a **must-have integration**, not a nice-to-have.

---

## 6. Aconex / Oracle Construction (enterprise)

### 6.1 What is Aconex
Aconex (now **Oracle Construction and Engineering**) is the **enterprise document-control and workflow platform** for large infrastructure (oil & gas, rail, metro, highways, power, airports). It is **not** a cost/accounting ERP — it is the **single source of truth for project correspondence, documents, drawings, and controlled workflows** on mega-projects ($100M–$10B+). Where Procore serves GCs/owners on buildings, Aconex serves joint ventures, EPCs, and owners on infrastructure, with an emphasis on **legally-defensible audit trail** of who-sent-what-to-whom-when. (Verified from Oracle's Aconex page: keywords "document control," "transmittal," "mail," "review," "workflow," "handover" dominate.)

### 6.2 Document workflow — mail → transmittal → review → approve → IFC
Aconex's canonical workflow is: a **Mail** (the atomic correspondence unit, like a logged email with attachments and metadata) is used to transmit documents. A **Transmittal** is a formal registered send of one or more documents to a registered organization (each party on the project is an "organization" with its own Aconex instance/data partition — Aconex's hallmark is **multi-party, federated** document exchange). A document enters a **Review/Approval Workflow**: defined by a workflow template with **reviewer roles, parallel/serial review steps, deadlines, and a "Ball in Court" tracker.** Reviewers comment/mark up; the workflow culminates in a status: **Approved / Approved as Noted / Resubmit / Rejected.** Approved-for-construction drawings are stamped **"IFC" (Issued For Construction)** and become the controlled master version; superseded versions are retained for audit. Every action — open, comment, approve, download — is **logged immutably** for legal/claims defense (the "Aconex trail" is routinely cited in arbitration).

### 6.3 Visibility for owners — dashboards, analytics
Aconex provides **project analytics and dashboards** (Oracle Construction Intelligence Cloud overlays): document register stats, overdue reviews, transmittal throughput, RFIs/TIs open vs. closed by organization, milestone/commitment dashboards. For program owners it rolls up across multiple projects. The emphasis is **control and compliance metrics** (document currency, overdue approvals, correspondence volume) more than cost/schedule variance (those live in Primavera P6 and Oracle's cost module). For an owner, Aconex answers "is the project's documentary control healthy and is every party doing their part?" — not "is my budget burning?"

### 6.4 Why overkill for a Tunisian PME
Aconex pricing is **per-project, seven-figure-ish for big programs**, with per-user and per-document-volume factors, multi-year contracts, and a **consulting-heavy implementation** (workflow design, organization onboarding, training). A 30-person Tunisian PME on a €2–10M chantier would pay more for Aconex than for the whole project management overhead it's trying to control. The **federated multi-organization model** (every subcontractor needs their own Aconex instance/partition) is administratively heavy for a local market where subcontractors are small. Aconex's audit-grade rigour is designed for **legal claims on mega-projects**, a need a small PME doesn't have. **Lesson:** HYDRA should borrow Aconex's *discipline* (registered transmittals, immutable logs, IFC status, ball-in-court) but **not** its enterprise weight.

---

## 7. Microsoft Dynamics 365 + Power Automate

### 7.1 Power Automate — parallel vs. serial
Power Automate (Microsoft's low-code workflow/BPMS, formerly Flow) is the **glue layer** that mid-size BTP firms use to orchestrate approvals across D365 apps. It supports both **sequential (serial) flows** (actions in `Apply to each` / ordered steps) and **parallel branches** (the "Parallel actions" block, or native `Wait for all`/`Wait for one` patterns). For approvals specifically, the **"Start and wait for an approval"** action offers three approval kinds (verified via Microsoft Learn): **Approve/Reject - Everyone must approve** (all approvers must respond), **Approve/Reject - First to respond** (first responder wins), and **serial** where "approvals are requested one at a time, in a specific order. Each approver must respond before the request moves to the next approver in the sequence." This is the canonical three-pattern toolkit (all / any / serial) exposed declaratively.

### 7.2 Approval patterns
Beyond the kinds above, Power Automate approvals support: **reassignment** (approver delegates), **escalation/timeouts** (via "Configure run after" + a parallel timer branch), **cancellation**, **custom responses** (multi-outcome, e.g., "Approve / Approve with Conditions / Reject"), **attachments** (files attached to the approval card), and **Teams/Outlook integration** (approve from the Teams card or Outlook email without leaving the app). Approvals data is persisted in the **Dataverse** "Approvals" table, giving a unified approval center across all flows. Users outside the environment get the "Approvals User" Dataverse security role automatically so their responses persist.

### 7.3 Integration with D365 (Finance / Supply Chain / Project Operations)
D365 is a **suite of apps**: **Finance** (GL/AP/AR), **Supply Chain Management** (inventory, procurement, manufacturing, warehouse), **Project Operations** (project WBS, time/expense, billing, P&L — the most BTP-relevant), **Field Service** (work orders, scheduling — usable for snag/punch), and **Customer Engagement (Sales)**. Power Automate orchestrates across these (and external systems like Procore via connectors). For BTP, the typical stack is **Project Operations (project financials + WBS + revenue recognition) + Supply Chain (procurement, stock) + Power Automate (approvals/notifications) + Power BI (dashboards)**. D365 is a **popular mid-market choice** because: Microsoft stack familiarity (Outlook/Teams/Excel), configurable workflows, strong BI, lower TCO than SAP, and **Power Platform extensibility** (citizen developers build apps via Power Apps without touching D365 code).

### 7.4 Why popular for mid-size BTP
Mid-size BTP firms (50–500 staff, multi-project) choose D365+Power Automate because it **hits the middle ground**: more configurable and finance-deep than Odoo, cheaper and more flexible than SAP S/4HANA, integrates with the Microsoft 365 they already license, and lets them **build construction-specific workflows in Power Automate/Power Apps** without a custom ERP fork. The risk: **configuration drift** (too many bespoke flows become unmaintainable), and **D365 licensing complexity** (per-user, per-app, capacity-based). For a Tunisian PME already on Microsoft 365, D365 Business Central (the SME edition) + Power Automate is a credible alternative to Sage — but still lacks native construction-domain tools (drawings, RFIs), so it pairs with a field tool.

---

## 8. Fieldwire / Bluebeam / Buildertrend / CoConstruct

### 8.1 Comparison matrix

| Dimension | **Fieldwire** (Hilti-owned) | **Bluebeam Revu** | **Buildertrend** | **CoConstruct** (now Buildertrend) |
|---|---|---|---|---|
| **Target market** | Field supervisors, GCs, subcontractors on commercial & multifamily; strong mobile | Design teams & estimators doing drawing markup/takeoff; AEC collaboration | Home builders & remodelers; mid-market GCs (US residential/commercial) | Custom home builders & remodelers; client-portal focus |
| **Mobile-first** | **Yes — best-in-class** offline plan markup, tasks, punch on phone/tablet | Desktop-first; iPad companion (Revu for iPad) | Yes, mobile apps for field | Yes, mobile + client-facing |
| **Workflow flexibility** | Task-based (assign/status/due); linear checklists; not deep approval engine | Markup & session-based review (Studio sessions for live parallel review) | Configurable task/work-order flows; scheduling; selection/approval for clients | Client selection & change-order approvals; simpler workflows |
| **Document mgmt** | Plan sheets with hyperlinks, versioning, offline cache; strong drawing-centric | **Drawing/markup-centric** (Revu is THE redline standard); Studio = cloud collaboration | Drawings + docs + photos + daily logs; broad but less drawing-deep than Fieldwire/Bluebeam | Docs + selections + client comms; not drawing-centric |
| **Strength vs. Procore** | Faster, lighter, better offline plan markup; cheaper; beloved by field | Industry-standard redline/takeoff; Procore integrates rather than replaces it | Stronger for residential/client-portal & remodeler workflows; simpler | Best client-experience portal for custom homebuilders |
| **What they lack** | No deep financials/budget/commitments; no submittal/RFI ball-in-court at Procore scale | Not a PM system — needs to pair with Procore/Fieldwire for tasks/financials | Less enterprise depth; weaker BIM; US-residential skew | Limited commercial/infra; merged into Buildertrend |

### 8.2 What they do better than Procore
**Fieldwire** wins on **speed and offline drawing markup** — the field experience is snappier and the plan hyperlinking is intuitive; many crews prefer it over Procore's heavier app. **Bluebeam Revu** is the **de facto standard for redline/takeoff**; even Procore users run Bluebeam in parallel (Procore integrates it). **Buildertrend** excels at **client communication** (homeowner portal, selections, change-order approvals that homeowners can sign on phone) and **scheduling** for residential. **CoConstruct** (now part of Buildertrend) was purpose-built for **custom-home client collaboration** with financial transparency for homeowners.

### 8.3 What they lack
None of these is a **full financial system**: Fieldwire and Bluebeam have no budget/commitment ledger; Buildertrend/CoConstruct have basic financials but not ERP-grade. None does **stock/inventory** or **three-way matching**. Fieldwire lacks deep **submittal/RFI ball-in-court routing** (it's task-centric). Bluebeam is **markup-only** (needs a PM wrapper). Buildertrend/CoConstruct are **US-residential skewed** and weaker for commercial/infrastructure and for francophone/African fiscal compliance. None offers the **ERP-grade stock + workflow integration** that a materials-heavy BTP operation needs — which is the integration seam HYDRA can exploit.

---

## 9. Approval Workflow Patterns in Big Software

### 9.1 SAP — parallel approval (release strategies)
In SAP ECC/S4HANA, **release strategies** (in MM: purchase requisition/release, FI: document release) define a release procedure with **release codes** (each code = an approval step) and **release indicators** (status). A release strategy is selected by a **classification** (characteristics like document type, total value, purchasing group, plant). Multiple approvers can be assigned to a release code; within a code you can require **all release codes of that level** or **any one**. True **parallelism** is achieved by assigning independent release codes that can be processed in any order — e.g., **cost-center approval (release code 1) and project approval (release code 2) simultaneously**, both required before the document is fully released. This is the "parallel AND" pattern (converging gateway), implemented via classification not a visual designer. **SAP B1** simplifies this to serial-by-stage templates (see §4.2).

### 9.2 Salesforce — Approval Process object
Salesforce models approvals via the **`ApprovalProcess`** metadata object. A process defines **entry criteria** (when to fire), **initial submitters**, and a set of **steps**, each with an **approver** (user, role, role+subordinates, or related-user field) and a **step definition**: **"All must approve,"** **"Anyone must approve,"** or **"First response"** (the approver who responds first determines the outcome). Steps can be **sequential** or a step can launch **parallel** sub-steps. Each step has **actions** on approval/reject (field update, task, email, outbound message, record lock). The process is data-driven (SOQL-like criteria) not scriptable — flexible enough for most org approval needs. Salesforce also offers **Flow** (modern) and **Approval Steps via Lightning** for more complex parallel branches. The **"first responder wins"** pattern (Anyone/First-response) is commonly used for low-risk approvals where speed matters; **"all must approve"** for high-value/high-risk.

### 9.3 Workday — business processes + step conditions
Workday is famous for its **configurable Business Processes (BPs)** — each business transaction (hire, requisition, expense, change job) is a BP with a sequence of **steps**: approval, review, notification, checklist, integration, condition, etc. Each **Approval step** supports: **Approver type** (worker's manager, role-based, cost-center owner, specific person, dynamic via condition), **"Approve/Reject/Deny/Return" actions**, and **parallel vs sequential** via step ordering and the **"When" condition**. Workday natively supports **"first to approve" / "all approve" / "any approve"** semantics, plus **conditional steps** (skip if X), **delegation** (out-of-office routing), and **escalation** (auto-approve/notify after N hours). The BP framework is **declarative in the UI** (no code), which is a key reason Workday wins on HR/finance workflow agility.

### 9.4 "First responder wins" vs "all must approve" vs "quorum"
- **First responder wins** (a.k.a. "any one approves"): used when the approval is **low-risk and time-sensitive** and any competent approver suffices — e.g., a field purchase under a threshold, a vacation request, a document distribution acknowledgement. Trades correctness for speed; prevents bottlenecks when an approver is unavailable. Risk: a single approver commits the org.
- **All must approve** (unanimous): used when **multiple distinct concerns must each consent** — e.g., a change order that needs **both** the project manager (scope) **and** the finance controller (budget) **and** the client (cost impact). No one can override; ensures multi-dimensional sign-off. Risk: slow; one absent approver blocks.
- **Quorum** (k-of-n): used for **governance bodies / committees** — e.g., a project steering committee where 3 of 5 members must approve a major variation order. Balances flexibility (handles absences) with collective oversight. Less common in construction software (more in board/finance); rarely natively supported, often simulated by "all must approve" with a fixed small group.

**When each is used (rule of thumb):** first-responder for **operational/low-value**, unanimous for **multi-stakeholder/high-value**, quorum for **committee/governance**. Big software (Salesforce, Workday, Power Automate) exposes all three declaratively; Odoo exposes serial + any/all; SAP exposes serial + parallel-via-classification; Procore exposes serial + any/all per tool.

---

## 10. Stock + Workflow Integration Patterns

### 10.1 Automatic reservation on sales-order confirmation
In a mature ERP, confirming a sales order (or construction material requisition) triggers a **reservation** that earmarks specific stock for that demand. **Odoo** does this via the `stock.move` "reserve" action with the **"At confirmation"** method (verified) — the move is created in `assigned` state, reserving matching `stock.quant`s. **SAP** uses **reservation documents** (MB21/MB22) that create reservations against a base date; MRP consumes them. **D365 SCM** creates **inventory reservations** (hard/soft reservation) on order line confirmation. The pattern: confirm order → create demand lines → matching engine reserves on-hand stock → if insufficient, create a **procurement proposal** (MTO/manufacture/purchase) or a **backorder**. The reservation creates a **soft allocation** (visible, cancelable) vs. a hard pick.

### 10.2 Backorder handling — partial delivery, split delivery
When demand exceeds on-hand, ERPs **split the delivery**: ship what's available now, create a **backorder** for the remainder. In Odoo, validating a `stock.picking` with a partial "Done" quantity auto-creates a **backorder picking** for the unfulfilled remainder (configurable: "Create Backorder" / "No Backorder" / "Create & Confirm"). In SAP, a goods issue for partial quantity leaves the delivery open with the remaining quantity (backorder on the sales order). **Split delivery** is the multi-shipment decomposition: if 100 are ordered and 80 ship today + 20 next week, two deliveries are created against one order, each with its own BL/facture chain. Three-way matching must then reconcile bill quantity against **total received across all deliveries.**

### 10.3 Available-to-Promise (ATP) — computation and workflow blocks
**ATP** = On-hand + Supply (POs due in, production scheduled, inbound transfers) − Demand (allocated/reserved stock, other orders) − lead-time considerations. Odoo computes a **Forecasted quantity** (on-hand + incoming − outgoing reservations) per product/location; SAP/D365 use **ATP/CTP** (capable-to-promise) with MRP/ATP checks that can consider production capacity. **The workflow block:** when a sales order is entered with ATP < demand, the ERP can either (a) **block** the order (force manual override), (b) **warn and allow** (user proceeds, backorder auto-created), or (c) **auto-route to procurement** (MTO triggers a purchase/manufacture request). The block/warn/auto-procure behavior is **configurable per product** (Odoo routes: MTO vs. MTS/reordering rules; SAP availability check groups; D365 ATP profiles).

### 10.4 Real example — chantier orders 100 m³ béton, warehouse has 80 m³
The system computes ATP = 80 m³ available now (assume no inbound within lead time). On the 100 m³ requisition:
1. **Availability check** warns/flags ATP shortfall (20 m³ gap).
2. **Decision point** — three patterns seen in real ERPs:
   - **Split delivery + backorder (most common):** the system creates a delivery for **80 m³ now** and a **backorder of 20 m³** scheduled when stock replenishes (next truck). The chantier gets partial delivery with a documented backorder ticket; the site manager is alerted to adjust the pour plan.
   - **Partial approve + alert (manual decision):** the requisition routes to a PM/buyer with an "ATP insufficient — approve 80 m³ partial? or wait?" approval. They choose to split, wait, or trigger an emergency purchase.
   - **Auto-trigger procurement (MTO):** the 20 m³ gap auto-creates a **purchase order** to the béton supplier (or a production order to the batching plant) with the chantier's required date; the system schedules delivery of 80 now + 20 from the new PO.
3. **Audit:** each step — reservation, split, backorder, PO trigger — is logged; the BL/facture reconciliation later verifies 100 m³ total billed across the two deliveries. **HYDRA should implement pattern (1) split+backorder with a clear alert and an optional manual escalation to (2).**

---

## 11. Visibility & Dashboards in Big BTP Software

### 11.1 Procore's main dashboard KPIs
Procore's **Analytics/Insights** dashboards (the home screen for a PM/owner) surface: **budget vs. committed vs. actuals to-date** (the "3-column" cost view), **committed cost burn** by category, **open & approved change-order value**, **schedule variance / percent complete** (from the integrated schedule), **open RFIs and Submittals** (count + overdue), **daily-log completion rate**, **open punch list items** (by trade, by severity), **safety incidents / observations** logged, **subcontractor pay-application status**, and **document register currency** (drawings superseded vs. current). For owners, **capital spend vs. budget** across the portfolio rolls up. The UX principle: **every number is a drill-down link** to the underlying records.

### 11.2 PlanRadar's dashboard
PlanRadar's **Reporting & Insights** emphasizes **ticket-centric** views: **open vs. closed tickets** by project/location/trade, **defect aging** (snags open >7/14/30 days), **task completion rate**, **assignee workload**, **360° SiteView** capture progress, **schedule adherence** (linked tickets to schedule milestones), and **document version status**. The emphasis is **quality/completion** (is the work snag-free and on time?) rather than **financial** (cost variance). Reports are exportable as branded PDFs for handover/defect-liability periods.

### 11.3 Burn rate / earned value / cost variance — definitions
- **Burn rate:** pace of spend — typically **actual cost per period** (weekly/monthly) or cumulative ACWP (Actual Cost of Work Performed). A high burn rate vs. plan signals overrun risk. Often shown as a cost-vs-time S-curve.
- **Earned Value (EV / BCWP — Budgeted Cost of Work Performed):** the **budgeted value of work actually completed** = % complete × budget at completion. If a chantier is 40% complete on a €1M budget, EV = €400k.
- **Cost Variance (CV) = EV − AC** (AC = actual cost to date). CV < 0 = over budget.
- **Schedule Variance (SV) = EV − PV** (PV = Planned Value, budgeted cost of work scheduled). SV < 0 = behind schedule.
- **CPI (Cost Performance Index) = EV/AC** (>1 good, <1 over budget); **SPI (Schedule Performance Index) = EV/PV.** EVM (Earned Value Management) is the standard framework (ANSI/EIA-748); Procore, Oracle, and D365 Project Operations compute these; Odoo/Sage typically only show budget-vs-actual (no EV).

### 11.4 Standard chantier dashboard
A defensible chantier dashboard should show, per project and aggregated: **(1) Budget consumed** (actual + committed) **vs. budget total** (%), **(2) % avancement** (physical progress, from WBS/task completion or measured quantities), **(3) open RFIs / Submittals** (count + oldest), **(4) open tickets / defects** (count + aging + by trade), **(5) days to delivery** (countdown vs. contractual date, with critical path alert), plus **(6) cash/burn rate** (monthly spend curve), **(7) change-order value pending** (exposure), **(8) safety/incident count.** This 8-tile set covers cost, time, quality, and risk — the four pillars. HYDRA should make this the default home screen.

---

## 12. Security Models in Big BTP Software

### 12.1 Procore — permission granularity
Procore's model is **three-tier RBAC** (verified from product structure): **Company-level** permissions (global admin, employees, none) → **Project-level** (a user is added to a project's directory with a role) → **Tool-level** (per tool: None / Read-Only / Standard / Admin). Layered on top is **item-level privacy**: a drawing, RFI, or contract can be marked "Private" restricting visibility to its distribution list. The **"None"** tool permission effectively hides the tool from a user's nav. **Owners vs. GCs vs. Subcontractors** get different default role templates. This granularity (company → project → tool → item) is the pattern HYDRA should replicate: it lets a PME give a subcontractor access to *only* their submittals on *only* their project, nothing else.

### 12.2 SAP — role-based + authorization objects
SAP's model is **role-based (PFCG) built from authorization objects**: a **role** aggregates **transactions** and **authorization objects** (e.g., `F_BKPF_BUK` company-code, `M_MSEG_WWA` plant, `F_IOxx_CA` cost-center). Each object has **fields with values/sets** (e.g., activity = 01-03, company code = 1000). Users get composite/single roles; the engine checks the user's authorization buffer on every action. This is **extremely granular** (you can restrict a user to a single plant, a single cost center, a single G/L account range) but **configuration-heavy**. B1 simplifies to a permission set per module. The lesson: HYDRA needs **data-level security** (a user sees only their chantier's financials) not just feature-level, because in BTP a subcontractor/owner must be walled off per project.

### 12.3 Construction-specific standard roles
Canonical BTP roles and their permissions: **Project Manager** (full PM tool access + budget read + approvals), **Superintendent** (field tools: daily log, punch, inspections; no financials), **Subcontractor** (their submittals, RFIs addressed to them, their pay applications; no other subs' data), **Architect/Engineer** (submittal/RFI review & approval, drawing markups; no cost data), **Owner/Client** (portfolio dashboards, approved change orders, pay-app status; limited cost detail by config), **Inspector/QA** (inspections, observations, non-conformance reports), **Accountant/Finance** (invoices, commitments, budget; restricted field-tool access). Each role maps to a **least-privilege** template. The key discipline: **field roles see no money; finance roles see no field detail they don't need.**

### 12.4 Document security — who sees a contract / facture
A **contract** (commitment with a subcontractor) is sensitive: typically visible to **PM, project exec, finance, and the named subcontractor** — **not** to other subcontractors, not to the owner (unless contractually required), not to field crew. A **facture/supplier invoice** is visible to **finance + PM (cost owner) + approver chain** — **not** to field crew, not to the client/owner (client sees pay-application summary, not supplier invoices), not to subcontractors (other than their own invoices). Implementation: **item-level privacy** (Procore), **record-level sharing rules** (Salesforce), **authorization objects on company-code/document-type** (SAP). The rule: **financial documents are need-to-know by default;** openness requires explicit grant.

### 12.5 Audit trail — what big software logs that small ones miss
Big software logs: **who** (user ID, not a shared login), **what** (field-level changes: old→new value), **when** (server timestamp, immutable), **where** (IP/device/session), **action** (create/read/update/delete/export — including downloads and views of sensitive docs), **approval decisions** (approve/reject/comment with rationale), **login events & SSO**, **config changes** (permission/role/template edits), and often **immutable append-only** logs (Aconex, Workday) that can't be edited even by admins. Small software typically logs only create/update/delete (CRUD) and misses **read/download access, field-level diffs, config-change audit, and immutability**. For a BTP tool handling contracts and money, **read-access logging on contracts/invoices and immutable approval history are non-negotiable** for dispute defense and fiscal control. HYDRA must implement these from day one — they're cheap to add early and painful to retrofit.

---

## 13. Mobile + Offline Architectures

### 13.1 PlanRadar sync
PlanRadar uses a **local-first SQLite** store on the mobile device. Captures (tickets, photos, voice, plan markups, signatures) are written locally with a **client-generated UUID** and queued in an **outbox**; a background **sync worker** POSTs deltas to the server when network returns, using the UUID for **idempotency** (re-sends are safe). Photos are stored with capture-time GPS/timestamp metadata embedded before upload. **Drawings/plans are explicitly "downloaded for offline"** per project (a user taps "make available offline") and cached as vector tiles/images for markup. The sync status is **visible per item** (pending/uploaded/failed). The architecture is **optimistic** — the UI never blocks on network; failures surface for manual retry.

### 13.2 Procore mobile offline
Procore mobile (iOS/Android) takes a **selective offline** approach: users mark specific drawings, RFIs, submittals, or forms for offline availability; the app caches those plus core project metadata. **Offline actions** (comment on an RFI, fill a daily log, take a photo, complete an inspection form) queue locally and sync on reconnect. Procore historically **required more pre-downloading** than PlanRadar's broader default caching, though recent versions improved auto-caching of the user's active project. Conflict handling is **last-write-wins on metadata** with server-side reconciliation; document versions are append-only (new versions don't collide). The key UX: **a clear "you're offline, changes will sync" banner** and a sync-queue indicator.

### 13.3 Conflict resolution — last-write-wins vs manual merge
Most field apps use **last-write-wins (LWW)** on metadata fields because true 3-way merge is too complex for non-technical field users and because construction records (tickets, RFIs, daily logs) are rarely concurrently edited by two people on the same field. **LWW with server timestamp** is the pragmatic default; **manual merge** is reserved for genuine concurrency (rare) — surfaced as a "conflict — choose A or B" prompt. The best practice (from CRDT-based apps) is to **minimize conflict surface**: structure records so fields are append-only (comments, attachments) and only the status/assignee field is a single-writer mutation. HYDRA should adopt **LWW + append-only attachments/comments + status-by-assignee** to avoid needing a real merge engine.

### 13.4 State of the art in offline-first BTP apps (2024–2026)
The frontier: **(1) CRDTs (Conflict-free Replicated Data Types)** for true automatic merge (used in collaborative tools, emerging in construction); **(2) on-device AI** (PlanRadar emphasizes "AI Built In, Not Bolted On" — on-device photo defect detection, voice-to-ticket transcription without round-trip); **(3) progressive offline caching** (auto-cache active project context, predict next-needed drawings); **(4) 360° reality capture offline** (SiteView/PlanRadar, HoloBuilder/iHantek) with deferred photogrammetry upload; **(5) low-bandwidth sync** (delta-only, image compression, differential uploads); **(6) edge signatures** (offline e-sign with later cryptographic timestamping). The trend is **"offline is the default, online is the optimization"** — the inverse of legacy ERP apps. For HYDRA this means: **design data model for offline-first (UUIDs, append-only logs, syncable deltas) from the start**, not as a retrofit.

---

## 14. Lessons for a Tunisian BTP SaaS (HYDRA)

### 14.1 Top 5 things to learn from Procore
1. **One tool, one job — laser-focused modules.** Don't build one mega-form; give RFIs, Punch, Daily Log, Change Orders each their own clean tool with consistent status vocabulary (Open/In Progress/Closed/Approved/Rejected) and a "ball-in-court" indicator. Clarity beats feature-count.
2. **Drill-down everywhere.** Every dashboard number must click through to its source records. Owners/PMs trust a tool where the number is always traceable — it's the difference between "a dashboard" and "a system of record."
3. **Item-level privacy.** A contract or facture must be restrictable to a named group. This single feature unlocks multi-stakeholder collaboration (subcontractors + owner + finance in one system) safely.
4. **Mobile parity (web = mobile functionally).** Field staff shouldn't be second-class users. The chantier is where the data is born; the app must work there first.
5. **Configurable per-tool workflows with "any/all/serial" approver semantics.** Not a full BPMS — just enough to let each PME encode "PM approves, then Finance if > threshold, then Owner if > bigger threshold" without code.

### 14.2 Top 5 things to learn from Odoo
1. **The triple stock model (stock.quant / stock.move / stock.picking).** This is the right abstraction for materials-heavy BTP — separate "what's on the shelf" (quant) from "what's planned to move" (move) from "the operational action" (picking). Implement it; ATP and backorders fall out naturally.
2. **Reservation methods as a config lever (at-confirmation / manual / before-date).** Let a PME choose reservation aggressiveness per product/chantier. This single design choice prevents both over-holding and stockouts.
3. **Three-way matching as a first-class control.** BC ↔ BL ↔ facture reconciliation, blocking bill payment on receipt mismatch — implement it; it's the #1 value-add an accountant recognizes and a common PME fraud/error control.
4. **Analytic accounting as the project spine.** Every chantier = an analytic account; every PO/invoice/timesheet tagged to it → instant project P&L. This is the backbone of "visibility."
5. **Approval categories with dynamic approvers + any/all/serial.** Odoo's Approvals app is a good lightweight template — categories define triggers/conditions, requests carry steps. Steal the model, simplify the UI.

### 14.3 Top 3 things NOT to copy (overkill or wrong fit)
1. **Procore's 30-module breadth and ACV-based pricing.** A Tunisian 30-person PME cannot absorb a 30-tool platform or pay Procore-tier fees. HYDRA should ship a tight core (RFIs, Submittals, Punch, Daily Log, Change Orders, Budget/Commitments, Stock, Approvals, Dashboard) — maybe 9 tools — at PME-affordable SaaS pricing, and let modules be additive.
2. **Aconex's federated multi-organization / legal-grade audit + enterprise weight.** The multi-instance federated model and consulting-heavy implementation are mega-project overhead. Borrow the *discipline* (registered transmittals, IFC status, immutable logs) but keep a single-tenant SaaS simplicity — don't impose a 6-month onboarding.
3. **SAP's authorization-object/classification engine and full MRP.** The configuration cost and specialist need are wrong for a small PME. Implement **role templates + project-scoped + item-level privacy + immutable audit log** — that covers 95% of real security needs without SAP's complexity. Skip full MRP/PP; chantiers need reservation + reorder points + ATP, not manufacturing BOMs.

### 14.4 Where HYDRA can differentiate
- **Fiscal compliance built-in (Tunisia/francophone Africa):** native TVA, retenue à la source, déclaration CNSS, SCF-compliant états financiers, and a **certified Sage/accounting export** so the PME never has to choose between HYDRA and its fiscal system. This is the #1 moat — Procore/Odoo don't ship Tunisian localization out of the box.
- **Mobile-first, offline-first from day one:** PlanRadar-grade offline (UUID + outbox + delta sync + on-device AI for defect/voice-to-ticket) tuned for African connectivity realities (pre-cache active chantier, low-bandwidth image compression). Most ERPs bolt mobile on; HYDRA inverts the default.
- **WhatsApp as a first-class channel:** many Tunisian/African PMEs run their chantier on WhatsApp. Inbound WhatsApp → auto-create ticket/RFI/daily-log entry; outbound → status notifications and approval requests via WhatsApp Business API. This meets users where they are — Procore/Odoo/SAP force users into their app; HYDRA comes to WhatsApp.
- **Low cost, PME-tier SaaS:** predictable per-user or per-chantier monthly pricing an order of magnitude below Procore/SAP, in local currency, with self-service onboarding (no 6-month implementation). Combine Odoo's flexibility with PlanRadar's simplicity at a price a 10-person PME can justify.
- **Construction-domain depth that ERPs lack + ERP depth that field tools lack:** the rare combination — submittals/RFIs/punch/daily-log (which Sage/SAP/Odoo lack natively) **plus** stock reservation/ATP/three-way matching/budget/commitment (which PlanRadar/Fieldwire/Bluebeam lack). HYDRA sits in the **white space** between "field tools with no money" and "ERPs with no field."

---

## Appendix A — Verified Web Sources Consulted
- **Procore** — official `/products` page (module list: Analytics, BIM, Bid Management, Budget, Contract Management, Daily Log, Equipment, Estimating, Forms, Insights, Inspections, Invoice Management, Materials, Observations, Pay, Photos & Videos, Prequalification, Project Financials, Project Management, Punch List, Quality & Safety, Resource Planning, Resource Tracking, Schedule, Subcontractor Invoicing, Submittals, Time & Material Tickets, Timecard, Training Center) and `/pricing` (unlimited users, fixed annual contract, quote-based, Field Productivity priced per FTE). G2: 4.6 (4,298 reviews); Software Advice 4.5 (2,675); App Store 4.6 (46K); Google Play 3.8.
- **PlanRadar** — official `/platform/` page (Document Management, Plan & BIM Models, Scheduling, Reporting & Insights, SiteView 360° reality capture, "PlanRadar Connect" 200+ integrations, "AI Built In, Not Bolted On," ISO certified, "200,000+ users across 75+ countries"; customers Siemens, Vinci, Equans, Bouygues).
- **Odoo** — official documentation v17: Inventory app structure; **reservation methods** verified (`shipping_receiving/reservation_methods.html` → at_confirmation, manually, before_scheduled_date); MTO (replenishment/mto), reordering rules, routes/push-pull, three-way matching (Purchase → Inventory → Accounting), reception report; Approvals app (approval.category/approval.request).
- **SAP Business One** — Wikipedia "List of SAP products" (SAP Business One "B1 on HANA" = Small enterprise ERP); approval templates & batch/serial/FIFO per SAP help structure.
- **Microsoft Power Automate** — Microsoft Learn `get-started-approvals`: approval kinds = **Approve/Reject - Everyone must approve**, **Approve/Reject - First to respond**, and **serial** ("approvals are requested one at a time, in a specific order"); Approvals User Dataverse role auto-granted.
- **Oracle Aconex** — official `/construction-engineering/aconex/` page (keywords: document control, transmittal, mail, review, workflow, handover).
- **Salesforce** — Approval Process metadata object (steps with "All must approve"/"Anyone"/"First response"; entry criteria; actions).
- **Workday** — Business Process framework with declarative approval steps, parallel/sequential, delegation, escalation.

## Appendix B — Cross-Product Summary Matrix

| Capability | Procore | PlanRadar | Odoo | SAP B1 | Sage | Aconex | D365+Power Auto | Fieldwire | Bluebeam | Buildertrend |
|---|---|---|---|---|---|---|---|---|---|---|
| Construction docs (RFI/Submittal/Drawing) | ★★★★★ | ★★★★ | ★★ | ★ | ★ | ★★★★★ | ★★ | ★★★ | ★★★★★ | ★★★ |
| Field/mobile | ★★★★ | ★★★★★ | ★★ | ★ | ✗ | ★★ | ★★★ | ★★★★★ | ★★★ | ★★★★ |
| Offline | ★★★ | ★★★★★ | ★ | ✗ | ✗ | ★ | ★★ | ★★★★★ | ★★★ | ★★★ |
| Budget/commitment | ★★★★★ | ★★ | ★★★★ | ★★★ | ★★★★ | ★ | ★★★★ | ★ | ✗ | ★★★ |
| Stock/inventory | ★★ (Materials only) | ✗ | ★★★★★ | ★★★★ | ★★★ | ✗ | ★★★★★ | ✗ | ✗ | ★ |
| Three-way match | ✗ (via ERP) | ✗ | ★★★★ | ★★★★ | ★★★★ | ✗ | ★★★★ | ✗ | ✗ | ★ |
| Approval workflow flex | ★★★★ | ★★★ | ★★★ | ★★★ | ★ | ★★★★ | ★★★★★ | ★★ | ★★ | ★★★ |
| Visibility/dashboards | ★★★★★ | ★★★★ | ★★★ | ★★★ | ★★ | ★★★★ | ★★★★★ | ★★★ | ★ | ★★★ |
| Security granularity | ★★★★★ | ★★★ | ★★★★ | ★★★★ | ★★★ | ★★★★★ | ★★★★ | ★★★ | ★★ | ★★★ |
| Fiscal/localization (Tunisia/Africa) | ★ | ★★ (FR) | ★★★★ | ★★★★ | ★★★★★ | ★ | ★★★ | ✗ | ✗ | ✗ |
| Cost for 30-p PME | ★ (too high) | ★★★★ | ★★★★ | ★★ | ★★★★ | ★ (too high) | ★★★ | ★★★★ | ★★★★ | ★★★ |
| WhatsApp/native chat | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ | ✗ (via Teams) | ✗ | ✗ | ★★ |

**The HYDRA opportunity is the empty upper-right quadrant:** construction-domain depth (RFI/Submittal/Punch) + mobile/offline-first + stock/ATP/three-way-match + fiscal-localization + WhatsApp + PME cost — a combination no current product delivers for the Tunisian/francophone-African market.
