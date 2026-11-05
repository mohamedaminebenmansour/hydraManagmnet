# Test — Profil de l'entreprise et groupements (S1-5)

Cochez « OK » ou « KO » après chaque étape. Connectez-vous comme **Gérant Démo**
(mot de passe = `HYDRA_DEMO_PASSWORD`).

| # | Action | Résultat attendu | OK / KO |
|---|--------|------------------|---------|
| 1 | Ouvrir **Entreprise** depuis le menu | La page affiche l'en-tête A4 avec « HYDRA Démo SARL », l'adresse, le MF formaté avec slashes « 1234567/A/A/M/000 », le RIB groupé « 99 001 000000001234 5646 ». | |
| 2 | Vérifier le chip de complétude | Vert « Profil complet » (tous les champs sont remplis dans le demo). Au survol ou clic, rien ne se passe car il n'y a rien à corriger. | |
| 3 | Onglet **Identité** → taper un MF avec une mauvaise lettre (changer le 2e caractère en U) | Message rouge sous le champ « La lettre de contrôle ne peut pas être I, O ou U. » qui apparaît en live pendant la frappe. | |
| 4 | Corriger le MF → quitter le champ (blur) | Le MF est reformaté avec slashes « 1234567/A/A/M/000 » automatiquement. | |
| 5 | Onglet **Banque** → taper un RIB avec une mauvaise clé (changer le dernier chiffre) | Message rouge « Clé RIB incorrecte : vérifiez le numéro. » après la frappe des 20 chiffres. | |
| 6 | Corriger le RIB → taper un RIB valide | Message vert « Clé RIB correcte » + IBAN affiché groupé par 4 + bouton « Copier l'IBAN ». | |
| 7 | Cliquer « Copier l'IBAN » | Le presse-papiers contient l'IBAN sans espaces « TN5999010000000012345646 ». Toast vert « IBAN copié ». | |
| 8 | Onglet **Logo** → choisir un fichier PNG | Le logo s'affiche en 64px et 32px. | |
| 9 | Onglet **Logo** → essayer un fichier SVG ou texte renommé .png | Refusé avec une boîte de confirmation / toast : « Format accepté : PNG ou JPEG. » | |
| 10 | Onglet **Logo** → essayer un fichier > 1 Mo | Refusé : « Le logo dépasse 1 Mo. » | |
| 11 | Cliquer « Retirer le logo » | Boîte de dialogue PrimeNG : « Voulez-vous vraiment retirer le logo ? » avec boutons « Oui, retirer » / « Annuler ». Cliquer « Oui, retirer » → le logo disparaît, toast « Logo retiré ». | |
| 12 | Modifier le profil dans deux onglets (changer la raison sociale dans A, enregistrer ; puis changer l'adresse dans B, enregistrer) | Le 2e onglet reçoit une bannière jaune au-dessus des onglets « Le profil a été modifié par quelqu'un d'autre. » avec bouton « Recharger ». Cliquer « Recharger » → les champs du 2e onglet sont rechargés depuis le serveur (la raison sociale de A est visible). | |
| 13 | Saisir la même raison sociale deux fois (sans modifier aucun champ) et cliquer « Enregistrer » | Aucun toast de confirmation (pas de changement détecté). La version n'augmente pas. Aucune ligne d'audit n'est écrite. | |
| 14 | Saisir le capital « 50000 », enregistrer ; puis saisir « 50000.000 », enregistrer | Aucune ligne d'audit pour le capital la 2e fois (compareTo BigDecimal : 50000 == 50000.000). La version n'augmente pas. | |
| 15 | Onglet **Groupements** → cliquer « + Nouveau groupement » | Panneau latéral droit s'ouvre avec un formulaire vide : nom, type (Conjoint / Solidaire en cartes radio), chantier, référence marché, notes. Table des membres avec ligne 1 « Notre entreprise » (raison sociale et MF en lecture seule, pas de « Retirer ») + un partenaire vide. Bouton « Ajouter un membre ». | |
| 16 | Saisir un nom, choisir « Solidaire », laisser parts 50 + 40 (total 90) | Le pied du panneau affiche en rouge « Total des parts : 90 % — il manque 10 % ». Bouton « Enregistrer » désactivé. | |
| 17 | Mettre parts 60 + 40 → enregistrer | Le panneau se ferme. Toast vert « Groupement enregistré ». La carte du groupement apparaît dans la liste avec une barre horizontale un segment par membre (largeur = part) + légende sous la barre « Nom — 60 % » + badge « Mandataire » sur le membre mandataire. | |
| 18 | Saisir un groupement avec parts 33.33 / 33.33 / 33.34 (3 membres) | Le total affiche « 100 % » en vert. Bouton « Enregistrer » activé. L'enregistrement réussit (la somme des centièmes = 10000). | |
| 19 | Cliquer « Modifier » sur une carte non archivée | Le panneau s'ouvre en mode édition avec les données du groupement (nom, type, chantier, membres, parts). | |
| 20 | Modifier le groupement dans deux onglets (changer le nom dans A, enregistrer ; puis changer une part dans B, enregistrer) | Le 2e onglet reçoit une bannière jaune dans le panneau « Modifié par quelqu'un d'autre. » avec bouton « Recharger ». Cliquer « Recharger » → les champs sont rechargés depuis le serveur. | |
| 21 | Cliquer « Archiver » sur une carte | Boîte de dialogue PrimeNG : « Voulez-vous vraiment archiver « Nom » ? » avec boutons « Oui, archiver » / « Annuler ». Cliquer « Oui, archiver » → la carte est grisée. Cocher « Afficher les archivés » la fait réapparaître clairement. | |
| 22 | Créer un groupement avec nom vide, type non choisi, 1 seul membre | Le bouton « Enregistrer » reste désactivé. Aucune requête n'est envoyée. | |
| 23 | Créer un groupement avec nom null + un membre ayant un nom vide et un rôle « BOSS » (via API directe ou en contournant le disabled) | Une seule réponse 400 avec `{"code":"VALIDATION","fields":{"nom":"Le nom du groupement est obligatoire.","membres[1].raisonSociale":"La raison sociale du membre est obligatoire.","membres[1].role":"Rôle invalide."}}`. Aucune ligne en base. | |
| 24 | Se connecter comme un utilisateur sans ORG_SETTINGS (ex. `chef.demo`) | Le menu « Entreprise » est absent. Taper `/entreprise` → refusé (guard). | |
| 25 | Onglet **Groupements** : si la barre est composée de plusieurs couleurs, vérifier qu'il y a aussi un libellé « N % » dans chaque segment et une légende sous la barre | La couleur n'est jamais le seul signal. | |
| 26 | Réduire la fenêtre sous 640px, ouvrir un nouveau groupement | Chaque membre est affiché sous forme de carte (label au-dessus de chaque input), pas en tableau. | |
