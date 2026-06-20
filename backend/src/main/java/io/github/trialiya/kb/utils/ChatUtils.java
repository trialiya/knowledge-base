package io.github.trialiya.kb.utils;

import static io.github.trialiya.kb.functions.TopicFunction.USER_NAME;

import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public class ChatUtils {

    public static String DEFAULT_CONVERSATION_ID = "default";

    /** Имя пользователя для контекстов без аутентификации (фоновые задачи, тесты). */
    public static final String ANONYMOUS_USER = "anonymous";

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

    public static Map<String, Object> buildContext(
            String conversationId, ToolInvocationCollector toolCollector) {
        return buildContext(conversationId, toolCollector, getUser());
    }

    /**
     * Как {@link #buildContext(String, ToolInvocationCollector)}, но с явно переданным
     * пользователем. Нужно для фоновой генерации: её инструменты исполняются на потоках Reactor,
     * где {@link #getUser()} вернул бы анонима (SecurityContext туда не распространяется), поэтому
     * пользователя захватываем на потоке запроса и протаскиваем сюда.
     */
    public static Map<String, Object> buildContext(
            String conversationId, ToolInvocationCollector toolCollector, String user) {
        return Map.of(
                ChatMemory.CONVERSATION_ID,
                conversationId,
                ToolInvocationCollector.KEY,
                toolCollector,
                USER_NAME,
                user);
    }

    /**
     * Текущий аутентифицированный пользователь из {@link SecurityContextHolder}. В контекстах без
     * аутентификации (фоновые потоки, тесты) возвращает {@link #ANONYMOUS_USER}.
     */
    @NonNull
    public static String getUser() {
        final Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !"anonymousUser".equals(authentication.getPrincipal())) {
            return authentication.getName();
        }
        return ANONYMOUS_USER;
    }
}
