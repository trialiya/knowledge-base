package io.github.trialiya.kb.service.script;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.service.OutlineService;
import io.github.trialiya.kb.tools.RunCancellation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Stopping the chat has to stop the script too.
 *
 * <p>{@code ChatRunService.cancel} disposes the Reactor stream, which ends the response but does
 * nothing to a tool already running — invisible for a git read that returns in milliseconds, not
 * for a script with seconds of budget left. And with {@code Thread.stop} gone since JDK 20, "stop
 * it" only exists as the engine's own cancellation, which is what these tests pin.
 */
class ScriptCancellationTest {

    @TempDir Path repoDir;

    private ScriptRunner runner;

    @BeforeEach
    void setUp() {
        runGit("init", "-q");
        runGit("config", "user.email", "test@example.com");
        runGit("config", "user.name", "Test");
        GitProperties gitProperties = new GitProperties(repoDir.toString(), false);
        GitService gitService = new GitService(gitProperties, new OutlineService());
        ScriptProperties properties =
                new ScriptProperties(
                        true,
                        false,
                        null,
                        null,
                        null,
                        null,
                        // A minute of budget: whatever stops the script below, it is not the
                        // timeout.
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(1),
                        Duration.ofMillis(20),
                        null,
                        null,
                        null);
        runner =
                new ScriptRunner(
                        gitService,
                        null,
                        properties,
                        new ScriptEditPolicy(gitProperties, properties, gitService));
    }

    @Test
    void stopsARunawayScriptWhenTheRunIsCancelled() throws Exception {
        AtomicBoolean stopRequested = new AtomicBoolean();
        RunCancellation cancellation = new RunCancellation(stopRequested);
        AtomicBoolean finished = new AtomicBoolean();
        List<Throwable> thrown = new ArrayList<>();

        Thread script =
                Thread.ofVirtual()
                        .start(
                                () -> {
                                    try {
                                        runner.run("while (true) {}", null, cancellation);
                                    } catch (Throwable e) {
                                        thrown.add(e);
                                    } finally {
                                        finished.set(true);
                                    }
                                });

        Thread.sleep(200);
        assertThat(finished).isFalse();

        stopRequested.set(true);
        script.join(Duration.ofSeconds(5));

        assertThat(finished).isTrue();
        assertThat(thrown).singleElement().isInstanceOf(ScriptCancelledException.class);
    }

    @Test
    void aCancelledRunNeverReportsBackToTheModel() {
        RunCancellation cancellation = new RunCancellation(new AtomicBoolean(true));

        // Already cancelled before the first poll — the script must not come back as a result the
        // tool loop would feed to a model that is no longer listening.
        assertThatThrownBy(() -> runner.run("while (true) {}", null, cancellation))
                .isInstanceOf(ScriptCancelledException.class);
    }

    private void runGit(String... args) {
        try {
            var command = new ArrayList<String>();
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
            if (exit != 0) {
                throw new IllegalStateException(
                        "git " + String.join(" ", args) + " failed: " + output);
            }
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Failed to run git command", e);
        }
    }
}
