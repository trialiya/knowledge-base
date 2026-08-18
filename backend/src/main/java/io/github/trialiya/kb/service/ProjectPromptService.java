package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.project.Project;
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
        Every repo-file link must carry it: `[filename](/files?path=PATH&project=%s)`.\
        """
                .formatted(project.label(), project.id(), project.id());
    }
}
