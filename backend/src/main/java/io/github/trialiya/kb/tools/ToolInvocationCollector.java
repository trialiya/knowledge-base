package io.github.trialiya.kb.tools;

import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.STARTED;

import io.github.trialiya.kb.model.tool.ToolInvocation;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

public final class ToolInvocationCollector {

    public static final String KEY = "toolInvocationCollector";

    private final List<ToolInvocation> invocations = new CopyOnWriteArrayList<>();
    private final AtomicInteger callIndex = new AtomicInteger(0);

    /**
     * Хук на каждую запись — надёжная граница «инструмент пошёл» для владельца прогона (сброс
     * буфера сегмента в ChatRunService). Live-события TOOL_CALL отсюда не шлются — их публикует
     * ChatMemoryService.saveAll при сохранении сегмента.
     */
    @Nullable private final Runnable onRecord;

    public ToolInvocationCollector() {
        this(null);
    }

    public ToolInvocationCollector(@Nullable final Runnable onRecord) {
        this.onRecord = onRecord;
    }

    /** Достаёт коллектор из {@link ToolContext}; {@code null}, если его там нет. */
    @Nullable
    public static ToolInvocationCollector from(@Nullable ToolContext context) {
        if (context == null) {
            return null;
        }
        return context.getContext().get(KEY) instanceof ToolInvocationCollector collector
                ? collector
                : null;
    }

    public int nextCallIndex() {
        return callIndex.getAndIncrement();
    }

    public void record(ToolInvocation invocation) {
        invocations.add(invocation);
        if (onRecord != null) {
            onRecord.run();
        }
    }

    public List<ToolInvocation> snapshot() {
        return List.copyOf(invocations);
    }

    public List<ToolInvocation> completedSnapshot() {
        return invocations.stream().filter(inv -> STARTED != inv.status()).toList();
    }

    public enum ToolInvocationStatus {
        STARTED,
        OK,
        ERROR
    }
}
