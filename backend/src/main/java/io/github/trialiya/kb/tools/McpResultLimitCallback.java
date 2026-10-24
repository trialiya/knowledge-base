package io.github.trialiya.kb.tools;

import io.github.trialiya.kb.tools.McpContent.Normalized;
import io.github.trialiya.kb.tools.ToolResultShelf.Shelved;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.metadata.ToolMetadata;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * An MCP tool whose answers reach the model only up to a limit. A larger answer is kept whole on
 * the {@link ToolResultShelf}, and the model is answered with an envelope: how large the answer
 * was, its shape, its head, and the id a script can read the rest by.
 *
 * <p>An answer within the limit goes through byte for byte — the model of a deployment whose
 * servers answer briefly sees no difference. Only past the limit is it {@linkplain
 * McpContent#normalize normalized}, so the head the model is shown is payload rather than protocol
 * and escaping. An answer that fits once normalized (an image left out, say) is answered with the
 * normalized form and no envelope.
 *
 * <p>The definition and metadata are delegated for the reason {@link UnavailableToolCallback}
 * gives: the tool list is part of the prompt prefix every provider caches by.
 *
 * <p>The whole normalized answer also goes to {@link RecordingToolCallback} as the call's full
 * text, so its detail view can show what the model was spared — which is why this wrapper sits
 * inside the recording one.
 */
@Slf4j
public record McpResultLimitCallback(
        ToolCallback delegate, int limit, @Nullable ToolResultShelf shelf, int fullTextMaxChars)
        implements ToolCallback {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** What the envelope around the head costs, at most — kept out of the head's share. */
    private static final int ENVELOPE_CHARS = 1500;

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, @Nullable ToolContext toolContext) {
        String raw;
        try {
            raw = delegate.call(toolInput, toolContext);
        } catch (ToolExecutionException e) {
            throw limited(e);
        }
        if (raw.length() <= limit) {
            return raw;
        }
        Normalized normalized = McpContent.normalize(raw);
        if (normalized.json().length() <= limit) {
            return normalized.json();
        }
        String name = getToolDefinition().name();
        String conversationId = conversationId(toolContext);
        Shelved shelved = shelf == null || conversationId == null
                ? new Shelved(null, null)
                : shelf.keep(conversationId, "mcp:" + name, normalized.json());
        if (normalized.json().length() <= fullTextMaxChars) {
            RecordingToolCallback.CURRENT_FULL_TEXT.set(normalized.json());
        }
        log.info(
                "MCP tool '{}' answered {} characters, over its limit of {}; kept as {}",
                name,
                normalized.json().length(),
                limit,
                shelved.id());
        return bounded(normalized, shelved);
    }

    /**
     * The envelope, held to the limit it enforces. The shape is a sketch of bounded breadth but not
     * of bounded size — long key names, a dozen keys three levels deep — so an envelope that comes
     * out too large first loses the shape, then as much of the head as it overshoots by.
     */
    private String bounded(Normalized normalized, Shelved shelved) {
        boolean text = normalized.value().isString();
        String whole = text ? normalized.value().asString() : normalized.json();
        int headChars = Math.min(whole.length(), headChars());
        String answer = MAPPER.writeValueAsString(envelope(normalized, whole, headChars, shelved, !text));
        if (answer.length() > limit && !text) {
            answer = MAPPER.writeValueAsString(envelope(normalized, whole, headChars, shelved, false));
        }
        if (answer.length() > limit) {
            // Escaping makes a head take more room than its length, so the cut is by what the
            // serialized envelope overshoots, which is never less than what the head has to lose.
            headChars = Math.max(0, headChars - (answer.length() - limit));
            answer = MAPPER.writeValueAsString(envelope(normalized, whole, headChars, shelved, false));
        }
        return answer;
    }

    private ObjectNode envelope(
            Normalized normalized, String whole, int headChars, Shelved shelved, boolean withShape) {
        String head = whole.substring(0, headChars);
        ObjectNode envelope = JsonNodeFactory.instance.objectNode();
        envelope.put("truncated", true);
        envelope.put("totalChars", whole.length());
        envelope.put("shownChars", head.length());
        if (shelved.id() != null) {
            envelope.put("resultId", shelved.id());
        }
        if (withShape) {
            envelope.set("shape", McpContent.shape(normalized.value()));
        }
        if (normalized.omittedBytes() > 0) {
            envelope.put("binaryOmitted", true);
        }
        envelope.put("note", note(whole.length(), head.length(), shelved));
        envelope.put("head", head);
        return envelope;
    }

    private String note(int total, int shown, Shelved shelved) {
        String cut = "The answer is " + total + " characters, over the limit of " + limit + "; you see its first "
                + shown + ". ";
        String id = shelved.id();
        if (id != null) {
            return cut + "The whole answer is kept as " + id + ": read it in runScript with kb.result('" + id
                    + "') and return only what you need, or save it as a file with saveScriptResult (resultId "
                    + id + "). Do not call the tool again to get the rest.";
        }
        String note = shelved.note();
        String why = note == null ? "The rest was not kept." : note;
        return cut + why + " Narrow the request instead — filters, fewer fields, a smaller page — if the tool"
                + " takes them.";
    }

    /** The head's share of the limit: what the envelope leaves, and never less than half. */
    private int headChars() {
        return Math.max(limit - ENVELOPE_CHARS, limit / 2);
    }

    /** A failure is part of the answer too: a server's error can carry the whole payload. */
    private ToolExecutionException limited(ToolExecutionException e) {
        String message = e.getMessage();
        if (message == null || message.length() <= limit) {
            return e;
        }
        return new ToolExecutionException(
                e.getToolDefinition(),
                new IllegalStateException(message.substring(0, headChars())
                        + "… [error message cut: " + message.length() + " characters, over the limit of " + limit
                        + "]"));
    }

    /**
     * The chat the call belongs to; null outside one. Not {@code ChatUtils.conversationId}, which
     * falls back to a default id: an answer kept under a chat that does not exist is kept for
     * nobody.
     */
    private static @Nullable String conversationId(@Nullable ToolContext context) {
        Object id = context == null ? null : context.getContext().get(ChatMemory.CONVERSATION_ID);
        return id == null ? null : id.toString();
    }
}
