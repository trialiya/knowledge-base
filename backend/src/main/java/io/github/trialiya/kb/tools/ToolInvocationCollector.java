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

    /**
     * Инструменты, которые умеют читать не только активный проект чата ({@code project} — их
     * необязательный аргумент). Их результат надо сверять с {@code project} по факту — эхом,
     * которое сам ответ обязан нести (см. {@code GitFileContent}, {@code GitGrepMatch}, {@code
     * ScriptResult}), а не по аргументу вызова: {@code project} мог быть не указан и разрешиться в
     * дефолтный, или указан явно тем же дефолтным id — оба случая должны засчитаться как «свой
     * проект», а сверка по сырому аргументу этого не различает.
     */
    private static final Set<String> PROJECT_AWARE_TOOLS =
            Set.of("getFileContent", "grepContent", "runScript");

    private final List<ToolInvocation> invocations = new CopyOnWriteArrayList<>();
    private final AtomicInteger callIndex = new AtomicInteger(0);

    /**
     * Хук на каждую запись — надёжная граница «инструмент пошёл» для владельца прогона (сброс
     * буфера сегмента в ChatRunService). Live-события TOOL_CALL отсюда не шлются — их публикует
     * ToolCallEventPublisher при сохранении сегмента.
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
     * Показывали ли модели содержимое файла {@code path} из проекта {@code project} где-нибудь в
     * этом ответе — правило скриптовой песочницы для записи файла целиком ({@code kb.writeBytes},
     * см. {@code ScriptSession#requireRead}). Живёт здесь, а не внутри песочницы, потому что
     * спрашивает про историю всего ответа: файл, прочитанный обычным инструментом до скрипта,
     * засчитывается наравне с чтением внутри него.
     *
     * <p>Точечные правки ({@code editFile}, {@code kb.edit}) этого правила не спрашивают вовсе: там
     * гарантию даёт точное совпадение {@code oldString} (см. {@code GitEditFunction}). В {@link
     * #PATH_ARG_READ_TOOLS} правка остаётся как свидетельство — она означает, что модель содержимое
     * файла видела.
     *
     * <p>Намеренно снисходительно к тому, <em>как</em> файл был показан: считается либо успешный
     * вызов читающего инструмента с этим {@code filePath}, либо любой успешный результат
     * (grep/поиск/diff/листинг, в том числе {@code filesRead} более раннего {@code runScript}), в
     * тексте которого встречается путь.
     *
     * <p>Но не проект: чтение файла в одном репозитории не должно засчитываться за запись
     * одноимённого пути в другом (запись всегда идёт в проект прогона — см. {@code
     * ScriptFunction#runScript}). Для инструментов из {@link #PROJECT_AWARE_TOOLS} совпадение
     * засчитывается только когда собственное эхо ответа называет тот же {@code project}; остальные
     * инструменты не умеют читать чужой проект вовсе, так что для них сверка не нужна.
     *
     * @param path путь так, как его пишет репозиторий (канонизированный вызывающим)
     * @param project id проекта, куда пойдёт запись (канонический — тот же, что несёт эхо ответа)
     */
    public boolean hasSeenFile(String path, String project) {
        return snapshot().stream()
                .filter(inv -> ToolInvocationStatus.OK == inv.status())
                .filter(inv -> matchesProject(inv, project))
                .anyMatch(inv -> namesFileAsPathArgument(inv, path) || mentionsFile(inv, path));
    }

    private static boolean matchesProject(ToolInvocation invocation, String project) {
        if (!PROJECT_AWARE_TOOLS.contains(invocation.name())) {
            return true;
        }
        return invocation.resultText() != null
                && invocation.resultText().contains(projectEchoMarker(project));
    }

    private static String projectEchoMarker(String project) {
        return "\"project\":\"" + project + "\"";
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
