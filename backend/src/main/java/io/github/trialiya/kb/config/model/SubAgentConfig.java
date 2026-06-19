package io.github.trialiya.kb.config.model;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the search sub-agent (an "agent-as-tool" exposed to the main chat model as {@code
 * searchCodebase}). Bound from:
 *
 * <pre>
 * kb:
 *   search:
 *     subagent:
 *       enabled: true
 *       model-id: ...        # may differ from the main chat model (e.g. cheaper/faster)
 *       max-tokens: 12000
 *       max-iterations: 6    # hard cap on tool-call rounds before forced summarization
 *       allowed-tools:       # read-only tool names the sub-agent may call
 *         - grepContent
 *         - ...
 * </pre>
 *
 * <p>{@code allowedTools} is the structural recursion guard: {@code searchCodebase} itself must
 * never appear here, so the sub-agent can never call itself.
 */
@ConfigurationProperties(prefix = "kb.search.subagent")
public record SubAgentConfig(
        boolean enabled,
        String modelId,
        int maxTokens,
        int maxIterations,
        Set<String> allowedTools) {}
