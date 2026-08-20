package io.github.trialiya.kb.service.chat;

import io.github.trialiya.kb.model.project.Project;
import io.github.trialiya.kb.service.file.GitRegistry;
import io.github.trialiya.kb.service.file.ProjectCatalog;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Текст плейсхолдера {@code {project_context}} в {@code prompt/sys.md} — какой репозиторий читают
 * инструменты этого прогона.
 *
 * <p>Модель обязана знать проект не «для сведения»: ссылку на файл пишет она, а `/files?path=…` без
 * проекта означает дефолтный. Не назвав проект в промпте, мы получили бы ссылки, которые в другом
 * репозитории откроют файл с тем же путём — ошибку, которую никто не заметит. Поэтому здесь же
 * выдаётся готовый кусок ссылки, а не предложение вывести его самостоятельно.
 *
 * <p>Параллель {@code ScriptGuideService}/{@code SystemPromptService}: значение не бывает {@code
 * null} — незаполненный плейсхолдер роняет рендер шаблона.
 */
@Service
public class ProjectPromptService {

    private final ProjectCatalog catalog;

    /**
     * Пишем ли мы вообще в этот проект — вопрос к реестру, а не к настройке: при ro-монтировании
     * инструментов правки у модели нет, и обещать ей что-либо про них нельзя.
     */
    private final GitRegistry gitRegistry;

    public ProjectPromptService(ProjectCatalog catalog, GitRegistry gitRegistry) {
        this.catalog = catalog;
        this.gitRegistry = gitRegistry;
    }

    /**
     * @param projectId проект прогона; {@code null} — «не выбран», т.е. дефолтный из списка
     */
    public String context(@Nullable String projectId) {
        Project project = catalog.find(projectId).orElseGet(catalog::defaultProject);
        return """
        ### Active project
        Files, commits and scripts in this chat read the **%s** repository — project id `%s`.
        Every repo-file link must carry it: `[filename](/files?path=PATH&project=%s)`.
        `getFileContent`, `grepContent` and `runScript` accept an optional `project` argument to
        read a different repository instead, for a cross-project question — leave it out to use
        this one. Reading only: edits always land in this project, and a `runScript` that names
        another one cannot write at all.\
        """
                        .formatted(project.label(), project.id(), project.id())
                + allowGlobs(project, gitRegistry.editsAllowed(project.id()));
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
