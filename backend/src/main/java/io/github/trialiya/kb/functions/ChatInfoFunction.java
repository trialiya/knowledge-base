package io.github.trialiya.kb.functions;

import io.github.trialiya.kb.utils.ChatUtils;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.context.i18n.LocaleContextHolder;

/**
 * Сведения о самом разговоре: чей он, какой и когда. Название чата здесь не пишется — его
 * придумывает отдельный фоновый запрос после ответа ({@code AiTopicService}), модели чата об этом
 * думать не нужно.
 */
@Slf4j
public class ChatInfoFunction {

    public static final String USER_NAME = "USER_NAME";

    @Tool(description = "Returns the current chat conversation ID.")
    public String getChatId(ToolContext context) {
        final String chatId =
                Optional.ofNullable(context.getContext().get(ChatMemory.CONVERSATION_ID))
                        .map(Object::toString)
                        .orElse("default");
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

    private @NonNull String chatUser(ToolContext context) {
        return Optional.ofNullable(context.getContext().get(USER_NAME))
                .map(Object::toString)
                .orElse(ChatUtils.ANONYMOUS_USER);
    }
}
