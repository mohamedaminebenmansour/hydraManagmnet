# HYDRA — sécurité

Ce qui est protégé, comment, et ce que la personne responsable doit faire elle-même.

## Ce qui est en place

| Sujet | Protection | Où |
|-------|-----------|-----|
| Connexion | 5 échecs par e-mail et 20 par adresse IP en 15 minutes, puis HTTP 429 « Trop de tentatives. Réessayez dans 15 minutes. » (en mémoire : une seule instance) | `auth/LoginRateLimiter`, `AuthController.login` |
| Mots de passe | Hachés en BCrypt ; aucun mot de passe en clair dans le code (démo / dev : variables `HYDRA_DEMO_PASSWORD`, `HYDRA_DEV_SEED_PASSWORD`) | `SecurityConfig`, `DemoDataRunner`, `DevDataSeedRunner` |
| Session | Cookie `HYDRA_SID` HttpOnly, Secure (`HYDRA_COOKIE_SECURE`, vrai par défaut), SameSite=Lax, 12 h | `application.properties` |
| CSRF | Jeton `XSRF-TOKEN` exigé sur chaque requête qui modifie des données | `SecurityConfig` |
| CORS | Seules les origines de `HYDRA_ALLOWED_ORIGINS` (séparées par des virgules ; défaut `http://localhost:4200`), jamais « * » avec les cookies | `CorsConfig` |
| En-têtes | `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy: strict-origin-when-cross-origin` | `SecurityConfig` |
| Erreurs | Une erreur technique renvoie « Une erreur technique est survenue. Référence : XXXXXX » ; jamais de classe Java, de SQL ni de trace. Le détail est dans les logs avec la même référence | `GlobalExceptionHandler` |
| Logs | Niveau INFO en production (pas de DEBUG, pas de SQL avec valeurs) | `application.properties` |
| Droits | Permissions par rôle, droits par dossier (un sous-dossier hérite de son dossier parent), filtrage par organisation dans chaque requête | `PermissionService`, `FolderAccessService` |
| Actuator | Non installé : rien n'est exposé | — |

## À faire par la personne responsable

- [ ] **Changer le mot de passe du propriétaire** dans l'application (Mon compte → mot de passe), puis mettre à jour `OWNER_PASSWORD` sur Render si besoin.
- [ ] **Révoquer l'ancien jeton GitHub** (GitHub → Settings → Developer settings → Personal access tokens) et en créer un nouveau seulement si nécessaire.
- [ ] **Sur Render, définir `HYDRA_ALLOWED_ORIGINS`** avec l'URL Vercel exacte (par exemple `https://hydra.vercel.app`, sans « / » final). **À faire avant de déployer cette version** : le navigateur envoie l'origine Vercel même à travers la réécriture `/api`, et une origine absente de la liste est refusée.
- [ ] **Supabase : activer RLS** (Row Level Security) sur chaque table exposée à la Data API. Le backend se connecte avec son propre utilisateur et n'utilise pas la Data API ; sans RLS, ces tables sont lisibles avec la clé publique.
- [ ] **Mettre en place une restauration de la base et la TESTER une fois** : restaurer une sauvegarde dans une base vide, démarrer HYDRA dessus, vérifier qu'on se connecte et que les chantiers sont là. Noter la date du test.

## Variables d'environnement liées à la sécurité

| Variable | Rôle | Défaut |
|----------|------|--------|
| `HYDRA_ALLOWED_ORIGINS` | Origines autorisées (CORS) | `http://localhost:4200` |
| `HYDRA_COOKIE_SECURE` | Cookie de session Secure | `true` |
| `DB_PASSWORD`, `OWNER_PASSWORD` | Secrets, à saisir dans le tableau de bord Render (`sync: false`) | — |
| `HYDRA_DEMO_PASSWORD`, `HYDRA_DEV_SEED_PASSWORD` | Mots de passe des données de démo / dev (profils `demo` / `dev` seulement) | vide = pas de données |
