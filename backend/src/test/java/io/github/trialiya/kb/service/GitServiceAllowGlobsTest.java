package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
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
 * the configuration says. Inside the globs the working tree is the truth — {@code .gitignore}
 * included — everything else untracked stays invisible, and what the hole admits is readable and
 * editable but never creatable.
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
        writeFile(".gitignore", "notes/generated/\n");
        runGit("add", "-A");
        runGit("commit", "-q", "-m", "init");

        // The untracked working area the globs admit, one file outside it, one gitignored inside.
        writeFile("notes/todo.md", "remember the milk\n");
        writeFile("scratch.txt", "not admitted\n");
        writeFile("notes/generated/report.md", "generated, and gitignored\n");

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

    /**
     * Ради этого всё и делалось: отчёты сборки и логи лежат ровно в игнорируемом каталоге, и без
     * этого прохода маска над ним была бы бесполезна.
     */
    @Test
    void gitignoreDoesNotHideWhatTheGlobsAdmit() {
        assertThat(service.getFileContent("notes/generated/report.md").content())
                .isEqualTo("generated, and gitignored\n");
        assertThat(service.listTrackedFiles()).contains("notes/generated/report.md");
    }

    @Test
    void anUntrackedFileOutsideTheGlobsStaysInvisible() {
        assertThatThrownBy(() -> service.getFileContent("scratch.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File not found");
        assertThat(service.listTrackedFiles()).doesNotContain("scratch.txt");
    }

    /** Служебный каталог git не открывает никакая маска. */
    @Test
    void theGitDirectoryIsNeverAdmitted() {
        GitService wide = TestProjects.gitService(repoDir, true, List.of(".git/**"));

        assertThatThrownBy(() -> wide.getFileContent(".git/config"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File not found");
    }

    /**
     * Обход рабочего дерева укоренён в литеральном префиксе маски, и он же — граница радиуса
     * поражения от опечатки, раз маска перекрывает {@code .gitignore}. Отказ обязан ронять старт, а
     * не гасить проект: {@code GitRegistry} глушит ошибку открытия репозитория логом.
     */
    @Test
    void aGlobWithoutADirectoryRootIsRefusedAtStartup() {
        assertThatThrownBy(() -> TestProjects.gitService(repoDir, true, List.of("**/*.md")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must start with a directory");
    }

    /** Маска без wildcard называет один файл — его «корень» это он сам, а не каталог. */
    @Test
    void aGlobNamingOneFileAdmitsItEverywhereAndNotOnlyByPath() {
        GitService exact = TestProjects.gitService(repoDir, true, List.of("scratch.txt"));

        assertThat(exact.getFileContent("scratch.txt").content()).isEqualTo("not admitted\n");
        assertThat(exact.listTrackedFiles()).contains("scratch.txt");
        assertThat(exact.searchFiles("scratch", 5))
                .extracting(GitFileNode::path)
                .containsExactly("scratch.txt");
    }

    @Test
    void fuzzySearchMarksAnAdmittedFileAsUntracked() {
        assertThat(service.searchFiles("todo", 5))
                .singleElement()
                .extracting(GitFileNode::tracked)
                .isEqualTo(false);
        assertThat(service.searchFiles("App", 5)).allSatisfy(n -> assertThat(n.tracked()).isTrue());
    }

    /** {@code *.java} у git означает имя файла на любой глубине — иначе фильтр съедал бы всё. */
    @Test
    void aPathGlobWithoutASlashMatchesTheFileNameAtAnyDepth() {
        writeFile("notes/Draft.java", "class Draft { String milk; }\n");

        assertThat(service.grepContent("milk", "*.java", false, 0, 50, true))
                .extracting(GitGrepMatch::path)
                .containsExactly("notes/Draft.java");
    }

    /**
     * У git wildcard в pathspec переходит через {@code /} — иначе один и тот же {@code pathGlob}
     * отбирал бы tracked- и untracked-попадания по разным правилам.
     */
    @Test
    void aPathGlobWildcardCrossesDirectoriesForBothRunsAlike() {
        writeFile("src/deep/Tracked.java", "class Tracked { String milk; }\n");
        runGit("add", "src/deep/Tracked.java");
        writeFile("notes/deep/Admitted.java", "class Admitted { String milk; }\n");

        assertThat(service.grepContent("milk", "*/deep/*.java", false, 0, 50, true))
                .extracting(GitGrepMatch::path)
                .containsExactly("notes/deep/Admitted.java", "src/deep/Tracked.java");
    }

    /** Пустой индекс — это «tracked нет ни одного», а не «tracked все». */
    @Test
    void anEmptyIndexStillMarksAdmittedFilesAsUntracked() {
        Path bare = repoDir.resolve("bare");
        GitService fresh = freshRepo(bare);

        assertThat(fresh.getFileTree("notes"))
                .singleElement()
                .extracting(GitFileNode::tracked)
                .isEqualTo(false);
        assertThat(fresh.searchFiles("todo", 5))
                .singleElement()
                .extracting(GitFileNode::tracked)
                .isEqualTo(false);
    }

    /** Отказ должен наступать до записи: иначе прогон скрипта уронит уже разложенные файлы. */
    @Test
    void theRefusalToCreateHappensBeforeAnythingIsWritten() {
        assertThatThrownBy(() -> service.requireCreatable("notes/new.md", "fresh\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not created");
    }

    @Test
    void editingAnAdmittedFileWorksAndLeavesItUntracked() {
        service.editFile("notes/todo.md", "milk", "bread", false);

        assertThat(readFile("notes/todo.md")).isEqualTo("remember the bread\n");
        assertThat(runGitOutput("status", "--porcelain", "-uall")).contains("?? notes/todo.md");
    }

    /**
     * Файлы в разрешённой зоне производит кто-то другой — сборка, человек. Новый файл, положенный
     * туда ассистентом, либо ушёл бы в индекс и покинул зону, либо остался бы вне git навсегда.
     */
    @Test
    void creatingInsideTheGlobsIsRefused() {
        assertThatThrownBy(() -> service.createFile("notes/new.md", "fresh\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not created");
        assertThat(repoDir.resolve("notes/new.md")).doesNotExist();
    }

    @Test
    void creatingOutsideTheGlobsStillStagesTheFile() {
        service.createFile("src/New.java", "class New {}\n");

        assertThat(runGitOutput("status", "--porcelain")).contains("A  src/New.java");
    }

    @Test
    void grepSearchesTrackedFilesOnlyUnlessAsked() {
        assertThat(service.grepContent("milk", null, false, 0, 50, false)).isEmpty();

        assertThat(service.grepContent("milk", null, false, 0, 50, true))
                .extracting(GitGrepMatch::path)
                .containsExactly("notes/todo.md");
    }

    @Test
    void grepReachesGitignoredFilesInsideTheGlobsButNothingElse() {
        assertThat(service.grepContent("and gitignored", null, false, 0, 50, true))
                .extracting(GitGrepMatch::path)
                .containsExactly("notes/generated/report.md");

        assertThat(service.grepContent("not admitted", null, false, 0, 50, true)).isEmpty();
    }

    /**
     * Лимит ограничивает ответы, а не прочитанные строки: невидимое отсеивается до обрезки, иначе
     * весь лимит ушёл бы на совпадения, которых никто не увидит.
     */
    @Test
    void theResultLimitCountsOnlyVisibleMatches() {
        writeFile("scratch.txt", "remember the milk\n");
        writeFile("src/App.java", "class App { String milk; }\n");
        runGit("add", "src/App.java");

        List<GitGrepMatch> hits = service.grepContent("milk", null, false, 0, 2, true);

        assertThat(hits)
                .extracting(GitGrepMatch::path)
                .containsExactly("notes/todo.md", "src/App.java");
    }

    /** Правки ассистента в разрешённой зоне обязаны быть видны в списке изменений. */
    @Test
    void admittedUntrackedFilesShowUpAmongTheUncommittedChanges() {
        assertThat(service.getUncommittedChanges(false))
                .extracting(GitDiffEntry::path)
                .containsExactly("notes/todo.md");
    }

    /** Сборочный артефакт читается, но изменением не является — в ревью ему делать нечего. */
    @Test
    void gitignoredFilesStayOutOfTheUncommittedChanges() {
        assertThat(service.getUncommittedChanges(false))
                .extracting(GitDiffEntry::path)
                .doesNotContain("notes/generated/report.md");
    }

    @Test
    void theTreeMarksAnAdmittedFileAsUntracked() {
        assertThat(service.getFileTree("notes"))
                .filteredOn(n -> "notes/todo.md".equals(n.path()))
                .singleElement()
                .extracting(GitFileNode::tracked)
                .isEqualTo(false);
        assertThat(service.getFileTree("src")).allSatisfy(n -> assertThat(n.tracked()).isTrue());
    }

    @Test
    void withoutGlobsUntrackedFilesStayInvisible() {
        GitService plain = TestProjects.gitService(repoDir, true);

        assertThatThrownBy(() -> plain.getFileContent("notes/todo.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("File not found");
        assertThat(plain.listTrackedFiles()).doesNotContain("notes/todo.md");
    }

    /** Репозиторий без единого коммита, в котором есть только admitted-файл. */
    private GitService freshRepo(Path dir) {
        try {
            Files.createDirectories(dir.resolve("notes"));
            Files.writeString(dir.resolve("notes/todo.md"), "remember the milk\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        runGitIn(dir, "init", "-q");
        return TestProjects.gitService(dir, true, List.of("notes/**"));
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
        return runGitIn(repoDir, args);
    }

    private String runGitIn(Path dir, String... args) {
        try {
            var command = new java.util.ArrayList<String>();
            command.add("git");
            command.addAll(List.of(args));
            Process process =
                    new ProcessBuilder(command)
                            .directory(dir.toFile())
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
