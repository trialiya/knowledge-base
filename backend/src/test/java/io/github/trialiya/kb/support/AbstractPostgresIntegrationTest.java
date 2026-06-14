package io.github.trialiya.kb.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Базовый класс для интеграционных тестов на «настоящем» PostgreSQL.
 *
 * <p>Поднимает один общий контейнер {@code pgvector/pgvector:pg17} (тот же образ, что и в
 * docker-compose.yaml) на весь JVM и переиспользует его между тестовыми классами — это паттерн
 * singleton-container из документации Testcontainers, он заметно быстрее, чем поднимать контейнер
 * на каждый класс. Контейнер останавливает Ryuk при завершении JVM.
 *
 * <p>В отличие от H2-тестов ({@code DocumentServiceUnitTest}), здесь применяются РЕАЛЬНЫЕ миграции
 * из {@code db/migration} — с {@code vector(1024)}, GiST-индексами по {@code gist_trgm_ops}, {@code
 * bigserial}/{@code identity} и {@code IS NOT DISTINCT FROM}. Это единственный способ проверить
 * SQL, который на H2 либо не существует, либо ведёт себя иначе.
 *
 * <p>Логин/пароль/имя БД совпадают с продовыми ({@code knowledgebase}), чтобы отрабатывал, в
 * частности, {@code ALTER TABLE attachments OWNER TO knowledgebase} из V1.
 */
public abstract class AbstractPostgresIntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(
                            DockerImageName.parse("pgvector/pgvector:pg17")
                                    .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("knowledgebase")
                    .withUsername("knowledgebase")
                    .withPassword("knowledgebase")
                    // ставит расширения vector + pg_trgm ДО запуска Flyway
                    .withInitScript("db/testcontainers-init.sql");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // прогоняем именно постгресовые миграции, а не migration-h2
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration");
        registry.add("spring.flyway.validate-on-migrate", () -> "false");
    }
}
