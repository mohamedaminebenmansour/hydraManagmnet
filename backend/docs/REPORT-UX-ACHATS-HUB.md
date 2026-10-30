# REPORT — Achats Hub + Project Tiles

## Before/After sidebar menu

### BEFORE (15 flat items)
```
Accueil
Mes tâches
Trésorerie
Espace Finance
Processus
Chantiers
Matériel
Commandes           ← scattered (no grouping)
Réceptions          ← scattered
Rapprochement       ← scattered
Factures            ← scattered
Tâches / Signalements
Utilisateurs
Rôles
Journal
```

### AFTER (12 items + 1 expandable)
```
Accueil
Mes tâches
Trésorerie
Espace Finance
Processus
Chantiers
Matériel
Achats ▾                        ← NEW: expandable parent
  ├─ Commandes
  ├─ Réceptions fournisseur
  ├─ Factures
  └─ Rapprochement
Tâches / Signalements
Utilisateurs
Rôles
Journal
```

- "Achats" is collapsed by default, auto-expands when a child route is active
- Click "Achats" to expand/collapse
- "Espace Finance" and "Rôles" kept (not reachable in 1 click from their parents)
- Label "Réceptions" → "Réceptions fournisseur" (clarity: supplier BL ≠ internal transfer)
- All routes unchanged

## Project detail — 4 hub tiles

Added between hero and cash-strip on `/projects/:id`:

| Tile | Icon | Links to | Hint |
|------|------|----------|------|
| Achats | pi-shopping-cart (#2563eb) | `/stock/commandes?projectId=:id` | Commandes · Réceptions · Factures |
| Stock | pi-box (#0d9488) | `/stock/stocks?projectId=:id` | Niveaux · Mouvements |
| Argent | pi-wallet (#f59e0b) | `/tresorerie/caisses` | Caisse du chantier |
| Documents | pi-folder (#6366f1) | Tab "Dossiers" (click → activeTab=0) | Dossiers du chantier |

Static links + icons. No new APIs. No counts (deferred — would need per-project count endpoints).

## How owner finds BC in < 5 seconds

1. Click **Achats** in sidebar → expands
2. Click **Commandes** → BC list loads
3. Total: **2 clicks, < 5 seconds** ✅

## ng build: 0 errors ✅

## Files changed

| File | Change |
|---|---|
| `shell.component.ts` | NavItem +children; Achats parent with 4 children; achatsExpanded signal; auto-expand on init; template + styles for parent/child nav items |
| `project-detail.component.html` | +4 hub tiles (Achats/Stock/Argent/Docs) between hero and cash-strip |
| `project-detail.component.scss` | +styles for hub tiles (responsive grid) |
