package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.CompactPayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.chat.entity.TokenUsage;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Правило запуска авто-сжатия: когда чат сжимает себя сам перед ответом, чем он при этом закрывает
 * раунд и что делает, если раунд не состоялся.
 *
 * <p>Сам раунд здесь заглушка — он целиком принадлежит {@code CompactService} и проверен там. Этот
 * тест про решение и про его границу: вопрос, ради которого чат сжался, обязан остаться живым.
 */
class AutoCompactServiceTest {

    private static final String CONV = "conv-1";
    private static final String RUN = "run-1";

    /** Позиция вопроса, ради которого идёт прогон: окно берётся строго до неё. */
    private static final long QUESTION = 20L;

    private static final CompactService.CompactOptions OPTIONS =
            new CompactService.CompactOptions(null, false, "kb", "MODE");

    /** Окно на 0.8 от него — 8000 токенов; ряды теста весят заметно меньше. */
    private static final int MODEL_WINDOW = 10_000;

    private ChatHistoryService chatHistory;
    private CompactService compactService;
    private ChatEventService events;

    @BeforeEach
    void setUp() {
        chatHistory = mock(ChatHistoryService.class);
        compactService = mock(CompactService.class);
        events = mock(ChatEventService.class);
        when(compactService.compact(anyString(), any(), any(), any(), any())).thenReturn(payload());
    }

    /** Окно легче порога — раунда нет вовсе: сжатие стоит денег и десятков секунд ожидания. */
    @Test
    void aWindowUnderTheLimitIsLeftAlone() {
        window(measured(4_000));

        service().compactIfOversized(CONV, RUN, QUESTION, MODEL_WINDOW, OPTIONS, sink -> {});

        verify(compactService, never()).compact(anyString(), any(), any(), any(), any());
        verify(events, never()).publish(anyString(), any(), any(), any(), any());
    }

    /**
     * Модель без названного окна не сжимается автоматически никогда: доля от выдуманного числа
     * решала бы, когда переписать историю чата.
     */
    @Test
    void aModelWithoutANamedWindowIsNeverCompacted() {
        window(measured(500_000));

        service().compactIfOversized(CONV, RUN, QUESTION, null, OPTIONS, sink -> {});

        verify(compactService, never()).compact(anyString(), any(), any(), any(), any());
    }

    /** Сжимать нечего — окна нет или оно уже состоит из одной сводки. */
    @Test
    void aWindowThatIsAlreadyOneSummaryIsNotCompactedAgain() {
        when(chatHistory.promptRowsBefore(CONV, QUESTION)).thenReturn(List.of(summaryRow(0)));

        service().compactIfOversized(CONV, RUN, QUESTION, MODEL_WINDOW, OPTIONS, sink -> {});

        verify(compactService, never()).compact(anyString(), any(), any(), any(), any());
    }

    /**
     * Главное здесь: граница разметки — последний ряд ОКНА, а не вопрос. Дотянись она до вопроса,
     * прогон начался бы с пустым вопросом — тем самым, ради которого чат и сжался.
     */
    @Test
    void theBoundaryStopsAtTheWindowSoTheQuestionStaysLive() {
        final List<PromptRow> rows = window(measured(9_000));
        final ChatMessageEntity lastRow = rows.getLast().entity();

        service().compactIfOversized(CONV, RUN, QUESTION, MODEL_WINDOW, OPTIONS, sink -> {});

        final CompactService.CompactTarget target = capturedTarget();
        assertThat(target.kind()).isEqualTo(CompactMeta.Kind.AUTO_COMPACT);
        assertThat(target.boundaryPosition()).isEqualTo(lastRow.getPosition());
        assertThat(target.boundaryPosition()).isLessThan(QUESTION);
        // Время рядов — время последнего сжатого: по времени конца раунда плашка встала бы в ленте
        // ПОД вопросом, которого она не касалась.
        assertThat(target.createdAt()).isEqualTo(lastRow.getCreatedAt());
    }

    /** Плашка уезжает вкладкам под runId оплатившего прогона — своего у сжатия нет. */
    @Test
    void theNoticeIsPublishedUnderTheRunThatPaidForIt() {
        window(measured(9_000));

        service().compactIfOversized(CONV, RUN, QUESTION, MODEL_WINDOW, OPTIONS, sink -> {});

        verify(events)
                .publish(
                        eq(CONV),
                        eq(ChatEventType.COMPACT_APPLIED),
                        eq(RUN),
                        isNull(),
                        any(CompactPayload.class));
    }

    /**
     * Раунд, который сводки не дал, провайдер посчитал так же, как удавшийся, а своего ряда у него
     * нет: замер уходит в накопитель идущего прогона — единственное место, откуда он доедет до
     * статистики чата.
     */
    @Test
    void theTokensOfARoundThatWroteNoSummaryGoToTheRunThatPaid() {
        window(measured(9_000));
        final TokenUsage spent = new TokenUsage(9_000, 40, 9_040, 8_000, 0);
        when(compactService.compact(anyString(), any(), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            final CompactService.CompactTarget target = invocation.getArgument(2);
                            target.spentRound()
                                    .record(spent, RunTokenUsage.Tally.EMPTY.with(spent).view());
                            throw new IllegalStateException(
                                    "The model returned an empty compaction");
                        });
        final AtomicReference<TokenUsage> folded = new AtomicReference<>();

        service().compactIfOversized(CONV, RUN, QUESTION, MODEL_WINDOW, OPTIONS, folded::set);

        assertThat(folded.get()).isEqualTo(spent);
        // Раунд упал — плашки нет.
        verify(events, never()).publish(anyString(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------

    private AutoCompactService service() {
        return new AutoCompactService(
                chatHistory,
                compactService,
                new SummaryWriter(mock(ChatMessageRepository.class), transactionManager()),
                events,
                new SummarizeProperties(
                        30_000, 50, 30, 5, 5, Duration.ofMinutes(10), 0.5, 3, 0.8, 4));
    }

    private CompactService.CompactTarget capturedTarget() {
        final ArgumentCaptor<CompactService.CompactTarget> target =
                ArgumentCaptor.forClass(CompactService.CompactTarget.class);
        verify(compactService).compact(eq(CONV), any(), target.capture(), isNull(), eq(OPTIONS));
        return target.getValue();
    }

    /**
     * Живое окно до вопроса. Вес его берётся замером последнего прогона — так же, как его берёт
     * сама проверка: оценка по символам на коротких рядах теста ничего не решает.
     */
    private List<PromptRow> window(RunTokenUsage lastRunUsage) {
        final List<PromptRow> rows = new ArrayList<>();
        for (long position = 0; position < 6; position++) {
            rows.add(row(position, position % 2 == 0 ? MessageType.USER : MessageType.ASSISTANT));
        }
        final PromptRow last = rows.getLast();
        rows.set(
                rows.size() - 1,
                new PromptRow(
                        last.entity().withMeta(ChatMessageMeta.ofUsage(lastRunUsage)),
                        last.text()));
        when(chatHistory.promptRowsBefore(CONV, QUESTION)).thenReturn(rows);
        return rows;
    }

    private static RunTokenUsage measured(long contextTokens) {
        return new RunTokenUsage(contextTokens, contextTokens, 0, 0, contextTokens, 0, 0, 1);
    }

    private static PromptRow row(long position, MessageType type) {
        return new PromptRow(
                new ChatMessageEntity(
                        position + 1,
                        CONV,
                        "message " + position,
                        type,
                        position,
                        false,
                        false,
                        LocalDateTime.now().minusMinutes(10 - position),
                        null),
                "message " + position);
    }

    private static PromptRow summaryRow(long position) {
        return new PromptRow(
                new ChatMessageEntity(
                        position + 1,
                        CONV,
                        "earlier summary",
                        MessageType.ASSISTANT,
                        position,
                        false,
                        true,
                        LocalDateTime.now(),
                        null),
                "earlier summary");
    }

    /** Ответ удавшегося раунда: здесь важно только то, что он есть и уезжает плашкой. */
    private static CompactPayload payload() {
        return new CompactPayload(
                42L, 6, 500, CompactMeta.Kind.AUTO_COMPACT, LocalDateTime.now(), null, null);
    }

    private static PlatformTransactionManager transactionManager() {
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
    }
}
