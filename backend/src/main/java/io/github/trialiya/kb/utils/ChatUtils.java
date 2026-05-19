package io.github.trialiya.kb.utils;

import static io.github.trialiya.kb.functions.TopicFunction.USER_NAME;

import java.util.Map;
import org.springframework.ai.chat.memory.ChatMemory;

public class ChatUtils {

    // todo this is temporarily
    public static Map<String, Object> buildContext(String conversationId) {
        return Map.of(ChatMemory.CONVERSATION_ID, conversationId, USER_NAME, getUser());
    }

    public static String getUser() {
        return "Test user";
    }
}
