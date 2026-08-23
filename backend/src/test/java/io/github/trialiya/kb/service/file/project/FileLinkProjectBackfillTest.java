package io.github.trialiya.kb.service.file.project;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ProjectProperties;
import io.github.trialiya.kb.config.model.ProjectProperties.ProjectOption;
import io.github.trialiya.kb.repository.BackfillStateRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

/**
 * Бэкафилл против настоящей схемы и настоящих данных: {@code sample-data.sql} — захваченный чат с
 * документами, в котором ссылки на файлы уже есть. Проверяется то, что нельзя проверить на строке в
 * памяти: что SQL попадает в те колонки, что маркер делает повтор no-op'ом, и что строки без ссылок
 * не переписываются.
 */
@ActiveProfiles("h2")
@DataJdbcTest(
        properties = {
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.url=jdbc:h2:mem:kb-file-link-backfill-test;MODE=PostgreSQL;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.flyway.locations=classpath:db/migration-h2",
            "spring.data.jdbc.dialect=postgresql",
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(CommonConfig.class)
@Sql("/db/sample-data.sql")
class FileLinkProjectBackfillTest {

    @Autowired private JdbcTemplate jdbc;
    @Autowired private BackfillStateRepository backfillStateRepository;

    private FileLinkProjectBackfillService service;

    @BeforeEach
    void setUp() {
        ProjectCatalog catalog =
                new ProjectCatalog(
                        new ProjectProperties(
                                List.of(
                                        new ProjectOption(
                                                "kb", "KB", "/srv/kb", false, false, null, true))),
                        new GitProperties(null));
        service = new FileLinkProjectBackfillService(jdbc, backfillStateRepository, catalog);
    }

    @Test
    void everyStoredLinkEndsUpNamingTheProject() {
        assertThat(linksWithoutProject()).isPositive();

        int updated = service.stampProjectInStoredLinksIfNeeded();

        assertThat(updated).isPositive();
        assertThat(linksWithoutProject()).isZero();
        assertThat(count("chat_message", "content LIKE '%&project=kb%'")).isPositive();
    }

    /** Маркер стоит в той же транзакции — второй старт приложения историю не перечитывает. */
    @Test
    void theSecondRunIsANoOp() {
        service.stampProjectInStoredLinksIfNeeded();

        assertThat(service.stampProjectInStoredLinksIfNeeded()).isZero();
        assertThat(backfillStateRepository.existsById(FileLinkProjectBackfillService.KEY)).isTrue();
        assertThat(count("chat_message", "content LIKE '%&project=kb&project=kb%'")).isZero();
    }

    /** Прогон поверх уже проставленных ссылок ничего не трогает — даже без маркера. */
    @Test
    void stampingTwiceOverTheSameRowsChangesNothingTheSecondTime() {
        assertThat(service.stampProjectInStoredLinks("kb")).isPositive();

        assertThat(service.stampProjectInStoredLinks("kb")).isZero();
    }

    private int linksWithoutProject() {
        return count(
                        "chat_message",
                        "content LIKE '%/files?path=%' AND content NOT LIKE '%&project=%'")
                + count(
                        "documents",
                        "description LIKE '%/files?path=%' AND description NOT LIKE '%&project=%'");
    }

    private int count(String table, String where) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM " + table + " WHERE " + where, Integer.class);
        return count == null ? 0 : count;
    }
}
