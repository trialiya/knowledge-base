package io.github.trialiya.kb.service.chat.memory;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.model.chat.dto.ChatEventType;
import io.github.trialiya.kb.model.chat.dto.CompactPayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatPendingSummaryEntity;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.ProjectSpan;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatPendingSummaryRepository;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Когда написанная сводка попадает в историю. Проверяется здесь именно решение — по паузе, по
 * размеру контекста, по команде {@code /compact}, — а не арифметика окна: её считает раунд, и
 * применение уже ничего не пересчитывает (см. {@code SummarizeServiceTest}).
 */
class PendingSummaryServiceTest {

    private static final String CONV = "conv-1";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 30, 12, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    /** Боевые значения из {@code application.yaml}. */
    private static final SummarizeProperties PRODUCTION =
            new SummarizeProperties(30_000, 50, 30, 5, 5, Duration.ofMinutes(10), 0.5, 0.8, 4);

    private static final RunTokenUsage ROUND_USAGE =
            new RunTokenUsage(48_900, 48_000, 0, 900, 48_000, 40_000, 0, 1);

    private ChatPendingSummaryRepository parkedRepository;
    private ChatMessageRepository chatMessages;
    private ChatEventService events;
    private PlatformTransactionManager transactions;
    private PendingSummaryService service;

    @BeforeEach
    void setUp() {
        parkedRepository = mock(ChatPendingSummaryRepository.class);
        chatMessages = mock(ChatMessageRepository.class);
        events = mock(ChatEventService.class);
        when(chatMessages.save(any(ChatMessageEntity.class))).thenAnswer(c -> c.getArgument(0));
        when(parkedRepository.claim(anyLong())).thenReturn(1);
        transactions = transactionManager();
        service =
                new PendingSummaryService(
                        parkedRepository,
                        chatMessages,
                        new SummaryWriter(chatMessages, transactions),
                        events,
                        PRODUCTION,
                        CLOCK,
                        transactions);
    }

    /**
     * Парковка несёт всё, чем потом будут записаны оба ряда: позиции и текст — строке-сводке, числа
     * и замер — плашке. Считать их позже не из чего: раунд, который читал и оплатил сжатый кусок, к
     * тому времени давно кончился.
     */
    @Test
    void parkingKeepsEverythingTheFutureRowsWillNeed() {
        service.park(CONV, row(), stats(ROUND_USAGE));

        final ArgumentCaptor<ChatPendingSummaryEntity> saved =
                ArgumentCaptor.forClass(ChatPendingSummaryEntity.class);
        verify(parkedRepository).save(saved.capture());
        final ChatPendingSummaryEntity parked = saved.getValue();
        assertThat(parked.getStartPosition()).isZero();
        assertThat(parked.getEndPosition()).isEqualTo(86L);
        assertThat(parked.getSummaryPosition()).isEqualTo(85L);
        assertThat(parked.getText()).isEqualTo("the summary");
        assertThat(parked.getMessages()).isEqualTo(58);
        assertThat(parked.getSummaryChars()).isEqualTo(4096);
        assertThat(requireNonNull(parked.getMeta()).project()).isEqualTo("billing");
        assertThat(requireNonNull(parked.getMeta()).usage()).isEqualTo(ROUND_USAGE);
    }

    /**
     * Пауза — обычный повод: кэш промпта у провайдера всё равно остыл, и подмена начала истории
     * достаётся бесплатно. Записывается тогда всё сразу — разметка, сводка и видимая плашка.
     */
    @Test
    void aPausedChatGetsItsSummaryFolded() {
        givenParked(ROUND_USAGE);
        givenLastRowAt(NOW.minusMinutes(11));

        service.applyIfPaused(CONV);

        verify(chatMessages).updateSummarized(CONV, 0L, 86L);
        final List<ChatMessageEntity> rows = savedRows();
        assertThat(rows.getFirst().isSummary()).isTrue();
        assertThat(rows.getFirst().getPosition()).isEqualTo(85L);
        assertThat(requireNonNull(rows.getFirst().getMeta()).project()).isEqualTo("billing");
        final ChatMessageEntity notice = rows.get(1);
        assertThat(notice.isSummarized()).isTrue();
        final CompactMeta compact = requireNonNull(notice.getMeta()).compact();
        assertThat(requireNonNull(compact).kind()).isEqualTo(CompactMeta.Kind.SUMMARIZE);
        assertThat(compact.messages()).isEqualTo(58);
        assertThat(requireNonNull(notice.getMeta()).usage()).isEqualTo(ROUND_USAGE);
    }

    /**
     * Очередь применяется целиком, по порядку и внутри одной транзакции. Порознь нельзя: её сводки
     * — куски одной непрерывной головы разговора, и остановка на середине оставила бы в промпте
     * живой кусок между двумя сжатыми.
     */
    @Test
    void theWholeQueueIsAppliedAtOnceAndInOrder() {
        when(parkedRepository.findByConversationIdOrderByStartPositionAsc(CONV))
                .thenReturn(List.of(queued(1L, 0L, 40L), queued(2L, 41L, 86L)));
        givenLastRowAt(NOW.minusMinutes(11));

        service.applyIfPaused(CONV);

        final InOrder order = inOrder(transactions, parkedRepository, chatMessages);
        // Транзакция открыта до первой заявки — как и у одиночного применения.
        order.verify(transactions).getTransaction(any());
        order.verify(parkedRepository).claim(1L);
        order.verify(chatMessages).updateSummarized(CONV, 0L, 40L);
        order.verify(parkedRepository).claim(2L);
        order.verify(chatMessages).updateSummarized(CONV, 41L, 86L);
        // Плашка у каждой своя: у них разные куски и разные деньги.
        verify(events, times(2)).publish(anyString(), any(), any(), any(), any());
    }

    /** Разговор идёт — кэш горячий, и свёртка стоила бы целого неоплаченного запроса. */
    @Test
    void aLiveChatKeepsItsSummaryParked() {
        givenParked(ROUND_USAGE);
        givenLastRowAt(NOW.minusMinutes(2));

        service.applyIfPaused(CONV);

        verifyNothingWritten();
    }

    /** Плашка встаёт в середину ленты, и вкладки узнают о ней событием, а не перезагрузкой. */
    @Test
    void applyingTellsTheOpenTabs() {
        givenParked(ROUND_USAGE);
        givenLastRowAt(NOW.minusMinutes(11));

        service.applyIfPaused(CONV);

        final ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(events)
                .publish(
                        org.mockito.ArgumentMatchers.eq(CONV),
                        org.mockito.ArgumentMatchers.eq(ChatEventType.COMPACT_APPLIED),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull(),
                        payload.capture());
        assertThat(payload.getValue()).isInstanceOf(CompactPayload.class);
        assertThat(((CompactPayload) payload.getValue()).kind())
                .isEqualTo(CompactMeta.Kind.SUMMARIZE);
    }

    /**
     * Паузы не дождались, но ждать больше нельзя: контекст перевалил за половину окна модели. Здесь
     * потерянный кэш — меньшая половина сделки.
     */
    @Test
    void anOversizedContextStopsWaitingForThePause() {
        givenParked(ROUND_USAGE);

        service.applyIfOversized(CONV, 70_000, 128_000);

        verify(chatMessages).updateSummarized(CONV, 0L, 86L);
    }

    /** До порога ждём паузы: сжатие раньше времени — это выброшенный кэш и ничего больше. */
    @Test
    void aContextBelowTheThresholdKeepsWaiting() {
        givenParked(ROUND_USAGE);

        service.applyIfOversized(CONV, 40_000, 128_000);

        verifyNothingWritten();
    }

    /**
     * Окно модели в конфигурации не названо — порога у чата нет вовсе: доля неизвестного числа
     * ничего не значит, и выдумывать её за конфигурацию нельзя.
     */
    @Test
    void aModelWithoutAKnownWindowHasNoSizeThreshold() {
        givenParked(ROUND_USAGE);

        service.applyIfOversized(CONV, 900_000, null);

        verifyNothingWritten();
    }

    /** Прогон без замера судить не по чему — такой чат дождётся паузы. */
    @Test
    void anUnmeasuredRunIsNoReasonToApply() {
        givenParked(ROUND_USAGE);

        service.applyIfOversized(CONV, 0, 128_000);

        verifyNothingWritten();
    }

    /**
     * Заявку на строку перехватил кто-то другой — пауза и предел контекста могут сойтись на одном
     * чате, и без заявки одна сводка легла бы в историю дважды.
     */
    @Test
    void aLostClaimWritesNothing() {
        givenParked(ROUND_USAGE);
        givenLastRowAt(NOW.minusMinutes(11));
        when(parkedRepository.claim(anyLong())).thenReturn(0);

        service.applyIfPaused(CONV);

        verifyNothingWritten();
    }

    /**
     * {@code /compact} заменил своей сводкой весь контекст — отложенной больше нечего описывать.
     */
    @Test
    void compactionThrowsTheParkedSummaryAway() {
        when(parkedRepository.deleteByConversationId(CONV)).thenReturn(1);

        service.discard(CONV);

        verify(parkedRepository).deleteByConversationId(CONV);
    }

    /**
     * Запись упала. Два требования сразу, и оба про то, что применение — оптимизация цены, а не
     * часть чьей-то работы: заявка на строку откатывается вместе с записью (сводку писала модель, и
     * второй раз её никто не напишет), а наружу ничего не летит — зовут применение с путей, где
     * прогон начинается или заканчивается, и уронить их ему нечем.
     */
    @Test
    void aFailedWriteKeepsTheSummaryParkedAndStaysQuiet() {
        givenParked(ROUND_USAGE);
        givenLastRowAt(NOW.minusMinutes(11));
        when(chatMessages.save(any(ChatMessageEntity.class)))
                .thenThrow(new IllegalStateException("the database went away"));

        service.applyIfPaused(CONV);

        // Порядком, а не одним фактом отката: откат сам по себе будет и у записи — транзакция у
        // неё своя, и на моке менеджера она с внешней не сливается. Регресс здесь ровно один —
        // заявка снаружи транзакции, — и виден он только тем, что транзакция ОТКРЫТА до заявки.
        final InOrder order = inOrder(transactions, parkedRepository);
        order.verify(transactions).getTransaction(any());
        order.verify(parkedRepository).claim(anyLong());
        order.verify(transactions, atLeastOnce()).rollback(any());
        verify(events, never()).publish(anyString(), any(), any(), any(), any());
    }

    /** Пустой чат паузой не считается: мерить её не от чего, а сжимать в нём нечего. */
    @Test
    void anEmptyChatIsNoPause() {
        givenParked(ROUND_USAGE);
        when(chatMessages.lastCreatedAt(CONV)).thenReturn(Optional.empty());

        service.applyIfPaused(CONV);

        verifyNothingWritten();
    }

    // -------------------------------------------------------------------------

    private void givenParked(RunTokenUsage usage) {
        service.park(CONV, row(), stats(usage));
        final ArgumentCaptor<ChatPendingSummaryEntity> saved =
                ArgumentCaptor.forClass(ChatPendingSummaryEntity.class);
        verify(parkedRepository).save(saved.capture());
        when(parkedRepository.findByConversationIdOrderByStartPositionAsc(CONV))
                .thenReturn(List.of(saved.getValue()));
    }

    private void givenLastRowAt(LocalDateTime at) {
        when(chatMessages.lastCreatedAt(CONV)).thenReturn(Optional.of(at));
    }

    /** Ряды применения в порядке записи: сводка, за ней плашка. */
    private List<ChatMessageEntity> savedRows() {
        final ArgumentCaptor<ChatMessageEntity> saved =
                ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(chatMessages, times(2)).save(saved.capture());
        return saved.getAllValues();
    }

    private void verifyNothingWritten() {
        verify(chatMessages, never()).updateSummarized(anyString(), anyLong(), anyLong());
        verify(chatMessages, never()).save(any(ChatMessageEntity.class));
        verify(events, never()).publish(anyString(), any(), any(), any(), any());
    }

    /** Строка очереди: сжатый кусок, готовый к записи. */
    private static ChatPendingSummaryEntity queued(long id, long start, long end) {
        return new ChatPendingSummaryEntity(
                id,
                CONV,
                start,
                end,
                end - 1,
                NOW.minusHours(1),
                "summary " + start + "-" + end,
                20,
                2048,
                null,
                NOW.minusHours(1));
    }

    private static SummaryWriter.SummaryRow row() {
        return new SummaryWriter.SummaryRow(
                CONV,
                0L,
                86L,
                85L,
                NOW.minusHours(1),
                "the summary",
                new ProjectTrace(List.of(new ProjectSpan("billing", 0, 86)), "billing"));
    }

    private static SummaryWriter.CompactStats stats(RunTokenUsage usage) {
        return new SummaryWriter.CompactStats(CompactMeta.Kind.SUMMARIZE, 58, 4096, usage);
    }

    /**
     * Транзакция настоящей работы не делает — сохранение здесь мок, — но откат по ней виден, и на
     * нём держится проверка «упавшая запись оставляет сводку припаркованной».
     */
    private static PlatformTransactionManager transactionManager() {
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
    }
}
