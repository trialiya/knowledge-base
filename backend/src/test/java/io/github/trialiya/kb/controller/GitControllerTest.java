package io.github.trialiya.kb.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.service.OutlineService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;

/**
 * Unit tests for {@code GET /api/git/tree} against a real, throwaway git repository — sorting, root
 * defaulting, and rejection of unsafe paths ({@code ..}, a leading {@code -}, a leading {@code /}).
 */
class GitControllerTest {

    @TempDir Path repoDir;

    private GitController controller;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");

        writeFile("banana.txt", "b");
        writeFile("Apple.txt", "a");
        writeFile("zebra/x.txt", "z");
        writeFile("Bird/x.txt", "b");
        runGit("add", "-A");
        runGit("commit", "-q", "-m", "test commit");

        GitService gitService = new GitService(repoDir.toString(), new OutlineService());
        controller = new GitController(gitService);
    }

    private void writeFile(String relativePath, String content) {
        try {
            Path file = repoDir.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void runGit(String... args) {
        try {
            var command = new java.util.ArrayList<String>();
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

    @Test
    void rootDefaultsWhenPathIsNullOrBlank() {
        List<GitFileNode> viaNull = controller.getTree(null);
        List<GitFileNode> viaBlank = controller.getTree("  ");

        assertThat(viaNull.stream().map(GitFileNode::name).toList())
                .containsExactly("Bird", "zebra", "Apple.txt", "banana.txt");
        assertThat(viaBlank.stream().map(GitFileNode::name).toList())
                .containsExactly("Bird", "zebra", "Apple.txt", "banana.txt");
    }

    @Test
    void sortsDirectoriesBeforeFilesThenAlphabeticallyIgnoringCase() {
        List<GitFileNode> result = controller.getTree(null);

        assertThat(result.stream().map(GitFileNode::name).toList())
                .containsExactly("Bird", "zebra", "Apple.txt", "banana.txt");
    }

    @Test
    void rejectsParentDirectoryTraversal() {
        assertThatThrownBy(() -> controller.getTree("../etc"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void rejectsLeadingDash() {
        assertThatThrownBy(() -> controller.getTree("-not-an-option"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void rejectsLeadingSlash() {
        assertThatThrownBy(() -> controller.getTree("/etc/passwd"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }
}
