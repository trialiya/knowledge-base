package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.ChatModelRegistry;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.CompactPayload;
import io.github.trialiya.kb.model.chat.dto.UserMessagePayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import io.github.trialiya.kb.service.chat.run.ChatEventService;
import io.github.trialiya.kb.service.chat.run.ChatRunService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Команда {@code /compact}: что модель получает историю как есть, что сама команда остаётся видна в
 * истории, но не участвует в сжатии, что сжатие накрывает всё окно целиком (плюс саму команду) и
 * что пустой ответ не стирает чат.
 *
 * <p>Проверка «как есть» здесь главная: суммаризатор пересказывает окно текстом и режет результаты
 * инструментов до гистов, а сжатие обязано отдать те же строки, которыми чат живёт, — с
 * протокольными {@code tool_calls} и полными ответами инструментов внутри.
 */
class CompactServiceTest {

    private static final String CONV = "conv-1";

    private ChatMessageRepository repository;
    private ChatHistoryService chatHistory;
    private ChatRunService chatRunService;
    private ChatEventService events;
    private OpenAiChatModel chatModel;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMessageRepository.class);
        chatHistory = mock(ChatHistoryService.class);
        chatRunService = mock(ChatRunService.class);
        events = mock(ChatEventService.class);
        chatModel = mock(OpenAiChatModel.class);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        answerWith("## Overview\ncompacted");
    }

    /**
     * Раунд идёт по всему живому окну плюс саму команду: разметка накрывает диапазон от первой
     * позиции окна до позиции команды, а сводка встаёт на её позицию — живого хвоста после сжатия
     * не остаётся, в отличие от фоновой суммаризации.
     */
    @Test
    void theWholeLiveWindowPlusTheCommandIsCompactedIntoOneSummaryRow() {
        final CompactPayload payload =
                service().compact(CONV, turns(3), commandRow(9).entity(), null, null);

        verify(repository).updateSummarized(CONV, 0L, 9L);
        final ArgumentCaptor<ChatMessageEntity> saved =
                ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isSummary()).isTrue();
        assertThat(saved.getValue().getType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(saved.getValue().getPosition()).isEqualTo(9L);
        assertThat(saved.getValue().getContent()).contains("## Overview\ncompacted");
        // 9 живых сообщений окна + сама команда — ровно то, что перестало ехать модели.
        assertThat(payload.messages()).isEqualTo(10);
    }

    /**
     * История уезжает модели теми же сообщениями, что и в обычном запросе чата: протокольные
     * tool-данные внутри, ничего не пересказано. Последним идёт инструкция сжатия — команда сама в
     * это окно не входит.
     */
    @Test
    void theHistoryReachesTheModelAsMessagesWithTheirToolData() {
        final List<PromptRow> rows = new ArrayList<>(turns(1));
        rows.set(1, withToolCall(1, "grepContent", "{\"query\":\"summarize\"}"));
        rows.set(2, withToolResponse(2, "grepContent", "SummarizeService.java:42 — the whole hit"));

        service().compact(CONV, rows, commandRow(3).entity(), null, null);

        final List<Message> sent = capturedPrompt().getInstructions();
        // system + 3 строки окна + инструкция; команда сама не входит.
        assertThat(sent).hasSize(5);
        assertThat(((AssistantMessage) sent.get(2)).getToolCalls().getFirst())
                .satisfies(
                        call -> {
                            assertThat(call.name()).isEqualTo("grepContent");
                            assertThat(call.arguments()).isEqualTo("{\"query\":\"summarize\"}");
                        });
        // Результат инструмента — целиком, а не гистом: этим сжатие и отличается от суммаризации.
        assertThat(((ToolResponseMessage) sent.get(3)).getResponses().getFirst().responseData())
                .isEqualTo("SummarizeService.java:42 — the whole hit");
        assertThat(sent.getLast().getText())
                .contains("Everything above this message is the conversation to compact")
                .contains("Of them USER messages: 1");
    }

    /**
     * Хвост команды доезжает до модели фокусом — и только им: остальные разделы остаются в силе.
     */
    @Test
    void theCommandTailBecomesTheFocusOfTheRound() {
        service()
                .compact(
                        CONV,
                        turns(1),
                        commandRow(3).entity(),
                        "разбор миграций, остальное коротко",
                        null);

        assertThat(capturedPrompt().getInstructions().getLast().getText())
                .contains("<focus>")
                .contains("разбор миграций, остальное коротко");
    }

    /** Сводка несёт проект, на котором закончилось окно, — маркер смены сжимается вместе с ним. */
    @Test
    void theSummaryRowCarriesTheProjectTheWindowEndedOn() {
        final List<PromptRow> rows = new ArrayList<>(turns(2));
        rows.set(3, switchRow(3, "kb", "billing"));

        service().compact(CONV, rows, commandRow(6).entity(), null, null);

        final ArgumentCaptor<ChatMessageEntity> saved =
                ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getMeta()).isNotNull();
        assertThat(saved.getValue().getMeta().project()).isEqualTo("billing");
    }

    /**
     * Пустой ответ модели — история обязана остаться нетронутой: разметка без сводки стирает чат.
     * Сама команда при этом уже сохранена отдельно (см. {@link #start} — здесь только сам раунд) и
     * этот метод её не трогает.
     */
    @Test
    void anEmptyModelAnswerLeavesTheHistoryUntouched() {
        answerWith("   ");

        assertThatThrownBy(
                        () -> service().compact(CONV, turns(2), commandRow(6).entity(), null, null))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).updateSummarized(anyString(), anyLong(), anyLong());
        verify(repository, never()).save(any());
    }

    /**
     * Сжимать нечего, когда живого контекста нет или он уже сводка: 422 (а не событие), заявка на
     * чат снимается, а команда не сохраняется — иначе в истории осталась бы реплика, которая ничего
     * не сделала.
     */
    @Test
    void aChatWithNothingButASummaryIsRefusedAndTheClaimIsReleased() {
        when(chatRunService.claim(CONV)).thenReturn("run-1");
        when(chatHistory.promptRows(CONV)).thenReturn(List.of(summaryRow(0)));

        assertThatThrownBy(() -> service().start(CONV, "/compact", null, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Nothing to compact");

        verify(chatRunService).release(CONV, "run-1");
        verify(chatHistory, never()).saveUserMessage(anyString(), anyString(), any(), any());
    }

    /**
     * {@code start}: команда сохраняется обычным USER-сообщением и уходит эхом {@code USER_MESSAGE}
     * — так же, как любой вопрос, — но окно, снятое ДО её сохранения, в раунд не попадает: команда
     * не материал для сжатия. По завершении раунда её позиция входит в размеченный диапазон, и она
     * перестаёт ехать модели дальше, оставаясь видимой в истории.
     */
    @Test
    void theCommandIsSavedAndEchoedButExcludedFromTheRound() {
        when(chatRunService.claim(CONV)).thenReturn("run-1");
        final List<PromptRow> oldWindow = turns(1); // позиции 0..2
        final PromptRow command = row(3, MessageType.USER, "/compact фокус");
        // Первый вызов — проверка «есть ли что сжимать», до сохранения команды; второй — уже
        // сам раунд, и там команда в истории уже стоит: раунд обязан отрезать её сам.
        when(chatHistory.promptRows(CONV)).thenReturn(oldWindow, append(oldWindow, command));
        final ChatMessageEntity saved = command.entity();
        when(chatHistory.saveUserMessage(CONV, "/compact фокус", List.of(), null))
                .thenReturn(saved);

        final CompactService.StartedCompact started =
                service().start(CONV, "/compact фокус", "фокус", null, "client-1");

        assertThat(started.runId()).isEqualTo("run-1");
        assertThat(started.messageId()).isEqualTo(saved.getId());

        final ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events)
                .publish(
                        eq(CONV),
                        eq(ChatEventType.USER_MESSAGE),
                        eq("run-1"),
                        eq("client-1"),
                        payload.capture());
        final UserMessagePayload echoed = (UserMessagePayload) payload.getValue();
        assertThat(echoed.id()).isEqualTo(saved.getId());
        assertThat(echoed.text()).isEqualTo("/compact фокус");

        verify(events)
                .publish(
                        eq(CONV),
                        eq(ChatEventType.COMPACT_STARTED),
                        eq("run-1"),
                        isNull(),
                        isNull());

        // Диапазон, который перестал ехать модели, — окно (0..2) ПЛЮС сама команда (3), а не
        // только окно: результат виден в updateSummarized, вызванном фоновым раундом (executor
        // здесь синхронный).
        verify(repository).updateSummarized(CONV, 0L, 3L);
        // Команда сохранена, но в модель уехало только окно до неё.
        assertThat(capturedPrompt().getInstructions())
                .noneMatch(message -> message.getText().contains("/compact фокус"));
    }

    /**
     * Исполнитель отказал (очередь переполнена, выключение): {@code COMPACT_STARTED} уже ушёл всем
     * вкладкам, поэтому его обязан погасить {@code COMPACT_ERROR} — иначе чужие вкладки навсегда
     * останутся на плашке «сжимаю…», ответ об ошибке видит только своя.
     */
    @Test
    void aRejectedRoundUnblocksEveryTabWithAnErrorEvent() {
        when(chatRunService.claim(CONV)).thenReturn("run-1");
        when(chatHistory.promptRows(CONV)).thenReturn(turns(1));
        when(chatHistory.saveUserMessage(eq(CONV), anyString(), any(), any()))
                .thenReturn(row(3, MessageType.USER, "/compact").entity());

        assertThatThrownBy(
                        () ->
                                service(rejectingExecutor())
                                        .start(CONV, "/compact", null, null, null))
                .isInstanceOf(RejectedExecutionException.class);

        verify(events)
                .publish(eq(CONV), eq(ChatEventType.COMPACT_ERROR), eq("run-1"), isNull(), any());
        verify(chatRunService).release(CONV, "run-1");
    }

    // -------------------------------------------------------------------------

    private static List<PromptRow> append(List<PromptRow> rows, PromptRow extra) {
        final List<PromptRow> all = new ArrayList<>(rows);
        all.add(extra);
        return all;
    }

    private static Executor rejectingExecutor() {
        return task -> {
            throw new RejectedExecutionException("shutting down");
        };
    }

    private void answerWith(String content) {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
    }

    private Prompt capturedPrompt() {
        final ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        return prompt.getValue();
    }

    /** Ходы по три позиции: вопрос, ответ модели и пустая протокольная TOOL-строка за ним. */
    private static List<PromptRow> turns(int count) {
        final List<PromptRow> rows = new ArrayList<>();
        for (int turn = 0; turn < count; turn++) {
            rows.add(row(turn * 3, MessageType.USER, "question " + turn));
            rows.add(row(turn * 3 + 1, MessageType.ASSISTANT, "answer " + turn));
            rows.add(row(turn * 3 + 2, MessageType.TOOL, ""));
        }
        return rows;
    }

    private static PromptRow withToolCall(long position, String name, String arguments) {
        final ChatMessageEntity entity =
                new ChatMessageEntity(
                        position + 1,
                        CONV,
                        "answer",
                        MessageType.ASSISTANT,
                        position,
                        false,
                        false,
                        LocalDateTime.now(),
                        null,
                        new ToolData(
                                List.of(new ToolData.Call("call-1", "function", name, arguments)),
                                null));
        return new PromptRow(entity, "answer");
    }

    private static PromptRow withToolResponse(long position, String name, String responseData) {
        final ChatMessageEntity entity =
                new ChatMessageEntity(
                        position + 1,
                        CONV,
                        "",
                        MessageType.TOOL,
                        position,
                        false,
                        false,
                        LocalDateTime.now(),
                        null,
                        new ToolData(
                                null,
                                List.of(new ToolData.Response("call-1", name, responseData))));
        return new PromptRow(entity, "");
    }

    private static PromptRow switchRow(long position, String from, String to) {
        final ChatMessageEntity entity =
                new ChatMessageEntity(
                        position + 1,
                        CONV,
                        "question",
                        MessageType.USER,
                        position,
                        false,
                        false,
                        LocalDateTime.now(),
                        ChatMessageMeta.ofUserMessage(List.of(), to, from));
        return new PromptRow(entity, "question");
    }

    private static PromptRow summaryRow(long position) {
        final ChatMessageEntity entity =
                new ChatMessageEntity(
                        position + 1,
                        CONV,
                        "earlier summary",
                        MessageType.ASSISTANT,
                        position,
                        false,
                        true,
                        LocalDateTime.now(),
                        null);
        return new PromptRow(entity, "earlier summary");
    }

    /** Сама команда {@code /compact} — обычная USER-строка, никогда не входящая в {@code rows}. */
    private static PromptRow commandRow(long position) {
        return row(position, MessageType.USER, "/compact");
    }

    private static PromptRow row(long position, MessageType type, String content) {
        final ChatMessageEntity entity =
                new ChatMessageEntity(
                        position + 1,
                        CONV,
                        content,
                        type,
                        position,
                        false,
                        false,
                        LocalDateTime.now(),
                        null);
        return new PromptRow(entity, content);
    }

    private CompactService service() {
        return service(Runnable::run);
    }

    private CompactService service(Executor executor) {
        final ChatModelRegistry models = mock(ChatModelRegistry.class);
        when(models.forModel(any())).thenReturn(chatModel);
        return new CompactService(
                models,
                chatHistory,
                mock(ChatTopicRepository.class),
                new SummaryWriter(repository, transactionManager()),
                chatRunService,
                events,
                new ByteArrayResource("compact".getBytes()),
                executor);
    }

    /** Транзакции здесь ничего не защищают — тест смотрит только на вызовы репозитория. */
    private static PlatformTransactionManager transactionManager() {
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
    }
}
