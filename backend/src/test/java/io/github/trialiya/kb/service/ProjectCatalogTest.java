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
                        List.of(new ProjectOption("kb", null, "/srv/kb", null)),
                        legacy(null, true));
        ProjectCatalog own =
                catalog(
                        List.of(new ProjectOption("kb", null, "/srv/kb", false)),
                        legacy(null, true));

        assertThat(inherited.defaultProject().editEnabled()).isTrue();
        assertThat(own.defaultProject().editEnabled()).isFalse();
    }

    @Test
    void theLabelDefaultsToTheId() {
        ProjectCatalog catalog =
                catalog(
                        List.of(new ProjectOption("kb", " ", "/srv/kb", null)),
                        legacy(null, false));

        assertThat(catalog.defaultProject().label()).isEqualTo("kb");
    }

    @Test
    void aSecondProjectIsRefusedRatherThanIgnored() {
        List<ProjectOption> two =
                List.of(
                        new ProjectOption("kb", null, "/srv/kb", null),
                        new ProjectOption("billing", null, "/srv/billing", null));

        assertThatThrownBy(() -> catalog(two, legacy(null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("only one is supported");
    }

    @Test
    void anIdThatCouldNotSurviveAUrlIsRefused() {
        assertThatThrownBy(
                        () ->
                                catalog(
                                        List.of(
                                                new ProjectOption(
                                                        "My Repo", null, "/srv/kb", null)),
                                        legacy(null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("My Repo");
    }

    @Test
    void aProjectWithoutAPathIsRefused() {
        assertThatThrownBy(
                        () ->
                                catalog(
                                        List.of(new ProjectOption("kb", null, " ", null)),
                                        legacy(null, false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("path is required");
    }

    @Test
    void namingNoProjectMeansTheFirstOne() {
        ProjectCatalog catalog =
                catalog(
                        List.of(new ProjectOption("kb", null, "/srv/kb", null)),
                        legacy(null, false));

        assertThat(catalog.find(null)).contains(catalog.defaultProject());
        assertThat(catalog.find("")).contains(catalog.defaultProject());
        assertThat(catalog.require(null)).isEqualTo(catalog.defaultProject());
    }

    @Test
    void anUnknownProjectIsAnErrorRatherThanASilentFallback() {
        ProjectCatalog catalog =
                catalog(
                        List.of(new ProjectOption("kb", null, "/srv/kb", null)),
                        legacy(null, false));

        assertThat(catalog.find("billing")).isEmpty();
        assertThatThrownBy(() -> catalog.require("billing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("billing");
    }
}
