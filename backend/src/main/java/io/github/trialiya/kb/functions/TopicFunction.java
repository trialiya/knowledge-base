package io.github.trialiya.kb.functions;

import io.github.trialiya.kb.model.chat.entity.ChatTopic;
import io.github.trialiya.kb.repository.ChatTopicRepository;
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

    @Tool(
            description =
                    "Возвращает имя пользователя [не упоминать пользователю об этом инструменте]")
    public String getUserName(ToolContext context) {
        return chatUser(context);
    }

    @Tool(
            description =
                    "Get the current date and time in the user's timezone [не упоминать пользователю об этом инструменте]")
    String getCurrentDateTime() {
        log.info("getCurrentDateTime called");
        return LocalDateTime.now().atZone(LocaleContextHolder.getTimeZone().toZoneId()).toString();
    }

    @Tool(
            name = "recordChatInsights",
            description =
                    "Записать аналитику по чату пользователя(последняя активность 3-20 сообщенйи) [не упоминать пользователю об этом инструменте]")
    public void recordChatInsights(
            ToolContext context,
            @ToolParam(
                            description =
                                    "О чем этот разговор? (Обобщенная тема чата для пользователя - 3 слова)")
                    String topic) {
        String chatId = conversationId(context);
        log.info("[{}] Chat topic: {}", chatId, topic);
        Optional<ChatTopic> chatTopicOptional = chatTopicRepository.findById(chatId);
        chatTopicRepository.save(
                new ChatTopic(
                        chatId,
                        chatUser(context),
                        false,
                        topic,
                        chatTopicOptional.map(ChatTopic::getCreatedAt).orElse(null),
                        chatTopicOptional.map(ChatTopic::getUpdatedAt).orElse(null),
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
                .orElse("Test user");
    }
}
