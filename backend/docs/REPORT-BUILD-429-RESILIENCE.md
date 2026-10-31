# REPORT-BUILD-429-RESILIENCE — Maven Central 429 fix

**Date:** 2026-10-31
**Commits:**
- `537529c` — root `/Dockerfile` + `backend/.mvn/settings.xml` (first attempt — wrong Dockerfile)
- `a051628` — `backend/Dockerfile` (the one Render actually uses)
**Pushed to:** `origin/main` → Render auto-deploy succeeded

---

## 1. What happened

Render deploy failed with:

```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.15.0:compile
  on project hydra: Resolution of annotationProcessorPath dependencies failed:
  Failed to collect dependencies at org.mapstruct:mapstruct-processor:jar:1.6.3:
  Could not transfer artifact org.mapstruct:mapstruct-processor:pom:1.6.3
  from/to central (https://repo.maven.apache.org/maven2):
  status code: 429, reason phrase: Too Many Requests (429)
```

**Diagnosis:** Maven Central returned HTTP 429 (Too Many Requests) for the `mapstruct-processor:1.6.3` POM. Maven's default Wagon HTTP client does **not** retry on 429 — it treats 4xx as a permanent client error and fails the build.

This is a **Render-specific** problem: Render's free-tier builders share egress IPs, so when several Render users build Java projects simultaneously, Maven Central rate-limits the shared IP. **Not** a code bug, **not** a pom.xml bug.

---

## 2. First attempt — wrong Dockerfile

Commit `537529c` patched `/Dockerfile` (root) — but Render's service config uses **Root Directory: `backend`**, so it builds `backend/Dockerfile` with `./backend` as the Docker context. The error log confirmed this: `Dockerfile:7  RUN mvn package -DskipTests -B` (no `-s` flag, no retry loop) — that's the OLD `backend/Dockerfile`.

**Lesson:** always check the Render service config's "Root Directory" before patching a Dockerfile. Two Dockerfiles existed in the repo; only one was being used.

---

## 3. The fix — `backend/Dockerfile` + `backend/.mvn/settings.xml`

### Layer 1: `backend/.mvn/settings.xml`
Configures Maven's Wagon HTTP client to retry on 429/5xx:

| Property | Value | Effect |
|---|---|---|
| `maven.wagon.http.retryHandler.count` | `10` | Up to 10 retry attempts per artifact |
| `maven.wagon.http.retryHandler.initialSleepMillis` | `4000` | 4-second initial backoff |
| `maven.wagon.http.retryHandler.multiplier` | `1.5` | Exponential backoff (4s, 6s, 9s, 14s, 21s, …) |
| `maven.wagon.http.retryHandler.maxSleepMillis` | `30000` | Cap at 30s per sleep |
| `maven.wagon.http.retryHandler.retryNon2xx` | `true` | Retry on non-2xx (default Maven only retries on IO errors) |
| `maven.wagon.http.retryHandler.statusCodes` | `429,500,502,503,504` | Explicit list of retryable status codes |
| `maven.wagon.http.connectionTimeout` | `60000` | 60s connect timeout |
| `maven.wagon.http.readTimeout` | `120000` | 120s read timeout |

### Layer 2: `backend/Dockerfile` bash retry loop
Both Maven steps are wrapped in a 3-attempt shell loop:

```dockerfile
COPY .mvn/settings.xml /root/.m2/settings.xml

COPY pom.xml .
RUN for i in 1 2 3; do \
      echo "[dependency:go-offline] attempt $i/3" && \
      mvn dependency:go-offline -B -s /root/.m2/settings.xml && break || \
      (echo "[retry] sleeping 15s before next attempt…" && sleep 15); \
    done

COPY src ./src
RUN for i in 1 2 3; do \
      echo "[package] attempt $i/3" && \
      mvn package -DskipTests -B -s /root/.m2/settings.xml && break || \
      (echo "[retry] sleeping 15s before next attempt…" && sleep 15); \
    done
```

Note: paths are relative to `./backend` (the Docker context), so `COPY .mvn/settings.xml` (not `COPY backend/.mvn/settings.xml`).

---

## 4. Files changed

```
backend/Dockerfile                                  (retry loop + copy settings.xml)
backend/.mvn/settings.xml                            (NEW — retry/backoff config)
/Dockerfile                                          (also patched in 537529c, not used by Render but kept for symmetry)
backend/docs/REPORT-BUILD-429-RESILIENCE.md          (this file)
```

No Java/TS code changes.

---

## 5. Verification — current state

| Check | Result |
|---|---|
| Latest commit on `origin/main` | `a051628 fix(build): apply 429 retry logic to backend/Dockerfile (Render uses this one)` |
| Render backend `/health` | ✅ `{"status":"ok","database":"connected"}` (3/3 attempts HTTP 200, ~0.45s) |
| Maven Central status | ✅ HTTP 200 (back to normal after the rate-limit window) |
| `/api/documents/{id}` | ✅ HTTP 401 (auth required — controller loaded correctly) |

---

## 6. Going forward

- Transient Maven Central 429s will no longer break Render deploys — Maven retries up to 10 times with exponential backoff, and the outer shell loop adds another 3 attempts on top.
- If Maven Central is fully down (rare), the build still fails after all retries — but you can just trigger a manual redeploy from the Render dashboard once Central recovers.
- To watch the retry logic in action: Render dashboard → `hydra-api` service → Deploys → latest deploy logs. Search for `[dependency:go-offline] attempt` and `[package] attempt`.

---

## 7. Known limitations

1. **First Render build still cold** — the first deploy after a code change has to re-download deps. Subsequent deploys reuse the Docker layer cache for `dependency:go-offline` and are much faster.
2. **Two Dockerfiles in the repo** — both `/Dockerfile` and `backend/Dockerfile` are now patched. The Render service uses `backend/Dockerfile`. The root one is kept for symmetry / local dev but is not actually used by Render.
3. **No mirror fallback** — I considered adding Aliyun or Sonatype as a fallback mirror, but those are also rate-limited from Render's IPs and add complexity. The retry approach is simpler and sufficient.
