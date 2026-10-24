package io.github.trialiya.kb.service.chat.script;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.config.model.ScriptMcpProperties;
import io.github.trialiya.kb.tools.McpContent;
import io.github.trialiya.kb.tools.McpToolRegistry.ScriptTool;
import io.github.trialiya.kb.tools.RunCancellation;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.graalvm.polyglot.HostAccess;
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
 * needs to carry on without the tool. A spent budget is still thrown from here, so the runner
 * reports it as BUDGET like every other.
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
        session.log("mcp.call('" + name + "'): " + answer.json().length() + " chars in " + millisSince(started)
                + " ms");
        log.info("Script called MCP tool '{}' ({}): {} chars", name, tool.connection(), answer.json().length());
        if (bytes > properties.maxBytes().toBytes()) {
            throw new ScriptLimitExceededException("Budget exceeded: maxMcpBytes=" + properties.maxBytes()
                    + " of MCP answers per run (kb.script.mcp.max-bytes). Narrow the request — filters, fewer"
                    + " fields, a smaller page — if the tool takes them.");
        }
        return "{\"value\":" + answer.json() + "}";
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
            throw new IllegalStateException("No time left in this run to call MCP tool '" + tool.name() + "'.");
        }
        CompletableFuture<String> pending = CompletableFuture.supplyAsync(
                () -> tool.callback().call(argsJson),
                task -> Thread.ofVirtual().name("script-mcp").start(task));
        while (true) {
            long left = deadlineNanos - System.nanoTime();
            if (cancellation.isStopRequested() || left <= 0) {
                pending.cancel(true);
                throw new IllegalStateException("Stopped waiting for MCP tool '" + tool.name() + "': "
                        + (left <= 0 ? "the run's time is up" : "the run was stopped")
                        + ". The request may still reach the server.");
            }
            try {
                return pending.get(Math.min(TimeUnit.NANOSECONDS.toMillis(left) + 1, POLL_MILLIS), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                // Not yet — check the deadline and the stop flag again.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pending.cancel(true);
                throw new IllegalStateException("Interrupted while waiting for MCP tool '" + tool.name() + "'", e);
            } catch (ExecutionException e) {
                throw failure(tool, e.getCause());
            }
        }
    }

    private static RuntimeException failure(ScriptTool tool, Throwable cause) {
        Throwable reason = cause instanceof ToolExecutionException && cause.getCause() != null
                ? cause.getCause()
                : cause;
        return new IllegalStateException(
                "MCP tool '" + tool.name() + "' failed: " + String.valueOf(reason.getMessage()), reason);
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }
}
