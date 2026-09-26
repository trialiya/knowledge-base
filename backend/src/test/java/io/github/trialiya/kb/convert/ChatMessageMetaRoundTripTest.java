package io.github.trialiya.kb.convert;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.ContextItem;
import io.github.trialiya.kb.model.chat.entity.ContextItemKind;
import io.github.trialiya.kb.model.chat.entity.FileRevertMeta;
import io.github.trialiya.kb.model.chat.entity.GitEventMeta;
import io.github.trialiya.kb.model.chat.entity.ProjectSpan;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.chat.entity.ScriptEventMeta;
import io.github.trialiya.kb.model.script.ScriptStats;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Колонка {@code chat_message.meta} ходит через явную проекцию {@code MetaJson}, а не через сам
 * {@link ChatMessageMeta}, поэтому новое поле записи в БД само не поедет: не дописав его в
 * проекцию, получишь поле, которое пишется и читается как {@code null}. Компилятор об этом молчит —
 * молчит и любой тест на моках репозитория.
 *
 * <p>Здесь метаданные со всеми заполненными полями прогоняются запись→чтение целиком. Что поля
 * заполнены все, проверяет сам тест, перебирая компоненты записи: добавили поле, но не в образец —
 * тест упадёт на этой проверке, дописали в образец, но не в проекцию — на сравнении.
 */
class ChatMessageMetaRoundTripTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static ScriptEventMeta scriptEvent() {
        return new ScriptEventMeta(
                "locale-diff",
                "frontend/scripts/locale-diff.js",
                "billing",
                true,
                Map.of("missing", 3),
                null,
                "сверено 12 файлов",
                List.of("frontend/src/i18n/ru/chat.json"),
                new ScriptStats(12, 2048, 30, 1, 420),
                "r3");
    }

    /** Мета, у которой заполнено каждое поле — см. {@link #everyFieldOfTheSampleIsSet}. */
    private static ChatMessageMeta full() {
        return ChatMessageMeta.builder()
                .runId("run-1")
                .toolCalls(true)
                .invocations(
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
                                        "call-0")))
                .contextItems(
                        List.of(
                                new ContextItem(
                                        ContextItemKind.ATTACHMENT,
                                        "7",
                                        "report.md",
                                        Map.of("size", 12))))
                .project("billing")
                .projectSwitchFrom("default")
                .model("deepseek-chat")
                .compact(
                        new CompactMeta(
                                21,
                                4096,
                                512,
                                CompactMeta.Kind.SUMMARIZE,
                                new RunTokenUsage(0, 0, 0, 900, 61_000, 40_000, 0, 61_900, 2)))
                .gitEvent(new GitEventMeta("pull", "billing", true, "Fast-forward", "main"))
                .interjection(true)
                .usage(
                        new RunTokenUsage(
                                12_400, 11_400, 700, 320, 31_000, 24_000, 1_100, 31_320, 3))
                .visitedProjects(
                        List.of(
                                new ProjectSpan("kb", 1, 34),
                                new ProjectSpan("billing", 35, 92),
                                new ProjectSpan("kb", 93, 140)))
                .fileRevert(new FileRevertMeta("billing", List.of("src/App.java", "src/New.java")))
                .scriptEvent(scriptEvent())
                .command(true)
                .build();
    }

    @Test
    void everyFieldOfTheSampleIsSet() {
        for (RecordComponent component : ChatMessageMeta.class.getRecordComponents()) {
            assertThat(isDefault(valueOf(component, full())))
                    .as("ChatMessageMeta.%s is not set in full()", component.getName())
                    .isFalse();
        }
    }

    @Test
    void everyFieldSurvivesWriteThenRead() {
        final ChatMessageMeta meta = full();

        final String json = new ChatMessageMetaToJsonConverter.Writer(objectMapper).convert(meta);
        final ChatMessageMeta read =
                new ChatMessageMetaToJsonConverter.Reader(objectMapper).convert(json);

        assertThat(read).isEqualTo(meta);
    }

    /**
     * Точечные копии ({@code withRun} и соседи) меняют то, что названо, и сохраняют всё остальное:
     * собери такая копия мету заново коротким конструктором — поле, о котором она не знает, пропало
     * бы молча, и ряд потерял бы свою плашку.
     */
    @Test
    void aTargetedCopyKeepsTheFieldsItDoesNotChange() {
        final ChatMessageMeta meta = full();

        assertSameExcept(meta, meta.withRun("run-2", "gpt-5"), "runId", "model");
        assertSameExcept(
                meta, meta.withUsage(new RunTokenUsage(1, 1, 0, 1, 1, 0, 0, 2, 1)), "usage");
        assertSameExcept(
                meta, meta.withProjectTrace("kb", List.of()), "project", "visitedProjects");
        assertSameExcept(
                meta, meta.withProjectSwitch("kb", "billing"), "project", "projectSwitchFrom");
    }

    private static void assertSameExcept(
            ChatMessageMeta before, ChatMessageMeta after, String... changed) {
        final Set<String> skip = Set.of(changed);
        for (RecordComponent component : ChatMessageMeta.class.getRecordComponents()) {
            if (!skip.contains(component.getName())) {
                assertThat(valueOf(component, after))
                        .as("ChatMessageMeta.%s after the copy", component.getName())
                        .isEqualTo(valueOf(component, before));
            }
        }
    }

    private static Object valueOf(RecordComponent component, ChatMessageMeta meta) {
        try {
            return component.getAccessor().invoke(meta);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isDefault(Object value) {
        return value == null
                || Boolean.FALSE.equals(value)
                || (value instanceof List<?> list && list.isEmpty());
    }

    /**
     * Незаполненное поле в колонку не пишется: у большинства рядов заполнено два-три поля из
     * дюжины, а колонка есть у каждого сообщения каждого чата.
     */
    @Test
    void anEmptyFieldIsAbsentFromTheColumnRatherThanWrittenAsNull() {
        final ChatMessageMeta sparse = ChatMessageMeta.ofProject("billing", List.of());

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
                .isEqualTo(ChatMessageMeta.ofProject("billing", List.of()));
    }

    /**
     * Плашка сжатия без вида — это {@code /compact}: других сжатий, когда такие ряды писались, не
     * было. Незнакомый вид читается так же: откат приложения не должен превращаться в отказ читать
     * чат.
     */
    @Test
    void aCompactionNoticeWithoutAKnownKindReadsAsTheUserCommand() {
        final ChatMessageMetaToJsonConverter.Reader reader =
                new ChatMessageMetaToJsonConverter.Reader(objectMapper);

        assertThat(
                        reader.convert(
                                        "{\"compact\":{\"messages\":10,\"summaryChars\":128,"
                                                + "\"summaryId\":7}}")
                                .compact())
                .isEqualTo(new CompactMeta(10, 128, 7, CompactMeta.Kind.COMPACT, null));
        assertThat(
                        reader.convert(
                                        "{\"compact\":{\"messages\":10,\"summaryChars\":128,"
                                                + "\"summaryId\":7,\"kind\":\"TELEPORT\"}}")
                                .compact())
                .isEqualTo(new CompactMeta(10, 128, 7, CompactMeta.Kind.COMPACT, null));
    }
}
