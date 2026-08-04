package io.github.trialiya.kb.model.chat.entity;

import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Метаданные сообщения. {@code toolCalls} — явный признак сообщения-«крошки» вызовов инструментов:
 * на него опираются и бэк (вырезать JSON, не показывать пользователю), и фронт. Раньше такие
 * сообщения отличали лишь по типу SYSTEM или по наличию meta — теперь это надёжный флаг.
 *
 * <p>{@code contextItems} — то, что пользователь приложил к вопросу (см. {@link ContextItem}).
 * Живёт здесь, а не в отдельной таблице: элементы всегда читаются вместе со своим сообщением и ни
 * разу — сами по себе. Отдельная таблица понадобится тогда, когда появится вид контекста с обратной
 * выборкой («все комментарии по файлу X»).
 */
public record ChatMessageMeta(
        @Nullable String runId,
        boolean toolCalls,
        List<ToolInvocationMeta> invocations,
        List<ContextItem> contextItems) {

    public ChatMessageMeta {
        invocations = invocations == null ? List.of() : invocations;
        contextItems = contextItems == null ? List.of() : contextItems;
    }

    public ChatMessageMeta(
            @Nullable String runId, boolean toolCalls, List<ToolInvocationMeta> invocations) {
        this(runId, toolCalls, invocations, List.of());
    }

    public ChatMessageMeta(List<ToolInvocationMeta> invocations) {
        this(null, true, invocations, List.of());
    }

    /** Метаданные сообщения пользователя: кроме приложенного контекста в них ничего нет. */
    public static ChatMessageMeta ofContextItems(List<ContextItem> contextItems) {
        return new ChatMessageMeta(null, false, List.of(), contextItems);
    }
}
