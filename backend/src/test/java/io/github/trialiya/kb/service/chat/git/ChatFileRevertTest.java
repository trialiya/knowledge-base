package io.github.trialiya.kb.service.chat.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.FileRevertPayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.FileRevertMeta;
import io.github.trialiya.kb.model.project.Project;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.git.GitService;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Откат правок ответа целиком: заявка на чат, всё-или-ничего при записи, ряд истории и событие
 * подписчикам. Сам разбор ответа проверяет {@link FileRevertPlanTest}, работу с деревом — {@code
 * GitServiceRevertTest}; здесь мокнуто и то и другое.
 */
class ChatFileRevertTest {

    private static final String CONV = "conv-1";

    private final ChatGitLog chatGitLog = mock(ChatGitLog.class);
    private final ChatHistoryService chatHistory = mock(ChatHistoryService.class);
    private final ChatEventService chatEvents = mock(ChatEventService.class);
    private final GitRegistry gitRegistry = mock(GitRegistry.class);
    private final GitService git = mock(GitService.class);

    private final ChatFileRevert revert =
            new ChatFileRevert(chatGitLog, chatHistory, chatEvents, gitRegistry);

    @BeforeEach
    void setUp() {
        when(chatGitLog.claimIdleAndOwned(CONV)).thenReturn("claim-1");
        when(gitRegistry.requireEditable(anyString())).thenReturn(git);
        when(git.project()).thenReturn(project());
    }

    /** Успешный откат: файл возвращается, ряд пишется, событие уходит, заявка снимается. */
    @Test
    void aRevertWritesTheFileBackAndLeavesARowWithTheEvent() {
        givenAnswer(editCall("call-1", "a.txt", "было", "стало"));
        when(git.previewEdited(eq("a.txt"), any())).thenReturn("было\n");
        when(chatHistory.appendFileRevert(eq(CONV), any())).thenReturn(revertRow());

        final FileRevertPayload payload = revert.revertLastAnswer(CONV, "kb");

        verify(git).replaceTrackedFile("a.txt", "было\n");
        assertThat(payload.event().paths()).containsExactly("a.txt");
        assertThat(payload.event().project()).isEqualTo("kb");
        verify(chatEvents).publish(eq(CONV), eq(ChatEventType.FILE_REVERT), any(), any(), any());
        verify(chatGitLog).release(CONV, "claim-1");
    }

    /** Созданный файл удаляется с тем содержимым, с которым его создали, — оно и есть сверка. */
    @Test
    void aCreatedFileIsDeletedWithTheContentItWasCreatedWith() {
        givenAnswer(createCall("call-1", "new.txt", "class New {}"));
        when(chatHistory.appendFileRevert(eq(CONV), any())).thenReturn(revertRow());

        revert.revertLastAnswer(CONV, "kb");

        verify(git).requireDeletable("new.txt", "class New {}");
        verify(git).deleteFile("new.txt", "class New {}");
    }

    /**
     * Половина отката хуже отказа: не сошёлся один файл — не пишется ни один, включая тот, что
     * пересчитался успешно.
     */
    @Test
    void nothingIsWrittenWhenOneOfTheFilesNoLongerMatches() {
        givenAnswer(
                editCall("call-1", "a.txt", "было", "стало"),
                editCall("call-2", "b.txt", "x", "y"));
        when(git.previewEdited(eq("a.txt"), any())).thenReturn("было\n");
        when(git.previewEdited(eq("b.txt"), any()))
                .thenThrow(new IllegalArgumentException("oldString not found in b.txt"));

        assertThatThrownBy(() -> revert.revertLastAnswer(CONV, "kb"))
                .isInstanceOf(FileRevertRefusedException.class)
                .hasMessageContaining("b.txt");

        verify(git, never()).replaceTrackedFile(anyString(), anyString());
        verify(chatHistory, never()).appendFileRevert(anyString(), any());
        verify(chatGitLog).release(CONV, "claim-1");
    }

    /** Второй откат того же ответа — отказ по своему же ряду, а не «файл изменился». */
    @Test
    void anAnswerAlreadyRevertedIsRefusedByItsOwnRow() {
        when(chatHistory.lastAnswerRows(CONV))
                .thenReturn(List.of(answer(List.of(), List.of()), revertRow()));

        assertThatThrownBy(() -> revert.revertLastAnswer(CONV, "kb"))
                .isInstanceOf(FileRevertRefusedException.class)
                .hasMessageContaining("already been reverted");
    }

    /** Ответу без файловых правок откатывать нечего — и это отказ, а не пустой успех. */
    @Test
    void anAnswerThatChangedNoFilesIsRefused() {
        when(chatHistory.lastAnswerRows(CONV)).thenReturn(List.of(answer(List.of(), List.of())));

        assertThatThrownBy(() -> revert.revertLastAnswer(CONV, "kb"))
                .isInstanceOf(FileRevertRefusedException.class)
                .hasMessageContaining("changed no files");
    }

    private void givenAnswer(Call... calls) {
        when(chatHistory.lastAnswerRows(CONV))
                .thenReturn(
                        List.of(
                                answer(
                                        List.of(calls).stream().map(Call::call).toList(),
                                        List.of(calls).stream().map(Call::meta).toList())));
    }

    private record Call(ToolData.Call call, ToolInvocationMeta meta) {}

    private static Call editCall(String id, String path, String oldString, String newString) {
        return new Call(
                new ToolData.Call(
                        id,
                        "function",
                        "editFile",
                        "{\"filePath\":\""
                                + path
                                + "\",\"oldString\":\""
                                + oldString
                                + "\",\"newString\":\""
                                + newString
                                + "\"}"),
                new ToolInvocationMeta(
                        "editFile",
                        Map.of(),
                        ToolInvocationStatus.OK,
                        null,
                        Map.of("path", path),
                        true,
                        0,
                        null,
                        id));
    }

    private static Call createCall(String id, String path, String content) {
        return new Call(
                new ToolData.Call(
                        id,
                        "function",
                        "createFile",
                        "{\"filePath\":\"" + path + "\",\"content\":\"" + content + "\"}"),
                new ToolInvocationMeta(
                        "createFile",
                        Map.of(),
                        ToolInvocationStatus.OK,
                        null,
                        Map.of("path", path),
                        true,
                        0,
                        null,
                        id));
    }

    private static ChatMessageEntity answer(
            List<ToolData.Call> calls, List<ToolInvocationMeta> invocations) {
        return new ChatMessageEntity(
                1,
                CONV,
                "",
                MessageType.ASSISTANT,
                1,
                false,
                false,
                LocalDateTime.now(),
                new ChatMessageMeta("run-1", false, invocations),
                new ToolData(calls, null));
    }

    private static ChatMessageEntity revertRow() {
        return new ChatMessageEntity(
                2,
                CONV,
                "",
                MessageType.USER,
                2,
                false,
                false,
                LocalDateTime.now(),
                ChatMessageMeta.ofFileRevert(new FileRevertMeta("kb", List.of("a.txt"))));
    }

    private static Project project() {
        return new Project("kb", "KB", Path.of("/tmp/kb"), true, false, List.of(), true, false);
    }
}
