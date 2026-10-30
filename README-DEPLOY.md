# HYDRA — Déploiement (Render + Supabase + Vercel)

> Guide pas à pas. Une fois configuré, chaque `git push` déploie automatiquement.

---

## Architecture

```
GitHub (push) ──→ Vercel  (frontend Angular, gratuit)
              ──→ Render  (backend Java, gratuit/starter)
Supabase        ──→ PostgreSQL (base de données, gratuit)
```

## URLs après déploiement

| Service | URL exemple | Rôle |
|---------|-------------|------|
| Frontend | `https://hydra-managmnet.vercel.app` | Site web |
| Backend | `https://hydra-api.onrender.com` | API Java |
| Base de données | `db.xxxxxx.supabase.co` | PostgreSQL |

---

## Étape 1 : Supabase (base de données) — 5 min

1. Aller sur https://supabase.com → **Start your project** → GitHub
2. **New Project** :
   - Name : `hydra-db`
   - Password : choisir un mot de passe (le noter !)
   - Region : `Europe West (Frankfurt)`
   - Plan : Free
3. Attendre 2 min (création)
4. Aller dans **Settings** → **Database**
5. Copier le **Host** (ex: `db.abcdefgh.supabase.co`)
6. Noter :
   - Host = `db.abcdefgh.supabase.co`
   - User = `postgres`
   - Password = votre mot de passe

---

## Étape 2 : Render (backend) — 10 min

1. Aller sur https://render.com → **Get Started** → GitHub
2. **New +** → **Web Service**
3. Sélectionner le dépôt : `mohamedaminebenmansour/hydraManagmnet`
4. Configurer :

   | Champ | Valeur |
   |-------|--------|
   | Name | `hydra-api` |
   | Language | `Docker` |
   | Root Directory | `backend` |
   | Instance Type | `Free` (ou Starter $7/mo pour éviter le sommeil) |

   > Alternative sans Docker : Language = `Java`, Build = `./mvnw package -DskipTests`, Start = `java -jar target/hydra-0.0.1-SNAPSHOT.jar`

5. **Environment Variables** — cliquer **Add Environment Variable** pour chaque ligne :

   | Key | Value |
   |-----|-------|
   | `DB_URL` | `jdbc:postgresql://db.abcdefgh.supabase.co:5432/postgres` |
   | `DB_USER` | `postgres` |
   | `DB_PASSWORD` | votre mot de passe Supabase |
   | `OWNER_EMAIL` | `owner@hydra.tn` |
   | `OWNER_PASSWORD` | `HydraOwner2026!` |
   | `HYDRA_STORAGE_TYPE` | `local` |
   | `HYDRA_STORAGE_ROOT` | `/opt/data/files` |

   Remplacer `abcdefgh` par votre host Supabase réel.

6. Cliquer **Create Web Service**
7. Attendre 5-10 min (Render build + deploy)
8. URL obtenue : `https://hydra-api.onrender.com`
9. Tester : `https://hydra-api.onrender.com/health` → `{"status":"ok"}`

---

## Étape 3 : Vercel (frontend) — 5 min

1. Aller sur https://vercel.com → **Sign Up** → GitHub
2. **Add New Project**
3. Sélectionner : `mohamedaminebenmansour/hydraManagmnet`
4. Configurer :

   | Champ | Valeur |
   |-------|--------|
   | Framework Preset | Angular |
   | Root Directory | `frontend` |
   | Build Command | `npm run build` |
   | Output Directory | `dist/hydra-web/browser` |
   | Install Command | `npm install` |

5. Cliquer **Deploy** (pas besoin de variables d'environnement — le `vercel.json` s'occupe du proxy)
6. Attendre 3-5 min
7. URL obtenue : `https://hydra-managmnet.vercel.app`

---

## Étape 4 : Connecter frontend → backend

Le fichier `frontend/vercel.json` contient déjà :

```json
{
  "rewrites": [
    {
      "source": "/api/:path*",
      "destination": "https://hydra-api.onrender.com/api/:path*"
    }
  ]
}
```

Si votre URL Render est différente (ex: `hydra-backend-xyz.onrender.com`), modifiez ce fichier + `git push`.

---

## Étape 5 : Tester

1. Ouvrir `https://hydra-managmnet.vercel.app`
2. Page de connexion HYDRA s'affiche
3. Login : `owner@hydra.tn` / `HydraOwner2026!`
4. Cliquer **Matériel** → voir le stock
5. 🎉 C'est en ligne !

---

## Après : chaque `git push` déploie automatiquement

```
git add .
git commit -m "my changes"
git push origin main
```

→ Vercel rebuild le frontend (3 min)
→ Render rebuild le backend (5 min)
→ Les deux URLs se mettent à jour

---

## En cas de problème

| Problème | Solution |
|----------|----------|
| `health` erreur 500 | Vérifier DB_URL / DB_PASSWORD dans Render Environment |
| Login échoue | Vérifier OWNER_EMAIL / OWNER_PASSWORD dans Render |
| Page blanche | Vérifier outputDirectory = `dist/hydra-web/browser` dans Vercel |
| `/api` 404 | Vérifier `vercel.json` — l'URL Render doit être exacte |
| Render dort | Normal en Free. Premier clic = 30s. Passer à Starter ($7/mo) |
| Flyway échoue | La base Supabase est vide au départ — le backend exécute les migrations automatiquement au premier démarrage |

---

## Coûts

| Plan gratuit | 0€/mois (Render dort après 15 min) |
| Plan recommandé | ~7€/mois (Render Starter = toujours actif) |
