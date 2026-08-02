package io.github.trialiya.kb.service.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.model.script.ScriptError;
import io.github.trialiya.kb.model.script.ScriptError.Kind;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.tools.RunCancellation;
import io.github.trialiya.kb.tools.ToolInvocationCollector;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Runs one script in a GraalJS context that has been denied everything except the injected {@code
 * kb} object.
 *
 * <p><b>Why an engine and not plain Java.</b> Restricting file access from inside the JVM is no
 * longer possible: the Security Manager is permanently disabled (JEP 486, JDK 24), and a runaway
 * computation cannot be stopped either, since {@code Thread.stop} was removed in JDK 20. Both
 * problems disappear when the untrusted code runs in a guest language: the context is built without
 * a filesystem at all, and {@link Context#close(boolean)} from another thread cancels execution.
 *
 * <p><b>The sandbox.</b> {@link IOAccess#NONE} means no {@code FileSystem} is attached; {@code
 * allowHostClassLookup(n -> false)} removes {@code Java.type}, so {@code java.io.File} and {@code
 * Runtime} cannot be named; {@code HostAccess.EXPLICIT} exposes only the {@code @HostAccess.Export}
 * methods of {@link KbScriptApi}. No threads, no processes, no native access, no environment. What
 * remains is ECMAScript plus {@code kb}.
 *
 * <p><b>Cancellation.</b> A watchdog thread polls the deadline and the run's {@link
 * RunCancellation} and closes the context under the running script. Cancellation lands when control
 * is next inside the guest, so a script blocked in a long host call (a {@code git grep} subprocess)
 * finishes that call first — the bound is the call, not the script.
 *
 * <p><b>What is not bounded: heap.</b> Graal's memory limits ({@code sandbox.MaxHeapMemory}) need
 * Oracle GraalVM; on the community engine this project ships with, there is no way to cap what a
 * script allocates. Time is bounded, and {@code kb.script.limits} bounds every byte the {@code kb}
 * methods hand in — but a script that generates its own data ({@code 'x'.repeat(1e10)}) can still
 * exhaust the backend's heap before the watchdog's next poll. That makes {@code kb.script.enabled}
 * a trust decision about the model, not only about what it can read: it is a denial-of-service
 * surface, not a path to anyone's files.
 */
@Slf4j
@Service
public class ScriptRunner {

    /**
     * The script body becomes a function body so that top-level {@code return} works — which is
     * what the handbook tells the model to write. The opening stays on line 1 <em>with</em> the
     * script's own first line, so reported error lines match the script the model sent.
     */
    private static final String PREFIX = "(function(){";

    private static final String SUFFIX = "\n})()";

    /** Guest-side JSON helpers, evaluated once per context (see {@link #stringify}). */
    private static final Source JSON_HELPERS =
            Source.newBuilder(
                            "js",
                            """
                            ({
                              result: function (x) { return x === undefined ? null : JSON.stringify(x); },
                              log: function (x) {
                                if (typeof x === 'string') return x;
                                if (x === undefined) return 'undefined';
                                var s = JSON.stringify(x);
                                return s === undefined ? String(x) : s;
                              }
                            })
                            """,
                            "kb-helpers.js")
                    .buildLiteral();

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GitService gitService;
    private final DocumentService documentService;
    private final ScriptProperties properties;
    private final ScriptEditPolicy editPolicy;

    /**
     * One engine, many contexts. The sandbox is a property of the context — each run still gets a
     * fresh one, with no filesystem and nothing shared with the run before it — while the engine
     * holds only what is identical every time: the loaded language and the parsed code cache. Given
     * one, building a context costs about a quarter of what it costs to stand up an engine too,
     * which is most of a short script's wall clock.
     *
     * <p>Closed with the application, and not before: a context outlives nothing here, but an
     * engine closed while a script is running would take that script down with it.
     */
    private final Engine engine =
            Engine.newBuilder("js")
                    // On a stock JDK there is no Graal compiler, so the engine warns once that it
                    // is interpreting. Expected here: scripts are glue code, and kb.script.limits
                    // keeps them small enough for interpretation to be irrelevant.
                    .option("engine.WarnInterpreterOnly", "false")
                    .build();

    public ScriptRunner(
            GitService gitService,
            DocumentService documentService,
            ScriptProperties properties,
            ScriptEditPolicy editPolicy) {
        this.gitService = gitService;
        this.documentService = documentService;
        this.properties = properties;
        this.editPolicy = editPolicy;
    }

    @PreDestroy
    void shutdown() {
        engine.close();
    }

    /**
     * Executes {@code script} and always returns a result — including for failures, because the
     * model's next move depends on <em>how</em> it failed.
     *
     * @param timeoutSeconds requested wall-clock budget; clamped to {@code kb.script.max-timeout},
     *     null for the configured default
     * @param cancellation the enclosing chat run's stop flag; {@link RunCancellation#none()} when
     *     there is no stoppable run behind the call
     * @throws ScriptCancelledException when the user stopped the run — the one failure that is not
     *     reported back to the model
     */
    public ScriptResult run(
            String script, @Nullable Integer timeoutSeconds, RunCancellation cancellation) {
        return run(script, timeoutSeconds, cancellation, false);
    }

    /**
     * As {@link #run(String, Integer, RunCancellation)}, but {@code forceReadOnly} withholds the
     * write methods regardless of {@code ScriptEditPolicy} — for the search sub-agent, which is
     * read-only by construction and must stay so in a deployment where the main chat may edit.
     */
    public ScriptResult run(
            String script,
            @Nullable Integer timeoutSeconds,
            RunCancellation cancellation,
            boolean forceReadOnly) {
        return run(script, timeoutSeconds, cancellation, forceReadOnly, null);
    }

    /**
     * As {@link #run(String, Integer, RunCancellation, boolean)}, but also hands the run's {@link
     * ScriptSession} the chat-response session's tool history, so {@code kb.edit}'s
     * read-before-edit check (see {@code ScriptSession#requireRead}) also honours a file the model
     * already looked at through another tool — or an earlier {@code runScript} call — in this same
     * response, not only what this one script itself read. Null when there is no such session
     * (background jobs, tests).
     */
    public ScriptResult run(
            String script,
            @Nullable Integer timeoutSeconds,
            RunCancellation cancellation,
            boolean forceReadOnly,
            @Nullable ToolInvocationCollector priorInvocations) {
        ScriptSession session = new ScriptSession(properties, priorInvocations);
        // Which object is bound IS the permission: with writes off, kb.edit does not exist.
        KbScriptApi api =
                editPolicy.enabled() && !forceReadOnly
                        ? new KbEditScriptApi(gitService, documentService, session)
                        : new KbScriptApi(gitService, documentService, session);
        Duration timeout = resolveTimeout(timeoutSeconds);
        long deadlineNanos = System.nanoTime() + timeout.toNanos();

        AtomicBoolean finished = new AtomicBoolean();
        AtomicReference<Kind> cancelReason = new AtomicReference<>();

        // Deliberately not try-with-resources: closing a context whose execution was cancelled
        // throws the cancellation again, which would replace the result this method just built.
        Context context = newContext();
        Thread watchdog =
                startWatchdog(context, cancellation, deadlineNanos, finished, cancelReason);
        try {
            Value helpers = context.eval(JSON_HELPERS);
            Value logFormatter = helpers.getMember("log");
            api.bindFormatter(value -> logFormatter.execute(value).asString());
            context.getBindings("js").putMember("kb", api);

            Value returned = context.eval(source(script));
            Object value = stringify(helpers.getMember("result"), returned, session);
            // Retire the watchdog before writing: the budget it enforces is the script's, and a
            // deadline landing mid-apply would mean a stop request that leaves files half written
            // instead of none. Idempotent with the finally below.
            stopWatchdog(finished, watchdog);
            // Only now, with the script finished and its result already converted, does anything
            // reach disk. Every earlier exit — a throw, a budget, a timeout, a user stop — leaves
            // the working tree exactly as the run found it.
            return success(value, session, applyPendingWrites(session));
        } catch (PolyglotException e) {
            return failure(e, session, cancelReason.get(), timeout);
        } catch (ScriptLimitExceededException e) {
            // A budget blown outside guest code (converting the return value, say).
            return failed(session, ScriptError.of(Kind.BUDGET, e.getMessage()));
        } catch (IllegalStateException e) {
            // The watchdog closed the context while this thread was between guest calls, so the
            // cancellation surfaces as "context is closed" rather than as a guest exception.
            if (cancelReason.get() == null) {
                throw e;
            }
            return stopped(session, cancelReason.get(), timeout);
        } finally {
            stopWatchdog(finished, watchdog);
            closeQuietly(context);
        }
    }

    // ── Sandbox ─────────────────────────────────────────────────────────────

    private Context newContext() {
        return Context.newBuilder("js")
                .engine(engine)
                // IOAccess.NONE, not the deprecated allowIO(false): same thing — no FileSystem is
                // attached — spelled the way the 23.0 API does.
                .allowIO(IOAccess.NONE)
                .allowHostClassLookup(className -> false)
                .allowHostAccess(HostAccess.EXPLICIT)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                .build();
    }

    private static Source source(String script) {
        return Source.newBuilder("js", PREFIX + script + SUFFIX, "script.js").buildLiteral();
    }

    private Thread startWatchdog(
            Context context,
            RunCancellation cancellation,
            long deadlineNanos,
            AtomicBoolean finished,
            AtomicReference<Kind> reason) {
        long pollMillis = Math.max(1, properties.cancelPoll().toMillis());
        return Thread.ofVirtual()
                .name("script-watchdog")
                .start(
                        () -> {
                            while (!finished.get()) {
                                if (cancellation.isStopRequested()) {
                                    reason.set(Kind.CANCELLED);
                                    closeQuietly(context);
                                    return;
                                }
                                if (System.nanoTime() - deadlineNanos >= 0) {
                                    reason.set(Kind.TIMEOUT);
                                    closeQuietly(context);
                                    return;
                                }
                                try {
                                    Thread.sleep(pollMillis);
                                } catch (InterruptedException e) {
                                    return; // the run finished on its own
                                }
                            }
                        });
    }

    /** Ends the watchdog's watch. Safe to call twice — the second call is a no-op. */
    private static void stopWatchdog(AtomicBoolean finished, Thread watchdog) {
        finished.set(true);
        watchdog.interrupt();
    }

    /** Cancels whatever the context is executing; a context already closing is not an error. */
    private static void closeQuietly(Context context) {
        try {
            context.close(true);
        } catch (RuntimeException e) {
            log.debug("Script context already closed", e);
        }
    }

    // ── Results ─────────────────────────────────────────────────────────────

    private ScriptResult success(
            @Nullable Object value, ScriptSession session, List<GitEditResult> edits) {
        return new ScriptResult(
                value, session.logLines(), session.stats(), null, session.filesRead(), edits);
    }

    private ScriptResult failed(ScriptSession session, ScriptError error) {
        return new ScriptResult(
                null, session.logLines(), session.stats(), error, session.filesRead(), List.of());
    }

    /**
     * Writes the run's buffered files, one atomic write and one diff per file, in the order the
     * script first touched them. Nothing is committed — the changes stay uncommitted for the user
     * to review, exactly like the {@code createFile}/{@code editFile} tools.
     *
     * <p>Atomic per file, not across files: every write was validated as the script made it, so a
     * failure here means the tree changed underneath the run. If that does happen the files already
     * written stay written, and the exception carries which ones — silently rolling back would be a
     * second unreviewed change on top of the first.
     */
    private List<GitEditResult> applyPendingWrites(ScriptSession session) {
        List<String> order = session.pendingWriteOrder();
        if (order.isEmpty()) {
            return List.of();
        }
        Map<String, String> writes = session.pendingWrites();
        List<GitEditResult> applied = new ArrayList<>(order.size());
        for (String path : order) {
            try {
                applied.add(
                        session.isPendingCreate(path)
                                ? gitService.createFile(path, writes.get(path))
                                : gitService.replaceTrackedFile(path, writes.get(path)));
            } catch (RuntimeException e) {
                throw new IllegalStateException(
                        "Script edits partially applied ("
                                + applied.size()
                                + " of "
                                + order.size()
                                + " files) — failed on "
                                + path
                                + ": "
                                + e.getMessage(),
                        e);
            }
        }
        log.info("runScript applied {} file change(s)", applied.size());
        return applied;
    }

    /**
     * Maps an engine failure onto something the model can act on. Cancellation is the exception:
     * the run that asked for the script is already gone, so it is rethrown instead.
     */
    private ScriptResult failure(
            PolyglotException e,
            ScriptSession session,
            @Nullable Kind cancelReason,
            Duration timeout) {
        if (e.isCancelled() || e.isInterrupted()) {
            return stopped(session, cancelReason, timeout);
        }
        if (e.isHostException()
                && e.asHostException() instanceof ScriptLimitExceededException limit) {
            return failed(session, ScriptError.of(Kind.BUDGET, limit.getMessage()));
        }
        if (e.isHostException()) {
            // A tool-level failure surfaced through the guest: an unknown path, an unsupported
            // language for outline, an invalid regex. The message is already model-readable.
            return failed(
                    session,
                    new ScriptError(Kind.RUNTIME, String.valueOf(e.getMessage()), line(e)));
        }
        Kind kind = e.isSyntaxError() ? Kind.SYNTAX : Kind.RUNTIME;
        return failed(session, new ScriptError(kind, String.valueOf(e.getMessage()), line(e)));
    }

    /**
     * The watchdog fired. A timeout is a recoverable result the model can respond to; a user stop
     * is not — nobody is left to read it, so it leaves as an exception.
     */
    private ScriptResult stopped(ScriptSession session, @Nullable Kind reason, Duration timeout) {
        if (reason == Kind.CANCELLED) {
            throw new ScriptCancelledException("Script cancelled: the chat response was stopped");
        }
        return failed(
                session,
                ScriptError.of(
                        Kind.TIMEOUT,
                        "Timed out after "
                                + timeout.toSeconds()
                                + "s. Narrow the work (a tighter glob, fewer files) or split it"
                                + " across two runScript calls."));
    }

    private static @Nullable Integer line(PolyglotException e) {
        SourceSection section = e.getSourceLocation();
        return section != null && section.hasLines() ? section.getStartLine() : null;
    }

    /**
     * Converts the script's return value to plain Java through the guest's own {@code
     * JSON.stringify}: it drops functions and host leftovers by construction, and gives one place
     * to enforce the size cap before anything reaches the model's context.
     *
     * <p>Oversize isn't fatal: unlike the other run budgets, the model has already done the work by
     * the time the result is this large, so the truncated value is returned along with a log line
     * warning about it, rather than throwing away the run.
     */
    private @Nullable Object stringify(Value stringifier, Value returned, ScriptSession session) {
        Value json = stringifier.execute(returned);
        if (json == null || json.isNull() || !json.isString()) {
            return null;
        }
        String text = json.asString();
        int max = properties.limits().maxResultChars();
        if (text.length() > max) {
            session.log(
                    "Result truncated: maxResultChars="
                            + max
                            + ", but the returned value was "
                            + text.length()
                            + " characters. Return a summary (counts, top-N) instead of raw"
                            + " content next time.");
            text = text.substring(0, max);
        }
        try {
            return OBJECT_MAPPER.readValue(text, Object.class);
        } catch (JsonProcessingException e) {
            log.warn("Script returned a value that is not valid JSON", e);
            return text;
        }
    }

    private Duration resolveTimeout(@Nullable Integer requestedSeconds) {
        if (requestedSeconds == null || requestedSeconds <= 0) {
            return properties.timeout();
        }
        Duration requested = Duration.ofSeconds(requestedSeconds);
        return requested.compareTo(properties.maxTimeout()) > 0
                ? properties.maxTimeout()
                : requested;
    }
}
