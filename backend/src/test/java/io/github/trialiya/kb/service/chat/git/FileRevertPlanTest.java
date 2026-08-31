package io.github.trialiya.kb.service.chat.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.git.dto.TextEdit;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

/**
 * План отката собирается из того, что ответ уже записал в историю: плашки вызовов дают путь и
 * исход, {@code tool_data} — аргументы целиком. Здесь проверяется ровно это соответствие и границы
 * «что откатить нельзя».
 */
class FileRevertPlanTest {

    /** Правка отменяется своими же аргументами наоборот — ради этого откат ничего и не хранит. */
    @Test
    void anEditBecomesTheSameReplacementTheOtherWayRound() {
        final FileRevertPlan plan =
                FileRevertPlan.of(
                        List.of(
                                answer(
                                        List.of(
                                                call(
                                                        "call-1",
                                                        "editFile",
                                                        "{\"filePath\":\"src/App.java\",\"oldString\":\"было\",\"newString\":\"стало\"}")),
                                        List.of(edit("call-1", "src/App.java")))));

        assertThat(plan.deletions()).isEmpty();
        assertThat(plan.edits())
                .containsExactly(
                        Map.entry("src/App.java", List.of(new TextEdit("стало", "было", false))));
    }

    /** Несколько правок одного файла отменяются с конца — иначе вторая не найдёт своего текста. */
    @Test
    void severalEditsOfOneFileAreUndoneFromTheLastOne() {
        final FileRevertPlan plan =
                FileRevertPlan.of(
                        List.of(
                                answer(
                                        List.of(
                                                call(
                                                        "call-1",
                                                        "editFile",
                                                        "{\"filePath\":\"a.txt\",\"oldString\":\"один\",\"newString\":\"два\"}"),
                                                call(
                                                        "call-2",
                                                        "editFile",
                                                        "{\"filePath\":\"a.txt\",\"oldString\":\"два\",\"newString\":\"три\",\"replaceAll\":true}")),
                                        List.of(
                                                edit("call-1", "a.txt"),
                                                edit("call-2", "a.txt")))));

        assertThat(plan.edits().get("a.txt"))
                .containsExactly(
                        new TextEdit("три", "два", true), new TextEdit("два", "один", false));
    }

    /**
     * Созданный файл удаляется целиком, и его же правки в план не идут: откатывать в нём нечего.
     */
    @Test
    void aCreatedFileIsDeletedRatherThanEdited() {
        final FileRevertPlan plan =
                FileRevertPlan.of(
                        List.of(
                                answer(
                                        List.of(
                                                call(
                                                        "call-1",
                                                        "createFile",
                                                        "{\"filePath\":\"new.txt\",\"content\":\"x\"}"),
                                                call(
                                                        "call-2",
                                                        "editFile",
                                                        "{\"filePath\":\"new.txt\",\"oldString\":\"x\",\"newString\":\"y\"}")),
                                        List.of(
                                                create("call-1", "new.txt"),
                                                edit("call-2", "new.txt")))));

        // Сверять удаление надо с тем, что ответ оставил на диске: создал «x», тут же поправил
        // на «y» — значит, ожидаем «y», иначе такой файл не откатить никогда.
        assertThat(plan.deletions()).containsExactly(Map.entry("new.txt", "y"));
        assertThat(plan.edits()).isEmpty();
        assertThat(plan.paths()).containsExactly("new.txt");
    }

    /**
     * TOOL-ряды ответа плашек не несут вовсе: мету проставляют одним ASSISTANT-сегментам. Разбор
     * обязан проходить сквозь них, а не падать на первом же.
     */
    @Test
    void rowsWithoutInvocationsAreSkipped() {
        final ChatMessageEntity toolRow =
                new ChatMessageEntity(
                        2,
                        "conv-1",
                        "результат",
                        MessageType.TOOL,
                        2,
                        false,
                        false,
                        LocalDateTime.now(),
                        null);

        assertThat(FileRevertPlan.of(List.of(toolRow)).isEmpty()).isTrue();
    }

    /** Упавший вызов файла не тронул — откатывать по нему нечего. */
    @Test
    void aFailedCallIsNotPartOfThePlan() {
        final ToolInvocationMeta failed =
                new ToolInvocationMeta(
                        "editFile",
                        Map.of(),
                        ToolInvocationStatus.ERROR,
                        "oldString not found",
                        Map.of("path", "a.txt"),
                        true,
                        0,
                        null,
                        "call-1");

        assertThat(
                        FileRevertPlan.of(
                                        List.of(
                                                answer(
                                                        List.of(
                                                                call(
                                                                        "call-1",
                                                                        "editFile",
                                                                        "{\"filePath\":\"a.txt\",\"oldString\":\"нет\",\"newString\":\"да\"}")),
                                                        List.of(failed))))
                                .isEmpty())
                .isTrue();
    }

    /**
     * Пачка {@code runScript} обратимых аргументов не несёт: в истории от неё остаются только
     * diff'ы, а те обрезаны на пятистах строках. Такой ответ откат не трогает вовсе — половина
     * отката хуже отказа.
     */
    @Test
    void aScriptThatChangedFilesMakesTheWholeAnswerNonRevertable() {
        final ToolInvocationMeta script =
                new ToolInvocationMeta(
                        "runScript",
                        Map.of(),
                        ToolInvocationStatus.OK,
                        null,
                        Map.of("edits", List.of(Map.of("path", "a.txt", "operation", "edit"))),
                        true,
                        0,
                        null,
                        "call-1");

        assertThatThrownBy(
                        () ->
                                FileRevertPlan.of(
                                        List.of(
                                                answer(
                                                        List.of(),
                                                        List.of(script, edit("call-2", "b.txt"))))))
                .isInstanceOf(FileRevertRefusedException.class)
                .hasMessageContaining("runScript");
    }

    /** Ответ, записанный версией без {@code callId}: аргументов не найти, и откат честно молчит. */
    @Test
    void anAnswerWithoutCallIdsIsRefused() {
        final ToolInvocationMeta legacy =
                new ToolInvocationMeta(
                        "editFile",
                        Map.of(),
                        ToolInvocationStatus.OK,
                        null,
                        Map.of("path", "a.txt"),
                        true,
                        0,
                        null,
                        null);

        assertThatThrownBy(() -> FileRevertPlan.of(List.of(answer(List.of(), List.of(legacy)))))
                .isInstanceOf(FileRevertRefusedException.class)
                .hasMessageContaining("older version");
    }

    /** Ответ, который файлов не менял, — пустой план, а не отказ: отказывать будет вызывающий. */
    @Test
    void anAnswerThatChangedNoFilesGivesAnEmptyPlan() {
        final ToolInvocationMeta read =
                new ToolInvocationMeta(
                        "getFileContent",
                        Map.of(),
                        ToolInvocationStatus.OK,
                        null,
                        Map.of("path", "a.txt"),
                        true,
                        0,
                        null,
                        "call-1");

        assertThat(FileRevertPlan.of(List.of(answer(List.of(), List.of(read)))).isEmpty()).isTrue();
    }

    private static ChatMessageEntity answer(
            List<ToolData.Call> calls, List<ToolInvocationMeta> invocations) {
        return new ChatMessageEntity(
                1,
                "conv-1",
                "",
                MessageType.ASSISTANT,
                1,
                false,
                false,
                LocalDateTime.now(),
                new ChatMessageMeta("run-1", false, invocations),
                new ToolData(calls, null));
    }

    private static ToolData.Call call(String id, String name, String arguments) {
        return new ToolData.Call(id, "function", name, arguments);
    }

    private static ToolInvocationMeta edit(String callId, String path) {
        return invocation("editFile", callId, path);
    }

    private static ToolInvocationMeta create(String callId, String path) {
        return invocation("createFile", callId, path);
    }

    private static ToolInvocationMeta invocation(String name, String callId, String path) {
        return new ToolInvocationMeta(
                name,
                Map.of(),
                ToolInvocationStatus.OK,
                null,
                Map.of("path", path),
                true,
                0,
                null,
                callId);
    }
}
