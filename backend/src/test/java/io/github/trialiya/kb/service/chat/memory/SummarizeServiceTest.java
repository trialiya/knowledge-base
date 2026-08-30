package io.github.trialiya.kb.service.chat.memory;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.ChatPendingSummaryEntity;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.ProjectSpan;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Проводка вокруг {@link SummarizeWindow}: что раунд действительно доходит до парковки готовой
 * сводки, а на пустом результате модели — не доходит. Границы и пороги здесь не проверяются, для
 * них есть {@code SummarizeWindowTest} — там та же арифметика стоит без единого мока; что
 * припаркованное потом попадает в историю, проверяет {@code PendingSummaryServiceTest}.
 */
class SummarizeServiceTest {

    private static final String CONV = "conv-1";

    /** Боевые значения из {@code application.yaml}. */
    private static final SummarizeProperties PRODUCTION =
            new SummarizeProperties(30_000, 50, 30, 5, 5, Duration.ofMinutes(10), 0.5, 0.8, 4);

    private ChatMessageRepository repository;
    private ChatTopicRepository chatTopicRepository;
    private ChatHistoryService chatHistory;
    private OpenAiChatModel chatModel;
    private PendingSummaryService pendingSummaries;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMessageRepository.class);
        chatTopicRepository = mock(ChatTopicRepository.class);
        chatHistory = mock(ChatHistoryService.class);
        chatModel = mock(OpenAiChatModel.class);
        pendingSummaries = mock(PendingSummaryService.class);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        answerWith("summary of the earlier conversation");
    }

    /**
     * Раунд паркует диапазон, который перестанет быть живым — до первого оставленного сообщения, —
     * и позицию сводки: позицию последнего сжатого, чтобы при следующем чтении сводка встала перед
     * живым хвостом.
     *
     * <p>Окно нарочно с протокольными TOOL-строками: их суммаризатор не читает, но разметка обязана
     * их накрыть, иначе хвост сжатого хода остался бы живым и осиротевшим. Из-за них размеченная
     * граница (86) на позицию дальше последнего прочитанного сообщения (85) — и заголовок сводки
     * обязан называть именно её, иначе «продолжай с 86» указывает на уже сжатую строку.
     */
    @Test
    void aRoundParksTheCompressedRangeAndTheSummary() {
        givenLive(turns(44));

        service().doSummarize(CONV);

        // 88 строк промпта, 88 - 30 = 58 по числу сообщений; граница — вопрос на позиции 87.
        final SummaryWriter.SummaryRow row = parked();
        assertThat(row.startPosition()).isZero();
        assertThat(row.endPosition()).isEqualTo(86L);
        assertThat(row.position()).isEqualTo(85L);
        assertThat(row.text()).contains("messages 0-86").contains("Continue from message 87");
        // Историю раунд не трогает: разметку и ряды напишет применение.
        verify(repository, never()).updateSummarized(anyString(), anyLong(), anyLong());
        verify(repository, never()).save(any());
    }

    /**
     * Числа будущей плашки паркуются вместе со сводкой: без плашки сжатие прошло бы молча — часть
     * разговора уезжает из контекста, а лента выглядит нетронутой. Вид отличает её от {@code
     * /compact}: сжато начало истории, живой хвост под плашкой останется.
     */
    @Test
    void aRoundParksTheNumbersOfItsFutureNotice() {
        givenLive(turns(44));

        service().doSummarize(CONV);

        final SummaryWriter.CompactStats stats = parkedStats();
        assertThat(stats.kind()).isEqualTo(CompactMeta.Kind.SUMMARIZE);
        assertThat(stats.messages()).isEqualTo(58);
        assertThat(stats.summaryChars()).isEqualTo("summary of the earlier conversation".length());
    }

    /**
     * Токены раунда паркуются вместе с ним: фоновое сжатие тратит те же деньги, что и прогон, и в
     * итог по чату обязано попадать наравне с ним — а до применения ждать иногда долго.
     */
    @Test
    void theRoundsTokensAreParkedWithIt() {
        givenLive(turns(44));
        answerWith(
                "summary of the earlier conversation",
                new DefaultUsage(48_000, 900, 48_900, null, 40_000L, 0L));

        service().doSummarize(CONV);

        final RunTokenUsage usage = requireNonNull(parkedStats().usage());
        assertThat(usage.promptTokens()).isEqualTo(48_000);
        assertThat(usage.cacheReadTokens()).isEqualTo(40_000);
        assertThat(usage.outputTokens()).isEqualTo(900);
        assertThat(usage.modelCalls()).isEqualTo(1);
    }

    /** Эндпоинт без замера — парковка без замера: «неизвестно» это не ноль. */
    @Test
    void anUnmeasuredRoundParksWithoutTokens() {
        givenLive(turns(44));

        service().doSummarize(CONV);

        assertThat(parkedStats().usage()).isNull();
    }

    /**
     * Сводка несёт проект, на котором закончилась сжатая часть: маркер смены проекта сжимается
     * вместе со своим сообщением, а след проектов остаётся на сводке.
     */
    @Test
    void theSummaryCarriesTheProjectTheCompressedSliceEndedOn() {
        final List<PromptRow> live = new ArrayList<>(turns(44));
        // Вопрос внутри сжимаемой части (позиции 0..86) сменил проект.
        live.set(30, switchRow(30, "kb", "billing"));
        givenLive(live);

        service().doSummarize(CONV);

        assertThat(parked().trace().lastProject()).isEqualTo("billing");
    }

    /** Пороги не достигнуты — ни модель, ни парковку трогать не за чем. */
    @Test
    void nothingHappensWhenNoThresholdIsReached() {
        givenLive(turns(15));

        service().doSummarize(CONV);

        verify(chatModel, never()).call(any(Prompt.class));
        verify(pendingSummaries, never()).park(anyString(), any(), any());
    }

    /**
     * Прошлая сводка ещё ждёт применения — значит, сжатый ею кусок в промпте пока живой, и сжать
     * его второй раз значит заплатить дважды за одно и то же. Раунд поэтому идёт не по нему:
     * припаркованное он получает контекстом, а сжимает накопившееся ЗА ним.
     */
    @Test
    void aRoundCompressesWhatCameAfterTheParkedSummary() {
        givenLive(turns(44));
        givenParked(parkedSummary(0L, 5L));

        service().doSummarize(CONV);

        // Первый живой ряд за припаркованным куском — с него и начинается новый.
        assertThat(parked().startPosition()).isEqualTo(6L);
        final String prompt = promptText();
        assertThat(prompt).contains("do not re-summarize").contains("parked summary 0-5");
        // Ряды сжатого куска в раунд не попадают ни одной строкой — иначе это оплаченный дубль.
        assertThat(prompt).doesNotContain("[msg:0]").doesNotContain("[msg:5]");
        assertThat(prompt).contains("[msg:6]");
    }

    /**
     * Применённые сводки лежат по позициям ДО припаркованного куска, но выбрасывать их вместе с ним
     * нельзя: это уже сжатое прошлое, и без него раунд напишет сводку без начала разговора.
     */
    @Test
    void anAppliedSummaryStaysInContextNextToTheParkedOne() {
        final List<PromptRow> live = new ArrayList<>(turns(44));
        live.set(0, summaryRow(0L, "applied summary of the beginning"));
        givenLive(live);
        givenParked(parkedSummary(1L, 5L));

        service().doSummarize(CONV);

        assertThat(promptText())
                .contains("applied summary of the beginning")
                .contains("parked summary 1-5");
    }

    /**
     * След проектов наследуется по цепочке сводок, и припаркованная — такое же звено: потеряй раунд
     * её спаны, и «в каком репозитории читан файл из сообщения 3» перестало бы иметь ответ.
     */
    @Test
    void theParkedSummaryPassesItsProjectSpansOn() {
        givenLive(turns(44));
        givenParked(
                parkedSummary(
                        0L,
                        5L,
                        ChatMessageMeta.ofProject(
                                "billing", List.of(new ProjectSpan("billing", 0, 5)))));

        service().doSummarize(CONV);

        // Ряды после припаркованной сводки своего проекта не называют, поэтому её спан не
        // копируется, а продолжается до конца нового куска: важно, что он начат с нуля и «billing».
        assertThat(parked().trace().spans())
                .first()
                .satisfies(
                        span -> {
                            assertThat(span.project()).isEqualTo("billing");
                            assertThat(span.from()).isZero();
                        });
    }

    /**
     * Схлопывание в метасводку ждёт применения очереди: метасводка заменяет собой перечисленные ею
     * сводки, а у припаркованной замена означала бы потерянный ряд — с ним её плашку и деньги её
     * раунда, которых больше нигде нет. Порог считает применённые сводки, и припаркованные его не
     * приближают: в промпте их ещё нет.
     */
    @Test
    void aParkedQueueIsNeverCollapsedIntoAMetaSummary() {
        givenLive(turns(44));
        givenParked(
                parkedSummary(0L, 1L),
                parkedSummary(2L, 3L),
                parkedSummary(4L, 5L),
                parkedSummary(6L, 7L));

        service().doSummarize(CONV);

        final String prompt = promptText();
        assertThat(prompt).contains("do not re-summarize");
        assertThat(prompt).doesNotContain("SINGLE merged summary");
    }

    /**
     * Модель вернула пустой ответ — раунд обязан пропасть целиком. Припарковать пустую сводку
     * значит договориться потерять сжатые ею сообщения позже.
     */
    @Test
    void anEmptyModelAnswerParksNothing() {
        givenLive(turns(44));
        answerWith("   ");

        service().doSummarize(CONV);

        verify(pendingSummaries, never()).park(anyString(), any(), any());
    }

    // -------------------------------------------------------------------------

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

    private SummaryWriter.SummaryRow parked() {
        final ArgumentCaptor<SummaryWriter.SummaryRow> row =
                ArgumentCaptor.forClass(SummaryWriter.SummaryRow.class);
        verify(pendingSummaries).park(eq(CONV), row.capture(), any());
        return row.getValue();
    }

    private SummaryWriter.CompactStats parkedStats() {
        final ArgumentCaptor<SummaryWriter.CompactStats> stats =
                ArgumentCaptor.forClass(SummaryWriter.CompactStats.class);
        verify(pendingSummaries).park(eq(CONV), any(), stats.capture());
        return stats.getValue();
    }

    private void givenLive(List<PromptRow> rows) {
        when(chatHistory.promptRows(CONV)).thenReturn(rows);
    }

    private void givenParked(ChatPendingSummaryEntity... parked) {
        when(pendingSummaries.parked(CONV)).thenReturn(List.of(parked));
    }

    /** Припаркованная сводка сжатого куска — ровно то, что вернёт {@code PendingSummaryService}. */
    private static ChatPendingSummaryEntity parkedSummary(long start, long end) {
        return parkedSummary(start, end, null);
    }

    private static ChatPendingSummaryEntity parkedSummary(
            long start, long end, ChatMessageMeta meta) {
        return new ChatPendingSummaryEntity(
                start + 1,
                CONV,
                start,
                end,
                end,
                LocalDateTime.now(),
                "parked summary " + start + "-" + end,
                2,
                64,
                meta,
                LocalDateTime.now());
    }

    /** Уже применённая сводка — обычный ряд истории с флагом {@code summary}. */
    private static PromptRow summaryRow(long position, String text) {
        final ChatMessageEntity entity =
                new ChatMessageEntity(
                        position + 1,
                        CONV,
                        text,
                        MessageType.ASSISTANT,
                        position,
                        false,
                        true,
                        LocalDateTime.now(),
                        null);
        return new PromptRow(entity, text);
    }

    /** Текст запроса к суммаризатору — то, по чему видно, что именно раунд сжимает. */
    private String promptText() {
        final ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        return prompt.getValue().getInstructions().stream()
                .map(message -> String.valueOf(message.getText()))
                .collect(Collectors.joining("\n"));
    }

    /** Ходы по три позиции: вопрос, ответ модели и пустая протокольная TOOL-строка за ним. */
    private static List<PromptRow> turns(int count) {
        final List<PromptRow> live = new ArrayList<>();
        for (int turn = 0; turn < count; turn++) {
            live.add(row(turn * 3, MessageType.USER, "question " + turn));
            live.add(row(turn * 3 + 1, MessageType.ASSISTANT, "answer " + turn));
            live.add(row(turn * 3 + 2, MessageType.TOOL, ""));
        }
        return live;
    }

    /** Вопрос, которым чат перешёл с {@code from} на {@code to}. */
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

    private SummarizeService service() {
        return new SummarizeService(
                chatModel,
                repository,
                chatHistory,
                chatTopicRepository,
                new ByteArrayResource("summarize".getBytes()),
                new SummaryWriter(repository, transactionManager()),
                pendingSummaries,
                PRODUCTION,
                mock(ContextItemService.class));
    }

    /** Транзакции здесь ничего не защищают — тест смотрит только на вызовы репозитория. */
    private static PlatformTransactionManager transactionManager() {
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
    }
}
