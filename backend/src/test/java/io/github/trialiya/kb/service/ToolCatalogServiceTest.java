package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.functions.DocumentFunction;
import io.github.trialiya.kb.service.ToolCatalogService.ToolInfo;
import io.github.trialiya.kb.service.ToolCatalogService.ToolParamInfo;
import io.github.trialiya.kb.tools.ChatToolset;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * The catalogue behind «Настройки → Инструменты»: what the panel shows about a tool comes from the
 * tool itself — the {@code @Tool} description and the JSON schema the model is given — so a new or
 * renamed argument reaches the panel without anyone editing it.
 *
 * <p>Built-in tools are read from real {@code @Tool} methods ({@code ToolCallbacks.from} only
 * reflects over the class, so null dependencies are safe). MCP tools are stood in for: their
 * schemas are written by whatever server is configured, and the shapes that matter — enums, arrays,
 * object references, unparsable JSON — do not all occur among our own tools.
 */
class ToolCatalogServiceTest {

    @Test
    void readsNameDescriptionAndArgumentsOfBuiltInTools() {
        ToolInfo tool =
                catalog(builtin()).stream()
                        .filter(t -> t.name().equals("findDocumentsByName"))
                        .findFirst()
                        .orElseThrow();

        assertThat(tool.origin()).isEqualTo("builtin");
        assertThat(tool.description()).contains("Find document/folder by title");
        assertThat(tool.params())
                .singleElement()
                .satisfies(
                        param -> {
                            assertThat(param.name()).isEqualTo("name");
                            assertThat(param.type()).isEqualTo("string");
                            assertThat(param.required()).isTrue();
                            assertThat(param.description()).contains("full or partial");
                        });
    }

    /** {@code required = false} on a {@code @ToolParam} is what the panel marks as optional. */
    @Test
    void optionalArgumentsAreNotRequired() {
        ToolInfo tool =
                catalog(builtin()).stream()
                        .filter(t -> t.name().equals("searchDocuments"))
                        .findFirst()
                        .orElseThrow();

        assertThat(tool.params())
                .extracting(ToolParamInfo::name)
                .contains("query", "mode", "limit");
        assertThat(required(tool, "query")).isTrue();
        assertThat(required(tool, "mode")).isFalse();
    }

    @Test
    void sortsByNameAndTagsMcpToolsByOrigin() {
        List<ToolInfo> tools =
                new ToolCatalogService(
                                new ChatToolset(
                                        builtin(),
                                        List.of(
                                                mcpTool("zzzLast", "{}"),
                                                mcpTool("aaaFirst", "{}"))))
                        .tools();

        assertThat(tools).extracting(ToolInfo::name).isSorted();
        assertThat(tools)
                .filteredOn(t -> t.origin().equals("mcp"))
                .extracting(ToolInfo::name)
                .containsExactly("aaaFirst", "zzzLast");
    }

    /** Types the panel prints as-is: an item type for arrays, the definition name behind a $ref. */
    @Test
    void rendersArrayAndReferenceTypes() {
        ToolInfo tool =
                mcpCatalog(
                        """
                        {
                          "type": "object",
                          "properties": {
                            "paths": { "type": "array", "items": { "type": "string" } },
                            "renames": { "type": "array", "items": { "$ref": "#/$defs/SectionRename" } }
                          }
                        }
                        """);

        assertThat(type(tool, "paths")).isEqualTo("array<string>");
        assertThat(type(tool, "renames")).isEqualTo("array<SectionRename>");
    }

    @Test
    void keepsEnumValues() {
        ToolInfo tool =
                mcpCatalog(
                        """
                        {
                          "type": "object",
                          "properties": {
                            "mode": { "type": "string", "enum": ["hybrid", "semantic", "keyword"] },
                            "query": { "type": "string" }
                          },
                          "required": ["query"]
                        }
                        """);

        assertThat(param(tool, "mode").values()).containsExactly("hybrid", "semantic", "keyword");
        assertThat(param(tool, "query").values()).isEmpty();
    }

    /** A schema we cannot read costs the argument list, not the tool: the model still has it. */
    @Test
    void survivesAnUnreadableSchema() {
        ToolInfo tool = mcpCatalog("not json at all");

        assertThat(tool.name()).isEqualTo("external");
        assertThat(tool.params()).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static List<ToolCallback> builtin() {
        return Arrays.asList(ToolCallbacks.from(new DocumentFunction(null, null)));
    }

    private static List<ToolInfo> catalog(List<ToolCallback> builtin) {
        return new ToolCatalogService(new ChatToolset(builtin, List.of())).tools();
    }

    /** The single tool of a catalogue holding one MCP tool with the given input schema. */
    private static ToolInfo mcpCatalog(String schema) {
        return new ToolCatalogService(
                        new ChatToolset(List.of(), List.of(mcpTool("external", schema))))
                .tools()
                .getFirst();
    }

    private static ToolCallback mcpTool(String name, String schema) {
        ToolDefinition definition =
                ToolDefinition.builder()
                        .name(name)
                        .description("Tool " + name)
                        .inputSchema(schema)
                        .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException("not called in this test");
            }
        };
    }

    private static ToolParamInfo param(ToolInfo tool, String name) {
        return tool.params().stream()
                .filter(p -> p.name().equals(name))
                .findFirst()
                .orElseThrow(
                        () -> new AssertionError("no argument '" + name + "' in " + tool.name()));
    }

    private static String type(ToolInfo tool, String name) {
        return param(tool, name).type();
    }

    private static boolean required(ToolInfo tool, String name) {
        return param(tool, name).required();
    }
}
