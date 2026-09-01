package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.chat.entity.RunTokenUsage;
import io.github.trialiya.kb.model.tool.ToolData;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import io.github.trialiya.kb.service.chat.context.AttachmentService;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.runtime.RunRegistry;
import io.github.trialiya.kb.support.ActiveProjectNotices;
import io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.MessageType;

/**
 * {@link ChatHistoryService#markRunResult}: чем помечены ответы прогона. Ни плашки вызовов, ни
 * модель, ни токены на записи не известны — advisor памяти приносит только сообщения, — поэтому их
 * проставляют по завершении прогона, поверх уже сохранённых рядов, одним заходом. Плашки достаются
 * сегментам, которые звали инструменты, модель — каждому ряду прогона, токены — одному последнему.
 */
class ChatHistoryRunResultTest {

    private static final String CONV = "conv-1";
    private static final String RUN = "run-1";
    private static final String MODEL = "gpt-5";
    private static final RunTokenUsage USAGE =
            new RunTokenUsage(12_400, 11_400, 700, 320, 31_000, 0, 0, 31_320, 3);

    private ChatMessageRepository messageRepo;
    private ChatHistoryService history;

    @BeforeEach
    void setUp() {
        messageRepo = mock(ChatMessageRepository.class);
        history =
                new ChatHistoryService(
                        messageRepo,
                        new ContextItemService(mock(AttachmentService.class)),
                        new ToolCallService(messageRepo, mock(ToolCallIndexRepository.class)),
                        new ToolCallEventPublisher(mock(ChatEventService.class), new RunRegistry()),
                        ActiveProjectNotices.silent());
    }

    private static ChatMessageEntity row(
            long id, MessageType type, @Nullable ChatMessageMeta meta) {
        return new ChatMessageEntity(
                id, CONV, "text", type, id, false, false, LocalDateTime.now(), meta);
    }

    /**
     * ASSISTANT-сегмент, который позвал один инструмент: плашек ещё нет, протокольный вызов есть.
     */
    private static ChatMessageEntity segment(long id, String callId, String tool) {
        return new ChatMessageEntity(
                id,
                CONV,
                "text",
                MessageType.ASSISTANT,
                id,
                false,
                false,
                LocalDateTime.now(),
                null,
                new ToolData(List.of(new ToolData.Call(callId, "function", tool, "{}")), null));
    }

    private static ToolInvocation invocation(String tool) {
        return new ToolInvocation(
                tool,
                Map.of(),
                ToolInvocationStatus.OK,
                null,
                null,
                "gist",
                "{}",
                "результат",
                0,
                null);
    }

    private void history(ChatMessageEntity... rows) {
        when(messageRepo
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                CONV))
                .thenReturn(List.of(rows));
    }

    private void mark(RunTokenUsage usage) {
        history.markRunResult(CONV, RUN, MODEL, usage, List.of());
    }

    private List<ChatMessageEntity> saved() {
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Iterable<ChatMessageEntity>> captor =
                ArgumentCaptor.forClass(Iterable.class);
        verify(messageRepo).saveAll(captor.capture());
        final List<ChatMessageEntity> rows = new ArrayList<>();
        captor.getValue().forEach(rows::add);
        return rows;
    }

    @Test
    void marksAssistantRowsOfTheCurrentTurnOnly() {
        history(
                row(1, MessageType.ASSISTANT, null), // прошлый ход — до последнего вопроса
                row(2, MessageType.USER, null),
                row(3, MessageType.ASSISTANT, null),
                row(4, MessageType.TOOL, null),
                row(5, MessageType.ASSISTANT, null));

        mark(RunTokenUsage.EMPTY);

        // Ответ прошлого хода мог быть написан другой моделью — его не трогаем; TOOL-ряд
        // никто не «писал», подписи под ним нет.
        assertThat(saved()).extracting(ChatMessageEntity::getId).containsExactly(3L, 5L);
        assertThat(saved())
                .allSatisfy(
                        row -> {
                            assertThat(row.getMeta()).isNotNull();
                            assertThat(row.getMeta().model()).isEqualTo(MODEL);
                            assertThat(row.getMeta().runId()).isEqualTo(RUN);
                        });
    }

    /**
     * Плашки и модель проставляются одной записью. Порознь это была бы пара с негласным порядком:
     * плашки ищут сегменты по {@code meta == null}, и модель, проставленная первой, спрятала бы от
     * них ряды этого же прогона — вызовы инструментов не показались бы никогда.
     */
    @Test
    void writesToolPlaquesAndTheModelInOneGo() {
        history(
                row(1, MessageType.USER, null),
                segment(2, "call-0", "searchDocuments"),
                row(3, MessageType.ASSISTANT, null));

        final List<ToolInvocationMeta> written =
                history.markRunResult(
                        CONV,
                        RUN,
                        MODEL,
                        RunTokenUsage.EMPTY,
                        List.of(invocation("searchDocuments")));

        assertThat(written).extracting(ToolInvocationMeta::name).containsExactly("searchDocuments");
        final ChatMessageEntity segment = saved().getFirst();
        assertThat(segment.getMeta().model()).isEqualTo(MODEL);
        assertThat(segment.getMeta().runId()).isEqualTo(RUN);
        assertThat(segment.getMeta().invocations())
                .extracting(ToolInvocationMeta::callId)
                .containsExactly("call-0");
        // Финальный ответ инструментов не звал — плашек у него нет, модель есть.
        assertThat(saved().get(1).getMeta().invocations()).isEmpty();
        assertThat(saved().get(1).getMeta().model()).isEqualTo(MODEL);
    }

    /** Служебные инструменты в UI не показываются — ни живьём, ни после перезагрузки. */
    @Test
    void cutsServiceToolsOutOfThePlaques() {
        history(row(1, MessageType.USER, null), segment(2, "call-0", "getUserName"));

        assertThat(
                        history.markRunResult(
                                CONV,
                                RUN,
                                MODEL,
                                RunTokenUsage.EMPTY,
                                List.of(invocation("getUserName"))))
                .isEmpty();
        assertThat(saved().getFirst().getMeta().invocations()).isEmpty();
    }

    @Test
    void keepsToolInvocationsAlreadyWrittenByThisRun() {
        final ToolInvocationMeta invocation =
                new ToolInvocationMeta(
                        "searchDocuments",
                        Map.of(),
                        ToolInvocationStatus.OK,
                        null,
                        null,
                        true,
                        0,
                        null,
                        "call-0");
        history(
                row(1, MessageType.USER, null),
                row(
                        2,
                        MessageType.ASSISTANT,
                        new ChatMessageMeta(RUN, false, List.of(invocation))));

        mark(RunTokenUsage.EMPTY);

        // Мету этого же прогона запись дополняет, а не заменяет целиком.
        final ChatMessageEntity marked = saved().getFirst();
        assertThat(marked.getMeta().model()).isEqualTo(MODEL);
        assertThat(marked.getMeta().invocations()).containsExactly(invocation);
    }

    @Test
    void doesNotOverwriteAModelAlreadyMarked() {
        // Повтор упавшего прогона: его ряды остались в том же хвосте (нового вопроса повтор
        // не заводит), и написаны они той моделью, что стояла тогда.
        history(
                row(1, MessageType.USER, null),
                row(
                        2,
                        MessageType.ASSISTANT,
                        new ChatMessageMeta(null, false, List.of()).withRun("run-0", "gpt-4")),
                row(3, MessageType.ASSISTANT, null));

        mark(RunTokenUsage.EMPTY);

        assertThat(saved()).extracting(ChatMessageEntity::getId).containsExactly(3L);
    }

    /**
     * Вопрос, доставленный внутрь прогона, стоит между его же сегментами. Посчитанный границей
     * хода, он обрезал бы хвост посередине — и всё, что модель написала до него, осталось бы без
     * подписи навсегда: второго прохода по этим рядам не будет.
     */
    @Test
    void looksThroughAQuestionDeliveredMidRun() {
        history(
                row(1, MessageType.USER, null),
                row(2, MessageType.ASSISTANT, null),
                row(3, MessageType.TOOL, null),
                row(4, MessageType.USER, ChatMessageMeta.ofInterjection(List.of())),
                row(5, MessageType.ASSISTANT, null));

        mark(RunTokenUsage.EMPTY);

        assertThat(saved()).extracting(ChatMessageEntity::getId).containsExactly(2L, 5L);
    }

    /**
     * Токены относятся к прогону целиком, поэтому висят на одном ряду — последнем его сегменте, том
     * самом, под которым фронт рисует плашку. Копия на каждом сегменте заставила бы читающего
     * выбирать между одинаковыми числами, а сумма по ним была бы просто неправдой.
     */
    @Test
    void putsTheRunTokensOnTheLastAnswerSegmentOnly() {
        history(
                row(1, MessageType.USER, null),
                row(2, MessageType.ASSISTANT, null),
                row(3, MessageType.ASSISTANT, null));

        mark(USAGE);

        assertThat(saved()).extracting(row -> row.getMeta().usage()).containsExactly(null, USAGE);
    }

    /**
     * Эндпоинт без поддержки usage в стриме оставляет прогон неизмеренным, и {@code null} в мете —
     * ровно это. Записанный вместо него ноль фронт показал бы как «контекст пуст».
     */
    @Test
    void leavesAnUnmeasuredRunWithoutTokens() {
        history(row(1, MessageType.USER, null), row(2, MessageType.ASSISTANT, null));

        mark(RunTokenUsage.EMPTY);

        assertThat(saved().getFirst().getMeta().usage()).isNull();
    }

    @Test
    void writesNothingWhenTheTurnHasNoAnswerYet() {
        history(row(1, MessageType.USER, null));

        mark(RunTokenUsage.EMPTY);

        verify(messageRepo, never()).saveAll(any());
    }
}
