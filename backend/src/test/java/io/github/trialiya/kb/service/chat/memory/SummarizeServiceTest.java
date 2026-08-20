package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.service.chat.ContextItemService;
import io.github.trialiya.kb.service.chat.memory.ChatMemoryService.PromptRow;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
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
    private ChatMemoryService chatMemoryService;
    private OpenAiChatModel chatModel;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMessageRepository.class);
        chatMemoryService = mock(ChatMemoryService.class);
        chatModel = mock(OpenAiChatModel.class);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
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

        final ArgumentCaptor<ChatMessageEntity> saved =
                ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().isSummary()).isTrue();
        assertThat(saved.getValue().getType()).isEqualTo(MessageType.ASSISTANT);
        assertThat(saved.getValue().getPosition()).isEqualTo(85L);
        assertThat(saved.getValue().getContent())
                .contains("messages 0-86")
                .contains("Continue from message 87");
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

        final ArgumentCaptor<ChatMessageEntity> saved =
                ArgumentCaptor.forClass(ChatMessageEntity.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getMeta()).isNotNull();
        assertThat(saved.getValue().getMeta().project()).isEqualTo("billing");
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

    private void givenLive(List<PromptRow> rows) {
        when(chatMemoryService.promptRows(CONV)).thenReturn(rows);
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
                chatMemoryService,
                new ByteArrayResource("summarize".getBytes()),
                transactionManager(),
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
