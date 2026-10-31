# Complex vs. Simple ERP Design — A Deep UX Research Brief

**Scope:** Enterprise / BTP (Bâtiment & Travaux Publics, i.e. construction) ERP systems.
**Purpose:** Give a product team a shared vocabulary, measurable thresholds, anti-patterns to avoid, patterns to copy, and an opinionated definition of "simple."

> **Methodological note.** This brief synthesizes established UX literature (Nielsen, Norman, Cooper, Spool, Sweller) and well-documented industry cases. Where a specific figure is cited to a published source, it is named. Where a threshold is offered as a *design target* rather than a research finding, it is labeled "design target." Web references are listed at the end; the synthesis is the author's.

---

## 1. Definitions

### 1.1 What is "complex design" in enterprise software?

In UX terms, complexity is **not** the same as "having many features." A system can have thousands of features and still feel simple (Linear, Excel). Complexity is the **friction between the user's intent and the system's response** — the cognitive and motor cost of translating "what I want to do" into "what the screen makes me do."

Don Norman distinguishes **complexity** (an objective property of a problem — a construction ERP genuinely *is* a complex problem) from **complicatedness** (a subjective property of an interface — "hard to understand, hard to use"). His thesis in *Living with Complexity* (2010): **we don't want simplicity, we want understanding.** The goal is to *tame* complexity, not to deny it. Hiding a complex workflow behind a smiling dashboard makes things worse, not better.

Alan Cooper (*About Face*) frames the same idea as the distinction between **implementation model** (how the database/developer thinks), **mental model** (how the user thinks), and **represented model** (what the UI shows). Complexity pain arises when the represented model mirrors the implementation model instead of the user's mental model. A screen showing `STA01 / DOC_TYPE=BCT / KNUMV=0000123` is implementation-model UI. A screen showing *"Bon de commande — Chantier Casablanca Marina — En attente validation chef de projet"* is mental-model UI.

Jared Spool's contribution is operational: he distinguishes **"self-evident"** (you know what to do by looking) from **"obvious"** (you know what to do after a moment) from **"confusing"** (you don't know what to do). His research (UIE) shows the dominant cost in enterprise tools is **"knowledge gaps"** — the gap between what the user must know to act and what the screen tells them. A simple ERP closes the gap; a complex ERP widens it.

Nielsen Norman Group (NN/g) operationalizes enterprise complexity around three measurable ideas: **learnability** (time to reach competent performance), **efficiency** (time-on-task for experts), and **error frequency/severity**. A complex ERP fails all three: slow to learn, slow even for experts, error-prone.

> **Working definition for this brief:** *A complex ERP is one where the cost of translating intent into action grows faster than the power the system provides.* A simple ERP keeps that cost low even as power grows.

### 1.2 What is "simple design"? Simple ≠ Simplistic

This distinction is the most important one in enterprise UX and the most abused by PMs.

- **Simple** = low friction to accomplish a goal, *without sacrificing capability.* The user can do everything the domain requires, with minimal cognitive overhead. Excel is simple-and-powerful. A well-designed "New Bon de Commande" screen that defaults 80% of fields and exposes the other 20% on demand is simple-and-powerful.
- **Simplistic** = low friction *achieved by removing capability.* A "create invoice" screen with only `Amount` and `Send` looks simple but cannot encode tax, retention, chantier, or payment terms. It is simplistic: it pushes the real work elsewhere (into Excel, into email, into phone calls).

Don Norman's warning (often paraphrased): *"When things are too simple, they're understood quickly but break under real use. When they're too complex, they're never understood."* The designer's job is the **saddle point**: enough structure to be correct, enough transparency to be fast.

The pragmatic test NN/g offers: **"Does removing this thing make the task impossible, illegal, or un-auditable? If yes, it's not complexity to remove — it's complexity to organize."**

| | Simple | Simplistic | Complex |
|---|---|---|---|
| Capability | Full | Truncated | Full |
| Cognitive cost | Low | Low | High |
| Real-world correctness | High | Low | High (but unreachable) |
| User feeling | "I can do this" | "This can't do my job" | "I'm lost" |
| Shadow-IT risk | Low | **High** (users flee to Excel) | High (users avoid the system) |

### 1.3 Nielsen's 10 Usability Heuristics, applied to ERP

Nielsen's heuristics (1994, *Usability Engineering* / NN/g) are the universal yardstick. Here is each one *operationalized* for a BTP ERP:

| # | Heuristic | ERP anti-example (complex) | ERP pro-example (simple) |
|---|---|---|---|
| 1 | **Visibility of system status** | A Bon de Commande vanishes into "workflow" with no indication of who has it, where it is, or what's next. | A persistent status bar: `Submitted → Validating (Chef de projet: Y. Amrani) → Next: Finance`. |
| 2 | **Match system & real world** | `BU=03 / CC=KNS / STA01`. Field labeled "KNUMV". | Field labeled "Chantier: Casablanca Marina, Tour B". Status "En attente validation". |
| 3 | **User control & freedom** | Once submitted, the BC is immutable; a typo requires a support ticket. | "Withdraw for edit" button; clear cancel; undo on inline edits. |
| 4 | **Consistency & standards** | "Save" in module A, "Valider" in B, "Confirmer" in C — three buttons, one action. | One verb everywhere: green "Enregistrer" = persist, "Soumettre" = send to workflow. |
| 5 | **Error prevention** | User can submit a BC to a closed chantier and only learn it on rejection 3 days later. | Chantier selector greys out closed sites; blocked suppliers show a red chip inline. |
| 6 | **Recognition rather than recall** | User must memorize the 4-digit supplier code and chantier code. | Search-first: type "amana" → shows "Amana BTP — chantier Marina". Recent list on home. |
| 7 | **Flexibility & efficiency** | Every BC must be entered field-by-field from scratch. | Templates ("Achat courant — ciment"); keyboard nav (Tab/Enter); duplicate-from-last. |
| 8 | **Aesthetic & minimalist** | 47 fields visible, 9 tabs, 3 toolbars. | ~7 visible fields; secondary fields behind "More"; one primary action button. |
| 9 | **Recognize, diagnose, recover** | "Error 500 / OOPS something went wrong / reference CONV_RFC_421". | "Supplier 'Amana' is blocked for payment since 12/03. Contact Finance (ext. 421) or pick another supplier." |
| 10 | **Help & documentation** | A 400-page PDF that opens in a new tab, generic. | Inline `?` next to "TVA déductible" explaining retention (5%) on BTP, with a link only if needed. |

A useful rule: **score the ERP on these 10.** Most legacy ERPs score ≤4/10. A "simple" ERP scores ≥8/10 on the five that matter most for daily use (1, 5, 6, 7, 8).

### 1.4 Laws of UX applied to ERP

#### Hick's Law — *RT = a + b · log₂(n + 1)*
Decision time grows with the log of the number of choices. This is *the* law that explains why a sidebar with 50 modules is slow: the user scans, compares, selects. Each extra option adds ~150–200 ms of decision cost, compounding across thousands of selections/day.

- **ERP implication:** Top-level navigation should expose ≤7 primary destinations (Hick's + Miller). Everything else is reachable via **search** (command-K), which converts *n choices* into *one search* — effectively removing Hick's cost.
- **Sub-menus:** A flyout with 12 options is slower than a 7-option flyout + a "More…" group. Chunking defeats Hick.

#### Miller's Law — *7 ± 2*
Working memory holds roughly 7±2 chunks. A form with 25 visible fields forces the user to scroll/forget/recheck — they cannot hold the task in mind.

- **ERP implication:** Group fields into **blocks of 5–7** (Header → Supplier → Lines → Accounting → Notes). Within a block, visible mandatory fields ≤ 7. Use progressive disclosure: "you're 2 of 4 blocks done."
- This is also why **wizards beat mega-forms** *only* when each step is ≤7 fields and the steps are reversible; otherwise wizards become modal hell.

#### Fitts's Law — *MT = a + b · log₂(2D / W)*
Time to acquire a target grows with distance and shrinks with target size. Sounds trivial; it is routinely violated.

- **ERP implication:** The primary action ("Enregistrer / Soumettre") should be large, pinned, and on the side of the user's dominant hand / cursor path. Destructive actions ("Supprimer") should be **far** from primary actions and require confirmation. Touch targets ≥ 44×44 px (Apple HIG) for field-workers on tablets.
- Critical for BTP: field users on phones/tablets in the rain, with gloves. Fitts's Law is not academic — it determines whether a chantier worker can actually tap the right button.

#### Two more worth naming
- **Jakob's Law:** *"Users spend most of their time on other sites."* So they expect ERP to behave like the web apps they know — search bar top-center, `⌘K`, breadcrumbs, sticky headers. A "novel" ERP navigation is a tax.
- **Tesler's Law (Conservation of Complexity):** Every system has irreducible complexity; it can only be **moved**, not removed. The designer's choice: pay it themselves (defaults, inference, smart rules) or bill it to the user (more fields). **Simple ERPs absorb complexity; complex ERPs invoice it to the user.**

---

## 2. Measurable Complexity Indicators

"Complex" must become measurable or it is just an opinion. Below is a scorecard. For each indicator: **what it measures**, a **target threshold** (design target, not research finding), and a **red-line** (above which the screen/module is almost certainly too complex).

| # | Indicator | Measures | Target | Red line |
|---|---|---|---|---|
| 1 | **Clicks to complete a top task** (Time-on-Task proxy) | Efficiency | Core top-5 task ≤ 5 clicks; read/view ≤ 3 | > 9 clicks |
| 2 | **Visible fields on the most-used screen** | Cognitive load (Miller) | ≤ 12 visible, ≤ 7 mandatory | > 25 visible |
| 3 | **Top-level menu items** | Hick's / navigation friction | ≤ 7 (+ search) | > 12 |
| 4 | **Depth of navigation tree** | Hick's / lost-in-IA | ≤ 3 levels | > 4 levels |
| 5 | **Tabs / sub-tabs on one screen** | Visual complexity, divided attention | ≤ 5 tabs, no nested tabs | > 7 tabs or nested |
| 6 | **Validation steps gating one workflow** | Error-prevention cost | ≤ 3 hard gates; soft warnings inline | > 5 gates |
| 7 | **Statuses a document can be in** | Mental-model load | ≤ 5 primary (+ sub-states hidden) | > 8 visible statuses |
| 8 | **Roles / permission sets to manage** | Admin complexity | ≤ 8 core roles | > 15 roles |
| 9 | **Distinct verbs/buttons meaning "save"** | Consistency (Heuristic 4) | 1 | > 2 |
| 10 | **% of fields with sensible defaults** | Tesler: absorbed complexity | ≥ 60% pre-filled | < 20% |
| 11 | **Field codes shown to users** (e.g., STA01) | Real-world match (Heuristic 2) | 0 | > 0 |
| 12 | **Modals deep-stacked during one task** | Modal-on-modal anti-pattern | 0 (use panels/pages) | ≥ 2 stacked |
| 13 | **Time-to-first-successful-task** (new hire) | Learnability | ≤ 30 min untrained | > 1 day |
| 14 | **% of documents created then edited again < 5 min later** | Error/recovery rate | < 5% | > 20% |

**A practical scorecard:** pick the 5 most-used screens, measure #1, #2, #5, #7, #10. If three of the five are in the red, the ERP is *objectively* complex — independent of opinions.

### 2.1 Cognitive Load — Sweller's three flavors

John Sweller's Cognitive Load Theory (1988) splits mental effort into three kinds. Mapping them to ERP is the single most useful mental model for a designer:

| Type | Definition | ERP example | Design response |
|---|---|---|---|
| **Intrinsic** | Difficulty inherent to the task itself. Cannot be removed, only sequenced. | Creating a multi-line BC with tax, retention, and multi-chantier allocation *is* intrinsically hard. | Chunk into steps; teach schemas; provide templates. |
| **Extraneous** | Load added by *bad design* — pure waste. | Hunting for the "Save" button; decoding STA01; re-entering the supplier because the form reset. | **This is the only one you can fully eliminate.** Aim: zero extraneous load. |
| **Germane** | Effort spent building lasting understanding — *good* load. | Learning that "TVA 20% → déductible" because the field shows a one-line explanation. | Encourage with progressive disclosure + contextual micro-copy. |

> **The rule:** *You cannot reduce intrinsic load below the domain's real difficulty. You must eliminate extraneous load. You should design for germane load.* Legacy ERPs are ~70% extraneous load. That is the whole opportunity.

---

## 3. Anti-Patterns of Complex ERP

These are the recurring sins. Each is named, diagnosed, and paired with the heuristic it violates.

### 3.1 Modal on modal on modal
User clicks "Add line" → modal → "Pick supplier" → modal → "Add address" → modal. Three layers deep, esc-able only one at a time, lose context on the parent.
**Violates:** User control & freedom (#3); Aesthetic & minimalist (#8).
**Fix:** Inline panels (slide-over on desktop, bottom sheet on mobile) that preserve the parent context; or full pages with breadcrumbs.

### 3.2 "Module-soup" navigation
A sidebar listing 50 modules, many with overlapping functions ("Achats", "Approvisionnement", "Magasin", "Réception" — four doors to one reality).
**Violates:** Hick's Law; Consistency (#4).
**Fix:** Role-based "spaces" (≤7), search-first, recents on home. Show only what the role uses.

### 3.3 Mandatory fields the user doesn't understand
Field "Code section comptable" is required; user has no idea what to put; guesses; the system accepts; finance corrects a month later.
**Violates:** Error prevention (#5); Real-world match (#2).
**Fix:** Smart defaults (inherit from chantier), inline search with human labels, and "I don't know → ask my manager" routing rather than forcing a guess.

### 3.4 Hidden workflow visibility
"I submitted it Tuesday. Where is it?" The answer requires opening the doc and scrolling to an "audit log" tab nobody told them about.
**Violates:** Visibility of system status (#1) — the #1 complaint in ERP usability studies.
**Fix:** A global "My pending" inbox showing every doc the user touched and its current gate + next actor + ETA.

### 3.5 Duplicate data entry
Supplier address typed in the supplier master, retyped on the BC, retyped on the delivery note, retyped on the invoice. Each retyping is a chance for a typo and an audit break.
**Violates:** Efficiency (Heuristic 7); Error prevention (#5).
**Fix:** Single source of truth; everywhere else references it; "override" is explicit and flagged.

### 3.6 Status codes without labels
`STA01 / STA02 / REL / CRTD / INPR`. The user memorizes a decoder ring; new hires are helpless.
**Violates:** Real-world match (#2); Recognition over recall (#6).
**Fix:** Always show a human label + color. Hide the code entirely unless the admin role is active.

### 3.7 Buttons labeled with internal codes
`ZMM_BDC_CREATE`, `ME21N`, `FB60`. (SAP transaction codes are the canonical example.)
**Violates:** Match to real world (#2).
**Fix:** "Nouveau Bon de Commande", "Saisir facture". Keep codes as power-user shortcuts (type `me21n` in command-K), never as the primary label.

### 3.8 Bonus anti-patterns (less famous, equally lethal)
- **The reset-to-top on every save** — user loses scroll position and context.
- **Save-and-continue vs Save-and-close ambiguity** — two buttons, no clear winner.
- **Date pickers that reject manual typing** — power users type dates 10× faster.
- **The "are you sure?" on non-destructive actions** — trains users to click "Yes" blindly, so they click "Yes" on the destructive one too.
- **Print-preview-as-only-view** — forcing PDF round-trips for data that should be inline.

---

## 4. Patterns of Simple ERP

The positive mirror. Each pattern maps to an indicator in §2.

### 4.1 Single-screen task completion
The top-5 tasks complete on one screen, no navigation away. Tabs are for *views* of the same object, not steps of a flow. *(Indicator #1, #5.)*

### 4.2 Inline editing (click → type → Tab)
A grid where you click a cell, type, press Tab. No modal, no "edit mode" toggle. This is what makes Excel fast — and what makes Linear fast. The ERP grid should behave like a spreadsheet for line items. *(Indicator #1, #12.)*

### 4.3 Defaults that work
Today's date. Last supplier. Last chantier. Last TVA rate for that supplier's category. The system *infers* from context. Tesler's Law in action: the system absorbs complexity. *(Indicator #10 — target ≥60% pre-filled.)*
BTP-specific: default the *chantier* from the user's current assignment; default *delivery address* to the chantier's site; default *cost center* from the chantier.

### 4.4 One primary action per screen
One green button. Everything else is secondary/tertiary. Removes decision cost (Hick) and accidental destructive clicks (Fitts). *(Indicator #9.)*

### 4.5 Contextual help, not a help center
A `?` next to the field, opening a 2-line explanation, example, and a "show me" link. Never a 400-page PDF in a new tab. *(Heuristic 10; Germane load.)*

### 4.6 Search-first navigation
A command-K bar that finds any document, any supplier, any chantier, any menu item. Turns *n choices* into *one search* (defeats Hick). *(Indicator #3, #4.)*

### 4.7 "Recent documents" on the home
The home screen is a personal dashboard: my drafts, my pending approvals, my recently opened BCs. Recognition over recall. *(Heuristic 6.)*

### 4.8 Live, honest status
A persistent, always-visible status line on every document: where it is, who has it, what happens next, ETA. *(Heuristic 1 — the #1 fix.)*

### 4.9 Reversible everything
Withdraw for edit, undo inline change, soft-delete with restore for 30 days. *(Heuristic 3.)*

### 4.10 Keyboard-complete
A trained user can do the whole top task without touching the mouse: Tab between fields, Enter to submit, `⌘K` to jump. *(Heuristic 7.)*

---

## 5. The "Excel Mind"

Excel is the **single strongest force in enterprise software** — and the most underestimated. In BTP, the real ERP is often Excel; the official ERP is a reporting tool people fill in on Fridays. Understanding *why* is existential for an ERP product team.

### 5.1 Why users prefer Excel for data-heavy work

- **Zero latency between thought and cell.** You click, you type. No "edit mode", no modal, no validation pop-up interrupting flow.
- **Free-form.** A remark column accepts any text; you can invent a temporary category, color it red, add a note. The system doesn't fight you.
- **Copy/paste freedom.** Duplicate 50 lines in 2 seconds. Adjust 100 quantities by paste-special-multiply.
- **No modals, no validation pop-ups.** Flow state preserved.
- **You own the mental model.** The sheet looks exactly like the task in your head.
- **No "submit and wait."** Everything is instant and local.
- **No training.** Everyone already knows it.

This is the **shadow-IT** phenomenon: when the official tool's friction exceeds the work's intrinsic difficulty, users route around it. Excel wins not because it's better, but because it has **lower extraneous load**.

### 5.2 What Excel does right (steal these)
1. **Instant grid** — click-cell-type, no friction.
2. **No modal for line entry** — rows just appear.
3. **No premature validation** — type freely, fix later.
4. **Copy/paste & fill** — bulk operations trivial.
5. **Free-form remarks** — a notes column that accepts anything.
6. **Visual immediacy** — colors, bold, conditional formatting to encode meaning the eye reads in 200 ms.
7. **Calculation transparency** — `=B2*C2` shows the math; trust through inspectability.

### 5.3 What Excel does wrong (your ERP must fix these)
1. **No audit trail** — who changed what, when? (Excel: nobody knows.)
2. **No validation** — typos in supplier name create duplicates forever.
3. **No multi-user concurrency** — "who has the latest version.xlsx?" is a daily BTP drama.
4. **No linking** — a BC in one sheet, a delivery note in another, never reconciled.
5. **No permissions** — anyone with the file can change anything.
6. **No workflow / approval** — a "validated" column anyone can tick.
7. **Fragile, not durable** — a deleted row is gone; a corrupted file is catastrophe.

### 5.4 The "best of both worlds" pattern: *looks like Excel, behaves like an ERP*

This is the design thesis of the modern wave (Airtable, Smartsheet) and the correct target for a BTP ERP's line-entry screens:

- **Grid surface:** cells you click and type into, like Excel.
- **Row = record:** each row is a typed, validated entity (a BC line, a supplier, a chantier), not free text.
- **Live validation:** a red border + inline message on a bad value — *not* a blocking modal. The user sees the problem, keeps typing, fixes it.
- **Paste with mapping:** pasting an Excel block opens a non-blocking "map these columns?" panel; accepts or remaps.
- **Free-form notes column** that accepts anything — preserving the "I'll fix it later" freedom — *plus* structured columns the system enforces.
- **Color = status:** row tinted by status (draft grey, pending amber, approved green, blocked red) — the eye reads it instantly.
- **Multi-user & audit:** multiple cursors (or conflict-aware merge), every change logged, reversible.
- **No "submit-and-pray":** auto-save + explicit "send to workflow" as a deliberate, one-click action.

> **Design heuristic:** *If a BTP controller could not move their daily spreadsheet into your screen in under 5 minutes and have it be strictly better (validated, shared, audited), the screen is not simple enough.*

---

## 6. Concrete Case Studies

### 6.1 Airtable — reinventing the spreadsheet as a database
**What it did:** Took the grid mental model of Excel and made each row a *typed, relational record*. Same surface, real schema underneath. Added views (grid, kanban, calendar, gallery, Gantt) over the same data.
**Why it feels simple:** the represented model matches the user's mental model (a grid of stuff), but the implementation model is a real database. Tesler's Law executed perfectly: complexity absorbed by the system, not the user.
**BTP lesson:** the same "Bon de Commande" can be a grid for the buyer, a kanban for the chef de chantier, and a Gantt for the planner — *one source of truth, multiple views*. This is exactly what BTP needs.

### 6.2 Smartsheet — Excel's enterprise cousin
**What it did:** Kept the Excel grid (formulas, cells, formatting) and bolted on Gantt, attachments, row-level permissions, and workflow. It is the bridge for users who refuse to leave Excel.
**Why it works:** it meets users where they are. It didn't ask accountants to learn a new paradigm; it gave their spreadsheet audit trails and sharing.
**BTP lesson:** do not force users to abandon their spreadsheet habits; *reward* them with structure underneath.

### 6.3 Notion — blocks and flexible databases
**What it did:** Made documents and databases the same substrate (a "block"). A table can be a kanban, a calendar, a gallery. Pages nest infinitely.
**Why it feels light:** minimal chrome, everything editable inline, `⌘K` everywhere.
**Caution for ERP:** Notion's flexibility is also its weakness for structured BTP workflows — too much freedom, no enforced schema. The lesson is *flexibility of view, strictness of data*.

### 6.4 Linear — making issue tracking feel fast
**What it did:** Re-engineered the feel of issue tracking around **speed**. Optimistic UI (clicks register before the server responds), keyboard-first (`⌘K`, `C` to create, `A` to assign), no modals for editing, instant search, status changes inline.
**Why it matters:** Linear proved that an "enterprise" tool (issue tracking is structurally similar to ERP document tracking) can feel like a consumer app. Its secret is **eliminating extraneous load entirely**: no spinner-watching, no modal chains, no scroll resets.
**BTP lesson:** a BC should move through its lifecycle as fast as a Linear issue moves through its statuses. If it doesn't, the friction is *design*, not domain.

### 6.5 Retool / Appsmith — making internal tools feel light
**What they did:** Drag-and-drop builders that connect to a real database, producing internal CRUD tools in hours instead of the months a custom ERP module takes.
**Why it matters:** they prove that the *cost* of building enterprise screens can collapse — and that when it does, teams iterate to simplicity because iteration is cheap.
**BTP lesson:** the ERP should be configurable like Retool (admin builds a screen) without code, so the system adapts to each chantier's reality instead of forcing chantiers to adapt to the system.

### 6.6 The failures — ERPs that broke their companies
These are the canonical "complexity catastrophe" cases. They are object lessons in what happens when an ERP is complex *and* mandated *and* non-negotiable.

- **Hershey's (1999, SAP):** $100M+ loss. A pre-Halloween SAP/R/3 + Manugistics rollout shipped with insufficient testing; order-fulfillment collapsed at peak season. Lesson: a complex ERP with poor implementation timing doesn't just fail to help — it *breaks the business*.
- **Nike (2000, i2 supply-chain):** ~$100M loss, 20% stock drop. The demand-planning module couldn't model Nike's product variety; it generated nonsense orders; warehouses overflowed while stores ran dry. Lesson: a simplistic model of a complex domain produces *worse* decisions than no model.
- **Target Canada (2013, SAP/Oracle):** ~$1B+ write-down, store closures. The new ERP couldn't handle pricing/inventory quirks; empty shelves and overstock simultaneously; the entire Canadian expansion died. Lesson: a "best-practice" ERP forced onto a real, idiosyncratic operation will be rejected by reality.
- **Waste Management vs SAP (2008):** $100M lawsuit. The delivered ERP couldn't handle WM's pricing model; SAP allegedly oversold capability. Lesson: complexity hidden in sales demos surfaces as millions in litigation.
- **HP / SAP (2004):** ~$160M. Order-to-cash failures. Lesson: even giants with money and consultants fail when the represented model doesn't match the business.
- **NHS National Programme for IT (UK):** among the largest public IT failures ever, ~£10B+ largely written off. Lesson: top-down, monolithic, complex ERP against a domain that is genuinely complex and political — without local fit — fails at civilizational scale.

> **Pattern across all failures:** the system was *complex* (high extraneous load, mismatched represented model) **and** *rigid* (couldn't be adapted locally) **and** *mandated* (no escape valve to Excel/manual). The trifecta is lethal. **A simple ERP must be locally adaptable and must always leave the user an honest escape hatch.**

---

## 7. Reference Principles Worth Stealing

- **SAP Fiori (SAP's design system, 2013→):** The "1-1-3" rule — *1 user, 1 use case, 3 screens max*. Role-based apps (not transactions), responsive, coherent. Fiori is SAP's admission that classic SAP (transaction codes, module soup) was too complex. The lesson: even the incumbent agrees the future is role-based, search-first, ≤3 screens per task.
- **Google Material / Apple HIG:** touch targets ≥44 px, one primary action, progressive disclosure — the consumer standards now expected inside enterprise tools (Jakob's Law).
- **NN/g enterprise UX research:** emphasizes task-based IA, not module-based IA; "role-based" over "function-based" navigation.

---

## 8. Synthesis — The 7 Conditions

> The preceding sections converge on a single, testable definition. A BTP ERP is **simple** if and only if it satisfies these seven conditions. Each is measurable, each maps to a §2 indicator, and each can be checked against a real screen in under a minute.

### A BTP ERP is simple if it satisfies these 7 conditions:

1. **Search-first, role-based navigation.** No user ever sees more than 7 top-level destinations, and every document/chantier/supplier is one `⌘K` keystroke away. *(Hick's Law; Indicator #3, #4.)*
2. **Top tasks complete in ≤5 clicks on one screen.** Create a BC, validate a delivery, approve an invoice — each finishes without navigating away, and the line-entry grid behaves like Excel (click, type, Tab, paste). *(Indicator #1, #12; Excel-mind.)*
3. **Defaults that work — ≥60% of fields pre-filled from context.** Today's date, the user's current chantier, last supplier, last TVA, last cost center. The system absorbs the complexity (Tesler); the user adds only the ~40% that genuinely varies. *(Indicator #10.)*
4. **Always-visible, honest status and "what's next."** Every document shows its current gate, the actor holding it, and the next step + ETA — never hidden behind an audit-log tab. No user ever asks "where is my document?" *(Heuristic #1 — the #1 ERP complaint; Indicator #7.)*
5. **No codes, no jargon, one verb per action.** Zero field codes (STA01) or transaction codes (ME21N) shown to users; every status has a human label and a color; "save" is one word everywhere. *(Heuristics #2, #4; Indicator #9, #11.)*
6. **Reversible and non-blocking.** Every action is undoable for 30 days; validation is *inline and non-blocking* (red border + message), never a modal that stops flow; the user never loses scroll position or context. *(Heuristics #3, #5, #8; Anti-patterns #1, #8.)*
7. **It beats Excel on its own turf and never forces the user back.** A BTP controller can move their daily spreadsheet into the ERP's grid in ≤5 minutes and get strict improvements — validation, multi-user, audit trail, linking — *without* losing Excel's speed, paste freedom, and free-form notes. The moment a user exports to Excel to do real work, the ERP has failed this condition. *(Excel-mind §5; the ultimate adoption test.)*

> **One-line summary:** *A simple BTP ERP is one where the complexity of construction lives in the database and the rules engine — never in the user's screen, never in the user's head, and never in a modal.*

---

## 9. Sources & Further Reading

**Foundational UX**
- Don Norman — *The Design of Everyday Things* (1988/2013); *Living with Complexity* (2010).
- Jakob Nielsen — *Usability Engineering* (1993); Nielsen Norman Group, "10 Usability Heuristics" (nngroup.com).
- Alan Cooper et al. — *About Face: The Essentials of Interaction Design*.
- Jared Spool — UIE / Center Centre, "The Anatomy of a Design Decision"; "Magic and Esthetics."
- John Sweller — "Cognitive Load During Problem Solving" (1988); Cognitive Load Theory.

**Laws of UX**
- William Hick (1952); Ray Hyman — choice reaction time. (Hick's Law)
- George Miller — "The Magical Number Seven, Plus or Minus Two" (1956). (Miller's Law)
- Paul Fitts — "The Information Capacity of the Human Motor System" (1954). (Fitts's Law)
- Bruce Tognazzini / Larry Tesler — Conservation of Complexity (Tesler's Law).
- Jakob Nielsen — "Users Spend Most of Their Time on Other Sites" (Jakob's Law).

**Enterprise / ERP**
- SAP Fiori Design Guidelines (experience.sap.com/fiori-design).
- NN/g enterprise UX articles (nngroup.com) on task-based IA, role-based design, forms.
- Marc jas & Spolsky, *Working Backwards* / various — on absorbing complexity.

**Cases**
- Hershey's SAP rollout (1999); Nike i2 (2000); Target Canada SAP/Oracle (2013); Waste Management v. SAP (2008); HP SAP (2004); NHS NPfIT — widely reported; e.g., *Computerworld*, *CIO.com*, *Harvard Business Review* case discussions.

**Products referenced**
- Airtable, Smartsheet, Notion (low-code relational-spreadsheet category).
- Linear (engineering blog: "How Linear builds a product", performance/optimistic-UI writings).
- Retool, Appsmith (internal-tool builders).

---

*End of brief. The 7 conditions in §8 are the actionable conclusion; everything else is evidence.*
