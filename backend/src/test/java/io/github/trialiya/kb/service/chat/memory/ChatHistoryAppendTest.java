package io.github.trialiya.kb.service.chat.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.tool.ToolCallIndexEntity;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ToolCallIndexRepository;
import io.github.trialiya.kb.service.chat.context.AttachmentService;
import io.github.trialiya.kb.service.chat.context.ContextItemService;
import io.github.trialiya.kb.service.chat.run.ChatEventService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

/**
 * Что {@link ChatHistoryService#append} записывает поверх самого сообщения: санитизацию
 * протокольных аргументов и строки {@code tool_call_index} (см. {@link ToolCallService#index}).
 */
class ChatHistoryAppendTest {

    private static final String CONV = "conv-1";

    private ChatMessageRepository messageRepo;
    private ToolCallIndexRepository toolCallIndexRepo;
    private ChatHistoryService history;

    @BeforeEach
    void setUp() {
        messageRepo = mock(ChatMessageRepository.class);
        toolCallIndexRepo = mock(ToolCallIndexRepository.class);
        history =
                new ChatHistoryService(
                        messageRepo,
                        new ContextItemService(mock(AttachmentService.class)),
                        new ToolCallService(messageRepo, toolCallIndexRepo),
                        new ToolCallEventPublisher(mock(ChatEventService.class)));
        ToolCallTestSupport.echoSavedWithIds(messageRepo);
    }

    @Test
    @SuppressWarnings("unchecked")
    void malformedArgumentsAreSanitizedBeforePersisting() {
        history.append(
                CONV,
                List.of(
                        new UserMessage("hi"),
                        // Missing closing brace, replaced by a second tool call's empty argument
                        // object — as a mis-accumulated streaming tool call would produce.
                        ToolCallTestSupport.assistantWithCalls(
                                ToolCallTestSupport.call(
                                        "id-0", "editFile", "{\"filePath\": \"a\"{}"))));

        final ArgumentCaptor<List<ChatMessageEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(messageRepo).saveAll(saved.capture());
        final ChatMessageEntity segment =
                saved.getValue().stream()
                        .filter(e -> e.getType() == MessageType.ASSISTANT)
                        .findFirst()
                        .orElseThrow();
        assertThat(segment.getToolData().toolCalls()).hasSize(1);
        // Persisted as valid JSON — this is what gets replayed to the model on every later turn,
        // and malformed JSON there would make the provider reject the whole request forever.
        assertThat(segment.getToolData().toolCalls().get(0).arguments()).isEqualTo("{}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void indexesToolCallsOnAppend() {
        history.append(
                CONV,
                List.of(
                        new UserMessage("hi"),
                        ToolCallTestSupport.assistantWithCalls(
                                ToolCallTestSupport.call("id-0", "searchDocuments", "{}")),
                        new ToolResponseMessage(
                                List.<ToolResponseMessage.ToolResponse>of(
                                        new ToolResponseMessage.ToolResponse(
                                                "id-0", "searchDocuments", "\"found 3 docs\"")),
                                Map.of()) {}));

        final ArgumentCaptor<List<ToolCallIndexEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(toolCallIndexRepo).saveAll(rows.capture());
        assertThat(rows.getValue()).hasSize(1);
        assertThat(rows.getValue().get(0).getCallId()).isEqualTo("id-0");
        assertThat(rows.getValue().get(0).getConversationId()).isEqualTo(CONV);

        // responseMessageId проставляется отдельно, по id только что сохранённой TOOL-строки —
        // без похода за messageId сегмента и без позиционной арифметики.
        verify(toolCallIndexRepo).setResponseMessageId(eq(CONV), eq("id-0"), anyLong());
    }

    @Test
    @SuppressWarnings("unchecked")
    void alreadyPersistedMessagesAreNotWrittenTwice() {
        final ChatMessageEntity stored =
                ToolCallTestSupport.entity(
                        CONV,
                        MessageType.USER,
                        null,
                        null); // ряд, прочитанный из истории — приходит обратно как IMessage
        when(messageRepo.maxPosition(CONV)).thenReturn(stored.getPosition());

        history.append(
                CONV, List.<Message>of(stored.getMessage(), new AssistantMessage("свежий ответ")));

        final ArgumentCaptor<List<ChatMessageEntity>> saved = ArgumentCaptor.forClass(List.class);
        verify(messageRepo).saveAll(saved.capture());
        assertThat(saved.getValue()).hasSize(1);
        assertThat(saved.getValue().get(0).getContent()).isEqualTo("свежий ответ");
        // Позиция продолжает историю, а не начинает её заново.
        assertThat(saved.getValue().get(0).getPosition()).isEqualTo(stored.getPosition() + 1);
        verify(messageRepo, org.mockito.Mockito.never()).save(any());
    }
}
