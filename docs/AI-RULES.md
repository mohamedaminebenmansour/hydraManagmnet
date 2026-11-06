## Your role

Claude Code writes the code for the module « Contrôle de trésorerie v2 ».
The plan is docs/controle/PLAN-V2.md. Do ONE phase per session, in order.
At the start of a phase: read the phase in the plan, then use plan mode and show
the plan (files, steps, tests) before writing code.
Business rules: tools/controle/analyse.py is the reference. The app must give the
same numbers as that script on the same file.
The human merges. Claude never merges and never pushes to main.

## Contrôle module rules

- Package tn.hydra.controle, route /controle, API /api/controle, tables ctl\_\*.
- Never touch cash_pot, cash_movement or the /tresorerie route.
- Line ids must stay identical to convert.py (sha1 of sheet|date|e|d|designation).
  Justifications and validations are keyed by these ids.
- Never commit a real .xlsx or a real import .json. Tests build their own small
  Excel file in code.
- Every table: filters, pagination, total of filtered rows, « Exporter Excel ».
