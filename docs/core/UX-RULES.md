# HYDRA UX rules (Angular 18 + PrimeNG 18)

Users: office and site staff, French-speaking, often under pressure, many used to Excel and
WhatsApp, not to software. The product must feel like **Excel** (grids), **Jira** (work items and
inbox) and **GitHub** (history and review) — without their jargon.

## 1. Every action is an icon **and** a word
- Icon (PrimeIcons) + short French verb: `pi-plus` « Nouveau », `pi-check` « Valider »,
  `pi-times` « Refuser », `pi-send` « Envoyer », `pi-paperclip` « Pièce », `pi-history` « Historique »,
  `pi-flag` « Signaler », `pi-download` « Exporter », `pi-print` « Imprimer », `pi-undo` « Annuler (contre-passer) ».
- Icon-only buttons only in dense grids, always with `aria-label` and a tooltip.
- One icon = one meaning everywhere. Keep the mapping in one file (`shared/hydra-icons.ts`).

## 2. Minimum clicks
- From the home page, every **daily** task is reachable in **1 click**, every weekly task in ≤ 2.
- Primary action of a page is always top-right, same place on every page.
- Forms: one screen, smart defaults (last project/site/category used), `Entrée` saves,
  `Échap` closes, the save button is disabled while sending (no double submit).
- Bulk: select rows → one action for all (validate 20 vouchers in one click).
- Paste from Excel into grids for bulk entry (rows are validated before saving).
- Keyboard: `N` new, `/` search, `?` shortcuts list. A command palette (`Ctrl+K`) to jump anywhere.
- Never ask for confirmation for reversible actions; confirm only irreversible ones, in one dialog
  that says exactly what will happen.

## 3. Excel-like grids (`hydra-grid`)
- Column filter like Excel (checkbox list with counts + search; ranges for amounts and dates).
- Saved views per user, shared views by the owner. Totals of the **filtered** rows in the footer.
- Sum of selected cells shown in the footer. Frozen first column. Export to Excel.
- Inline edit only on drafts. Posted rows are read-only with a lock icon.

## 4. Jira-like records
- Every business record opens in a **side panel** (no page change) with tabs:
  **Détail · Pièces · Validation · Tickets · Historique**.
- Status shown as a coloured chip with icon and word (never colour alone).
- « Mes actions » inbox per role: counts, age of the oldest item, overdue in red.

## 5. GitHub-like history and review
- **Historique** tab: who, when, what changed (old → new), why. Field changes shown as a diff.
- Review before posting: Brouillon → Soumis → Validé (or Refusé with a reason) → Comptabilisé.
- « Annuler » on posted items creates a linked reversal; both stay visible.

## 6. Alerts and warnings — three levels, same everywhere
| Level | Look | Behaviour |
|---|---|---|
| Info | blue, `pi-info-circle` | Shown, nothing required |
| Avertissement | amber, `pi-exclamation-triangle` | User may continue; optional reason; owner sees it in « Exceptions » |
| Bloquant | red, `pi-ban` | Action refused; message says how to fix or who can override |
- Messages say **what**, **why**, and **what to do**, in one or two short sentences.
- Due dates: reminder before (configurable), then overdue alert, then escalation to the owner.
- Alerts come from control policies (`OFF/INFO/WARN/BLOCK`), never hard-coded.

## 7. Language and layout
- French UI, site vocabulary (caisse, bon, décharge, chantier), no accounting jargon on screens
  for site users. Amounts with 3 decimals, space as thousands separator, « TND ».
- Show the useful figure **before** the action (balance after this payment, remaining float).
- Empty states always offer the next action (« Aucun bon — Nouveau bon de caisse »).
- Mobile-friendly layouts for site roles (big touch targets, photo capture).
