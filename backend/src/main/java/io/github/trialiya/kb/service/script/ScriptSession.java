package io.github.trialiya.kb.service.script;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.script.ScriptStats;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.util.AntPathMatcher;

/**
 * State of a single {@code runScript} call: what the script is allowed to see, what it has already
 * spent, and what it logged. One instance per run, never shared — the counters are the run's
 * budget, not a global rate limit.
 *
 * <p>Together with the tracked-files rule in {@code GitService} this is the whole of "restricted
 * file access". Authorisation alone is not enough: a script confined to tracked files can still
 * pull the entire repository into the model's context, so every read is metered as well.
 */
public final class ScriptSession {

    /**
     * Ant semantics, not {@code java.nio} glob: {@code **}{@code /*.pem} has to match a root-level
     * {@code x.pem} too, which the NIO matcher does not do (it requires at least one directory).
     * Ant is also what {@code pathGlob} looks like elsewhere in the tool surface.
     */
    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final ScriptProperties.Limits limits;
    private final List<String> denyGlobs;
    private final List<String> allowGlobs;

    private final Set<String> filesRead = new LinkedHashSet<>();
    private final List<String> log = new ArrayList<>();
    private final long startNanos = System.nanoTime();

    /**
     * Files the script has written, path → pending text, in first-write order. Nothing here has
     * touched disk: a script that edits twenty files and then throws on the twenty-first must leave
     * the working tree exactly as it found it, so writes are buffered until the run succeeds (see
     * {@code ScriptRunner}). Keeping the text — rather than a list of replacements to replay — is
     * also what makes a second edit of the same file behave the way the script expects.
     */
    private final Map<String, String> pendingText = new LinkedHashMap<>();

    /** Subset of {@link #pendingText} that does not exist yet and must be created, not replaced. */
    private final Set<String> pendingCreates = new LinkedHashSet<>();

    private long bytesRead;
    private int calls;
    private int logChars;

    public ScriptSession(ScriptProperties properties) {
        this.limits = properties.limits();
        this.denyGlobs = properties.denyGlobs();
        this.allowGlobs = properties.allowGlobs();
    }

    /**
     * Charges one {@code kb.*} call. The backstop for a loop that stays under every other budget —
     * re-reading an already-charged file, or grepping in circles — which would otherwise only be
     * stopped by the wall-clock timeout.
     */
    public void chargeCall() {
        if (++calls > limits.maxCalls()) {
            throw new ScriptLimitExceededException(
                    "Budget exceeded: maxCalls="
                            + limits.maxCalls()
                            + " kb.* calls per run. Do less work per script, or split the task"
                            + " across two runScript calls.");
        }
    }

    /**
     * Whether a path is visible to scripts at all. Applied to every path a script names
     * <em>and</em> to every path returned by a listing or a search, so a denied file cannot be
     * discovered by either route.
     */
    public boolean isVisible(String path) {
        for (String deny : denyGlobs) {
            if (MATCHER.match(deny, path)) {
                return false;
            }
        }
        if (allowGlobs.isEmpty()) {
            return true;
        }
        for (String allow : allowGlobs) {
            if (MATCHER.match(allow, path)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A hidden path is indistinguishable from a missing one — same message <em>and</em> same
     * exception type as {@code GitService} raises for an untracked file, so it also reaches the
     * model as {@code RUNTIME} rather than {@code BUDGET}. A script must not be able to probe
     * {@code kb.script.deny-globs} by comparing errors, and the model must not be told to narrow a
     * glob when the real answer is "that file does not exist".
     */
    public void requireVisible(String path) {
        if (!isVisible(path)) {
            throw new IllegalArgumentException("File not found: " + path);
        }
    }

    /** Rejects a file whose size alone blows the per-file budget, before any of it is read. */
    public void checkFileSize(String path, long sizeBytes) {
        long max = limits.maxFileBytes().toBytes();
        if (sizeBytes > max) {
            throw new ScriptLimitExceededException(
                    "Budget exceeded: maxFileBytes="
                            + max
                            + " bytes, but "
                            + path
                            + " is "
                            + sizeBytes
                            + ". Read a line range instead: kb.read(path, from, to).");
        }
    }

    // ── Writes (buffered until the run succeeds) ────────────────────────────

    /**
     * Current text of a file as the script sees it: its pending version if it has already been
     * written in this run, otherwise absent so the caller reads from disk.
     */
    public Optional<String> pendingText(String path) {
        return Optional.ofNullable(pendingText.get(path));
    }

    /**
     * Records the new text of a file, charging the write budgets. {@code created} marks a file that
     * does not exist yet, so the apply step knows to create rather than replace it.
     */
    public void stageWrite(String path, String text, boolean created) {
        boolean newFile = !pendingText.containsKey(path);
        if (newFile && pendingText.size() + 1 > limits.maxEditedFiles()) {
            throw new ScriptLimitExceededException(
                    "Budget exceeded: maxEditedFiles="
                            + limits.maxEditedFiles()
                            + " files per run, and nothing has been written to disk. Edit fewer"
                            + " files per script, or split the work across two runScript calls.");
        }
        pendingText.put(path, text);
        if (created) {
            pendingCreates.add(path);
        }

        long total = 0;
        for (String pending : pendingText.values()) {
            total += pending.getBytes(StandardCharsets.UTF_8).length;
        }
        long max = limits.maxEditedBytes().toBytes();
        if (total > max) {
            throw new ScriptLimitExceededException(
                    "Budget exceeded: maxEditedBytes="
                            + max
                            + " bytes per run, and nothing has been written to disk. Make smaller"
                            + " edits, or split the work across two runScript calls.");
        }
    }

    /**
     * Refuses an edit to a file the script has not looked at. The same rule the {@code editFile}
     * tool enforces through {@code ToolInvocationCollector} — which cannot see inside a script,
     * since {@code kb.read} is not a tool call — so the session keeps its own record.
     */
    public void requireRead(String path) {
        // filesRead holds paths as GitService normalised them; the script passes its own spelling.
        if (!filesRead.contains(path.strip().replace('\\', '/'))) {
            throw new IllegalArgumentException(
                    "Refusing to edit "
                            + path
                            + ": the script has not read it. Call kb.read(path) first (a line range"
                            + " is enough) so the edit is made against real current content.");
        }
    }

    /** Files written in this run, path → final text, in first-write order. */
    public Map<String, String> pendingWrites() {
        return Map.copyOf(pendingText);
    }

    /** Order in which pending writes must be applied — {@link #pendingWrites} is unordered. */
    public List<String> pendingWriteOrder() {
        return List.copyOf(pendingText.keySet());
    }

    public boolean isPendingCreate(String path) {
        return pendingCreates.contains(path);
    }

    /** Books a completed read against the per-run file-count and byte budgets. */
    public void chargeRead(String path, long bytes) {
        boolean newFile = filesRead.add(path);
        if (newFile && filesRead.size() > limits.maxFilesRead()) {
            throw new ScriptLimitExceededException(
                    "Budget exceeded: maxFilesRead="
                            + limits.maxFilesRead()
                            + " files per run. Narrow the file list before reading (kb.grep with a"
                            + " glob, or kb.files with a tighter pattern).");
        }
        bytesRead += bytes;
        long maxBytes = limits.maxBytesRead().toBytes();
        if (bytesRead > maxBytes) {
            throw new ScriptLimitExceededException(
                    "Budget exceeded: maxBytesRead="
                            + maxBytes
                            + " bytes per run. Read line ranges (kb.read(path, from, to)) instead"
                            + " of whole files.");
        }
    }

    /** Caps how many matches one {@code kb.grep} call may ask for. */
    public int cappedGrepLimit(Integer requested) {
        int max = limits.maxGrepMatches();
        if (requested == null || requested <= 0) {
            return max;
        }
        return Math.min(requested, max);
    }

    /** Appends a {@code kb.log} line, silently dropping the overflow once the budget is spent. */
    public void log(String text) {
        if (logChars >= limits.maxLogChars()) {
            return;
        }
        int room = limits.maxLogChars() - logChars;
        if (text.length() > room) {
            log.add(text.substring(0, room) + "…(log truncated)");
            logChars = limits.maxLogChars();
            return;
        }
        log.add(text);
        logChars += text.length();
    }

    public List<String> logLines() {
        return List.copyOf(log);
    }

    public List<String> filesRead() {
        return List.copyOf(filesRead);
    }

    public ScriptStats stats() {
        return new ScriptStats(
                filesRead.size(),
                bytesRead,
                calls,
                pendingText.size(),
                (System.nanoTime() - startNanos) / 1_000_000);
    }
}
