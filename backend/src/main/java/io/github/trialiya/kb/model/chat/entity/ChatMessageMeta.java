package io.github.trialiya.kb.model.chat.entity;

import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Метаданные сообщения. {@code toolCalls} — явный признак сообщения-«крошки» вызовов инструментов:
 * на него опираются и бэк (вырезать JSON, не показывать пользователю), и фронт. Признак именно
 * флаг, а не тип SYSTEM и не сам факт наличия меты: мета есть и у обычных сообщений.
 *
 * <p>{@code contextItems} — то, что пользователь приложил к вопросу (см. {@link ContextItem}).
 * Живёт здесь, а не в отдельной таблице: элементы всегда читаются вместе со своим сообщением и ни
 * разу — сами по себе. Отдельная таблица понадобится тогда, когда появится вид контекста с обратной
 * выборкой («все комментарии по файлу X»).
 *
 * <p>{@code project} и {@code projectSwitchFrom} — маркер смены проекта: этим сообщением чат
 * перешёл с {@code projectSwitchFrom} на {@code project} (оба — канонические id). Заполняются парой
 * и только на вопросе, который реально сменил проект, — история выше него относится к прежнему
 * репозиторию, и об этом предупреждают и модель (см. {@code ChatHistoryService.promptRow}), и
 * пользователь (плашка на фронте). На summary-строке {@code project} живёт без пары — это след «на
 * каком проекте закончилось сжатое», см. {@code SummarizeService}.
 *
 * <p>{@code model} — id модели, которая написала этот ответ (см. {@code
 * ChatHistoryService.markRunModel}). Только на ASSISTANT-рядах и только начиная с прогонов, где
 * поле уже существовало: у старых ответов его нет, и {@code null} здесь значит «неизвестно», а не
 * «дефолтная модель» — чат мог идти на любой.
 *
 * <p>{@code compact} — итог сжатия по команде {@code /compact} (см. {@link CompactMeta}). Стоит
 * ровно на одном ряду — строке-плашке, которую сжатие оставляет в истории вместо себя, — и он же
 * признак этой строки: ни у одного другого сообщения поля нет.
 *
 * <p>{@code gitEvent} — git-команда, выполненная пользователем из этого чата (см. {@link
 * GitEventMeta}). Тоже признак своего ряда: у такого сообщения пустой контент, и весь его смысл в
 * этом поле — карточка вывода на фронте, нотис модели в {@code ChatHistoryService.promptRow}.
 *
 * <p>{@code interjection} — вопрос доставлен ПОСРЕДИ прогона, между итерациями tool-цикла (см.
 * {@code PendingMessageService}): пользователь писал, глядя на ход работы, а не на готовый ответ.
 * Модель предупреждает нотис в {@code ChatHistoryService.promptRow}; для всего, что ищет «последний
 * вопрос» хода ({@code tailAfterLastUser} и его фронтовый двойник), такой ряд обязан быть
 * прозрачным — ход открыл не он.
 */
public record ChatMessageMeta(
        @Nullable String runId,
        boolean toolCalls,
        List<ToolInvocationMeta> invocations,
        List<ContextItem> contextItems,
        @Nullable String project,
        @Nullable String projectSwitchFrom,
        @Nullable String model,
        @Nullable CompactMeta compact,
        @Nullable GitEventMeta gitEvent,
        boolean interjection) {

    public ChatMessageMeta {
        invocations = invocations == null ? List.of() : invocations;
        contextItems = contextItems == null ? List.of() : contextItems;
    }

    public ChatMessageMeta(
            @Nullable String runId,
            boolean toolCalls,
            List<ToolInvocationMeta> invocations,
            List<ContextItem> contextItems,
            @Nullable String project,
            @Nullable String projectSwitchFrom,
            @Nullable String model) {
        this(
                runId,
                toolCalls,
                invocations,
                contextItems,
                project,
                projectSwitchFrom,
                model,
                null,
                null,
                false);
    }

    public ChatMessageMeta(
            @Nullable String runId,
            boolean toolCalls,
            List<ToolInvocationMeta> invocations,
            List<ContextItem> contextItems,
            @Nullable String project,
            @Nullable String projectSwitchFrom) {
        this(runId, toolCalls, invocations, contextItems, project, projectSwitchFrom, null);
    }

    public ChatMessageMeta(
            @Nullable String runId,
            boolean toolCalls,
            List<ToolInvocationMeta> invocations,
            List<ContextItem> contextItems) {
        this(runId, toolCalls, invocations, contextItems, null, null);
    }

    public ChatMessageMeta(
            @Nullable String runId, boolean toolCalls, List<ToolInvocationMeta> invocations) {
        this(runId, toolCalls, invocations, List.of());
    }

    public ChatMessageMeta(List<ToolInvocationMeta> invocations) {
        this(null, true, invocations, List.of());
    }

    /** Метаданные сообщения пользователя: приложенный контекст и, если была, смена проекта. */
    public static @Nullable ChatMessageMeta ofUserMessage(
            List<ContextItem> contextItems,
            @Nullable String project,
            @Nullable String projectSwitchFrom) {
        if (contextItems.isEmpty() && projectSwitchFrom == null) {
            return null;
        }
        return new ChatMessageMeta(
                null,
                false,
                List.of(),
                contextItems,
                projectSwitchFrom == null ? null : project,
                projectSwitchFrom);
    }

    /** Метаданные сообщения пользователя: кроме приложенного контекста в них ничего нет. */
    public static ChatMessageMeta ofContextItems(List<ContextItem> contextItems) {
        return new ChatMessageMeta(null, false, List.of(), contextItems);
    }

    /**
     * Метаданные строки-плашки «контекст сжат»: что именно сделало сжатие и где лежит его сводка.
     */
    public static ChatMessageMeta ofCompact(CompactMeta compact) {
        return new ChatMessageMeta(
                null, false, List.of(), List.of(), null, null, null, compact, null, false);
    }

    /**
     * Метаданные ряда git-команды. Проект остаётся внутри самого события: {@code project} на этом
     * уровне значит «проект, на котором закончилась сжатая история» (см. {@link #ofProject}), а
     * этот ряд историю ни во что не переводит.
     */
    public static ChatMessageMeta ofGitEvent(GitEventMeta gitEvent) {
        return new ChatMessageMeta(
                null, false, List.of(), List.of(), null, null, null, null, gitEvent, false);
    }

    /**
     * Метаданные вопроса, доставленного посреди прогона: приложенный контекст плюс флаг {@code
     * interjection}. Флаг живёт в мете, а не выводится из положения ряда: после завершения прогона
     * ряд ничем больше не отличается от обычного вопроса, а прозрачность для «последнего вопроса»
     * хода нужна и тогда.
     */
    public static ChatMessageMeta ofInterjection(List<ContextItem> contextItems) {
        return new ChatMessageMeta(
                null, false, List.of(), contextItems, null, null, null, null, null, true);
    }

    /** Метаданные summary-строки: проект, на котором закончилась сжатая часть истории. */
    public static ChatMessageMeta ofProject(String project) {
        return new ChatMessageMeta(null, false, List.of(), List.of(), project, null);
    }

    /**
     * Копия с проставленными прогоном и его моделью. Дописывает, а не заменяет: {@code
     * ChatHistoryService.markRunModel} проходит по рядам прогона последним, и уже сохранённые
     * плашки вызовов ({@code invocations}) обязаны пережить этот проход.
     */
    public ChatMessageMeta withRun(String runId, String model) {
        return new ChatMessageMeta(
                runId,
                toolCalls,
                invocations,
                contextItems,
                project,
                projectSwitchFrom,
                model,
                compact,
                gitEvent,
                interjection);
    }

    /**
     * Копия с заменённым маркером смены проекта. Как и {@link #withRun} — точечная замена, а не
     * пересборка через короткий конструктор: остальные поля обязаны пережить перезапись.
     */
    public ChatMessageMeta withProjectSwitch(
            @Nullable String project, @Nullable String projectSwitchFrom) {
        return new ChatMessageMeta(
                runId,
                toolCalls,
                invocations,
                contextItems,
                project,
                projectSwitchFrom,
                model,
                compact,
                gitEvent,
                interjection);
    }
}
