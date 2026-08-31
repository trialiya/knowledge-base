package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.model.git.dto.TextEdit;
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
 * Отмена того, что ассистент написал в рабочее дерево: пересчёт текста обратными заменами (без
 * записи) и удаление созданного файла — против настоящего одноразового репозитория.
 */
class GitServiceRevertTest {

    @TempDir Path repoDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        service = TestProjects.gitService(repoDir, false);
    }

    // ── previewEdited ────────────────────────────────────────────────────────

    /** Правка ассистента и её отмена — один и тот же вызов с переставленными сторонами. */
    @Test
    void anEditIsUndoneByTheSameReplacementTheOtherWayRound() {
        writeFile("src/App.java", "int x = 1;\nint y = 2;\n");
        commitAll();
        service.editFile("src/App.java", "int x = 1;", "int x = 42;", false);

        final String back =
                service.previewEdited(
                        "src/App.java", List.of(new TextEdit("int x = 42;", "int x = 1;", false)));

        assertThat(back).isEqualTo("int x = 1;\nint y = 2;\n");
        // Именно preview: на диске всё ещё правка ассистента, пока её не запишут.
        assertThat(repoDir.resolve("src/App.java")).hasContent("int x = 42;\nint y = 2;\n");
    }

    /** Пересчитанный текст записывается тем же путём, что и правки скрипта, — целиком. */
    @Test
    void writingBackThePreviewRestoresTheFile() {
        writeFile("a.txt", "было\n");
        commitAll();
        service.editFile("a.txt", "было", "стало", false);

        service.replaceTrackedFile(
                "a.txt",
                service.previewEdited("a.txt", List.of(new TextEdit("стало", "было", false))));

        assertThat(repoDir.resolve("a.txt")).hasContent("было\n");
        assertThat(service.getUncommittedChanges(false)).isEmpty();
    }

    /**
     * Проверка целостности, ради которой откат ничего не хранит: файл, изменившийся после ответа,
     * обратной заменой не совпадает — и откат отказывается, а не переписывает чужую работу.
     */
    @Test
    void aFileChangedSinceTheAnswerNoLongerMatches() {
        writeFile("a.txt", "было\n");
        commitAll();
        service.editFile("a.txt", "было", "стало", false);
        writeFile("a.txt", "совсем другое\n");

        assertThatThrownBy(
                        () ->
                                service.previewEdited(
                                        "a.txt", List.of(new TextEdit("стало", "было", false))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("oldString not found");
    }

    // ── deleteFile ───────────────────────────────────────────────────────────

    /** Созданный файл уходит и с диска, и из индекса: в незакоммиченных изменениях следа нет. */
    @Test
    void aCreatedFileIsRemovedFromTheTreeAndTheIndex() {
        writeFile("keep.txt", "x");
        commitAll();
        service.createFile("src/New.java", "class New {}");

        service.deleteFile("src/New.java", "class New {}");

        assertThat(repoDir.resolve("src/New.java")).doesNotExist();
        assertThat(service.getUncommittedChanges(false)).isEmpty();
    }

    /** Закоммиченный файл удалять отказываемся: это уже история репозитория, и убирает её git. */
    @Test
    void aCommittedFileIsNotDeleted() {
        service.createFile("src/New.java", "class New {}");
        commitAll();

        assertThatThrownBy(() -> service.deleteFile("src/New.java", "class New {}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("committed");
        assertThat(repoDir.resolve("src/New.java")).exists();
    }

    /** Отказ проверяется и отдельно — до того, как удалён хоть один файл ответа. */
    @Test
    void deletabilityCanBeAskedWithoutDeleting() {
        service.createFile("src/New.java", "class New {}");

        service.requireDeletable("src/New.java", "class New {}");

        assertThat(repoDir.resolve("src/New.java")).exists();
    }

    /**
     * Файл, созданный ответом и правленный человеком после него, удалению не подлежит: его правки
     * ушли бы вместе с ним. Та же проверка целостности, что даёт правке точное совпадение.
     */
    @Test
    void aCreatedFileEditedSinceIsNotDeleted() {
        service.createFile("src/New.java", "class New {}");
        writeFile("src/New.java", "class New { int mine; }");

        assertThatThrownBy(() -> service.deleteFile("src/New.java", "class New {}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("changed since it was created");
        assertThat(repoDir.resolve("src/New.java")).exists();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void writeFile(String relative, String content) {
        try {
            Path file = repoDir.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void commitAll() {
        runGit("add", "-A");
        runGit("commit", "-q", "-m", "test");
    }

    private void runGit(String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);
            Process process = new ProcessBuilder(command).directory(repoDir.toFile()).start();
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
