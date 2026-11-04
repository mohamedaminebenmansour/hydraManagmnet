# HYDRA Démo — company + scenario pack (T9)

A separate company « HYDRA Démo » with 9 ready-made users, 1 chantier and 11
signalements in every state. Anyone can log in and test the full app without
touching real data.

## How to switch it on

On Render (or any Spring Boot host), set **two** environment variables:

| Variable | Value | What it does |
|----------|-------|--------------|
| `SPRING_PROFILES_ACTIVE` | `demo` | Activates `DemoScenarioRunner` + `DemoTachesScenario` (and the old `DemoDataRunner`, which is now off by default). |
| `HYDRA_DEMO_PASSWORD` | e.g. `HydraDemo2026!` | The password for every demo user (BCrypt-hashed at startup). |

**Never** set `HYDRA_DEMO_LEGACY=true` on the real server — that would run the
old big seed (`DemoDataRunner`) in the real company. It defaults to `false`;
leave it that way.

If `HYDRA_DEMO_PASSWORD` is blank, the runner logs a warning and creates
nothing.

## Demo data lives in its own company

- Organisation **« HYDRA Démo »** (fixed id `dddddddd-0000-0000-0000-000000000001`).
- 9 users, 1 chantier (`DEMO-01`), 11 signalements — all in that org.
- The real company (created by `BootstrapRunner`) is never touched: no new
  user, project or ticket appears there.
- Re-running is safe: the runner inserts only if absent and skips a ticket if
  one with the same title already exists.

## The demo cast (9 users)

All passwords = `HYDRA_DEMO_PASSWORD`.

| # | Email | Full name (FR) | Company role | Chantier role | Notes |
|---|-------|----------------|--------------|---------------|-------|
| 1 | `gerant.demo@hydra.demo` | Gérant Démo | OWNER | MANAGER | is_owner = true; bypasses every ACL |
| 2 | `achats.demo@hydra.demo` | Responsable Achats | BUREAU | ENGINEER | can create/start/resolve TACHEs |
| 3 | `metre.demo@hydra.demo` | Métreur | INGENIEUR | ENGINEER | uploads a deliverable in #3 |
| 4 | `finance.demo@hydra.demo` | Responsable Financier | CHEF_PROJET | ENGINEER | resolves #4 |
| 5 | `travaux.demo@hydra.demo` | Conducteur Travaux | CONDUCTEUR | ENGINEER | target of several tasks |
| 6 | `chef.demo@hydra.demo` | Chef Chantier | CONDUCTEUR | ENGINEER | creates #9, acts on #10 |
| 7 | `quantites.demo@hydra.demo` | Responsable Quantités | INGENIEUR | ENGINEER | creates #10 |
| 8 | `externe.demo@hydra.demo` | Consultant Externe | SOUS_TRAITANT | — (not a member) | cannot see project tickets |
| 9 | `doc.demo@hydra.demo` | Responsable Documentaire | DOC_CTRL | ENGINEER | document controller |

## The demo chantier

- **Code**: `DEMO-01`
- **Name**: « Démo — Périmètre irrigué »
- **Client**: « CRDA Démo »
- **Gouvernorat**: Kairouan
- **Status**: ACTIF
- Created through `ProjectService.create` as the gerant, so folders, the COMMUN
  ouvrage and the default folder ACL matrix are all set up exactly like a real
  chantier.

## The 11 records (with expected state)

Every title starts with « [Démo] ».

| # | Created by | Type | Category | Title | Assigned to | Expected state |
|---|-----------|------|----------|-------|-------------|----------------|
| 1 | gerant | TACHE | DOCUMENT | Préparer le dossier de consultation PEHD | achats | OPEN, due +3 |
| 2 | gerant | TACHE | ACHAT | Demander 3 devis pour le ciment | achats | IN_PROGRESS (achats started it) |
| 3 | gerant | TACHE | TECHNIQUE | Calculer les quantités de l'attachement N°1 | metre | RESOLVED (metre started, uploaded a PDF deliverable, resolved) |
| 4 | gerant | TACHE | ADMINISTRATIF | Déposer la caution définitive | finance | CLOSED (finance resolved, gerant verified) |
| 5 | gerant | TACHE | AUTRE | Réserver la salle de réunion | travaux | CANCELLED (gerant cancelled: « Réunion déplacée sur le chantier ») |
| 6 | gerant | TACHE | DOCUMENT | Envoyer le planning au CRDA | travaux | OPEN, OVERDUE (due -2) |
| 7 | gerant | TACHE | FINANCE | Préparer les salaires d'octobre | finance | OPEN, PRIVATE, no chantier |
| 8 | achats | TACHE | ACHAT | Mettre à jour la liste des fournisseurs | achats | OPEN, no chantier |
| 9 | chef | PROBLEME | PANNE_ENGIN | Tractopelle en panne au PK 1+200 | travaux | OPEN, 1 BEFORE photo |
| 10 | quantites | RESERVE | MALFACON | Enduit fissuré sur la cuve | chef | RESOLVED (chef started, added 1 AFTER photo, resolved; origin CLIENT) |
| 11 | — | DEMANDE | INFORMATION | Demander les attestations d'assurance chantier | achats | OPEN, due +5 |

Plus 2 comments:
- On #2, achats writes « J'attends encore le devis d'Ettaamir ».
- On #6, gerant writes « Urgent, le CRDA l'a demandé hier ».

## How to test

Log in as any demo user (password = `HYDRA_DEMO_PASSWORD`) and walk through:

| Step | Log in as | What to check |
|------|-----------|---------------|
| 1 | `gerant.demo` | Home card « Signalements » shows the demo counts (À moi, En retard, À vérifier). |
| 2 | `gerant.demo` | Tickets list: 11 rows, TACHE chip (cyan `#0891b2`), « Bureau » in the Ouvrage/lieu column for #7 and #8, lock icon on #7 (private). |
| 3 | `achats.demo` | « [Démo] Demander 3 devis pour le ciment » is IN_PROGRESS; the comment « J'attends encore le devis d'Ettaamir » is in the timeline. |
| 4 | `metre.demo` | « [Démo] Calculer les quantités… » is RESOLVED; the « Documents livrés » section shows the PDF (filed in `02_TECH`). |
| 5 | `finance.demo` | « [Démo] Déposer la caution définitive » is CLOSED. |
| 6 | `gerant.demo` | « [Démo] Réserver la salle de réunion » is CANCELLED with reason « Réunion déplacée sur le chantier ». |
| 7 | `gerant.demo` | « [Démo] Envoyer le planning au CRDA » is OPEN with a red « En retard » badge (due 2 days ago). |
| 8 | `finance.demo` | « [Démo] Préparer les salaires d'octobre » (private, no chantier) is visible to finance (assignee). |
| 9 | `externe.demo` | Cannot see #7 or #8 (not creator/assignee, no project). They do not appear in the list or search. |
| 10 | `chef.demo` | « [Démo] Tractopelle en panne… » shows 1 photo (BEFORE). « [Démo] Enduit fissuré sur la cuve » is RESOLVED with an AFTER photo. |
| 11 | `gerant.demo` | Create a new TACHE: the 5th tile « Tâche » is cyan; choosing it hides photos/ouvrage/lieu/responsable and shows « Assigné à » (required) + « Tâche privée » checkbox. |
| 12 | `metre.demo` | Open a RESOLVED TACHE → resolve dialog says « Qu'avez-vous livré ? » and offers « Joindre le document produit » (accepts .pdf, .doc, .docx, .xls, .xlsx, .jpg, .png) when the task has a chantier. |

## What is NOT in the demo

- No cash movements, no bordereau, no stock, no process instances — the demo
  pack focuses on the Signalements feature (T1–T9). The old `DemoDataRunner`
  (off by default) still covers the rest when `HYDRA_DEMO_LEGACY=true`.
- No binary files committed: the JPEG and the PDF are generated in code at
  startup (`InMemoryMultipartFile` + `ImageIO` for the image, a hand-written
  minimal PDF for the deliverable).
