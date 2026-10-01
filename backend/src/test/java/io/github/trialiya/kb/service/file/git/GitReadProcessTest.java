package io.github.trialiya.kb.service.file.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The line ceiling of a git run: what counts as cut is what the search's {@code truncated} is built
 * on, so output exactly as long as the ceiling must read as complete — only a line past it says
 * git had more.
 */
class GitReadProcessTest {

    @TempDir
    Path repoDir;

    private GitReadProcess git;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        for (String subject : List.of("one", "two", "three")) {
            runGit("commit", "-q", "--allow-empty", "-m", subject);
        }
        git = new GitReadProcess(repoDir, Duration.ofSeconds(20));
    }

    @Test
    void outputExactlyAtTheCeilingIsNotCut() {
        GitReadProcess.Output out = git.run(List.of("git", "log", "--format=%s"), 3, git.deadline());

        assertThat(out.cut()).isFalse();
        assertThat(out.lines()).containsExactly("three", "two", "one");
    }

    @Test
    void outputPastTheCeilingIsCutAtIt() {
        GitReadProcess.Output out = git.run(List.of("git", "log", "--format=%s"), 2, git.deadline());

        assertThat(out.cut()).isTrue();
        assertThat(out.lines()).containsExactly("three", "two");
    }

    private void runGit(String... args) {
        try {
            List<String> command = new ArrayList<>();
            command.add("git");
            command.addAll(List.of(args));
            Process process = new ProcessBuilder(command)
                    .directory(repoDir.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IllegalStateException("git " + String.join(" ", args) + ": " + output);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
