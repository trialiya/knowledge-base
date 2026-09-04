package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.model.git.dto.GitCommit;
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
 * What a push would publish — the list the push dialog shows before the push runs.
 *
 * <p>Needs a second repository because the whole answer is about remote-tracking refs: a bare one
 * next to the working copy is what a real {@code origin} is here, and no network is involved.
 */
class GitOutgoingTest {

    @TempDir Path repoDir;

    @TempDir Path remoteDir;

    private GitService service;

    @BeforeEach
    void setUp() {
        git(remoteDir, "init", "-q", "--bare", "-b", "main");
        git("init", "-q", "-b", "main");
        git("config", "user.email", "test@example.com");
        git("config", "user.name", "Test");
        write("README.md", "one\n");
        git("add", "-A");
        git("commit", "-q", "-m", "first");
        git("remote", "add", "origin", remoteDir.toString());
        git("push", "-q", "-u", "origin", "main");
        service = TestProjects.gitService(repoDir, false);
    }

    @Test
    void aBranchInSyncWithItsUpstreamHasNothingToPublish() {
        assertThat(service.getOutgoingCommits(20)).isEmpty();
    }

    /** Ровно то же, что показывает счётчик «↑»: коммиты upstream..HEAD, свежие первыми. */
    @Test
    void commitsAheadOfTheUpstreamAreListedNewestFirst() {
        commit("second");
        commit("third");

        List<GitCommit> outgoing = service.getOutgoingCommits(20);

        assertThat(outgoing).extracting(GitCommit::message).containsExactly("third", "second");
        assertThat(service.branchStatus().ahead()).isEqualTo(outgoing.size());
    }

    /**
     * Ветку, созданную в панели, upstream ещё не отслеживает, и диапазон upstream..HEAD был бы пуст
     * — а push при этом опубликует настоящую работу. Показываем то, что он и отправит: коммиты,
     * которых нет ни в одной remote-ветке.
     */
    @Test
    void aBranchThatNeverWasPushedListsWhatTheFirstPushWouldSend() {
        git("switch", "-q", "-c", "feature/x");
        commit("on the new branch");

        assertThat(service.branchStatus().upstream()).isNull();
        assertThat(service.getOutgoingCommits(20))
                .extracting(GitCommit::message)
                .containsExactly("on the new branch");
    }

    @Test
    void aDetachedHeadHasNothingToPublish() {
        commit("second");
        git("checkout", "-q", "--detach", "HEAD");

        assertThat(service.getOutgoingCommits(20)).isEmpty();
    }

    private void commit(String message) {
        write("README.md", message + "\n");
        git("commit", "-q", "-am", message);
    }

    private void write(String name, String text) {
        try {
            Files.writeString(repoDir.resolve(name), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void git(String... args) {
        git(repoDir, args);
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
            int exit = process.waitFor();
            if (exit != 0) {
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
