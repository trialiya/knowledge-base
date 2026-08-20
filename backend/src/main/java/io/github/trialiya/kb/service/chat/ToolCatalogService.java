package io.github.trialiya.kb.service.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.trialiya.kb.tools.ChatToolset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

/**
 * The tools the model can call, as the Settings panel shows them: name, the description the model
 * itself reads, and the arguments taken from the tool's JSON schema.
 *
 * <p>The source is {@link ChatToolset} — the very callbacks handed to the {@code ChatClient} — so
 * what the panel lists is what the model has, including the tools that appear only with {@code
 * kb.projects[].edit-enabled}, {@code kb.script.enabled} or an MCP server configured. Nothing here
 * is curated by hand; a new {@code @Tool} shows up in the panel with no edit on this side.
 *
 * <p>Built once: the tool set is fixed when the context starts, and re-reading the schemas per
 * request would only re-parse constants.
 */
@Slf4j
@Service
public class ToolCatalogService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final List<ToolInfo> tools;

    public ToolCatalogService(ChatToolset toolset) {
        this.tools =
                Stream.concat(
                                toolset.builtin().stream().map(cb -> toInfo(cb, "builtin")),
                                toolset.mcp().stream().map(cb -> toInfo(cb, "mcp")))
                        .sorted(Comparator.comparing(ToolInfo::name))
                        .toList();
    }

    /** All tools available to the chat model, sorted by name. */
    public List<ToolInfo> tools() {
        return tools;
    }

    private static ToolInfo toInfo(ToolCallback callback, String origin) {
        ToolDefinition definition = callback.getToolDefinition();
        return new ToolInfo(
                definition.name(), definition.description(), origin, parameters(definition));
    }

    /**
     * Arguments of one tool, read off the JSON schema the model is given. A schema we cannot parse
     * costs the argument list, not the tool: MCP servers write their own schemas and are free to
     * shape them in ways the built-in generator never produces.
     */
    private static List<ToolParamInfo> parameters(ToolDefinition definition) {
        JsonNode schema;
        try {
            schema = OBJECT_MAPPER.readTree(definition.inputSchema());
        } catch (Exception e) {
            log.warn("Tool {}: input schema is not readable JSON", definition.name(), e);
            return List.of();
        }
        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(node -> required.add(node.asText()));

        List<ToolParamInfo> params = new ArrayList<>();
        schema.path("properties")
                .properties()
                .forEach(
                        entry ->
                                params.add(
                                        new ToolParamInfo(
                                                entry.getKey(),
                                                type(entry.getValue()),
                                                text(entry.getValue().path("description")),
                                                required.contains(entry.getKey()),
                                                values(entry.getValue()))));
        return List.copyOf(params);
    }

    /**
     * A schema node rendered as one readable type: {@code string}, {@code array&lt;string&gt;}, or
     * the name of a referenced definition ({@code $ref} — object arguments are emitted that way).
     */
    private static String type(JsonNode node) {
        String ref = text(node.path("$ref"));
        if (ref != null) {
            return ref.substring(ref.lastIndexOf('/') + 1);
        }
        String type = text(node.path("type"));
        if (type == null) {
            return "any";
        }
        if ("array".equals(type)) {
            return "array<" + type(node.path("items")) + ">";
        }
        return type;
    }

    /** The allowed values of an enum argument, empty for everything else. */
    private static List<String> values(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.path("enum").forEach(value -> values.add(value.asText()));
        return List.copyOf(values);
    }

    private static @Nullable String text(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    /**
     * @param origin {@code builtin} — a {@code @Tool} of this application, {@code mcp} — a tool
     *     advertised by an external MCP server
     */
    public record ToolInfo(
            String name, String description, String origin, List<ToolParamInfo> params) {}

    /**
     * @param values allowed values of an enum argument, empty when the argument is not one
     */
    public record ToolParamInfo(
            String name,
            String type,
            @Nullable String description,
            boolean required,
            List<String> values) {}
}
