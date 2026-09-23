package io.github.trialiya.kb.service.chat.topic;

import static io.github.trialiya.kb.model.chat.dto.ChatEventType.CHAT_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ChatTopicProperties;
import io.github.trialiya.kb.model.chat.dto.ChatTopicPayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;

class AiTopicServiceTest {

    private static final String CONV = "conv-1";

    private OpenAiChatModel chatModel;
    private ChatTopicRepository chatTopics;
    private ChatMessageRepository chatMessages;
    private ChatEventService events;
    private final List<ChatMessageEntity> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        chatModel = mock(OpenAiChatModel.class);
        when(chatModel.getOptions())
                .thenReturn(OpenAiChatOptions.builder().model("chat-default").build());
        chatTopics = mock(ChatTopicRepository.class);
        chatMessages = mock(ChatMessageRepository.class);
        events = mock(ChatEventService.class);
        when(chatMessages.findConversationTurns(CONV)).thenReturn(rows);
    }

    @Test
    void theFirstAnswerNamesTheChatAndTellsTheTabs() {
        when(chatTopics.findById(CONV))
                .thenReturn(chat(null, null), chat(null, "Настройка pgvector"));
        turns(1);
        answerWith("«Настройка pgvector»");

        service(true).name(CONV);

        verify(chatTopics).updateAiTopic(CONV, "Настройка pgvector", 1);
        verify(events)
                .publish(
                        CONV,
                        CHAT_TOPIC,
                        null,
                        null,
                        new ChatTopicPayload("Настройка pgvector", "Настройка pgvector"));
        assertThat(requestText()).startsWith("Conversation, oldest first:");
    }

    @Test
    void aRenamedChatIsLeftAlone() {
        when(chatTopics.findById(CONV)).thenReturn(chat("My own title", null));
        turns(1);

        service(true).name(CONV);

        verify(chatModel, never()).call(any(Prompt.class));
        verify(chatTopics, never()).updateAiTopic(anyString(), anyString(), anyInt());
    }

    @Test
    void betweenCheckpointsTheTitleStays() {
        when(chatTopics.findById(CONV)).thenReturn(chat(null, "Old title"));
        when(chatTopics.findAiTopicTurn(CONV)).thenReturn(1);
        turns(2);

        service(true).name(CONV);

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void aCheckpointShowsTheCurrentTitleAndKeepsItWhenTheModelDoes() {
        when(chatTopics.findById(CONV)).thenReturn(chat(null, "Old title"));
        when(chatTopics.findAiTopicTurn(CONV)).thenReturn(1);
        turns(3);
        answerWith("Old title");

        service(true).name(CONV);

        assertThat(requestText()).startsWith("Current title: Old title");
        verify(chatTopics).updateAiTopicTurn(CONV, 3);
        verify(chatTopics, never()).updateAiTopic(anyString(), anyString(), anyInt());
        verify(events, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void aMissedCheckpointIsTakenByTheNextAnswer() {
        // Ответ на третьем остановили (или запрос по нему пропустили, пока шёл прежний).
        when(chatTopics.findById(CONV)).thenReturn(chat(null, "Old title"));
        when(chatTopics.findAiTopicTurn(CONV)).thenReturn(1);
        turns(4);
        answerWith("Kafka retries");

        service(true).name(CONV);

        verify(chatTopics).updateAiTopic(CONV, "Kafka retries", 4);
    }

    @Test
    void aChatNamedBeforeTurnsWereRecordedIsNamedOnItsNextAnswer() {
        when(chatTopics.findById(CONV)).thenReturn(chat(null, "Old title"));
        turns(5);
        answerWith("Kafka retries");

        service(true).name(CONV);

        verify(chatTopics).updateAiTopic(CONV, "Kafka retries", 5);
    }

    @Test
    void anUnusableReplyWaitsForTheNextCheckpointInsteadOfTheNextAnswer() {
        when(chatTopics.findById(CONV)).thenReturn(chat(null, null));
        turns(1);
        answerWith("  \n");

        service(true).name(CONV);

        verify(chatTopics).updateAiTopicTurn(CONV, 1);
        verify(chatTopics, never()).updateAiTopic(anyString(), anyString(), anyInt());
        verify(events, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    void aRenameDuringTheRequestWinsOnTheTabs() {
        when(chatTopics.findById(CONV))
                .thenReturn(chat(null, null), chat("Renamed meanwhile", "Kafka retries"));
        turns(1);
        answerWith("Kafka retries");

        service(true).name(CONV);

        verify(events)
                .publish(
                        CONV,
                        CHAT_TOPIC,
                        null,
                        null,
                        new ChatTopicPayload("Renamed meanwhile", "Kafka retries"));
    }

    @Test
    void switchedOffItDoesNothing() {
        service(false).afterAnswer(CONV);

        verifyNoInteractions(chatTopics, chatMessages, events);
    }

    private AiTopicService service(boolean enabled) {
        return new AiTopicService(
                chatModel,
                new ByteArrayResource("Name the chat.".getBytes()),
                chatTopics,
                chatMessages,
                events,
                new ChatTopicProperties(enabled, null, null, null));
    }

    private void turns(int count) {
        for (int i = 1; i <= count; i++) {
            row(MessageType.USER, "question " + i);
            row(MessageType.ASSISTANT, "answer " + i);
        }
    }

    private void row(MessageType type, String content) {
        rows.add(
                new ChatMessageEntity(
                        rows.size() + 1L,
                        CONV,
                        content,
                        type,
                        rows.size(),
                        false,
                        false,
                        LocalDateTime.now(),
                        null));
    }

    private void answerWith(String content) {
        when(chatModel.call(any(Prompt.class)))
                .thenReturn(
                        new ChatResponse(List.of(new Generation(new AssistantMessage(content)))));
    }

    private String requestText() {
        final ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        return prompt.getValue().getInstructions().stream()
                .filter(UserMessage.class::isInstance)
                .map(message -> message.getText())
                .findFirst()
                .orElseThrow();
    }

    private static Optional<ChatTopicEntity> chat(
            @Nullable String userTopic, @Nullable String aiTopic) {
        return Optional.of(
                new ChatTopicEntity(
                        CONV,
                        "user",
                        userTopic,
                        aiTopic,
                        null,
                        null,
                        null,
                        LocalDateTime.now(),
                        LocalDateTime.now(),
                        false));
    }
}
