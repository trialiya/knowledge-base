package io.github.trialiya.kb.service.chat.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.config.model.ScriptMcpProperties;
import io.github.trialiya.kb.model.script.ScriptError;
import io.github.trialiya.kb.tools.McpContent;
import io.github.trialiya.kb.tools.McpToolRegistry.ScriptTool;
import io.github.trialiya.kb.tools.RunCancellation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.HostAccess;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.execution.ToolExecutionException;

/**
 * The host half of the {@code mcp} object a script gets when it may call MCP tools — one per run,
 * built by {@link ScriptMcp}. The guest half ({@code ScriptRunner}'s helpers) stringifies the
 * arguments and parses the answer, so everything crossing here is a string: no {@code Value} to
 * convert, no proxy to build, and an optional argument settled in JavaScript rather than by
 * overloads.
 *
 * <p>A refusal or a failed call comes back as {@code {"error": "…"}}, which the guest half throws
 * as an ordinary {@code Error}: a host exception reaches the guest as a host object, and with
 * {@code HostAccess.EXPLICIT} a script that catches it cannot read its message — the one thing it
 * needs to carry on without the tool. A spent budget is still thrown from here, as the {@code kb}
 * budgets are: uncaught, the runner reports it as BUDGET, and every later call is refused the same
 * way before it reaches the server. So is a run out of time or stopped mid-call.
 *
 * <p>A call runs on a virtual thread of its own and is waited for only until the run's deadline or
 * its stop flag. The watchdog closes the script's context at the deadline, but that does not reach
 * a host call blocked on a server: without this a call would outlive the run by up to the client's
 * {@code request-timeout}. A call given up on may still reach the server — the log says so.
 */
@Slf4j
public final class McpScriptApi {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** How often a call being waited for checks the run's stop flag. */
    private static final long POLL_MILLIS = 100;

    private final Supplier<List<ScriptTool>> tools;
    private final ScriptMcpProperties properties;
    private final ScriptSession session;
    private final long deadlineNanos;
    private final RunCancellation cancellation;
    private long bytes;

    /** Why a call was given up on mid-wait; null while none was — see {@link #abandoned()}. */
    private ScriptError.@Nullable Kind abandoned;

    /**
     * @param tools what this run may call, read again on every call — a connection can go down or
     *     come back while the script runs
     */
    McpScriptApi(
            Supplier<List<ScriptTool>> tools,
            ScriptMcpProperties properties,
            ScriptSession session,
            long deadlineNanos,
            RunCancellation cancellation) {
        this.tools = tools;
        this.properties = properties;
        this.session = session;
        this.deadlineNanos = deadlineNanos;
        this.cancellation = cancellation;
    }

    /** The tools this run may call, as JSON: {@code [{name, connection, description, available}]}. */
    @HostAccess.Export
    public String tools() {
        List<Map<String, Object>> rows = tools.get().stream()
                .map(tool -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", tool.name());
                    row.put("connection", tool.connection());
                    row.put("description", tool.description());
                    row.put("available", tool.available());
                    return row;
                })
                .toList();
        try {
            return OBJECT_MAPPER.writeValueAsString(rows);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The MCP tool list does not serialise", e);
        }
    }

    /**
     * Calls {@code name} with {@code argsJson}: {@code {"value": <answer>}} with the answer
     * {@linkplain McpContent#normalize normalized}, or {@code {"error": "…"}}.
     */
    @HostAccess.Export
    public String call(String name, String argsJson) {
        Optional<ScriptTool> found = find(name);
        if (found.isEmpty()) {
            return error(refusal(name));
        }
        ScriptTool tool = found.get();
        // Checked before the call as well as after: a script that caught the first refusal must not
        // get to reach the server again with the budget already spent.
        if (bytes >= properties.maxBytes().toBytes()) {
            throw bytesExceeded();
        }
        int count = session.countMcpCall();
        if (count > properties.maxCalls()) {
            throw new ScriptLimitExceededException("Budget exceeded: maxMcpCalls=" + properties.maxCalls()
                    + " MCP calls per run (kb.script.mcp.max-calls). Every call counts, a repeat included:"
                    + " keep an answer in a variable instead of asking again.");
        }
        long started = System.nanoTime();
        String raw;
        try {
            raw = invoke(tool, argsJson);
        } catch (IllegalStateException e) {
            session.log("mcp.call('" + name + "') failed after " + millisSince(started) + " ms: " + e.getMessage());
            return error(String.valueOf(e.getMessage()));
        }
        McpContent.Normalized answer = McpContent.normalize(raw);
        bytes += answer.json().length();
        session.log(
                "mcp.call('" + name + "'): " + answer.json().length() + " chars in " + millisSince(started) + " ms");
        log.info(
                "Script called MCP tool '{}' ({}): {} chars",
                name,
                tool.connection(),
                answer.json().length());
        if (bytes > properties.maxBytes().toBytes()) {
            throw bytesExceeded();
        }
        return "{\"value\":" + answer.json() + "}";
    }

    /**
     * Why a call of this run was given up on — {@code TIMEOUT} or {@code CANCELLED} — or null. The
     * runner asks after the script returns: the guest may have caught the exception, and the run
     * must still end as what it was.
     */
    ScriptError.@Nullable Kind abandoned() {
        return abandoned;
    }

    private ScriptLimitExceededException bytesExceeded() {
        return new ScriptLimitExceededException("Budget exceeded: maxMcpBytes=" + properties.maxBytes()
                + " of MCP answers per run (kb.script.mcp.max-bytes). Narrow the request — filters, fewer"
                + " fields, a smaller page — if the tool takes them.");
    }

    private static String error(String message) {
        try {
            return OBJECT_MAPPER.writeValueAsString(Map.of("error", message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("An error message does not serialise", e);
        }
    }

    private Optional<ScriptTool> find(String name) {
        return tools.get().stream().filter(tool -> tool.name().equals(name)).findFirst();
    }

    private String refusal(String name) {
        String allowed = tools.get().stream().map(ScriptTool::name).collect(Collectors.joining(", "));
        return "MCP tool '" + name + "' is not available to scripts here. "
                + (allowed.isEmpty()
                        ? "No MCP tool is."
                        : "Available: " + allowed + " (mcp.tools() lists them with descriptions).")
                + " Which tools scripts may call is set per connection in kb.mcp.script-access.";
    }

    /**
     * The call on a thread of its own, waited for until the run's deadline or its stop flag —
     * whichever comes first. What the tool threw is rethrown with its message, which is the text the
     * script and the model need ("the server is not reachable", the server's own error).
     */
    private String invoke(ScriptTool tool, String argsJson) {
        if (System.nanoTime() >= deadlineNanos) {
            abandoned = ScriptError.Kind.TIMEOUT;
            throw new CallAbandonedException("No time left in this run to call MCP tool '" + tool.name() + "'.");
        }
        // A FutureTask on a thread of its own rather than a CompletableFuture: cancel(true) on the
        // latter does not interrupt the thread, and a call given up on would hold it — and a
        // request slot of the client — until the client's own timeout.
        FutureTask<String> pending = new FutureTask<>(() -> tool.callback().call(argsJson));
        Thread.ofVirtual().name("script-mcp").start(pending);
        while (true) {
            long left = deadlineNanos - System.nanoTime();
            if (cancellation.isStopRequested() || left <= 0) {
                pending.cancel(true);
                abandoned = left <= 0 ? ScriptError.Kind.TIMEOUT : ScriptError.Kind.CANCELLED;
                throw new CallAbandonedException("Stopped waiting for MCP tool '" + tool.name() + "': "
                        + (left <= 0 ? "the run's time is up" : "the run was stopped")
                        + ". The request may still reach the server.");
            }
            Optional<String> answer =
                    poll(tool, pending, Math.min(TimeUnit.NANOSECONDS.toMillis(left) + 1, POLL_MILLIS));
            if (answer.isPresent()) {
                return answer.get();
            }
        }
    }

    /**
     * The run ran out of time or was stopped while a call was pending. Not turned into an {@code
     * {error}} the script could catch: a run past its deadline or stopped by the user must end as
     * such, not return a fallback value as a success.
     */
    private static final class CallAbandonedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        CallAbandonedException(String message) {
            super(message);
        }
    }

    /** The answer if it came within {@code millis}; empty when it is not there yet. */
    private static Optional<String> poll(ScriptTool tool, FutureTask<String> pending, long millis) {
        try {
            return Optional.of(pending.get(millis, TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pending.cancel(true);
            throw new IllegalStateException("Interrupted while waiting for MCP tool '" + tool.name() + "'", e);
        } catch (ExecutionException e) {
            throw failure(tool, e);
        }
    }

    /**
     * What the tool threw, with the message of what actually went wrong — under the executor's own
     * wrapper, and under the {@link ToolExecutionException} Spring AI wraps a server's error in.
     */
    private static IllegalStateException failure(ScriptTool tool, ExecutionException e) {
        Throwable reason = e.getCause() == null ? e : e.getCause();
        if (reason instanceof ToolExecutionException && reason.getCause() != null) {
            reason = reason.getCause();
        }
        return new IllegalStateException("MCP tool '" + tool.name() + "' failed: " + reason.getMessage(), e);
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
