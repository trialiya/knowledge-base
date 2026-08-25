package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.model.git.dto.GitBranchStatus;
import io.github.trialiya.kb.model.git.dto.GitCommandResult;
import io.github.trialiya.kb.support.TestProjects;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The commands a user runs from the panel, against a real repository.
 *
 * <p>What is tested here is mostly what these commands <em>refuse</em> to do: the whole feature
 * turns on a switch never overwriting uncommitted work, a pop never losing a stash it could not
 * apply, and a commit never being signed with an identity nobody configured.
 */
class GitCommandsTest {

    @TempDir Path repoDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        git("init", "-q", "-b", "main");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        write("README.md", "one\n");
        git("add", "-A");
        git("commit", "-q", "-m", "first");
        service = TestProjects.gitService(repoDir, false);
    }

    // ── switch ───────────────────────────────────────────────────────────────

    @Test
    void switchingCreatesAndMovesTheCheckout() {
        GitCommandResult created = service.switchBranch("feature/x", true);

        assertThat(created.command()).isEqualTo("switch -c feature/x");
        assertThat(created.status().current()).isEqualTo("feature/x");
        assertThat(service.switchBranch("main", false).status().current()).isEqualTo("main");
    }

    /**
     * Главное правило фичи: переключение не сносит незакоммиченную работу. Отказ обязан назвать
     * файлы — по ним пользователь и решает, спрятать их в stash или закоммитить.
     */
    @Test
    void aSwitchThatWouldOverwriteLocalChangesIsRefusedAndNamesThem() {
        service.switchBranch("feature/x", true);
        write("README.md", "on the branch now\n");
        git("commit", "-q", "-am", "branch change");
        service.switchBranch("main", false);
        write("README.md", "uncommitted work\n");

        assertThatThrownBy(() -> service.switchBranch("feature/x", false))
                .isInstanceOf(GitCommandFailedException.class)
                .hasMessageContaining("README.md")
                .hasMessageContaining("stash");

        // И работа осталась на месте — ради этого отказ и существует.
        assertThat(read("README.md")).isEqualTo("uncommitted work\n");
    }

    @Test
    void anUnknownBranchAndAnUnusableNameAreBothRefused() {
        assertThatThrownBy(() -> service.switchBranch("nope", false))
                .isInstanceOf(GitCommandFailedException.class)
                .hasMessageContaining("No such branch");
        assertThatThrownBy(() -> service.switchBranch("-rf", true))
                .isInstanceOf(GitCommandFailedException.class)
                .hasMessageContaining("cannot start with '-'");
        assertThatThrownBy(() -> service.switchBranch("a b", true))
                .isInstanceOf(GitCommandFailedException.class)
                .hasMessageContaining("Not a valid branch name");
        assertThatThrownBy(() -> service.switchBranch("  ", true))
                .isInstanceOf(GitCommandFailedException.class);
    }

    // ── stash ────────────────────────────────────────────────────────────────

    @Test
    void stashingPutsChangesAsideAndPoppingBringsThemBack() {
        write("README.md", "work in progress\n");

        service.stashPush();
        assertThat(read("README.md")).isEqualTo("one\n");
        assertThat(service.branchStatus().dirty()).isFalse();

        service.stashPop();
        assertThat(read("README.md")).isEqualTo("work in progress\n");
        assertThat(service.branchStatus().dirty()).isTrue();
    }

    @Test
    void thereIsNothingToStashOrPopWhenNothingChanged() {
        assertThatThrownBy(() -> service.stashPush())
                .isInstanceOf(GitCommandFailedException.class)
                .hasMessageContaining("No local changes");
        assertThatThrownBy(() -> service.stashPop())
                .isInstanceOf(GitCommandFailedException.class)
                .hasMessageContaining("stash is empty");
    }

    /** Не применившийся stash обязан остаться stash'ем: иначе работа исчезнет насовсем. */
    @Test
    void aPopThatConflictsKeepsTheStash() {
        write("README.md", "stashed content\n");
        service.stashPush();
        write("README.md", "conflicting content here\n");

        assertThatThrownBy(() -> service.stashPop())
                .isInstanceOf(GitCommandFailedException.class)
                .hasMessageContaining("conflict");

        // Прятанное на месте — вернуть его можно, как только дерево освободят.
        git("checkout", "--", "README.md");
        service.stashPop();
        assertThat(read("README.md")).isEqualTo("stashed content\n");
    }

    // ── commit ───────────────────────────────────────────────────────────────

    @Test
    void committingRecordsTheTrackedChanges() {
        write("README.md", "two lines now\n");

        GitCommandResult result = service.commit("second");

        assertThat(result.output()).startsWith("Committed ");
        assertThat(service.branchStatus().dirty()).isFalse();
        assertThat(service.getCommitLog(1, null).getFirst().message()).isEqualTo("second");
    }

    @Test
    void aCommitNeedsAMessageAndSomethingToRecord() {
        assertThatThrownBy(() -> service.commit("  "))
                .isInstanceOf(GitCommandFailedException.class)
                .hasMessageContaining("needs a message");
        assertThatThrownBy(() -> service.commit("empty"))
                .isInstanceOf(GitCommandFailedException.class)
                .hasMessageContaining("Nothing to commit");
    }

    // Отказ коммитить без user.name/user.email проверяется только вручную: JGit читает и
    // глобальный конфиг, а он на машине сборки задан — изолировать его в тесте значило бы
    // подменять SystemReader, то есть тестировать JGit, а не нас.

    // ── discard ──────────────────────────────────────────────────────────────

    @Test
    void discardingRestoresOneFileToItsCommittedState() {
        write("other.md", "kept\n");
        git("add", "-A");
        git("commit", "-q", "-m", "add other");
        write("README.md", "changed content\n");
        write("other.md", "also changed\n");

        service.discard("README.md");

        assertThat(read("README.md")).isEqualTo("one\n");
        // Сосед не тронут: команда берёт путь именно поэтому.
        assertThat(read("other.md")).isEqualTo("also changed\n");
    }

    @Test
    void discardingAnUntrackedFileIsRefusedRatherThanDeletingIt() {
        write("scratch.txt", "mine\n");

        assertThatThrownBy(() -> service.discard("scratch.txt"))
                .isInstanceOf(GitCommandFailedException.class);
        assertThat(repoDir.resolve("scratch.txt")).exists();
    }

    @Test
    void aPathThatLeavesTheRepositoryIsRefused() {
        assertThatThrownBy(() -> service.discard("../outside.txt"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * A directory resolves against HEAD too — to a tree, not a blob — and would otherwise restore
     * everything beneath it at once. Discard takes one file.
     */
    @Test
    void discardingADirectoryIsRefusedRatherThanRestoringEverythingUnderIt() {
        Path sub = repoDir.resolve("dir");
        write("dir/kept.md", "kept\n");
        git("add", "-A");
        git("commit", "-q", "-m", "add dir");
        write("dir/kept.md", "changed\n");

        assertThatThrownBy(() -> service.discard("dir"))
                .isInstanceOf(GitCommandFailedException.class)
                .hasMessageContaining("directory");
        assertThat(read("dir/kept.md")).isEqualTo("changed\n");
        assertThat(sub).exists();
    }

    // ── switch ───────────────────────────────────────────────────────────────

    /**
     * {@code checkout().setName()} resolves through {@code findRef}, which also matches remote and
     * tag refs — switching to one would silently detach HEAD instead of refusing.
     */
    @Test
    void switchingToARemoteTrackingRefIsRefusedRatherThanDetaching() {
        git("branch", "feature/x");
        git("update-ref", "refs/remotes/origin/feature/x", "refs/heads/feature/x");

        assertThatThrownBy(() -> service.switchBranch("origin/feature/x", false))
                .isInstanceOf(GitCommandFailedException.class)
                .hasMessageContaining("No such branch");
        assertThat(service.branchStatus().detached()).isFalse();
    }

    // ── commit ───────────────────────────────────────────────────────────────

    /** A commit made on a detached HEAD is reachable from nothing after the next switch. */
    @Test
    void committingOnADetachedHeadIsRefused() {
        git("switch", "-q", "--detach", "HEAD");
        write("README.md", "changed\n");

        assertThatThrownBy(() -> service.commit("message"))
                .isInstanceOf(GitCommandFailedException.class)
                .hasMessageContaining("not on a branch");
    }

    // ── merge --abort ────────────────────────────────────────────────────────

    /** Конфликт после merge — не тупик: abort обязан вернуть дерево в рабочее состояние. */
    @Test
    void anAbortLeavesTheConflictedMergeAndTheStatusSaysSoWhileItLasts() {
        service.switchBranch("feature/x", true);
        write("README.md", "theirs side\n");
        git("commit", "-q", "-am", "theirs");
        service.switchBranch("main", false);
        write("README.md", "ours side\n");
        git("commit", "-q", "-am", "ours");
        git(repoDir, false, "merge", "feature/x");

        GitBranchStatus conflicted = service.branchStatus();
        assertThat(conflicted.merging()).isTrue();
        assertThat(conflicted.conflicts()).containsExactly("README.md");

        GitCommandResult aborted = service.abortMerge();

        assertThat(aborted.status().merging()).isFalse();
        assertThat(aborted.status().conflicts()).isEmpty();
        assertThat(read("README.md")).isEqualTo("ours side\n");
    }

    /**
     * A stopped rebase is not a merge: the banner offering "abort merge" would run {@code git merge
     * --abort}, which fails with "There is no merge to abort" and leaves the user exactly where
     * they were.
     */
    @Test
    void anInterruptedRebaseIsNotReportedAsAMergeAndCannotBeAbortedAsOne() {
        service.switchBranch("feature/x", true);
        write("README.md", "theirs side\n");
        git("commit", "-q", "-am", "theirs");
        service.switchBranch("main", false);
        write("README.md", "ours side\n");
        git("commit", "-q", "-am", "ours");
        git(repoDir, false, "rebase", "feature/x");

        assertThat(service.branchStatus().merging()).isFalse();
        assertThatThrownBy(() -> service.abortMerge())
                .isInstanceOf(GitCommandFailedException.class)
                .hasMessageContaining("no merge in progress");

        git(repoDir, false, "rebase", "--abort");
    }

    @Test
    void abortingWithoutAMergeIsRefused() {
        assertThatThrownBy(() -> service.abortMerge())
                .isInstanceOf(GitCommandFailedException.class)
                .hasMessageContaining("no merge in progress");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void write(String name, String content) {
        try {
            Path file = repoDir.resolve(name);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private String read(String name) {
        try {
            return Files.readString(repoDir.resolve(name), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void git(String... args) {
        git(repoDir, true, args);
    }

    private static void git(Path dir, String... args) {
        git(dir, true, args);
    }

    /**
     * @param requireSuccess false для команд, чей ненулевой код и есть ожидаемый исход (merge с
     *     конфликтом)
     */
    private static void git(Path dir, boolean requireSuccess, String... args) {
        try {
            String[] command = new String[args.length + 1];
            command[0] = "git";
            System.arraycopy(args, 0, command, 1, args.length);
            Process process =
                    new ProcessBuilder(command)
                            .directory(dir.toFile())
                            .redirectErrorStream(true)
                            .start();
            String output =
                    new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            if (requireSuccess && exit != 0) {
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
