package io.github.trialiya.kb.service;

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

    private static GitProperties legacy(String path, boolean editEnabled) {
        return new GitProperties(path, editEnabled);
    }

    @Test
    void anEmptyListFallsBackToTheLegacySingleProject() {
        ProjectCatalog catalog = catalog(List.of(), legacy("/srv/repo", true));

        Project project = catalog.defaultProject();
        assertThat(catalog.projects()).hasSize(1);
        assertThat(project.id()).isEqualTo("default");
        assertThat(project.path()).isEqualTo(Path.of("/srv/repo").toAbsolutePath().normalize());
        assertThat(project.editEnabled()).isTrue();
    }

    @Test
    void withNothingConfiguredAtAllTheContextFails() {
        assertThatThrownBy(() -> catalog(List.of(), legacy(null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kb.projects[0].path");
    }

    @Test
    void aProjectWithoutItsOwnFlagInheritsTheDeploymentWideOne() {
        ProjectCatalog inherited =
                catalog(
                        List.of(new ProjectOption("kb", null, "/srv/kb", null, true)),
                        legacy(null, true));
        ProjectCatalog own =
                catalog(
                        List.of(new ProjectOption("kb", null, "/srv/kb", false, true)),
                        legacy(null, true));

        assertThat(inherited.defaultProject().editEnabled()).isTrue();
        assertThat(own.defaultProject().editEnabled()).isFalse();
    }

    @Test
    void theLabelDefaultsToTheId() {
        ProjectCatalog catalog =
                catalog(
                        List.of(new ProjectOption("kb", " ", "/srv/kb", null, true)),
                        legacy(null, false));

        assertThat(catalog.defaultProject().label()).isEqualTo("kb");
    }

    @Test
    void aSecondEnabledProjectIsRefusedRatherThanIgnored() {
        List<ProjectOption> two =
                List.of(
                        new ProjectOption("kb", null, "/srv/kb", null, true),
                        new ProjectOption("billing", null, "/srv/billing", null, true));

        assertThatThrownBy(() -> catalog(two, legacy(null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only one is supported");
    }

    /**
     * Так второй проект и готовят: его блок лежит в конфигурации выключенным, пока фронт и ссылки
     * не научатся его различать. Выключенного проекта для приложения нет вовсе.
     */
    @Test
    void aDisabledProjectIsNeitherServedNorCounted() {
        ProjectCatalog catalog =
                catalog(
                        List.of(
                                new ProjectOption("kb", null, "/srv/kb", null, true),
                                new ProjectOption("billing", null, "/srv/billing", null, false)),
                        legacy(null, false));

        assertThat(catalog.projects()).singleElement().extracting(Project::id).isEqualTo("kb");
        assertThat(catalog.isAllowed("billing")).isFalse();
        assertThat(catalog.find("billing")).isEmpty();
        assertThat(catalog.options().projects()).hasSize(1);
    }

    /** Выключенный проект не проверяют: его путь может быть ещё не смонтирован. */
    @Test
    void aDisabledProjectIsNotValidated() {
        ProjectCatalog catalog =
                catalog(
                        List.of(
                                new ProjectOption("kb", null, "/srv/kb", null, true),
                                new ProjectOption("Not An Id", null, "", null, false)),
                        legacy(null, false));

        assertThat(catalog.defaultProject().id()).isEqualTo("kb");
    }

    @Test
    void switchingOffEveryProjectLeavesNothingToServe() {
        assertThatThrownBy(
                        () ->
                                catalog(
                                        List.of(
                                                new ProjectOption(
                                                        "kb", null, "/srv/kb", null, false)),
                                        legacy("/srv/legacy", false)))
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
                                                        "My Repo", null, "/srv/kb", null, true)),
                                        legacy(null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("My Repo");
    }

    @Test
    void aProjectWithoutAPathIsRefused() {
        assertThatThrownBy(
                        () ->
                                catalog(
                                        List.of(new ProjectOption("kb", null, " ", null, true)),
                                        legacy(null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No project configured");
    }

    @Test
    void namingNoProjectMeansTheFirstOne() {
        ProjectCatalog catalog =
                catalog(
                        List.of(new ProjectOption("kb", null, "/srv/kb", null, true)),
                        legacy(null, false));

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
                        List.of(new ProjectOption("kb", null, "/srv/kb", null, true)),
                        legacy(null, false));

        assertThat(catalog.isAllowed("kb")).isTrue();
        assertThat(catalog.isAllowed("billing")).isFalse();
        assertThat(catalog.isAllowed(null)).isFalse();
        assertThat(catalog.isAllowed("")).isFalse();
    }

    @Test
    void theSelectorIsOfferedTheListAndThePreselectedEntry() {
        ProjectCatalog catalog =
                catalog(
                        List.of(new ProjectOption("kb", "KB", "/srv/kb", true, true)),
                        legacy(null, false));

        assertThat(catalog.options().defaultProject()).isEqualTo("kb");
        assertThat(catalog.options().projects())
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.id()).isEqualTo("kb");
                            assertThat(p.label()).isEqualTo("KB");
                            assertThat(p.editEnabled()).isTrue();
                        });
    }

    @Test
    void anUnknownProjectIsAnErrorRatherThanASilentFallback() {
        ProjectCatalog catalog =
                catalog(
                        List.of(new ProjectOption("kb", null, "/srv/kb", null, true)),
                        legacy(null, false));

        assertThat(catalog.find("billing")).isEmpty();
        assertThatThrownBy(() -> catalog.require("billing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("billing");
    }
}
