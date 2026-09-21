package io.github.trialiya.kb.service.chat.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.ScriptRunPayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ScriptEventMeta;
import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.model.script.ScriptError;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.model.script.ScriptRunSource;
import io.github.trialiya.kb.model.script.ScriptStats;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.chat.run.ChatRunService;
import io.github.trialiya.kb.service.chat.run.RunOptionsResolver;
import io.github.trialiya.kb.service.chat.runtime.ChatActionClaim;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Команда {@code /script}: прогон тот же, что у модели, а вокруг него — три вещи, которых у неё
 * нет. Чат занимается на время прогона, след остаётся рядом истории (в том числе у упавшего), и ни
 * одна беда записи не превращается в отказ команды, которая уже сработала.
 */
class ChatScriptRunTest {

    private static final String CONV = "conv-1";
    private static final String CLAIM = "claim-1";

    private final ChatActionClaim claim = mock(ChatActionClaim.class);
    private final RunOptionsResolver runOptions = mock(RunOptionsResolver.class);
    private final SavedScriptResolver resolver = mock(SavedScriptResolver.class);
    private final ScriptRunner runner = mock(ScriptRunner.class);
    private final ScriptEditPolicy editPolicy = mock(ScriptEditPolicy.class);
    private final ChatHistoryService chatHistory = mock(ChatHistoryService.class);
    private final ChatEventService chatEvents = mock(ChatEventService.class);

    private final ChatScriptRun service = service(ScriptProperties.enabledWithDefaults());

    private ChatScriptRun service(ScriptProperties properties) {
        return new ChatScriptRun(
                properties,
                claim,
                runOptions,
                resolver,
                runner,
                editPolicy,
                chatHistory,
                chatEvents);
    }

    @BeforeEach
    void setUp() {
        when(claim.claimIdleAndOwned(CONV)).thenReturn(CLAIM);
        when(runOptions.current(CONV))
                .thenReturn(
                        new ChatRunService.RunOptions(null, false, false, "", "kb", "kb", null));
        when(resolver.resolve(any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(
                        new ScriptRequest(
                                new ScriptSource("return 1;", "tools/report.js", null),
                                ScriptArgs.none(),
                                null,
                                true,
                                null,
                                "kb"));
        when(chatHistory.appendScriptEvent(eq(CONV), any())).thenReturn(row());
    }

    @Test
    void runsTheScriptAndLeavesARowTheOtherTabsHearAbout() {
        when(runner.run(any(ScriptRequest.class), any())).thenReturn(result(null));

        service.run(CONV, "report", Map.of("area", "docs"), null);

        final ArgumentCaptor<ScriptEventMeta> event =
                ArgumentCaptor.forClass(ScriptEventMeta.class);
        verify(chatHistory).appendScriptEvent(eq(CONV), event.capture());
        assertThat(event.getValue().script()).isEqualTo("report");
        assertThat(event.getValue().ok()).isTrue();
        assertThat(event.getValue().path()).isEqualTo("tools/report.js");
        assertThat(event.getValue().edited()).containsExactly("src/App.java");
        verify(chatEvents)
                .publish(
                        eq(CONV),
                        eq(ChatEventType.SCRIPT_RUN),
                        any(),
                        any(),
                        any(ScriptRunPayload.class));
        verify(claim).release(CONV, CLAIM);
    }

    /**
     * Права на запись — проекта, а не поверхности: чат это место, где дифф показывают и привязывают
     * к сообщению, поэтому прогон здесь пишет там же, где пишет модель.
     */
    @Test
    void writesFollowTheProjectsOwnPermission() {
        when(editPolicy.enabled("kb")).thenReturn(true);
        when(runner.run(any(ScriptRequest.class), any())).thenReturn(result(null));

        service.run(CONV, "bump", null, null);

        verify(resolver).resolve(eq("kb"), eq("bump"), any(), any(), eq(true), eq(null));
    }

    /** Упавший прогон — тоже ряд: он и есть то, за чем к нему вернутся. */
    @Test
    void aFailedRunIsRecordedToo() {
        when(runner.run(any(ScriptRequest.class), any()))
                .thenReturn(result(new ScriptError(ScriptError.Kind.RUNTIME, "boom", 2)));

        service.run(CONV, "report", null, null);

        final ArgumentCaptor<ScriptEventMeta> event =
                ArgumentCaptor.forClass(ScriptEventMeta.class);
        verify(chatHistory).appendScriptEvent(eq(CONV), event.capture());
        assertThat(event.getValue().ok()).isFalse();
        assertThat(event.getValue().error()).isNotNull();
        verify(claim).release(CONV, CLAIM);
    }

    /**
     * Скрипт уже отработал и, если писал, рабочее дерево уже сдвинулось: ответить на это ошибкой
     * значило бы заставить панель нарисовать состояние, которого больше нет.
     */
    @Test
    void aLostRowDoesNotFailTheRunThatAlreadyHappened() {
        when(runner.run(any(ScriptRequest.class), any())).thenReturn(result(null));
        when(chatHistory.appendScriptEvent(eq(CONV), any()))
                .thenThrow(new IllegalStateException("БД недоступна"));

        assertThat(service.run(CONV, "report", null, null)).isNotNull();
        verify(claim).release(CONV, CLAIM);
    }

    /** Занятый или чужой чат — отказ до всего: ни прогона, ни ряда. */
    @Test
    void aBusyChatIsRefusedBeforeAnythingRuns() {
        when(claim.claimIdleAndOwned(CONV))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "busy"));

        assertThatThrownBy(() -> service.run(CONV, "report", null, null))
                .isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(runner, chatHistory, chatEvents);
        verify(claim, never()).release(any(), any());
    }

    /** Отказ разбора имени или аргументов возвращает заявку: иначе чат остался бы занятым. */
    @Test
    void aRefusedNameStillHandsTheClaimBack() {
        when(resolver.resolve(any(), any(), any(), any(), anyBoolean(), any()))
                .thenThrow(new IllegalArgumentException("Unknown script \"repoort\""));

        assertThatThrownBy(() -> service.run(CONV, "repoort", null, null))
                .isInstanceOf(IllegalArgumentException.class);

        verify(claim).release(CONV, CLAIM);
        verifyNoInteractions(runner, chatHistory);
    }

    /**
     * Выключенная песочница — отказ команде, а не тихий прогон. Проверяется здесь, потому что
     * эндпоинт {@code /script} есть всегда: бин контроллера не знает о настройке, и весь отказ
     * приходит отсюда. 409, как у стенда: команда существует, развёртка выключила то, что ей нужно.
     */
    @Test
    void scriptsSwitchedOffRefuseTheCommandBeforeTheChatIsEvenClaimed() {
        ChatScriptRun disabled =
                service(
                        new ScriptProperties(
                                false, true, true, false, null, null, null, null, null, null, null,
                                null, null));

        assertThatThrownBy(() -> disabled.run(CONV, "report", null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("kb.script.enabled=false")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        verifyNoInteractions(claim, resolver, runner, chatHistory, chatEvents);
    }

    private static ScriptResult result(ScriptError error) {
        return new ScriptResult(
                "kb",
                new ScriptRunSource(
                        ScriptRunSource.Kind.PROJECT,
                        "report",
                        "tools/report.js",
                        "0f1c2d3e4a5b",
                        Map.of()),
                error == null ? "ok" : null,
                List.of("строка журнала"),
                new ScriptStats(3, 100, 5, error == null ? 1 : 0, 42),
                error,
                List.of("tools/report.js"),
                error == null
                        ? List.of(new GitEditResult("edit", "src/App.java", 1, 1, 10, "@@"))
                        : List.of());
    }

    private static ChatMessageEntity row() {
        return new ChatMessageEntity(
                42L, CONV, "", MessageType.USER, 7, false, false, LocalDateTime.now(), null, null);
    }
}
