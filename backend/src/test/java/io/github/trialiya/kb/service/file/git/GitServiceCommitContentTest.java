package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.model.git.dto.GitFileContent;
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
 * Чтение файла из дерева коммита ({@code getFileContentAt}) — то, чем оно отличается от чтения с
 * диска: отвечает история, а не рабочее дерево.
 */
class GitServiceCommitContentTest {

    @TempDir Path repoDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        runGit("init", "-q", "-b", "main");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        write("src/App.java", "class App {\n    void one() {}\n}\n");
        commitAll("first");
        service = TestProjects.gitService(repoDir, false);
    }

    /** Правка на диске в ответ не просачивается: у коммита своё содержимое. */
    @Test
    void aCommitAnswersWithTheFileAsItWasThenNotAsItIsNow() {
        String first = head();
        write("src/App.java", "class App {\n    void two() {}\n}\n");
        commitAll("second");
        write("src/App.java", "uncommitted\n");

        assertThat(service.getFileContentAt(first, "src/App.java", null, null))
                .satisfies(
                        c -> {
                            assertThat(c.content())
                                    .isEqualTo("class App {\n    void one() {}\n}\n");
                            assertThat(c.commit()).isEqualTo(first);
                            assertThat(c.path()).isEqualTo("src/App.java");
                            assertThat(c.language()).isEqualTo("java");
                            assertThat(c.lineCount()).isEqualTo(4);
                            assertThat(c.truncated()).isFalse();
                        });

        // А чтение с диска отвечает тем, что там лежит сейчас, и коммита у него нет.
        assertThat(service.getFileContent("src/App.java"))
                .satisfies(
                        c -> {
                            assertThat(c.content()).isEqualTo("uncommitted\n");
                            assertThat(c.commit()).isNull();
                        });
    }

    /**
     * Ради этого инструмент и заведён: файла на диске нет вовсе, а история его помнит — обычный
     * getFileContent здесь отказывает.
     */
    @Test
    void aFileDeletedSinceThatCommitStillReads() {
        String withFile = head();
        runGit("rm", "-q", "src/App.java");
        commitAll("drop it");

        assertThat(service.getFileContentAt(withFile, "src/App.java", null, null).content())
                .isEqualTo("class App {\n    void one() {}\n}\n");
        assertThatThrownBy(() -> service.getFileContent("src/App.java"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Коммит называют как угодно — отвечает всегда полный хеш, иначе завтра ответ неповторим. */
    @Test
    void anyRevisionSpellingIsAnsweredWithTheFullHash() {
        String first = head();
        write("src/App.java", "class App {\n    void two() {}\n}\n");
        commitAll("second");
        runGit("tag", "v1", first);

        for (String rev : List.of(first.substring(0, 7), "HEAD~1", "v1", "  " + first + "  ")) {
            assertThat(service.getFileContentAt(rev, "src/App.java", null, null))
                    .satisfies(
                            c -> {
                                assertThat(c.commit()).isEqualTo(first);
                                assertThat(c.content()).contains("void one()");
                            });
        }
    }

    /** Диапазон строк считается от начала файла в коммите — так же, как при чтении с диска. */
    @Test
    void aLineRangeIsServedFromTheCommittedFile() {
        GitFileContent content = service.getFileContentAt(head(), "src/App.java", 2, 2);

        assertThat(content.content()).isEqualTo("    void one() {}");
        assertThat(content.fromLine()).isEqualTo(2);
        assertThat(content.toLine()).isEqualTo(2);
        assertThat(content.truncated()).isTrue();
        // Счётчик строк — про файл целиком, а не про отданный кусок.
        assertThat(content.lineCount()).isEqualTo(4);
    }

    /** Путь пишется так, как он назывался в том коммите: переименование историю не переписывает. */
    @Test
    void aPathThatTheCommitDoesNotHoldIsRefusedByName() {
        String before = head();
        runGit("mv", "src/App.java", "src/Renamed.java");
        commitAll("rename");

        assertThat(service.getFileContentAt(before, "src/App.java", null, null).content())
                .contains("void one()");
        assertThatThrownBy(() -> service.getFileContentAt(before, "src/Renamed.java", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File not found in " + before);
    }

    /** Каталог — не файл: содержимого, которое имело бы смысл показать, у него нет. */
    @Test
    void aDirectoryIsRefusedRatherThanServedAsAFile() {
        assertThatThrownBy(() -> service.getFileContentAt(head(), "src", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a file in");
    }

    @Test
    void anUnknownRevisionIsRefused() {
        assertThatThrownBy(
                        () -> service.getFileContentAt("no-such-rev", "src/App.java", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Commit not found");
    }

    /** Путь наружу отвергается до всякого обращения к истории — как и в остальных чтениях. */
    @Test
    void aPathOutsideTheRepositoryIsRefused() {
        assertThatThrownBy(() -> service.getFileContentAt(head(), "../outside.txt", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Двоичный файл в истории отвечает так же, как на диске: метаданные без содержимого. */
    @Test
    void aBinaryFileIsFlaggedWithoutContent() {
        writeBytes("logo.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 0, 0, 1});
        commitAll("add binary");

        assertThat(service.getFileContentAt(head(), "logo.png", null, null))
                .satisfies(
                        c -> {
                            assertThat(c.binary()).isTrue();
                            assertThat(c.content()).isNull();
                            assertThat(c.sizeBytes()).isEqualTo(7);
                        });
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String head() {
        return service.getCommitLog(1, null, false).getFirst().hash();
    }

    private void write(String relativePath, String content) {
        writeBytes(relativePath, content.getBytes(StandardCharsets.UTF_8));
    }

    private void writeBytes(String relativePath, byte[] content) {
        try {
            Path file = repoDir.resolve(relativePath);
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.write(file, content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void commitAll(String message) {
        runGit("add", "-A");
        runGit("commit", "-q", "-m", message);
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
            if (process.waitFor() != 0) {
                throw new IllegalStateException(
                        "git " + String.join(" ", args) + " failed: " + output);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
