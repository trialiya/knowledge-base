package io.github.trialiya.kb.service.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ProjectProperties;
import io.github.trialiya.kb.config.model.ProjectProperties.ProjectOption;
import io.github.trialiya.kb.model.project.Project;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What a deployment's configuration resolves to — including the two answers the rest of the code
 * leans on everywhere: an unnamed project is the first one, and a configuration that cannot be
 * served fails the context instead of producing a catalogue nobody can use.
 */
class ProjectCatalogTest {

    private static ProjectCatalog catalog(List<ProjectOption> projects, GitProperties git) {
        return new ProjectCatalog(new ProjectProperties(projects), git);
    }

    private static GitProperties legacy(String path) {
        return new GitProperties(path);
    }

    @Test
    void anEmptyListFallsBackToTheLegacyReadOnlySingleProject() {
        ProjectCatalog catalog = catalog(List.of(), legacy("/srv/repo"));

        Project project = catalog.defaultProject();
        assertThat(catalog.projects()).hasSize(1);
        assertThat(project.id()).isEqualTo("default");
        assertThat(project.path()).isEqualTo(Path.of("/srv/repo").toAbsolutePath().normalize());
        // The legacy form carries no edit opt-in: writes require a kb.projects entry of their own.
        assertThat(project.editEnabled()).isFalse();
        assertThat(project.allowGlobs()).isEmpty();
    }

    @Test
    void withNothingConfiguredAtAllTheContextFails() {
        assertThatThrownBy(() -> catalog(List.of(), legacy(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kb.projects[0].path");
    }

    @Test
    void editsAndAllowGlobsAreCarriedPerProject() {
        ProjectCatalog catalog =
                catalog(
                        List.of(
                                new ProjectOption(
                                        "kb", null, "/srv/kb", true, List.of("notes/**"), true),
                                new ProjectOption(
                                        "billing", null, "/srv/billing", false, null, true)),
                        legacy(null));

        assertThat(catalog.require("kb").editEnabled()).isTrue();
        assertThat(catalog.require("kb").allowGlobs()).containsExactly("notes/**");
        assertThat(catalog.require("billing").editEnabled()).isFalse();
        assertThat(catalog.require("billing").allowGlobs()).isEmpty();
    }

    @Test
    void theLabelDefaultsToTheId() {
        ProjectCatalog catalog =
                catalog(
                        List.of(new ProjectOption("kb", " ", "/srv/kb", false, null, true)),
                        legacy(null));

        assertThat(catalog.defaultProject().label()).isEqualTo("kb");
    }

    /** Порядок записей — не украшение: первая и есть ответ на «проект не назван». */
    @Test
    void everyEnabledProjectIsServedAndTheFirstOneIsTheDefault() {
        ProjectCatalog catalog =
                catalog(
                        List.of(
                                new ProjectOption("kb", null, "/srv/kb", false, null, true),
                                new ProjectOption(
                                        "billing", "Billing", "/srv/billing", true, null, true)),
                        legacy(null));

        assertThat(catalog.projects()).extracting(Project::id).containsExactly("kb", "billing");
        assertThat(catalog.defaultProject().id()).isEqualTo("kb");
        assertThat(catalog.isAllowed("billing")).isTrue();
        assertThat(catalog.require("billing").path())
                .isEqualTo(Path.of("/srv/billing").toAbsolutePath().normalize());
        assertThat(catalog.require("billing").editEnabled()).isTrue();
    }

    /**
     * Так проект и готовят до того, как доехал его mount: блок лежит в конфигурации выключенным.
     * Выключенного проекта для приложения нет вовсе.
     */
    @Test
    void aDisabledProjectIsNeitherServedNorCounted() {
        ProjectCatalog catalog =
                catalog(
                        List.of(
                                new ProjectOption("kb", null, "/srv/kb", false, null, true),
                                new ProjectOption(
                                        "billing", null, "/srv/billing", false, null, false)),
                        legacy(null));

        assertThat(catalog.projects()).singleElement().extracting(Project::id).isEqualTo("kb");
        assertThat(catalog.isAllowed("billing")).isFalse();
        assertThat(catalog.find("billing")).isEmpty();
    }

    /** Выключенный проект не проверяют: его путь может быть ещё не смонтирован. */
    @Test
    void aDisabledProjectIsNotValidated() {
        ProjectCatalog catalog =
                catalog(
                        List.of(
                                new ProjectOption("kb", null, "/srv/kb", false, null, true),
                                new ProjectOption("Not An Id", null, "", false, null, false)),
                        legacy(null));

        assertThat(catalog.defaultProject().id()).isEqualTo("kb");
    }

    @Test
    void switchingOffEveryProjectLeavesNothingToServe() {
        assertThatThrownBy(
                        () ->
                                catalog(
                                        List.of(
                                                new ProjectOption(
                                                        "kb", null, "/srv/kb", false, null, false)),
                                        legacy("/srv/legacy")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("every configured project is disabled");
    }

    @Test
    void anIdThatCouldNotSurviveAUrlIsRefused() {
        assertThatThrownBy(
                        () ->
                                catalog(
                                        List.of(
                                                new ProjectOption(
                                                        "My Repo", null, "/srv/kb", false, null,
                                                        true)),
                                        legacy(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("My Repo");
    }

    @Test
    void aProjectWithoutAPathIsRefused() {
        assertThatThrownBy(
                        () ->
                                catalog(
                                        List.of(
                                                new ProjectOption(
                                                        "kb", null, " ", false, null, true)),
                                        legacy(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No project configured");
    }

    @Test
    void namingNoProjectMeansTheFirstOne() {
        ProjectCatalog catalog =
                catalog(
                        List.of(new ProjectOption("kb", null, "/srv/kb", false, null, true)),
                        legacy(null));

        assertThat(catalog.find(null)).contains(catalog.defaultProject());
        assertThat(catalog.find("")).contains(catalog.defaultProject());
        assertThat(catalog.require(null)).isEqualTo(catalog.defaultProject());
    }

    /**
     * {@code isAllowed} — не {@code find}: пустое имя здесь не «дефолт», а значение, которого никто
     * не настраивал. На этом держится резолв выбранного в чате проекта, где «не назван» уже занят
     * отдельным случаем.
     */
    @Test
    void anUnsetProjectIsNotAllowedEvenThoughItResolvesToTheDefault() {
        ProjectCatalog catalog =
                catalog(
                        List.of(new ProjectOption("kb", null, "/srv/kb", false, null, true)),
                        legacy(null));

        assertThat(catalog.isAllowed("kb")).isTrue();
        assertThat(catalog.isAllowed("billing")).isFalse();
        assertThat(catalog.isAllowed(null)).isFalse();
        assertThat(catalog.isAllowed("")).isFalse();
    }

    @Test
    void anUnknownProjectIsAnErrorRatherThanASilentFallback() {
        ProjectCatalog catalog =
                catalog(
                        List.of(new ProjectOption("kb", null, "/srv/kb", false, null, true)),
                        legacy(null));

        assertThat(catalog.find("billing")).isEmpty();
        assertThatThrownBy(() -> catalog.require("billing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("billing");
    }
}
