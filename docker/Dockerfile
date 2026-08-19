# ── Stage 1: Build ────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace

# Устанавливаем Node.js и yarn для сборки frontend
RUN apk add --no-cache nodejs npm yarn

# Копируем Gradle wrapper и базовые конфиги
COPY gradlew settings.gradle build.gradle ./
COPY gradle/ gradle/

# Копируем конфигурации подпроектов отдельно для изоляции кэша
COPY backend/build.gradle backend/
COPY frontend/build.gradle frontend/package.json frontend/yarn.lock frontend/
COPY frontend/vite.config.js frontend/postcss.config.js frontend/eslint.config.js frontend/

# Копируем исходники.
# index.html лежит в корне модуля — для Vite это точка входа сборки, а не
# статика (в CRA он был public/index.html).
COPY frontend/index.html frontend/
COPY backend/src backend/src/
COPY frontend/src frontend/src/
COPY frontend/public frontend/public/

# Собираем bootJar (включает frontend build через :frontend:copyFrontend)
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/workspace/frontend/.gradle/nodejs \
    --mount=type=cache,target=/workspace/frontend/.gradle/yarn \
    --mount=type=cache,target=/workspace/frontend/node_modules \
    ./gradlew -PuseSystemNode=true :backend:bootJar --no-daemon -x test

# ── Stage 2: Extract layers ──────────────────────────────────────
# Распаковываем слоистый JAR на отдельные директории по слоям.
FROM eclipse-temurin:25-jre-alpine AS extract
WORKDIR /workspace
COPY --from=build /workspace/backend/build/libs/*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ── Stage 3: Runtime ─────────────────────────────────────────────
FROM eclipse-temurin:25-jre-jammy

# glibc's built-in UTF-8 locale (no locale-gen needed). Without this the JVM falls
# back to sun.jnu.encoding=ANSI_X3.4-1968 (plain ASCII) on this base image, and
# GitService throws "Malformed input or input contains unmappable characters" for
# any non-ASCII path — e.g. every file under docs/проект.
ENV LANG=C.utf8 \
    LC_ALL=C.utf8

RUN apt-get update \
    && apt-get install -y --no-install-recommends git curl \
    && rm -rf /var/lib/apt/lists/*

ENV PROJECT_PATH=/project

# The mount belongs to the host user, the container runs as kb — without this exception git
# refuses the working tree with "dubious ownership". JGit does not check, but `git grep` runs as
# a subprocess (GitService), so it does. Tied to PROJECT_PATH: the exception is one path, and a
# second repository would need its own line here as well as its own mount.
RUN git config --system safe.directory "$PROJECT_PATH"

RUN groupadd --system kb && useradd --system --gid kb kb

# All as root: create /app, create local-db, set ownership
RUN mkdir -p /app/local-db && chown -R kb:kb /app

WORKDIR /app
USER kb

COPY --chmod=755 docker/start.sh /start.sh

COPY --from=extract --chown=kb:kb /workspace/extracted/dependencies/ ./
COPY --from=extract --chown=kb:kb /workspace/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=kb:kb /workspace/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=kb:kb /workspace/extracted/application/ ./

EXPOSE 8080
CMD /start.sh