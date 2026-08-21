package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.script.ScriptStats;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
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
import org.jspecify.annotations.Nullable;

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

    private final ScriptProperties.Limits limits;

    /**
     * The chat-response session's tool history, if this run has one — every tool call the model
     * made before this script, regardless of which tool made it. Consulted by {@link #requireRead}
     * so a file the model already looked at through another tool — or through an earlier script in
     * the same response — does not have to be re-read with {@code kb.read} just to satisfy this
     * run's own bookkeeping. Null for a run with no such session (background jobs, tests): then
     * only what this run itself read or grepped counts.
     */
    private final @Nullable ToolInvocationCollector priorInvocations;

    /**
     * The id this run actually reads and writes (resolved by {@code ScriptRunner} before the
     * session is built). What {@link #requireRead} passes to {@link
     * ToolInvocationCollector#hasSeenFile} — a read of the same path in a different project must
     * not satisfy this run's own read-before-overwrite rule.
     */
    private final String project;

    /**
     * Files whose content this run was actually handed. Reported back in {@code
     * ScriptResult.filesRead}, which is serialised into the tool result and read again by {@code
     * ToolInvocationCollector.hasSeenFile} — so a path lands here only when the script really was
     * shown what is in the file, never merely because the backend opened it (see {@link
     * #chargeScan}).
     */
    private final Set<String> filesRead = new LinkedHashSet<>();

    /**
     * Every file this run made the backend read, whether or not its content was handed over — what
     * {@code maxFilesRead} counts. A digest still costs a full pass over the file, so it has to be
     * bounded by something; it just must not be reported as a read.
     */
    private final Set<String> filesTouched = new LinkedHashSet<>();

    private final List<String> log = new ArrayList<>();
    private final long startNanos = System.nanoTime();

    /**
     * Files the script has written, path → pending write, in first-write order. Nothing here has
     * touched disk: a script that edits twenty files and then throws on the twenty-first must leave
     * the working tree exactly as it found it, so writes are buffered until the run succeeds (see
     * {@code ScriptRunner}). Keeping the text — rather than a list of replacements to replay — is
     * also what makes a second edit of the same file behave the way the script expects.
     */
    private final Map<String, PendingWrite> pending = new LinkedHashMap<>();

    private long pendingBytes;

    private long bytesRead;
    private int calls;
    private int logChars;

    /**
     * Memoized results of read-only {@code kb.*} calls, keyed on the call's own arguments. Safe for
     * the run's whole lifetime: nothing this run writes reaches disk until it finishes (see {@code
     * ScriptRunner}), so a script cannot read back its own staged edits either way. A concurrent
     * writer — another run applying its edits, the {@code editFile} tool — is not excluded, but
     * runs are not serialised against one another to begin with: without the cache such a run would
     * see a torn mix of before and after, and with it, the first answer for a given call. See
     * {@link #call}.
     */
    private final Map<List<Object>, Object> cache = new HashMap<>();

    /**
     * @param priorInvocations the chat-response session's tool history, or null when this run has
     *     none (background jobs, tests) — see {@link #priorInvocations}.
     * @param project the id this run reads and writes — see {@link #project}.
     */
    public ScriptSession(
            ScriptProperties properties,
            @Nullable ToolInvocationCollector priorInvocations,
            String project) {
        this.limits = properties.limits();
        this.priorInvocations = priorInvocations;
        this.project = project;
    }

    // ── Calls (charged once, then answered from the run's cache) ─────────────

    /**
     * One read-only {@code kb.*} call: charged against {@link #chargeCall} and run the first time
     * this run asks for {@code key}, then handed back — without touching {@code GitService}, {@code
     * DocumentService} or any budget — every time after. {@code key} is the call's own arguments,
     * so two calls collide here exactly when they would have returned the same thing anyway.
     *
     * <p>Charging lives here rather than in each caller so that "a repeat costs nothing" is
     * structural: the only calls that spend budget are the ones that reach {@code compute}.
     *
     * <p>The cache exists because a script that compares many files against many names naturally
     * writes the file loop <em>inside</em> the name loop — read the whole set again for every name
     * — and that pattern used to spend one {@code kb.*} call per repetition for work that produced
     * nothing new after the first pass. It is not a workaround for that pattern; it is what makes
     * the distinction between "asked something new" and "asked the same thing again" real, so a
     * script shaped the natural way is not the one punished for it.
     *
     * <p>Only a successful call is memoized. {@code compute} throwing — a missing file, a bad regex
     * — is not cached, so retrying after a fix pays for a real attempt rather than replaying the
     * failure.
     */
    @SuppressWarnings("unchecked")
    public <T> T call(List<Object> key, Supplier<T> compute) {
        if (cache.containsKey(key)) {
            return (T) cache.get(key);
        }
        chargeCall();
        T value = compute.get();
        cache.put(key, value);
        return value;
    }

    /**
     * Charges one {@code kb.*} call that is not memoizable ({@code kb.log}, the write methods); the
     * memoized ones are charged by {@link #call}. The backstop for a loop that does new work on
     * every iteration — a distinct file, a distinct search — which would otherwise only be stopped
     * by the wall-clock timeout.
     */
    public void chargeCall() {
        if (++calls > limits.maxCalls()) {
            throw budgetExceeded(
                    "maxCalls",
                    limits.maxCalls(),
                    "kb.* calls per run. Do less work per script, or split the task across two"
                            + " runScript calls.");
        }
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    /** Books a completed read against the per-run file-count and byte budgets. */
    public void chargeRead(String path, long bytes) {
        filesRead.add(path);
        countFile(path);
        chargeBytes(bytes, "Read line ranges (kb.read(path, from, to)) instead of whole files.");
    }

    /**
     * Books a pass over a file that handed the script none of its content — {@code kb.hash}, which
     * turns a file of any size into 64 characters, a byte window that landed past the end of the
     * file, and the read {@code kb.edit} makes to match {@code oldString} against.
     *
     * <p>Counted against the file budget because the backend really did read the file; charged no
     * bytes because none were handed over; and kept out of {@link #filesRead}, because neither a
     * digest nor an empty window nor the pass an edit makes over a file is the content being handed
     * over. Keeping them out is what stops this run from overwriting a file whole that it never
     * looked at (see {@link #requireRead}) — and stops it saying otherwise to the rest of the
     * response, since {@code filesRead} is reported back and {@code ToolInvocationCollector} reads
     * it as evidence for a later tool call.
     */
    public void chargeScan(String path) {
        countFile(path);
    }

    private void countFile(String path) {
        boolean newFile = filesTouched.add(path);
        if (newFile && filesTouched.size() > limits.maxFilesRead()) {
            throw budgetExceeded(
                    "maxFilesRead",
                    limits.maxFilesRead(),
                    "files per run. Narrow the file list before reading (kb.grep with a glob, or"
                            + " kb.files with a tighter pattern).");
        }
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
            throw budgetExceeded("maxBytesRead", maxBytes, "bytes per run. " + advice);
        }
    }

    /** Files the script was shown the content of — not every file the run made the backend open. */
    public List<String> filesRead() {
        return List.copyOf(filesRead);
    }

    // ── Writes (buffered until the run succeeds) ────────────────────────────

    /**
     * A file this run has written, as it will be applied once the script finishes successfully.
     *
     * <p>Text and bytes stay apart all the way to the apply step rather than collapsing into one
     * byte array: a text write is applied through the line-diffing path that produces the diff the
     * user reviews, and a binary one has no such diff to produce. Which of the two a file is staged
     * as is also what {@code kb.edit} consults before it agrees to edit it as text.
     */
    public sealed interface PendingWrite {

        String path();

        /** The file does not exist yet and must be created, not replaced. */
        boolean created();

        /**
         * Encoded size of the content, carried rather than recomputed: a script may rewrite one
         * file many times, and re-encoding every pending file on each of those writes is quadratic
         * in exactly the case the byte budget exists to survive.
         */
        int sizeBytes();
    }

    /** A file staged as text — {@code kb.edit} / {@code kb.create}. */
    public record TextWrite(String path, String text, boolean created, int sizeBytes)
            implements PendingWrite {}

    /** A file staged as raw bytes — {@code kb.writeBytes} / {@code kb.createBytes}. */
    public record BinaryWrite(String path, byte[] bytes, boolean created, int sizeBytes)
            implements PendingWrite {}

    /**
     * How this run has already written {@code path}, or empty if it has not — so a caller can both
     * see the file as the script now has it and tell text from bytes.
     */
    public Optional<PendingWrite> pending(String path) {
        return Optional.ofNullable(pending.get(path));
    }

    /** Records the new text of an existing file, charging the write budgets. */
    public void stageEdit(String path, String text) {
        stage(new TextWrite(path, text, false, text.getBytes(StandardCharsets.UTF_8).length));
    }

    /**
     * As {@link #stageEdit}, for a file that does not exist yet — so the apply step knows to create
     * rather than replace it. Editing a created file afterwards leaves it a creation.
     */
    public void stageCreate(String path, String text) {
        stage(new TextWrite(path, text, true, text.getBytes(StandardCharsets.UTF_8).length));
    }

    /** As {@link #stageEdit}, for content written as raw bytes. */
    public void stageBinaryEdit(String path, byte[] bytes) {
        stage(new BinaryWrite(path, bytes, false, bytes.length));
    }

    /** As {@link #stageCreate}, for content written as raw bytes. */
    public void stageBinaryCreate(String path, byte[] bytes) {
        stage(new BinaryWrite(path, bytes, true, bytes.length));
    }

    /** The largest a single write may be: the whole write budget, spent on one file. */
    public long maxWriteBytes() {
        return limits.maxEditedBytes().toBytes();
    }

    private void stage(PendingWrite write) {
        PendingWrite previous = pending.get(write.path());
        if (previous == null && pending.size() + 1 > limits.maxEditedFiles()) {
            throw budgetExceeded(
                    "maxEditedFiles",
                    limits.maxEditedFiles(),
                    "files per run, and nothing has been written to disk. Edit fewer files per"
                            + " script, or split the work across two runScript calls.");
        }
        // Both budgets are checked before anything is recorded, so a refused write leaves the run's
        // pending state exactly as it was — the counters a failed run reports describe what it
        // actually staged, not what it was stopped from staging.
        long total =
                pendingBytes - (previous == null ? 0 : previous.sizeBytes()) + write.sizeBytes();
        long max = maxWriteBytes();
        if (total > max) {
            throw budgetExceeded(
                    "maxEditedBytes",
                    max,
                    "bytes per run, and nothing has been written to disk. Make smaller edits, or"
                            + " split the work across two runScript calls.");
        }

        pending.put(
                write.path(), previous != null && previous.created() ? asCreation(write) : write);
        pendingBytes = total;
    }

    /**
     * A write of a file this run created earlier is still a creation, whatever it is written as.
     */
    private static PendingWrite asCreation(PendingWrite write) {
        if (write.created()) {
            return write;
        }
        return switch (write) {
            case TextWrite text -> new TextWrite(text.path(), text.text(), true, text.sizeBytes());
            case BinaryWrite bin -> new BinaryWrite(bin.path(), bin.bytes(), true, bin.sizeBytes());
        };
    }

    /**
     * Refuses a whole-content write ({@code kb.writeBytes}) to a file this response has not looked
     * at. Fragment edits ({@code kb.edit}) do not come here: their exact-match contract is evidence
     * of its own, while a byte write carries none — which is why this is the only guard such a
     * write has.
     *
     * <p>What counts as having looked is not limited to <em>this</em> script: a read this run made
     * (see {@link #filesRead}), or a read/edit call made anywhere else in the same chat-response
     * session (see {@link #priorInvocations}), both count.
     */
    public void requireRead(String path) {
        // A file this run already wrote needs no read: the script authored its content, which is
        // the whole point of the rule. Without this, create-then-edit in one script is impossible.
        if (pending.containsKey(path)) {
            return;
        }
        // Both sides are canonical — the caller normalises before it asks (see KbScriptApi), and
        // filesRead holds the paths GitService reported back.
        if (filesRead.contains(path)) {
            return;
        }
        if (priorInvocations != null && priorInvocations.hasSeenFile(path, project)) {
            return;
        }
        throw new IllegalArgumentException(
                "Refusing to overwrite "
                        + path
                        + ": the script has not looked at it. Read it first (kb.readBytes, or"
                        + " kb.read for a text file — a range is enough), so the write is made"
                        + " against a file whose current content is known.");
    }

    /** Files written in this run, in the order they must be applied — first-write order. */
    public List<PendingWrite> pendingWrites() {
        return List.copyOf(pending.values());
    }

    // ── Output ──────────────────────────────────────────────────────────────

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

    public ScriptStats stats() {
        return new ScriptStats(
                filesRead.size(),
                bytesRead,
                calls,
                pending.size(),
                (System.nanoTime() - startNanos) / 1_000_000);
    }

    /**
     * Every budget refusal in one shape: the limit as {@code ScriptProperties.Limits} names it, the
     * value it was configured to, and what the model should do differently. The name is what the
     * model matches on — the guide it was given lists these — so it stays verbatim.
     */
    private static ScriptLimitExceededException budgetExceeded(
            String limit, Object max, String advice) {
        return new ScriptLimitExceededException(
                "Budget exceeded: " + limit + "=" + max + " " + advice);
    }
}
