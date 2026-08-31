package io.github.trialiya.kb.service.file.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ProjectProperties;
import io.github.trialiya.kb.config.model.ProjectProperties.GitCommandsOption;
import io.github.trialiya.kb.config.model.ProjectProperties.ProjectOption;
import io.github.trialiya.kb.config.model.ProjectProperties.SkillOption;
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
                                        "kb",
                                        null,
                                        "/srv/kb",
                                        true,
                                        true,
                                        List.of("notes/**"),
                                        null,
                                        null,
                                        true),
                                new ProjectOption(
                                        "billing",
                                        null,
                                        "/srv/billing",
                                        false,
                                        false,
                                        null,
                                        null,
                                        null,
                                        true)),
                        legacy(null));

        assertThat(catalog.require("kb").editEnabled()).isTrue();
        assertThat(catalog.require("kb").untrackedEditEnabled()).isTrue();
        assertThat(catalog.require("kb").allowGlobs()).containsExactly("notes/**");
        assertThat(catalog.require("billing").editEnabled()).isFalse();
        assertThat(catalog.require("billing").untrackedEditEnabled()).isFalse();
        assertThat(catalog.require("billing").allowGlobs()).isEmpty();
    }

    /**
     * {@code untracked-edit-enabled} только сужает правки, а не открывает их: на проекте без {@code
     * edit-enabled} он не должен пролезть в {@link Project} — иначе один флаг в конфиге сделал бы
     * репозиторий записываемым в обход второго.
     */
    @Test
    void untrackedEditsNeedTheProjectToAllowEditsAtAll() {
        ProjectCatalog catalog =
                catalog(
                        List.of(
                                new ProjectOption(
                                        "kb",
                                        null,
                                        "/srv/kb",
                                        false,
                                        true,
                                        List.of("notes/**"),
                                        null,
                                        null,
                                        true)),
                        legacy(null));

        assertThat(catalog.require("kb").editEnabled()).isFalse();
        assertThat(catalog.require("kb").untrackedEditEnabled()).isFalse();
    }

    /** Пользовательские git-команды — свой раздел конфигурации и своё разрешение на проект. */
    @Test
    void gitCommandsAreCarriedPerProject() {
        ProjectCatalog catalog =
                catalog(
                        List.of(
                                new ProjectOption(
                                        "kb",
                                        null,
                                        "/srv/kb",
                                        false,
                                        false,
                                        null,
                                        null,
                                        new GitCommandsOption(true, true),
                                        true),
                                new ProjectOption(
                                        "billing",
                                        null,
                                        "/srv/billing",
                                        false,
                                        false,
                                        null,
                                        null,
                                        new GitCommandsOption(true, false),
                                        true)),
                        legacy(null));

        assertThat(catalog.require("kb").gitCommandsEnabled()).isTrue();
        assertThat(catalog.require("kb").gitPushEnabled()).isTrue();
        assertThat(catalog.require("billing").gitCommandsEnabled()).isTrue();
        assertThat(catalog.require("billing").gitPushEnabled()).isFalse();
    }

    /** Раздела нет — не даётся ничего: команды пользователя всегда явный opt-in. */
    @Test
    void withoutTheSectionNoGitCommandIsOffered() {
        ProjectCatalog catalog =
                catalog(
                        List.of(
                                new ProjectOption(
                                        "kb", null, "/srv/kb", true, false, null, null, null,
                                        true)),
                        legacy(null));

        assertThat(catalog.require("kb").gitCommandsEnabled()).isFalse();
        assertThat(catalog.require("kb").gitPushEnabled()).isFalse();
    }

    /**
     * {@code push-enabled} только сужает набор команд: без {@code enabled} он не должен открыть
     * публикацию в remote в обход второго флага — ровно как {@code untracked-edit-enabled} не
     * открывает записи.
     */
    @Test
    void pushNeedsTheProjectToAllowGitCommandsAtAll() {
        ProjectCatalog catalog =
                catalog(
                        List.of(
                                new ProjectOption(
                                        "kb",
                                        null,
                                        "/srv/kb",
                                        false,
                                        false,
                                        null,
                                        null,
                                        new GitCommandsOption(false, true),
                                        true)),
                        legacy(null));

        assertThat(catalog.require("kb").gitCommandsEnabled()).isFalse();
        assertThat(catalog.require("kb").gitPushEnabled()).isFalse();
    }

    @Test
    void theLabelDefaultsToTheId() {
        ProjectCatalog catalog =
                catalog(
                        List.of(
                                new ProjectOption(
                                        "kb", " ", "/srv/kb", false, false, null, null, null,
                                        true)),
                        legacy(null));

        assertThat(catalog.defaultProject().label()).isEqualTo("kb");
    }

    /** Порядок записей — не украшение: первая и есть ответ на «проект не назван». */
    @Test
    void everyEnabledProjectIsServedAndTheFirstOneIsTheDefault() {
        ProjectCatalog catalog =
                catalog(
                        List.of(
                                new ProjectOption(
                                        "kb", null, "/srv/kb", false, false, null, null, null,
                                        true),
                                new ProjectOption(
                                        "billing",
                                        "Billing",
                                        "/srv/billing",
                                        true,
                                        false,
                                        null,
                                        null,
                                        null,
                                        true)),
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
                                new ProjectOption(
                                        "kb", null, "/srv/kb", false, false, null, null, null,
                                        true),
                                new ProjectOption(
                                        "billing",
                                        null,
                                        "/srv/billing",
                                        false,
                                        false,
                                        null,
                                        null,
                                        null,
                                        false)),
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
                                new ProjectOption(
                                        "kb", null, "/srv/kb", false, false, null, null, null,
                                        true),
                                new ProjectOption(
                                        "Not An Id",
                                        null,
                                        "",
                                        false,
                                        false,
                                        null,
                                        null,
                                        null,
                                        false)),
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
                                                        "kb", null, "/srv/kb", false, false, null,
                                                        null, null, false)),
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
                                                        "My Repo", null, "/srv/kb", false, false,
                                                        null, null, null, true)),
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
                                                        "kb", null, " ", false, false, null, null,
                                                        null, true)),
                                        legacy(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No project configured");
    }

    @Test
    void namingNoProjectMeansTheFirstOne() {
        ProjectCatalog catalog =
                catalog(
                        List.of(
                                new ProjectOption(
                                        "kb", null, "/srv/kb", false, false, null, null, null,
                                        true)),
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
                        List.of(
                                new ProjectOption(
                                        "kb", null, "/srv/kb", false, false, null, null, null,
                                        true)),
                        legacy(null));

        assertThat(catalog.isAllowed("kb")).isTrue();
        assertThat(catalog.isAllowed("billing")).isFalse();
        assertThat(catalog.isAllowed(null)).isFalse();
        assertThat(catalog.isAllowed("")).isFalse();
    }

    // ── kb.projects[].skills ─────────────────────────────────────────────────

    private static ProjectOption withSkills(SkillOption... skills) {
        return new ProjectOption(
                "kb", null, "/srv/kb", false, false, null, List.of(skills), null, true);
    }

    /** Путь навыка разрешается от дерева проекта; триггер обрезается, порядок — конфигурации. */
    @Test
    void skillsAreResolvedAgainstTheProjectTree() {
        ProjectCatalog catalog =
                catalog(
                        List.of(
                                withSkills(
                                        new SkillOption(
                                                "release",
                                                " before a release ",
                                                "docs/skills/release.md"))),
                        legacy(null));

        assertThat(catalog.require("kb").skills())
                .singleElement()
                .satisfies(
                        skill -> {
                            assertThat(skill.name()).isEqualTo("release");
                            assertThat(skill.trigger()).isEqualTo("before a release");
                            assertThat(skill.file())
                                    .isEqualTo(
                                            Path.of("/srv/kb/docs/skills/release.md")
                                                    .toAbsolutePath()
                                                    .normalize());
                        });
    }

    /**
     * Файл за пределами дерева — отказ старта: навык обещает текст «этого репозитория», и
     * конфигурация, дотягивающаяся через {@code ..} до чужих файлов, — ошибка развёртывания, а не
     * инструмент, которому можно отказать на вызове.
     */
    @Test
    void aSkillFileOutsideTheProjectTreeIsRefused() {
        assertThatThrownBy(
                        () ->
                                catalog(
                                        List.of(
                                                withSkills(
                                                        new SkillOption(
                                                                "release",
                                                                "t",
                                                                "../secrets/notes.md"))),
                                        legacy(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the project tree");
    }

    @Test
    void aSkillNameThatCouldNotBeCalledIsRefused() {
        assertThatThrownBy(
                        () ->
                                catalog(
                                        List.of(
                                                withSkills(
                                                        new SkillOption("My Skill", "t", "a.md"))),
                                        legacy(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("My Skill");
    }

    @Test
    void duplicateSkillNamesWithinAProjectAreRefused() {
        assertThatThrownBy(
                        () ->
                                catalog(
                                        List.of(
                                                withSkills(
                                                        new SkillOption("release", "t", "a.md"),
                                                        new SkillOption("release", "t", "b.md"))),
                                        legacy(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    /** Без триггера каталог не скажет, когда навык загружать, — пустым он бесполезен. */
    @Test
    void aSkillWithoutATriggerOrFileIsRefused() {
        assertThatThrownBy(
                        () ->
                                catalog(
                                        List.of(
                                                withSkills(
                                                        new SkillOption("release", " ", "a.md"))),
                                        legacy(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trigger");
        assertThatThrownBy(
                        () ->
                                catalog(
                                        List.of(withSkills(new SkillOption("release", "t", ""))),
                                        legacy(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("file");
    }

    @Test
    void anUnknownProjectIsAnErrorRatherThanASilentFallback() {
        ProjectCatalog catalog =
                catalog(
                        List.of(
                                new ProjectOption(
                                        "kb", null, "/srv/kb", false, false, null, null, null,
                                        true)),
                        legacy(null));

        assertThat(catalog.find("billing")).isEmpty();
        assertThatThrownBy(() -> catalog.require("billing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("billing");
    }
}
