package io.github.trialiya.kb.tools;

import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.STARTED;

import io.github.trialiya.kb.model.chat.dto.ToolCallMessage;
import io.github.trialiya.kb.model.tool.ToolInvocation;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

public final class ToolInvocationCollector {

    public static final String KEY = "toolInvocationCollector";

    private final List<ToolInvocation> invocations = new CopyOnWriteArrayList<>();
    private final AtomicInteger callIndex = new AtomicInteger(0);

    @Nullable private final Consumer<ToolInvocation> liveSink;

    public ToolInvocationCollector() {
        this(null);
    }

    public ToolInvocationCollector(@Nullable final Consumer<Object> liveSink) {
        this.liveSink =
                liveSink != null
                        ? invocation -> liveSink.accept(new ToolCallMessage(invocation))
                        : null;
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
        if (liveSink != null) {
            liveSink.accept(invocation);
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
