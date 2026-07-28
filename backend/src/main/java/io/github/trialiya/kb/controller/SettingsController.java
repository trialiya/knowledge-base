package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.config.model.ChatModeProperties;
import io.github.trialiya.kb.config.model.ChatModeProperties.ModeView;
import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties.ModelOption;
import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
import io.github.trialiya.kb.config.model.GitProperties;
import io.github.trialiya.kb.config.model.McpProperties;
import io.github.trialiya.kb.config.model.SearchConfiguration;
import io.github.trialiya.kb.config.model.SubAgentConfig;
import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.functions.GitEditFunction;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpSseClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStdioClientProperties;
import org.springframework.ai.mcp.client.common.autoconfigure.properties.McpStreamableHttpClientProperties;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only view of the AI-side configuration for the Settings panel. Everything here comes from
 * {@code application.yaml}; nothing is editable at runtime.
 *
 * <p>Secrets are excluded <em>by construction</em>: this controller assembles typed records field
 * by field and never touches {@link org.springframework.core.env.Environment} in bulk, so API keys
 * ({@code spring.ai.openai.api-key}), the MCP bearer tokens ({@code kb.mcp.bearer-tokens} — only
 * connection <em>names</em> are reported) and the datasource password cannot leak into the
 * response. Keep it that way when adding fields.
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final ChatModelProperties chatModelProperties;
    private final ChatModeProperties chatModeProperties;
    private final EmbeddingConfiguration embeddingConfiguration;
    private final SubAgentConfig subAgentConfig;
    private final SearchConfiguration searchConfiguration;
    private final @Nullable Integer chatMaxTokens;
    private final @Nullable Double chatTemperature;
    private final @Nullable Double chatTopP;
    private final SummarizeProperties summarizeProperties;
    private final ChatTimeoutProperties chatTimeoutProperties;
    private final McpProperties mcpProperties;

    /** Configured opt-in ({@code kb.git.edit-enabled}) — may be true while the tools are absent. */
    private final boolean gitEditEnabled;

    /**
     * Whether the edit tools are actually exposed to the model. The bean exists only when the
     * opt-in is on <em>and</em> the working tree is writable (see {@code
     * ChatConfig#gitEditFunction}), so its presence — not the flag — is the honest answer.
     */
    private final boolean gitEditActive;

    private final Duration requestTimeout;
    private final int retryMaxAttempts;
    private final DataSize maxFileSize;
    private final DataSize maxRequestSize;
    private final List<McpConnection> mcpConnections;

    // McpSseClientProperties is deprecated for removal upstream (streamable-HTTP supersedes SSE),
    // but the SSE connections are still configurable and documented in application.yaml, so they
    // are still reported here. Drop this together with the transport itself.
    @SuppressWarnings("removal")
    public SettingsController(
            ChatModelProperties chatModelProperties,
            ChatModeProperties chatModeProperties,
            EmbeddingConfiguration embeddingConfiguration,
            SubAgentConfig subAgentConfig,
            SearchConfiguration searchConfiguration,
            OpenAiChatModel openAiChatModel,
            SummarizeProperties summarizeProperties,
            ChatTimeoutProperties chatTimeoutProperties,
            McpProperties mcpProperties,
            ObjectProvider<GitEditFunction> gitEditFunction,
            ObjectProvider<McpSseClientProperties> sseProperties,
            ObjectProvider<McpStreamableHttpClientProperties> streamableHttpProperties,
            ObjectProvider<McpStdioClientProperties> stdioProperties,
            GitProperties gitProperties,
            @Value("${spring.ai.openai.timeout:60s}") Duration requestTimeout,
            @Value("${spring.ai.retry.max-attempts:10}") int retryMaxAttempts,
            @Value("${spring.servlet.multipart.max-file-size:1MB}") DataSize maxFileSize,
            @Value("${spring.servlet.multipart.max-request-size:10MB}") DataSize maxRequestSize) {
        this.chatModelProperties = chatModelProperties;
        this.chatModeProperties = chatModeProperties;
        this.embeddingConfiguration = embeddingConfiguration;
        this.subAgentConfig = subAgentConfig;
        this.searchConfiguration = searchConfiguration;
        this.chatMaxTokens = openAiChatModel.getOptions().getMaxTokens();
        this.chatTemperature = openAiChatModel.getOptions().getTemperature();
        this.chatTopP = openAiChatModel.getOptions().getTopP();
        this.summarizeProperties = summarizeProperties;
        this.chatTimeoutProperties = chatTimeoutProperties;
        this.mcpProperties = mcpProperties;
        this.gitEditEnabled = gitProperties.editEnabled();
        this.gitEditActive = gitEditFunction.getIfAvailable() != null;
        this.requestTimeout = requestTimeout;
        this.retryMaxAttempts = retryMaxAttempts;
        this.maxFileSize = maxFileSize;
        this.maxRequestSize = maxRequestSize;
        this.mcpConnections =
                mcpConnections(sseProperties, streamableHttpProperties, stdioProperties);
    }

    /** Full AI configuration snapshot consumed by the Settings panel. */
    @GetMapping("/ai-config")
    public AiConfigResponse getAiConfig() {
        return new AiConfigResponse(
                new ChatSection(
                        chatModelProperties.defaultModel(),
                        chatModelProperties.models(),
                        new ChatOptions(
                                chatMaxTokens,
                                chatTemperature,
                                chatTopP,
                                requestTimeout.toSeconds(),
                                retryMaxAttempts,
                                chatTimeoutProperties.sse().toSeconds())),
                new EmbeddingSection(
                        embeddingConfiguration.model(),
                        embeddingConfiguration.reindexBatchSize(),
                        new ChunkerInfo(
                                embeddingConfiguration.chunker().maxTokens(),
                                embeddingConfiguration.chunker().overlapTokens()),
                        new CacheInfo(
                                embeddingConfiguration.cache().enabled(),
                                embeddingConfiguration.cache().ttlDays())),
                new SearchCodebaseSection(
                        subAgentConfig.enabled(),
                        subAgentConfig.modelId(),
                        subAgentConfig.maxTokens(),
                        subAgentConfig.maxIterations(),
                        subAgentConfig.allowedTools().stream().sorted().toList()),
                summarizeProperties,
                searchConfiguration,
                new ToolsSection(
                        chatModeProperties.views(),
                        new GitToolsInfo(gitEditEnabled, gitEditActive),
                        new McpInfo(mcpProperties.enabled(), mcpConnections),
                        new UploadLimits(maxFileSize.toBytes(), maxRequestSize.toBytes())));
    }

    /**
     * Names of the configured MCP servers, with the transport each one uses — never their URLs,
     * commands or bearer tokens. Every property bean is optional: with {@code
     * spring.ai.mcp.client.*} left unconfigured the starter registers none of them.
     */
    @SuppressWarnings("removal")
    private static List<McpConnection> mcpConnections(
            ObjectProvider<McpSseClientProperties> sseProperties,
            ObjectProvider<McpStreamableHttpClientProperties> streamableHttpProperties,
            ObjectProvider<McpStdioClientProperties> stdioProperties) {
        List<McpConnection> connections = new ArrayList<>();
        sseProperties.ifAvailable(p -> collect(connections, "sse", p.getConnections()));
        streamableHttpProperties.ifAvailable(
                p -> collect(connections, "streamable-http", p.getConnections()));
        stdioProperties.ifAvailable(p -> collect(connections, "stdio", p.getConnections()));
        return List.copyOf(connections);
    }

    private static void collect(
            List<McpConnection> target, String transport, Map<String, ?> connections) {
        connections.keySet().stream()
                .sorted()
                .forEach(name -> target.add(new McpConnection(name, transport)));
    }

    public record AiConfigResponse(
            ChatSection chat,
            EmbeddingSection embedding,
            SearchCodebaseSection searchCodebase,
            SummarizeProperties summarize,
            SearchConfiguration search,
            ToolsSection tools) {}

    public record ChatSection(
            ModelOption defaultModel, List<ModelOption> models, ChatOptions options) {}

    /**
     * Core inference parameters from {@code spring.ai.openai.chat.options.*}, plus the limits that
     * shape a conversation around them: the SDK request deadline ({@code spring.ai.openai.timeout}
     * — the thing that actually cancels a call), the retry count and the SSE subscription window.
     *
     * <p>{@code MessageWindowChatMemory.maxMessages} is intentionally absent — see {@code
     * ChatConfig#chatMemory}: it has no observable effect here, and the real context limit is the
     * summarization thresholds already reported in {@code summarize}.
     */
    public record ChatOptions(
            Integer maxTokens,
            Double temperature,
            Double topP,
            long requestTimeoutSeconds,
            int retryMaxAttempts,
            long sseTimeoutSeconds) {}

    public record EmbeddingSection(
            String model, int reindexBatchSize, ChunkerInfo chunker, CacheInfo cache) {}

    public record ChunkerInfo(int maxTokens, int overlapTokens) {}

    public record CacheInfo(boolean enabled, int ttlDays) {}

    public record SearchCodebaseSection(
            boolean enabled,
            String modelId,
            int maxTokens,
            int maxIterations,
            List<String> allowedTools) {}

    /** What the model may reach beyond the built-in read-only tools. */
    public record ToolsSection(
            List<ModeView> modes, GitToolsInfo git, McpInfo mcp, UploadLimits uploads) {}

    public record GitToolsInfo(boolean editEnabled, boolean editActive) {}

    public record McpInfo(boolean enabled, List<McpConnection> connections) {}

    public record McpConnection(String name, String transport) {}

    public record UploadLimits(long maxFileSizeBytes, long maxRequestSizeBytes) {}
}
