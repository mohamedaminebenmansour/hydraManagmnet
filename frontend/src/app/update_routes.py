import re

with open('c:\\Users\\Lenovo\\hydra-web\\src\\app\\app.routes.ts', 'r', encoding='utf-8') as f:
    content = f.read()

# The exact text to find and replace (audit route block + children closing)
old_block = """      { path: 'audit', canActivate: [permGuard(PERM.AUDIT_VIEW)],\n        loadComponent: () => import('./features/audit/audit.component').then(m => m.AuditComponent)\n    ]"""

new_block = """      { path: 'audit', canActivate: [permGuard(PERM.AUDIT_VIEW)],\n        loadComponent: () => import('./features/audit/audit.component').then(m => m.AuditComponent)\n\n      /** --- Process routes (new) --- */\n      { path: 'mes-taches', loadComponent: () => import('./features/process/my-tasks.component').then(m => m.MyTasksComponent) },\n      { path: 'processes/:id', loadComponent: () => import('./features/process/process-timeline.component').then(m => m.ProcessTimelineComponent) },\n      { path: 'process-test', loadComponent: () => import('./features/process/start-process.component').then(m => m.StartProcessComponent) },\n    ]"""

if old_block in content:
    content = content.replace(old_block, new_block)
    with open('c:\\Users\\Lenovo\\hydra-web\\src\\app\\app.routes.ts', 'w', encoding='utf-8') as f:
        f.write(content)
    print('SUCCESS: Routes updated')
else:
    print('FAIL: old_block not found in content')
    # Show what's around line 34-36
    lines = content.split('\n')
    for i in range(32, 40):
        if i < len(lines):
            print(f'Line {i+1}: {repr(lines[i])}')