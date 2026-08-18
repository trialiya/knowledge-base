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

    /**
     * Начало сборки {@code toolContext} прогона — того, что инструменты видят помимо своих
     * аргументов: разговор, пользователь, проект, сборщик вызовов, флаг остановки.
     *
     * <p>Именно билдер, а не набор перегрузок: половина составляющих необязательна, а две из них —
     * пользователь и проект — это две подряд идущие строки, которые в позиционном вызове меняются
     * местами молча и без единой ошибки компиляции.
     */
    public static ToolContextBuilder context(String conversationId) {
        return new ToolContextBuilder(conversationId);
    }

    /** Сборщик {@code toolContext}: см. {@link #context(String)}. */
    public static final class ToolContextBuilder {

        private final String conversationId;
        private @Nullable String user;
        private @Nullable String projectId;
        private @Nullable ToolInvocationCollector toolCollector;
        private @Nullable RunCancellation cancellation;

        private ToolContextBuilder(String conversationId) {
            this.conversationId = conversationId;
        }

        /**
         * Проект, в котором работает прогон; {@code null} — «вызывающий проект не назвал», и тогда
         * ключа в контексте не будет вовсе, а инструменты поедут на дефолтном (см. {@link
         * ProjectContext}, {@code GitRegistry}).
         */
        public ToolContextBuilder project(@Nullable String projectId) {
            this.projectId = projectId;
            return this;
        }

        /**
         * Пользователь, если его нельзя взять из {@link SecurityContextHolder}. Нужно фоновой
         * генерации: её инструменты исполняются на потоках Reactor, куда SecurityContext не
         * распространяется, поэтому пользователя захватывают на потоке запроса и передают сюда.
         */
        public ToolContextBuilder user(String user) {
            this.user = user;
            return this;
        }

        /** Сборщик вызовов инструментов — им прогон рисует «крошку» в чате. */
        public ToolContextBuilder collector(ToolInvocationCollector toolCollector) {
            this.toolCollector = toolCollector;
            return this;
        }

        /**
         * Флаг остановки прогона. Нужен инструментам, которые работают заметное время: остановка
         * чата рвёт подписку на стрим, но уже запущенный инструмент об этом не узнаёт (см. {@link
         * RunCancellation}).
         */
        public ToolContextBuilder cancellation(RunCancellation cancellation) {
            this.cancellation = cancellation;
            return this;
        }

        public Map<String, Object> build() {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put(ChatMemory.CONVERSATION_ID, conversationId);
            context.put(USER_NAME, user != null ? user : getUser());
            if (projectId != null) {
                context.put(ProjectContext.KEY, projectId);
            }
            if (toolCollector != null) {
                context.put(ToolInvocationCollector.KEY, toolCollector);
            }
            if (cancellation != null) {
                context.put(RunCancellation.KEY, cancellation);
            }
            return Map.copyOf(context);
        }
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
