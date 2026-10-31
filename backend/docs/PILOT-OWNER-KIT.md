# HYDRA — Kit Pilote Gérant

> Document de référence pour le gérant lors du pilote.
> À imprimer ou garder ouvert sur un deuxième écran.

---

## 1. Cheatsheet — 6 étapes du flux complet

```
┌─────────────────────────────────────────────────────────┐
│  BC → BL → Facture → Rapprochement → Payer → Clôturer  │
└─────────────────────────────────────────────────────────┘
```

| Étape | Où (sidebar) | Action | Résultat attendu |
|-------|-------------|--------|-----------------|
| **1. Commander** | Achats → Commandes | Bouton « Nouveau BC » → sélectionner projet + article + quantité → Créer → Confirmer | Statut **CONFIRMÉ** (vert) |
| **2. Réceptionner** | Achats → Réceptions fournisseur | Bouton « Nouvelle réception » → sélectionner le BC → ajuster qté reçue → Créer brouillon → Poster | Statut **RÉCEPTIONNÉ** (vert). Stock augmenté. |
| **3. Facturer** | Achats → Factures | Bouton « Nouvelle facture » → lier le BC → saisir qté + montant HT + TVA → Créer → Valider | Statut **VALIDÉE** (vert) |
| **4. Vérifier** | Achats → Rapprochement | Voir la ligne : commandé = reçu = stock = facturé | Statut **CONFORME** (vert) |
| **5. Payer** | Trésorerie → Nouvelle opération | Section 5 : sélectionner le BC → Envoyer pour validation | 200, **pas de bannière ambre** (tout est conforme) |
| **6. Clôturer** | Achats → Commandes → ouvrir le BC | Bouton « Clôturer la commande » → confirmer | Statut **CLOS** (violet) |

---

## 2. Ce qu'il NE faut PAS faire

| ❌ Ne pas faire | ✅ À la place |
|-----------------|-------------|
| Attacher une facture à un **transfert interne** (dépôt → chantier) | Les transferts internes n'ont pas de facture. Section « Contrôle matériel » reste vide. |
| Créer un BL sans BC confirmé (pour le pilote) | D'abord : Achats → Commandes → créer + confirmer le BC. Ensuite : Réceptions → créer le BL lié au BC. |
| Valider une facture sans lignes | Ajouter au moins 1 ligne (article + qté) avant de valider. |
| Forcer la clôture d'un BC non conforme (si non-owner) | Le forçage est réservé au gérant ou à l'admin stock. Le bouton est visible mais refusera. |
| Chercher les réceptions dans « Mes validations » | Les réceptions fournisseur sont dans **Achats → Réceptions fournisseur**. « Mes validations » = approvals de paiement uniquement. |
| Mettre une quantité reçue supérieure à la quantité commandée | Le système bloque : `BL_OVER_RECEIVE` avec le restant possible. |
| Confondre « Demandes » (signalements terrain) avec « Mes validations » (approbations paiement) | « Demandes » = problèmes chantier. « Mes validations » = étapes de workflow paiement. |

---

## 3. Script de session gérant (30 minutes)

### Avant la session
- Backend + frontend démarrés (ou https://github.com/mohamedaminebenmansour/hydraManagmnet cloné en local)
- Compte : `owner@hydra.tn` / `<OWNER_PASSWORD>`
- Un projet disponible (ex: Adduction Aïn Draham)
- Un article disponible (ex: Ciment 25kg)

### Minute 0–5 : Découverte
1. **Se connecter** → page d'accueil (dashboard)
2. **Sidebar** : montrer les 3 zones principales :
   - **Mes validations** (en haut, badge orange) = ce qui demande mon action
   - **Achats** (expandable) = Commandes / Réceptions / Factures / Rapprochement
   - **Trésorerie** = caisses + paiements
3. Cliquer **Achats** → expand → voir les 4 sous-menus

### Minute 5–15 : Flux complet (BC → BL → Facture → Match)
4. **Achats → Commandes** → « Nouveau BC »
   - Projet : Adduction Aïn Draham
   - Ligne : Ciment 25kg, qté 50, sac
   - Créer → le BC apparaît en DRAFT
5. Ouvrir le BC → **Confirmer** → statut CONFIRMÉ (vert)
6. **Achats → Réceptions fournisseur** → « Nouvelle réception »
   - Sélectionner le BC (auto-remplit les lignes)
   - Ajuster qté reçue = 50
   - Créer brouillon → **Poster**
   - Toast « BL réceptionné » → stock +50
7. **Achats → Factures** → « Nouvelle facture »
   - Lier le BC
   - Ligne : Ciment, qté 50, prix 10, HT 500, TVA 95, TTC 595
   - Téléverser un PDF (optionnel)
   - Créer → **Valider**
8. **Achats → Rapprochement**
   - Voir la ligne : commandé=50, reçu=50, stock=50, facturé=50
   - Statut **CONFORME** (vert) ✅

### Minute 15–22 : Paiement
9. **Trésorerie** → « Nouvelle opération »
10. Section 2 : montant 500, date aujourd'hui
11. Section 3 : sous-traitant (Pipetech), libellé « Paiement ciment »
12. Section 4 : mode Espèces, nature Matériel
13. Section 5 : **sélectionner le BC** (contrôle matériel)
14. **Envoyer pour validation**
    - Résultat : 200, **pas de bannière ambre** (tout conforme)
    - Toast « Paiement envoyé »
15. Ouvrir le détail du paiement → pas de panneau ambre

### Minute 22–27 : Clôture
16. **Achats → Commandes** → ouvrir le BC
17. Bouton **« Clôturer la commande »** → confirmer
18. Statut **CLOS** (violet) ✅

### Minute 27–30 : Test d'erreur
19. Créer un nouveau BC (qté 100) → confirmer (pas de BL)
20. **Trésorerie** → nouvelle opération → Section 5 : sélectionner ce BC
21. **Envoyer pour validation**
    - Résultat : 200 + **bannière ambre** « aucune réception (BL) enregistrée »
    - Le paiement passe quand même (non bloquant)
22. Ouvrir le détail → **panneau ambre** avec warning + liens « Voir commande » + « Rapprochement »

### Fin de session
- Le gérant a vu : le flux complet, le rapprochement conforme, un paiement propre, une clôture, et un paiement avec alerte.

---

## 4. Modèle de signalement de bug

```markdown
### Bug # [date]

**Écran** : [nom de la page / route]
**Action** : [ce que vous avez fait]
**Attendu** : [ce qui devrait se passer]
**Actuel** : [ce qui s'est passé à la place]

**Capture d'écran** : [joindre ou décrire]
**Utilisateur** : [owner@hydra.tn / karim.bouzid@hydra.tn / …]
**Projet** : [nom du chantier]
**Données** : [BC-001, BL-001, montant, qté…]

**Reproductible** : Oui / Non
**Si oui, étapes** :
1. …
2. …
3. …
```

---

## 5. Limitations connues (pilote v1)

| # | Limitation | Impact | Quand |
|---|------------|--------|-------|
| 1 | `require-stock-match = false` (défaut) | Le paiement passe même si le rapprochement n'est pas conforme. Les alertes sont informatives (ambré), jamais bloquantes. | Pour bloquer : passer le flag à `true` (redémarrage backend requis) |
| 2 | `bcId` non persisté sur les paiements créés avant Part 4.1 | Les anciens paiements n'ont pas de lien BC → pas de materialWarnings sur leur détail. | Seuls les nouveaux paiements ont le lien. |
| 3 | Pas de module TVA / RAS | Les montants HT/TVA/TTC sont saisis manuellement. Pas de calcul automatique de retenue à la source. | Partie 5 (future) |
| 4 | Pas de bordereau de livraison interne | Les transferts dépôt → chantier sont des TRANSFER simples (pas de document signé). | Partie 5 (future) |
| 5 | Pas de module sous-traitant (ST) | Pas de gestion des contrats ST, retenue de garantie, décompte. | Partie 5 (future) |
| 6 | Pas d'application mobile native | L'interface web est responsive mais pas optimisée offline. | PWA future |
| 7 | `factureId` persisté mais non visible sur le détail du paiement | Le champ existe en DB mais le movement-detail n'affiche pas encore la facture liée. | UI future |
| 8 | Pas d'import CSV en masse | Les articles, BC, factures doivent être saisis un par un. | v1.5 |
| 9 | Forçage de clôture BC = OWNER ou STOCK_ADMIN uniquement | Un chef de projet ne peut pas forcer la clôture d'un BC non conforme. | Par design (contrôle) |
| 10 | Le flag `require-stock-match` se lit au démarrage | Changer la valeur dans `application.properties` nécessite un redémarrage du backend. | Acceptable v1 |
| 11 | Pas de multi-organisation | Une instance = une entreprise. | Future |
| 12 | Houssem Brahmi (ACHAT) n'a pas de projet | Il ne voit aucun chantier → test IDOR idéal mais pas utilisable pour le flux normal. | Assigner un projet si besoin |
