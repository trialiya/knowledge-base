package io.github.trialiya.kb.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.ContextItemKind;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Колонка {@code chat_message.meta} ходит через явную проекцию {@code MetaJson}, а не через сам
 * {@link ChatMessageMeta}, поэтому новое поле записи в БД само не поедет: не дописав его в
 * проекцию, получишь поле, которое пишется и читается как {@code null}. Компилятор об этом молчит —
 * молчит и любой тест на моках репозитория.
 *
 * <p>Здесь метаданные собираются позиционным (каноническим) конструктором и прогоняются
 * запись→чтение целиком. Добавили поле — тест перестанет компилироваться, дописали его сюда, но не
 * в проекцию — тест упадёт на сравнении.
 */
class ChatMessageMetaRoundTripTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void everyFieldSurvivesWriteThenRead() {
        final ChatMessageMeta meta =
                new ChatMessageMeta(
                        "run-1",
                        true,
                        List.of(
                                new ToolInvocationMeta(
                                        "searchDocuments",
                                        Map.of("q", "запрос"),
                                        ToolInvocationStatus.OK,
                                        null,
                                        Map.of("id", 7),
                                        true,
                                        3,
                                        "гист",
                                        "call-0")),
                        List.of(
                                new ContextItem(
                                        ContextItemKind.ATTACHMENT,
                                        "7",
                                        "report.md",
                                        Map.of("size", 12))),
                        "billing",
                        "default",
                        "deepseek-chat",
                        new CompactMeta(21, 4096, 512),
                        new GitEventMeta("pull", "billing", true, "Fast-forward", "main"));

        final String json = new ChatMessageMetaToJsonConverter.Writer(objectMapper).convert(meta);
        final ChatMessageMeta read =
                new ChatMessageMetaToJsonConverter.Reader(objectMapper).convert(json);

        assertThat(read).isEqualTo(meta);
    }

    /**
     * Незаполненное поле в колонку не пишется. Это не только про размер: по подстроке в {@code
     * meta} ходит запрос ({@code ChatMessageRepository#findProjectSwitches}), и выписанный {@code
     * null} для него неотличим от настоящего значения.
     */
    @Test
    void anEmptyFieldIsAbsentFromTheColumnRatherThanWrittenAsNull() {
        final ChatMessageMeta sparse = ChatMessageMeta.ofProject("billing");

        final String json = new ChatMessageMetaToJsonConverter.Writer(objectMapper).convert(sparse);

        assertThat(json).contains("\"project\":\"billing\"").doesNotContain("null");
        assertThat(new ChatMessageMetaToJsonConverter.Reader(objectMapper).convert(json))
                .isEqualTo(sparse);
    }

    /**
     * Ряды, записанные с выписанными {@code null}, лежат в базе и читаются наравне: отсутствующее
     * поле и поле-{@code null} означают одно и то же.
     */
    @Test
    void aRowThatSpellsOutItsNullsReadsTheSame() {
        final String withNulls =
                "{\"runId\":null,\"toolCalls\":false,\"invocations\":[],\"contextItems\":null,"
                        + "\"project\":\"billing\",\"projectSwitchFrom\":null,\"model\":null,"
                        + "\"compact\":null}";

        assertThat(new ChatMessageMetaToJsonConverter.Reader(objectMapper).convert(withNulls))
                .isEqualTo(ChatMessageMeta.ofProject("billing"));
    }
}
