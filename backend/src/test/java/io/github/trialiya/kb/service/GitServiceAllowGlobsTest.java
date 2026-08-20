package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import io.github.trialiya.kb.support.TestProjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The per-project {@code allow-globs}: the one hole in the tracked-files rule, cut exactly where
 * the configuration says — untracked files matching the globs are served and editable, everything
 * else untracked stays invisible, and {@code .gitignore} stays authoritative over the globs.
 */
class GitServiceAllowGlobsTest {

    @TempDir Path repoDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        writeFile("src/App.java", "class App {}\n");
        writeFile(".gitignore", "notes/private/\n");
        runGit("add", "-A");
        runGit("commit", "-q", "-m", "init");

        // The untracked working area the globs admit, one file outside it, one ignored inside it.
        writeFile("notes/todo.md", "remember the milk\n");
        writeFile("scratch.txt", "not admitted\n");
        writeFile("notes/private/secret.md", "hidden by .gitignore\n");

        service = TestProjects.gitService(repoDir, true, List.of("notes/**"));
    }

    @Test
    void servesAnUntrackedFileTheGlobsAdmit() {
        assertThat(service.getFileContent("notes/todo.md").content())
                .isEqualTo("remember the milk\n");
        assertThat(service.listTrackedFiles()).contains("src/App.java", "notes/todo.md");
        assertThat(service.getFileTree("notes"))
                .extracting(GitFileNode::path)
                .contains("notes/todo.md");
    }

    @Test
    void anUntrackedFileOutsideTheGlobsStaysInvisible() {
        assertThatThrownBy(() -> service.getFileContent("scratch.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File not found");
        assertThat(service.listTrackedFiles()).doesNotContain("scratch.txt");
    }

    @Test
    void gitignoreStillWinsOverTheGlobs() {
        assertThatThrownBy(() -> service.getFileContent("notes/private/secret.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File not found");
        assertThat(service.listTrackedFiles()).doesNotContain("notes/private/secret.md");
    }

    @Test
    void editingAnAdmittedFileWorksAndLeavesItUntracked() {
        service.editFile("notes/todo.md", "milk", "bread", false);

        assertThat(readFile("notes/todo.md")).isEqualTo("remember the bread\n");
        assertThat(runGitOutput("status", "--porcelain", "-uall")).contains("?? notes/todo.md");
    }

    @Test
    void creatingInsideTheGlobsLeavesTheFileUntracked() {
        service.createFile("notes/new.md", "fresh\n");

        assertThat(readFile("notes/new.md")).isEqualTo("fresh\n");
        assertThat(service.getFileContent("notes/new.md").content()).isEqualTo("fresh\n");
        assertThat(runGitOutput("status", "--porcelain", "-uall")).contains("?? notes/new.md");
    }

    @Test
    void creatingAGitignoredPathIsRefusedEvenInsideTheGlobs() {
        assertThatThrownBy(() -> service.createFile("notes/private/more.md", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".gitignore");
        assertThat(repoDir.resolve("notes/private/more.md")).doesNotExist();
    }

    @Test
    void grepSearchesAdmittedUntrackedFilesButNotTheRest() {
        List<GitGrepMatch> hits = service.grepContent("milk", null, false, 0, 50);
        assertThat(hits).extracting(GitGrepMatch::path).containsExactly("notes/todo.md");

        assertThat(service.grepContent("not admitted", null, false, 0, 50)).isEmpty();
    }

    @Test
    void withoutGlobsUntrackedFilesStayInvisible() {
        GitService plain = TestProjects.gitService(repoDir, true);

        assertThatThrownBy(() -> plain.getFileContent("notes/todo.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File not found");
        assertThat(plain.listTrackedFiles()).doesNotContain("notes/todo.md");
    }

    private void writeFile(String relativePath, String content) {
        try {
            Path file = repoDir.resolve(relativePath);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String readFile(String relativePath) {
        try {
            return Files.readString(repoDir.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void runGit(String... args) {
        runGitOutput(args);
    }

    private String runGitOutput(String... args) {
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
            return output;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
