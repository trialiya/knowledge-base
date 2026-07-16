package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.model.git.dto.GitEditResult;
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
 * Unit tests for the working-tree write operations of {@link GitService} ({@link
 * GitService#createFile} / {@link GitService#editFile}) against a real, throwaway git repository.
 */
class GitServiceEditTest {

    @TempDir Path repoDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        service = new GitService(repoDir.toString(), new OutlineService());
    }

    // ── createFile ───────────────────────────────────────────────────────────

    @Test
    void createFileWritesStagesAndReportsLineCount() {
        GitEditResult result = service.createFile("src/New.java", "line1\nline2\nline3");

        assertThat(result.operation()).isEqualTo("create");
        assertThat(result.path()).isEqualTo("src/New.java");
        assertThat(result.additions()).isEqualTo(3);
        assertThat(result.deletions()).isZero();
        assertThat(result.lineCount()).isEqualTo(3);
        assertThat(result.diff()).isNull();
        assertThat(repoDir.resolve("src/New.java")).hasContent("line1\nline2\nline3");
        // Staged ⇒ tracked ⇒ immediately readable by the read tools.
        assertThat(service.getFileContent("src/New.java").content())
                .isEqualTo("line1\nline2\nline3");
    }

    @Test
    void createFileRejectsExistingFile() {
        writeFile("existing.txt", "x");
        commitAll();

        assertThatThrownBy(() -> service.createFile("existing.txt", "y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void createFileRejectsIgnoredPathAndRollsBack() {
        writeFile(".gitignore", "build/\n");
        commitAll();

        assertThatThrownBy(() -> service.createFile("build/out.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ignored");
        assertThat(repoDir.resolve("build/out.txt")).doesNotExist();
    }

    @Test
    void createFileRestoresTrackedFileDeletedFromWorkingTree() throws IOException {
        writeFile("restore.txt", "old");
        commitAll();
        Files.delete(repoDir.resolve("restore.txt"));

        GitEditResult result = service.createFile("restore.txt", "recreated");

        assertThat(result.operation()).isEqualTo("create");
        assertThat(repoDir.resolve("restore.txt")).hasContent("recreated");
        assertThat(service.getFileContent("restore.txt").content()).isEqualTo("recreated");
    }

    @Test
    void createFileRejectsUnsafePaths() {
        assertThatThrownBy(() -> service.createFile("../outside.txt", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createFile(".git/hooks/pre-commit", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(".git");
        assertThatThrownBy(() -> service.createFile("some/dir/.DS_Store", "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── editFile ─────────────────────────────────────────────────────────────

    @Test
    void editFileReplacesUniqueOccurrenceAndReturnsDiff() {
        writeFile("app.txt", "alpha\nbeta\ngamma\n");
        commitAll();

        GitEditResult result = service.editFile("app.txt", "beta", "BETA", false);

        assertThat(result.operation()).isEqualTo("edit");
        assertThat(result.additions()).isEqualTo(1);
        assertThat(result.deletions()).isEqualTo(1);
        assertThat(result.diff()).contains("-beta").contains("+BETA");
        assertThat(repoDir.resolve("app.txt")).hasContent("alpha\nBETA\ngamma\n");
    }

    @Test
    void editFileRejectsMissingOldString() {
        writeFile("app.txt", "alpha\n");
        commitAll();

        assertThatThrownBy(() -> service.editFile("app.txt", "nope", "x", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void editFileRejectsAmbiguousMatchWithoutReplaceAll() {
        writeFile("app.txt", "dup\nmiddle\ndup\n");
        commitAll();

        assertThatThrownBy(() -> service.editFile("app.txt", "dup", "x", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2 times");
    }

    @Test
    void editFileReplaceAllReplacesEveryOccurrence() {
        writeFile("app.txt", "dup\nmiddle\ndup\n");
        commitAll();

        GitEditResult result = service.editFile("app.txt", "dup", "one", true);

        assertThat(repoDir.resolve("app.txt")).hasContent("one\nmiddle\none\n");
        assertThat(result.additions()).isEqualTo(2);
        assertThat(result.deletions()).isEqualTo(2);
    }

    @Test
    void editFilePreservesCrlfLineEndings() throws IOException {
        Files.write(repoDir.resolve("win.txt"), "one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8));
        commitAll();

        // The model matches against the LF-normalised view (as served by getFileContent).
        service.editFile("win.txt", "two", "TWO", false);

        assertThat(Files.readAllBytes(repoDir.resolve("win.txt")))
                .isEqualTo("one\r\nTWO\r\n".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void editFileRejectsUntrackedFile() {
        writeFile("untracked.txt", "x");

        assertThatThrownBy(() -> service.editFile("untracked.txt", "x", "y", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void editFileRejectsBinaryFile() throws IOException {
        Files.write(repoDir.resolve("bin.dat"), new byte[] {1, 0, 2, 0});
        commitAll();

        assertThatThrownBy(() -> service.editFile("bin.dat", "a", "b", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binary");
    }

    @Test
    void editFileRejectsIdenticalStrings() {
        assertThatThrownBy(() -> service.editFile("app.txt", "same", "same", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("identical");
    }

    @Test
    void editedFileShowsUpInUncommittedChanges() {
        writeFile("app.txt", "alpha\n");
        commitAll();

        service.editFile("app.txt", "alpha", "omega", false);

        assertThat(service.getUncommittedChanges(false))
                .anySatisfy(
                        entry -> {
                            assertThat(entry.path()).isEqualTo("app.txt");
                            assertThat(entry.status()).isEqualTo("M");
                        });
    }

    @Test
    void isRepoWritableIsTrueForTempRepo() {
        assertThat(service.isRepoWritable()).isTrue();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

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

    private void commitAll() {
        runGit("add", "-A");
        runGit("commit", "-q", "-m", "test commit");
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
}
