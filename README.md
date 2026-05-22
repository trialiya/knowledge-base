# Knowledge Base

**Knowledge Base** — это веб-приложение для управления базой знаний с возможностью интеллектуального поиска и чат-ассистента на основе ИИ.

## Цель проекта

Создать удобный инструмент для хранения, организации и поиска документов (в формате Markdown) в иерархической структуре папок, дополненный AI-ассистентом, который отвечает на вопросы на основе содержимого базы знаний.

## Возможности

- **Иерархическая база знаний** — папки и документы с древовидной структурой
- **Markdown-редактор** — создание и редактирование документов прямо в интерфейсе
- **Гибридный поиск** — комбинация keyword + semantic (векторный) поиск по содержимому
- **AI-чат** — ассистент на базе OpenAI-совместимого API, отвечающий с учётом контекста базы знаний
- **Экспорт документов** — выгрузка базы знаний в файловую систему
- **Docker** — готовый compose-файл для быстрого развёртывания

## Стек технологий

| Компонент    | Технологии                                                         |
|--------------|--------------------------------------------------------------------|
| **Бэкенд**   | Java 25, Spring Boot 3.5, Spring AI, PostgreSQL 17 + pgvector      |
| **Фронтенд** | React 19, CSS                                                      |
| **Поиск**    | Гибридный (keyword + векторные эмбеддинги)                         |
| **Сборка**   | Gradle (мультипроект), Node.js 22, Yarn 1.22                       |
| **Инфра**    | Docker, docker-compose                                              |

## Быстрый старт

### Запуск через Docker (рекомендуется)

```bash
cd docker
cp example.env .env
# Отредактируйте .env — укажите AI_API_KEY, AI_EMBED_API_KEY и PROJECT_PATH_MOUNT
docker compose up -d
```

Приложение будет доступно на `http://localhost:8080`.

### Локальный запуск (для разработки)

1. Убедитесь, что PostgreSQL 17 + pgvector запущен (по умолчанию `localhost:5432`, БД `knowledgebase`)
2. Скопируйте `docker/example.env` в `.env` в корне проекта и укажите свои ключи
3. Запустите backend (frontend соберётся автоматически через Gradle):

   ```bash
   ./gradlew :backend:bootJar
   ./gradlew :backend:bootRun
   ```

   Или для разработки с hot-reload фронта:

   ```bash
   # Терминал 1: backend
   ./gradlew :backend:bootRun
   # Терминал 2: frontend (отдельный dev-сервер на localhost:3000)
   ./gradlew:frontend:yarnServe
   ```

## Конфигурация

Все настройки задаются через переменные окружения (см. `docker/example.env`):

| Переменная              | Описание                                      | По умолчанию                                     |
|-------------------------|-----------------------------------------------|--------------------------------------------------|
| `AI_BASE_URL`           | Базовый URL OpenAI-совместимого API для чата  | `https://api.openai.com/`                        |
| `AI_API_KEY`            | API-ключ для чата                             | —                                                |
| `AI_MODEL`              | Модель для чата                               | `deepseek-v4-flash`                              |
| `AI_EMBED_BASE_URL`     | Базовый URL для эмбеддингов (если отличается) | `${AI_BASE_URL}`                                 |
| `AI_EMBED_API_KEY`      | API-ключ для эмбеддингов (если отличается)    | `${AI_API_KEY}`                                  |
| `AI_EMBED_MODEL`        | Модель эмбеддингов (1024-dim)                 | `bge-m3`                                         |
| `DATASOURCE_URL`        | JDBC URL для PostgreSQL                       | `jdbc:postgresql://localhost:5432/knowledgebase` |
| `DATASOURCE_USERNAME`   | Пользователь БД                               | `knowledgebase`                                  |
| `DATASOURCE_PASSWORD`   | Пароль БД                                     | `knowledgebase`                                  |
| `PROJECT_PATH`          | Путь к git-репозиторию на диске               | —                                                |
| `DOCUMENTS_EXPORT_PATH` | Путь для экспорта документов                  | —                                                |
| `JIRA_BASE_URL`         | Базовый URL Jira (опционально)                | —                                                |
| `JIRA_TOKEN`            | API-токен Jira (опционально)                  | —                                                |
| `CONFLUENCE_BASE_URL`   | Базовый URL Confluence (опционально)          | —                                                |
| `CONFLUENCE_TOKEN`      | API-токен Confluence (опционально)            | —                                                |
