package io.github.trialiya.kb.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * An MCP server can announce a tool named like a built-in one at any time, and a request carrying
 * two tools of one name is refused whole — so the MCP one is what gives way.
 */
class ChatToolsetTest {

    @Test
    void anMcpToolNamedLikeABuiltinOneIsLeftOut() {
        ChatToolset toolset = new ChatToolset(List.of(tool("readFile")), List.of(tool("readFile"), tool("issue")));

        assertThat(names(toolset.mcp())).containsExactly("issue");
        assertThat(names(Arrays.asList(toolset.all()))).containsExactly("readFile", "issue");
    }

    private static List<String> names(List<ToolCallback> tools) {
        return tools.stream().map(tool -> tool.getToolDefinition().name()).toList();
    }

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name(name)
                        .description(name)
                        .inputSchema("{}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return "{}";
            }

            @Override
            public String call(String toolInput, @Nullable ToolContext ctx) {
                return call(toolInput);
            }
        };
    }
}
