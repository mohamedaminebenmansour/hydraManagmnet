# Build stage — context is ./backend so pom.xml + src/ are at /app
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# PHASE-BUILD-RESILIENCE: copy the Maven settings.xml with retry/backoff config
# so intermittent HTTP 429 from Maven Central doesn't kill the build.
# (Render uses Root Directory: backend, so the context is ./backend — settings.xml
#  lives at backend/.mvn/settings.xml in the repo, copied to /root/.m2/ in the image.)
COPY .mvn/settings.xml /root/.m2/settings.xml

COPY pom.xml .
# Pre-warm the local cache: download all dependencies first (with retries).
# This step is the most likely to hit 429 — retry it up to 3 times.
RUN for i in 1 2 3; do \
      echo "[dependency:go-offline] attempt $i/3" && \
      mvn dependency:go-offline -B -s /root/.m2/settings.xml && break || \
      (echo "[retry] sleeping 15s before next attempt…" && sleep 15); \
    done

COPY src ./src
# Build the actual jar (with retries).
RUN for i in 1 2 3; do \
      echo "[package] attempt $i/3" && \
      mvn package -DskipTests -B -s /root/.m2/settings.xml && break || \
      (echo "[retry] sleeping 15s before next attempt…" && sleep 15); \
    done

# Run stage — minimal JRE
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN mkdir -p /opt/data/files
COPY --from=build /app/target/hydra-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
