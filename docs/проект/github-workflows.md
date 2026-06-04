# GitHub Workflows

> **Связанные документы:** [Архитектура](архитектура.md) · [Разработка и контрибьюция](разработка-и-контрибьюция.md) · [Руководство по установке](руководство-по-установке.md)

## Обзор

CI/CD построен на GitHub Actions. Три workflow + Dependabot для автообновления зависимостей.

| Файл | Тип | Назначение |
|---|---|---|
| `.github/dependabot.yml` | Конфиг | Автообновление Gradle, npm, Docker, GitHub Actions |
| `.github/workflows/dependabot-locks.yml` | Workflow | Перегенерация Gradle lock-файлов после PR от Dependabot |
| `.github/workflows/frontend-main-daily.yml` | Workflow | Ежедневная сборка фронта с тестами (только при изменениях) |
| `.github/workflows/frontend-pr.yml` | Workflow | Тесты фронта на каждый PR, затрагивающий `frontend/**` |

---

## 1. Dependabot (`.github/dependabot.yml`)

Автоматически создаёт PR на обновление зависимостей раз в неделю (понедельник).

| Экосистема | Директория | Лимит PR | Лейблы |
|---|---|---|---|
| **gradle** | `/` | 10 | `dependencies`, `backend` |
| **npm** | `/frontend` | 10 | `dependencies`, `frontend` |
| **docker** | `/docker` | — | `dependencies`, `docker` |
| **github-actions** | `/` | — | `dependencies`, `ci` |

**Группировка:** Spring-зависимости (`org.springframework*`) сгруппированы в один PR. Minor и patch-обновления также группируются.

---

## 2. Dependabot Lockfiles (`dependabot-locks.yml`)

**Триггер:** push в ветки `dependabot/gradle/**` (только от бота `dependabot[bot]`).

**Что делает:** после того как Dependabot обновляет версию в `build.gradle`, Gradle lock-файлы (`gradle.lockfile`) могут рассинхронизироваться. Этот workflow запускает `./gradlew resolveAndLockAll --write-locks` и коммитит обновлённые lock-файлы в ту же ветку.

**Права:** `contents: write` (для коммита и пуша).

---

## 3. Frontend Main Daily (`frontend-main-daily.yml`)

**Триггеры:**
- **Расписание:** каждый день в 18:00 UTC (`cron: '0 18 * * *'`)
- **Ручной запуск:** `workflow_dispatch` (собирает всегда, без проверки изменений)

**Concurrency:** `frontend-main-daily` — не отменяет предыдущий запуск (`cancel-in-progress: false`).

### Job: detect (проверка изменений)

Определяет, менялись ли файлы в `frontend/` с момента последней успешной сборки:

1. При ручном запуске (`workflow_dispatch`) — всегда `changed=true`
2. Ищет SHA последнего успешного запуска этого workflow на ветке `main` через `gh run list`
3. Если предыдущей сборки нет — `changed=true`
4. Если коммит не найден в истории (force-push) — `changed=true`
5. Сравнивает через `git diff --quiet $last_sha HEAD -- frontend/`:
   - Нет изменений → `changed=false` (пропускаем сборку)
   - Есть изменения → `changed=true`

### Job: build (сборка и тесты)

Запускается только если `detect.changed == 'true'`.

**Шаги:**
1. `actions/checkout@v6`
2. `actions/setup-java@v5` — Temurin JDK 25
3. `gradle/actions/setup-gradle@v5`
4. Кэширование `node_modules`, `.gradle/nodejs`, `.gradle/yarn` через `actions/cache@v4`
5. `./gradlew :frontend:build` — полный цикл: `yarnInstall` → `yarnTest` → `yarnBuild` → `copyFrontend` + `spotlessCheck`
6. Загрузка артефакта `frontend/build` (retention: 7 дней)

**Переменные окружения:**
| Переменная | Значение |
|---|---|
| `FRONTEND_DIR` | `frontend` |
| `FRONTEND_MODULE` | `:frontend` |
| `NODE_VERSION` | `22.22.3` |

---

## 4. Frontend PR (`frontend-pr.yml`)

**Триггер:** Pull Request в `main`, затрагивающий пути `frontend/**` или сам workflow-файл.

**Concurrency:** `frontend-pr-{номер_PR}` — новый пуш в ту же ветку отменяет предыдущий прогон (`cancel-in-progress: true`).

### Job: test

**Шаги:**
1. `actions/checkout@v6`
2. `actions/setup-node@v6` — Node.js 22.22.3, кэш yarn по `frontend/yarn.lock`
3. `yarn install --frozen-lockfile`
4. `yarn test --watchAll=false --passWithNoTests` (с `CI=true`)

**Отличие от daily-сборки:** только тесты (без Gradle, без сборки CRA). Быстрее, так как не требует JDK и полного backend-окружения.

---

## 5. Общая схема CI/CD

```
Dependabot (weekly)
  │
  ├─ PR: Gradle deps ──► dependabot-locks.yml (regenerate lockfiles)
  ├─ PR: npm deps
  ├─ PR: Docker deps
  └─ PR: Actions deps
        │
        ▼
   PR в main (frontend/**) ──► frontend-pr.yml (yarn test)
        │
        ▼
   merge в main
        │
        ▼
   frontend-main-daily.yml (cron: daily, только при изменениях)
        │
        ▼
   :frontend:build (yarnInstall → yarnTest → yarnBuild → copyFrontend)
```
