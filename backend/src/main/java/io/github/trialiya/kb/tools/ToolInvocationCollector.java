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

    /**
     * Чем кончился вызов с этим сквозным номером; {@code null} — вызов ещё идёт либо этот прогон
     * его не делал. Спрашивает {@link
     * io.github.trialiya.kb.service.chat.memory.ToolCallEventPublisher}: по протокольному ответу
     * инструмента провал не отличить от успеха — текст ошибки уходит модели обычным результатом
     * (см. {@code ChatConfig#toolExecutionExceptionProcessor}), и знает об ошибке только запись
     * здесь.
     */
    public @Nullable ToolInvocation completed(int callIndex) {
        return invocations.stream()
                .filter(inv -> STARTED != inv.status())
                .filter(inv -> inv.callIndex() == callIndex)
                .reduce((first, second) -> second)
                .orElse(null);
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
     * ScriptFunction#runScript}). Репозиторий вызова берётся из {@code ToolInvocation#project} —
     * его проставил {@code RecordingToolCallback} по {@code ProjectScoped} самого ответа, то есть
     * уже канонизированным. Сверять по аргументу вызова нельзя: {@code project} мог быть не указан
     * и разрешиться в дефолтный, или указан явно тем же дефолтным id — оба случая означают «свой
     * проект», а сырой аргумент этого не различает.
     *
     * @param path путь так, как его пишет репозиторий (канонизированный вызывающим)
     * @param project id проекта, куда пойдёт запись (канонический — тот же, что несёт ответ)
     */
    public boolean hasSeenFile(String path, String project) {
        return snapshot().stream()
                .filter(inv -> ToolInvocationStatus.OK == inv.status())
                .filter(inv -> matchesProject(inv, project))
                .anyMatch(inv -> namesFileAsPathArgument(inv, path) || mentionsFile(inv, path));
    }

    /**
     * {@code null} — результат к репозиторию не привязан (документы, вложения): сверять нечего, и
     * такой вызов засчитывается как раньше.
     */
    private static boolean matchesProject(ToolInvocation invocation, String project) {
        return invocation.project() == null || invocation.project().equals(project);
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
        ERROR,
        /**
         * Исход вызова не сохранён: мету прогона записать не успели (процесс умер посреди хода)
         * либо запись старее самой меты. Отдельным статусом, а не {@code OK}: в протокольных {@code
         * tool_data} провал ничем не отличается от успеха, и зелёная плашка утверждала бы то, чего
         * никто не знает.
         */
        UNKNOWN
    }
}
