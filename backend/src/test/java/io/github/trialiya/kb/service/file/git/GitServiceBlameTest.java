package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.trialiya.kb.model.git.dto.GitFileBlame;
import io.github.trialiya.kb.support.TestProjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Авторство строк через настоящий {@code git blame}: рабочее дерево против снимка ревизии, и
 * главное — что коммиты из {@code .git-blame-ignore-revs} действительно пропускаются.
 */
class GitServiceBlameTest {

    @TempDir
    Path repoDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        service = TestProjects.gitService(repoDir, false);
    }

    @Test
    void linesAreCreditedToTheCommitThatLastChangedThem() {
        writeFile("f.txt", "one\ntwo\n");
        commitAll("first");
        writeFile("f.txt", "one\ntwo changed\nthree\n");
        commitAll("second");

        GitFileBlame blame = service.getBlame("f.txt");

        assertThat(blame.path()).isEqualTo("f.txt");
        assertThat(blame.commit()).isNull();
        assertThat(blame.lineCount()).isEqualTo(3);
        assertThat(blame.hunks())
                .extracting(GitFileBlame.Hunk::fromLine, GitFileBlame.Hunk::lineCount, GitFileBlame.Hunk::summary)
                .containsExactly(tuple(1, 1, "first"), tuple(2, 2, "second"));
        assertThat(blame.hunks().get(0).hash()).hasSize(40);
        assertThat(blame.hunks().get(0).shortHash()).hasSize(7);
        assertThat(blame.hunks().get(0).author()).isEqualTo("Test");
        assertThat(blame.hunks().get(0).email()).isEqualTo("test@example.com");
        assertThat(blame.hunks().get(0).date()).isNotNull();
    }

    /**
     * Коммит-переформатирование, названный в {@code .git-blame-ignore-revs}, авторства не
     * получает: его строки остаются за тем, кто писал их до него. Без игнор-файла тот же blame
     * отдал бы всё переформатированию — это и проверяется вторым чтением.
     */
    @Test
    void aReformattingCommitNamedInTheIgnoreFileIsSkipped() {
        writeFile("f.txt", "one\ntwo\n");
        commitAll("original");
        writeFile("f.txt", "  one\n  two\n");
        commitAll("reformat");
        String reformat = head();
        writeFile(GitBlameRunner.IGNORE_REVS_FILE, "# массовое переформатирование\n" + reformat + "\n");
        commitAll("ignore reformat");

        GitFileBlame blame = service.getBlame("f.txt");

        assertThat(blame.hunks())
                .extracting(GitFileBlame.Hunk::fromLine, GitFileBlame.Hunk::lineCount, GitFileBlame.Hunk::summary)
                .containsExactly(tuple(1, 2, "original"));

        // Снимок до появления игнор-файла его не знает — там переформатирование видно.
        GitFileBlame before = service.getBlameAt("HEAD~1", "f.txt");
        assertThat(before.commit()).isEqualTo(reformat);
        assertThat(before.hunks()).extracting(GitFileBlame.Hunk::summary).containsExactly("reformat");
    }

    /**
     * После переименования строки из старого коммита несут путь, под которым файл лежал тогда:
     * ссылка на файл в снимке того коммита по нынешнему имени открыла бы «не найдено».
     */
    @Test
    void hunksCarryThePathTheFileHadInTheirCommit() {
        writeFile("old.txt", "a\nb\nc\n");
        commitAll("first");
        runGit("mv", "old.txt", "new.txt");
        writeFile("new.txt", "a\nX\nc\n");
        commitAll("renamed and changed");

        GitFileBlame blame = service.getBlame("new.txt");

        assertThat(blame.hunks())
                .extracting(GitFileBlame.Hunk::summary, GitFileBlame.Hunk::path)
                .containsExactly(
                        tuple("first", "old.txt"), tuple("renamed and changed", "new.txt"), tuple("first", "old.txt"));
    }

    /** Неглубокий клон не знает коммита из игнор-файла — git на такой {@code --ignore-rev} отказывает целиком. */
    @Test
    void anIgnoredRevisionTheRepositoryDoesNotHoldIsLeftOut() {
        writeFile("f.txt", "one\n");
        commitAll("first");
        writeFile(GitBlameRunner.IGNORE_REVS_FILE, "0123456789abcdef0123456789abcdef01234567\n");

        assertThat(service.getBlame("f.txt").hunks())
                .extracting(GitFileBlame.Hunk::summary)
                .containsExactly("first");
    }

    /** Хеш дерева или блоба в игнор-файле git тоже не принимает — пропускается, как отсутствующий. */
    @Test
    void anIgnoredHashThatIsNotACommitIsLeftOut() {
        writeFile("f.txt", "one\n");
        commitAll("first");
        String tree = runGit("rev-parse", "HEAD^{tree}").strip();
        String blob = runGit("rev-parse", "HEAD:f.txt").strip();
        writeFile(GitBlameRunner.IGNORE_REVS_FILE, tree + "\n" + blob + "\n");

        assertThat(service.getBlame("f.txt").hunks())
                .extracting(GitFileBlame.Hunk::summary)
                .containsExactly("first");
    }

    /**
     * Путь с пробелами, кириллицей и символами оболочки уезжает одним аргументом после {@code --}
     * и возвращается в {@code filename} как есть; голый {@code \r} внутри строки не делит её на две
     * — иначе хвост без табуляции читался бы как поле коммита, а строк стало бы больше, чем в
     * файле.
     */
    @Test
    void aPathWithSpecialCharactersAndABareCarriageReturnSurviveTheRoundTrip() {
        String path = "тест dir/a b#'$c(1).txt";
        writeFile(path, "one\rtwo\nthree\n");
        commitAll("first");

        GitFileBlame blame = service.getBlame(path);

        assertThat(blame.lineCount()).isEqualTo(2);
        assertThat(blame.hunks()).singleElement().satisfies(h -> {
            assertThat(h.lineCount()).isEqualTo(2);
            assertThat(h.path()).isEqualTo(path);
            assertThat(h.author()).isEqualTo("Test");
        });
    }

    /** Файл в индексе репозитория без единого коммита: истории нет — это ошибка запроса. */
    @Test
    void aStagedFileOnAnUnbornBranchIsTheCallersMistake() {
        writeFile("f.txt", "one\n");
        runGit("add", "-A");

        assertThatThrownBy(() -> service.getBlame("f.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no commits");
    }

    /** Строки, которых ещё нет в истории, приходят ханком без коммита. */
    @Test
    void uncommittedLinesHaveNoCommit() {
        writeFile("f.txt", "one\n");
        commitAll("first");
        writeFile("f.txt", "one\nnew\n");

        GitFileBlame blame = service.getBlame("f.txt");

        assertThat(blame.hunks())
                .extracting(GitFileBlame.Hunk::fromLine, GitFileBlame.Hunk::hash)
                .containsExactly(tuple(1, blame.hunks().get(0).hash()), tuple(2, null));
        assertThat(blame.hunks().get(0).hash()).isNotNull();
        assertThat(blame.hunks().get(1).author()).isNull();
    }

    @Test
    void blameAtARevisionReadsThatCommitNotTheWorkingTree() {
        writeFile("f.txt", "one\n");
        commitAll("first");
        writeFile("f.txt", "one\ntwo\n");
        commitAll("second");
        writeFile("f.txt", "one\ntwo\nthree\n");

        GitFileBlame atFirst = service.getBlameAt("HEAD~1", "f.txt");

        assertThat(atFirst.commit()).hasSize(40);
        assertThat(atFirst.lineCount()).isEqualTo(1);
        assertThat(atFirst.hunks()).extracting(GitFileBlame.Hunk::summary).containsExactly("first");
    }

    @Test
    void anUntrackedFileIsRefusedLikeAMissingOne() {
        writeFile("f.txt", "one\n");
        commitAll("first");
        writeFile("new.txt", "no history\n");

        assertThatThrownBy(() -> service.getBlame("new.txt")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.getBlame("gone.txt")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBinaryFileIsRefused() throws IOException {
        Files.write(repoDir.resolve("blob.bin"), new byte[] {1, 0, 2, 0});
        commitAll("binary");

        assertThatThrownBy(() -> service.getBlame("blob.bin"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Binary");
        assertThatThrownBy(() -> service.getBlameAt("HEAD", "blob.bin")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownRevisionIsTheCallersMistake() {
        writeFile("f.txt", "one\n");
        commitAll("first");

        assertThatThrownBy(() -> service.getBlameAt("nosuch", "f.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Commit not found");
    }

    /** Мусор в игнор-файле не валит blame: git получает только то, что похоже на хеш. */
    @Test
    void junkInTheIgnoreFileDoesNotBreakTheRun() {
        writeFile("f.txt", "one\n");
        commitAll("first");
        writeFile(GitBlameRunner.IGNORE_REVS_FILE, "not a hash\n\n# comment only\n");

        assertThat(service.getBlame("f.txt").hunks()).hasSize(1);
    }

    /** Дедлайн, истёкший до запуска, — отказ по таймауту, а не пустая колонка. */
    @Test
    void aBlameWhoseBudgetIsSpentIsRefusedAsTimedOut() throws IOException {
        writeFile("f.txt", "one\n");
        commitAll("first");
        RepoPaths paths = new RepoPaths(repoDir);
        try (Repository repository =
                new FileRepositoryBuilder().setWorkTree(repoDir.toFile()).build()) {
            GitBlameRunner runner = new GitBlameRunner(
                    paths, repository, new VisibleFiles(service.project(), paths, repository), Duration.ZERO);

            assertThatThrownBy(() -> runner.blame("f.txt")).isInstanceOf(GitReadTimeoutException.class);
        }
    }

    private String head() {
        return runGit("rev-parse", "HEAD").strip();
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
            List<String> command = new java.util.ArrayList<>(List.of("git"));
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
