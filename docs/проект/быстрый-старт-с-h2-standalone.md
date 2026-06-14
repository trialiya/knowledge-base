# Быстрый старт с H2 (standalone)

> **Связанные документы:** [Руководство по установке](руководство-по-установке.md) · [Конфигурация](конфигурация.md) · [Введение](введение.md)

## Что это

H2-профиль позволяет запустить Knowledge Base **без PostgreSQL** — всё, что нужно, уже внутри Docker-образа. Встроенная H2 работает в файловом режиме (PostgreSQL-совместимый диалект), данные сохраняются в Docker volume. Семантический поиск отключён, но keyword-поиск и весь остальной функционал работают.

**Идеально для:** первого знакомства, демо, тестирования.

## Быстрый старт (3 шага)

### Шаг 1: Клонируйте репозиторий

```bash
git clone <repo-url>
cd <project-root>
```

### Шаг 2: Настройте .env

```bash
cd docker
cp example.env .env
```

Отредактируйте `.env` — укажите `AI_API_KEY`, `AI_BASE_URL` и `AI_MODEL` (обязателен для чата). `PROJECT_PATH_MOUNT` по умолчанию указывает на родительскую директорию.

### Шаг 3: Запустите

```bash
docker compose -f docker-compose-h2.yaml up
```

Готово! Приложение доступно на **http://localhost:8080**.

## Что внутри

| Компонент | Статус |
|---|---|
| **База знаний** (дерево, документы, папки) | ✅ Работает |
| **Чат с AI** | ✅ Требует AI_API_KEY |
| **Keyword-поиск** | ✅ Работает |
| **Семантический поиск** | ❌ Отключён (нет pgvector) |
| **Git-интеграция** | ✅ Работает (read-only) |
| **Экспорт документов** | ✅ Работает |
| **Jira / Confluence** | ❌ Требуют токенов |
| **H2-консоль** | ✅ http://localhost:8080/h2-console |

## H2-консоль

Для отладки доступна веб-консоль H2:

- **URL:** http://localhost:8080/h2-console
- **JDBC URL:** `jdbc:h2:file:./local-db/h2`
- **Username:** `knowledgebase`
- **Password:** `knowledgebase`

## Где хранятся данные

Данные H2 хранятся в Docker volume `kb-h2-data` (файл `h2.mv.db` внутри контейнера по пути `/app/local-db`). При перезапуске контейнера данные сохраняются.

Чтобы полностью сбросить данные:

```bash
docker compose -f docker-compose-h2.yaml down -v
```

## Ограничения

- **Нет семантического поиска** — только keyword-поиск (по названиям и содержимому)
- **H2, не PostgreSQL** — некоторые продвинутые фичи PostgreSQL (pgvector, pg_trgm) недоступны
- **Однопользовательский режим** — H2 в файловом режиме не рассчитана на высокую конкурентность

## Переход на полный стек

Когда понадобится семантический поиск — переключитесь на PostgreSQL:

```bash
# Остановите H2
docker compose -f docker-compose-h2.yaml down

# Запустите полный стек (AI_API_KEY уже в .env)
docker compose up -d
```

> Подробнее — см. [Руководство по установке](руководство-по-установке.md), вариант A.

## Локальный запуск с H2 (без Docker)

```bash
export SPRING_PROFILES_ACTIVE=h2; \
export AI_BASE_URL=https://api.openai.com/; \
export AI_API_KEY=your-key; \
export AI_MODEL=gpt-4o; \
PROJECT_PATH=./; \
./gradlew :backend:bootRun
```

Приложение будет доступно на `http://localhost:8080`, данные H2 — в `backend/local-db/`.

> Подробнее — см. [Руководство по установке](руководство-по-установке.md), раздел 3.

## Запуск из собранного JAR (без Docker)

Соберите исполняемый JAR (фронтенд встраивается автоматически) и запустите его напрямую:

```bash
# 1. Сборка
./gradlew :backend:bootJar

# 2. Запуск с H2-профилем
SPRING_PROFILES_ACTIVE=h2 \
AI_BASE_URL=https://api.openai.com/ \
AI_API_KEY=your-key \
AI_MODEL=gpt-4o \
PROJECT_PATH=./ \
java -jar backend/build/libs/backend-1.0-SNAPSHOT.jar
```

Готовый артефакт — `backend/build/libs/backend-1.0-SNAPSHOT.jar`, данные H2 — в каталоге `local-db/` рядом с процессом. Требуется JDK/JRE 25.

> Подробнее — см. [Руководство по установке](руководство-по-установке.md), раздел 4.
