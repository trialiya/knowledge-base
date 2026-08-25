package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitCommandResult;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;

/**
 * The git commands a <b>user</b> runs on one repository from the UI. One instance per project,
 * owned by {@link GitService} the way {@link GitWriter} is — and reached only through {@code
 * GitRegistry.requireGitCommands}, which is where the project's permission is checked.
 *
 * <p>The model never gets here: no {@code @Tool} calls these, and nothing about them is in the
 * system prompt. Each is a person's explicit action in the interface.
 *
 * <p><b>Why a subprocess.</b> The reads and the local operations are JGit's, in-process, typed —
 * see {@link GitBranches}. What runs here instead is the part that talks to a remote, and it does
 * so through the system {@code git} precisely because of the credentials: an ssh agent, a
 * credential helper and whatever {@code insteadOf} rewriting the deployment configured are the
 * host's, and JGit would have to be taught each of them separately. There is no shell — {@link
 * ProcessBuilder} takes an argv array — and no argument here comes from a user: this is a fixed
 * verb, not a command line.
 *
 * <p><b>One at a time.</b> Every command writes something: a fetch moves refs, the operations to
 * come move the working tree. Two of them overlapping would interleave those writes, and the second
 * caller would read a state neither command produced — so a project runs one at a time and a
 * request that finds one running is refused rather than queued behind it.
 */
@Slf4j
class GitCommands {

    /**
     * How long a network command may run before it is killed. Long enough for a first fetch of a
     * large repository over a slow link, short enough that a hung connection frees the project's
     * lock within a page's patience rather than holding it until the server restarts.
     */
    private static final long TIMEOUT_SECONDS = 120;

    /**
     * How long the output is still collected after the process is gone. Only ever the tail already
     * in the pipe, so this is a guard against a stuck reader thread rather than a wait.
     */
    private static final long OUTPUT_DRAIN_SECONDS = 5;

    /** Output beyond this is cut: it goes to a panel, and git's progress can be long. */
    private static final int MAX_OUTPUT_CHARS = 4000;

    private final Path root;
    private final GitBranches branches;

    /**
     * Held for the whole command. Never waited on: a caller that finds it taken is told the
     * repository is busy, because the alternative is a request that blocks for the timeout above
     * while the user watches a spinner for someone else's fetch.
     */
    private final ReentrantLock lock = new ReentrantLock();

    GitCommands(Path root, GitBranches branches) {
        this.root = root;
        this.branches = branches;
    }

    /**
     * @see GitService#fetch()
     */
    GitCommandResult fetch() {
        // --prune: a branch deleted on the remote otherwise stays in the list forever, and the
        // list is what the branch picker offers. --no-tags keeps a fetch from dragging in every
        // tag of a large repository, which is not what "refresh what I can pull" means.
        return run("fetch", List.of("git", "fetch", "--prune", "--no-tags"));
    }

    /**
     * Runs one command in the repository, refusing rather than waiting when the project is busy.
     *
     * @param name the command as it is reported back — the verb, not the argv
     */
    private GitCommandResult run(String name, List<String> argv) {
        if (!lock.tryLock()) {
            throw new GitBusyException(
                    "Another git command is running for this repository — try again in a moment");
        }
        try {
            String output = exec(name, argv);
            return new GitCommandResult(name, output, branches.status());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs {@code argv} in the repository root and returns its output, stderr included — git says
     * everything worth showing there, progress and refusals alike.
     *
     * @throws GitCommandFailedException when git exits non-zero; the output is the message, because
     *     git's own wording ("could not read Username", "Permission denied (publickey)") is what
     *     tells the operator what to fix
     */
    private String exec(String name, List<String> argv) {
        ProcessBuilder builder =
                new ProcessBuilder(argv).directory(root.toFile()).redirectErrorStream(true);
        // There is no terminal here and nothing to type into one. Without this git waits for a
        // username on a repository whose credentials the deployment did not provide — and waits
        // out the whole timeout below instead of saying so in a second.
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        // The same for ssh, which asks on its own tty rather than through git. Only when the
        // deployment configured no command of its own: overriding that would undo the very
        // credentials this subprocess exists to reuse.
        builder.environment()
                .putIfAbsent("GIT_SSH_COMMAND", "ssh -o BatchMode=yes -o StrictHostKeyChecking=no");

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot run git " + name, e);
        }
        // Read on a thread of our own, and wait on the process rather than on end-of-output: a
        // git that hangs holds its pipe open, and a read that owned the waiting would sit there
        // long past the timeout it is supposed to enforce.
        var reading =
                CompletableFuture.supplyAsync(
                        () -> {
                            try (var reader =
                                    new BufferedReader(
                                            new InputStreamReader(
                                                    process.getInputStream(),
                                                    StandardCharsets.UTF_8))) {
                                return truncate(reader.lines().toList());
                            } catch (IOException e) {
                                log.warn("Cannot read the output of git {}", name, e);
                                return "";
                            }
                        });
        try {
            // Nothing will ever be written to it, and a git left waiting on its stdin is a git
            // that never exits.
            process.getOutputStream().close();
        } catch (IOException e) {
            log.debug("Cannot close the stdin of git {}", name, e);
        }
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new GitCommandFailedException(
                        "git " + name + " did not finish in " + TIMEOUT_SECONDS + "s");
            }
            String output = reading.get(OUTPUT_DRAIN_SECONDS, TimeUnit.SECONDS);
            int exit = process.exitValue();
            if (exit != 0) {
                log.warn("git {} exited {} in {}: {}", name, exit, root, output);
                throw new GitCommandFailedException(
                        output.isBlank() ? "git " + name + " failed (exit " + exit + ")" : output);
            }
            return output;
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Cannot read the output of git " + name, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("git " + name + " was interrupted", e);
        } finally {
            process.destroy();
            reading.cancel(true);
        }
    }

    private static String truncate(List<String> lines) {
        String text = String.join("\n", lines).strip();
        return text.length() <= MAX_OUTPUT_CHARS
                ? text
                : text.substring(0, MAX_OUTPUT_CHARS) + "\n… (output truncated)";
    }
}
