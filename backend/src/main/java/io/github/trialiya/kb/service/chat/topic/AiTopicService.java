package io.github.trialiya.kb.service.chat.topic;

import static io.github.trialiya.kb.model.chat.dto.ChatEventType.CHAT_TOPIC;

import io.github.trialiya.kb.config.model.ChatTopicProperties;
import io.github.trialiya.kb.model.chat.dto.ChatTopicPayload;
import io.github.trialiya.kb.model.chat.entity.ChatMessageEntity;
import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.ChatMessageRepository;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.service.chat.event.ChatEventService;
import io.github.trialiya.kb.utils.BackgroundCallOptions;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * Название чата от ИИ: после ответа модели — отдельный короткий запрос по хвосту разговора. Не
 * инструмент основной модели: тот стоил бы лишнего раунда с полным контекстом на каждом ответе, а
 * здесь запрос читает несколько сообщений и может идти на модели подешевле ({@link
 * ChatTopicProperties}).
 *
 * <p>Идёт в фоне и ответа не задерживает: вкладки узнают о новом названии событием {@code
 * CHAT_TOPIC}. Чат, переименованный пользователем, не трогает вовсе. Когда запрос нужен и что он
 * читает — {@link TopicPrompt}. Если название ещё не придумано (прошлый запрос не удался), оно
 * придумывается на ближайшем ответе, не дожидаясь контрольной точки.
 */
@Slf4j
@Service
public class AiTopicService implements DisposableBean {

    private final ChatClient chatClient;
    private final ChatTopicRepository chatTopics;
    private final ChatMessageRepository chatMessages;
    private final ChatEventService events;
    private final ChatTopicProperties properties;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * Чаты, название которых придумывается прямо сейчас. Второй запрос по тому же чату не
     * начинается: два ответа подряд иначе дали бы два платных запроса, и победил бы тот, что
     * закончил последним, — возможно, по более старому окну.
     */
    private final Set<String> naming = ConcurrentHashMap.newKeySet();

    public AiTopicService(
            OpenAiChatModel openAiChatModel,
            @Value("classpath:prompt/chat-topic.md") Resource prompt,
            ChatTopicRepository chatTopics,
            ChatMessageRepository chatMessages,
            ChatEventService events,
            ChatTopicProperties properties) {
        this.chatClient =
                ChatClient.builder(openAiChatModel)
                        .defaultOptions(
                                BackgroundCallOptions.of(
                                        openAiChatModel,
                                        properties.model(),
                                        properties.reasoningEffort(),
                                        properties.thinking()))
                        .defaultSystem(prompt)
                        .build();
        this.chatTopics = chatTopics;
        this.chatMessages = chatMessages;
        this.events = events;
        this.properties = properties;
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }

    /** Ответ модели записан — в фоне решить, пора ли назвать чат, и назвать. */
    public void afterAnswer(String conversationId) {
        if (!properties.enabled()) {
            return;
        }
        if (!naming.add(conversationId)) {
            return;
        }
        try {
            executor.execute(
                    () -> {
                        try {
                            name(conversationId);
                        } catch (RuntimeException e) {
                            // Без названия чат живёт и так: следующий ответ попробует снова.
                            log.warn("[{}] Chat naming failed: {}", conversationId, e.getMessage());
                        } finally {
                            naming.remove(conversationId);
                        }
                    });
        } catch (RejectedExecutionException e) {
            naming.remove(conversationId);
            // Приложение останавливается — название подождёт следующего ответа.
            log.debug("[{}] Chat naming skipped: executor is shut down", conversationId);
        }
    }

    /** Синхронная часть {@link #afterAnswer} — вынесена ради тестов. */
    void name(String conversationId) {
        final ChatTopicEntity chat = chatTopics.findById(conversationId).orElse(null);
        if (chat == null || chat.getUserTopic() != null) {
            return;
        }
        final List<ChatMessageEntity> rows =
                chatMessages
                        .findChatMessageByConversationIdAndSummaryFalseOrderByCreatedAtAscPositionAsc(
                                conversationId);
        final @Nullable String current = chat.getAiTopic();
        if (current != null && !TopicPrompt.isCheckpoint(TopicPrompt.turns(rows))) {
            return;
        }
        final List<TopicPrompt.Line> excerpt = TopicPrompt.excerpt(rows);
        if (excerpt.isEmpty()) {
            return;
        }
        final String topic =
                TopicPrompt.clean(
                        chatClient
                                .prompt()
                                .user(TopicPrompt.request(current, excerpt))
                                .call()
                                .content());
        if (topic == null) {
            log.warn("[{}] Chat naming returned no title", conversationId);
            return;
        }
        if (topic.equals(current)) {
            return;
        }
        chatTopics.updateAiTopic(conversationId, topic);
        log.info("[{}] Chat topic: {}", conversationId, topic);
        // Отображаемое название перечитываем: пока шёл запрос, чат могли переименовать, и вкладке
        // нельзя затирать название пользователя предложенным.
        final @Nullable String display =
                chatTopics
                        .findById(conversationId)
                        .map(ChatTopicEntity::getDisplayTopic)
                        .orElse(topic);
        events.publish(
                conversationId, CHAT_TOPIC, null, null, new ChatTopicPayload(display, topic));
    }
}
