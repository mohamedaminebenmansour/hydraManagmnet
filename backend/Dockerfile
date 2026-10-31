# Build stage — context is ./backend so pom.xml + src/ are at /app
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# PHASE-BUILD-RESILIENCE: copy the Maven settings.xml with retry/backoff config
# so intermittent HTTP 429 from Maven Central doesn't kill the build.
COPY .mvn/settings.xml /root/.m2/settings.xml

COPY pom.xml .
# Pre-warm the local cache: download all dependencies first (with retries).
# This step is the most likely to hit 429 — retry it up to 3 times.
# The trailing `ls` is a safety net: if all 3 attempts failed, the RUN step
# must exit non-zero so Docker stops instead of proceeding without a jar.
RUN ok=0; \
    for i in 1 2 3; do \
      echo "[dependency:go-offline] attempt $i/3" && \
      mvn dependency:go-offline -B -s /root/.m2/settings.xml && { ok=1; break; } || \
      { echo "[retry] sleeping 15s before next attempt…" && sleep 15; } ; \
    done; \
    [ "$ok" = "1" ] || { echo "[FATAL] dependency:go-offline failed after 3 attempts"; exit 1; }

COPY src ./src
# Build the actual jar (with retries).
# Same pattern: track success, fail hard if all 3 attempts fail.
RUN ok=0; \
    for i in 1 2 3; do \
      echo "[package] attempt $i/3" && \
      mvn package -DskipTests -B -s /root/.m2/settings.xml && { ok=1; break; } || \
      { echo "[retry] sleeping 15s before next attempt…" && sleep 15; } ; \
    done; \
    [ "$ok" = "1" ] || { echo "[FATAL] mvn package failed after 3 attempts"; exit 1; }; \
    ls -la target/hydra-0.0.1-SNAPSHOT.jar

# Run stage — minimal JRE
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN mkdir -p /opt/data/files
COPY --from=build /app/target/hydra-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
