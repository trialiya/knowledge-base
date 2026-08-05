package io.github.trialiya.kb.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * Граница живого хвоста в {@code SummarizeService}: два правила перекрытия работают в И, а не в ИЛИ
 * — хвост обязан удержать и {@code overlap-messages} сообщений любого рода, и {@code
 * overlap-user-messages} сообщений пользователя.
 *
 * <p>Цена ошибки — молчаливая и односторонняя: сжатие не падает, а увозит в сводку последние
 * вопросы пользователя, после чего модель отвечает на них по пересказу вместо оригинала.
 */
class SummarizeOverlapTest {

    private static final String CONV = "conv-1";

    private ChatMessageRepository repository;
    private OpenAiChatModel chatModel;

    @BeforeEach
    void setUp() {
        repository = mock(ChatMessageRepository.class);
        chatModel = mock(OpenAiChatModel.class);
        when(chatModel.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(List.of(new Generation(new AssistantMessage("gist")))));
        when(repository
                        .findChatMessageByConversationIdAndSummarizedFalseAndSummaryTrueOrderByCreatedAtAscPositionAsc(
                                anyString()))
                .thenReturn(List.of());
    }

    /**
     * Правило по числу USER-сообщений сдвигает границу раньше правила по общему числу: последние 20
     * сообщений — один вопрос и длинный хвост ответов, поэтому резать по {@code size - overlap}
     * значило бы оставить живым ровно один вопрос из пяти требуемых.
     */
    @Test
    void userOverlapMovesTheCutoffEarlierThanTheCountOverlap() {
        // 0..39 — чередование вопрос/ответ, 40..59 — только ответы модели.
        final List<ChatMessageEntity> live = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            live.add(message(i, i % 2 == 0 ? MessageType.USER : MessageType.ASSISTANT));
        }
        for (int i = 40; i < 60; i++) {
            live.add(message(i, MessageType.ASSISTANT));
        }
        givenLive(live);

        service(properties(10, 5, 1, 1)).doSummarize(CONV);

        // Правило по числу сообщений дало бы границу 50 → после выравнивания на USER — 38.
        // Пятый с конца вопрос стоит на 30 — он и становится первым живым сообщением.
        verify(repository).updateSummarized(CONV, 0L, 29L);
    }

    /**
     * Вопросов в окне меньше, чем требует {@code overlap-user-messages}: удержать пять там, где
     * есть один, невозможно, поэтому правило отступает и граница берётся по числу сообщений. Иначе
     * один вопрос с бесконечным tool-марафоном навсегда заблокировал бы сжатие.
     */
    @Test
    void tooFewUserMessagesFallBackToTheCountBoundary() {
        final List<ChatMessageEntity> live = new ArrayList<>();
        live.add(message(0, MessageType.USER));
        for (int i = 1; i < 60; i++) {
            live.add(message(i, MessageType.ASSISTANT));
        }
        givenLive(live);

        service(properties(10, 5, 1, 1)).doSummarize(CONV);

        verify(repository).updateSummarized(CONV, 0L, 49L);
    }

    /**
     * Обратная сторона правила: сузив сжимаемый срез, оно может увести его под порог запуска —
     * тогда раунд не стартует вовсе. Это осознанный размен, живой хвост важнее лишнего раунда
     * сжатия.
     */
    @Test
    void userOverlapCanShrinkTheSliceBelowTheThresholds() {
        // Все вопросы — в начале диалога: пятый с конца стоит на позиции 5.
        final List<ChatMessageEntity> live = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            live.add(message(i, MessageType.USER));
        }
        for (int i = 10; i < 60; i++) {
            live.add(message(i, MessageType.ASSISTANT));
        }
        givenLive(live);

        service(properties(10, 5, 50, 100_000)).doSummarize(CONV);

        verify(repository, never()).updateSummarized(anyString(), anyLong(), anyLong());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    // -------------------------------------------------------------------------

    private void givenLive(List<ChatMessageEntity> live) {
        when(repository
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                eq(CONV)))
                .thenReturn(live);
    }

    private static ChatMessageEntity message(long position, MessageType type) {
        return new ChatMessageEntity(
                position + 1,
                CONV,
                "message " + position,
                type,
                position,
                false,
                false,
                LocalDateTime.now(),
                null);
    }

    private static SummarizeProperties properties(
            int overlapMessages,
            int overlapUserMessages,
            int messageThreshold,
            int tokenThreshold) {
        return new SummarizeProperties(
                tokenThreshold, messageThreshold, overlapMessages, overlapUserMessages, 5, 4);
    }

    private SummarizeService service(SummarizeProperties properties) {
        final ContextItemService contextItemService = mock(ContextItemService.class);
        when(contextItemService.render(anyString(), anyList())).thenReturn("");
        return new SummarizeService(
                chatModel,
                repository,
                new ByteArrayResource("summarize".getBytes()),
                transactionManager(),
                properties,
                contextItemService);
    }

    /** Транзакции здесь ничего не защищают — тест смотрит только на границу среза. */
    private static PlatformTransactionManager transactionManager() {
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final TransactionStatus status = new SimpleTransactionStatus();
        when(manager.getTransaction(any())).thenReturn(status);
        return manager;
    }
}
