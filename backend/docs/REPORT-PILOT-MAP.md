# HYDRA — Pilot Map (Domain + Product Analysis)

> **Scope:** Read-only analysis of all modules, routes, menus, permissions, demo users.
> No code changes. French + English terms.

---

## 1. Module comparison table

| Module | Purpose (FR) | Who uses it | When |
|--------|-------------|-------------|------|
| **Tickets** (Tâches / Signalements) | Signalement simple — "il y a un problème sur le chantier" (fuite, retard, sécurité). Status: DRAFT → IN_REVIEW → APPROVED → IFC → AS_BUILT. Pas d'argent, pas de workflow engine. | CHEF_PROJET, CONDUCTEUR, INGENIEUR, BUREAU, FINANCE | Quand quelqu'un voit un problème sur le terrain et veut le signaler. Pas un processus formel. |
| **Process** (Processus / Mes tâches) | Workflow d'approbation formel avec étapes séquentielles (ex: PAYMENT_APPROVAL: soumettre → chef valide → finance valide → argent décaissé). Multi-étapes, basé sur les rôles, avec commentaires. | CHEF_PROJET (soumet), FINANCE (valide), OWNER (force) | Quand un paiement nécessite une approbation formelle avant que l'argent ne sorte de la caisse. |
| **Payment / Cash** (Trésorerie) | Mouvements d'argent — OUT (payer un sous-traitant), IN (encaisser), TRANSFER (central → chantier). DRAFT → POSTED (immuable) → VOID. Workflow d'approbation optionnel via Process. | FINANCE, CHEF_PROJET, OWNER | Chaque fois que l'argent entre ou sort d'une caisse. L'"Espace Finance" a aussi l'export CSV pour le comptable. |
| **Stock / Achats** (Matériel) | Visibilité matériel + chaîne d'achat: **BC** (Bon de Commande) → **BL** (Bon de Livraison fournisseur) → **stock IN** → **Facture** (facture fournisseur) → **Rapprochement** (match 3-way: commandé / reçu / stocké / facturé). | CHEF_PROJET, ACHAT, FINANCE, BUREAU (lecture) | Quand on commande du matériel, on le reçoit, on le facture, et on veut vérifier que tout concorde. |

### Key distinction: Ticket ≠ Process ≠ Payment

| | Ticket | Process | Payment |
|---|--------|---------|---------|
| **Quoi** | "Y a un problème" | "Valide ce paiement" | "Paye 1000 TND" |
| **Argent?** | Non | Non (mais approuve un paiement) | Oui |
| **Workflow?** | Statut simple | Multi-étapes séquentiel | DRAFT → POSTED (workflow optionnel) |
| **Qui agit** | TICKET_WRITE | Rôle par étape | CASH_DRAFT → CASH_SUBMIT → CASH_POST |
| **Audit** | event_log | event_log + timeline process | event_log + timeline mouvement |

---

## 2. Daily spine for a bureau user (max 6 steps)

The BUREAU role (Amel Riahi) has STOCK_VIEW + TICKET_READ/WRITE + DOCUMENT_READ/UPLOAD + CASH_READ + PROJECT_READ/WRITE — she can see everything but can't create stock moves or post payments.

### Routine quotidienne (6 étapes)

| Étape | Où | Ce qu'elle fait |
|-------|-----|-----------------|
| 1 | **Accueil** (`/`) | Vérifier le tableau de bord — tâches en attente, processus bloqués, chantiers actifs |
| 2 | **Mes tâches** (`/mes-taches`) | Si des étapes de processus lui sont assignées, les valider/refuser |
| 3 | **Rapprochement** (`/stock/rapprochement`) | Vérifier si BC/BL/Facture sont alignés — repérer les MISSING_BL ou QTY_MISMATCH |
| 4 | **Stock** (`/stock/stocks`) | Voir les niveaux de stock par chantier — identifier les ruptures |
| 5 | **Trésorerie** (`/tresorerie`) | Consulter les soldes de caisse — voir si des paiements sont en attente |
| 6 | **Tâches / Signalements** (`/tickets`) | Créer ou suivre un signalement terrain (fuite, retard, sécurité) |

---

## 3. Likely confusions for an Excel user

| # | Confusion | Pourquoi | Impact |
|---|-----------|---------|--------|
| 1 | **Deux "boîtes de réception"** — "Mes tâches" + "Tâches / Signalements" | "Mes tâches" = étapes de processus (formel). "Tâches / Signalements" = tickets simples (informel). Même mot "tâches" dans les deux. | L'utilisateur ne sait pas où chercher ce qui demande son attention. |
| 2 | **Trois entrées "Trésorerie"** — "Trésorerie" + "Espace Finance" + (implicitement) "Caisses" | "Trésorerie" = hub, "Espace Finance" = dashboard FINANCE avec export CSV, "Caisses" = liste des caisses. | L'utilisateur clique "Trésorerie" et ne réalise pas que "Espace Finance" a les paiements en attente + l'export. |
| 3 | **Cinq entrées "Matériel"** — "Matériel" + "Commandes" + "Réceptions" + "Rapprochement" + "Factures" | Cinq items dans la sidebar pour le module stock/achat. Un utilisateur Excel s'attend à une seule feuille "Achats". | L'utilisateur ne sait pas par où commencer. L'ordre logique est: Commandes → Réceptions → Factures → Rapprochement, mais la sidebar ne montre pas cet ordre. |
| 4 | **"Processus" vs "Mes tâches"** | "Processus" = liste des templates (owner construit les workflows). "Mes tâches" = boîte de réception. Un non-owner qui clique "Processus" voit des templates mais ne peut rien faire. | L'utilisateur perd du temps sur le hub Processus au lieu d'aller à Mes tâches. |
| 5 | **"Stock" vs "Matériel"** | La sidebar dit "Matériel" (le hub) mais la route est `/stock` et le titre de la page dit "Stock & Matériel". | Confusion mineure — même chose, deux mots. |
| 6 | **"Plus d'options" sur le formulaire de paiement** | Les dropdowns BC/facture sont cachés sous "Plus d'options" — un utilisateur Excel s'attend à voir tous les champs. | L'utilisateur soumet un paiement sans lier un BC, puis ne comprend pas pourquoi aucune alerte matériel n'apparaît. |

---

## 4. Recommendations for pilot

### KEEP (essentiel pour le pilote)

| Module | Pourquoi |
|--------|---------|
| **Accueil** (dashboard) | Point d'entrée unique — montre ce qui demande attention |
| **Mes tâches** | Boîte de réception d'approbation — critique pour le workflow de paiement |
| **Trésorerie** (hub + caisses + opérations) | Suivi financier de base — chaque entreprise BTP en a besoin |
| **Matériel** (stock hub + stock list + mouvements) | Visibilité matériel — la valeur principale de Part 1 |
| **Commandes** (BC) | Suivi des bons de commande — essentiel pour les achats |
| **Réceptions** (BL) | Réception fournisseur — le lien entre commandes et stock |
| **Rapprochement** (match) | La vue "une seule vérité" — BC vs BL vs stock vs facture |
| **Chantiers** (projets) | L'unité organisationnelle — tout est par chantier |
| **Factures** | Suivi factures fournisseur — nécessaire pour le match |

### MERGE (simplifier pour le pilote)

| Quoi | Comment |
|------|---------|
| "Trésorerie" + "Espace Finance" | Fusionner en un seul hub "Trésorerie". Le dashboard FINANCE (paiements en attente + export) devient un onglet ou une section dans le hub, pas une entrée sidebar séparée. |
| "Processus" hub | Masquer pour les non-owner. Seul OWNER a besoin de voir/construire les templates de processus. Les autres ont juste besoin de "Mes tâches". |
| "Mes tâches" + "Tâches / Signalements" | Renommer pour éviter la confusion: "Mes validations" (processus) vs "Signalements" (tickets). Garder comme entrées sidebar séparées. |

### HIDE (pas nécessaire pour le pilote)

| Quoi | Qui le voit aujourd'hui | Pourquoi cacher |
|------|------------------------|-----------------|
| Process Builder (`/processes/builder`) | OWNER only | Déjà caché — garder tel quel |
| Gestion des rôles (`/roles`) | ROLE_MANAGE | Owner uniquement, rarement nécessaire pendant le pilote |
| Registre documents (`/projects/:id/register`) | DOCUMENT_READ | Utile mais complexe — cacher jusqu'à ce que les utilisateurs le demandent |
| Dialogue d'accès dossiers | PROJECT_ASSIGN | Matrice de permissions complexe — configurer une fois, cacher pendant le pilote |

### REORDONNER la sidebar (recommandé pour le pilote)

```
Accueil
Mes validations          (was "Mes tâches")
Trésorerie               (merged — hub + finance + caisses)
Chantiers
Matériel
Signalements             (was "Tâches / Signalements")
Utilisateurs             (owner only)
Journal                  (owner only)
```

---

## 5. Exact routes + menu labels today

### Sidebar menu labels (as they appear today)

| # | Menu label (FR) | Route | Permission |
|---|-----------------|-------|------------|
| 1 | Accueil | `/` | (all) |
| 2 | Mes tâches | `/mes-taches` | (all) |
| 3 | Trésorerie | `/tresorerie` | CASH_READ |
| 4 | Espace Finance | `/tresorerie/finance` | CASH_READ |
| 5 | Processus | `/processus` | (all) |
| 6 | Chantiers | `/projects` | PROJECT_READ |
| 7 | Matériel | `/stock` | STOCK_VIEW |
| 8 | Commandes | `/stock/commandes` | STOCK_VIEW |
| 9 | Réceptions | `/stock/receptions` | STOCK_VIEW |
| 10 | Rapprochement | `/stock/rapprochement` | STOCK_VIEW |
| 11 | Factures | `/stock/factures` | STOCK_VIEW |
| 12 | Tâches / Signalements | `/tickets` | TICKET_READ |
| 13 | Utilisateurs | `/users` | USER_CREATE |
| 14 | Rôles | `/roles` | ROLE_MANAGE |
| 15 | Journal | `/audit` | AUDIT_VIEW |

### Full route list (47 routes)

| Category | Routes |
|----------|--------|
| **Auth** | `/login` |
| **Home** | `/` |
| **Tickets** | `/tickets`, `/tickets/:id` |
| **Projects** | `/projects`, `/projects/:id`, `/projects/:projectId/folders/:folderId`, `/projects/:id/register` |
| **Documents** | `/documents/:id` |
| **Users** | `/users`, `/roles`, `/roles/:id` |
| **Audit** | `/audit` |
| **Process** | `/processus`, `/mes-taches`, `/processes/start`, `/processes/builder`, `/processes/builder/:id`, `/processes/:id`, `/processus/etapes/:stepInstanceId/validation` |
| **Trésorerie** | `/tresorerie`, `/tresorerie/finance`, `/tresorerie/caisses`, `/tresorerie/caisses/:potId`, `/tresorerie/operations/new`, `/tresorerie/operations/:id`, `/tresorerie/operations/:id/edit`, `/tresorerie/parties`, `/tresorerie/parties/:id` |
| **Stock / Achats** | `/stock`, `/stock/stocks`, `/stock/mouvements`, `/stock/mouvements/new`, `/stock/articles`, `/stock/emplacements`, `/stock/commandes`, `/stock/commandes/new`, `/stock/commandes/:id`, `/stock/receptions`, `/stock/receptions/new`, `/stock/receptions/:id`, `/stock/rapprochement`, `/stock/factures`, `/stock/factures/new`, `/stock/factures/:id` |

### Demo users for pilot testing

| User | Role | Can do | Login |
|------|------|--------|-------|
| `owner@hydra.tn` | OWNER | Everything | `<OWNER_PASSWORD>` |
| `safa.mtibaa@hydra.tn` | FINANCE | See stock + match, submit/validate payments, see trésorerie | `<DEMO_PASSWORD>` |
| `karim.bouzid@hydra.tn` | CHEF_PROJET | Create BC/BL on P9, submit payments, see stock | `<DEMO_PASSWORD>` |
| `amel.riahi@hydra.tn` | BUREAU | Read-only stock + tickets, see trésorerie | `<DEMO_PASSWORD>` |
| `houssem.brahmi@hydra.tn` | ACHAT | Stock view + move, no project membership (IDOR test) | `<DEMO_PASSWORD>` |

---

## 6. Summary

HYDRA today is a **4-module SaaS** (Tickets + Process + Cash + Stock/Achats) wrapped around a **project-based access model** with 14 roles and 28 permissions. Parts 1-4 delivered the full procurement chain (BC→BL→stock IN→Facture→Match→Payment warnings→BC close).

**For pilot**: focus on the **daily spine** (Accueil → Mes validations → Rapprochement → Stock → Trésorerie → Signalements). Hide the Process builder, merge Trésorerie entries, and rename "Mes tâches" to "Mes validations" to distinguish from "Signalements".

**Key risk**: the "two inboxes" confusion (Mes tâches vs Tâches/Signalements) is the #1 UX issue for an Excel user. A rename + visual separation will fix it.
