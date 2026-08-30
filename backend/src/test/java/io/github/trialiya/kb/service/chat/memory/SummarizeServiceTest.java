package io.github.trialiya.kb.service.chat.memory;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.CompactMeta;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.memory.ChatHistoryService.PromptRow;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
 * Проводка вокруг {@link SummarizeWindow}: что раунд действительно доходит до разметки и до строки
 * сводки, а на пустом результате модели — не доходит. Границы и пороги здесь не проверяются, для
 * них есть {@code SummarizeWindowTest} — там та же арифметика стоит без единого мока.
 */
class SummarizeServiceTest {

    private static final String CONV = "conv-1";

    /** Боевые значения из {@code application.yaml}. */
    private static final SummarizeProperties PRODUCTION =
            new SummarizeProperties(30_000, 50, 30, 5, 5, 4);

    private ChatMessageRepository repository;
    private ChatTopicRepository chatTopicRepository;
    private ChatHistoryService chatHistory;
    private OpenAiChatModel chatModel;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMessageRepository.class);
        chatTopicRepository = mock(ChatTopicRepository.class);
        chatHistory = mock(ChatHistoryService.class);
        chatModel = mock(OpenAiChatModel.class);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        // Плашка ссылается на id сохранённой сводки, поэтому save обязан вернуть строку, а не
        // умолчательный null мока.
        when(repository.save(any(ChatMessageEntity.class))).thenAnswer(call -> call.getArgument(0));
        answerWith("summary of the earlier conversation");
    }

    /**
     * Раунд помечает сжатое по позициям — до первого оставленного сообщения — и кладёт сводку на
     * позицию последнего сжатого, чтобы она встала перед живым хвостом при следующем чтении.
     *
     * <p>Окно нарочно с протокольными TOOL-строками: их суммаризатор не читает, но разметка обязана
     * их накрыть, иначе хвост сжатого хода остался бы живым и осиротевшим. Из-за них размеченная
     * граница (86) на позицию дальше последнего прочитанного сообщения (85) — и заголовок сводки
     * обязан называть именно её, иначе «продолжай с 86» указывает на уже сжатую строку.
     */
    @Test
    void aRoundMarksTheCompressedRangeAndStoresTheSummary() {
        givenLive(turns(44));

        service().doSummarize(CONV);

        // 88 строк промпта, 88 - 30 = 58 по числу сообщений; граница — вопрос на позиции 87.
        verify(repository).updateSummarized(CONV, 0L, 86L);

        final ChatMessageEntity summary = savedRows().getFirst();
        assertThat(summary.isSummary()).isTrue();
        assertThat(summary.getType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(summary.getPosition()).isEqualTo(85L);
        assertThat(summary.getContent())
                .contains("messages 0-86")
                .contains("Continue from message 87");
    }

    /**
     * За сводкой встаёт видимая плашка: без неё сжатие проходило бы молча — часть разговора уезжает
     * из контекста, а лента выглядит нетронутой. Вид отличает её от {@code /compact}: сжато начало
     * истории, живой хвост под плашкой остался.
     */
    @Test
    void aRoundLeavesAVisibleNoticeOfItsOwnKind() {
        givenLive(turns(44));

        service().doSummarize(CONV);

        final ChatMessageEntity notice = savedRows().get(1);
        assertThat(notice.isSummary()).isFalse();
        assertThat(notice.isSummarized()).isTrue();
        // Сразу за сводкой (85): в ленте плашка встаёт между сжатым и живым хвостом.
        assertThat(notice.getPosition()).isEqualTo(86L);
        final CompactMeta compact = requireNonNull(notice.getMeta()).compact();
        assertThat(compact).isNotNull();
        assertThat(compact.kind()).isEqualTo(CompactMeta.Kind.SUMMARIZE);
        assertThat(compact.messages()).isEqualTo(58);
        assertThat(compact.summaryChars())
                .isEqualTo("summary of the earlier conversation".length());
    }

    /**
     * Токены раунда ложатся в мету плашки — тем же полем, что и у ответа: фоновое сжатие тратит те
     * же деньги, что и прогон, и в итог по чату обязано попадать наравне с ним.
     */
    @Test
    void theRoundsTokensAreRecordedOnTheNoticeRow() {
        givenLive(turns(44));
        answerWith(
                "summary of the earlier conversation",
                new DefaultUsage(48_000, 900, 48_900, null, 40_000L, 0L));

        service().doSummarize(CONV);

        final RunTokenUsage usage = requireNonNull(savedRows().get(1).getMeta()).usage();
        assertThat(usage).isNotNull();
        assertThat(usage.promptTokens()).isEqualTo(48_000);
        assertThat(usage.cacheReadTokens()).isEqualTo(40_000);
        assertThat(usage.outputTokens()).isEqualTo(900);
        assertThat(usage.modelCalls()).isEqualTo(1);
    }

    /** Эндпоинт без замера — плашка без замера: «неизвестно» это не ноль. */
    @Test
    void anUnmeasuredRoundLeavesTheNoticeWithoutTokens() {
        givenLive(turns(44));

        service().doSummarize(CONV);

        assertThat(requireNonNull(savedRows().get(1).getMeta()).usage()).isNull();
    }

    /**
     * Сводка несёт проект, на котором закончилась сжатая часть: маркер смены проекта сжимается
     * вместе со своим сообщением, а meta сводки остаётся его следом.
     */
    @Test
    void theSummaryRowCarriesTheProjectTheCompressedSliceEndedOn() {
        final List<PromptRow> live = new ArrayList<>(turns(44));
        // Вопрос внутри сжимаемой части (позиции 0..86) сменил проект.
        live.set(30, switchRow(30, "kb", "billing"));
        givenLive(live);

        service().doSummarize(CONV);

        assertThat(requireNonNull(savedRows().getFirst().getMeta()).project()).isEqualTo("billing");
    }

    /** Пороги не достигнуты — ни модель, ни репозиторий трогать не за чем. */
    @Test
    void nothingHappensWhenNoThresholdIsReached() {
        givenLive(turns(15));

        service().doSummarize(CONV);

        verify(chatModel, never()).call(any(Prompt.class));
        verify(repository, never()).updateSummarized(anyString(), anyLong(), anyLong());
    }

    /**
     * Модель вернула пустой ответ — раунд обязан пропасть целиком. Разметить сообщения сжатыми, не
     * сохранив сводку, значит потерять их из истории безвозвратно.
     */
    @Test
    void anEmptyModelAnswerLeavesTheHistoryUntouched() {
        givenLive(turns(44));
        answerWith("   ");

        service().doSummarize(CONV);

        verify(repository, never()).updateSummarized(anyString(), anyLong(), anyLong());
        verify(repository, never()).save(any());
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

    /** Ряды раунда в порядке записи: сводка, за ней плашка. */
    private List<ChatMessageEntity> savedRows() {
        final ArgumentCaptor<ChatMessageEntity> saved =
                ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(repository, times(2)).save(saved.capture());
        return saved.getAllValues();
    }

    private void givenLive(List<PromptRow> rows) {
        when(chatHistory.promptRows(CONV)).thenReturn(rows);
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
