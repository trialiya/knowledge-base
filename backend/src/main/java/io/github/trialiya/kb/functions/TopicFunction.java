package io.github.trialiya.kb.functions;

import io.github.trialiya.kb.model.chat.entity.ChatTopicEntity;
import io.github.trialiya.kb.repository.ChatTopicRepository;
import io.github.trialiya.kb.utils.ChatUtils;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.i18n.LocaleContextHolder;

@Slf4j
@AllArgsConstructor
public class TopicFunction {

    public static final String USER_NAME = "USER_NAME";
    private final ChatTopicRepository chatTopicRepository;

    @Tool(description = "Returns the current chat conversation ID.")
    public String getChatId(ToolContext context) {
        final String chatId = conversationId(context);
        log.info("ChatId: {}", chatId);
        return chatId;
    }

    @Tool(description = "Returns the current user name.")
    public String getUserName(ToolContext context) {
        return chatUser(context);
    }

    @Tool(description = "Returns current date and time in the user's time zone.")
    String getCurrentDateTime() {
        log.info("getCurrentDateTime called");
        return LocalDateTime.now().atZone(LocaleContextHolder.getTimeZone().toZoneId()).toString();
    }

    @Tool(
            name = "recordChatInsights",
            description =
                    "MUST call at the start of EVERY response. Records the conversation topic for the chat list.")
    public void recordChatInsights(
            ToolContext context,
            @ToolParam(description = "Chat topic: 3 words, in the user's language.") String topic) {
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("topic is required");
        }
        String chatId = conversationId(context);
        log.info("[{}] Chat topic: {}", chatId, topic);
        Optional<ChatTopicEntity> chatTopicOptional = chatTopicRepository.findById(chatId);
        chatTopicRepository.save(
                new ChatTopicEntity(
                        chatId,
                        chatUser(context),
                        chatTopicOptional.map(ChatTopicEntity::getUserTopic).orElse(null),
                        topic,
                        chatTopicOptional.map(ChatTopicEntity::getModel).orElse(null),
                        chatTopicOptional.map(ChatTopicEntity::getMode).orElse(null),
                        chatTopicOptional.map(ChatTopicEntity::getCreatedAt).orElse(null),
                        chatTopicOptional.map(ChatTopicEntity::getUpdatedAt).orElse(null),
                        chatTopicOptional.isEmpty()));
    }

    private @NonNull String conversationId(ToolContext context) {
        return Optional.ofNullable(context.getContext().get(ChatMemory.CONVERSATION_ID))
                .map(Object::toString)
                .orElse("default");
    }

    private @NonNull String chatUser(ToolContext context) {
        return Optional.ofNullable(context.getContext().get(USER_NAME))
                .map(Object::toString)
                .orElse(ChatUtils.ANONYMOUS_USER);
    }
}
