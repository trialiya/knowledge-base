package io.github.trialiya.kb.utils;

import static io.github.trialiya.kb.functions.TopicFunction.USER_NAME;

import io.github.trialiya.kb.tools.ProjectContext;
import io.github.trialiya.kb.tools.RunCancellation;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
        return buildContext(conversationId, (String) null);
    }

    /**
     * As {@link #buildContext(String)}, plus the project the caller works on — {@code null} when it
     * does not know one, which every caller does today and which tools read as "the default
     * project" (see {@link ProjectContext}, {@code GitRegistry}).
     */
    public static Map<String, Object> buildContext(
            String conversationId, @Nullable String projectId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put(ChatMemory.CONVERSATION_ID, conversationId);
        context.put(USER_NAME, getUser());
        if (projectId != null) {
            context.put(ProjectContext.KEY, projectId);
        }
        return Map.copyOf(context);
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
        return buildContext(conversationId, toolCollector, user, RunCancellation.none());
    }

    /**
     * Как {@link #buildContext(String, ToolInvocationCollector, String)}, плюс флаг остановки
     * прогона. Нужен инструментам, которые работают заметное время: остановка чата рвёт подписку на
     * стрим, но уже запущенный инструмент об этом не узнаёт (см. {@link RunCancellation}).
     */
    public static Map<String, Object> buildContext(
            String conversationId,
            ToolInvocationCollector toolCollector,
            String user,
            RunCancellation cancellation) {
        return Map.of(
                ChatMemory.CONVERSATION_ID,
                conversationId,
                ToolInvocationCollector.KEY,
                toolCollector,
                USER_NAME,
                user,
                RunCancellation.KEY,
                cancellation);
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
