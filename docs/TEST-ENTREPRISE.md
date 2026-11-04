# Test — Profil de l'entreprise et groupements (S1-5)

Cochez « OK » ou « KO » après chaque étape. Connectez-vous comme **Gérant Démo**
(mot de passe = `HYDRA_DEMO_PASSWORD`).

| # | Action | Résultat attendu | OK / KO |
|---|--------|------------------|---------|
| 1 | Ouvrir **Entreprise** depuis le menu | La page affiche l'en-tête A4 avec « HYDRA Démo SARL », l'adresse, le MF formaté, le RIB. | |
| 2 | Vérifier le chip de complétude | Vert « Profil complet » (tous les champs sont remplis dans le demo). | |
| 3 | Onglet **Banque** → taper un RIB avec une mauvaise clé (changer le dernier chiffre) | Message rouge « Clé RIB incorrecte ». | |
| 4 | Corriger le RIB → taper un RIB valide | Message vert « Clé RIB correcte » + IBAN affiché + bouton « Copier l'IBAN ». | |
| 5 | Onglet **Logo** → choisir un fichier PNG | Le logo s'affiche en 64px et 32px. | |
| 6 | Onglet **Logo** → essayer un fichier SVG ou texte renommé .png | Refusé : « Format accepté : PNG ou JPEG. » | |
| 7 | Onglet **Logo** → essayer un fichier > 1 Mo | Refusé : « Le logo dépasse 1 Mo. » | |
| 8 | Onglet **Groupements** → cliquer « + Nouveau groupement » | Panneau latéral avec formulaire (nom, type, membres). | |
| 9 | Créer un groupement avec parts 60 + 30 (total 90) | Bouton « Enregistrer » désactivé ou erreur « Le total des parts doit faire 100 % ». | |
| 10 | Corriger à 60 + 40 → enregistrer | Groupement créé, carte affichée avec les parts. | |
| 11 | Se connecter comme un utilisateur sans ORG_SETTINGS (ex. `chef.demo`) | Le menu « Entreprise » est absent. Taper `/entreprise` → refusé (guard). | |
| 12 | Ouvrir Entreprise dans deux onglets, modifier dans les deux | Le deuxième onglet reçoit « Le profil a été modifié par quelqu'un d'autre ». | |
| 13 | Onglet **Groupements** → « Archiver » un groupement | Le groupement est marqué archivé. Cocher « Afficher les archivés » le fait réapparaître. | |
