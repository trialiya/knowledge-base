package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatMessageMeta;
import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import io.github.trialiya.kb.service.chat.AttachmentService;
import io.github.trialiya.kb.service.chat.ContextItemService;
import io.github.trialiya.kb.service.chat.run.ChatEventService;
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
 * {@link ChatHistoryService#markRunModel}: чем помечены ответы прогона. Модель на записи неизвестна
 * — advisor памяти приносит только сообщения, — поэтому её проставляют по завершении прогона,
 * поверх уже сохранённых рядов.
 */
class ChatHistoryRunModelTest {

    private static final String CONV = "conv-1";
    private static final String RUN = "run-1";
    private static final String MODEL = "gpt-5";

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
                        new ToolCallEventPublisher(mock(ChatEventService.class)));
    }

    private static ChatMessageEntity row(
            long id, MessageType type, @Nullable ChatMessageMeta meta) {
        return new ChatMessageEntity(
                id, CONV, "text", type, id, false, false, LocalDateTime.now(), meta);
    }

    private void history(ChatMessageEntity... rows) {
        when(messageRepo
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                CONV))
                .thenReturn(List.of(rows));
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

        history.markRunModel(CONV, RUN, MODEL);

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

    @Test
    void keepsToolInvocationsWrittenByAttachRunMeta() {
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

        history.markRunModel(CONV, RUN, MODEL);

        // attachRunMeta ходит первой и уже записала плашки вызовов — пометка модели
        // дописывает поле, а не заменяет мету целиком.
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

        history.markRunModel(CONV, RUN, MODEL);

        assertThat(saved()).extracting(ChatMessageEntity::getId).containsExactly(3L);
    }

    @Test
    void writesNothingWhenTheTurnHasNoAnswerYet() {
        history(row(1, MessageType.USER, null));

        history.markRunModel(CONV, RUN, MODEL);

        verify(messageRepo, never()).saveAll(any());
    }
}
