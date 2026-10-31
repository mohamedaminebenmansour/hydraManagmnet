"""Fix TypeScript errors found by `npm run build` for the stock UI."""
from pathlib import Path

ROOT = Path('/tmp/my-project/frontend/src/app')

# ─────────────────────────────────────────────────────────────────────
# Fix 1 — stock.service.ts: change `AnyRaw` to `any` to bypass
# noPropertyAccessFromIndexSignature on raw backend JSON rows
# (defensive mappers, no real type safety lost)
# ─────────────────────────────────────────────────────────────────────
p = ROOT / 'core/services/stock.service.ts'
s = p.read_text(encoding='utf-8')
s = s.replace(
    'type AnyRaw = Record<string, any>;',
    '// AnyRaw is `any` so the defensive mappers can use dot-notation for\n'
    '// backend JSON keys without tripping noPropertyAccessFromIndexSignature.\n'
    'type AnyRaw = any;',
    1
)
p.write_text(s, encoding='utf-8')
print('OK  stock.service.ts  AnyRaw → any (unblock dot-notation access)')

# ─────────────────────────────────────────────────────────────────────
# Fix 2 — stock.model.ts: add `projectName` to BalanceRow (used in
# stock-list table column)
# ─────────────────────────────────────────────────────────────────────
p = ROOT / 'core/models/stock.model.ts'
s = p.read_text(encoding='utf-8')
old_iface = (
    'export interface BalanceRow {\n'
    '  itemId: string;\n'
    '  itemName: string;\n'
    '  itemCode?: string | null;\n'
    '  uom: string;\n'
    '  locationId: string;\n'
    '  locationName: string;\n'
    '  locationType: StockLocationType;\n'
    '  projectId?: string | null;\n'
    '  qty: number;\n'
    '}'
)
new_iface = (
    'export interface BalanceRow {\n'
    '  itemId: string;\n'
    '  itemName: string;\n'
    '  itemCode?: string | null;\n'
    '  uom: string;\n'
    '  locationId: string;\n'
    '  locationName: string;\n'
    '  locationType: StockLocationType;\n'
    '  projectId?: string | null;\n'
    '  projectName?: string | null;\n'
    '  qty: number;\n'
    '}'
)
assert old_iface in s
s = s.replace(old_iface, new_iface, 1)
# Also map projectName in mapBalanceRow
old_map = (
    '    projectId: raw?.projectId ?? raw?.project_id ?? null,\n'
    '    qty: Number(raw?.qty ?? 0),\n'
    '  };\n'
    '}\n'
    '\n'
    'export function mapOrgBalanceRow'
)
new_map = (
    '    projectId: raw?.projectId ?? raw?.project_id ?? null,\n'
    '    projectName: raw?.projectName ?? raw?.project_name ?? null,\n'
    '    qty: Number(raw?.qty ?? 0),\n'
    '  };\n'
    '}\n'
    '\n'
    'export function mapOrgBalanceRow'
)
assert old_map in s
s = s.replace(old_map, new_map, 1)
p.write_text(s, encoding='utf-8')
print('OK  stock.model.ts     BalanceRow.projectName added + mapped')

# ─────────────────────────────────────────────────────────────────────
# Fix 3 — items-admin / locations-admin / stock-moves / stock-hub:
# replace `signal = false` (assignment) with `signal.set(false)` in
# template event handlers (signal assignment doesn't compile)
# ─────────────────────────────────────────────────────────────────────
for fname in ['items-admin.component.ts', 'locations-admin.component.ts',
              'stock-moves.component.ts']:
    p = ROOT / 'features/stock' / fname
    s = p.read_text(encoding='utf-8')
    # Replace patterns: (onClick)="X = false" → (onClick)="X.set(false)"
    for sig in ['formVisible', 'aliasVisible', 'reverseVisible', 'docsVisible']:
        s = s.replace(
            f'(onClick)="{sig} = false"',
            f'(onClick)="{sig}.set(false)"',
        )
    p.write_text(s, encoding='utf-8')
    print(f'OK  {fname:35s}  signal=false → signal.set(false)')

# ─────────────────────────────────────────────────────────────────────
# Fix 4 — move-form.component.ts / stock-moves.component.ts:
# p-select [options] expects an array, not a Signal. Unwrap with ().
# ─────────────────────────────────────────────────────────────────────
# move-form: itemOptions, fromLocationOptions, toLocationOptions are computed signals
p = ROOT / 'features/stock/move-form.component.ts'
s = p.read_text(encoding='utf-8')
for sig in ['itemOptions', 'fromLocationOptions', 'toLocationOptions']:
    s = s.replace(f'[options]="{sig}"', f'[options]="{sig}()"')
p.write_text(s, encoding='utf-8')
print('OK  move-form.component.ts       [options] → call signal()')

# stock-moves: itemOptions, locationOptions are computed signals
p = ROOT / 'features/stock/stock-moves.component.ts'
s = p.read_text(encoding='utf-8')
for sig in ['itemOptions', 'locationOptions']:
    s = s.replace(f'[options]="{sig}"', f'[options]="{sig}()"')
# Add abs() helper for the template (templates can't see Math)
if 'abs = (n: number)' not in s:
    s = s.replace(
        '  // Visual helpers\n  mtv = moveTypeVisual;',
        '  // Visual helpers\n  mtv = moveTypeVisual;\n  abs = (n: number | null | undefined): number => Math.abs(Number(n ?? 0));'
    )
# Replace Math.abs(m.qty) in template with abs(m.qty)
s = s.replace('Math.abs(m.qty)', 'abs(m.qty)')
p.write_text(s, encoding='utf-8')
print('OK  stock-moves.component.ts     [options] call signal() + abs() helper')

print('\nAll TypeScript fixes applied.')
