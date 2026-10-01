package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.github.trialiya.kb.model.git.dto.FileEntryType;
import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitCommitGrepResult;
import io.github.trialiya.kb.model.git.dto.GitCommitMatch;
import io.github.trialiya.kb.model.git.dto.GitCommitSearchResult;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.model.git.dto.GitTreeLevel;
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
 * Unit tests for {@link GitService#getFileTree(String)} against a real, throwaway git repository
 * (built via the {@code git} binary itself, mirroring how {@link GitService} shells out).
 */
class GitServiceTest {

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

    private void writeFile(String relativePath, String content) {
        try {
            Path file = repoDir.resolve(relativePath);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteFile(String relativePath) {
        try {
            Files.delete(repoDir.resolve(relativePath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void commitAll() {
        commitAll("test commit");
    }

    private void commitAll(String message) {
        runGit("add", "-A");
        runGit("commit", "-q", "-m", message);
    }

    private void runGit(String... args) {
        runGit(true, args);
    }

    /** Runs git; with {@code failOnError=false} a non-zero exit (e.g. a conflicted merge) is OK. */
    private void runGit(boolean failOnError, String... args) {
        try {
            var command = new java.util.ArrayList<String>();
            command.add("git");
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command)
                    .directory(repoDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (failOnError && exit != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to run git command", e);
        }
    }

    @Test
    void parsesCyrillicPathsAsOneNodeWithoutPhantomEntries() {
        writeFile("docs/проект/readme.md", "hello");
        writeFile("pom.txt", "root file");
        commitAll();

        List<GitFileNode> root = service.getFileTree(null, null);

        // Regression: without core.quotepath=false, git quotes/octal-escapes the Cyrillic path,
        // which used to split into a bogus quoted "docs" node distinct from the real one.
        List<GitFileNode> docsNodes =
                root.stream().filter(n -> n.name().equals("docs")).toList();
        assertThat(docsNodes).hasSize(1);
        assertThat(docsNodes.get(0).path()).isEqualTo("docs");
        assertThat(docsNodes.get(0).type()).isEqualTo(FileEntryType.DIRECTORY);

        List<GitFileNode> underDocs = service.getFileTree(null, "docs");
        assertThat(underDocs).hasSize(1);
        assertThat(underDocs.get(0).path()).isEqualTo("docs/проект");
        assertThat(underDocs.get(0).type()).isEqualTo(FileEntryType.DIRECTORY);

        List<GitFileNode> underProject = service.getFileTree(null, "docs/проект");
        assertThat(underProject).hasSize(1);
        assertThat(underProject.get(0).path()).isEqualTo("docs/проект/readme.md");
        assertThat(underProject.get(0).name()).isEqualTo("readme.md");
        assertThat(underProject.get(0).type()).isEqualTo(FileEntryType.FILE);
    }

    @Test
    void sortsDirectoriesBeforeFilesThenAlphabeticallyIgnoringCase() {
        writeFile("banana.txt", "b");
        writeFile("Apple.txt", "a");
        writeFile("zebra/x.txt", "z");
        writeFile("Bird/x.txt", "b");
        commitAll();

        List<GitFileNode> root = service.getFileTree(null, null);

        assertThat(root.stream().map(GitFileNode::name).toList())
                .containsExactly("Bird", "zebra", "Apple.txt", "banana.txt");
    }

    @Test
    void untrackedAndMissingFilesReportIdenticalError() {
        writeFile("tracked.txt", "tracked");
        commitAll();
        // Present on disk but never added/committed.
        writeFile("secret.env", "API_KEY=hunter2");

        assertThatThrownBy(() -> service.getFileContent("secret.env"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("File not found: secret.env");

        // A path that doesn't exist on disk at all must produce the exact same message —
        // otherwise the error text itself would leak which untracked files exist on disk.
        assertThatThrownBy(() -> service.getFileContent("does-not-exist.txt"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("File not found: does-not-exist.txt");
    }

    /**
     * Тело коммита стоит контекста, поэтому в истории оно за флагом, а рядом с диффом одного
     * названного коммита — без флага. Subject в обоих случаях остаётся отдельно от тела.
     */
    @Test
    void theMessageBodyIsOptionalInTheLogAndUnconditionalNextToADiff() {
        writeFile("a.txt", "x\n");
        commitAll("Subject line\n\nWhy it was done.\n\nAnd a second paragraph.");

        assertThat(service.getCommitLog(1, null, false).commits().getFirst())
                .satisfies(c -> assertThat(c.message()).isEqualTo("Subject line"))
                .satisfies(c -> assertThat(c.body()).isNull());

        GitCommit withBody = service.getCommitLog(1, null, true).commits().getFirst();
        assertThat(withBody.message()).isEqualTo("Subject line");
        assertThat(withBody.body()).isEqualTo("Why it was done.\n\nAnd a second paragraph.");

        assertThat(service.getCommitDiff(withBody.hash(), false).getFirst().body())
                .isEqualTo(withBody.body());
    }

    /** Репозиторий без единого коммита — пустая история, а не ошибка: и у листинга, и у поиска. */
    @Test
    void anUnbornRepositoryHasAnEmptyHistory() {
        assertThat(service.getCommitLog(5, null, false)).isEqualTo(new GitCommitSearchResult(List.of(), false));
        assertThat(service.searchCommits("x", 5)).isEqualTo(new GitCommitSearchResult(List.of(), false));
    }

    /**
     * Ревизия у чтения, которое умеет и рабочее дерево, и снимок: пустая и из одних пробелов —
     * рабочее дерево, как и {@code null}; правка на диске видна только там.
     */
    @Test
    void aBlankRevisionReadsTheWorkingTree() {
        writeFile("a.txt", "committed\n");
        commitAll("first");
        writeFile("a.txt", "on disk\n");
        String head = service.getCommitLog(1, null, false).commits().get(0).hash();

        assertThat(service.getFileContent("  ", "a.txt", null, null).content()).isEqualTo("on disk\n");
        assertThat(service.getFileContent(null, "a.txt", null, null).content()).isEqualTo("on disk\n");
        assertThat(service.getFileContent(head, "a.txt", null, null).content()).isEqualTo("committed\n");
        assertThat(service.getRawFile("", "a.txt").bytes()).isEqualTo("on disk\n".getBytes(StandardCharsets.UTF_8));
    }

    /** Пустой запрос — пустой ответ, и пробелы вокруг ничего не меняют: искать нечего. */
    @Test
    void aBlankQueryFindsNothing() {
        writeFile("a.txt", "1\n");
        commitAll("first");

        assertThat(service.searchCommits("   ", 5)).isEqualTo(new GitCommitSearchResult(List.of(), false));
        assertThat(service.grepCommits(" \t ", 5, null)).isEqualTo(new GitCommitGrepResult(List.of(), false));
        assertThat(service.searchCommits("  first  ", 5).commits())
                .extracting(GitCommit::message)
                .containsExactly("first");
    }

    /**
     * Листинг говорит, есть ли история за последним отданным коммитом: просьба о двух при трёх
     * коммитах — неполная, о трёх — полная.
     */
    @Test
    void aListingSaysWhetherHistoryGoesOnPastIt() {
        for (String subject : List.of("one", "two", "three")) {
            writeFile("a.txt", subject + "\n");
            commitAll(subject);
        }

        assertThat(service.getCommitLog(2, null, false).truncated()).isTrue();
        assertThat(service.getCommitLog(3, null, false).truncated()).isFalse();
        // Без точности обход стоит на лимите: полная страница — «могут быть ещё», даже когда история
        // на ней и кончилась; неполная — конец истории.
        assertThat(service.getCommitLog(3, null, false, null, false).truncated())
                .isTrue();
        assertThat(service.getCommitLog(5, null, false, null, false).truncated())
                .isFalse();
    }

    /**
     * Дифф по списку хешей тел не отдаёт: иначе цена контекста, ради которой в логе заведён флаг,
     * вернулась бы через соседний инструмент — «покажи, что менялось в двадцати коммитах».
     */
    @Test
    void aListOfCommitsGetsDiffsWithoutTheirBodies() {
        writeFile("a.txt", "1\n");
        commitAll("First\n\nBody one.");
        writeFile("a.txt", "2\n");
        commitAll("Second\n\nBody two.");

        List<GitCommit> log = service.getCommitLog(2, null, false).commits();
        String pair = log.get(0).hash() + "," + log.get(1).hash();

        assertThat(service.getCommitDiff(pair, false))
                .hasSize(2)
                .allSatisfy(c -> assertThat(c.body()).isNull())
                .allSatisfy(c -> assertThat(c.files()).isNotEmpty());
    }

    /**
     * Слева у тела снимаются только переносы: тело часто открывается блоком кода, и общий {@code
     * strip()} снял бы отступ у одной первой строки, оставив остальные.
     */
    @Test
    void anIndentedFirstBodyLineKeepsItsIndentation() {
        writeFile("a.txt", "x\n");
        commitAll("Fix the parser\n\n    if (x) {\n        return y;\n    }\n");

        assertThat(service.getCommitLog(1, null, true).commits().getFirst().body())
                .isEqualTo("    if (x) {\n        return y;\n    }");
    }

    /** Тело из одних пробелов — это отсутствие тела, а не значение. */
    @Test
    void aWhitespaceOnlyBodyIsNoBody() {
        writeFile("a.txt", "x\n");
        commitAll("Subject\n\n   \n  \n");

        assertThat(service.getCommitLog(1, null, true).commits().getFirst().body())
                .isNull();
    }

    /** Однострочное сообщение — тела нет, а не пустая строка: пустое поле незачем сериализовать. */
    @Test
    void aSubjectOnlyCommitHasNoBody() {
        writeFile("a.txt", "x\n");
        commitAll("Just a subject");

        assertThat(service.getCommitLog(1, null, true).commits().getFirst().body())
                .isNull();
    }

    /**
     * Длинный subject git переносит на несколько строк, и {@code RevCommit.getShortMessage()}
     * склеивает его обратно через пробел — режем по первой пустой строке, иначе такой перенос
     * утащил бы хвост subject-а в тело.
     */
    @Test
    void aWrappedSubjectDoesNotLeakIntoTheBody() {
        writeFile("a.txt", "x\n");
        commitAll("Subject that git\nwrapped onto two lines\n\nThe body.");

        GitCommit commit = service.getCommitLog(1, null, true).commits().getFirst();
        assertThat(commit.message()).isEqualTo("Subject that git wrapped onto two lines");
        assertThat(commit.body()).isEqualTo("The body.");
    }

    @Test
    void rootCommitDiffIncludesTheInitialFiles() {
        writeFile("a.txt", "line1\nline2\n");
        commitAll();

        List<GitCommit> log = service.getCommitLog(10, null, false).commits();
        assertThat(log).hasSize(1);

        // Regression: `git diff-tree` needs --root to show anything for the very first commit;
        // the original implementation never passed it, so this used to come back with an empty
        // files list even though the commit clearly added a file.
        List<GitCommit> diff = service.getCommitDiff(log.get(0).hash(), false);
        assertThat(diff).hasSize(1);
        List<GitDiffEntry> files = diff.get(0).files();
        assertThat(files).hasSize(1);
        assertThat(files.get(0).status()).isEqualTo("A");
        assertThat(files.get(0).path()).isEqualTo("a.txt");
        assertThat(files.get(0).additions()).isEqualTo(2);
    }

    @Test
    void appendOnlyEditToExistingFileIsReportedAsModifyNotAdd() throws IOException {
        writeFile("a.txt", "line1\nline2\n");
        commitAll();

        // Pure addition, no deletions — a naive numstat heuristic (additions>0 && deletions==0)
        // would misclassify this as "added" even though the file already existed.
        Files.writeString(repoDir.resolve("a.txt"), "line1\nline2\nline3\n", StandardCharsets.UTF_8);

        List<GitDiffEntry> changes = service.getUncommittedChanges(false);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).status()).isEqualTo("M");
        assertThat(changes.get(0).path()).isEqualTo("a.txt");
        assertThat(changes.get(0).additions()).isEqualTo(1);
        assertThat(changes.get(0).deletions()).isEqualTo(0);
    }

    @Test
    void uncommittedChangesSkipsUntrackedFiles() {
        writeFile("tracked.txt", "hello\n");
        commitAll();

        writeFile("tracked.txt", "hello\nworld\n");
        writeFile("new-file.txt", "brand new");

        List<GitDiffEntry> changes = service.getUncommittedChanges(true);

        // Only the tracked edit — an untracked file has no Git history and no read tool can open
        // it, so it never appears here.
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).path()).isEqualTo("tracked.txt");
        assertThat(changes.get(0).status()).isEqualTo("M");
        assertThat(changes.get(0).patch()).contains("+world");
    }

    /**
     * Шапка патча описывает файл, а не его строки, и приходит отдельным полем: показывают её над
     * блоком кода, а не в нём, и делить патч на стороне клиента для этого не нужно.
     */
    @Test
    void patchArrivesWithItsHeaderSplitOffIntoItsOwnField() {
        writeFile("tracked.txt", "hello\n");
        commitAll();
        writeFile("tracked.txt", "hello\nworld\n");

        GitDiffEntry entry = service.getUncommittedChanges(true).get(0);

        assertThat(entry.patchHeader())
                .contains("diff --git")
                .contains("--- a/tracked.txt")
                .contains("+++ b/tracked.txt");
        // Заголовок ханка размечает сами строки — он остаётся в патче, и патч с него начинается.
        assertThat(entry.patch()).startsWith("@@").contains("+world").doesNotContain("diff --git");
    }

    @Test
    void uncommittedChangesNarrowedToOnePathReturnsOnlyThatFile() {
        writeFile("a.txt", "one\n");
        writeFile("b.txt", "one\n");
        commitAll();
        writeFile("a.txt", "one\ntwo\n");
        writeFile("b.txt", "one\nthree\n");

        List<GitDiffEntry> changes = service.getUncommittedChanges(true, "b.txt");

        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).path()).isEqualTo("b.txt");
        assertThat(changes.get(0).patch()).contains("+three").doesNotContain("+two");
    }

    @Test
    void uncommittedChangesNarrowedToAnUnchangedPathIsEmpty() {
        writeFile("a.txt", "one\n");
        writeFile("b.txt", "one\n");
        commitAll();
        writeFile("a.txt", "one\ntwo\n");

        // Открыт файл без изменений, а режим diff остался включённым — панель
        // показывает «изменений нет», и пустой ответ здесь именно это и значит.
        assertThat(service.getUncommittedChanges(true, "b.txt")).isEmpty();
        // Правка рядом при этом видна — пустой ответ выше про файл, а не про весь список.
        assertThat(service.getUncommittedChanges(true, "a.txt"))
                .singleElement()
                .satisfies(entry -> assertThat(entry.path()).isEqualTo("a.txt"));
    }

    /**
     * Фильтр — это pathspec git, а не имя файла: без wildcard он префикс пути, поэтому каталог
     * отбирает всё под собой, а wildcard идёт через «/» и достаёт файлы на любой глубине.
     */
    @Test
    void uncommittedChangesNarrowedByDirectoryAndByGlob() {
        writeFile("docs/a.md", "one\n");
        writeFile("src/main/A.java", "class A {}\n");
        writeFile("src/main/deep/B.java", "class B {}\n");
        writeFile("root.txt", "one\n");
        commitAll();
        writeFile("docs/a.md", "one\ntwo\n");
        writeFile("src/main/A.java", "class A { int x; }\n");
        writeFile("src/main/deep/B.java", "class B { int x; }\n");
        writeFile("root.txt", "one\ntwo\n");

        assertThat(service.getUncommittedChanges(false, List.of("docs")))
                .extracting(GitDiffEntry::path)
                .containsExactly("docs/a.md");
        assertThat(service.getUncommittedChanges(false, List.of("*.java")))
                .extracting(GitDiffEntry::path)
                .containsExactlyInAnyOrder("src/main/A.java", "src/main/deep/B.java");
    }

    /**
     * Одиночный путь — это путь, а не pathspec: панель спрашивает тем, что выбрано в URL, а выбран
     * бывает и каталог, и префиксное совпадение показало бы под его именем дифф первого файла
     * внутри.
     */
    @Test
    void uncommittedChangesNarrowedToASinglePathMatchThatPathOnly() {
        writeFile("docs/a.md", "one\n");
        commitAll();
        writeFile("docs/a.md", "one\ntwo\n");

        assertThat(service.getUncommittedChanges(true, "docs")).isEmpty();
        assertThat(service.getUncommittedChanges(true, "docs/a.md")).hasSize(1);
    }

    /**
     * «.» — это корень дерева, а не путь: фильтр из него значит то же, что пропущенный аргумент.
     */
    @Test
    void uncommittedChangesFilteredByTheRepoRootIsTheWholeTree() {
        writeFile("a.txt", "one\n");
        commitAll();
        writeFile("a.txt", "one\ntwo\n");

        assertThat(service.getUncommittedChanges(false, List.of(".")))
                .extracting(GitDiffEntry::path)
                .containsExactly("a.txt");
    }

    /** Несколько фильтров складываются как несколько pathspec'ов в команде git — по ИЛИ. */
    @Test
    void uncommittedChangesNarrowedToSeveralFiltersUnionsThem() {
        writeFile("docs/a.md", "one\n");
        writeFile("src/A.java", "class A {}\n");
        writeFile("root.txt", "one\n");
        commitAll();
        writeFile("docs/a.md", "one\ntwo\n");
        writeFile("src/A.java", "class A { int x; }\n");
        writeFile("root.txt", "one\ntwo\n");

        assertThat(service.getUncommittedChanges(false, List.of("docs", "root.txt")))
                .extracting(GitDiffEntry::path)
                .containsExactlyInAnyOrder("docs/a.md", "root.txt");
        // Пустой список — весь список изменений, как и вызов без фильтра вовсе.
        assertThat(service.getUncommittedChanges(false, List.of()))
                .extracting(GitDiffEntry::path)
                .containsExactlyInAnyOrder("docs/a.md", "src/A.java", "root.txt");
    }

    @Test
    void uncommittedChangesNarrowedToARenameKeepsStatusRUnderEitherName() {
        writeFile("old-name.txt", "one\ntwo\nthree\n");
        commitAll();
        runGit("mv", "old-name.txt", "new-name.txt");

        // Обе стороны переименования ведут к одной записи: список показывает
        // файл под новым именем, а ссылка на старое приходит из истории.
        for (String name : List.of("new-name.txt", "old-name.txt")) {
            List<GitDiffEntry> changes = service.getUncommittedChanges(false, name);
            assertThat(changes).hasSize(1);
            assertThat(changes.get(0).status()).isEqualTo("R");
            assertThat(changes.get(0).path()).isEqualTo("new-name.txt");
            assertThat(changes.get(0).oldPath()).isEqualTo("old-name.txt");
        }
    }

    /**
     * Сузить список до одного пути — про этот путь, а не про то, что рядом: переименование другого
     * файла в тот же ответ не попадает, хотя ради него список изменений и читается целиком.
     */
    @Test
    void uncommittedChangesNarrowedToOneFileLeaveARenameOfAnotherOut() {
        writeFile("old-name.txt", "one\ntwo\nthree\n");
        writeFile("edited.txt", "before\n");
        commitAll();
        runGit("mv", "old-name.txt", "new-name.txt");
        writeFile("edited.txt", "after\n");

        List<GitDiffEntry> changes = service.getUncommittedChanges(false, "edited.txt");
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).status()).isEqualTo("M");
        assertThat(changes.get(0).path()).isEqualTo("edited.txt");
    }

    @Test
    void uncommittedChangesReportsStagedNewFileAsAdded() {
        writeFile("tracked.txt", "hello\n");
        commitAll();

        writeFile("new-file.txt", "brand new\n");
        runGit("add", "new-file.txt");

        List<GitDiffEntry> changes = service.getUncommittedChanges(true);
        assertThat(changes).hasSize(1);

        GitDiffEntry added = changes.get(0);
        assertThat(added.path()).isEqualTo("new-file.txt");
        assertThat(added.status()).isEqualTo("A");
        assertThat(added.patch()).contains("+brand new");
    }

    @Test
    void renameCommitDiffReportsStatusRWithOldAndNewPath() {
        writeFile("old-name.txt", "one\ntwo\nthree\n");
        commitAll();
        runGit("mv", "old-name.txt", "new-name.txt");
        runGit("commit", "-q", "-m", "rename");

        List<GitCommit> log = service.getCommitLog(1, null, false).commits();
        List<GitDiffEntry> files =
                service.getCommitDiff(log.get(0).hash(), false).get(0).files();

        assertThat(files).hasSize(1);
        GitDiffEntry entry = files.get(0);
        assertThat(entry.status()).isEqualTo("R");
        assertThat(entry.oldPath()).isEqualTo("old-name.txt");
        assertThat(entry.path()).isEqualTo("new-name.txt");
        // A pure rename moves content untouched — no line churn.
        assertThat(entry.additions()).isZero();
        assertThat(entry.deletions()).isZero();
    }

    @Test
    void copyDetectedAlongsideRenameCarriesItsSourcePath() {
        writeFile("origin.txt", "shared\ncontent\nlines\n");
        commitAll();
        // Delete the original and add two byte-identical files: JGit's rename detector pairs the
        // delete with one add (RENAME) and marks the leftover exact match as COPY.
        runGit("mv", "origin.txt", "kept.txt");
        writeFile("extra.txt", "shared\ncontent\nlines\n");
        commitAll();

        List<GitCommit> log = service.getCommitLog(1, null, false).commits();
        List<GitDiffEntry> files =
                service.getCommitDiff(log.get(0).hash(), false).get(0).files();

        // Both new files are byte-identical, so which one the detector promotes to RENAME (vs
        // COPY) is an arbitrary internal choice — assert the pair, not the assignment.
        assertThat(files).extracting(GitDiffEntry::status).containsExactlyInAnyOrder("R", "C");
        assertThat(files).extracting(GitDiffEntry::path).containsExactlyInAnyOrder("kept.txt", "extra.txt");
        // The copy must carry its source path, same as the rename.
        assertThat(files).extracting(GitDiffEntry::oldPath).containsExactly("origin.txt", "origin.txt");
    }

    /**
     * The file browser's view of one commit: the message with its body, every file it changed
     * without patches, and — asked by path — one file with its patch.
     */
    @Test
    void getCommitDescribesOneCommitAndItsFilesAndPatchesOneFileOnRequest() {
        writeFile("kept.txt", "a\n");
        writeFile("gone.txt", "bye\n");
        commitAll("base");
        writeFile("kept.txt", "a\nb\n");
        deleteFile("gone.txt");
        commitAll("subject\n\nwhy it changed");

        GitCommit commit = service.getCommit("HEAD", false, null);
        assertThat(commit.message()).isEqualTo("subject");
        assertThat(commit.body()).isEqualTo("why it changed");
        assertThat(commit.files())
                .extracting(GitDiffEntry::status, GitDiffEntry::path)
                .containsExactlyInAnyOrder(tuple("M", "kept.txt"), tuple("D", "gone.txt"));
        assertThat(commit.files()).allSatisfy(f -> assertThat(f.patch()).isNull());

        GitDiffEntry deleted =
                service.getCommit("HEAD", true, "gone.txt").files().getFirst();
        assertThat(deleted.status()).isEqualTo("D");
        assertThat(deleted.patch()).contains("-bye");
        assertThat(service.getCommit("HEAD~1", true, "nowhere.txt").files()).isEmpty();
    }

    /**
     * The Commit tab leads back through the parents, so the browser's view of a commit names them —
     * a root commit none; the model's diff does not, where they would only cost tokens.
     */
    @Test
    void getCommitNamesTheParentsButTheToolsDiffDoesNot() {
        writeFile("a.txt", "a\n");
        commitAll("root");
        writeFile("a.txt", "b\n");
        commitAll("child");

        GitCommit root = service.getCommit("HEAD~1", false, null);
        assertThat(root.parents()).isEmpty();
        assertThat(service.getCommit("HEAD", false, null).parents()).containsExactly(root.hash());
        assertThat(service.getCommitDiff("HEAD", false).getFirst().parents()).isNull();
        assertThat(service.getCommitLog(5, null, false).commits())
                .allSatisfy(c -> assertThat(c.parents()).isNull());
    }

    /**
     * A renamed file opened in the browser by its new name is still a rename: the path picks the
     * entry after rename detection instead of hiding the file's old side from it.
     */
    @Test
    void getCommitByPathKeepsARenameARename() {
        writeFile("old-name.txt", "one\ntwo\nthree\n");
        commitAll();
        runGit("mv", "old-name.txt", "new-name.txt");
        runGit("commit", "-q", "-m", "rename");

        List<GitDiffEntry> files =
                service.getCommit("HEAD", true, "new-name.txt").files();

        assertThat(files).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo("R");
            assertThat(entry.oldPath()).isEqualTo("old-name.txt");
        });
    }

    @Test
    void getCommitOfAnUnknownRevisionIsAnArgumentError() {
        writeFile("a.txt", "a\n");
        commitAll();

        assertThatThrownBy(() -> service.getCommit("nosuchbranch", false, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void windowsBackslashInSubPathIsNormalizedToForwardSlash() {
        writeFile("src/main/Foo.java", "class Foo {}");
        commitAll();

        // Windows callers may pass "src\\main" — must be treated identically to "src/main".
        List<GitFileNode> nodes = service.getFileTree(null, "src\\main");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).name()).isEqualTo("Foo.java");
        assertThat(nodes.get(0).path()).isEqualTo("src/main/Foo.java");
    }

    @Test
    void windowsBackslashInFilePathIsNormalizedForGetFileContent() {
        writeFile("src/main/Foo.java", "class Foo {}\n");
        commitAll();

        // "src\\main\\Foo.java" must resolve to "src/main/Foo.java".
        var content = service.getFileContent("src\\main\\Foo.java");
        assertThat(content.path()).isEqualTo("src/main/Foo.java");
        assertThat(content.content()).contains("Foo");
    }

    @Test
    void windowsBackslashInCommitLogFilePathIsNormalized() {
        writeFile("src/main/Bar.java", "class Bar {}\n");
        commitAll("bar");
        writeFile("src/main/Other.java", "class Other {}\n");
        commitAll("other");

        // A backslash path must still find the commit that touched THAT file — a filter that
        // stopped being applied would return both commits.
        var log = service.getCommitLog(10, "src\\main\\Bar.java", false).commits();
        assertThat(log).singleElement().satisfies(c -> assertThat(c.message()).isEqualTo("bar"));
    }

    @Test
    void windowsBackslashInCommitDiffFilePathIsNormalized() {
        writeFile("src/main/Baz.java", "class Baz {}\n");
        commitAll();
        String hash = service.getCommitLog(1, null, false).commits().get(0).hash();

        // A backslash path must resolve to the same file the diff's PathFilter expects.
        var diff = service.getCommitDiff(hash, false, "src\\main\\Baz.java");
        assertThat(diff).hasSize(1);
        assertThat(diff.get(0).files()).hasSize(1);
        assertThat(diff.get(0).files().get(0).path()).isEqualTo("src/main/Baz.java");
    }

    @Test
    void crlfLineEndingsAreNormalizedInFileContent() throws IOException {
        writeFile("crlf.txt", "line1\nline2\n");
        commitAll();
        // Overwrite the working-tree file with CRLF content (simulating Windows checkout).
        Files.write(repoDir.resolve("crlf.txt"), "line1\r\nline2\r\n".getBytes(StandardCharsets.UTF_8));

        var content = service.getFileContent("crlf.txt");
        // Returned content must use LF; no trailing \r should appear on any line.
        assertThat(content.content()).doesNotContain("\r");
        assertThat(content.lineCount()).isEqualTo(3); // "line1", "line2", "" (trailing empty)
    }

    @Test
    void conflictedFilesRemainVisibleInTreeContentAndUncommittedChanges() {
        writeFile("conflict.txt", "base\n");
        commitAll();
        runGit("branch", "-M", "main");
        runGit("checkout", "-q", "-b", "side");
        writeFile("conflict.txt", "side\n");
        commitAll();
        runGit("checkout", "-q", "main");
        writeFile("conflict.txt", "ours\n");
        commitAll();
        // Non-zero exit — the conflict is exactly the state under test.
        runGit(false, "merge", "side");

        // A conflicted file has only stage-1..3 index entries (no stage 0). It is still tracked,
        // so it must not vanish from the tree, from file content, or from uncommitted changes.
        assertThat(service.getFileTree(null, null))
                .extracting(GitFileNode::name)
                .contains("conflict.txt");
        assertThat(service.getFileContent("conflict.txt").content()).contains("<<<<<<<");

        List<GitDiffEntry> changes = service.getUncommittedChanges(false);
        assertThat(changes).extracting(GitDiffEntry::path).containsExactly("conflict.txt");
        assertThat(changes.get(0).status()).isEqualTo("M");
    }

    // ── browsePath: opening one path in the file browser ─────────────────────

    @Test
    void browsePathReturnsFileContentAndEveryAncestorListing() {
        writeFile("src/main/java/com/app/Main.java", "class Main {}\n");
        writeFile("src/main/java/com/app/Util.java", "class Util {}\n");
        writeFile("README.md", "readme\n");
        commitAll();

        var view = service.browsePath(null, "src/main/java/com/app/Main.java", true);

        assertThat(view.type()).isEqualTo(FileEntryType.FILE);
        assertThat(view.file()).isNotNull();
        assertThat(view.file().content()).contains("class Main");
        assertThat(view.nodes()).isNull();

        // The whole chain root → parent comes back at once: that is what saved the file browser
        // one round trip per level of nesting.
        assertThat(view.tree())
                .extracting(GitTreeLevel::path)
                .containsExactly("", "src", "src/main", "src/main/java", "src/main/java/com", "src/main/java/com/app");
        assertThat(view.tree().getFirst().nodes())
                .extracting(GitFileNode::name)
                .containsExactly("src", "README.md"); // directories first
        // The last level is the file's own directory — the tree needs its siblings to render it.
        assertThat(view.tree().getLast().nodes())
                .extracting(GitFileNode::name)
                .containsExactly("Main.java", "Util.java");
    }

    @Test
    void browsePathReturnsDirectoryListingWithoutRepeatingItInTheAncestors() {
        writeFile("docs/guide/intro.md", "intro\n");
        commitAll();

        var view = service.browsePath(null, "docs/guide", true);

        assertThat(view.type()).isEqualTo(FileEntryType.DIRECTORY);
        assertThat(view.file()).isNull();
        assertThat(view.nodes()).extracting(GitFileNode::name).containsExactly("intro.md");
        // The opened directory itself is in `nodes`, so it must not be duplicated as a level.
        assertThat(view.tree()).extracting(GitTreeLevel::path).containsExactly("", "docs");
    }

    @Test
    void browsePathSkipsAncestorsWhenTheCallerAlreadyHasThem() {
        writeFile("docs/guide/intro.md", "intro\n");
        commitAll();

        var view = service.browsePath(null, "docs/guide/intro.md", false);

        assertThat(view.type()).isEqualTo(FileEntryType.FILE);
        assertThat(view.file()).isNotNull();
        assertThat(view.tree()).isEmpty();
    }

    @Test
    void browsePathReportsAnUnknownPathAsMissingInsteadOfFailing() {
        writeFile("docs/guide/intro.md", "intro\n");
        commitAll();

        var view = service.browsePath(null, "docs/guide/gone.md", true);

        // A dead deep link must render as "not found", not as a load error.
        assertThat(view.type()).isNull();
        assertThat(view.file()).isNull();
        assertThat(view.nodes()).isNull();
        // The tree still expands as far as the path exists, so the user sees where it broke.
        assertThat(view.tree()).extracting(GitTreeLevel::path).containsExactly("", "docs", "docs/guide");
    }

    /**
     * Индекс помнит файл, удалённый из рабочего дерева, — и путь к нему открывают: по ссылке, по
     * строке списка изменений, кнопкой «обновить» на уже открытом файле. Ответ тот же, что у пути,
     * которого не было вовсе: показать нечего. Отказ на его месте уносил бы вместе с содержимым
     * дерево и листинги предков — всю панель ради одного пути, — а режиму изменений нечего было бы
     * подставить под diff, в котором такой файл только и виден.
     */
    @Test
    void browsePathReportsATrackedFileDeletedFromTheWorkingTreeAsMissing() {
        writeFile("docs/guide/intro.md", "intro\n");
        writeFile("docs/guide/gone.md", "gone\n");
        commitAll();
        deleteFile("docs/guide/gone.md");

        var view = service.browsePath(null, "docs/guide/gone.md", true);

        assertThat(view.type()).isNull();
        assertThat(view.file()).isNull();
        assertThat(view.tree()).extracting(GitTreeLevel::path).containsExactly("", "docs", "docs/guide");
        // Соседний файл того же каталога открывается как ни в чём не бывало.
        assertThat(service.browsePath(null, "docs/guide/intro.md", false).type())
                .isEqualTo(FileEntryType.FILE);
    }

    @Test
    void browsePathWithoutPathOpensTheRepositoryRoot() {
        writeFile("docs/guide/intro.md", "intro\n");
        writeFile("README.md", "readme\n");
        commitAll();

        var view = service.browsePath(null, null, true);

        assertThat(view.path()).isEmpty();
        assertThat(view.type()).isEqualTo(FileEntryType.DIRECTORY);
        assertThat(view.nodes()).extracting(GitFileNode::name).containsExactly("docs", "README.md");
        // The root has no ancestors — its own listing is `nodes`, not a tree level.
        assertThat(view.tree()).isEmpty();
    }

    @Test
    void browsePathListingsMatchTheSingleDirectoryEndpoint() {
        writeFile("src/main/java/com/app/Main.java", "class Main {}\n");
        writeFile("src/main/resources/app.yaml", "a: 1\n");
        writeFile("src/README.md", "readme\n");
        commitAll();

        // The batched pass over the index must produce exactly what getFileTree does level by
        // level — same nodes, same order.
        var view = service.browsePath(null, "src/main/java/com/app/Main.java", true);
        for (GitTreeLevel level : view.tree()) {
            assertThat(level.nodes()).isEqualTo(service.getFileTree(null, level.path()));
        }

        // Обе стороны сравнения строит один и тот же обход, поэтому один уровень закреплён
        // именами: общая поломка листингов иначе осталась бы зелёной.
        assertThat(view.tree())
                .filteredOn(level -> "src".equals(level.path()))
                .singleElement()
                .satisfies(level ->
                        assertThat(level.nodes()).extracting(GitFileNode::name).containsExactly("main", "README.md"));
    }

    @Test
    void browsePathHandlesWindowsBackslashesAndTrailingSlashes() {
        writeFile("src/main/Foo.java", "class Foo {}\n");
        commitAll();

        assertThat(service.browsePath(null, "src\\main\\Foo.java", true).type()).isEqualTo(FileEntryType.FILE);

        var dir = service.browsePath(null, "src/main/", true);
        assertThat(dir.path()).isEqualTo("src/main");
        assertThat(dir.type()).isEqualTo(FileEntryType.DIRECTORY);
        assertThat(dir.nodes()).extracting(GitFileNode::name).containsExactly("Foo.java");
    }

    // ── searchCommits ───────────────────────────────────────────────────────

    @Test
    void searchCommitsMatchesSubjectSubstringIgnoringCase() {
        writeFile("a.txt", "a\n");
        commitAll("Add parsing for placeholders");
        writeFile("b.txt", "b\n");
        commitAll("Unrelated change");

        assertThat(service.searchCommits("PLACEHOLD", 10).commits())
                .extracting(GitCommit::message)
                .containsExactly("Add parsing for placeholders");
    }

    @Test
    void searchCommitsMatchesHashPrefix() {
        writeFile("a.txt", "a\n");
        commitAll("Only commit");
        String hash = service.getCommitLog(1, null, false).commits().get(0).hash();

        assertThat(service.searchCommits(hash.substring(0, 6).toUpperCase(), 10).commits())
                .extracting(GitCommit::hash)
                .containsExactly(hash);
    }

    @Test
    void searchCommitsReturnsNewestFirstAndHonoursTheLimit() {
        writeFile("a.txt", "a\n");
        commitAll("fix one");
        writeFile("b.txt", "b\n");
        commitAll("fix two");
        writeFile("c.txt", "c\n");
        commitAll("fix three");

        assertThat(service.searchCommits("fix", 2).commits())
                .extracting(GitCommit::message)
                .containsExactly("fix three", "fix two");
    }

    /**
     * The picker is told when its list is not everything: a limit hit with history left is cut, a
     * walk that reached the first commit is not — even with nothing found.
     */
    @Test
    void searchCommitsSaysWhetherHistoryWasWalkedToItsEnd() {
        writeFile("a.txt", "a\n");
        commitAll("fix one");
        writeFile("b.txt", "b\n");
        commitAll("fix two");

        assertThat(service.searchCommits("fix", 1).truncated()).isTrue();
        assertThat(service.searchCommits("fix", 10).truncated()).isFalse();
        assertThat(service.searchCommits("nothing-like-this", 10).truncated()).isFalse();
    }

    @Test
    void searchCommitsReturnsEmptyWhenNothingMatches() {
        writeFile("a.txt", "a\n");
        commitAll("Only commit");

        assertThat(service.searchCommits("nothing-like-this", 10).commits()).isEmpty();
    }

    /** A hash prefix must not be confused with a body hit: the picker only shows the subject. */
    @Test
    void searchCommitsIgnoresTheMessageBody() {
        writeFile("a.txt", "a\n");
        commitAll("Subject line" + System.lineSeparator() + System.lineSeparator() + "mentions zzz");

        assertThat(service.searchCommits("zzz", 10).commits()).isEmpty();
        assertThat(service.searchCommits("Subject", 10).commits()).hasSize(1);
    }

    /**
     * The search page gets where the query matched instead of the description: the lines of the
     * description that hold it (numbered from 1), whether the subject did, and — when neither did —
     * that the hash prefix is what found the commit. The description itself does not travel.
     */
    @Test
    void grepCommitsSaysWhereEachCommitMatched() {
        writeFile("a.txt", "a\n");
        commitAll("Subject line\n\nfirst line\nmentions ZZZ\nlast");
        writeFile("b.txt", "b\n");
        commitAll("Unrelated zzz change");

        assertThat(service.grepCommits("zzz", 10, null).commits())
                .satisfiesExactly(
                        subject -> {
                            assertThat(subject.commit().message()).isEqualTo("Unrelated zzz change");
                            assertThat(subject.subjectMatch()).isTrue();
                            assertThat(subject.lines()).isEmpty();
                            assertThat(subject.hashMatch()).isFalse();
                        },
                        body -> {
                            assertThat(body.commit().body()).isNull();
                            assertThat(body.subjectMatch()).isFalse();
                            assertThat(body.lines()).containsExactly(new GitCommitMatch.Line(2, "mentions ZZZ"));
                        });

        String hash = service.getCommitLog(1, null, false).commits().get(0).hash();
        assertThat(service.grepCommits(hash.substring(0, 8), 10, null).commits())
                .singleElement()
                .satisfies(found -> {
                    assertThat(found.hashMatch()).isTrue();
                    assertThat(found.lines()).isEmpty();
                });
    }

    @Test
    void grepCommitsFromARevisionSkipsCommitsMadeAfterIt() {
        writeFile("a.txt", "a\n");
        commitAll("fix one");
        String first = service.getCommitLog(1, null, false).commits().get(0).hash();
        writeFile("b.txt", "b\n");
        commitAll("fix two");

        assertThat(service.grepCommits("fix", 10, first).commits())
                .extracting(found -> found.commit().message())
                .containsExactly("fix one");
    }

    /**
     * Truncated means there may be more matches: one found past the limit says so, a walk that
     * reached the root does not — even when it found exactly as many as asked for, and even when
     * older commits that match nothing lie behind them.
     */
    @Test
    void grepCommitsSaysWhetherHistoryWasWalkedToItsEnd() {
        writeFile("a.txt", "a\n");
        commitAll("fix one");
        writeFile("b.txt", "b\n");
        commitAll("fix two");
        writeFile("c.txt", "c\n");
        commitAll("unrelated");

        assertThat(service.grepCommits("fix", 1, null).truncated()).isTrue();
        // A limit filled by the newest match, with only non-matching history after it, is whole.
        assertThat(service.grepCommits("unrelated", 1, null).truncated()).isFalse();
        assertThat(service.grepCommits("fix", 2, null).truncated()).isFalse();
        assertThat(service.grepCommits("nothing-like-this", 2, null).truncated())
                .isFalse();
    }

    /**
     * The model's search: the description is searched either way, but only comes back when asked
     * for — choosing a commit needs its subject, and bodies run to thousands of characters.
     */
    @Test
    void searchCommitLogSearchesTheDescriptionButReturnsItOnlyWhenAsked() {
        writeFile("a.txt", "a\n");
        commitAll("Subject line" + System.lineSeparator() + System.lineSeparator() + "mentions ZZZ");

        assertThat(service.searchCommitLog("zzz", 10, null, false).commits())
                .singleElement()
                .satisfies(c -> assertThat(c.body()).isNull());
        assertThat(service.searchCommitLog("zzz", 10, null, true).commits())
                .singleElement()
                .satisfies(c -> assertThat(c.body()).isEqualTo("mentions ZZZ"));
    }

    @Test
    void searchCommitLogWithAPathKeepsOnlyCommitsThatTouchedIt() {
        writeFile("src/a.txt", "a\n");
        commitAll("fix in src");
        writeFile("docs/b.txt", "b\n");
        commitAll("fix in docs");

        assertThat(service.searchCommitLog("fix", 10, "src", false).commits())
                .extracting(GitCommit::message)
                .containsExactly("fix in src");
    }

    @Test
    void grepCommitsFromAnUnknownRevisionIsRefused() {
        writeFile("a.txt", "a\n");
        commitAll("Only commit");

        assertThatThrownBy(() -> service.grepCommits("only", 10, "nosuchtag"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Имя не совпало — совпасть может путь: так берут файл с частым именем, назвав его каталог.
     * Слэш в запросе при этом не обязателен, буквы подряд читаются через границы сегментов.
     */
    @Test
    void fileSearchFallsBackToTheWholePath() {
        writeFile("service/git/GitService.java", "a\n");
        commitAll();

        assertThat(service.searchFiles("servicegit", 5))
                .extracting(GitFileNode::path)
                .containsExactly("service/git/GitService.java");
    }

    /**
     * Путь длиннее имени и набирает очки уже поэтому: границ слов в нём больше. Значит имя
     * выигрывает у пути местом в порядке, а не счётом, — иначе каталог с удачным названием вытеснял
     * бы файл, который именно так и называется.
     */
    @Test
    void aNameHitOutranksAPathHitWhateverTheScore() {
        writeFile("git.md", "a\n");
        writeFile("git/tools/verylongfilename.md", "b\n");
        commitAll();

        assertThat(service.searchFiles("git", 5))
                .extracting(GitFileNode::path)
                .containsExactly("git.md", "git/tools/verylongfilename.md");
    }

    /**
     * Файл, который git помнит, а рабочего дерева уже нет: индекс пропускает такой путь, и до этого
     * отказа доходит само чтение с диска. Это ошибка запроса — контроллер переводит её в 400, — а
     * не поломка сервера: спросить о только что удалённом файле может кто угодно, и страница с
     * картинкой запрашивает его сама, без единого клика.
     */
    @Test
    void aTrackedFileDeletedFromTheWorkingTreeIsRefusedAsABadRequest() {
        writeFile("docs/icon.svg", "<svg/>\n");
        commitAll();
        deleteFile("docs/icon.svg");

        assertThatThrownBy(() -> service.getRawFile(null, "docs/icon.svg"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("docs/icon.svg");
        assertThatThrownBy(() -> service.getFileContent("docs/icon.svg")).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Имя, которое нельзя назвать обратно в API, из листингов выпадает целиком: и из дерева, и из
     * поиска. Показанный, но не открывающийся файл — отказ в ответ на клик, и объяснить его
     * пользователю нечем.
     */
    @Test
    void aFileWhoseNameCannotBeNamedBackIsNotOffered() {
        writeFile("docs/plain.md", "a\n");
        writeFile("docs/a\"b.md", "b\n");
        commitAll();

        assertThat(service.getFileTree(null, "docs"))
                .extracting(GitFileNode::path)
                .containsExactly("docs/plain.md");
        assertThat(service.searchFiles("md", 5)).extracting(GitFileNode::path).containsExactly("docs/plain.md");
    }
}
