package io.github.trialiya.kb.service.file.git;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

/**
 * One read-only {@code git} subprocess, bounded in time and in output: the part of a shell-out
 * that is the same whatever git is asked ({@code grep}, {@code blame}) — the deadline kept by a
 * watchdog, stderr drained so git never blocks on a full pipe, the output cut at a line ceiling.
 * What the exit code and the lines mean is the caller's to decide, since that differs per command
 * ({@code git grep} exits 1 for "no match", {@code git blame} for nothing).
 */
@Slf4j
final class GitReadProcess {

    /**
     * Lines of git's stderr kept — read past, never stopped at. Only a refusal is ever read out of
     * them, and that is the first {@code fatal:}; the rest are warnings kept for the log, and a
     * walk that warns about every directory it cannot open should not be able to fill memory with
     * them.
     */
    private static final int MAX_STDERR_LINES = 200;

    /** How long the stderr drain is waited for once git itself has exited. */
    private static final Duration STDERR_DRAIN_WAIT = Duration.ofSeconds(1);

    private final Path root;
    private final Duration timeout;

    /**
     * @param root the working directory git runs in — the repository root
     * @param timeout how long one run may take; named in the timeout's message, the budget itself
     *     is the deadline handed to {@link #run}
     */
    GitReadProcess(Path root, Duration timeout) {
        this.root = root;
        this.timeout = timeout;
    }

    Duration timeout() {
        return timeout;
    }

    /** {@link System#nanoTime()} a run started now has to finish by. */
    long deadline() {
        return System.nanoTime() + timeout.toNanos();
    }

    /**
     * What one run left behind.
     *
     * @param lines stdout, at most the ceiling the caller named
     * @param cut whether git was stopped at that ceiling — then {@code exit} says only that git was
     *     killed, and the lines are all the caller gets
     * @param exit git's exit code, meaningful only when not {@code cut}
     * @param stderr what git had to say, up to {@link #MAX_STDERR_LINES}
     */
    record Output(List<String> lines, boolean cut, int exit, List<String> stderr) {

        /** Everything git said on stderr, as one message for a log line or an exception. */
        String said() {
            return String.join("\n", stderr);
        }
    }

    /**
     * Runs {@code command} and reads at most {@code maxLines} of what it prints: past that git is
     * stopped and the lines already in hand are the answer, since the caller has no use for the
     * rest.
     *
     * @param deadline {@link System#nanoTime()} past which the run is killed; shared between runs
     *     that make up one answer, see {@link #deadline()}
     * @throws GitReadTimeoutException if git did not answer by {@code deadline}
     * @throws IllegalStateException if the process could not be started or was interrupted
     */
    Output run(List<String> command, int maxLines, long deadline) {
        long budget = deadline - System.nanoTime();
        if (budget <= 0) {
            throw timedOut(command);
        }
        try {
            // core.quotepath=false: without it, git quotes/octal-escapes any path containing
            // non-ASCII bytes (e.g. Cyrillic filenames) in its output — "docs/проект" becomes
            // "\"docs/\\320\\277...\"", which breaks path parsing.
            List<String> withConfig = new ArrayList<>(command.size() + 2);
            withConfig.add(command.get(0));
            withConfig.add("-c");
            withConfig.add("core.quotepath=false");
            withConfig.addAll(command.subList(1, command.size()));

            // stderr is kept apart from stdout, not merged into it: a warning git prints on the
            // way — an unreadable directory during a walk — would otherwise be parsed as output.
            ProcessBuilder pb = new ProcessBuilder(withConfig).directory(root.toFile());
            Process process = pb.start();
            // Nobody reads stderr until git is done, and a full pipe would stop it mid-run, so it
            // is drained as it comes; what git has to say about a refusal fits in the cap many
            // times over.
            List<String> complaints = new CopyOnWriteArrayList<>();
            Thread stderrDrain = Thread.ofVirtual().start(() -> {
                try (var err =
                        new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = err.readLine()) != null) {
                        // Read on past the cap and keep only what fits:
                        // stopping would leave the pipe to fill and git
                        // blocked on it, which is what draining prevents.
                        if (complaints.size() < MAX_STDERR_LINES) {
                            complaints.add(line);
                        }
                    }
                } catch (IOException e) {
                    // The stream dies with the process this side killed —
                    // whatever git was saying is moot by then.
                    log.debug("Reading git stderr ended early", e);
                }
            });
            // The read below blocks until git closes its output, so the deadline is kept by a
            // watchdog that kills the process; the read then ends and waitFor sees the signal.
            AtomicBoolean timedOut = new AtomicBoolean();
            Thread watchdog = Thread.ofVirtual().start(() -> {
                try {
                    if (!process.waitFor(budget, TimeUnit.NANOSECONDS)) {
                        timedOut.set(true);
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            List<String> lines = new ArrayList<>();
            boolean cut = false;
            try (var reader =
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = readLine(reader)) != null) {
                    lines.add(line);
                    if (lines.size() >= maxLines) {
                        // Enough. Whatever git still has to say would be thrown away, so it is
                        // not read; the kill is what ends git, not a full pipe.
                        cut = true;
                        process.destroyForcibly();
                        break;
                    }
                }
            }
            int exit = process.waitFor();
            watchdog.interrupt();
            // git is gone, so its stderr is at end of stream and the drain is about to finish; the
            // wait is bounded all the same rather than trusting that of a thread nothing depends
            // on.
            awaitDrain(stderrDrain);
            // Killed by this side with the answer in hand: the exit code says only that, and so
            // does the watchdog if the deadline fell on the same instant.
            if (!cut && timedOut.get()) {
                log.warn("Git command killed after {}: {}", timeout, command);
                throw timedOut(command);
            }
            return new Output(lines, cut, exit, List.copyOf(complaints));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git command interrupted: " + command, e);
        } catch (IOException e) {
            throw new IllegalStateException("Git command failed: " + command, e);
        }
    }

    /**
     * One line of git's output, ended by {@code \n} alone — a trailing {@code \r} (a CRLF file)
     * is dropped, a bare {@code \r} inside the line is kept. {@link BufferedReader#readLine} ends
     * a line at a bare {@code \r} too, and both commands print file content verbatim: a source
     * line holding one would come back as two, the second without the prefix that marks it as
     * content, to be read as something else by the parser.
     *
     * @return the line without its terminator, or {@code null} at end of stream
     */
    private static @Nullable String readLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = reader.read()) >= 0) {
            if (c == '\n') {
                int end = line.length();
                if (end > 0 && line.charAt(end - 1) == '\r') {
                    line.setLength(end - 1);
                }
                return line.toString();
            }
            line.append((char) c);
        }
        return line.isEmpty() ? null : line.toString();
    }

    /**
     * Waits out {@link #STDERR_DRAIN_WAIT} for the stderr drain, in as many {@code join}s as it
     * takes: a single one can return before its timeout, and what the drain has not read by then is
     * everything git said about a refusal — the whole of the message the caller is owed.
     */
    private static void awaitDrain(Thread drain) throws InterruptedException {
        long deadline = System.nanoTime() + STDERR_DRAIN_WAIT.toNanos();
        long left;
        while (drain.isAlive() && (left = deadline - System.nanoTime()) > 0) {
            drain.join(Duration.ofNanos(left));
        }
    }

    private GitReadTimeoutException timedOut(List<String> command) {
        return new GitReadTimeoutException(command.get(0) + " " + command.get(1) + " did not finish within "
                + timeout.toSeconds() + "s: " + String.join(" ", command));
    }
}
