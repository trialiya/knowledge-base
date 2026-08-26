package io.github.trialiya.kb.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.git.dto.GitBranchStatus;
import io.github.trialiya.kb.model.git.dto.GitCommandResult;
import io.github.trialiya.kb.service.chat.git.ChatGitLog;
import io.github.trialiya.kb.service.file.git.GitBusyException;
import io.github.trialiya.kb.service.file.git.GitCommandFailedException;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.git.GitService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Что даёт команде параметр {@code chat} и, главное, чего он не даёт.
 *
 * <p>Три правила, на которых держится вся чат-половина фичи: допуск проверяется до того, как
 * что-либо будет выполнено; в историю попадает то, что произошло, — включая отказ git'а; отказы,
 * при которых репозиторий не трогали, следа не оставляют. Без {@code chat} не меняется ничего:
 * панель «Файлы» ходит теми же эндпоинтами.
 */
class GitCommandChatTest {

    private static final String CHAT = "conv-1";

    private final GitRegistry gitRegistry = mock(GitRegistry.class);
    private final ChatGitLog chatGitLog = mock(ChatGitLog.class);
    private final GitService git = mock(GitService.class);

    private final GitCommandController controller =
            new GitCommandController(gitRegistry, chatGitLog);

    private static final GitBranchStatus AFTER =
            new GitBranchStatus(
                    "main",
                    false,
                    false,
                    "origin/main",
                    0,
                    0,
                    List.of("main"),
                    false,
                    false,
                    List.of());

    @BeforeEach
    void permitEverything() {
        when(gitRegistry.requireGitCommands(any())).thenReturn(git);
        when(gitRegistry.requireGitPush(any())).thenReturn(git);
    }

    /** Без чата всё как было: ни допуска, ни записи — панель «Файлы» этого пути не касается. */
    @Test
    void withoutAChatTheCommandKnowsNothingAboutChats() {
        when(git.fetch()).thenReturn(new GitCommandResult("fetch", "", AFTER));

        controller.fetch("kb", null);

        verifyNoInteractions(chatGitLog);
    }

    /**
     * Допуск — до команды. Отказ занятого или чужого чата обязан оставить рабочее дерево
     * нетронутым: иначе pull успевал бы пройти, а пользователь видел бы ошибку.
     */
    @Test
    void aRefusedChatStopsTheCommandBeforeItRuns() {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "busy"))
                .when(chatGitLog)
                .requireIdleAndOwned(CHAT);

        assertThatThrownBy(() -> controller.pull("kb", CHAT))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verifyNoInteractions(git);
    }

    /** Успех записывается командой в том виде, в каком её выполнил git. */
    @Test
    void aSucceededCommandIsRecordedWithTheBranchItLeftBehind() {
        when(git.pull()).thenReturn(new GitCommandResult("pull --ff-only", "Fast-forward", AFTER));

        controller.pull("kb", CHAT);

        verify(chatGitLog).record(CHAT, "pull --ff-only", "kb", true, "Fast-forward", "main");
    }

    /**
     * Отказ git'а — тоже исход, и он записывается: пользователю нужно прочитать причину ещё раз,
     * модели — не считать отклонённый push опубликованным.
     */
    @Test
    void gitOwnRefusalIsRecordedToo() {
        when(git.push()).thenThrow(new GitCommandFailedException("remote rejected"));

        assertThatThrownBy(() -> controller.push("kb", CHAT))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e ->
                                assertThat(e.getStatusCode())
                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));

        verify(chatGitLog).record(CHAT, "push", "kb", false, "remote rejected", null);
    }

    /**
     * Занятый репозиторий — не исход, а «попробуйте ещё раз»: выполнять было нечего, и писать в
     * историю тоже.
     */
    @Test
    void aBusyRepositoryLeavesNoTrace() {
        when(git.stashPush()).thenThrow(new GitBusyException("another command is running"));

        assertThatThrownBy(() -> controller.stashPush("kb", CHAT))
                .isInstanceOf(ResponseStatusException.class);

        verify(chatGitLog, never())
                .record(anyString(), anyString(), any(), anyBoolean(), anyString(), any());
    }

    /** Неверный аргумент — ошибка вызывающего, а не событие репозитория. */
    @Test
    void aBadArgumentLeavesNoTraceEither() {
        when(git.discard("../etc"))
                .thenThrow(new IllegalArgumentException("path escapes the repo"));

        assertThatThrownBy(() -> controller.discard("../etc", "kb", CHAT))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(chatGitLog, never())
                .record(anyString(), anyString(), any(), anyBoolean(), anyString(), any());
    }

    /** Аргумент команды входит в её имя: «switch» без ветки не сказал бы модели ничего. */
    @Test
    void theArgumentIsPartOfTheNameTheChatRemembers() {
        when(git.switchBranch(eq("feature/x"), eq(false)))
                .thenThrow(new GitCommandFailedException("would be overwritten"));

        assertThatThrownBy(() -> controller.switchBranch("feature/x", false, "kb", CHAT))
                .isInstanceOf(ResponseStatusException.class);

        verify(chatGitLog)
                .record(CHAT, "switch feature/x", "kb", false, "would be overwritten", null);
    }
}
