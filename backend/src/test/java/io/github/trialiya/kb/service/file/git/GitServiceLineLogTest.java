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

    /**
     * Конец за краем файла подтягивается, как у blame; начало за краем — ошибка с длиной файла;
     * конец раньше начала — ошибка вызова, а не пустой диапазон.
     */
    @Test
    void theRangeIsFittedToTheFile() {
        assertThat(service.getLineLog("g.txt", 6, 100, 20, false).commits())
                .extracting(GitCommit::message)
                .containsExactly("changed below", "written");
        assertThatThrownBy(() -> service.getLineLog("g.txt", 7, null, 20, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("it has 6 lines");
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

    /**
     * Номера — как у рабочего дерева, которое модель прочла: незакоммиченная вставка выше сдвигает
     * их, и без перевода к HEAD git ушёл бы к чужим строкам.
     */
    @Test
    void theRangeIsNumberedAsTheWorkingTreeHasIt() {
        writeFile("g.txt", "one\ntwo\ntop\na\nvoid m() {\n  two();\n}\nz changed\n");

        assertThat(service.getLineLog("g.txt", 5, 7, 20, false).commits())
                .extracting(GitCommit::message)
                .containsExactly("changed inside", "written");
    }

    /**
     * Застейдженная правка выше и файл в конфликте слияния — тоже «изменён»: модель читала рабочую
     * копию, и номера переводятся к HEAD.
     */
    @Test
    void stagedEditsAndConflictsAreNumberedAsTheWorkingTreeHasThem() {
        writeFile("g.txt", "one\ntwo\ntop\na\nvoid m() {\n  two();\n}\nz changed\n");
        runGit("add", "g.txt");

        assertThat(service.getLineLog("g.txt", 5, 7, 20, false).commits())
                .extracting(GitCommit::message)
                .containsExactly("changed inside", "written");

        runGit("reset", "-q", "--hard");
        runGit("checkout", "-q", "-b", "side", "HEAD~1");
        writeFile("g.txt", "side\ntop\na\nvoid m() {\n  two();\n}\nz changed\n");
        commitAll("side edit");
        runGit("checkout", "-q", "-");
        writeFile("g.txt", "main\ntop\na\nvoid m() {\n  two();\n}\nz changed\n");
        commitAll("main edit");
        try {
            runGit("merge", "-q", "side");
        } catch (IllegalStateException conflict) {
            // Ожидаемо: обе ветки правили первую строку.
        }

        // Маркеры конфликта сдвинули метод на строки 8–10.
        assertThat(service.getLineLog("g.txt", 8, 10, 20, false).commits())
                .extracting(GitCommit::message)
                .containsExactly("changed inside", "written");
    }

    /**
     * Символьная ссылка — не файл со строками: в HEAD она лежит ссылкой, и чтение файла из HEAD
     * отказывает ещё до git — а не отдаёт историю строк цели.
     */
    @Test
    void aSymbolicLinkHasNoLineHistory() throws IOException {
        Files.createSymbolicLink(repoDir.resolve("link.txt"), Path.of("g.txt"));
        commitAll("link");

        assertThatThrownBy(() -> service.getLineLog("link.txt", 1, 1, 20, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File not found")
                .hasMessageContaining("link.txt");
    }

    /** Строки, которых нет ни в одном коммите, истории не имеют — это ошибка, а не пустой ответ. */
    @Test
    void uncommittedAdditionsHaveNoHistory() {
        writeFile("g.txt", "new 1\nnew 2\ntop\na\nvoid m() {\n  two();\n}\nz changed\n");

        assertThatThrownBy(() -> service.getLineLog("g.txt", 1, 2, 20, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("uncommitted additions");
    }

    /**
     * Обход, упёршийся в границу неглубокого клона, помечен {@code truncated}: строки могут быть
     * старше клона, и самый старый коммит в списке — его граница, а не их начало.
     */
    @Test
    void aWalkEndingOnAShallowClonesCutIsTruncated(@TempDir Path cloneDir) {
        runGit(
                "clone",
                "-q",
                "--depth",
                "2",
                repoDir.toUri().toString(),
                cloneDir.resolve("shallow").toString());
        GitService shallow = TestProjects.gitService(cloneDir.resolve("shallow"), false);

        GitCommitSearchResult log = shallow.getLineLog("g.txt", 3, 5, 20, false);

        assertThat(log.commits()).extracting(GitCommit::message).containsExactly("renamed");
        assertThat(log.truncated()).isTrue();
    }

    @Test
    void theCommandKeepsThePathInsideTheRangeArgument() {
        assertThat(GitLineLog.args("-x.txt", new GitBlame.Lines(3, 7), 21, "deadbee"))
                .containsExactly(
                        "git",
                        "log",
                        "-L",
                        "3,7:-x.txt",
                        "--no-patch",
                        "--no-show-signature",
                        "--format=%H",
                        "-n",
                        "21",
                        "deadbee");
    }

    /** Только хеши; строка подписи или патча — отказ, а не молча короткий ответ. */
    @Test
    void anythingButHashesInTheOutputIsRefused() {
        String hash = "a".repeat(40);

        assertThat(GitLineLog.hashes(List.of(hash, "", " " + hash + " "))).containsExactly(hash, hash);
        assertThatThrownBy(() -> GitLineLog.hashes(List.of(hash, "No signature")))
                .isInstanceOf(IllegalStateException.class);
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
