package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.support.TestProjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the symlink barrier of {@link GitService}. Confining a path with {@code normalize()} plus a
 * prefix check is purely textual — a tracked symlink out of the working tree passes it and the read
 * lands on whatever it points at. That matters most for {@code runScript}, whose scripts walk the
 * tree in a loop and would find such a link where a human-driven tool call never would.
 */
class GitServiceSymlinkTest {

    @TempDir Path repoDir;

    /** Stands in for "anywhere on the host filesystem that is not the repository". */
    @TempDir Path outsideDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        write(outsideDir.resolve("secret.txt"), "PRIVATE KEY");
        service = TestProjects.gitService(repoDir, true);
    }

    @Test
    void refusesToReadThroughATrackedSymlinkOutOfTheRepository() {
        symlink(repoDir.resolve("secret.txt"), outsideDir.resolve("secret.txt"));
        commitAll();

        assertThatThrownBy(() -> service.getFileContent("secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void refusesToReadThroughASymlinkedDirectory() {
        symlink(repoDir.resolve("linked"), outsideDir);
        commitAll();

        assertThatThrownBy(() -> service.getFileContent("linked/secret.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symlink");
    }

    @Test
    void allowsSymlinksThatStayInsideTheRepository() {
        write(repoDir.resolve("real.txt"), "in-repo content");
        symlink(repoDir.resolve("alias.txt"), repoDir.resolve("real.txt"));
        commitAll();

        assertThat(service.getFileContent("alias.txt").content()).isEqualTo("in-repo content");
    }

    @Test
    void refusesToCreateAFileThroughASymlinkedDirectory() {
        symlink(repoDir.resolve("linked"), outsideDir);
        commitAll();

        assertThatThrownBy(() -> service.createFile("linked/planted.txt", "payload"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symlink");
        assertThat(outsideDir.resolve("planted.txt")).doesNotExist();
    }

    @Test
    void refusesToEditThroughATrackedSymlinkOutOfTheRepository() throws IOException {
        symlink(repoDir.resolve("secret.txt"), outsideDir.resolve("secret.txt"));
        commitAll();

        assertThatThrownBy(() -> service.editFile("secret.txt", "PRIVATE", "PUBLIC", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("symlink");
        assertThat(Files.readString(outsideDir.resolve("secret.txt"))).isEqualTo("PRIVATE KEY");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static void write(Path file, String content) {
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void symlink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
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
