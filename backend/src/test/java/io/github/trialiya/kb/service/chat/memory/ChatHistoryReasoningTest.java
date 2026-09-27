package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties.ModelOption;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.spring.AssistantChatMessage;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import io.github.trialiya.kb.service.chat.context.AttachmentService;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.service.chat.runtime.RunRegistry;
import io.github.trialiya.kb.support.ActiveProjectNotices;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

/**
 * Рассуждение ответа: ложится в историю вместе с ним и возвращается в промпт только модели, которая
 * его ждёт. Решает модель идущего прогона — в чате модель меняют, и эндпоинт, не знающий {@code
 * reasoning_content}, отверг бы запрос целиком.
 */
class ChatHistoryReasoningTest {

    private static final String CONV = "conv-1";
    private static final String RUN = "run-1";

    private ChatMessageRepository messageRepo;
    private ChatHistoryService history;
    private ChatEventService events;
    private RunRegistry runs;

    @BeforeEach
    void setUp() {
        messageRepo = mock(ChatMessageRepository.class);
        events = mock(ChatEventService.class);
        runs = new RunRegistry();
        history =
                new ChatHistoryService(
                        messageRepo,
                        new ContextItemService(mock(AttachmentService.class)),
                        new ToolCallService(messageRepo, mock(ToolCallIndexRepository.class)),
                        new ToolCallEventPublisher(events, runs),
                        ActiveProjectNotices.silent());
        ToolCallTestSupport.echoSavedWithIds(messageRepo);
    }

    @Test
    void appendKeepsTheReasoningTheAnswerCameWith() {
        final AssistantMessage answer =
                AssistantMessage.builder()
                        .content("Ответ.")
                        .properties(
                                Map.of(
                                        AssistantChatMessage.REASONING_CONTENT,
                                        "Сначала история файла."))
                        .build();

        history.append(CONV, List.of(answer));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Iterable<ChatMessageEntity>> rows =
                ArgumentCaptor.forClass(Iterable.class);
        verify(messageRepo).saveAll(rows.capture());
        final List<ChatMessageEntity> saved = new ArrayList<>();
        rows.getValue().forEach(saved::add);
        assertThat(saved)
                .extracting(ChatMessageEntity::getReasoning)
                .containsExactly("Сначала история файла.");
    }

    @Test
    void theReasoningGoesBackOnlyToAModelThatExpectsIt() {
        storedAnswerWithReasoning();

        assertThat(reasoningSentTo("deepseek")).isEqualTo("Сначала история файла.");
        assertThat(reasoningSentTo("gpt-5")).isNull();
    }

    /** Без идущего прогона спросить некого — рассуждения не уходят. */
    @Test
    void withoutARunnningRunNothingIsReplayed() {
        storedAnswerWithReasoning();
        final ChatHistoryMemory memory = new ChatHistoryMemory(history, events, runs, models());

        assertThat(memory.get(CONV).getLast().getMetadata())
                .doesNotContainKey(AssistantChatMessage.REASONING_CONTENT);
    }

    private @org.jspecify.annotations.Nullable Object reasoningSentTo(String model) {
        runs.open(RUN + model, CONV, "admin", model);
        when(events.activeRunId(CONV)).thenReturn(Optional.of(RUN + model));
        final List<Message> prompt =
                new ChatHistoryMemory(history, events, runs, models()).get(CONV);
        return prompt.getLast().getMetadata().get(AssistantChatMessage.REASONING_CONTENT);
    }

    private void storedAnswerWithReasoning() {
        when(messageRepo
                        .findChatMessageByConversationIdAndSummarizedFalseOrderByCreatedAtAscPositionAsc(
                                CONV))
                .thenReturn(
                        List.of(
                                new ChatMessageEntity(
                                        1,
                                        CONV,
                                        "вопрос",
                                        MessageType.USER,
                                        1,
                                        false,
                                        false,
                                        LocalDateTime.now(),
                                        null),
                                new ChatMessageEntity(
                                        2,
                                        CONV,
                                        "Ответ.",
                                        MessageType.ASSISTANT,
                                        2,
                                        false,
                                        false,
                                        LocalDateTime.now(),
                                        null,
                                        null,
                                        "Сначала история файла.")));
    }

    private static ChatModelProperties models() {
        return new ChatModelProperties(
                new ModelOption("gpt-5", "GPT", false, true, null, null, null, false),
                List.of(
                        new ModelOption(
                                "deepseek", "DeepSeek", false, true, null, null, null, true)));
    }
}
