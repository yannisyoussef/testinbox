# syntax=docker/dockerfile:1.7
#
# One definition for all three JVM deployables (ADR-001/029): the API, the
# inbound SMTP gateway and the migration executor differ only in which Gradle
# module they package. Building them from one file keeps the runtime hardening
# identical across them by construction rather than by review, and lets the
# three builds share a dependency-resolution and compilation cache.
#
# Build context is the REPOSITORY ROOT: the backend build composes the JVM SDK
# build (settings.gradle.kts `includeBuild("../sdk/kotlin")`), so a
# backend/-only context cannot resolve.
#
#   docker build -f deploy/docker/backend.Dockerfile --build-arg MODULE=api .

ARG JAVA_VERSION=25

# --- build -------------------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jdk AS build
ARG MODULE
WORKDIR /src

COPY config ./config
COPY sdk/kotlin ./sdk/kotlin
COPY backend ./backend

# The cache mount carries Gradle's dependency cache between builds; there is
# no separate "copy build files first" layer because every module's build file
# is named build.gradle.kts, so a wildcard COPY silently collapses them into
# one — a build that appears to work and packages the wrong module.
# --no-daemon: the daemon would outlive the RUN layer for nothing.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    cd backend && ./gradlew --no-daemon --console=plain ":${MODULE}:bootJar"

# Spring Boot's layered extraction: dependencies change far less often than our
# own code, so keeping them in their own layers keeps pushes and pulls small.
RUN set -eu; \
    jar="$(find "/src/backend/${MODULE}/build/libs" -name '*.jar' ! -name '*-plain.jar' | head -1)"; \
    test -n "$jar" || { echo "no boot jar produced for module ${MODULE}" >&2; exit 1; }; \
    mkdir -p /extracted; cd /extracted; \
    java -Djarmode=tools -jar "$jar" extract --layers --launcher --destination .

# --- runtime -----------------------------------------------------------------
FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine AS runtime
ARG MODULE
ARG GIT_SHA=unknown
ARG BUILD_VERSION=0.1.0-SNAPSHOT

LABEL org.opencontainers.image.source="https://github.com/yannisyoussef/testinbox" \
      org.opencontainers.image.title="testinbox-${MODULE}" \
      org.opencontainers.image.revision="${GIT_SHA}" \
      org.opencontainers.image.version="${BUILD_VERSION}" \
      org.opencontainers.image.licenses="UNLICENSED"

# Non-root, no login shell, nothing writable in the image.
RUN addgroup -S -g 10001 testinbox \
 && adduser -S -u 10001 -G testinbox -H -s /sbin/nologin testinbox

WORKDIR /app
COPY --from=build --chown=root:root /extracted/dependencies/ ./
COPY --from=build --chown=root:root /extracted/spring-boot-loader/ ./
COPY --from=build --chown=root:root /extracted/snapshot-dependencies/ ./
COPY --from=build --chown=root:root /extracted/application/ ./

USER 10001:10001

# MaxRAMPercentage rather than a fixed -Xmx: the JVM reads the *container's*
# limit, so the same image is correctly sized wherever it is scheduled.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Exec form, no shell wrapper: the JVM is PID 1 and receives SIGTERM directly,
# which is what makes Spring Boot's graceful shutdown drain a parked long poll
# rather than have it killed by the container runtime.
ENTRYPOINT ["java", "-cp", ".", "org.springframework.boot.loader.launch.JarLauncher"]
