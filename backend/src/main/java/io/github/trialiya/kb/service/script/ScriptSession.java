package io.github.trialiya.kb.service.script;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.script.ScriptStats;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.util.AntPathMatcher;

/**
 * State of a single {@code runScript} call: what the script is allowed to see, what it has already
 * spent, and what it logged. One instance per run, never shared — the counters are the run's
 * budget, not a global rate limit.
 *
 * <p>Together with the tracked-files rule in {@code GitService} this is the whole of "restricted
 * file access" — but only the visibility half of it is authorisation. The counters are not: what a
 * script reads never reaches the model on its own, so they meter the backend (see {@code
 * ScriptProperties.Limits}), and they are set to catch a runaway loop rather than to make a
 * repository-wide pass fail.
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

    /**
     * Files whose real current text this run has been shown — everything in {@link #filesRead},
     * plus every file a {@code kb.grep} returned a match from. What {@link #requireRead} checks.
     *
     * <p>Separate from {@link #filesRead} because the two answer different questions. {@code
     * filesRead} is consumption: which files were read, reported back and charged. This is
     * evidence: a grep match is a line of the file exactly as it stands on disk, which is all the
     * edit rule ever wanted. Insisting on a whole read as well made the common case — grep for a
     * symbol, then replace it everywhere — pay for the file twice while granting the script
     * strictly more freedom than the grep line it actually used.
     */
    private final Set<String> filesSeen = new LinkedHashSet<>();

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

    /**
     * Encoded size of each pending file, and their running total. Kept alongside the text rather
     * than recomputed: a script may rewrite one file many times, and re-encoding every pending file
     * on each of those writes is quadratic in exactly the case the byte budget exists to survive.
     */
    private final Map<String, Integer> pendingSize = new LinkedHashMap<>();

    private long pendingBytes;

    private long bytesRead;
    private int calls;
    private int logChars;

    /**
     * Memoized results of read-only {@code kb.*} calls, keyed on the call's own arguments. Safe for
     * the run's whole lifetime because the snapshot it reads from cannot move under it: nothing
     * this run writes reaches disk until it finishes (see {@code ScriptRunner}), and nothing else
     * writes to the working tree while it runs. See {@link #cached}.
     */
    private final Map<List<Object>, Object> cache = new HashMap<>();

    public ScriptSession(ScriptProperties properties) {
        this.limits = properties.limits();
        this.denyGlobs = properties.denyGlobs();
        this.allowGlobs = properties.allowGlobs();
    }

    /**
     * Runs {@code compute} the first time this run asks for {@code key}, and hands back the same
     * result — without touching {@code GitService}, {@code DocumentService} or any budget — every
     * time after. {@code key} is the call's own arguments, so two calls collide here exactly when
     * they would have returned the same thing anyway.
     *
     * <p>This exists because a script that compares many files against many names naturally writes
     * the file loop <em>inside</em> the name loop — read the whole set again for every name — and
     * that pattern used to spend one {@code kb.*} call per repetition for work that produced
     * nothing new after the first pass. It is not a workaround for that pattern; it is what makes
     * the distinction between "asked something new" and "asked the same thing again" real, so a
     * script shaped the natural way is not the one punished for it.
     *
     * <p>Only a successful call is memoized. {@code compute} throwing — a missing file, a bad regex
     * — is not cached, so retrying after a fix pays for a real attempt rather than replaying the
     * failure.
     */
    @SuppressWarnings("unchecked")
    public <T> T cached(List<Object> key, Supplier<T> compute) {
        if (cache.containsKey(key)) {
            return (T) cache.get(key);
        }
        T value = compute.get();
        cache.put(key, value);
        return value;
    }

    /**
     * Charges one {@code kb.*} call. The backstop for a loop that does new work on every iteration
     * — a distinct file, a distinct search — which would otherwise only be stopped by the
     * wall-clock timeout. A call answered from {@link #cached} never reaches here, because it never
     * repeats work in the first place.
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
        // Both budgets are checked before anything is recorded, so a refused write leaves the run's
        // pending state exactly as it was — the counters a failed run reports describe what it
        // actually staged, not what it was stopped from staging.
        int size = text.getBytes(StandardCharsets.UTF_8).length;
        long total = pendingBytes - pendingSize.getOrDefault(path, 0) + size;
        long max = limits.maxEditedBytes().toBytes();
        if (total > max) {
            throw new ScriptLimitExceededException(
                    "Budget exceeded: maxEditedBytes="
                            + max
                            + " bytes per run, and nothing has been written to disk. Make smaller"
                            + " edits, or split the work across two runScript calls.");
        }

        pendingText.put(path, text);
        pendingSize.put(path, size);
        pendingBytes = total;
        if (created) {
            pendingCreates.add(path);
        }
    }

    /**
     * Refuses an edit to a file whose current text the script has not been shown. The same rule the
     * {@code editFile} tool enforces through {@code ToolInvocationCollector} — which cannot see
     * inside a script, since {@code kb.read} is not a tool call — so the session keeps its own
     * record. What satisfies it is a read <em>or</em> a grep match: see {@link #filesSeen}.
     */
    public void requireRead(String path) {
        // A file this run already wrote needs no read: the script authored its content, which is
        // the whole point of the rule. Without this, create-then-edit in one script is impossible.
        if (pendingText.containsKey(path)) {
            return;
        }
        // Both sides are canonical — the caller normalises before it asks (see KbScriptApi), and
        // filesSeen holds the paths GitService reported back.
        if (!filesSeen.contains(path)) {
            throw new IllegalArgumentException(
                    "Refusing to edit "
                            + path
                            + ": the script has not looked at it. Call kb.read(path) first (a line"
                            + " range is enough), or take oldString from a kb.grep match in this"
                            + " file, so the edit is made against real current content.");
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

    /** Records that a {@code kb.grep} match came from this file — evidence, not consumption. */
    public void noteSeen(String path) {
        filesSeen.add(path);
    }

    /** Books a completed read against the per-run file-count and byte budgets. */
    public void chargeRead(String path, long bytes) {
        filesSeen.add(path);
        boolean newFile = filesRead.add(path);
        if (newFile && filesRead.size() > limits.maxFilesRead()) {
            throw new ScriptLimitExceededException(
                    "Budget exceeded: maxFilesRead="
                            + limits.maxFilesRead()
                            + " files per run. Narrow the file list before reading (kb.grep with a"
                            + " glob, or kb.files with a tighter pattern).");
        }
        chargeBytes(bytes, "Read line ranges (kb.read(path, from, to)) instead of whole files.");
    }

    /**
     * Books the text a search returned against the byte budget, without counting the files it came
     * from as read — a match line is not the file.
     *
     * <p>It is charged at all because a search returns file <em>content</em>, and content is what
     * {@code maxBytesRead} exists to bound. Left unmetered, a loop of matching searches was the one
     * way left to pull an unbounded amount of the repository into a script's memory while every
     * other budget stayed comfortably unspent.
     */
    public void chargeSearch(long bytes) {
        chargeBytes(
                bytes,
                "Narrow the search (a glob, a longer pattern, fewer context lines) or ask for"
                        + " fewer matches with {max: N}.");
    }

    /**
     * Books the snippets a document search returned, for the same reason as {@link #chargeSearch}:
     * {@code kb.searchDocs} hands back document <em>text</em>, and text left unmetered is a way to
     * fill a script — and then the model's context — while every other budget stays unspent.
     */
    public void chargeDocSearch(long bytes) {
        chargeBytes(bytes, "Ask for fewer hits: kb.searchDocs(query, N).");
    }

    private void chargeBytes(long bytes, String advice) {
        bytesRead += bytes;
        long maxBytes = limits.maxBytesRead().toBytes();
        if (bytesRead > maxBytes) {
            throw new ScriptLimitExceededException(
                    "Budget exceeded: maxBytesRead=" + maxBytes + " bytes per run. " + advice);
        }
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
