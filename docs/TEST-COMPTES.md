# Test — Comptes, mots de passe, utilisateurs et rôles (U1-U6)

Ce script est destiné à un **non-développeur**. Connectez-vous sur HYDRA avec
les comptes de démo (mot de passe = la valeur de `HYDRA_DEMO_PASSWORD` sur Render).

Cochez « OK » ou « KO » après chaque étape.

| # | Qui | Action | Résultat attendu | OK / KO |
|---|-----|--------|------------------|---------|
| 1 | gerant | Ouvrir **Utilisateurs** → onglet **Tableau** | Les compteurs montrent : 1 en attente 1re co., 1 sans connexion 30 j (rouge), 1 inactif, 1 exception signée. | |
| 2 | gerant | Basculer en **Cartes**, recharger la page | La vue **Cartes** est conservée après rechargement. | |
| 3 | gerant | Trier par **Dernière connexion** ; filtrer par rôle **Magasinier (démo)** | Seuls **chef** et **travaux** apparaissent (ils ont le rôle Magasinier). | |
| 4 | gerant | Ouvrir le panneau de **Dali Dormant** → onglet **Activité** | Des événements s'affichent (connexions, modifications). Aller dans **Sécurité** → **Réinitialiser** → un mot de passe temporaire s'affiche une seule fois. | |
| 5 | nouveau | Se connecter → atterrit sur `/premiere-connexion` | Taper `/projects` dans la barre d'adresse → retour sur `/premiere-connexion`. Un mot de passe faible est refusé avec un message rouge. Une bonne phrase de passe est acceptée → page d'accueil. | |
| 6 | reinit | Se connecter (mot de passe = `HYDRA_DEMO_PASSWORD`) | Forcé de changer son mot de passe (même écran que `nouveau`). | |
| 7 | any | **Mon compte** → changer téléphone + fonction → **Enregistrer** | Le gerant voit les changements dans le panneau de l'utilisateur. L'audit montre before → after. | |
| 8 | any | **Mon compte** → **Déconnecter mes autres appareils** avec 2 navigateurs ouverts | Le second navigateur est déconnecté. | |
| 9 | any | Page de connexion → **Mot de passe oublié ?** | Sans email configuré : texte « Demandez à votre administrateur ». Avec email : message neutre pour une adresse réelle ET une fausse ; email reçu pour la réelle seulement ; lien utilisable une fois ; 2e utilisation → « Ce lien n'est plus valide ». | |
| 10 | gerant | **Rôles** → ouvrir **Magasinier (démo)** → décocher une permission → **Enregistrer** | Boîte de confirmation nommant **chef** et **travaux** → confirmer → enregistré. | |
| 11 | gerant | **Rôles** → **Vue matrice** | La matrice montre les mêmes permissions que chaque page de rôle. **Exporter (CSV)** → le fichier s'ouvre dans Excel avec les accents corrects. | |
| 12 | gerant | **Rôles** → ouvrir un rôle système (ex. **OWNER**) → tenter de modifier | Tout est en lecture seule + bandeau « Rôle système — dupliquez-le ». **Dupliquer** crée une copie modifiable. | |
| 13 | real account | Se connecter avec votre VRAI compte | Aucun utilisateur de démo n'apparaît dans votre liste d'utilisateurs. | |

## Comptes de démo (mot de passe = `HYDRA_DEMO_PASSWORD`)

| Email | Nom | Rôle | Statut attendu |
|-------|-----|------|----------------|
| `gerant.demo@hydra.demo` | Gérant Démo | OWNER | OWNER |
| `nouveau@demo.hydra.tn` | Nadia Nouveau | ACHAT | PENDING (1re connexion) |
| `reinit@demo.hydra.tn` | Rami Réinit | CONDUCTEUR | PENDING (admin reset) |
| `dormant@demo.hydra.tn` | Dali Dormant | INGENIEUR | Sans connexion 30 j |
| `inactif@demo.hydra.tn` | Ines Inactive | BUREAU | INACTIVE |
| `absent@demo.hydra.tn` | Amel Absente | BUREAU | ABSENT (jusqu'à +7 j) |
| `exception@demo.hydra.tn` | Elyes Exception | ACHAT + FINANCE | ACTIVE (exception signée) |
| `chef.demo@hydra.demo` | Chef Chantier | CONDUCTEUR + Magasinier | ACTIVE |
| `travaux.demo@hydra.demo` | Conducteur Travaux | CONDUCTEUR + Magasinier | ACTIVE |

**Note** : `reinit@demo.hydra.tn` a eu son mot de passe réinitialisé par l'admin.
Son mot de passe temporaire a été remplacé par `HYDRA_DEMO_PASSWORD` pour que
les testeurs puissent se connecter. Il devra le changer à sa première connexion.
