# Test — Contrôle de trésorerie (P8)

Cochez « OK » ou « KO » après chaque étape. Connectez-vous comme **Gérant Démo**
(mot de passe = `HYDRA_DEMO_PASSWORD`). Le fichier `docs/sample.json` est un
FAKE fichier (noms et montants fictifs) produit par `docs/convert.py`.

## Préparation

| # | Action | Résultat attendu | OK / KO |
|---|--------|------------------|---------|
| 1 | Ouvrir **Contrôle caisse** depuis le menu | La page affiche « Aucun fichier importé » avec un bouton « Importer le fichier ». | |
| 2 | Cliquer « Importer le fichier » → onglet **Importer** | La page affiche les étapes et le bouton « Choisir le fichier JSON ». | |
| 3 | Choisir `docs/sample.json` → cliquer « Importer » | Boîte de dialogue « Remplacer les lignes actuelles par ce fichier ? ». Cliquer « Oui, importer ». | |
| 4 | Vérifier le résultat | « Import réussi » avec 5 lignes de caisse, 3 lignes sous-traitants, 3 comptes. | |
| 5 | Vérifier l'historique | Une ligne dans l'historique : date du jour, fichier « exemple-fictif.xlsx », par « Gérant Démo ». | |

## Vue d'ensemble

| # | Action | Résultat attendu | OK / KO |
|---|--------|------------------|---------|
| 6 | Ouvrir **Contrôle caisse** → onglet **Vue d'ensemble** | La page affiche « Où est l'argent de Hajeb, Hammed et Ouled Khalfallah » avec 0 lignes (le projet Alpha n'est pas du groupement). | |
| 7 | Basculer le sélecteur en haut à droite sur « Tous les projets » | La page se recalcule : « Où est l'argent de tous les projets ». | |
| 8 | Vérifier les KPI | Entrées **52 400,000** (50 000 + 2 400 mis par le partenaire), Sorties **8 900,000** (6 500 + 2 400 ; le transfert de 1 000 n'est pas compté), Lignes **5**, Sorties justifiées **—**. | |
| 9 | Vérifier « Qui a payé quoi, projet par projet » | Le tableau montre le projet Alpha avec une colonne « Total sorties » à 8 900,000. | |
| 10 | Vérifier « À vérifier en priorité » | Une ligne « Espèces > 5 000 TND » (1 ligne), une ligne « Sans date » (1 ligne), et « Paiement non retrouvé » (1 ligne). | |
| 11 | Vérifier « Justificatifs par compte » | Une barre empilée pour UIB TEST et une pour Caisse Partenaire Alpha, toutes au 0 % justifié. | |

## Comptes

| # | Action | Résultat attendu | OK / KO |
|---|--------|------------------|---------|
| 12 | Onglet **Comptes** | Une carte « Caisse Central (vue réunie) » en haut, puis une section « Banques » avec UIB TEST, une section « Caisses chantier » avec Caisse chantier Alpha, une section « Caisses partenaires » avec Caisse Partenaire Alpha. | |
| 13 | Cliquer sur « UIB TEST » | Page du compte : 4 KPIs (Entrées 50 000,000, Sorties 7 500,000, Solde 42 500,000, Lignes 3), « Par projet » (Alpha), « Sorties par catégorie », puis le tableau des lignes (3 lignes). | |
| 14 | Cliquer sur « Caisse Central » | Page de la vue réunie : le tableau des lignes montre les colonnes « Compte » (visible car c'est la vue réunie). | |

## Lignes et justificatif

| # | Action | Résultat attendu | OK / KO |
|---|--------|------------------|---------|
| 15 | Dans le tableau, cliquer sur la ligne `t0000000003` (Avance ST Test, 6 500,000) | Le tiroir s'ouvre à droite : détails de la ligne, statut « Non vérifié ». | |
| 16 | Dans le tiroir, cliquer « Justifié », Type = Facture, Référence = F-01, Montant = 6 500,000 | Le message « Le document couvre exactement la ligne. » apparaît. | |
| 17 | Cliquer « Enregistrer » | Toast « Justificatif enregistré. », le tiroir se ferme. La ligne `t0000000003` affiche maintenant un pill vert « Justifié ». | |
| 18 | Vérifier sur la page Vue d'ensemble | Le KPI « Sorties justifiées » affiche maintenant un pourcentage, et la barre de UIB TEST est partiellement verte. | |
| 19 | Re-importer `docs/sample.json` | L'import réussit. Le justificatif de `t0000000003` est toujours là (pill vert « Justifié »). | |
| 20 | Vérifier le résultat de l'import | « 0 justificatif(s) ne correspondent plus à aucune ligne » (la ligne t0000000003 existe toujours). | |

## Sous-traitants

| # | Action | Résultat attendu | OK / KO |
|---|--------|------------------|---------|
| 21 | Onglet **Sous-traitants** | Le tableau affiche « Sous-traitant Test » avec Travaux **8 000,000**, Payé **7 000,000**, Solde **1 000,000** (vert), 0 hors compte, 1 paiement non retrouvé, pill « Pas encore vu ». | |
| 22 | Cliquer sur « Sous-traitant Test » | La fiche s'ouvre : 4 KPIs (Travaux 8 000,000, Payé 7 000,000, Solde 1 000,000 « SIGAT lui doit encore 1 000,000 TND. », Payé hors compte 0). | |
| 23 | Vérifier la feuille « ST Test Alpha » | Big solde 1 000,000. Table « Travaux (1) » avec une ligne à 8 000,000. Table « Paiements (2) » : une ligne à 6 500,000 (Payé depuis : chip « UIB TEST ») et une ligne à 500,000 (chip rouge « Non retrouvé »). | |
| 24 | Cliquer sur la ligne de paiement à 500,000 (Non retrouvé) | Le tiroir s'ouvre sur cette ligne sous-traitant. | |
| 25 | Cliquer sur la ligne de paiement à 6 500,000 | Le tiroir s'ouvre sur la ligne `t0000000003` (qui est déjà justifiée). | |
| 26 | Dans le bloc « Validation du compte Alpha », cliquer « Validé avec lui », solde = 1 000,000, « Enregistrer la validation » | Toast « Validation enregistrée. ». Le pill dans la liste des sous-traitants passe à « Validé avec lui ». | |
| 27 | Cliquer « Version imprimable » | La boîte de dialogue d'impression du navigateur s'ouvre. Le menu, les onglets et les boutons sont masqués dans l'aperçu. | |

## Justificatifs

| # | Action | Résultat attendu | OK / KO |
|---|--------|------------------|---------|
| 28 | Onglet **Justificatifs** | KPI « Sorties justifiées » avec un pourcentage, KPI « Alpha » avec un pourcentage. Le tableau affiche les sorties triées par montant décroissant (6 500,000 en premier), filtre par défaut « Non vérifié ». | |
| 29 | Depuis la Vue d'ensemble, cliquer sur « Espèces > 5 000 TND » | Navigation vers Justificatifs avec `?flag=especes`. Le filtre « alerte » est sur « Espèces > 5 000 TND », le filtre « justificatif » est vide. | |

## Réunion NGNET

| # | Action | Résultat attendu | OK / KO |
|---|--------|------------------|---------|
| 30 | Onglet **Réunion NGNET** | « Aucun projet du groupement NGNET (Hajeb, Hammed, Ouled Khalfallah) dans les données importées. » (le sample ne contient que le projet Alpha). | |

## Import — erreurs

| # | Action | Résultat attendu | OK / KO |
|---|--------|------------------|---------|
| 31 | Choisir un fichier qui n'est pas du JSON (ex. un fichier .txt) → Importer | « Échec de l'import » avec le message « Le fichier n'est pas un export valide de convert.py. ». | |
| 32 | Choisir le fichier JSON puis modifier `meta.lines` à 99 → Importer | « Échec de l'import » avec la liste des problèmes, dont un mentionnant « meta.lines ». | |

## Permissions

| # | Action | Résultat attendu | OK / KO |
|---|--------|------------------|---------|
| 33 | Se connecter comme un utilisateur avec seulement `CTL_READ` (sans `CTL_JUSTIFY`) | Le menu « Contrôle caisse » est visible. L'onglet « Importer » est absent. Les pages sont lisibles. | |
| 34 | Ouvrir une ligne dans le tiroir | Tous les champs sont en lecture seule, le bouton « Enregistrer » est désactivé, un bandeau bleu « Lecture seule : votre accès ne permet pas de modifier. » | |
| 35 | Se connecter comme un utilisateur sans `CTL_READ` | Le menu « Contrôle caisse » est absent. Taper `/controle` → refusé (guard). | |

## Conflit (deux navigateurs)

| # | Action | Résultat attendu | OK / KO |
|---|--------|------------------|---------|
| 36 | Ouvrir le tiroir sur la ligne `t0000000003` dans deux navigateurs. Modifier le justificatif dans le premier, enregistrer. Modifier dans le deuxième, enregistrer. | Le deuxième navigateur affiche le bandeau « Quelqu'un d'autre a modifié ce justificatif. » avec un bouton « Recharger ». | |

## Responsive

| # | Action | Résultat attendu | OK / KO |
|---|--------|------------------|---------|
| 37 | Réduire la fenêtre à 400 px de large | Aucun scroll horizontal sur la page. Les tableaux larges scrollent dans leur propre boîte. Les cartes vont une par ligne. | |
