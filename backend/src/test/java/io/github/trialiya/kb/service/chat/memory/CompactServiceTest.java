package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.ChatModelRegistry;
import io.github.trialiya.kb.model.chat.dto.CompactPayload;
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
 * Команда {@code /compact}: что модель получает историю как есть, что сжатие накрывает всё окно
 * целиком и что пустой ответ не стирает чат.
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
    private OpenAiChatModel chatModel;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMessageRepository.class);
        chatHistory = mock(ChatHistoryService.class);
        chatRunService = mock(ChatRunService.class);
        chatModel = mock(OpenAiChatModel.class);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        answerWith("## Overview\ncompacted");
    }

    /**
     * Раунд идёт по всему живому окну: разметка накрывает его от первой позиции до последней, а
     * сводка встаёт на позицию последнего сжатого сообщения — живого хвоста после сжатия не
     * остаётся, в отличие от фоновой суммаризации.
     */
    @Test
    void theWholeLiveWindowIsCompactedIntoOneSummaryRow() {
        final CompactPayload payload = service().compact(CONV, turns(3), null, null);

        verify(repository).updateSummarized(CONV, 0L, 8L);
        final ArgumentCaptor<ChatMessageEntity> saved =
                ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isSummary()).isTrue();
        assertThat(saved.getValue().getType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(saved.getValue().getPosition()).isEqualTo(8L);
        assertThat(saved.getValue().getContent()).contains("## Overview\ncompacted");
        assertThat(payload.messages()).isEqualTo(9);
    }

    /**
     * История уезжает модели теми же сообщениями, что и в обычном запросе чата: протокольные
     * tool-данные внутри, ничего не пересказано. Последним идёт инструкция сжатия — то, что стоит
     * на месте не сохранённой команды пользователя.
     */
    @Test
    void theHistoryReachesTheModelAsMessagesWithTheirToolData() {
        final List<PromptRow> rows = new ArrayList<>(turns(1));
        rows.set(1, withToolCall(1, "grepContent", "{\"query\":\"summarize\"}"));
        rows.set(2, withToolResponse(2, "grepContent", "SummarizeService.java:42 — the whole hit"));

        service().compact(CONV, rows, null, null);

        final List<Message> sent = capturedPrompt().getInstructions();
        // system + 3 строки окна + инструкция.
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
        service().compact(CONV, turns(1), "разбор миграций, остальное коротко", null);

        assertThat(capturedPrompt().getInstructions().getLast().getText())
                .contains("<focus>")
                .contains("разбор миграций, остальное коротко");
    }

    /** Сводка несёт проект, на котором закончилось окно, — маркер смены сжимается вместе с ним. */
    @Test
    void theSummaryRowCarriesTheProjectTheWindowEndedOn() {
        final List<PromptRow> rows = new ArrayList<>(turns(2));
        rows.set(3, switchRow(3, "kb", "billing"));

        service().compact(CONV, rows, null, null);

        final ArgumentCaptor<ChatMessageEntity> saved =
                ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getMeta()).isNotNull();
        assertThat(saved.getValue().getMeta().project()).isEqualTo("billing");
    }

    /**
     * Пустой ответ модели — история обязана остаться нетронутой: разметка без сводки стирает чат.
     */
    @Test
    void anEmptyModelAnswerLeavesTheHistoryUntouched() {
        answerWith("   ");

        assertThatThrownBy(() -> service().compact(CONV, turns(2), null, null))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).updateSummarized(anyString(), anyLong(), anyLong());
        verify(repository, never()).save(any());
    }

    /**
     * Сжимать нечего, когда живого контекста нет или он уже сводка: 422 (а не событие), и заявка на
     * чат снимается — иначе чат остался бы занятым навсегда.
     */
    @Test
    void aChatWithNothingButASummaryIsRefusedAndTheClaimIsReleased() {
        when(chatRunService.claim(CONV)).thenReturn("run-1");
        when(chatHistory.promptRows(CONV)).thenReturn(List.of(summaryRow(0)));

        assertThatThrownBy(() -> service().start(CONV, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Nothing to compact");

        verify(chatRunService).release(CONV, "run-1");
    }

    // -------------------------------------------------------------------------

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
        final ChatModelRegistry models = mock(ChatModelRegistry.class);
        when(models.forModel(any())).thenReturn(chatModel);
        return new CompactService(
                models,
                chatHistory,
                mock(ChatTopicRepository.class),
                new SummaryWriter(repository, transactionManager()),
                chatRunService,
                mock(ChatEventService.class),
                new ByteArrayResource("compact".getBytes()),
                Runnable::run);
    }

    /** Транзакции здесь ничего не защищают — тест смотрит только на вызовы репозитория. */
    private static PlatformTransactionManager transactionManager() {
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
    }
}
