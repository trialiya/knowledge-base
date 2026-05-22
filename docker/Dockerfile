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

# Копируем исходники
COPY backend/src backend/src/
COPY frontend/src frontend/src/
COPY frontend/public frontend/public/

# Собираем bootJar (включает frontend build через :frontend:copyFrontend)
# Кэш-папки определяются настройками node-gradle в frontend/build.gradle:
#   /root/.gradle                        — Gradle зависимости и кэш задач
#   /workspace/frontend/.gradle/nodejs   — бинарник Node (node-gradle workDir)
#   /workspace/frontend/.gradle/yarn     — бинарник Yarn (node-gradle yarnWorkDir)
#   /workspace/frontend/node_modules     — npm/yarn пакеты
RUN --mount=type=cache,target=/root/.gradle \
    --mount=type=cache,target=/workspace/frontend/.gradle/nodejs \
    --mount=type=cache,target=/workspace/frontend/.gradle/yarn \
    --mount=type=cache,target=/workspace/frontend/node_modules \
    ./gradlew :backend:bootJar --no-daemon -x test

# ── Stage 2: Runtime ─────────────────────────────────────────────
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app

RUN apk add --no-cache git curl

ENV PROJECT_PATH=/project
# Разрешаем git работать в /project для всех пользователей.
RUN git config --system safe.directory /project

RUN addgroup -S kb && adduser -S kb -G kb
USER kb

COPY --from=build /workspace/backend/build/libs/*.jar app.jar
COPY --chmod=755 docker/start.sh /start.sh

EXPOSE 8080
ENTRYPOINT ["java"]
CMD ["-jar", "app.jar"]