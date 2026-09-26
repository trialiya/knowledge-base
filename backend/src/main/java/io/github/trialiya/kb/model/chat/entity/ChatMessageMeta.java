package io.github.trialiya.kb.model.chat.entity;

import io.github.trialiya.kb.model.tool.ToolInvocationMeta;
import java.util.List;
import lombok.Builder;
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
 * <p>{@code project} и {@code projectSwitchFrom} — принадлежность истории репозиторию, в двух
 * видах. Парой это маркер смены проекта: этим сообщением чат перешёл с {@code projectSwitchFrom} на
 * {@code project} (оба — канонические id), история выше относится к прежнему репозиторию, и об этом
 * предупреждают и модель (см. {@code ChatHistoryService.promptRow}), и пользователь (плашка на
 * фронте). Один {@code project} без пары на ПЕРВОМ сообщении чата — базовый штамп: предупреждать
 * над пустой историей не о чем, но назвать репозиторий, с которого чат начался, обязан кто-то, и
 * это единственный ряд, который может сделать это без «откуда». Вместе они и есть весь след
 * проектов в живой истории: {@code ActiveProjectNotice} собирает по ним таймлайн чата.
 *
 * <p>{@code visitedProjects} — тот же след, но за сжатую часть истории: хронологические отрезки
 * «сообщения с N по M прожиты на этом репозитории» (см. {@link ProjectSpan}). Стоит на
 * summary-строке и накапливается — каждая следующая сводка наследует спаны предыдущей и дописывает
 * свои, поэтому последняя сводка окна знает всю историю смен, а тянуться за маркерами, которых в
 * живом окне уже нет, не приходится. Пишет их {@code SummaryWriter.projectTrace}.
 *
 * <p>Одинокий {@code project} на summary-строке — прежний вид того же следа («на каком проекте
 * закончилось сжатое»). Читателя у него больше нет: спаны отвечают на тот же вопрос точнее, и в
 * промпт идут они. Пишется он только затем, чтобы сводку, записанную этой версией, понял откат на
 * версию без спанов, — и перестать его писать можно ровно тогда, когда такой откат перестанет быть
 * возможным. Аннотации {@code @Deprecated} на поле нет намеренно: у {@code project} есть две живые
 * роли выше, и пометка на компоненте записи ругалась бы на них, а не на этот один случай.
 *
 * <p>{@code model} — id модели, которая написала этот ответ (см. {@code
 * ChatHistoryService.markRunResult}). Только на ASSISTANT-рядах и только начиная с прогонов, где
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
 * <p>{@code usage} — токены прогона (см. {@link RunTokenUsage}). Стоит на одном ряду прогона, его
 * последнем ASSISTANT-ряду: числа относятся к прогону целиком, и копия на каждом его сегменте
 * заставила бы читающего выбирать между одинаковыми. Есть только у прогонов, где эндпоинт отдавал
 * usage в стриме, — {@code null} здесь значит «не измерено», а не «ноль».
 *
 * <p>{@code fileRevert} — откат файловых правок ответа, выполненный пользователем (см. {@link
 * FileRevertMeta}). Признак своего ряда, как и {@code gitEvent}: контент пустой, весь смысл в поле.
 *
 * <p>{@code scriptEvent} — сохранённый скрипт, который пользователь запустил из этого чата командой
 * {@code /script} (см. {@link ScriptEventMeta}). Третий ряд той же семьи, что {@code gitEvent} и
 * {@code fileRevert}: контент пустой, весь смысл в поле, и читателей у него двое — плашка на фронте
 * и нотис модели.
 *
 * <p>{@code command} — ряд написан слэш-командой чата ({@code /compact}), а не репликой: текст у
 * него пользовательский и в ленте он выглядит обычным вопросом, но отвечает на него не модель, и
 * материалом для названия чата он не служит (см. {@code TopicPrompt}). Флаг ставится при сохранении
 * — разбирать текст ряда заново значило бы держать вторую копию списка триггеров фронта ({@code
 * chatCommands.js}), и разойтись эти копии могли бы молча.
 *
 * <p>{@code interjection} — вопрос доставлен ПОСРЕДИ прогона, между итерациями tool-цикла (см.
 * {@code PendingMessageService}): пользователь писал, глядя на ход работы, а не на готовый ответ.
 * Модель предупреждает нотис в {@code ChatHistoryService.promptRow}; для всего, что ищет «последний
 * вопрос» хода ({@code tailAfterLastUser} и его фронтовый двойник), такой ряд обязан быть
 * прозрачным — ход открыл не он.
 *
 * <p><b>Собирается только билдером</b> ({@link #builder()}, {@link #toBuilder()}), а не позиционным
 * конструктором: полей полтора десятка, у большинства один тип, и каждое новое поле правило бы все
 * места сборки — пропущенное молча теряло поле при копировании. Незаданное — {@code null}, {@code
 * false} или пустой список.
 */
@Builder(toBuilder = true)
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
        boolean interjection,
        @Nullable RunTokenUsage usage,
        List<ProjectSpan> visitedProjects,
        @Nullable FileRevertMeta fileRevert,
        @Nullable ScriptEventMeta scriptEvent,
        boolean command) {

    /** Мета, которой нечего о себе сказать: все поля по умолчанию. */
    public static final ChatMessageMeta EMPTY = builder().build();

    public ChatMessageMeta {
        invocations = invocations == null ? List.of() : invocations;
        contextItems = contextItems == null ? List.of() : contextItems;
        visitedProjects = visitedProjects == null ? List.of() : visitedProjects;
    }

    /** Метаданные «крошки» вызовов инструментов: флаг {@code toolCalls} и сами вызовы. */
    public static ChatMessageMeta ofToolCalls(List<ToolInvocationMeta> invocations) {
        return builder().toolCalls(true).invocations(invocations).build();
    }

    /**
     * Метаданные сообщения пользователя: приложенный контекст и принадлежность репозиторию —
     * маркером смены ({@code project} + {@code projectSwitchFrom}) или базовым штампом первого
     * сообщения ({@code project} без пары).
     *
     * <p>{@code null} — сообщению нечего о себе сказать. Один только штамп таким случаем не
     * является: без него у чата, который никуда не переключался, следа проекта не осталось бы
     * вовсе, и промпту пришлось бы догадываться о репозитории по {@code chat_topic}.
     */
    public static @Nullable ChatMessageMeta ofUserMessage(
            List<ContextItem> contextItems,
            @Nullable String project,
            @Nullable String projectSwitchFrom) {
        if (contextItems.isEmpty() && project == null) {
            return null;
        }
        return builder()
                .contextItems(contextItems)
                .project(project)
                .projectSwitchFrom(projectSwitchFrom)
                .build();
    }

    /** Метаданные сообщения пользователя: кроме приложенного контекста в них ничего нет. */
    public static ChatMessageMeta ofContextItems(List<ContextItem> contextItems) {
        return builder().contextItems(contextItems).build();
    }

    /**
     * Метаданные строки-плашки «контекст сжат»: что именно сделало сжатие и где лежит его сводка.
     */
    public static ChatMessageMeta ofCompact(CompactMeta compact) {
        return builder().compact(compact).build();
    }

    /**
     * Метаданные ряда, от которого остался один замер токенов: раунд сжатия, который провайдер
     * посчитал, но сводки не дал, — он записан на строку собственной команды (см. {@code
     * CompactService}). Замер на USER-ряду бывает только так.
     */
    public static ChatMessageMeta ofUsage(RunTokenUsage usage) {
        return builder().usage(usage).build();
    }

    /**
     * Метаданные ряда git-команды. Проект остаётся внутри самого события: {@code project} на этом
     * уровне значит «проект, на котором закончилась сжатая история» (см. {@link #ofProject}), а
     * этот ряд историю ни во что не переводит.
     */
    public static ChatMessageMeta ofGitEvent(GitEventMeta gitEvent) {
        return builder().gitEvent(gitEvent).build();
    }

    /**
     * Метаданные ряда отката файловых правок. Как и у ряда git-команды, проект остаётся внутри
     * самого события: {@code project} на этом уровне значит другое (см. {@link #ofProject}).
     */
    public static ChatMessageMeta ofFileRevert(FileRevertMeta fileRevert) {
        return builder().fileRevert(fileRevert).build();
    }

    /**
     * Метаданные ряда запуска сохранённого скрипта. Как у ряда git-команды и ряда отката, проект
     * остаётся внутри самого события: {@code project} на этом уровне значит другое (см. {@link
     * #ofProject}).
     */
    public static ChatMessageMeta ofScriptEvent(ScriptEventMeta scriptEvent) {
        return builder().scriptEvent(scriptEvent).build();
    }

    /**
     * Метаданные вопроса, доставленного посреди прогона: приложенный контекст плюс флаг {@code
     * interjection}. Флаг живёт в мете, а не выводится из положения ряда: после завершения прогона
     * ряд ничем больше не отличается от обычного вопроса, а прозрачность для «последнего вопроса»
     * хода нужна и тогда.
     */
    public static ChatMessageMeta ofInterjection(List<ContextItem> contextItems) {
        return builder().contextItems(contextItems).interjection(true).build();
    }

    /** Метаданные ряда слэш-команды чата: кроме флага {@code command} в них ничего нет. */
    public static ChatMessageMeta ofCommand() {
        return builder().command(true).build();
    }

    /**
     * Метаданные summary-строки: след проектов сжатой части истории. Спаны — то, что читают; {@code
     * project} («на каком проекте закончилось сжатое») пишется тем же вызовом ради отката на
     * прежнюю версию и в промпт не идёт.
     */
    public static ChatMessageMeta ofProject(
            @Nullable String project, List<ProjectSpan> visitedProjects) {
        return builder().project(project).visitedProjects(visitedProjects).build();
    }

    /**
     * Копия с проставленными прогоном и его моделью. Дописывает, а не заменяет: {@code
     * ChatHistoryService.markRunResult} проходит по рядам прогона последним, и уже сохранённые
     * плашки вызовов ({@code invocations}) обязаны пережить этот проход.
     */
    public ChatMessageMeta withRun(String runId, String model) {
        return toBuilder().runId(runId).model(model).build();
    }

    /**
     * Копия с проставленными токенами прогона. Отдельно от {@link #withRun}: модель проставляется
     * всем рядам прогона, а токены — одному (см. javadoc записи), и объединение этих двух пометок в
     * один вызов заставило бы вызывающего передавать {@code null} на каждом ряду, кроме последнего.
     */
    public ChatMessageMeta withUsage(RunTokenUsage usage) {
        return toBuilder().usage(usage).build();
    }

    /**
     * Копия с заменённым следом проектов — обеими его половинами сразу: спанами и одиноким {@code
     * project} рядом с ними (см. javadoc записи). Нужна разовому проходу {@code
     * ProjectStampBackfill}, который дописывает след к ряду, записанному чужой версией: собери он
     * мету заново, поле, о котором он не знает, пропало бы молча.
     */
    public ChatMessageMeta withProjectTrace(
            @Nullable String project, List<ProjectSpan> visitedProjects) {
        return toBuilder().project(project).visitedProjects(visitedProjects).build();
    }

    /** Копия с заменённым маркером смены проекта; остальные поля переживают перезапись. */
    public ChatMessageMeta withProjectSwitch(
            @Nullable String project, @Nullable String projectSwitchFrom) {
        return toBuilder().project(project).projectSwitchFrom(projectSwitchFrom).build();
    }
}
