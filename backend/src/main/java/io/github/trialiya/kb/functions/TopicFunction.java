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

    @Tool(description = "Возвращает id текущего чата")
    public String getChatId(ToolContext context) {
        final String chatId = conversationId(context);
        log.info("ChatId: {}", chatId);
        return chatId;
    }

    @Tool(description = "Возвращает имя пользователя")
    public String getUserName(ToolContext context) {
        return chatUser(context);
    }

    @Tool(description = "Текущие дата и время в часовом поясе пользователя")
    String getCurrentDateTime() {
        log.info("getCurrentDateTime called");
        return LocalDateTime.now().atZone(LocaleContextHolder.getTimeZone().toZoneId()).toString();
    }

    @Tool(
            name = "recordChatInsights",
            description =
                    "ОБЯЗАТЕЛЬНО вызывать в начале КАЖДОГО ответа. "
                            + "Записывает тему разговора для списка чатов.")
    public void recordChatInsights(
            ToolContext context,
            @ToolParam(description = "Тема чата — 3 слова, на языке пользователя") String topic) {
        String chatId = conversationId(context);
        log.info("[{}] Chat topic: {}", chatId, topic);
        Optional<ChatTopicEntity> chatTopicOptional = chatTopicRepository.findById(chatId);
        if (!chatTopicOptional.map(ChatTopicEntity::isUser).orElse(false)) {
            chatTopicRepository.save(
                    new ChatTopicEntity(
                            chatId,
                            chatUser(context),
                            false,
                            topic,
                            chatTopicOptional.map(ChatTopicEntity::getModel).orElse(null),
                            chatTopicOptional.map(ChatTopicEntity::getMode).orElse(null),
                            chatTopicOptional.map(ChatTopicEntity::getCreatedAt).orElse(null),
                            chatTopicOptional.map(ChatTopicEntity::getUpdatedAt).orElse(null),
                            chatTopicOptional.isEmpty()));
        }
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
