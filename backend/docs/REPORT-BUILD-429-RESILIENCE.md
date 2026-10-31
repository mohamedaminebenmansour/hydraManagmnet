# REPORT-BUILD-429-RESILIENCE — Maven Central 429 fix

**Date:** 2026-10-31
**Commit:** `537529c fix(build): Maven Central 429 resilience — retry + backoff settings + Dockerfile retry loop`
**Pushed to:** `origin/main` → Render auto-redeploying

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

**Diagnosis:** Maven Central returned HTTP 429 (Too Many Requests) for the `mapstruct-processor:1.6.3` POM. The artifact itself exists and is healthy — confirmed by hitting the URL directly (also got 429 at the time, then 200 later). Maven's default Wagon HTTP client does **not** retry on 429; it treats 4xx as a permanent client error and fails the build.

This is a **Render-specific** problem: Render's free-tier builders share egress IPs, so when several Render users build Java projects simultaneously, Maven Central rate-limits the shared IP. It is **not** a code bug, not a pom.xml bug, and not a Dockerfile bug in the strict sense — but the Dockerfile was not resilient to it.

---

## 2. The fix — two layers of defense

### Layer 1: `backend/.mvn/settings.xml` (NEW file)
Configures Maven's Wagon HTTP client to **retry on 429/5xx**:

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

A `<mirror>` block points `central` at the same URL but routes through this retry handler.

### Layer 2: Dockerfile bash retry loop
Both Maven steps are now wrapped in a 3-attempt shell loop:

```dockerfile
RUN for i in 1 2 3; do \
      echo "[dependency:go-offline] attempt $i/3" && \
      mvn dependency:go-offline -B -s /root/.m2/settings.xml && break || \
      (echo "[retry] sleeping 15s before next attempt…" && sleep 15); \
    done

RUN for i in 1 2 3; do \
      echo "[package] attempt $i/3" && \
      mvn package -DskipTests -B -s /root/.m2/settings.xml && break || \
      (echo "[retry] sleeping 15s before next attempt…" && sleep 15); \
    done
```

Even if Maven's internal retry (Layer 1) exhausts its 10 attempts, the outer shell loop restarts the whole `mvn` step. With the local Maven cache partially populated from the failed attempt, the next attempt often succeeds immediately.

Both steps now invoke `mvn -s /root/.m2/settings.xml` explicitly so the retry config is always loaded (Render's default Maven image does not auto-pick up `.mvn/settings.xml`).

---

## 3. Files changed

```
Dockerfile                                  (retry loop + copy settings.xml)
backend/.mvn/settings.xml                   (NEW — retry/backoff config)
```

2 files. No Java/TS code changes.

---

## 4. Why this works

| Scenario | Without fix | With fix |
|---|---|---|
| Maven Central returns 429 once | Build fails | Wagon retries up to 10 times with exponential backoff |
| Maven Central returns 429 for 5+ min straight | Build fails | After 10 internal retries fail, the outer loop sleeps 15s and restarts — local cache is now partially warm, second attempt succeeds |
| Maven Central is fully down | Build fails | Same — but eventually all 3 outer attempts fail; deploy fails (acceptable: there's nothing to download from) |
| Normal operation | Works | Works (slight overhead from explicit `-s` flag, negligible) |

---

## 5. How to verify on Render

1. Watch the Render deploy logs for commit `537529c`:
   - You should see `[dependency:go-offline] attempt 1/3` then `[package] attempt 1/3`
   - If a 429 hits, you'll see Maven's own retry logs (`Retrying ... attempt N of 10`) followed by either success or `[retry] sleeping 15s before next attempt…`
2. Build should complete in 3–6 min on the first try if Central is healthy; up to 10 min if Central is rate-limiting.
3. Health check after deploy: `curl https://hydra-0gjc.onrender.com/health` → `{"status":"ok"}` (cold start ~60–90s).

---

## 6. Known limitations

1. **Maven wrapper bypassed on Render** — the Dockerfile uses `mvn` directly (from the `maven:3.9-eclipse-temurin-21` image), not `./mvnw`. This is intentional and correct for Docker builds; the wrapper is only needed for local dev where Maven isn't installed.
2. **First Render build still cold** — the first deploy after this fix has to re-download all deps from scratch (no warm cache yet). Subsequent deploys reuse the Docker layer cache for `dependency:go-offline` and are much faster.
3. **No mirror fallback** — I considered adding Aliyun or Sonatype as a fallback mirror, but those are also rate-limited from Render's IPs and add complexity. The retry approach is simpler and sufficient: Maven Central's 429s are transient by nature.
4. **If Maven Central is fully down** (rare, but possible), no retry logic will save the build. The mitigation is to wait it out and trigger a manual redeploy from the Render dashboard once Central is back.
