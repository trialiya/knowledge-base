package io.github.trialiya.kb.service.chat.prompt;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ProjectProperties;
import io.github.trialiya.kb.config.model.ProjectProperties.ProjectOption;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.project.ProjectCatalog;
import io.github.trialiya.kb.support.TestProjects;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Что промпт говорит модели про репозитории: активный — и те, которые она вправе назвать в
 * необязательном аргументе {@code project} читающего инструмента.
 *
 * <p>Второй список — не украшение: id проекта модели взять больше неоткуда, а выдуманный падает на
 * {@code ProjectCatalog#require}. Без строки в промпте кросс-проектное чтение остаётся
 * возможностью, которой никто не пользуется.
 */
class ProjectPromptServiceTest {

    @TempDir Path root;

    private ProjectPromptService service(String... ids) throws IOException {
        List<ProjectOption> options = List.of(ids).stream().map(this::project).toList();
        ProjectCatalog catalog =
                new ProjectCatalog(new ProjectProperties(options), new GitProperties(null));
        GitRegistry registry = TestProjects.registry(options);
        return new ProjectPromptService(catalog, registry);
    }

    private ProjectOption project(String id) {
        Path dir = root.resolve(id);
        try {
            java.nio.file.Files.createDirectories(dir);
            new ProcessBuilder("git", "init", "-q").directory(dir.toFile()).start().waitFor();
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
        return new ProjectOption(id, id.toUpperCase(), dir.toString(), false, false, null, true);
    }

    @Test
    void theActiveProjectIsNamedWithItsIdAndLinkForm() throws IOException {
        String text = service("kb").context("kb", List.of());

        assertThat(text).contains("project id `kb`").contains("/files?path=PATH&project=kb");
    }

    @Test
    void aLoneProjectGetsNoListOfOthers() throws IOException {
        String text = service("kb").context("kb", List.of());

        assertThat(text).doesNotContain("Other repositories");
    }

    @Test
    void everyOtherConfiguredProjectIsOfferedByItsId() throws IOException {
        String text = service("kb", "billing").context("kb", List.of());

        assertThat(text).contains("Other repositories").contains("`billing`");
    }

    @Test
    void aProjectTheChatWorkedOnEarlierIsMarkedAsSuch() throws IOException {
        String text = service("kb", "billing", "docs").context("kb", List.of("billing"));

        assertThat(text).contains("`billing` — BILLING — selected earlier in this chat");
        // Тот, где чат не был, перечислен без пометки — назвать его тоже можно.
        assertThat(text).contains("`docs` — DOCS").doesNotContain("`docs` — DOCS — selected");
    }

    @Test
    void theActiveProjectIsNeverOfferedTwice() throws IOException {
        String text = service("kb", "billing").context("kb", List.of("kb", "billing"));

        assertThat(text).doesNotContain("`kb` — KB");
    }

    /** Уехавший из конфигурации проект называть нечем — {@code require} на нём и падает. */
    @Test
    void anEarlierProjectThatIsNoLongerConfiguredIsDropped() throws IOException {
        String text = service("kb", "billing").context("kb", List.of("gone"));

        assertThat(text).doesNotContain("gone");
    }
}
