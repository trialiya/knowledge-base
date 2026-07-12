package io.github.trialiya.kb.model.chat.entity;

import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Метаданные сообщения. {@code toolCalls} — явный признак сообщения-«крошки» вызовов инструментов:
 * на него опираются и бэк (вырезать JSON, не показывать пользователю), и фронт. Раньше такие
 * сообщения отличали лишь по типу SYSTEM или по наличию meta — теперь это надёжный флаг.
 */
public record ChatMessageMeta(
        @Nullable String runId, boolean toolCalls, List<ToolInvocationMeta> invocations) {

    public ChatMessageMeta {
        invocations = invocations == null ? List.of() : invocations;
    }

    public ChatMessageMeta(List<ToolInvocationMeta> invocations) {
        this(null, true, invocations);
    }
}
