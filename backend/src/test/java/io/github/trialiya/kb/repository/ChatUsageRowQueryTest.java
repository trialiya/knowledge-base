package io.github.trialiya.kb.repository;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.CommonConfig;
import io.github.trialiya.kb.config.H2JdbcConfig;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatUsageRow;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Проекция рядов для счёта токенов против настоящей схемы.
 *
 * <p>Мок репозитория с удовольствием сделал бы вид, что всё это работает: {@link ChatUsageRow} — не
 * корень агрегата, тип ряда приезжает строкой, а мета — JSON, который поднимает пользовательский
 * конвертер. Итог по чату целиком стоит на этом одном запросе, и молчаливый сбой маппинга унёс бы
 * его весь.
 */
@ActiveProfiles("h2")
@DataJdbcTest(
        properties = {
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.url=jdbc:h2:mem:kb-usage-row-test;MODE=PostgreSQL;"
                    + "DEFAULT_NULL_ORDERING=HIGH;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "spring.flyway.locations=classpath:db/migration-h2",
            "spring.data.jdbc.dialect=postgresql",
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({CommonConfig.class, H2JdbcConfig.class})
class ChatUsageRowQueryTest {

    private static final String CONV = "conv-usage";

    private static final RunTokenUsage MEASURED =
            new RunTokenUsage(12_400, 11_400, 700, 320, 31_000, 24_000, 1_100, 31_320, 3);

    @Autowired private ChatMessageRepository repo;

    @Test
    void mapsTypeAndMetaOfEveryRow() {
        save("Вопрос", MessageType.USER, 0, false, null);
        save("Ответ", MessageType.ASSISTANT, 1, false, ChatMessageMeta.ofUsage(MEASURED));

        final List<ChatUsageRow> rows = repo.findUsageRows(CONV);

        assertThat(rows)
                .extracting(ChatUsageRow::type)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT);
        assertThat(rows.getFirst().meta()).isNull();
        assertThat(rows.getLast().meta()).isNotNull();
        assertThat(rows.getLast().meta().usage()).isEqualTo(MEASURED);
    }

    /** Сводки в счёт не идут: их замер стоит на плашке, а сама сводка ленты не касается. */
    @Test
    void skipsSummaryRows() {
        save("Ответ", MessageType.ASSISTANT, 0, false, ChatMessageMeta.ofUsage(MEASURED));
        save("Сводка", MessageType.ASSISTANT, 1, true, ChatMessageMeta.ofUsage(MEASURED));

        assertThat(repo.findUsageRows(CONV)).hasSize(1);
    }

    @Test
    void readsOnlyTheAskedConversation() {
        save("Ответ", MessageType.ASSISTANT, 0, false, ChatMessageMeta.ofUsage(MEASURED));

        assertThat(repo.findUsageRows("другой-чат")).isEmpty();
    }

    private void save(
            String content,
            MessageType type,
            long position,
            boolean summary,
            ChatMessageMeta meta) {
        repo.save(
                new ChatMessageEntity(
                        0L,
                        CONV,
                        content,
                        type,
                        position,
                        false,
                        summary,
                        LocalDateTime.now().plusSeconds(position),
                        meta));
    }
}
