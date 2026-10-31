"""
Surgical edits to three existing frontend files so the new Stock module
plugs into the existing app without rewriting whole files.

Files patched (all under /tmp/my-project/frontend/src/app/):
  1. core/permissions.ts   — add STOCK_VIEW/STOCK_MOVE/STOCK_ADMIN perms +
                             French labels + a 'stock' PERM_GROUP
  2. app.routes.ts          — add 6 lazy routes under /stock (guarded by
                             permGuard(PERM.STOCK_VIEW))
  3. features/shell/shell.component.ts — add a NAV entry 'Matériel' between
                             'Chantiers' and 'Tâches / Signalements'
"""
import re
import sys
from pathlib import Path

ROOT = Path('/tmp/my-project/frontend/src/app')

# ─────────────────────────────────────────────────────────────────────
# 1. permissions.ts
# ─────────────────────────────────────────────────────────────────────
p = ROOT / 'core' / 'permissions.ts'
src = p.read_text(encoding='utf-8')

# 1a. Add the three STOCK_* constants inside the PERM object.
old_perm_end = "  CASH_VOID: 'CASH_VOID',\n  PARTY_MANAGE: 'PARTY_MANAGE'\n} as const;"
new_perm_end = (
    "  CASH_VOID: 'CASH_VOID',\n"
    "  PARTY_MANAGE: 'PARTY_MANAGE',\n"
    "  // Stock & matériel (backend: V30__stock_virtual_v1.sql)\n"
    "  STOCK_VIEW: 'STOCK_VIEW',\n"
    "  STOCK_MOVE: 'STOCK_MOVE',\n"
    "  STOCK_ADMIN: 'STOCK_ADMIN'\n"
    "} as const;"
)
assert old_perm_end in src, 'PERM block tail not found'
src = src.replace(old_perm_end, new_perm_end, 1)

# 1b. Add French labels.
old_lab_end = "  PARTY_MANAGE: 'Gérer les sous-traitants'\n};"
new_lab_end = (
    "  PARTY_MANAGE: 'Gérer les sous-traitants',\n"
    "  STOCK_VIEW: 'Voir le stock',\n"
    "  STOCK_MOVE: 'Saisir les mouvements de stock',\n"
    "  STOCK_ADMIN: 'Administrer les articles et emplacements',\n"
    "};"
)
assert old_lab_end in src, 'PERM_LABELS tail not found'
src = src.replace(old_lab_end, new_lab_end, 1)

# 1c. Add the 'stock' PERM_GROUP just before the 'tickets' group.
old_grp = (
    "  { key: 'cash',     icon: 'pi pi-wallet',    title: 'Trésorerie',\n"
    "    ids: ['CASH_READ','CASH_DRAFT','CASH_SUBMIT','CASH_POST','CASH_VOID','PARTY_MANAGE'] },\n"
    "  { key: 'tickets',  icon: 'pi pi-ticket',    title: 'Tickets',"
)
new_grp = (
    "  { key: 'cash',     icon: 'pi pi-wallet',    title: 'Trésorerie',\n"
    "    ids: ['CASH_READ','CASH_DRAFT','CASH_SUBMIT','CASH_POST','CASH_VOID','PARTY_MANAGE'] },\n"
    "  { key: 'stock',    icon: 'pi pi-box',       title: 'Stock',\n"
    "    ids: ['STOCK_VIEW','STOCK_MOVE','STOCK_ADMIN'] },\n"
    "  { key: 'tickets',  icon: 'pi pi-ticket',    title: 'Tickets',"
)
assert old_grp in src, 'PERM_GROUPS cash→tickets block not found'
src = src.replace(old_grp, new_grp, 1)

p.write_text(src, encoding='utf-8')
print('OK  permissions.ts  (+3 perms, +3 labels, +1 group)')

# ─────────────────────────────────────────────────────────────────────
# 2. app.routes.ts — add 6 stock routes just before the closing ']'
# ─────────────────────────────────────────────────────────────────────
p = ROOT / 'app.routes.ts'
src = p.read_text(encoding='utf-8')

stock_routes = """      // Stock & matériel — read access required (write actions re-checked in
      // components: STOCK_MOVE to post moves, STOCK_ADMIN for items/locations admin)
      { path: 'stock', canActivate: [permGuard(PERM.STOCK_VIEW)],
        loadComponent: () => import('./features/stock/stock-hub.component').then(m => m.StockHubComponent) },
      { path: 'stock/stocks', canActivate: [permGuard(PERM.STOCK_VIEW)],
        loadComponent: () => import('./features/stock/stock-list.component').then(m => m.StockListComponent) },
      { path: 'stock/mouvements', canActivate: [permGuard(PERM.STOCK_VIEW)],
        loadComponent: () => import('./features/stock/stock-moves.component').then(m => m.StockMovesComponent) },
      { path: 'stock/mouvements/new', canActivate: [permGuard(PERM.STOCK_VIEW)],
        loadComponent: () => import('./features/stock/move-form.component').then(m => m.MoveFormComponent) },
      { path: 'stock/articles', canActivate: [permGuard(PERM.STOCK_VIEW)],
        loadComponent: () => import('./features/stock/items-admin.component').then(m => m.ItemsAdminComponent) },
      { path: 'stock/emplacements', canActivate: [permGuard(PERM.STOCK_VIEW)],
        loadComponent: () => import('./features/stock/locations-admin.component').then(m => m.LocationsAdminComponent) },
    ]
  },
  { path: '**', redirectTo: '' }
];
"""

old_tail = """    ]
  },
  { path: '**', redirectTo: '' }
];
"""
assert old_tail in src, 'app.routes.ts tail not found'
src = src.replace(old_tail, stock_routes, 1)
p.write_text(src, encoding='utf-8')
print('OK  app.routes.ts  (+6 lazy stock routes)')

# ─────────────────────────────────────────────────────────────────────
# 3. shell.component.ts — add a NAV entry 'Matériel' between Chantiers
#    and Tâches / Signalements
# ─────────────────────────────────────────────────────────────────────
p = ROOT / 'features' / 'shell' / 'shell.component.ts'
src = p.read_text(encoding='utf-8')

old_nav = (
    "  { label: 'Chantiers',      icon: 'pi-briefcase',    path: '/projects',    perm: PERM.PROJECT_READ, color: '#0d9488' },\n"
    "  // Tickets = tâches / signalements simples (assignation, statut, photo) — PAS les validations"
)
new_nav = (
    "  { label: 'Chantiers',      icon: 'pi-briefcase',    path: '/projects',    perm: PERM.PROJECT_READ, color: '#0d9488' },\n"
    "  // Stock & matériel (Part 1.3) — visible to anyone with STOCK_VIEW\n"
    "  { label: 'Matériel',       icon: 'pi-box',           path: '/stock',       perm: PERM.STOCK_VIEW,   color: '#0d9488' },\n"
    "  // Tickets = tâches / signalements simples (assignation, statut, photo) — PAS les validations"
)
assert old_nav in src, 'shell.component.ts NAV insertion point not found'
src = src.replace(old_nav, new_nav, 1)
p.write_text(src, encoding='utf-8')
print('OK  shell.component.ts  (+1 NAV entry: Matériel)')

print('\nAll three patches applied successfully.')
