package io.github.trialiya.kb.service.chat.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.ChatClientRegistry;
import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.service.chat.ProjectPromptService;
import io.github.trialiya.kb.service.chat.SystemPromptService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.chat.memory.SummarizeService;
import io.github.trialiya.kb.service.chat.memory.ToolCallEventPublisher;
import io.github.trialiya.kb.service.chat.memory.ToolCallService;
import io.github.trialiya.kb.service.chat.script.ScriptGuideService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * Остановка приложения ({@link ChatRuntimeShutdown}): активные прогоны отменяются с частичным
 * сохранением, SSE-подписки закрываются, реестры пустеют. Без этого открытая вкладка держала
 * async-запрос, и graceful shutdown Tomcat ждал её свои 30 с, а затем всё равно обрывал.
 */
class ChatRuntimeShutdownTest {

    private static final String CONV = "conv-1";
    private static final String USER = "admin";

    private ChatMemory chatMemory;
    private ChatHistoryService chatHistory;
    private ChatEventService events;
    private ChatRunService runService;
    private final Deque<Runnable> pending = new ArrayDeque<>();

    /** Пул, который откладывает задачу до явного {@link #runPending()} — для гонки старт/стоп. */
    private final Executor deferred = pending::add;

    @BeforeEach
    void setUp() {
        chatMemory = mock(ChatMemory.class);
        chatHistory = mock(ChatHistoryService.class);
        // Вопрос пользователя сохраняется до старта прогона — прогон берёт из ряда id и текст.
        when(chatHistory.saveUserMessage(anyString(), anyString(), anyList(), any()))
                .thenAnswer(
                        inv ->
                                new ChatMessageEntity(
                                        1L,
                                        inv.getArgument(0),
                                        inv.getArgument(1),
                                        MessageType.USER,
                                        1,
                                        false,
                                        false,
                                        LocalDateTime.now(),
                                        null));
        events = new ChatEventService(new ChatTimeoutProperties(Duration.ofMinutes(1)));
        pending.clear();
    }

    @Test
    void cancelsRunsAndClosesSubscriptionsOnContextClosed() {
        runService = runService(Runnable::run);
        final SseEmitter emitter = events.subscribe(CONV, 0);
        runService.start(CONV, USER, "привет", List.of(), options(), "msg-1");

        assertThat(runService.activeRunCount()).isEqualTo(1);
        assertThat(events.hubCount()).isEqualTo(1);

        shutdown(5000).onContextClosed(new ContextClosedEvent(mock(ApplicationContext.class)));

        assertThat(runService.activeRunCount()).isZero();
        assertThat(runService.claimedConversationCount()).isZero();
        assertThat(events.hubCount()).isZero();
        // Оборванный ответ сохранён до закрытия подписок — пул соединений ещё жив.
        final ArgumentCaptor<Message> saved = ArgumentCaptor.captor();
        verify(chatMemory).add(eq(CONV), saved.capture());
        assertThat(saved.getValue().getText()).isEqualTo("Привет\n\n[stopped]");
        // Подписка завершена: запрос больше не активен, Tomcat остановится сразу.
        assertThatThrownBy(() -> emitter.send(SseEmitter.event().data("x")))
                .isInstanceOf(IllegalStateException.class);
    }

    /** Прогон, которому сигнал остановки пришёл до подписки на стрим, всё равно останавливается. */
    @Test
    void cancelsRunThatHasNotSubscribedYet() {
        runService = runService(deferred);
        runService.start(CONV, USER, "привет", List.of(), options(), "msg-1");

        assertThat(runService.stopAll()).isEqualTo(1);
        assertThat(runService.activeRunCount()).isEqualTo(1); // задача ещё не стартовала

        runPending();

        assertThat(runService.activeRunCount()).isZero();
        verify(chatMemory).add(eq(CONV), any(Message.class));
    }

    @Test
    void quiescenceWaitEndsImmediatelyWithoutRuns() {
        runService = runService(Runnable::run);
        assertThat(runService.stopAll()).isZero();
        assertThat(runService.awaitQuiescence(Duration.ofSeconds(5))).isTrue();
    }

    private void runPending() {
        while (!pending.isEmpty()) {
            pending.poll().run();
        }
    }

    private ChatRuntimeShutdown shutdown(long graceMs) {
        return new ChatRuntimeShutdown(runService, events, graceMs);
    }

    /**
     * Прогон, который отдаёт один чанк и дальше висит — как настоящая генерация в момент Ctrl+C.
     */
    @SuppressWarnings("unchecked")
    private ChatRunService runService(Executor executor) {
        final ChatClient chatClient = mock(ChatClient.class);
        final ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        final ChatClient.StreamResponseSpec stream = mock(ChatClient.StreamResponseSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.system(any(Consumer.class))).thenReturn(spec);
        when(spec.toolContext(any())).thenReturn(spec);
        when(spec.advisors(any(Consumer.class))).thenReturn(spec);
        when(spec.stream()).thenReturn(stream);
        when(stream.chatResponse())
                .thenReturn(
                        Flux.concat(
                                Flux.just(
                                        new ChatResponse(
                                                List.of(
                                                        new Generation(
                                                                new AssistantMessage("Привет"))))),
                                Flux.never()));
        return new ChatRunService(
                new ChatClientRegistry("default-model", chatClient, Map.of()),
                chatMemory,
                chatHistory,
                mock(ToolCallService.class),
                mock(ToolCallEventPublisher.class),
                mock(SummarizeService.class),
                events,
                mock(ScriptGuideService.class),
                mock(SystemPromptService.class),
                mock(ProjectPromptService.class),
                executor);
    }

    /** Дефолтные настройки прогона: модель/режим/проект не выбраны. */
    private static ChatRunService.RunOptions options() {
        return new ChatRunService.RunOptions(null, false, "", null, null);
    }
}
