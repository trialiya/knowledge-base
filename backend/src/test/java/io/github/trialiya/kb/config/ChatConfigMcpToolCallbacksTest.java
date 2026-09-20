package io.github.trialiya.kb.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpClientCommonProperties;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@code spring.ai.mcp.client.toolcallback.enabled} is the starter's own off switch for MCP tools.
 * It used to work by removing the {@code ToolCallbackProvider} bean; the providers are built per
 * connection by {@code McpToolRegistry} now, so nothing honours the flag unless {@code ChatConfig}
 * does — and a deployment that set it to false meant «no MCP tools», not «no starter bean».
 */
class ChatConfigMcpToolCallbacksTest {

    @Test
    void theStartersOffSwitchStillTurnsMcpToolsOff() {
        McpClientCommonProperties properties = new McpClientCommonProperties();
        properties.getToolcallback().setEnabled(false);

        assertThat(ChatConfig.toolCallbacksEnabled(provider(properties))).isFalse();
    }

    @Test
    void theDefaultLeavesThemOn() {
        assertThat(ChatConfig.toolCallbacksEnabled(provider(new McpClientCommonProperties())))
                .isTrue();
    }

    /** No MCP configured at all: the starter registers no properties bean to read the flag from. */
    @Test
    void aMissingPropertiesBeanIsNotAnOffSwitch() {
        assertThat(ChatConfig.toolCallbacksEnabled(provider(null))).isTrue();
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<McpClientCommonProperties> provider(
            McpClientCommonProperties properties) {
        ObjectProvider<McpClientCommonProperties> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(properties);
        return provider;
    }
}
