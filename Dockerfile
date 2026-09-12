# syntax=docker/dockerfile:1.7
# Three stages (S14). The toolchain never reaches the runtime layer: the JDK and Node
# builders are discarded, and what ships has no shell and no package manager.

# ---------------------------------------------------------------- 1. backend
FROM gradle:8.8-jdk21 AS api
WORKDIR /src
# Copy build definitions first so dependency resolution caches across source edits.
COPY settings.gradle.kts build.gradle.kts ./
COPY config/ config/
COPY backend/build.gradle.kts backend/
RUN gradle --no-daemon :backend:dependencies --configuration runtimeClasspath > /dev/null 2>&1 || true
COPY backend/ backend/
RUN gradle --no-daemon :backend:bootJar -x test

# An empty directory, owned by the runtime user, to be copied into the final image as the
# vault mount point. Docker initialises a fresh named volume from whatever the image has at
# that path -- ownership included -- so without this the volume arrives owned by root and a
# non-root process cannot write a key to it. The runtime image is distroless and has no shell,
# so the directory cannot be created there; it has to be built here and carried across.
RUN mkdir -p /vault-skeleton && chown 65532:65532 /vault-skeleton

# --------------------------------------------------------------- 2. frontend
# Node 20: Angular 18's supported range. Pinned here and in CI so the console is never built
# by whatever Node a given workstation happens to have.
FROM node:20-alpine AS web
WORKDIR /src
# Lockfile first, so a source-only change does not re-resolve the dependency tree.
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ .
RUN npm run build -- --configuration production

# ---------------------------------------------------------------- 3. runtime
FROM gcr.io/distroless/java21-debian12:nonroot
COPY --from=api /src/backend/build/libs/qvs.jar /app/qvs.jar
COPY --from=web /src/dist/qvs-console/browser /app/static

# 65532 is the uid of `nonroot` in the distroless base. Named numerically because a COPY
# --chown that cannot resolve a name fails at build time in a confusing way.
COPY --from=api --chown=65532:65532 /vault-skeleton /vault
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
USER nonroot
ENTRYPOINT ["java", "-jar", "/app/qvs.jar"]
