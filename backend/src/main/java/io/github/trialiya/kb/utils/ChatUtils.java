package io.github.trialiya.kb.utils;

import static io.github.trialiya.kb.functions.TopicFunction.USER_NAME;

import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;

public class ChatUtils {

    public static String DEFAULT_CONVERSATION_ID = "default";

    @NonNull
    public static String conversationId(ToolContext context) {
        return Optional.ofNullable(context.getContext().get(ChatMemory.CONVERSATION_ID))
                .map(Object::toString)
                .orElse("default");
    }

    // todo this is temporarily
    public static Map<String, Object> buildContext(String conversationId) {
        return Map.of(ChatMemory.CONVERSATION_ID, conversationId, USER_NAME, getUser());
    }

    public static String getUser() {
        return "Test user";
    }
}
