package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitCommitSearchResult;
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
 * История диапазона строк через настоящий {@code git log -L}: в ответе коммиты, менявшие сами эти
 * строки, — через сдвиг вставкой выше и через переименование файла, — и только они.
 */
class GitServiceLineLogTest {

    @TempDir
    Path repoDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        service = TestProjects.gitService(repoDir, false);
        writeFile("f.txt", "a\nvoid m() {\n  one();\n}\nz\n");
        commitAll("written");
        writeFile("f.txt", "a\nvoid m() {\n  two();\n}\nz\n");
        commitAll("changed inside");
        writeFile("f.txt", "a\nvoid m() {\n  two();\n}\nz changed\n");
        commitAll("changed below");
        runGit("mv", "f.txt", "g.txt");
        commitAll("renamed");
        writeFile("g.txt", "top\na\nvoid m() {\n  two();\n}\nz changed\n");
        commitAll("inserted above");
    }

    @Test
    void theRangeListsOnlyTheCommitsThatChangedItsLines() {
        GitCommitSearchResult log = service.getLineLog("g.txt", 3, 5, 20, false);

        assertThat(log.commits()).extracting(GitCommit::message).containsExactly("changed inside", "written");
        assertThat(log.truncated()).isFalse();
        assertThat(log.commits().getFirst().hash()).hasSize(40);
    }

    /** На один коммит больше, чем просили, — так {@code truncated} знает, что история идёт дальше. */
    @Test
    void maxCountCutsTheListAndSaysSo() {
        GitCommitSearchResult log = service.getLineLog("g.txt", 3, 5, 1, true);

        assertThat(log.commits()).extracting(GitCommit::message).containsExactly("changed inside");
        assertThat(log.truncated()).isTrue();
    }

    /** Конец за краем файла подтягивается, как у blame; начало за краем — ошибка с длиной файла. */
    @Test
    void theRangeIsFittedToTheFileAtHead() {
        assertThat(service.getLineLog("g.txt", 6, 100, 20, false).commits())
                .extracting(GitCommit::message)
                .containsExactly("changed below", "written");
        assertThatThrownBy(() -> service.getLineLog("g.txt", 7, null, 20, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("6 lines at HEAD");
        assertThatThrownBy(() -> service.getLineLog("g.txt", 4, 2, 20, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("before fromLine");
    }

    /** Файла нет в HEAD — у его строк нет и истории. */
    @Test
    void aPathMissingAtHeadIsRefused() {
        assertThatThrownBy(() -> service.getLineLog("f.txt", 1, 2, 20, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theCommandKeepsThePathInsideTheRangeArgument() {
        assertThat(GitLineLog.args("-x.txt", new GitBlame.Lines(3, 7), 21, "deadbee"))
                .containsExactly("git", "log", "-L", "3,7:-x.txt", "--no-patch", "--format=%H", "-n", "21", "deadbee");
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

    private void commitAll(String message) {
        runGit("add", "-A");
        runGit("commit", "-q", "-m", message);
    }

    private String runGit(String... args) {
        try {
            List<String> command = new ArrayList<>(List.of("git"));
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command)
                    .directory(repoDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed (" + exit + "): " + output);
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
