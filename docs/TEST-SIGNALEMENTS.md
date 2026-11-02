# Test manual — Signalements v2 (T1–T7)

Ce document est un test pas-à-pas pour un non-développeur. Suivez chaque étape
et notez OK ou KO après chaque résultat attendu.

## Préparation
1. Deux comptes sont créés : **A** (ingénieur, membre du projet) et **B** (chef
   de chantier, membre du même projet). Les deux ont la permission TICKET_WRITE.
2. Connectez-vous avec **B** sur un téléphone (ou dev tools à 360 px).

---

## Scénario 1 — PROBLEME (B crée, A voit)

1. B : menu **Signalements** → bouton **+ Nouveau signalement**.
   → La page de création s'ouvre avec 4 tuiles (Problème / Réserve / Demande / Visa).
2. B : touchez **Problème chantier**.
   → Le formulaire apparaît (Titre, Catégorie, Photo, Chantier, Enregistrer).
3. B : titre « Tractopelle en panne au PK 2+300 », catégorie **Panne d'engin**,
   chantier du projet, photo « avant » (prenez une photo).
   → La photo est compressée (« Préparation… ») puis une vignette apparaît.
4. B : touchez **Enregistrer le signalement**.
   → « Envoi… » puis « Signalement créé ». La page de détail s'ouvre.
5. B : vérifiez le statut **Ouvert**, la photo dans **Avant**, l'échéance
   « dans 2 jours ».
6. Déconnectez B, connectez **A**. Allez dans **Signalements** → onglet **À moi**.
   → Le signalement « Tractopelle en panne » apparaît, assigné à personne
   (ou à B si B a été assigné).

## Scénario 2 — RESERVE avec maker-checker (B résout, B ne peut pas vérifier, A vérifie)

1. B : créez une **Réserve** « Garde-corps non conforme », catégorie **Malfaçon**,
   origine **Contrôle interne**, assigné à B.
2. B : sur le détail, touchez **Démarrer** → statut **En cours**.
3. B : touchez **Marquer résolu** → dialogue « Qu'est-ce qui a été fait ? ».
   Notez « Reprise effectuée ». Ajoutez une photo **après** (obligatoire pour
   une réserve — le texte « Obligatoire pour une réserve » s'affiche).
   → Touchez **Résoudre**. Statut **Résolu — à vérifier**.
4. B : vérifiez qu'il n'y a **pas** de bouton **Vérifier et clore** (B a résolu,
   B ne peut pas vérifier une réserve qu'il a lui-même résolue).
5. Déconnectez B, connectez **A**. Ouvrez le signalement.
   → A voit le bouton **Vérifier et clore**.
6. A : touchez **Vérifier et clore** → « Vous confirmez ? » → **Oui, clore**.
   → Statut **Clos**.
7. A : ouvrez l'**Historique** → 4 lignes (création, démarrage, résolution,
   clôture), avec les noms et les dates relatives.

## Scénario 3 — DEMANDE annulée

1. Créez une **Demande** « Besoin de 50 sacs de ciment », catégorie **Matériel**.
2. Sur le détail, touchez **Annuler le signalement** (bouton rouge).
3. Notez un motif « Doublon avec la commande BC-012 » (min 5 caractères).
4. Touchez **Annuler le signalement**.
   → Statut **Annulé**. L'historique montre l'annulation avec le motif.

## Scénario 4 — En retard

1. Créez un **Problème** avec une échéance **d'hier** (modifiez la date).
2. Sur la liste, onglet **En retard** : le signalement apparaît.
   → Date en rouge + badge **En retard**.
3. Sur l'accueil : la carte **Signalements** montre « En retard : 1 » en rouge.

## Scénario 5 — VISA_DOCUMENT (ancien workflow)

1. Créez un signalement de type **Visa de document** (pas de catégorie).
2. Sur le détail : les boutons de transition apparaissent (Brouillon → En revue
   → Approuvé → Bon pour exécution → As-built).
3. Avancez d'un statut. → Le statut change. Pas de bouton « Démarrer » ou
   « Marquer résolu ».

## Scénario 6 — Réseau faible (hors ligne pendant l'envoi)

1. Ouvrez la création d'un signalement, remplissez le formulaire.
2. Avant d'envoyer : dev tools → Network → **Offline**.
3. Touchez **Enregistrer**.
   → « Connexion perdue. Vos données sont gardées, réessayez. » + bouton
   **Réessayer**.
4. Remettez le réseau en ligne. Touchez **Réessayer**.
   → « Envoi… » puis « Signalement créé ».

## Où regarder les photos

- **RESERVE** → Documents > **04 Qualité > Réserves**
- **PROBLEME / DEMANDE** → Documents > **03 Exécution > Photos**
- **VISA_DOCUMENT** → Documents > **02 Technique > Plans**
