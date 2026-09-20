package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.config.model.ChatModeProperties;
import io.github.trialiya.kb.config.model.ChatModeProperties.ModeView;
import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties.ModelOption;
import io.github.trialiya.kb.config.model.ChatTimeoutProperties;
import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
import io.github.trialiya.kb.config.model.McpProperties;
import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.config.model.SearchConfiguration;
import io.github.trialiya.kb.config.model.SubAgentConfig;
import io.github.trialiya.kb.config.model.SummarizeProperties;
import io.github.trialiya.kb.functions.GitEditFunction;
import io.github.trialiya.kb.functions.ScriptFunction;
import io.github.trialiya.kb.service.chat.ToolCatalogService;
import io.github.trialiya.kb.service.chat.ToolCatalogService.ToolInfo;
import io.github.trialiya.kb.service.chat.script.ScriptEditPolicy;
import io.github.trialiya.kb.service.file.project.ProjectCatalog;
import io.github.trialiya.kb.tools.McpToolRegistry;
import io.github.trialiya.kb.tools.McpToolRegistry.Status;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
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
 * ({@code spring.ai.openai.api-key}), the MCP bearer tokens and custom headers ({@code
 * kb.mcp.bearer-tokens} / {@code kb.mcp.headers} — only connection <em>names</em> are reported) and
 * the datasource password cannot leak into the response. Keep it that way when adding fields.
 *
 * <p>The one property record reported as a whole is {@code ChatModelProperties.ModelOption}, which
 * carries a per-model {@code base-url}/{@code api-key}; both are {@code @JsonIgnore} there, and the
 * panel gets only the {@code ownEndpoint} flag derived from them.
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
    private final ScriptProperties scriptProperties;
    private final ToolCatalogService toolCatalogService;

    /**
     * Whether scripts may actually write, as opposed to being configured to: {@code
     * kb.script.edit-enabled} is one of three gates, and the panel reports the answer rather than
     * the flag (see {@link ScriptEditPolicy}).
     */
    private final boolean scriptEditActive;

    /**
     * Whether {@code runScript} was actually handed to the model, read off the bean the way {@link
     * #gitEditActive} is — the row in the panel asks about the tool, so it is answered by the tool
     * and not by {@code kb.script.enabled}, which is only what this process read on the way to it.
     * Both sides of that derivation live in {@code ChatConfig#scriptFunction} and agree by
     * construction; the panel reports the end of it rather than the beginning, and keeps saying the
     * truth if a gate is ever added in between.
     */
    private final boolean scriptToolActive;

    /** The default project's configured opt-in — may be true while the tools are absent. */
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

    /** Connection name -> transport, from the configuration; the state comes from the registry. */
    private final Map<String, String> mcpTransports;

    /** Absent when {@code kb.mcp.enabled=false} — then there is nothing connected to report. */
    private final @Nullable McpToolRegistry mcpToolRegistry;

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
            ScriptProperties scriptProperties,
            ToolCatalogService toolCatalogService,
            ScriptEditPolicy scriptEditPolicy,
            ObjectProvider<ScriptFunction> scriptFunction,
            ObjectProvider<GitEditFunction> gitEditFunction,
            ObjectProvider<McpSseClientProperties> sseProperties,
            ObjectProvider<McpStreamableHttpClientProperties> streamableHttpProperties,
            ObjectProvider<McpStdioClientProperties> stdioProperties,
            ObjectProvider<McpToolRegistry> mcpToolRegistry,
            ProjectCatalog projectCatalog,
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
        this.scriptProperties = scriptProperties;
        this.toolCatalogService = toolCatalogService;
        this.scriptEditActive = scriptEditPolicy.enabled();
        this.scriptToolActive = scriptFunction.getIfAvailable() != null;
        this.gitEditEnabled = projectCatalog.defaultProject().editEnabled();
        this.gitEditActive = gitEditFunction.getIfAvailable() != null;
        this.requestTimeout = requestTimeout;
        this.retryMaxAttempts = retryMaxAttempts;
        this.maxFileSize = maxFileSize;
        this.maxRequestSize = maxRequestSize;
        this.mcpToolRegistry = mcpToolRegistry.getIfAvailable();
        this.mcpTransports =
                mcpTransports(sseProperties, streamableHttpProperties, stdioProperties);
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
                        new McpInfo(
                                mcpProperties.enabled(),
                                mcpProperties.retryIntervalMs(),
                                mcpConnections()),
                        new UploadLimits(maxFileSize.toBytes(), maxRequestSize.toBytes())),
                scriptSection());
    }

    /**
     * The tools the model can call right now, for «Настройки → Инструменты». A separate endpoint
     * rather than a section of the snapshot above: it is descriptions and argument schemas — one
     * group's worth of reading, and several times the size of the whole configuration snapshot the
     * other groups share.
     */
    @GetMapping("/tools")
    public List<ToolInfo> getTools() {
        return toolCatalogService.tools();
    }

    private ScriptSection scriptSection() {
        ScriptProperties.Limits limits = scriptProperties.limits();
        return new ScriptSection(
                scriptProperties.enabled(),
                scriptToolActive,
                scriptProperties.editEnabled(),
                scriptEditActive,
                scriptProperties.timeout().toSeconds(),
                scriptProperties.maxTimeout().toSeconds(),
                scriptProperties.cancelPoll().toMillis(),
                new ScriptLimits(
                        limits.maxFilesRead(),
                        limits.maxBytesRead().toBytes(),
                        limits.maxCalls(),
                        limits.maxLogChars(),
                        limits.maxResultChars(),
                        limits.maxEditedFiles(),
                        limits.maxEditedBytes().toBytes()));
    }

    /**
     * The configured MCP servers with the state of each one's last probe — never their URLs,
     * commands, bearer tokens or the error text a failed connection produced (a connection error
     * quotes the address it failed to reach). A connection the registry does not know about is
     * reported as {@code PENDING}: that is the honest answer both before the first probe and when
     * MCP is switched off entirely.
     */
    private List<McpConnection> mcpConnections() {
        Map<String, McpToolRegistry.ConnectionStatus> probed =
                mcpToolRegistry == null
                        ? Map.of()
                        : mcpToolRegistry.statuses().stream()
                                .collect(
                                        Collectors.toMap(
                                                McpToolRegistry.ConnectionStatus::name,
                                                status -> status));
        return mcpTransports.entrySet().stream()
                .map(
                        entry -> {
                            McpToolRegistry.ConnectionStatus status = probed.get(entry.getKey());
                            return new McpConnection(
                                    entry.getKey(),
                                    entry.getValue(),
                                    status == null ? Status.PENDING : status.status(),
                                    status == null ? 0 : status.toolCount());
                        })
                .toList();
    }

    /**
     * Connection name -> transport, as configured. Every property bean is optional: with {@code
     * spring.ai.mcp.client.*} left unconfigured the starter registers none of them.
     */
    @SuppressWarnings("removal")
    private static Map<String, String> mcpTransports(
            ObjectProvider<McpSseClientProperties> sseProperties,
            ObjectProvider<McpStreamableHttpClientProperties> streamableHttpProperties,
            ObjectProvider<McpStdioClientProperties> stdioProperties) {
        Map<String, String> transports = new TreeMap<>();
        sseProperties.ifAvailable(p -> collect(transports, "sse", p.getConnections()));
        streamableHttpProperties.ifAvailable(
                p -> collect(transports, "streamable-http", p.getConnections()));
        stdioProperties.ifAvailable(p -> collect(transports, "stdio", p.getConnections()));
        return Collections.unmodifiableMap(transports);
    }

    private static void collect(
            Map<String, String> target, String transport, Map<String, ?> connections) {
        connections.keySet().forEach(name -> target.put(name, transport));
    }

    public record AiConfigResponse(
            ChatSection chat,
            EmbeddingSection embedding,
            SearchCodebaseSection searchCodebase,
            SummarizeProperties summarize,
            SearchConfiguration search,
            ToolsSection tools,
            ScriptSection script) {}

    public record ChatSection(
            ModelOption defaultModel, List<ModelOption> models, ChatOptions options) {}

    /**
     * Core inference parameters from {@code spring.ai.openai.chat.options.*}, plus the limits that
     * shape a conversation around them: the SDK request deadline ({@code spring.ai.openai.timeout}
     * — the thing that actually cancels a call), the retry count and the SSE subscription window.
     *
     * <p>No message-count limit is reported here because none exists: chat memory is append-only
     * (see {@code ChatHistoryMemory}), and the real context limit is the summarization thresholds
     * already reported in {@code summarize}.
     */
    public record ChatOptions(
            @Nullable Integer maxTokens,
            @Nullable Double temperature,
            @Nullable Double topP,
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

    /**
     * @param retryIntervalMs how often a connection that is not up is probed again, so the panel
     *     can say when a {@code DOWN} row is expected to change on its own
     */
    public record McpInfo(boolean enabled, long retryIntervalMs, List<McpConnection> connections) {}

    /**
     * @param status the last probe of this connection (see {@code McpToolRegistry})
     * @param toolCount how many tools it is currently contributing — zero unless {@code UP}
     */
    public record McpConnection(String name, String transport, Status status, int toolCount) {}

    public record UploadLimits(long maxFileSizeBytes, long maxRequestSizeBytes) {}

    /**
     * {@code kb.script.*} — the sandbox the {@code runScript} tool executes in, and the budgets one
     * run may spend. The guide resources are deliberately absent: they are prompt text, not
     * configuration a reader of this panel can act on.
     *
     * @param enabled {@code kb.script.enabled} — the configured opt-in
     * @param active whether {@code runScript} was actually handed to the model — the bean, not the
     *     flag it is derived from (see {@link #scriptToolActive})
     * @param editEnabled {@code kb.script.edit-enabled} — the configured opt-in for writes
     * @param editActive whether {@code kb.edit}/{@code kb.create} are actually bound, i.e. all
     *     three gates of {@link ScriptEditPolicy} agree
     */
    public record ScriptSection(
            boolean enabled,
            boolean active,
            boolean editEnabled,
            boolean editActive,
            long timeoutSeconds,
            long maxTimeoutSeconds,
            long cancelPollMillis,
            ScriptLimits limits) {}

    /** {@code kb.script.limits.*}, with the two {@code DataSize} values flattened to bytes. */
    public record ScriptLimits(
            int maxFilesRead,
            long maxBytesRead,
            int maxCalls,
            int maxLogChars,
            int maxResultChars,
            int maxEditedFiles,
            long maxEditedBytes) {}
}
