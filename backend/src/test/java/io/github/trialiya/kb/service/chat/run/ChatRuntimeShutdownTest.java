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
import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties.ModelOption;
import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.AutoCompactService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService;
import io.github.trialiya.kb.service.chat.memory.PendingSummaryService;
import io.github.trialiya.kb.service.chat.memory.SummarizeService;
import io.github.trialiya.kb.service.chat.prompt.SystemPromptService;
import io.github.trialiya.kb.service.chat.run.PendingMessageService.Flushed;
import io.github.trialiya.kb.service.chat.runtime.ConversationSlots;
import io.github.trialiya.kb.service.chat.runtime.RunRegistry;
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
import org.springframework.ai.openai.OpenAiChatOptions;
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
    private RunRegistry runs;
    private ConversationSlots slots;
    private ChatRunService runService;
    private PendingMessageService pendingMessages;
    private final Deque<Runnable> pending = new ArrayDeque<>();

    /** Пул, который откладывает задачу до явного {@link #runPending()} — для гонки старт/стоп. */
    private final Executor deferred = pending::add;

    @BeforeEach
    void setUp() {
        chatMemory = mock(ChatMemory.class);
        chatHistory = mock(ChatHistoryService.class);
        // Вопрос пользователя сохраняется до старта прогона — прогон берёт из ряда id и текст.
        when(chatHistory.saveUserMessage(anyString(), anyString(), anyList(), any(), any()))
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
        runs = new RunRegistry();
        slots = new ConversationSlots(events);
        pendingMessages = mock(PendingMessageService.class);
        when(pendingMessages.flushPlain(anyString())).thenReturn(Flushed.NOTHING);
        pending.clear();
    }

    @Test
    void cancelsRunsAndClosesSubscriptionsOnContextClosed() {
        runService = runService(Runnable::run);
        final SseEmitter emitter = events.subscribe(CONV, 0);
        runService.start(CONV, USER, "привет", List.of(), options(), "msg-1");

        assertThat(runs.size()).isEqualTo(1);
        assertThat(events.hubCount()).isEqualTo(1);

        shutdown(5000).onContextClosed(new ContextClosedEvent(mock(ApplicationContext.class)));

        assertThat(runs.size()).isZero();
        assertThat(slots.claimedConversationCount()).isZero();
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
        assertThat(runs.size()).isEqualTo(1); // задача ещё не стартовала

        runPending();

        assertThat(runs.size()).isZero();
        verify(chatMemory).add(eq(CONV), any(Message.class));
    }

    /**
     * Остановленный прогон очередь всё-таки опустошает — сообщение не теряется, — но отвечать на
     * неё не начинает: остановку нажимают, чтобы генерация прекратилась. Вопрос остаётся последним
     * рядом истории, то есть ровно там, где чат предлагает «Повторить».
     */
    @Test
    void aStoppedRunDeliversItsQueueButStartsNoAnswer() {
        runService = runService(Runnable::run);
        when(pendingMessages.flushPlain(CONV))
                .thenReturn(
                        new Flushed(
                                List.of(userRow()),
                                USER,
                                PendingMessageService.PendingOptions.NONE));
        runService.start(CONV, USER, "привет", List.of(), options(), "msg-1");

        assertThat(runService.stopAll()).isEqualTo(1);

        // Дважды: страховочный флаш на старте прогона и терминальная доставка. Отвечать на
        // доставленное при этом никто не начал — иначе появился бы второй прогон.
        verify(pendingMessages, org.mockito.Mockito.times(2)).flushPlain(CONV);
        assertThat(runs.size()).isZero();
        assertThat(slots.claimedConversationCount()).isZero();
    }

    private static ChatMessageEntity userRow() {
        return new ChatMessageEntity(
                42L, CONV, "вопрос", MessageType.USER, 2, false, false, LocalDateTime.now(), null);
    }

    /**
     * Реестр прогонов прогон покидает ДО доставки очереди (иначе принятое на границе завершения
     * сообщение зависло бы), а доставка пишет в БД. Значит, ждать опустевшего реестра нельзя:
     * shutdown закрыл бы пул соединений прямо посреди неё.
     */
    @Test
    void quiescenceWaitsForTheDeliveryThatFollowsTheRegistry() {
        runService = runService(Runnable::run);
        final Deque<Boolean> quiescentDuringFlush = new ArrayDeque<>();
        when(pendingMessages.flushPlain(CONV))
                .thenAnswer(
                        inv -> {
                            quiescentDuringFlush.add(runService.awaitQuiescence(Duration.ZERO));
                            return Flushed.NOTHING;
                        });
        runService.start(CONV, USER, "привет", List.of(), options(), "msg-1");

        assertThat(runService.stopAll()).isEqualTo(1);

        // Два флаша: страховочный на старте прогона (тогда ждать и правда некого) и терминальный —
        // на нём реестр уже пуст, и без отдельного счёта завершающихся ожидание бы закончилось.
        assertThat(quiescentDuringFlush).containsExactly(true, false);
        // А после — уже тихо: терминальная обработка дописала.
        assertThat(runService.awaitQuiescence(Duration.ZERO)).isTrue();
    }

    /**
     * Прогон мог покинуть реестр за миг до остановки приложения и всё ещё дописывать хвост
     * терминальной обработки — доставку очереди в БД. {@code stopAll} насчитает ноль, но ждать есть
     * кого: без безусловного вызова пул соединений закрылся бы прямо посреди неё.
     */
    @Test
    void waitsEvenWhenThereWasNobodyToStop() {
        final ChatRunService finishing = mock(ChatRunService.class);
        when(finishing.stopAll()).thenReturn(0);

        new ChatRuntimeShutdown(finishing, events, 5000)
                .onContextClosed(new ContextClosedEvent(mock(ApplicationContext.class)));

        verify(finishing).awaitQuiescence(Duration.ofMillis(5000));
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
        // Опции прогон ставит всегда — в них едет stream_options.include_usage
        // (см. ChatRunService.run), а не только выбранная модель.
        when(spec.options(any(OpenAiChatOptions.Builder.class))).thenReturn(spec);
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
                mock(SummarizeService.class),
                mock(PendingSummaryService.class),
                mock(AutoCompactService.class),
                new ChatModelProperties(
                        new ModelOption("default-model", "Default", true, true, null, null, null),
                        List.of()),
                events,
                mock(SystemPromptService.class),
                pendingMessages,
                mock(RunOptionsResolver.class),
                runs,
                slots,
                executor);
    }

    /** Дефолтные настройки прогона: модель/режим/проект не выбраны. */
    private static ChatRunService.RunOptions options() {
        return new ChatRunService.RunOptions(null, false, true, "", null, "kb", null);
    }
}
