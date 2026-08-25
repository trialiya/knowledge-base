package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Where the working tree sits, against real repositories built with the {@code git} binary — a
 * clone with a remote among them, because the ahead/behind counters only exist there and they are
 * the whole point of the indicator this feeds.
 */
class GitBranchesTest {

    /** The repository the panel shows — a clone, so it has an upstream to drift from. */
    @TempDir Path repoDir;

    /** What it was cloned from; stands in for the remote. */
    @TempDir Path originDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        git(originDir, "init", "-q", "-b", "main");
        configure(originDir);
        write(originDir, "README.md", "origin\n");
        commit(originDir, "first");

        git(repoDir, "clone", "-q", originDir.toString(), ".");
        configure(repoDir);
        service = TestProjects.gitService(repoDir, false);
    }

    @Test
    void aFreshCloneIsOnItsBranchAndLevelWithTheUpstream() {
        GitBranchStatus status = service.branchStatus();

        assertThat(status.current()).isEqualTo("main");
        assertThat(status.detached()).isFalse();
        assertThat(status.unborn()).isFalse();
        assertThat(status.upstream()).isEqualTo("origin/main");
        assertThat(status.ahead()).isZero();
        assertThat(status.behind()).isZero();
        assertThat(status.branches()).containsExactly("main");
    }

    @Test
    void ownCommitsCountAsAhead() {
        write(repoDir, "local.md", "mine\n");
        commit(repoDir, "local work");

        assertThat(service.branchStatus().ahead()).isEqualTo(1);
        assertThat(service.branchStatus().behind()).isZero();
    }

    /**
     * Главное про счётчик «позади»: он считается по refs на диске, и до fetch'а чужой коммит его не
     * двигает. Это и делает fetch осмысленной кнопкой рядом с ним, а не украшением.
     */
    @Test
    void whatTheRemoteGainedShowsUpAsBehindOnlyAfterAFetch() {
        write(originDir, "theirs.md", "theirs\n");
        commit(originDir, "their work");

        assertThat(service.branchStatus().behind()).isZero();

        GitCommandResult result = service.fetch();

        assertThat(result.command()).isEqualTo("fetch");
        assertThat(result.status().behind()).isEqualTo(1);
        assertThat(service.branchStatus().behind()).isEqualTo(1);
    }

    @Test
    void everyLocalBranchIsListedAndTheCurrentOneIsNamed() {
        git(repoDir, "branch", "feature/x");
        git(repoDir, "switch", "-q", "feature/x");

        GitBranchStatus status = service.branchStatus();

        assertThat(status.current()).isEqualTo("feature/x");
        assertThat(status.branches()).containsExactly("feature/x", "main");
        // Своя ветка ничего не отслеживает — счётчикам не от чего считать, и upstream пуст.
        assertThat(status.upstream()).isNull();
        assertThat(status.ahead()).isZero();
        assertThat(status.behind()).isZero();
    }

    /** Detached HEAD — не ветка: панель обязана сказать это, а не показать хэш как имя ветки. */
    @Test
    void aDetachedHeadIsReportedAsSuchWithAnAbbreviatedCommit() {
        git(repoDir, "switch", "-q", "--detach", "HEAD");

        GitBranchStatus status = service.branchStatus();

        assertThat(status.detached()).isTrue();
        assertThat(status.current()).hasSize(7);
        assertThat(status.upstream()).isNull();
    }

    /** Свежий `git init`: ветка есть по имени, но коммитов нет — ни списка, ни счётчиков. */
    @Test
    void anUnbornBranchIsNeitherDetachedNorAheadOfAnything(@TempDir Path emptyRepo) {
        git(emptyRepo, "init", "-q", "-b", "main");
        configure(emptyRepo);

        GitBranchStatus status = TestProjects.gitService(emptyRepo, false).branchStatus();

        assertThat(status.current()).isEqualTo("main");
        assertThat(status.unborn()).isTrue();
        assertThat(status.detached()).isFalse();
        assertThat(status.branches()).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void configure(Path dir) {
        git(dir, "config", "user.email", "test@example.com");
        git(dir, "config", "user.name", "Test");
    }

    private static void write(Path dir, String name, String content) {
        try {
            Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void commit(Path dir, String message) {
        git(dir, "add", "-A");
        git(dir, "commit", "-q", "-m", message);
    }

    private static void git(Path dir, String... args) {
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
