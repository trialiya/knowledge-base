package io.github.trialiya.kb.service.chat.prompt;

import io.github.trialiya.kb.model.chat.entity.ProjectSpan;
import io.github.trialiya.kb.model.project.Project;
import io.github.trialiya.kb.service.chat.skill.SkillService;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.project.ProjectCatalog;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Текст блока «какой репозиторий читают инструменты этого чата» — тело нотиса {@code
 * <active-project>}, который {@code ActiveProjectNotice} ставит на последний вопрос окна.
 *
 * <p>В системном промпте этого блока нет намеренно. Проект — свойство разговора, а не приложения:
 * он меняется посреди чата, и сказанное в системном промпте относилось бы ко всей истории разом, в
 * том числе к сообщениям, прочитанным в другом репозитории. В сообщении блок стоит там, где
 * действует, — на текущем ходу, за спиной у истории, которая может быть чужой.
 *
 * <p>Модель обязана знать проект не «для сведения»: ссылку на файл пишет она, а `/files?path=…` без
 * проекта означает дефолтный. Не назвав проект, мы получили бы ссылки, которые в другом репозитории
 * откроют файл с тем же путём — ошибку, которую никто не заметит. Поэтому здесь же выдаётся готовый
 * кусок ссылки, а не предложение вывести его самостоятельно.
 *
 * <p>Правила кросс-проектного чтения — что аргумент {@code project} есть у каждого читающего
 * инструмента, что id берётся из эха ответа, что правки остаются в активном проекте — статичны и
 * живут в {@code sys.md} («Reading another project»); дублировать их здесь значило бы платить за
 * них дважды и однажды разойтись.
 *
 * <p><b>Это горячий путь.</b> Блок собирается при каждом чтении окна, то есть на каждой итерации
 * tool-цикла, а не раз на прогон, как было у системного промпта. Сейчас цена — одна проверка прав
 * на дерево ({@code GitRegistry#editsAllowed} спрашивает файловую систему намеренно) и сборка пары
 * килобайт текста; на фоне запроса к модели это ничто, но всё, что сюда добавляют, платится столько
 * же раз.
 */
@Service
public class ProjectPromptService {

    private final ProjectCatalog catalog;

    /**
     * Пишем ли мы вообще в этот проект — вопрос к реестру, а не к настройке: при ro-монтировании
     * инструментов правки у модели нет, и обещать ей что-либо про них нельзя.
     */
    private final GitRegistry gitRegistry;

    /**
     * Навыки активного проекта объявляются здесь, а не в системном промпте, — тому же правилу
     * подчинён весь блок: их список меняется со сменой проекта, а системный промпт менять при этом
     * нельзя (см. javadoc {@code SkillService}). Секцию рендерит сам {@code SkillService}, чтобы
     * все слова про навыки жили в одном месте.
     */
    private final SkillService skills;

    public ProjectPromptService(
            ProjectCatalog catalog, GitRegistry gitRegistry, SkillService skills) {
        this.catalog = catalog;
        this.gitRegistry = gitRegistry;
        this.skills = skills;
    }

    /**
     * @param projectId проект прогона; {@code null} — «не выбран», т.е. дефолтный из списка
     * @param visited где прожит каждый кусок чата, хронологически (см. {@link ProjectSpan});
     *     активный проект среди отрезков есть и отфильтровывается здесь, чтобы вызывающему не
     *     приходилось знать, чем он разрешился
     */
    public String context(@Nullable String projectId, List<ProjectSpan> visited) {
        Project project = catalog.find(projectId).orElseGet(catalog::defaultProject);
        return """
        ### Active project
        Files, commits and scripts in this chat read the **%s** repository — project id `%s`.
        Every repo-file link must carry it: `[filename](/files?path=PATH&project=%s)`.\
        """
                        .formatted(project.label(), project.id(), project.id())
                + allowGlobs(project, gitRegistry.editsAllowed(project.id()))
                + skills.projectSkills(project)
                + timeline(visited)
                + otherProjects(project, visited);
    }

    /**
     * Что где читано: отрезки чата с номерами сообщений. Печатается только у чата, который проект
     * действительно менял, — там, где репозиторий один, строка «messages 1-140» не сообщает ничего,
     * а платить за неё пришлось бы в каждом запросе каждого чата.
     *
     * <p>Диапазоны, а не список посещённых id: без них «этот проект выбирали раньше» не отвечает на
     * единственный вопрос, ради которого след и хранится, — в каком репозитории читан файл, о
     * котором речь в сообщении 40. Номера здесь те же, что в {@code [msg:N]}, и {@code
     * getOriginalMessages} достаёт по ним даже сжатые сообщения, так что диапазон можно не только
     * прочесть, но и раскрыть.
     *
     * <p>Проект, выбывший из конфигурации, остаётся в списке под своим id: история и правда читана
     * в нём, и умолчать об этом значило бы приписать те сообщения соседнему репозиторию. Назвать
     * его в аргументе {@code project} нельзя — этим занят список ниже, и туда он не попадает.
     */
    private String timeline(List<ProjectSpan> visited) {
        if (visited.stream().map(ProjectSpan::project).distinct().count() <= 1) {
            return "";
        }
        final StringBuilder sb =
                new StringBuilder(
                        "\n\nThis chat changed repository along the way. Which messages belong"
                                + " where:");
        for (int i = 0; i < visited.size(); i++) {
            final ProjectSpan span = visited.get(i);
            sb.append("\n- `")
                    .append(span.project())
                    .append("` — ")
                    .append(catalog.find(span.project()).map(Project::label).orElse(span.project()))
                    .append(" — ")
                    .append(range(span, i == visited.size() - 1));
        }
        sb.append(
                "\nPaths, file contents, grep hits and script output in a range belong to that"
                        + " range's repository, whatever the active project is now.");
        return sb.toString();
    }

    /**
     * Диапазон одного отрезка. Последний открыт — и не только потому, что чат в этом репозитории
     * продолжается: <b>от этого зависит стабильность блока</b>. Собирается он на каждой итерации
     * tool-цикла, а окно за итерацию прирастает TOOL-рядами, то есть верхняя граница последнего
     * отрезка растёт. Напечатай её числом — и текст последнего вопроса менялся бы внутри одного
     * прогона, сбивая кэш промпта на ровном месте. Закрытые отрезки такого не умеют: их границы
     * зафиксированы сжатием.
     */
    private static String range(ProjectSpan span, boolean current) {
        if (current) {
            return "message " + span.from() + " onward (the active project)";
        }
        return span.from() == span.to()
                ? "message " + span.from()
                : "messages " + span.from() + "-" + span.to();
    }

    /**
     * Список репозиториев, которые модель вправе назвать в аргументе {@code project}. Без него
     * аргумент бесполезен: id проекта модели взять неоткуда — выше был назван только активный, а
     * выдуманный id падает на {@code ProjectCatalog#require}.
     *
     * <p>Проекты, на которых чат уже работал, идут первыми: чаще всего вопрос «а как это было в
     * прошлом проекте» относится именно к ним, и половина истории чата прочитана там же. Чем именно
     * они заняты в этом чате, говорит таймлайн выше — здесь только отсылка к нему, чтобы двум
     * текстам про одно и то же не разойтись.
     *
     * <p>Недоступные проекты (репозиторий не открылся) не перечисляются вовсе: назвать такой id
     * модель может только чтобы получить отказ.
     */
    private String otherProjects(Project active, List<ProjectSpan> visited) {
        List<String> earlier =
                visited.stream()
                        .map(ProjectSpan::project)
                        .filter(id -> !id.equals(active.id()))
                        .filter(catalog::isAllowed)
                        .filter(gitRegistry::isAvailable)
                        .distinct()
                        .toList();
        List<Project> rest =
                catalog.projects().stream()
                        .filter(p -> !p.id().equals(active.id()))
                        .filter(p -> !earlier.contains(p.id()))
                        .filter(p -> gitRegistry.isAvailable(p.id()))
                        .toList();
        if (earlier.isEmpty() && rest.isEmpty()) {
            return "";
        }
        StringBuilder sb =
                new StringBuilder("\n\nOther repositories you may read — pass the id as the");
        sb.append(" `project` argument of a read tool (see \"Reading another project\"):");
        earlier.forEach(
                id ->
                        sb.append("\n- `")
                                .append(id)
                                .append("` — ")
                                .append(catalog.require(id).label())
                                .append(" — this chat worked in it earlier, see the ranges above"));
        rest.forEach(p -> sb.append("\n- `").append(p.id()).append("` — ").append(p.label()));
        return sb.toString();
    }

    /**
     * Приписка про {@code allow-globs} проекта — «tracked files only» из {@code sys.md} для него
     * уже неправда.
     *
     * <p>Без неё модель считает untracked-файлы недоступными и не пойдёт их искать: правило в
     * промпте статичное, а исключение из него — настройка конкретного проекта.
     *
     * <p>Правку этих файлов проект разрешает отдельно ({@code untracked-edit-enabled}), и сказано
     * это в обе стороны: без явного «нельзя» модель, увидев файл в выдаче, потратит вызов {@code
     * editFile} на отказ, а потом ещё один — на попытку обойти его через {@code runScript}.
     */
    private static String allowGlobs(Project project, boolean editsAllowed) {
        if (project.allowGlobs().isEmpty()) {
            return "";
        }
        return """

        Beyond the tracked files, this project also serves untracked files matching %s, whatever
        `.gitignore` says about them — build reports and local notes live there. They are listed
        and readable like any other file, `grepContent` skips them unless you pass
        `includeUntracked: true`, and `getUncommittedChanges` reports them as `U`. %s\
        """
                // stripTrailing: у проекта без правок вторая подстановка пуста, и без этого
                // абзац кончался бы висящим пробелом.
                .formatted(
                        String.join(", ", project.allowGlobs()),
                        untrackedEdits(project, editsAllowed))
                .stripTrailing();
    }

    /**
     * Что можно делать с untracked-файлами проекта — одной фразой, без «возможно». Проекту, который
     * не пишет вовсе, фразы не достаётся: правила про `editFile` там незачем — самого инструмента у
     * модели нет.
     */
    private static String untrackedEdits(Project project, boolean editsAllowed) {
        if (!editsAllowed) {
            return "";
        }
        return project.untrackedEditEnabled()
                ? """
                Editing them is allowed: `editFile` works on them and leaves them untracked (they
                are never staged), but they cannot be created — `createFile` there is refused.\
                """
                : """
                Editing them is NOT allowed: they are read-only here, and `editFile`, `createFile`
                and the `runScript` write methods all refuse them. Only tracked files can be
                changed in this project.\
                """;
    }
}
