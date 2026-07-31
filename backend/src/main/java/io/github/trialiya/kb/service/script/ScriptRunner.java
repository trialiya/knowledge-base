package io.github.trialiya.kb.service.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.model.script.ScriptError;
import io.github.trialiya.kb.model.script.ScriptError.Kind;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.service.GitService;
import io.github.trialiya.kb.tools.RunCancellation;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.EnvironmentAccess;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
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
 * <p><b>The sandbox.</b> {@code allowIO(false)} means no {@code FileSystem} is attached; {@code
 * allowHostClassLookup(n -> false)} removes {@code Java.type}, so {@code java.io.File} and {@code
 * Runtime} cannot be named; {@code HostAccess.EXPLICIT} exposes only the {@code @HostAccess.Export}
 * methods of {@link KbScriptApi}. No threads, no processes, no native access, no environment. What
 * remains is ECMAScript plus {@code kb}.
 *
 * <p><b>Cancellation.</b> A watchdog thread polls the deadline and the run's {@link
 * RunCancellation} and closes the context under the running script. Cancellation lands when control
 * is next inside the guest, so a script blocked in a long host call (a {@code git grep} subprocess)
 * finishes that call first — the bound is the call, not the script.
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
    private static final String JSON_HELPERS =
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
            """;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final GitService gitService;
    private final DocumentService documentService;
    private final ScriptProperties properties;

    public ScriptRunner(
            GitService gitService, DocumentService documentService, ScriptProperties properties) {
        this.gitService = gitService;
        this.documentService = documentService;
        this.properties = properties;
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
        ScriptSession session = new ScriptSession(properties);
        KbScriptApi api = new KbScriptApi(gitService, documentService, session);
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
            Value helpers = context.eval("js", JSON_HELPERS);
            Value logFormatter = helpers.getMember("log");
            api.bindFormatter(value -> logFormatter.execute(value).asString());
            context.getBindings("js").putMember("kb", api);

            Value returned = context.eval(source(script));
            Object value = stringify(helpers.getMember("result"), returned, session);
            return success(value, session);
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
            finished.set(true);
            watchdog.interrupt();
            closeQuietly(context);
        }
    }

    // ── Sandbox ─────────────────────────────────────────────────────────────

    private static Context newContext() {
        return Context.newBuilder("js")
                .allowIO(false)
                .allowHostClassLookup(className -> false)
                .allowHostAccess(HostAccess.EXPLICIT)
                .allowCreateThread(false)
                .allowCreateProcess(false)
                .allowNativeAccess(false)
                .allowEnvironmentAccess(EnvironmentAccess.NONE)
                // On a stock JDK there is no Graal compiler, so the engine warns once per context
                // that it is interpreting. Expected here: scripts are glue code, and
                // kb.script.limits keeps them small enough for interpretation to be irrelevant.
                .option("engine.WarnInterpreterOnly", "false")
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

    /** Cancels whatever the context is executing; a context already closing is not an error. */
    private static void closeQuietly(Context context) {
        try {
            context.close(true);
        } catch (RuntimeException e) {
            log.debug("Script context already closed", e);
        }
    }

    // ── Results ─────────────────────────────────────────────────────────────

    private ScriptResult success(@Nullable Object value, ScriptSession session) {
        return new ScriptResult(
                value, session.logLines(), session.stats(), null, session.filesRead());
    }

    private ScriptResult failed(ScriptSession session, ScriptError error) {
        return new ScriptResult(
                null, session.logLines(), session.stats(), error, session.filesRead());
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
     */
    private @Nullable Object stringify(Value stringifier, Value returned, ScriptSession session) {
        Value json = stringifier.execute(returned);
        if (json == null || json.isNull() || !json.isString()) {
            return null;
        }
        String text = json.asString();
        int max = properties.limits().maxResultChars();
        if (text.length() > max) {
            throw new ScriptLimitExceededException(
                    "Budget exceeded: maxResultChars="
                            + max
                            + ", but the returned value is "
                            + text.length()
                            + " characters. Return a summary (counts, top-N) instead of raw"
                            + " content.");
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
