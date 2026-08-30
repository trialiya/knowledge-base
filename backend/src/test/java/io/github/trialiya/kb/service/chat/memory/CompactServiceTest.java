package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.ChatModelRegistry;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.CompactDetail;
import io.github.trialiya.kb.model.chat.dto.CompactErrorPayload;
import io.github.trialiya.kb.model.chat.dto.CompactPayload;
import io.github.trialiya.kb.model.chat.dto.UserMessagePayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import io.github.trialiya.kb.service.chat.prompt.SystemPromptService;
import io.github.trialiya.kb.service.chat.runtime.ConversationSlots;
import io.github.trialiya.kb.tools.ChatToolset;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
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

    /** Настройки чата: их собирает контроллер тем же резолвом, что и для обычного прогона. */
    private static final CompactService.CompactOptions OPTIONS =
            new CompactService.CompactOptions(null, false, "kb", "MODE");

    private ChatMessageRepository repository;
    private PendingSummaryService pendingSummaries;
    private PlatformTransactionManager transactions;
    private ChatHistoryService chatHistory;
    private ConversationSlots slots;
    private ChatEventService events;
    private OpenAiChatModel chatModel;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMessageRepository.class);
        pendingSummaries = mock(PendingSummaryService.class);
        transactions = transactionManager();
        // Записанный ряд возвращается как есть: раунд читает id сводки, чтобы плашка знала,
        // где лежит её текст.
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        chatHistory = mock(ChatHistoryService.class);
        slots = mock(ConversationSlots.class);
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
                service()
                        .compact(CONV, turns(3), forCommand(commandRow(9).entity()), null, OPTIONS);

        verify(repository).updateSummarized(CONV, 0L, 9L);
        final ChatMessageEntity summary = savedRows().get(0);
        assertThat(summary.isSummary()).isTrue();
        assertThat(summary.getType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(summary.getPosition()).isEqualTo(9L);
        assertThat(summary.getContent()).contains("## Overview\ncompacted");
        // 9 живых сообщений окна + сама команда — ровно то, что перестало ехать модели.
        assertThat(payload.messages()).isEqualTo(10);
    }

    /**
     * У автоматического сжатия лишнего ряда нет: граница кончается на последнем ряду окна, вопрос
     * прогона остаётся живым, и «сколько сообщений перестало ехать модели» — это ровно окно. Число
     * с плашки читает пользователь, и завышенное на команду, которой не было, оно просто неверно.
     */
    @Test
    void anAutomaticRoundCountsTheWindowAlone() {
        final List<PromptRow> rows = turns(3);
        final ChatMessageEntity lastRow = rows.getLast().entity();

        final CompactPayload payload =
                service()
                        .compact(
                                CONV,
                                rows,
                                new CompactService.CompactTarget(
                                        CompactMeta.Kind.AUTO_COMPACT,
                                        lastRow.getPosition(),
                                        lastRow.getCreatedAt(),
                                        (call, usage) -> null),
                                null,
                                OPTIONS);

        assertThat(payload.messages()).isEqualTo(rows.size());
        verify(repository).updateSummarized(CONV, 0L, lastRow.getPosition());
    }

    /**
     * Полное сжатие выбрасывает очередь отложенных сводок — их кусок оно заменило собой, — а деньги
     * их раундов забирает на свою плашку. Другого ряда у этих денег нет, и молча потерянные, они
     * разошлись бы со счётом провайдера ровно на стоимость этих сводок.
     */
    @Test
    void theMoneyOfTheDiscardedQueueLandsOnTheNotice() {
        final RunTokenUsage carried = new RunTokenUsage(0, 0, 0, 900, 61_000, 40_000, 0, 2);
        when(pendingSummaries.discard(CONV)).thenReturn(carried);

        final CompactPayload payload =
                service()
                        .compact(CONV, turns(3), forCommand(commandRow(9).entity()), null, OPTIONS);

        assertThat(payload.carried()).isEqualTo(carried);
        final CompactMeta compact = savedRows().get(1).getMeta().compact();
        assertThat(compact).isNotNull();
        assertThat(compact.carried()).isEqualTo(carried);
    }

    /**
     * Выбрасывание очереди лежит внутри той же транзакции, что и запись плашки. Порознь нельзя:
     * удаление, пережившее неудавшуюся запись, стёрло бы написанные моделью сводки насовсем —
     * вместе с их деньгами, — а второй раз их никто не напишет.
     */
    @Test
    void theQueueIsDiscardedInsideTheTransactionThatWritesTheNotice() {
        service().compact(CONV, turns(3), forCommand(commandRow(9).entity()), null, OPTIONS);

        final InOrder order = inOrder(transactions, pendingSummaries, repository);
        order.verify(transactions).getTransaction(any());
        order.verify(pendingSummaries).discard(CONV);
        order.verify(repository, atLeastOnce()).save(any());
    }

    /**
     * Упавший раунд очередь не трогает: сжатия не было, отложенные сводки по-прежнему описывают
     * живое начало истории, и выбросить их значит потерять и их текст, и их деньги разом.
     */
    @Test
    void aRoundThatWroteNoSummaryLeavesTheQueueParked() {
        answerWith("");

        assertThatThrownBy(
                        () ->
                                service()
                                        .compact(
                                                CONV,
                                                turns(3),
                                                forCommand(commandRow(9).entity()),
                                                null,
                                                OPTIONS))
                .isInstanceOf(IllegalStateException.class);

        verify(pendingSummaries, never()).discard(anyString());
    }

    /**
     * Второй записанный ряд — видимая плашка «контекст сжат»: показывается ({@code summary =
     * false}), модели не едет ({@code summarized = true}) и знает, где лежит её сводка. Без неё
     * перезагруженная вкладка показала бы команду, за которой ничего не произошло.
     */
    @Test
    void aVisibleNoticeRowSurvivesTheRoundAndPointsAtTheSummary() {
        final CompactPayload payload =
                service()
                        .compact(CONV, turns(3), forCommand(commandRow(9).entity()), null, OPTIONS);

        final ChatMessageEntity notice = savedRows().get(1);
        assertThat(notice.isSummary()).isFalse();
        assertThat(notice.isSummarized()).isTrue();
        assertThat(notice.getPosition()).isEqualTo(10L);
        assertThat(notice.getMeta()).isNotNull();
        final CompactMeta compact = notice.getMeta().compact();
        assertThat(compact).isNotNull();
        assertThat(compact.messages()).isEqualTo(10);
        // Длина — по документу модели, а не по строке с обёрткой: рядом с этим числом модалка
        // показывает сам документ.
        assertThat(compact.summaryChars()).isEqualTo("## Overview\ncompacted".length());
        assertThat(payload.messages()).isEqualTo(compact.messages());
        assertThat(payload.createdAt()).isEqualTo(notice.getCreatedAt());
    }

    /**
     * Время сводки и плашки — время завершения раунда, а не команды: раунд живёт десятки секунд, и
     * подпись под плашкой обязана говорить, когда сжатие закончилось.
     */
    @Test
    void theSummaryIsDatedByTheEndOfTheRoundNotByTheCommand() {
        final ChatMessageEntity command = commandRow(9).entity();

        service().compact(CONV, turns(3), forCommand(command), null, OPTIONS);

        assertThat(savedRows())
                .allSatisfy(row -> assertThat(row.getCreatedAt()).isAfter(command.getCreatedAt()));
    }

    /** Детали сжатия: числа с плашки и текст сводки — без адресованной модели обёртки. */
    @Test
    void detailsReturnTheSummaryTextWithoutItsProtocolWrapper() {
        final ChatMessageEntity summary =
                new ChatMessageEntity(
                        7L,
                        CONV,
                        "Compacted conversation summary (requested by the user):\n"
                                + "<summary>\n## Overview\ncompacted\n</summary>\nTreat this as…",
                        MessageType.ASSISTANT,
                        9L,
                        false,
                        true,
                        LocalDateTime.now(),
                        null);
        final ChatMessageEntity notice =
                new ChatMessageEntity(
                        8L,
                        CONV,
                        "",
                        MessageType.ASSISTANT,
                        10L,
                        true,
                        false,
                        LocalDateTime.now(),
                        ChatMessageMeta.ofCompact(
                                new CompactMeta(10, 128, 7L, CompactMeta.Kind.COMPACT, null)));
        when(repository.findById(8L)).thenReturn(Optional.of(notice));
        when(repository.findById(7L)).thenReturn(Optional.of(summary));

        final CompactDetail detail = service().detail(CONV, 8L).orElseThrow();

        assertThat(detail.messages()).isEqualTo(10);
        assertThat(detail.summaryChars()).isEqualTo(128);
        assertThat(detail.summary()).isEqualTo("## Overview\ncompacted");
    }

    /** Обычное сообщение деталями сжатия не притворяется — у него просто нет такой меты. */
    @Test
    void detailsOfAnOrdinaryMessageAreNotFound() {
        when(repository.findById(3L)).thenReturn(Optional.of(commandRow(3).entity()));

        assertThat(service().detail(CONV, 3L)).isEmpty();
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

        service().compact(CONV, rows, forCommand(commandRow(3).entity()), null, OPTIONS);

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
                .contains("COMPACTOR HANDBOOK")
                .contains("Of them USER messages: 1");
    }

    /**
     * Начало запроса — байт в байт начало обычного запроса чата: тот же {@code sys.md} с теми же
     * подстановками и те же схемы инструментов. Это и есть весь механизм попадания в кэш промпта:
     * провайдер считает совпадение от первого байта, и своя роль в системном сообщении обнулила бы
     * скидку на всём окне, которое сжатие как раз и пришло сократить.
     */
    @Test
    void theRequestStartsExactlyLikeAChatRequestSoTheProviderCountsItAsCached() {
        service().compact(CONV, turns(1), forCommand(commandRow(3).entity()), null, OPTIONS);

        final Prompt prompt = capturedPrompt();
        assertThat(prompt.getInstructions().getFirst())
                .satisfies(
                        system -> {
                            assertThat(system.getMessageType()).isEqualTo(MessageType.SYSTEM);
                            assertThat(system.getText()).isEqualTo("SYSTEM MODE SCRIPTS");
                        });
        assertThat(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks())
                .extracting(callback -> callback.getToolDefinition().name())
                .containsExactly("getFileContent");
    }

    /**
     * Инструкция сжатия едет последним сообщением, а не системным: системное место занято промптом
     * чата, и подменить его — значит разойтись с ним с нулевой позиции.
     */
    @Test
    void theCompactionHandbookRidesInTheLastMessageNotInTheSystemOne() {
        service().compact(CONV, turns(1), forCommand(commandRow(3).entity()), null, OPTIONS);

        final List<Message> sent = capturedPrompt().getInstructions();
        assertThat(sent.getFirst().getText()).doesNotContain("COMPACTOR HANDBOOK");
        assertThat(sent.getLast().getMessageType()).isEqualTo(MessageType.USER);
        assertThat(sent.getLast().getText()).startsWith("COMPACTOR HANDBOOK");
    }

    /**
     * Токены раунда ложатся в мету плашки — тем же полем, что и у обычного ответа. Без них сжатие
     * было бы единственной тратой чата, не попадающей в его же статистику.
     */
    @Test
    void theRoundsTokensAreRecordedOnTheNoticeRow() {
        answerWith(
                "## Overview\ncompacted",
                new DefaultUsage(169_000, 1_200, 170_200, null, 160_000L, 8_000L));

        final CompactPayload payload =
                service()
                        .compact(CONV, turns(3), forCommand(commandRow(9).entity()), null, OPTIONS);

        final RunTokenUsage usage = savedRows().get(1).getMeta().usage();
        assertThat(usage).isNotNull();
        assertThat(usage.promptTokens()).isEqualTo(169_000);
        assertThat(usage.cacheReadTokens()).isEqualTo(160_000);
        assertThat(usage.cacheWriteTokens()).isEqualTo(8_000);
        assertThat(usage.outputTokens()).isEqualTo(1_200);
        assertThat(usage.modelCalls()).isEqualTo(1);
        // Событие несёт те же числа, что и мета: живая вкладка и перезагруженная обязаны показать
        // одну плашку.
        assertThat(payload.usage()).isEqualTo(usage);
    }

    /**
     * Модель прочла схемы инструментов (они в запросе ради кэша) и вместо документа вызвала один из
     * них: исполнять вызов некому, история остаётся нетронутой, а отказ называет причину — иначе
     * это неотличимо от эндпоинта, который просто ничего не ответил.
     *
     * <p>Текст в таком ответе как раз есть — и это не выдуманный краевой случай: {@code sys.md}
     * требует начинать ответ с {@code recordChatInsights}, так что «сейчас запишу» плюс вызов —
     * ровно та форма, которую даёт неподчинившаяся модель. Прими её раунд за сводку, этой одной
     * фразой был бы заменён весь контекст чата.
     */
    @Test
    void aToolCallInsteadOfTheDocumentFailsTheRoundEvenWithTextBesideIt() {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(
                                List.of(
                                        new Generation(
                                                AssistantMessage.builder()
                                                        .content("Записываю инсайты чата.")
                                                        .toolCalls(
                                                                List.of(
                                                                        new AssistantMessage
                                                                                .ToolCall(
                                                                                "call-1",
                                                                                "function",
                                                                                "recordChatInsights",
                                                                                "{}")))
                                                        .build()))));

        assertThatThrownBy(
                        () ->
                                service()
                                        .compact(
                                                CONV,
                                                turns(2),
                                                forCommand(commandRow(6).entity()),
                                                null,
                                                OPTIONS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("called a tool");

        verify(repository, never()).updateSummarized(anyString(), anyLong(), anyLong());
        verify(repository, never()).save(any());
    }

    /** Эндпоинт без usage: в мете {@code null}, а не уверенный ноль. */
    @Test
    void anEndpointThatMeasuresNothingLeavesTheNoticeWithoutTokens() {
        final CompactPayload payload =
                service()
                        .compact(CONV, turns(3), forCommand(commandRow(9).entity()), null, OPTIONS);

        assertThat(savedRows().get(1).getMeta().usage()).isNull();
        assertThat(payload.usage()).isNull();
    }

    /**
     * Раунд, который сводки не дал, провайдер посчитал так же, как удавшийся: его замер обязан
     * остаться в чате — на единственном ряду, который у несостоявшегося сжатия есть, — строке самой
     * команды. Историю при этом он по-прежнему не трогает: разметки нет, сводки нет, записан ровно
     * один ряд.
     */
    @Test
    void aRoundThatWroteNoSummaryStillLeavesItsTokensInTheChat() {
        answerWith("   ", new DefaultUsage(169_000, 40, 169_040, null, 160_000L, 0L));
        final ChatMessageEntity command = commandRow(6).entity();

        final Throwable thrown =
                catchThrowable(
                        () ->
                                service()
                                        .compact(
                                                CONV,
                                                turns(2),
                                                forCommand(command),
                                                null,
                                                OPTIONS));

        assertThat(thrown).isInstanceOf(CompactService.CompactRoundFailed.class);
        final CompactService.CompactRoundFailed failed = (CompactService.CompactRoundFailed) thrown;
        // Событию COMPACT_ERROR числа нужны здесь же: вкладка досчитывает итог чата, не дожидаясь
        // перезагрузки.
        assertThat(failed.messageId()).isEqualTo(command.getId());
        assertThat(failed.usage()).isNotNull();
        assertThat(failed.usage().promptTokens()).isEqualTo(169_000);

        verify(repository, never()).updateSummarized(anyString(), anyLong(), anyLong());
        final ArgumentCaptor<ChatMessageEntity> saved =
                ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(repository).save(saved.capture());
        final ChatMessageMeta meta = saved.getValue().getMeta();
        assertThat(saved.getValue().getId()).isEqualTo(command.getId());
        assertThat(meta).isNotNull();
        assertThat(meta.usage()).isNotNull();
        assertThat(meta.usage().promptTokens()).isEqualTo(169_000);
        assertThat(meta.usage().cacheReadTokens()).isEqualTo(160_000);
    }

    /**
     * Тот же замер уезжает вкладкам событием — иначе живая вкладка разошлась бы с перезагруженной.
     */
    @Test
    void theErrorEventCarriesTheTokensOfTheRoundThatFailed() {
        when(slots.claim(CONV)).thenReturn("run-1");
        final List<PromptRow> oldWindow = turns(1);
        final PromptRow command = row(3, MessageType.USER, "/compact");
        when(chatHistory.promptRows(CONV)).thenReturn(append(oldWindow, command));
        when(chatHistory.promptRowsBefore(CONV, 3L)).thenReturn(oldWindow);
        when(chatHistory.saveUserMessage(CONV, "/compact", List.of(), null, null))
                .thenReturn(command.entity());
        answerWith("", new DefaultUsage(12_000, 3, 12_003, null, 0L, 0L));

        service().start(CONV, "/compact", null, OPTIONS, null);

        final ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events)
                .publish(
                        eq(CONV),
                        eq(ChatEventType.COMPACT_ERROR),
                        eq("run-1"),
                        isNull(),
                        payload.capture());
        final CompactErrorPayload error = (CompactErrorPayload) payload.getValue();
        assertThat(error.messageId()).isEqualTo(command.entity().getId());
        assertThat(error.usage()).isNotNull();
        assertThat(error.usage().promptTokens()).isEqualTo(12_000);
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
                        forCommand(commandRow(3).entity()),
                        "разбор миграций, остальное коротко",
                        OPTIONS);

        assertThat(capturedPrompt().getInstructions().getLast().getText())
                .contains("<focus>")
                .contains("разбор миграций, остальное коротко");
    }

    /** Сводка несёт проект, на котором закончилось окно, — маркер смены сжимается вместе с ним. */
    @Test
    void theSummaryRowCarriesTheProjectTheWindowEndedOn() {
        final List<PromptRow> rows = new ArrayList<>(turns(2));
        rows.set(3, switchRow(3, "kb", "billing"));

        service().compact(CONV, rows, forCommand(commandRow(6).entity()), null, OPTIONS);

        final ChatMessageEntity summary = savedRows().get(0);
        assertThat(summary.getMeta()).isNotNull();
        assertThat(summary.getMeta().project()).isEqualTo("billing");
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
                        () ->
                                service()
                                        .compact(
                                                CONV,
                                                turns(2),
                                                forCommand(commandRow(6).entity()),
                                                null,
                                                OPTIONS))
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
        when(slots.claim(CONV)).thenReturn("run-1");
        when(chatHistory.promptRows(CONV)).thenReturn(List.of(summaryRow(0)));

        assertThatThrownBy(() -> service().start(CONV, "/compact", null, OPTIONS, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Nothing to compact");

        verify(slots).release(CONV, "run-1");
        verify(chatHistory, never()).saveUserMessage(anyString(), anyString(), any(), any(), any());
    }

    /**
     * {@code start}: команда сохраняется обычным USER-сообщением и уходит эхом {@code USER_MESSAGE}
     * — так же, как любой вопрос, — но окно, снятое ДО её сохранения, в раунд не попадает: команда
     * не материал для сжатия. По завершении раунда её позиция входит в размеченный диапазон, и она
     * перестаёт ехать модели дальше, оставаясь видимой в истории.
     */
    @Test
    void theCommandIsSavedAndEchoedButExcludedFromTheRound() {
        when(slots.claim(CONV)).thenReturn("run-1");
        final List<PromptRow> oldWindow = turns(1); // позиции 0..2
        final PromptRow command = row(3, MessageType.USER, "/compact фокус");
        // Проверка «есть ли что сжимать» идёт до сохранения команды и видит всю историю; сам раунд
        // просит окно ДО позиции команды — и получает его без неё.
        when(chatHistory.promptRows(CONV)).thenReturn(append(oldWindow, command));
        when(chatHistory.promptRowsBefore(CONV, 3L)).thenReturn(oldWindow);
        final ChatMessageEntity saved = command.entity();
        when(chatHistory.saveUserMessage(CONV, "/compact фокус", List.of(), null, null))
                .thenReturn(saved);

        final CompactService.StartedCompact started =
                service().start(CONV, "/compact фокус", "фокус", OPTIONS, "client-1");

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
        when(slots.claim(CONV)).thenReturn("run-1");
        when(chatHistory.promptRows(CONV)).thenReturn(turns(1));
        when(chatHistory.saveUserMessage(eq(CONV), anyString(), any(), any(), any()))
                .thenReturn(row(3, MessageType.USER, "/compact").entity());

        assertThatThrownBy(
                        () ->
                                service(rejectingExecutor())
                                        .start(CONV, "/compact", null, OPTIONS, null))
                .isInstanceOf(RejectedExecutionException.class);

        verify(events)
                .publish(eq(CONV), eq(ChatEventType.COMPACT_ERROR), eq("run-1"), isNull(), any());
        verify(slots).release(CONV, "run-1");
    }

    // -------------------------------------------------------------------------

    /** Записанные раундом ряды по порядку: сначала сводка, за ней видимая плашка. */
    private List<ChatMessageEntity> savedRows() {
        final ArgumentCaptor<ChatMessageEntity> saved =
                ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(repository, times(2)).save(saved.capture());
        return saved.getAllValues();
    }

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

    private void answerWith(String content, Usage usage) {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(
                                List.of(new Generation(new AssistantMessage(content))),
                                ChatResponseMetadata.builder().usage(usage).build()));
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

    /**
     * Цель раунда по команде — ровно та, что собирает сам сервис для {@code /compact}: тесты здесь
     * про эту ветку, и своя копия её правил разошлась бы с ней на первом же изменении.
     */
    private CompactService.CompactTarget forCommand(ChatMessageEntity commandRow) {
        return service().commandTarget(commandRow);
    }

    private CompactService service() {
        return service(Runnable::run);
    }

    private CompactService service(Executor executor) {
        final ChatModelRegistry models = mock(ChatModelRegistry.class);
        when(models.forModel(any())).thenReturn(chatModel);
        final SystemPromptService systemPrompts = mock(SystemPromptService.class);
        when(systemPrompts.placeholders(false, "kb", "MODE"))
                .thenReturn(Map.of("mode", "MODE", "scripts", "SCRIPTS"));
        return new CompactService(
                models,
                chatHistory,
                mock(ChatTopicRepository.class),
                repository,
                new SummaryWriter(repository, transactions),
                pendingSummaries,
                slots,
                events,
                systemPrompts,
                new ChatToolset(List.of(toolCallback("getFileContent")), List.of()),
                new ByteArrayResource("SYSTEM {mode} {scripts}".getBytes()),
                new ByteArrayResource("COMPACTOR HANDBOOK".getBytes()),
                executor,
                transactions);
    }

    /** Заглушка инструмента: раунду важна только схема — вызывать её здесь некому. */
    private static ToolCallback toolCallback(String name) {
        final ToolCallback callback = mock(ToolCallback.class);
        when(callback.getToolDefinition())
                .thenReturn(
                        DefaultToolDefinition.builder()
                                .name(name)
                                .description(name)
                                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                                .build());
        return callback;
    }

    /** Транзакции здесь ничего не защищают — тест смотрит только на вызовы репозитория. */
    private static PlatformTransactionManager transactionManager() {
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
    }
}
