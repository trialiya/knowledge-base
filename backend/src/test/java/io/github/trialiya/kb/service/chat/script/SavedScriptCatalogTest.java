package io.github.trialiya.kb.service.chat.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ProjectProperties;
import io.github.trialiya.kb.config.model.ProjectProperties.ProjectOption;
import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.project.Project;
import io.github.trialiya.kb.model.script.SavedScript;
import io.github.trialiya.kb.service.file.git.GitRegistry;
import io.github.trialiya.kb.service.file.project.ProjectCatalog;
import io.github.trialiya.kb.support.TestProjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The scripts a project declares: where the list comes from, when it is re-read, and which files it
 * is allowed to point at.
 *
 * <p>Both halves of the split matter here. The manifest is named by the <em>deployment</em>, so it
 * is read straight off the disk — and re-checked against a symlink, which the startup's textual
 * check cannot see. The scripts are named by the <em>repository</em>, so they go through {@code
 * GitService} and have to be tracked: nothing in the untracked area went through the review that
 * makes executing a file reasonable.
 */
class SavedScriptCatalogTest {

    private static final String MANIFEST = ".kb/scripts.yaml";

    @TempDir Path repoDir;

    @TempDir Path outsideDir;

    /** The project {@link #catalog} last built — what the prompt section is rendered for. */
    private Project project;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        write(repoDir.resolve("tools/report.js"), "return kb.files('**/*.md').length;\n");
        write(repoDir.resolve("scripts/bump.js"), "return 'bumped';\n");
    }

    @Test
    void listsWhatTheManifestDeclares() {
        manifest(
                """
                scripts:
                  - name: report
                    file: tools/report.js
                    desc: Count the markdown files
                    params:
                      - { name: area, desc: 'A subtree' }
                  - name: bump
                    file: scripts/bump.js
                    desc: Bump the year
                    write: true
                """);
        commitAll();

        assertThat(catalog().scripts(TestProjects.ID))
                .extracting(SavedScript::name)
                .containsExactly("report", "bump");
    }

    /**
     * With the sandbox switched off there is no tool to call, so the block must not list anything:
     * a catalogue naming a tool that no bean registered is paid for on every turn and answers
     * nothing.
     */
    @Test
    void withScriptsDisabledNothingIsAnnounced() {
        manifest("scripts:\n  - { name: report, file: tools/report.js, desc: Count }\n");
        commitAll();
        SavedScriptCatalog catalog =
                catalog(
                        MANIFEST,
                        new ScriptProperties(
                                false, false, null, null, null, null, null, null, null, null));

        assertThat(catalog.anyManifests()).isFalse();
        assertThat(catalog.projectScripts(project)).isEmpty();
    }

    /** No manifest configured is the default state, and it is not an error anywhere. */
    @Test
    void aProjectWithoutAManifestHasNoScripts() {
        commitAll();
        SavedScriptCatalog catalog = catalog((String) null);

        assertThat(catalog.anyManifests()).isFalse();
        assertThat(catalog.scripts(TestProjects.ID)).isEmpty();
        assertThat(catalog.projectScripts(project)).isEmpty();
    }

    /** A branch that does not carry the file is a state of the tree, not a failure. */
    @Test
    void aMissingManifestIsAnEmptyList() {
        commitAll();
        SavedScriptCatalog catalog = catalog();

        assertThat(catalog.anyManifests()).isTrue();
        assertThat(catalog.scripts(TestProjects.ID)).isEmpty();
    }

    /** Read at call time: a pull or a branch switch changes the list with no restart. */
    @Test
    void rereadsTheManifestWhenTheFileMoves() throws IOException {
        manifest("scripts:\n  - { name: report, file: tools/report.js, desc: First }\n");
        commitAll();
        SavedScriptCatalog catalog = catalog();
        assertThat(catalog.scripts(TestProjects.ID))
                .extracting(SavedScript::desc)
                .containsExactly("First");

        manifest(
                """
                scripts:
                  - { name: report, file: tools/report.js, desc: Second }
                  - { name: bump, file: scripts/bump.js, desc: Added }
                """);
        // The stamp is (size, mtime); a rewrite within the same millisecond would otherwise read
        // as the same file — which is what a test on a fast disk hits and a user never does.
        Files.setLastModifiedTime(
                repoDir.resolve(MANIFEST),
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 2000));

        assertThat(catalog.scripts(TestProjects.ID))
                .extracting(SavedScript::name)
                .containsExactly("report", "bump");
    }

    @Test
    void readsTheScriptFromTheWorkingTree() {
        manifest("scripts:\n  - { name: report, file: tools/report.js, desc: Count }\n");
        commitAll();
        SavedScriptCatalog catalog = catalog();

        ScriptSource source =
                catalog.source(
                        TestProjects.ID, catalog.require(TestProjects.ID, "report"), Map.of());

        assertThat(source.text()).contains("kb.files");
        assertThat(source.sourceName()).isEqualTo("tools/report.js");
        assertThat(source.report()).isNotNull();
        assertThat(source.report().name()).isEqualTo("report");
        assertThat(source.report().path()).isEqualTo("tools/report.js");
        assertThat(source.report().sha()).hasSize(12);
    }

    /** The manifest may point anywhere in the tree — but only at something git tracks. */
    @Test
    void refusesAScriptThatIsNotTracked() {
        manifest("scripts:\n  - { name: fresh, file: tools/fresh.js, desc: Uncommitted }\n");
        commitAll();
        write(repoDir.resolve("tools/fresh.js"), "return 1;\n");
        SavedScriptCatalog catalog = catalog();

        assertThatThrownBy(
                        () ->
                                catalog.source(
                                        TestProjects.ID,
                                        catalog.require(TestProjects.ID, "fresh"),
                                        Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fresh");
    }

    @Test
    void anUnknownNameAnswersWithWhatThereIs() {
        manifest("scripts:\n  - { name: report, file: tools/report.js, desc: Count }\n");
        commitAll();

        assertThatThrownBy(() -> catalog().require(TestProjects.ID, "repoort"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("repoort")
                .hasMessageContaining("report")
                .hasMessageContaining("runScript");
    }

    /**
     * The startup check compares strings and cannot see a symlink, which can be put in place long
     * after the deployment started — so where the path really lands is asked again at each read.
     */
    @Test
    void aManifestReachedThroughASymlinkIsNotRead() throws IOException {
        write(
                outsideDir.resolve("scripts.yaml"),
                "scripts:\n  - { name: x, file: a.js, desc: X }\n");
        Files.createDirectories(repoDir.resolve(".kb"));
        try {
            Files.createSymbolicLink(repoDir.resolve(MANIFEST), outsideDir.resolve("scripts.yaml"));
        } catch (UnsupportedOperationException | IOException e) {
            return; // a filesystem without symlinks has nothing to check here
        }
        commitAll();

        assertThat(catalog().scripts(TestProjects.ID)).isEmpty();
    }

    /** The block the model reads: names, one line each, and what may be passed. */
    @Test
    void rendersTheActiveProjectSection() {
        manifest(
                """
                scripts:
                  - name: report
                    file: tools/report.js
                    desc: "Count the markdown\\n  files"
                    params:
                      - { name: area }
                      - { name: since, required: true }
                  - name: bump
                    file: scripts/bump.js
                    desc: Bump the year
                    write: true
                """);
        commitAll();
        SavedScriptCatalog catalog = catalog();

        String section = catalog.projectScripts(project);

        assertThat(section)
                .contains("`report` — Count the markdown files (args: area?, since)")
                .contains("`bump` — Bump the year (no args; writes files)")
                .contains("runSavedScript");
    }

    // ── Fixture ─────────────────────────────────────────────────────────────

    private SavedScriptCatalog catalog() {
        return catalog(MANIFEST, ScriptProperties.enabledWithDefaults());
    }

    private SavedScriptCatalog catalog(@Nullable String manifestPath) {
        return catalog(manifestPath, ScriptProperties.enabledWithDefaults());
    }

    private SavedScriptCatalog catalog(@Nullable String manifestPath, ScriptProperties properties) {
        ProjectOption option =
                new ProjectOption(
                        TestProjects.ID,
                        null,
                        repoDir.toString(),
                        false,
                        false,
                        null,
                        null,
                        manifestPath,
                        null,
                        true);
        ProjectCatalog projects =
                new ProjectCatalog(new ProjectProperties(List.of(option)), new GitProperties(null));
        GitRegistry registry = TestProjects.registry(List.of(option));
        project = projects.defaultProject();
        return new SavedScriptCatalog(projects, registry, properties);
    }

    private void manifest(String yaml) {
        write(repoDir.resolve(MANIFEST), yaml);
    }

    private static void write(Path file, String content) {
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void commitAll() {
        runGit("add", "-A");
        runGit("commit", "-q", "-m", "test commit");
    }

    private void runGit(String... args) {
        try {
            var command = new ArrayList<String>();
            command.add("git");
            command.addAll(List.of(args));
            Process process =
                    new ProcessBuilder(command)
                            .directory(repoDir.toFile())
                            .redirectErrorStream(true)
                            .start();
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException(
                        "git " + String.join(" ", args) + " failed: " + output);
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to run git command", e);
        }
    }
}
