package io.github.trialiya.kb.tools;

import static io.github.trialiya.kb.tools.ToolInvocationCollector.ToolInvocationStatus.STARTED;

import io.github.trialiya.kb.model.tool.ToolInvocation;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

public final class ToolInvocationCollector {

    public static final String KEY = "toolInvocationCollector";

    /**
     * Инструменты, у которых аргумент {@code filePath} означает, что модель осознанно смотрела
     * именно в этот файл. См. {@link #hasSeenFile}.
     */
    private static final Set<String> PATH_ARG_READ_TOOLS =
            Set.of("getFileContent", "getFileOutline", "editFile");

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
    public static @Nullable ToolInvocationCollector from(@Nullable ToolContext context) {
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

    /**
     * Показывали ли модели содержимое файла {@code path} где-нибудь в этом ответе — общее правило
     * «read before edit» для {@code editFile} ({@code GitEditFunction}) и для {@code kb.edit}
     * внутри скрипта ({@code ScriptSession#requireRead}). Оба спрашивают об одном и том же, поэтому
     * правило живёт здесь, а не двумя копиями по сторонам границы песочницы.
     *
     * <p>Намеренно снисходительно к тому, <em>как</em> файл был показан: считается либо успешный
     * вызов читающего инструмента с этим {@code filePath}, либо любой успешный результат
     * (grep/поиск/diff/листинг, в том числе {@code filesRead} более раннего {@code runScript}), в
     * тексте которого встречается путь. Точное совпадение {@code oldString} и так заставляет модель
     * цитировать реальное текущее содержимое.
     *
     * @param path путь так, как его пишет репозиторий (канонизированный вызывающим)
     */
    public boolean hasSeenFile(String path) {
        return snapshot().stream()
                .filter(inv -> ToolInvocationStatus.OK == inv.status())
                .anyMatch(inv -> namesFileAsPathArgument(inv, path) || mentionsFile(inv, path));
    }

    private static boolean namesFileAsPathArgument(ToolInvocation invocation, String path) {
        return PATH_ARG_READ_TOOLS.contains(invocation.name())
                && path.equals(String.valueOf(invocation.arguments().get("filePath")));
    }

    private static boolean mentionsFile(ToolInvocation invocation, String path) {
        return invocation.resultText() != null && invocation.resultText().contains(path);
    }

    public enum ToolInvocationStatus {
        STARTED,
        OK,
        ERROR
    }
}
