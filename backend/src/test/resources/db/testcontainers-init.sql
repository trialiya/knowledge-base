-- Расширения, которые в проде ставит docker/init.sql до старта приложения.
-- Testcontainers выполняет этот скрипт сразу после инициализации БД и ДО Flyway,
-- поэтому миграции из db/migration (vector(1024), gist_trgm_ops) применяются корректно.
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;
