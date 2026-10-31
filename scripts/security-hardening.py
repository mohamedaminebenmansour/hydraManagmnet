#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Execute the 5 tasks from the user's instruction (security: stop test credentials
and demo data from reaching real deployments).

Scope is strictly limited per AI-RULES rule 1 ("Make only the change asked").
"""
from pathlib import Path
import sys

ROOT = Path('/tmp/my-project')

def read(p): return p.read_text(encoding='utf-8')
def write(p, s): p.write_text(s, encoding='utf-8')

# ─────────────────────────────────────────────────────────────────────────────
# Task 1 — Annotate DemoDataRunner with @Profile("demo")
# ─────────────────────────────────────────────────────────────────────────────
def task1_annotate_demo_runner():
    p = ROOT / 'backend/src/main/java/tn/hydra/config/DemoDataRunner.java'
    src = read(p)
    if '@Profile("demo")' in src:
        print("[SKIP] task1: DemoDataRunner already has @Profile(\"demo\")")
        return
    # Add the import after the existing spring imports (keep alphabetical-ish order
    # by inserting after the Component import — minimal change, no reordering)
    old_import = "import org.springframework.stereotype.Component;"
    new_import = ("import org.springframework.stereotype.Component;\n"
                  "import org.springframework.context.annotation.Profile;")
    if old_import not in src:
        print("[FAIL] task1: Component import not found", file=sys.stderr)
        sys.exit(1)
    src = src.replace(old_import, new_import, 1)
    # Add @Profile("demo") just above @Component (the annotation block)
    old_anno = "@Component\n@Order(Ordered.LOWEST_PRECEDENCE)"
    new_anno = "@Component\n@Profile(\"demo\")\n@Order(Ordered.LOWEST_PRECEDENCE)"
    if old_anno not in src:
        print("[FAIL] task1: @Component @Order annotation block not found", file=sys.stderr)
        sys.exit(1)
    src = src.replace(old_anno, new_anno, 1)
    write(p, src)
    print(f"[OK]   task1: added @Profile(\"demo\") to DemoDataRunner")

# ─────────────────────────────────────────────────────────────────────────────
# Task 2 — Delete the last two lines of application.properties that hard-code
#          app.owner.email and app.owner.password
# ─────────────────────────────────────────────────────────────────────────────
def task2_delete_hardcoded_owner():
    p = ROOT / 'backend/src/main/resources/application.properties'
    src = read(p)
    # The last two lines are exactly:
    #   app.owner.email=owner@company.tn
    #   app.owner.password=ChangeMe123!
    # (plus possibly a trailing newline). We delete those two property lines only.
    # The comment line "# Override owner credentials for production" stays per rule 1.
    lines = src.splitlines(keepends=True)
    # Find the two lines to remove (must be the literal hard-coded ones, NOT the
    # ${OWNER_EMAIL} / ${OWNER_PASSWORD} placeholders which must stay).
    to_remove = [
        "app.owner.email=owner@company.tn\n",
        "app.owner.password=ChangeMe123!\n",
        "app.owner.email=owner@company.tn",
        "app.owner.password=ChangeMe123!",
    ]
    new_lines = [ln for ln in lines if ln not in to_remove]
    if len(new_lines) == len(lines):
        # Maybe already done — check
        if "ChangeMe123!" not in src and "owner@company.tn" not in src:
            print("[SKIP] task2: application.properties already cleaned")
            return
        print("[FAIL] task2: hardcoded lines not found in application.properties", file=sys.stderr)
        sys.exit(1)
    write(p, ''.join(new_lines))
    print(f"[OK]   task2: deleted hardcoded app.owner.email/password from application.properties")

# ─────────────────────────────────────────────────────────────────────────────
# Task 3 — render.yaml: change OWNER_PASSWORD to sync: false (no value)
# ─────────────────────────────────────────────────────────────────────────────
def task3_render_yaml_owner_password():
    p = ROOT / 'render.yaml'
    src = read(p)
    old = """      - key: OWNER_PASSWORD
        value: HydraOwner2026!"""
    new = """      - key: OWNER_PASSWORD
        sync: false"""
    if new in src:
        print("[SKIP] task3: render.yaml OWNER_PASSWORD already sync: false")
        return
    if old not in src:
        print("[FAIL] task3: OWNER_PASSWORD block not found in render.yaml", file=sys.stderr)
        sys.exit(1)
    src = src.replace(old, new, 1)
    write(p, src)
    print(f"[OK]   task3: render.yaml OWNER_PASSWORD now sync: false (no value)")

# ─────────────────────────────────────────────────────────────────────────────
# Task 4 — Replace literal passwords in README-DEPLOY.md + backend/docs/*.md
#          HydraOwner2026!  -> <OWNER_PASSWORD>
#          HydraDemo2026!   -> <DEMO_PASSWORD>
# ─────────────────────────────────────────────────────────────────────────────
def task4_replace_password_literals():
    targets = [ROOT / 'README-DEPLOY.md'] + sorted((ROOT / 'backend/docs').glob('*.md'))
    total_owner = 0
    total_demo = 0
    files_touched = []
    for p in targets:
        src = read(p)
        n_owner = src.count('HydraOwner2026!')
        n_demo = src.count('HydraDemo2026!')
        if n_owner == 0 and n_demo == 0:
            continue
        new = src.replace('HydraOwner2026!', '<OWNER_PASSWORD>').replace('HydraDemo2026!', '<DEMO_PASSWORD>')
        write(p, new)
        files_touched.append((p, n_owner, n_demo))
        total_owner += n_owner
        total_demo += n_demo
    print(f"[OK]   task4: replaced {total_owner}x 'HydraOwner2026!' -> <OWNER_PASSWORD> and "
          f"{total_demo}x 'HydraDemo2026!' -> <DEMO_PASSWORD> across {len(files_touched)} file(s):")
    for p, no, nd in files_touched:
        rel = p.relative_to(ROOT)
        print(f"         - {rel}  (owner:{no}, demo:{nd})")

# ─────────────────────────────────────────────────────────────────────────────
# Task 5 — Remove backend/data/ and tool-results/ from git tracking.
#          Both are already in .gitignore (verified) — so we only untrack.
# ─────────────────────────────────────────────────────────────────────────────
def task5_untrack_data_and_toolresults():
    import subprocess
    # Verify .gitignore already has them (no-op if not, but we won't duplicate)
    gi = read(ROOT / '.gitignore')
    has_data = 'backend/data/' in gi
    has_tool = 'tool-results/' in gi
    note = []
    if not has_data:
        # append (rule 1 says add to .gitignore — so appending is in scope)
        gi += "\nbackend/data/\n"
        write(ROOT / '.gitignore', gi)
        note.append("added 'backend/data/' to .gitignore")
    else:
        note.append("'backend/data/' already in .gitignore")
    if not has_tool:
        gi += "\ntool-results/\n"
        write(ROOT / '.gitignore', gi)
        note.append("added 'tool-results/' to .gitignore")
    else:
        note.append("'tool-results/' already in .gitignore")
    # Untrack (git rm --cached -r) — keeps the working-tree files
    tracked = subprocess.run(
        ['git', '-C', str(ROOT), 'ls-files', 'backend/data/', 'tool-results/'],
        capture_output=True, text=True
    ).stdout.strip().splitlines()
    if not tracked:
        print(f"[OK]   task5: nothing to untrack (already not tracked). {', '.join(note)}")
        return
    # git rm --cached -r each path
    r = subprocess.run(
        ['git', '-C', str(ROOT), 'rm', '--cached', '-r', 'backend/data/', 'tool-results/'],
        capture_output=True, text=True
    )
    if r.returncode != 0:
        print(f"[FAIL] task5: git rm --cached failed: {r.stderr}", file=sys.stderr)
        sys.exit(1)
    n = len(tracked)
    print(f"[OK]   task5: untracked {n} file(s) from git (backend/data/ + tool-results/). "
          f"{', '.join(note)}")
    for f in tracked[:10]:
        print(f"         - {f}")
    if len(tracked) > 10:
        print(f"         ... ({len(tracked) - 10} more)")

# ─────────────────────────────────────────────────────────────────────────────
def main():
    task1_annotate_demo_runner()
    task2_delete_hardcoded_owner()
    task3_render_yaml_owner_password()
    task4_replace_password_literals()
    task5_untrack_data_and_toolresults()
    print("\nAll 5 tasks done. Now run mvn test + npm run build per AI-RULES rule 8.")

if __name__ == '__main__':
    main()
