# UI Dual View — Table (default) + Cards

## Why

BTP finance users live in Excel. Their mental model is a dense grid: rows =
operations, columns = fields, scan = eye. Cards are nice for visual summaries
but lose the "scan 50 rows in 5 seconds" capability that Excel users rely on.

**Default = Table. Optional = Cards.** User preference persists in localStorage.

## Architecture

`ViewModeService` (`core/view-mode.service.ts`):
- `forPage(pageKey, defaultMode)` → returns a `WritableSignal<'table' | 'cards'>`
- `set(pageKey, mode)` → updates signal + localStorage
- `toggle(pageKey)` → flips between table and cards
- Per-page localStorage key: `hydra.view.{pageKey}` (e.g. `hydra.view.movements`)

## Pages with dual view

| Page | Key | Default | Component |
|------|-----|---------|-----------|
| Cash movements history (pot detail) | `movements` | `table` | `PotDetailComponent` |

## Table columns (Excel mental model for cash)

| Date | Libellé | Tiers | Type | Mode | Montant | Statut |
|------|---------|-------|------|------|---------|--------|
| dd/MM/yyyy | label | party name | IN/OUT/TRANSFER icon | Espèces/Chèque/... | +/− amount (green/red) | Brouillon/En attente/Comptabilisé/Annulé |

- Row click → movement detail
- DRAFT rows highlighted amber
- Amount: green for IN, red for OUT
- Mode: small chip
- Status: colored chip (reuse existing `movementStatusVisual`)

## Cards view (existing layout, unchanged)

- Icon + label + date + party + amount + status chip
- Same row click → movement detail

## Toggle UI

Two icon buttons (top-right of the section header):
- `pi-list` (Tableau) — active state: white background + teal icon
- `pi-th-large` (Cartes) — active state: white background + teal icon
- `pTooltip` shows "Tableau" / "Cartes"

## How to test

1. Open a caisse (`/tresorerie/caisses/:id`)
2. Scroll to "Historique des opérations"
3. Default view = Tableau (dense rows)
4. Click "Cartes" → switches to card layout
5. Refresh page → Cards view persists (localStorage)
6. Click "Tableau" → back to table
7. Filters (date range) apply to both views

## Future pages (pattern ready)

Apply the same `ViewModeService.forPage()` pattern to:
- Parties list (`parties` key)
- Projects list (`projects` key)
- Documents list (`documents` key)
- Mes tâches (keep cards default; table secondary)
