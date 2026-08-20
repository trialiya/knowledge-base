/**
 * Фикстуры ответа GET /api/admin/system (SystemInfoController.SystemInfoResponse).
 *
 * На нём строятся группы «Система» и «Очередь эмбеддингов» админ-панели, а также
 * строка с папкой экспорта в «Массовых операциях». Пароля в этих данных нет — и
 * не должно появиться: бэкенд собирает ответ по полям, а URL прогоняет через
 * sanitizeJdbcUrl (см. SystemInfoControllerTest).
 */

/**
 * Локальный запуск на профиле h2 — то, что видно в прогоне из песочницы.
 * URL уже зачищен: у настоящего значения был хвост
 * `;MODE=PostgreSQL;DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE`.
 */
export const h2SystemInfo = {
  application: {
    name: 'kb-demo',
    profiles: ['h2'],
    port: 8080,
    javaVersion: '21.0.10',
    startedAt: '2026-07-27T23:31:35.000Z',
    uptimeSeconds: 14,
  },
  database: {
    url: 'jdbc:h2:./local-db/h2',
    driver: 'org.h2.Driver',
    username: 'knowledgebase',
    flywayLocations: 'classpath:db/migration-h2',
    schemaVersion: '2026.07.27.00',
  },
  git: {
    projectPath: '/home/user/knowledge-base',
    editEnabled: false,
    untrackedEditEnabled: false,
    writable: true,
  },
  documents: {
    exportPath: './doc-export',
    replace: true,
  },
  security: {
    username: 'admin',
  },
  indexing: {
    workers: 4,
    pollBatchSize: 20,
    pollIntervalMs: 1000,
    maxAttempts: 3,
    retryBackoffSeconds: 30,
    stuckTimeoutMinutes: 10,
    stuckCheckMs: 300000,
    cleanupRetentionDays: 7,
    cacheEnabled: true,
    cacheTtlDays: 30,
    cacheCleanupCron: '0 0 2 * * *',
  },
};

/**
 * Postgres-развёртывание с незаданной папкой экспорта — вариант, в котором
 * «Массовые операции» обязаны сказать, что выгружать некуда, до того как
 * пользователь нажмёт кнопку и получит невнятную ошибку.
 */
export const postgresNoExportPath = {
  ...h2SystemInfo,
  application: { ...h2SystemInfo.application, profiles: ['default'] },
  database: {
    url: 'jdbc:postgresql://db.internal:5432/knowledgebase',
    driver: 'org.postgresql.Driver',
    username: 'knowledgebase',
    flywayLocations: 'classpath:db/migration',
    schemaVersion: '2026.07.27.00',
  },
  documents: { exportPath: '', replace: true },
};
