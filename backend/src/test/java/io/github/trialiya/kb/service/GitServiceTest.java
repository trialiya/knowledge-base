package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.model.git.dto.FileEntryType;
import io.github.trialiya.kb.model.git.dto.GitCommit;
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

    @TempDir Path repoDir;

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
            Process process =
                    new ProcessBuilder(command)
                            .directory(repoDir.toFile())
                            .redirectErrorStream(true)
                            .start();
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (failOnError && exit != 0) {
                throw new IllegalStateException(
                        "git " + String.join(" ", args) + " failed: " + output);
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

        List<GitFileNode> root = service.getFileTree(null);

        // Regression: without core.quotepath=false, git quotes/octal-escapes the Cyrillic path,
        // which used to split into a bogus quoted "docs" node distinct from the real one.
        List<GitFileNode> docsNodes = root.stream().filter(n -> n.name().equals("docs")).toList();
        assertThat(docsNodes).hasSize(1);
        assertThat(docsNodes.get(0).path()).isEqualTo("docs");
        assertThat(docsNodes.get(0).type()).isEqualTo(FileEntryType.DIRECTORY);

        List<GitFileNode> underDocs = service.getFileTree("docs");
        assertThat(underDocs).hasSize(1);
        assertThat(underDocs.get(0).path()).isEqualTo("docs/проект");
        assertThat(underDocs.get(0).type()).isEqualTo(FileEntryType.DIRECTORY);

        List<GitFileNode> underProject = service.getFileTree("docs/проект");
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

        List<GitFileNode> root = service.getFileTree(null);

        assertThat(root.stream().map(GitFileNode::name).toList())
                .containsExactly("Bird", "zebra", "Apple.txt", "banana.txt");
    }

    @Test
    void subPathIsNeverInterpretedAsGitOption() {
        writeFile("tracked.txt", "tracked");
        commitAll();
        // Not added/committed — must never surface via ls-files, tracked or not.
        writeFile("untracked.txt", "untracked");

        // Without a "--" pathspec separator, "--others" would be parsed by `git ls-files` as the
        // --others *option* (list untracked files too) rather than a literal pathspec, leaking
        // untracked.txt. With the separator it's just a (non-existent) directory name.
        List<GitFileNode> result = service.getFileTree("--others");

        assertThat(result).isEmpty();
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

    @Test
    void rootCommitDiffIncludesTheInitialFiles() {
        writeFile("a.txt", "line1\nline2\n");
        commitAll();

        List<GitCommit> log = service.getCommitLog(10, null);
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
        Files.writeString(
                repoDir.resolve("a.txt"), "line1\nline2\nline3\n", StandardCharsets.UTF_8);

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

        List<GitCommit> log = service.getCommitLog(1, null);
        List<GitDiffEntry> files = service.getCommitDiff(log.get(0).hash(), false).get(0).files();

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

        List<GitCommit> log = service.getCommitLog(1, null);
        List<GitDiffEntry> files = service.getCommitDiff(log.get(0).hash(), false).get(0).files();

        // Both new files are byte-identical, so which one the detector promotes to RENAME (vs
        // COPY) is an arbitrary internal choice — assert the pair, not the assignment.
        assertThat(files).extracting(GitDiffEntry::status).containsExactlyInAnyOrder("R", "C");
        assertThat(files)
                .extracting(GitDiffEntry::path)
                .containsExactlyInAnyOrder("kept.txt", "extra.txt");
        // The copy must carry its source path, same as the rename.
        assertThat(files)
                .extracting(GitDiffEntry::oldPath)
                .containsExactly("origin.txt", "origin.txt");
    }

    @Test
    void windowsBackslashInSubPathIsNormalizedToForwardSlash() {
        writeFile("src/main/Foo.java", "class Foo {}");
        commitAll();

        // Windows callers may pass "src\\main" — must be treated identically to "src/main".
        List<GitFileNode> nodes = service.getFileTree("src\\main");
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
        commitAll();

        // A backslash path must still find the commit that touched the file.
        var log = service.getCommitLog(10, "src\\main\\Bar.java");
        assertThat(log).hasSize(1);
    }

    @Test
    void windowsBackslashInCommitDiffFilePathIsNormalized() {
        writeFile("src/main/Baz.java", "class Baz {}\n");
        commitAll();
        String hash = service.getCommitLog(1, null).get(0).hash();

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
        Files.write(
                repoDir.resolve("crlf.txt"), "line1\r\nline2\r\n".getBytes(StandardCharsets.UTF_8));

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
        assertThat(service.getFileTree(null))
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

        var view = service.browsePath("src/main/java/com/app/Main.java", true);

        assertThat(view.type()).isEqualTo(FileEntryType.FILE);
        assertThat(view.file()).isNotNull();
        assertThat(view.file().content()).contains("class Main");
        assertThat(view.nodes()).isNull();

        // The whole chain root → parent comes back at once: that is what saved the file browser
        // one round trip per level of nesting.
        assertThat(view.tree())
                .extracting(GitTreeLevel::path)
                .containsExactly(
                        "",
                        "src",
                        "src/main",
                        "src/main/java",
                        "src/main/java/com",
                        "src/main/java/com/app");
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

        var view = service.browsePath("docs/guide", true);

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

        var view = service.browsePath("docs/guide/intro.md", false);

        assertThat(view.type()).isEqualTo(FileEntryType.FILE);
        assertThat(view.file()).isNotNull();
        assertThat(view.tree()).isEmpty();
    }

    @Test
    void browsePathReportsAnUnknownPathAsMissingInsteadOfFailing() {
        writeFile("docs/guide/intro.md", "intro\n");
        commitAll();

        var view = service.browsePath("docs/guide/gone.md", true);

        // A dead deep link must render as "not found", not as a load error.
        assertThat(view.type()).isNull();
        assertThat(view.file()).isNull();
        assertThat(view.nodes()).isNull();
        // The tree still expands as far as the path exists, so the user sees where it broke.
        assertThat(view.tree())
                .extracting(GitTreeLevel::path)
                .containsExactly("", "docs", "docs/guide");
    }

    @Test
    void browsePathWithoutPathOpensTheRepositoryRoot() {
        writeFile("docs/guide/intro.md", "intro\n");
        writeFile("README.md", "readme\n");
        commitAll();

        var view = service.browsePath(null, true);

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
        var view = service.browsePath("src/main/java/com/app/Main.java", true);
        for (GitTreeLevel level : view.tree()) {
            assertThat(level.nodes()).isEqualTo(service.getFileTree(level.path()));
        }
    }

    @Test
    void browsePathHandlesWindowsBackslashesAndTrailingSlashes() {
        writeFile("src/main/Foo.java", "class Foo {}\n");
        commitAll();

        assertThat(service.browsePath("src\\main\\Foo.java", true).type())
                .isEqualTo(FileEntryType.FILE);

        var dir = service.browsePath("src/main/", true);
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

        assertThat(service.searchCommits("PLACEHOLD", 10))
                .extracting(GitCommit::message)
                .containsExactly("Add parsing for placeholders");
    }

    @Test
    void searchCommitsMatchesHashPrefix() {
        writeFile("a.txt", "a\n");
        commitAll("Only commit");
        String hash = service.getCommitLog(1, null).get(0).hash();

        assertThat(service.searchCommits(hash.substring(0, 6).toUpperCase(), 10))
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

        assertThat(service.searchCommits("fix", 2))
                .extracting(GitCommit::message)
                .containsExactly("fix three", "fix two");
    }

    @Test
    void searchCommitsReturnsEmptyWhenNothingMatches() {
        writeFile("a.txt", "a\n");
        commitAll("Only commit");

        assertThat(service.searchCommits("nothing-like-this", 10)).isEmpty();
    }

    /** A hash prefix must not be confused with a body hit: the picker only shows the subject. */
    @Test
    void searchCommitsIgnoresTheMessageBody() {
        writeFile("a.txt", "a\n");
        commitAll(
                "Subject line" + System.lineSeparator() + System.lineSeparator() + "mentions zzz");

        assertThat(service.searchCommits("zzz", 10)).isEmpty();
        assertThat(service.searchCommits("Subject", 10)).hasSize(1);
    }
}
