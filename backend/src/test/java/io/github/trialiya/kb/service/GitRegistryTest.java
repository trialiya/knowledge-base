package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.support.TestProjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The routing every caller depends on while nothing selects a project yet: an unnamed project is
 * the default one, an unknown one is refused, and "may the model write here" is answered in one
 * place for the edit tools and for scripts alike.
 */
class GitRegistryTest {

    @TempDir Path repoDir;

    @BeforeEach
    void initRepo() {
        runGit("init", "-q");
    }

    @Test
    void namingNoProjectGivesTheDefaultRepository() {
        GitRegistry registry = TestProjects.registry(repoDir, false);

        assertThat(registry.forProject(null)).isSameAs(registry.defaultProject());
        assertThat(registry.forProject(TestProjects.ID)).isSameAs(registry.defaultProject());
        assertThat(registry.defaultProject().project().path())
                .isEqualTo(repoDir.toAbsolutePath().normalize());
    }

    @Test
    void namingNoProjectAndNamingTheDefaultOneAreTheSameProject() {
        GitRegistry registry = TestProjects.registry(repoDir, false);

        // What tells "the model named another repository" from "it named this one": a chat that
        // stored no project still runs on the default one, so the raw values differ but the
        // project does not.
        assertThat(registry.sameProject(null, TestProjects.ID)).isTrue();
        assertThat(registry.sameProject(TestProjects.ID, null)).isTrue();
        assertThat(registry.sameProject(null, null)).isTrue();
    }

    @Test
    void anUnknownProjectIsRefused() {
        GitRegistry registry = TestProjects.registry(repoDir, false);

        assertThatThrownBy(() -> registry.forProject("billing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("billing");
    }

    @Test
    void aReadOnlyProjectOffersNoWrites() {
        GitRegistry registry = TestProjects.registry(repoDir, false);

        assertThat(registry.editsAllowed(null)).isFalse();
        assertThat(registry.anyEditable()).isFalse();
        assertThatThrownBy(() -> registry.requireEditable(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(TestProjects.ID);
    }

    @Test
    void aWritableProjectWithEditsOnAcceptsThem() {
        GitRegistry registry = TestProjects.registry(repoDir, true);

        assertThat(registry.editsAllowed(null)).isTrue();
        assertThat(registry.anyEditable()).isTrue();
        assertThat(registry.requireEditable(null)).isSameAs(registry.defaultProject());
    }

    private void runGit(String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);
            Process process =
                    new ProcessBuilder(command)
                            .directory(repoDir.toFile())
                            .redirectErrorStream(true)
                            .start();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
