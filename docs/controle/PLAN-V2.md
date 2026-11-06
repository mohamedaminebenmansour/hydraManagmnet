# HYDRA — Contrôle v2 : plan pour Claude Code

Nov 6, 2026 · @Work Shopify

## Résumé

L'import échoue parce que la page n'accepte que le fichier `.json` produit par convert.py, pas l'Excel. Pour lundi, on importe le fichier `SIGAT_import.json` déjà prêt. Ensuite Claude Code construit la version 2, où le gérant charge directement son Excel.

**Lundi 9 novembre (sans nouveau code)**

1. Importer `SIGAT_import.json` dans HYDRA → Contrôle → Import. Il contient 8 520 lignes de caisse et 1 870 lignes sous-traitants, avec les numéros de facture et de bon des caisses NGNET (ils manquaient avant).
2. Utiliser le classeur `Controle_SIGAT_articles.xlsx` pour la réunion : problèmes, projets, articles, comptes, sous-traitants 90/10, et chaque ligne avec sa feuille et son numéro de ligne Excel.
3. Garder la page prototype comme secours.

**Version 2 (Claude Code, après lundi ou ce week-end si le temps le permet)**

- Le gérant charge son Excel (`.xlsx`) directement, sans Python.
- On voit l'argent par compte, par caisse, par projet, par article et par sous-traitant, avec chaque ligne, sa pièce (facture, bon ou rien) et son mode de paiement.
- Chaque tableau a des filtres, une pagination, un total des lignes filtrées et un bouton « Exporter Excel ».
- Une page « Anomalies » liste tout ce qui n'est pas logique, avec un export Excel.
- Une page sous-traitants montre les décomptes, la retenue de garantie (10 % ou 5 %), le payé et le solde.

Le module actuel (V55, P0 à P8, F1 à F3) reste la base : on l'étend, on ne le refait pas.

## Ce que les vraies données montrent

Les totaux de chaque feuille correspondent à la somme des lignes (0 écart sur 37 feuilles). Les problèmes sont donc dans le contenu des lignes, pas dans les formules.

| Gravité | Problème | Lignes | Montant (TND) |
| --- | --- | --- | --- |
| Critique | Espèces > 5 000 TND | 52 | 536 779,697 |
| Critique | Sorties sans facture ni bon (3 projets) | 3 390 | 1 173 638,965 |
| Critique | Sous-traitant payé plus que son crédit | 13 comptes | 187 242,038 |
| Critique | Retenue de garantie mal calculée | 2 comptes | 128 230,995 |
| Important | Retenue de garantie encore due aux ST | 10 comptes | 132 337,946 |
| Important | Mode de paiement vide (banques et caisses NGNET) | 258 | 2 797 561,404 |
| Important | Paiement à un ST absent de son compte ST | 85 | 453 981,662 |
| Important | Paiement du compte ST introuvable dans les caisses | 109 | 297 756,333 |
| Important | Caisse qui passe en négatif | 10 comptes | — |
| Important | Doublons possibles | 50 | 29 569,766 |
| À vérifier | Avance sur bénéfice | 9 | 285 500,000 |
| À vérifier | Lignes sans date | 774 | 1 593 515,728 |
| À vérifier | Dates impossibles | 22 | 6 226,470 |

**Trois points pour la réunion de lundi :**

- **Hamza Msalmi (Hajeb)** : payé 22 076,499 TND de plus que son crédit. Sa ligne « 10% Retenue de garantie » (46 679,869) vaut exactement 10 % des décomptes listés. Si ces décomptes sont bruts, la retenue est comptée deux fois. Il faut le décompte papier.
- **Abd El Moula (Ouled Khalfallah)** : 81 551,126 TND de retenue créditée, alors que 10 % du brut donne 41 051,126. La ligne « Décompte Provisoire N°5 (10%) » de 40 500 n'a pas de décompte N°5.
- **Équipe MO Ouled Khalfallah** : 116 246,521 TND payés sans aucun attachement saisi.

**Chiffres de contrôle** (ils doivent être identiques dans HYDRA après import) :

| Où | Attendu (TND) |
| --- | --- |
| Caisse Central, entrées | 7 408 202,966 |
| Caisse Central, sorties | 7 077 685,420 |
| Caisse NGNET Hajeb, sorties | 3 288 118,354 |
| Hamza Msalmi Hajeb, solde | −22 076,499 |
| Ahmed Houimli Hajeb, solde | 51 054,998 |

UIB SIGAT descend jusqu'à −133 760,610 TND le 11/07/2026 dans l'ordre de l'Excel. Soit une alimentation manque, soit il y a un découvert : à comparer avec le relevé.

**Qui a payé chaque projet** (sorties hors transferts, TND). Les caisses NGNET portent 4 655 423,558 des 7 429 146,246 dépensés sur les trois projets.

| Compte | Hajeb | Hammed | Ouled Khalfallah |
| --- | --- | --- | --- |
| UIB SIGAT | 310 014,327 | 105 785,206 | 141 547,227 |
| UIB OMAR | 226 872,934 | 106 765,477 | 188 630,619 |
| UIB NGNET | 793 688,257 | 33 794,256 | 514 617,874 |
| Caisse SIGAT | 42 446,939 | 4 051,808 | 28 166,000 |
| Caisse chantier | 93 525,614 | 51 543,850 | 132 272,300 |
| Caisse NGNET | 3 288 118,354 | 689 946,454 | 677 358,750 |
| **Total** | **4 754 666,425** | **991 887,051** | **1 682 592,770** |

Pour Hammed, la ligne « Caisse chantier » inclut 360,000 payés par la caisse chantier d'Ouled Khalfallah.

## Règles métier

Ces règles sont déjà appliquées dans le classeur (script `analyse.py`). L'application doit donner exactement les mêmes chiffres.

**Comptes et flux** (comme sur le dessin du gérant)

- Trois banques : UIB SIGAT, UIB NGNET, UIB OMAR. « Omar » est un compte de l'entreprise, pas une erreur. La Caisse SIGAT est la caisse espèces du bureau.
- « Caisse Central » est la feuille qui regroupe ces quatre comptes. Ce n'est pas un compte en plus.
- Chaque projet du groupement (Hajeb, Hammed, Ouled Khalfallah) a une caisse chantier et une caisse NGNET.
- Une alimentation de caisse chantier est un **transfert** : elle ne compte pas comme dépense. C'est la dépense faite ensuite dans la caisse chantier qui compte.

**Article** = le poste du marché où va l'argent. Il vient de la colonne « Sous-traitant / Chef Projet » ou « Destination » de l'Excel, nettoyée :

| Texte dans l'Excel | Article |
| --- | --- |
| RSE 150 D, RSE150, RSE 150 | RSE 150 |
| RSE 100 M, RSE100 | RSE 100 |
| RSE100 Existant, RES Existant | Réservoir existant |
| MO RSE 100, MO RSE 150, MO RSE 500, MO RSP | même nom (main-d'œuvre) |
| Charge sur marché, Charges sur marché, Charge sur NGNET | Charge sur marché |
| Conduite en PEHD / Conduite en Fonte | Conduite en PEHD / Conduite en fonte |
| Piéces spéciales / piéces des niches, Niches | Pièces spéciales / Pièces des niches |
| Abri forage, ABRI FORAGE, Abris | Abri forage |
| Station de Surpresseur, Surpresseur | Station de surpresseur |
| ST Hamza Msalmi, st hamza Msalmi, sous trataint hamza | ST Hamza Msalmi (un par sous-traitant) |
| caisse chantier | Alimentation caisse chantier (transfert) |
| Encaissement | Encaissement (décomptes) |
| Hosni Hamzaoui, Mohamed Ali Laabar | Alimentation (personne) |
| BC, RSE, MO RSE, vide, NGNET, SIGAT | À préciser (le gérant choisit) |

Dans l'application, ce tableau est une table modifiable (texte Excel → article). Le gérant corrige un regroupement sans réimporter.

**Pièce** = « Facture » si le N° de facture contient un chiffre, « Bon » si le N° de bon contient un chiffre, « Mention sans numéro » pour un texte comme « O,B », et « Aucune pièce » pour vide, « - » ou « S ».

**Espèces** : un paiement de strictement plus de 5 000 TND en espèces coûte une amende de 20 % au payeur, minimum 2 000 TND. Dans une caisse chantier, un mode vide veut dire espèces. Le mode « 350 » (12 lignes Hani Barkia) est une faute de frappe.

**Retenue de garantie (le 90 / 10)**

Côté client, le maître d'ouvrage garde 10 % de chaque paiement et les rend environ quatre mois après la réception définitive. Le cautionnement définitif est de 3 % du marché ([exemple de CCAP tunisien, articles 06 et 16](https://igppp.tn/sites/default/files/ERP%20IGPPP%2001-2024_6.pdf)).

Côté sous-traitant, SIGAT fait la même chose : elle paie 90 % de chaque décompte et garde 10 % jusqu'à la réception. Certains contrats sont à 5 %, d'autres « sans retenue ».

Chaque ligne de crédit d'un compte ST reçoit un type :

| Type | Exemples dans l'Excel |
| --- | --- |
| Décompte / attachement | Décompte Provisoire N°2, Attachament provisoire N°1, Niches Réalisé |
| Retenue de garantie créditée | 10% Retenue de garentie, Total retenue de garantie (10%), Décompte N°1 (10%), Retenue décompte 5% |
| Attachement sans retenue | Attachement N°3 (sans retenue) |
| TVA et factures | Reste TVA, TVA, Facture Ahmed 19% |
| Paiement MO | Paiement MO RSE 500 (le crédit suit le paiement) |
| Autre | avoir, relèvement du crédit ancien |

Contrôle de la retenue : quand un décompte est noté net (90 %), la retenue de 10 % du brut vaut décompte ÷ 9, soit 11,11 % du net. Pour 5 %, c'est 5/95, soit 5,26 %. Un autre taux est une anomalie.

Quand aucune ligne de retenue n'existe, SIGAT garde encore la retenue. Elle sera due au ST à la réception, et l'application l'affiche comme « Retenue à payer ».

Le taux de retenue est un champ de chaque compte ST (10, 5 ou 0 %), à confirmer par le gérant contrat par contrat.

## Écrans

Chaque écran répond à une seule question du gérant. Un clic sur un chiffre ouvre toujours les lignes qui le composent.

**Navigation** (menu à gauche, 8 entrées)

| Écran | Question du gérant | Contenu |
| --- | --- | --- |
| Où va l'argent | D'où vient l'argent et où part-il ? | Le schéma du dessin : banques → 3 projets → caisses chantier et caisses NGNET → articles, avec le montant sur chaque flèche. En haut : entrées, sorties, sans pièce, espèces > 5 000, à valider. |
| Comptes et caisses | Combien sur chaque compte ? | Un bloc par compte : entrées, sorties, transferts, solde calculé, solde le plus bas et sa date, solde réel saisi (relevé ou comptage) et écart. Clic → toutes les lignes avec le solde ligne par ligne. |
| Projets | Qu'a coûté Hajeb, et qui a payé ? | Tableau par compte source (UIB SIGAT, UIB NGNET, UIB OMAR, caisses). Puis le tableau des articles : entrées, sorties, espèces, sorties sans pièce, % justifié. Puis les décomptes encaissés. |
| Articles | Combien pour les RSE 100 sur tous les projets ? | Une matrice article × projet. Clic sur une case → ses lignes. |
| Sous-traitants | Que dois-je à Hamza, ou que me doit-il ? | Une fiche par personne (tous projets) : décomptes, retenue créditée ou à payer, autres crédits, payé, solde, payé en espèces, paiements introuvables, validation. Bouton « Fiche réunion » (imprimable) et « Exporter Excel ». |
| NGNET | Qu'a payé NGNET sur chaque projet ? | Par projet : caisse NGNET et UIB NGNET par article, total du groupement, validation. |
| Anomalies | Qu'est-ce qui n'est pas logique ? | Les mêmes problèmes que le classeur, avec le nombre et le montant. Clic → les lignes filtrées. « Exporter le classeur des anomalies ». |
| Justificatifs | Que reste-t-il à justifier ? | La file des lignes sans pièce, la plus grosse en premier. |

L'import se fait depuis un bouton dans le menu du haut, pas depuis une entrée du menu.

**Le tableau de lignes** (un seul composant, utilisé partout)

- Colonnes : date, compte, projet, article, désignation, bénéficiaire, paiement, bon, facture, pièce, entrée, sortie, alertes, statut du justificatif. L'utilisateur peut masquer des colonnes.
- Filtres en haut : recherche libre, période, projet, compte, article, sous-traitant, mode de paiement, pièce, alerte, statut. Chaque filtre actif est une puce qu'on retire d'un clic. Les filtres restent dans l'adresse de la page.
- Pagination : 50 lignes par page (25, 50, 100 ou 200), avec le numéro de page et « 1 à 50 sur 2 761 ».
- Une ligne de total fixe en bas : nombre de lignes, entrées et sorties de **toutes les lignes filtrées**, pas seulement de la page.
- Tri par clic sur l'en-tête.
- « Exporter Excel » exporte toutes les lignes filtrées, avec ces colonnes, la feuille et le numéro de ligne Excel d'origine, et une ligne de total.
- Clic sur une ligne → le volet de détail existant (justificatif, ligne liée dans le compte ST).

**Style**

- Montants alignés à droite, 3 décimales, chiffres à chasse fixe, sorties en couleur sombre et pas en rouge. Le rouge est réservé aux alertes.
- Trois couleurs d'alerte seulement : critique (rouge), important (orange), à vérifier (bleu). Mêmes couleurs dans l'application et dans les exports.
- Pas de graphique décoratif. Le seul schéma est celui de « Où va l'argent ».
- Fonctionne sur tablette, car le gérant sera en réunion.

## Travailler avec Claude Code dans VS Code

Le dépôt a déjà un `CLAUDE.md` et des règles (`docs/AI-RULES.md`), et `.claude/settings.json` bloque déjà les commandes dangereuses. Il reste trois choses à faire avant la première phase.

1. Merger `feature/controle-tresorerie` (CI verte sur `4465076`).
2. Copier dans le dépôt, sur la branche de la phase 0 : `tools/controle/convert.py` (la version corrigée envoyée aujourd'hui), `tools/controle/analyse.py` (le script du classeur), et ce plan dans `docs/controle/PLAN-V2.md`.
3. Ne jamais mettre dans le dépôt le vrai Excel, `SIGAT_import.json` ou le classeur des anomalies. Ce sont des données réelles de l'entreprise. Ajouter `*.xlsx` et `SIGAT_import*.json` au `.gitignore` en phase 0.

**Remplacer la section « Your role » du CLAUDE.md par :**

```markdown
## Your role
Claude Code writes the code for the module « Contrôle de trésorerie v2 ».
The plan is docs/controle/PLAN-V2.md. Do ONE phase per session, in order.
At the start of a phase: read the phase in the plan, then use plan mode and show
the plan (files, steps, tests) before writing code.
Business rules: tools/controle/analyse.py is the reference. The app must give the
same numbers as that script on the same file.
The human merges. Claude never merges and never pushes to main.

## Contrôle module rules
- Package tn.hydra.controle, route /controle, API /api/controle, tables ctl_*.
- Never touch cash_pot, cash_movement or the /tresorerie route.
- Line ids must stay identical to convert.py (sha1 of sheet|date|e|d|designation).
  Justifications and validations are keyed by these ids.
- Never commit a real .xlsx or a real import .json. Tests build their own small
  Excel file in code.
- Every table: filters, pagination, total of filtered rows, « Exporter Excel ».
```

**Méthode pour chaque phase**

1. Ouvrir une nouvelle session (`/clear`).
2. Coller le prompt de la phase (section suivante).
3. Claude Code montre son plan : vérifier les fichiers et les tests avant de dire oui.
4. À la fin, il colle les 20 dernières lignes de `./mvnw test` et de `npm run build`. Si le PC n'a pas Java ou Node, attendre la CI de la branche.
5. Une branche par phase, un commit par étape. Merger quand la CI est verte, puis passer à la phase suivante depuis `main`.

## Phases et prompts

Sept phases, une par session et une branche chacune. Seule la phase 0 est nécessaire avant lundi ; la phase 3 l'est si le temps le permet.

| Phase | Branche | Ce que le gérant gagne | Taille |
| --- | --- | --- | --- |
| 0 | chore/controle-v2-setup | Rien de visible : fichiers de référence et règles | 30 min |
| 1 | feature/controle-xlsx-import | Il charge son Excel directement | 1 session longue |
| 2 | feature/controle-articles | Chaque ligne a son article et sa pièce | 1 session |
| 3 | feature/controle-table-v2 | Filtres, pagination, totaux, export Excel partout | 1 session |
| 4 | feature/controle-pages-v2 | Où va l'argent, Projets, Articles | 1 session |
| 5 | feature/controle-st-retenue | Sous-traitants avec retenue 90/10 et fiche réunion | 1 session |
| 6 | feature/controle-anomalies | Page Anomalies, export du classeur, soldes réels | 1 session |

### Phase 0 — préparation

```text
Tâche : chore/controle-v2-setup. Lis CLAUDE.md et docs/AI-RULES.md.
1. git switch main, git pull, git switch -c chore/controle-v2-setup.
2. git mv docs/prototype.html docs/controle/prototype.html ;
   git mv docs/sample.json docs/controle/sample.json ;
   git mv docs/convert.py tools/controle/convert.py.
   Mets à jour docs/TEST-CONTROLE.md et docs/MAP.md avec les nouveaux chemins.
3. Remplace tools/controle/convert.py par le fichier que je te donne (version corrigée :
   les caisses chantier et NGNET lisent maintenant Bon N°, Facture N° et Observation).
4. Ajoute tools/controle/analyse.py (je te le donne) et docs/controle/PLAN-V2.md (ce plan).
5. .gitignore : ajoute *.xlsx, SIGAT_import*.json, Controle_SIGAT*.xlsx
   (sauf backend/src/test/resources/**).
6. Remplace la section « Your role » du CLAUDE.md par le texte du plan.
7. Un commit : chore(controle): v2 setup, reference scripts and plan.
Pas de code Java ni Angular dans cette phase.
```

### Phase 1 — import direct de l'Excel

```text
Tâche : feature/controle-xlsx-import. Lis docs/controle/PLAN-V2.md (Phase 1) et
tools/controle/convert.py en entier : c'est la référence, à porter en Java à l'identique.

But : POST /api/controle/import accepte un .xlsx (le fichier « Caisse des projets »)
en plus du .json. Le résultat en base doit être identique à convert.py + import JSON.

1. Nouveau ControleExcelReader (Apache POI, déjà dans le pom) qui produit un
   ControleImportFile.ImportFile, exactement comme convert.py :
   mêmes feuilles, mêmes colonnes, mêmes règles (mode_of, proj_of, person_of,
   poste_out, nature_in, INTERNAL, SITE, PARTNER, ST_SHEETS, flags, rapprochement ST
   à ±10 jours).
   - Valeurs : lire la valeur calculée des cellules formule (getCachedFormulaResultType),
     jamais la formule.
   - Id : sha1 de "sheet|date|e|d|designation en minuscules", 10 premiers hex, suffixe
     -1, -2 pour les doublons dans l'ordre de lecture.
     Montants formatés comme Python f"{x:.3f}" : new BigDecimal(double).setScale(3,
     RoundingMode.HALF_EVEN).toPlainString(). Minuscules : toLowerCase(Locale.ROOT).
   - txt() : même nettoyage que Python (strip, espaces). Recopie la fonction, ne la
     réinvente pas.
2. ControleImportController : si le nom finit par .xlsx → reader, sinon JSON comme avant.
   Limite 20 Mo inchangée. Erreur IMPORT_FORMAT si une feuille attendue manque, avec
   son nom dans le message.
3. Frontend import : accept=".xlsx,.json", texte « Choisissez le fichier Excel
   Caisse des projets (.xlsx) ».
4. Tests (nouveaux seulement) : ControleExcelReaderTest construit en code, avec POI,
   un petit classeur (Caisse Central 6 lignes, une caisse chantier, une caisse NGNET
   avec Bon N° et Facture N°, un compte ST) et vérifie : nombre de lignes, ids
   attendus (calculés à la main dans le test avec la formule sha1), bon et facture lus,
   transferts, flags especes (> 5 000 strict), lien ST.
   CX-01..CX-08. Ne mets jamais le vrai fichier dans le dépôt.
5. Vérification locale (pas dans la CI) : un main de test ou une commande
   ./mvnw -q exec qui lit un .xlsx et écrit le JSON, pour que je compare avec
   python3 tools/controle/convert.py sur ma machine : mêmes ids, mêmes montants.
Résultat attendu : importer le vrai Excel en production donne 8 520 lignes,
1 870 lignes ST, et les chiffres de contrôle du plan.
```

### Phase 2 — articles et pièces

```text
Tâche : feature/controle-articles. Lis PLAN-V2.md (Règles métier, Phase 2) et
les fonctions article() et piece() de tools/controle/analyse.py.

1. Migration V56 (numéro suivant) :
   - ctl_line : ajoute article text not null default '', piece text not null default ''.
   - ctl_article_alias(org_id uuid not null, raw_key text not null, article text not null,
     version int not null default 1, updated_by uuid, updated_at timestamptz,
     primary key(org_id, raw_key)) + RLS.
2. À l'import : piece = règle de piece(). article = alias de l'org pour
   raw_key = texte nettoyé (minuscules, sans accents, espaces simples) de la colonne cat,
   sinon règle de article(). Même résultat que analyse.py.
3. API : GET /api/controle/articles (liste article, raw_key, nb lignes) ;
   PUT /api/controle/articles/aliases/{rawKey} {article, version} avec PERM_CTL_JUSTIFY,
   règle de version comme les justificatifs (0 = nouveau, 409 STALE), et dans la même
   transaction : update ctl_line set article = ? where org_id = ? and raw_key = ?.
   Audit CONTROLE_ARTICLE_CHANGED.
4. GET /api/controle/data renvoie article et piece sur chaque ligne ; ajoute les
   champs dans controle.service.ts.
5. Frontend : un petit écran « Regroupement des articles » (texte Excel → article,
   nb lignes, liste déroulante ou texte libre, enregistrer).
6. Tests : CA-01 règles (RSE 150 D → RSE 150, st hamza Msalmi → ST Hamza Msalmi,
   caisse chantier → Alimentation caisse chantier, BC → BC (à préciser)) ; CA-02 pièce
   (N°53859 → Facture, S → Aucune pièce, O,B → Mention sans numéro) ; CA-03 un alias
   change l'article de toutes ses lignes ; CA-04 version périmée → 409 ; CA-05 org B
   ne voit pas les alias de org A.
```

### Phase 3 — le tableau de lignes v2

```text
Tâche : feature/controle-table-v2. Frontend seulement. Lis PLAN-V2.md (Écrans,
« Le tableau de lignes ») et features/controle/lines-table.component.ts.

1. lines-table : filtres (recherche, période, projet, compte, article, sous-traitant,
   mode, pièce, alerte, statut) en puces retirables ; état dans les query params.
2. Pagination 25/50/100/200 (50 par défaut), « 1 à 50 sur N », tri par colonne,
   colonnes masquables (choix gardé en localStorage, dans un try/catch).
3. Ligne de total fixe : nb, entrées, sorties de TOUTES les lignes filtrées
   (mêmes règles inAmt/outAmt que le store).
4. « Exporter Excel » : exceljs (npm i exceljs@4.4.0), chargé à la demande avec
   await import('exceljs') pour ne pas grossir le bundle. Exporte toutes les lignes
   filtrées avec feuille et ligne Excel d'origine, montants en nombres (format
   #,##0.000), une ligne de total, en-têtes figés, filtre auto. Nom :
   SIGAT_<écran>_<filtre>_<aaaa-mm-jj>.xlsx.
5. Ne change pas les budgets du build. Si le build dépasse, arrête et dis-le.
Vérification : sur Hajeb, filtre article = RSE 150 → 93 lignes, sorties 71 411,606 ;
l'export a 93 lignes + le total.
```

### Phase 4 — Où va l'argent, Projets, Articles

```text
Tâche : feature/controle-pages-v2. Frontend seulement, calculs dans controle.store.ts.
1. « Où va l'argent » (accueil) : schéma en SVG simple, 4 colonnes : banques
   (UIB SIGAT, UIB NGNET, UIB OMAR, Caisse SIGAT) → projets (Hajeb, Hammed, Ouled
   Khalfallah, autres) → caisses (chantier, NGNET) → 10 premiers articles du projet
   choisi. Montant sur chaque flèche ; clic → tableau filtré. 5 chiffres en haut :
   entrées, sorties, sorties sans pièce, espèces > 5 000, comptes ST à valider.
2. Projet : tableau par compte source (lignes, entrées, sorties, transferts,
   sans pièce), tableau des articles (lignes, entrées, sorties, espèces, sans pièce
   nb et TND, % justifié), liste des encaissements (décomptes).
3. Articles : matrice article × projet, clic sur une case → lignes.
4. Chaque tableau a son « Exporter Excel ».
Vérification : Hajeb total sorties 4 754 666,425 ; Hammed 991 887,051 ;
Ouled Khalfallah 1 682 592,770 (mêmes chiffres que l'onglet Projets du classeur).
```

### Phase 5 — sous-traitants et retenue 90/10

```text
Tâche : feature/controle-st-retenue. Lis PLAN-V2.md (Retenue de garantie) et
credit_type() + la section 6 de tools/controle/analyse.py.
1. Migration : ctl_st_account ajoute retenue_pct numeric(5,2) not null default 10
   (valeurs 0, 5 ou 10) ; ctl_st_line ajoute credit_type text not null default ''.
   L'import remplit credit_type ; retenue_pct est gardé d'un import à l'autre
   (table séparée ctl_st_setting(org_id, st_account_id, retenue_pct, version) si besoin,
   car l'import efface ctl_st_account).
2. PUT /api/controle/st-accounts/{id}/retenue {pct, version}, PERM_CTL_JUSTIFY,
   règle de version, audit.
3. Fiche sous-traitant (par personne, tous projets) : par compte ST, les colonnes
   de l'onglet « Sous-traitants 90-10 » du classeur, le verdict (payé plus que le
   crédit, SIGAT doit, soldé) et le contrôle de retenue (11,11 % / 5,26 % /
   à vérifier / retenue encore due). Liste des crédits par type.
4. « Fiche réunion » : page imprimable A4 (CSS print) avec le résumé, les décomptes,
   les paiements, les lignes à discuter, et deux cadres de signature.
5. Tests : CR-01 classification des crédits ; CR-02 retenue 1/9 → « déjà créditée » ;
   CR-03 retenue = 10 % des décomptes → « à vérifier » ; CR-04 pas de retenue →
   « encore due » = décomptes ÷ 9 ; CR-05 retenue_pct survit à un réimport.
Vérification : Hamza Msalmi Hajeb solde −22 076,499 et « à vérifier » ;
Haider Methneni « retenue 10 % déjà créditée (9 350,000) ».
```

### Phase 6 — anomalies et soldes réels

```text
Tâche : feature/controle-anomalies.
1. Page Anomalies : les problèmes de l'onglet « Problèmes » du classeur, calculés
   dans le store avec les mêmes règles ; gravité, nb, montant ; clic → lignes filtrées.
2. « Exporter le classeur des anomalies » : exceljs, mêmes onglets que
   Controle_SIGAT_articles.xlsx (Problèmes, Projets, Articles, Comptes,
   Sous-traitants 90-10, Sans pièce, Espèces > 5000, …).
3. Soldes réels : table ctl_account_check(org_id, account_id, check_date,
   real_balance numeric(18,3), note, version, updated_by, updated_at), écran
   Comptes : saisir le relevé ou le comptage à une date, voir l'écart avec le solde
   calculé à cette date. Cette table n'est pas effacée par l'import.
4. Tests backend pour ctl_account_check (version, org, 409).
Vérification : les nombres de la page Anomalies = ceux de l'onglet Problèmes.
```

## Remise à zéro des données

Supprimer les données d'exemple est irréversible, donc on fait une sauvegarde d'abord, un essai à blanc ensuite, et la suppression en dernier. Le module Contrôle n'en a pas besoin : chaque import remplace déjà les lignes de l'entreprise.

**Ce qu'on garde** : l'organisation de l'entreprise, le compte gérant, les rôles et permissions, l'organisation séparée « HYDRA Démo » (règle du projet).

**Ce qu'on supprime** : dans l'organisation de l'entreprise seulement, les projets, documents, tickets, mouvements de caisse, stock et autres utilisateurs créés pour tester.

1. Sauvegarde : Supabase → Database → Backups (ou `pg_dump` depuis le PC). Vérifier que le fichier existe avant d'aller plus loin.
2. Donner ce prompt à Claude Code :

```text
Tâche : chore/reset-org-data. N'exécute rien sur la base : tu écris seulement un script.
Écris tools/reset/reset_org.sql, paramétré par :org_id et :keep_user_email.
1. Liste toutes les tables avec org_id (docs/MAP.md + les migrations), dans l'ordre
   des clés étrangères (enfants d'abord).
2. Partie A « essai à blanc » : un SELECT count(*) par table pour cet org_id.
3. Partie B dans begin; … ; puis rollback; par défaut (je changerai en commit
   moi-même) : delete de chaque table pour cet org_id, sauf organization, role,
   role_permission, permission, et app_user où email = :keep_user_email.
   Garde aussi l'adresse, le logo et le profil de l'entreprise.
4. Ne touche jamais une autre organisation. Chaque delete a "where org_id = :org_id".
5. Explique en français, en tête du fichier, comment le lancer dans l'éditeur SQL
   Supabase et comment lire l'essai à blanc.
Pas de migration Flyway, pas de code Java.
```

3. Lancer la partie A, et lire les nombres avec moi avant d'aller plus loin.
4. Lancer la partie B avec `rollback`, vérifier qu'il n'y a pas d'erreur, puis la relancer avec `commit`.
5. Se connecter en gérant, importer l'Excel, vérifier les chiffres de contrôle.

## Vérification et questions pour le gérant

Une phase est finie quand ses chiffres sont égaux à ceux du classeur sur le même Excel. Après chaque phase, vérifier :

- [ ] Les chiffres de contrôle (section 2) apparaissent dans HYDRA.
- [ ] Un export Excel d'un tableau filtré a le même nombre de lignes et le même total que l'écran.
- [ ] Un justificatif saisi avant un réimport est toujours là après.
- [ ] Passer d'un compte à l'autre, d'un projet à l'autre, d'un sous-traitant à l'autre change bien le tableau.
- [ ] Sur tablette, le tableau défile sans casser la page.
- [ ] Les tests CI sont verts ; aucun ancien test n'a été modifié.

**Questions à poser au gérant (elles changent les règles)**

1. La retenue de garantie avec chaque sous-traitant : 10 %, 5 % ou aucune ? Elle est écrite dans le contrat ?
2. Hamza Msalmi Hajeb : les décomptes notés sont-ils bruts ou nets ? (46 679,869 de retenue en jeu.)
3. Abd El Moula Ouled Khalfallah : à quoi correspond « Décompte Provisoire N°5 (10%) » de 40 500 sans décompte N°5 ?
4. Équipe MO Ouled Khalfallah : où sont les attachements ou les fiches de pointage pour 116 246,521 TND ?
5. Que veut dire « BC » à Ouled Khalfallah (104 lignes, 42 944,537 TND) : béton, bon de commande, autre ?
6. « S » dans la colonne Bon (1 243 lignes) veut-il dire « sans bon » ?
7. UIB SIGAT descend à −133 760,610 en juillet 2026 : y a-t-il un découvert autorisé ?
8. Les montants des décomptes client (brut, retenue 10 %, net reçu) de chaque projet, pour comparer avec les encaissements.

Sources : [CCAP tunisien type, articles 06 et 16 (retenue 10 %, cautionnement 3 %)](https://igppp.tn/sites/default/files/ERP%20IGPPP%2001-2024_6.pdf) ; [décret n° 2014-1039 portant réglementation des marchés publics](https://legislation-securite.tn/latest-laws/decret-n-2014-1039-du-13-mars-2014-portant-reglementation-des-marches-publics/).
