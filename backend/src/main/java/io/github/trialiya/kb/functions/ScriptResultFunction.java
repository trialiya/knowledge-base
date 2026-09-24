package io.github.trialiya.kb.functions;

import static io.github.trialiya.kb.tools.ToolArgs.requireText;
import static io.github.trialiya.kb.utils.ChatUtils.conversationId;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.model.attachment.dto.Attachment;
import io.github.trialiya.kb.service.chat.context.AttachmentService;
import io.github.trialiya.kb.service.chat.script.ResultScope;
import io.github.trialiya.kb.service.chat.script.ScriptResultReader;
import io.github.trialiya.kb.service.chat.script.ScriptResultStore;
import io.github.trialiya.kb.tools.CompactToolResultConverter;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;

/**
 * The {@code saveScriptResult} tool: a value a script of this chat returned, saved as a chat
 * attachment by its {@code resultId}.
 *
 * <p>Not {@code createAttachment} with the value as content: that would send the value through the
 * model twice — once read, once written back as an argument — and it is exactly the value the model
 * was shown only the head of when it was large. Here the text never leaves the backend.
 *
 * <p>A string value is saved as it is, so a script that builds a CSV or a Markdown report and
 * returns it produces that file; anything else is saved as indented JSON.
 *
 * <p>Registered only when scripts run and results are kept (see {@code
 * ChatConfig#scriptResultFunction}).
 */
@Slf4j
@AllArgsConstructor
public class ScriptResultFunction {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ScriptResultStore store;
    private final AttachmentService attachmentService;

    @Tool(
            description =
                    """
                    Saves the value a script of this chat returned as a chat attachment, by its \
                    resultId (from runScript / runSavedScript, or a <script-run> notice) — the whole \
                    value, even where you saw it truncated. A string value is saved as-is (return a \
                    CSV or Markdown string from the script to get that file), anything else as \
                    indented JSON. Use it instead of createAttachment with the value retyped. \
                    Returns the attachment.
                    """,
            resultConverter = CompactToolResultConverter.class)
    public Attachment saveScriptResult(
            ToolContext context,
            @ToolParam(description = "The result's id, e.g. \"r3\".") String resultId,
            @ToolParam(
                            description =
                                    "Attachment file name, e.g. \"todo-report.csv\". Omit for "
                                            + "script-result-<id>.json (or .txt for a string).",
                            required = false)
                    @Nullable String fileName) {
        final String id = ScriptResultReader.canonical(requireText(resultId, "resultId"));
        final String chat = conversationId(context);
        final String json = ScriptResultReader.of(store, ResultScope.readOnly(chat)).valueJson(id);
        final JsonNode value = parse(json);
        final String content = value.isTextual() ? value.textValue() : pretty(value);
        final String name =
                fileName == null || fileName.isBlank()
                        ? "script-result-" + id + (value.isTextual() ? ".txt" : ".json")
                        : fileName.strip();
        log.info(
                "[{}] saveScriptResult called: resultId={}, fileName={}, {} chars",
                chat,
                id,
                name,
                content.length());
        return attachmentService.createFromText(chat, name, contentType(name, value), content);
    }

    /**
     * A string is whatever its name says — the model named it {@code .csv} because the script built
     * a CSV. Anything else was written as JSON here, and is labelled so whatever it was named: a
     * {@code .md} holding JSON is still JSON.
     */
    private static @Nullable String contentType(String name, JsonNode value) {
        if (!value.isTextual()) {
            return MediaType.APPLICATION_JSON_VALUE;
        }
        return MediaTypeFactory.getMediaType(name).map(MediaType::toString).orElse(null);
    }

    private static JsonNode parse(String json) {
        try {
            return OBJECT_MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A kept script result is not valid JSON", e);
        }
    }

    private static String pretty(JsonNode value) {
        try {
            return OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A script result could not be written as JSON", e);
        }
    }
}
