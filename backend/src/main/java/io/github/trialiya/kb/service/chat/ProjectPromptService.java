package io.github.trialiya.kb.service.chat;

import io.github.trialiya.kb.model.project.Project;
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

    public ProjectPromptService(ProjectCatalog catalog) {
        this.catalog = catalog;
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
                + allowGlobs(project);
    }

    /**
     * Приписка про {@code allow-globs} проекта — «tracked files only» из {@code sys.md} для него
     * уже неправда.
     *
     * <p>Без неё модель считает untracked-файлы недоступными и не пойдёт их искать: правило в
     * промпте статичное, а исключение из него — настройка конкретного проекта.
     */
    private static String allowGlobs(Project project) {
        if (project.allowGlobs().isEmpty()) {
            return "";
        }
        return """

        Beyond the tracked files, this project also serves untracked files matching %s, whatever
        `.gitignore` says about them — build reports and local notes live there. They are listed
        and readable like any other file and `editFile` works on them, but they cannot be created,
        and `grepContent` skips them unless you pass `includeUntracked: true`.\
        """
                .formatted(String.join(", ", project.allowGlobs()));
    }
}
