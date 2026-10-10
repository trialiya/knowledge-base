package io.github.trialiya.kb.config.model;

import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the search sub-agent (an "agent-as-tool" exposed to the main chat model as {@code
 * searchCodebase}). Defaults and the meaning of each key are in {@code application.yaml}, section
 * {@code kb.search.subagent}.
 *
 * <p>{@code allowedTools} is the structural recursion guard: {@code searchCodebase} itself must
 * never appear here, so the sub-agent can never call itself.
 *
 * <p>The sampling fields are the sub-agent's own, not inherited from {@code
 * spring.ai.openai.chat.options}: its requests are built from scratch (see {@code
 * SearchAgentService#requestOptions}). {@code null} means the field is not sent at all; for the
 * reasoning pair see {@link ReasoningSettings}.
 *
 * @param reasoningEffort {@code reasoning_effort} of every sub-agent request
 * @param thinking {@code type} of the {@code thinking} field in the request body
 * @param temperature sampling temperature; {@code null} for the endpoint's default
 */
@ConfigurationProperties(prefix = "kb.search.subagent")
public record SubAgentConfig(
        boolean enabled,
        String modelId,
        int maxTokens,
        int maxIterations,
        Set<String> allowedTools,
        @Nullable String reasoningEffort,
        @Nullable String thinking,
        @Nullable Double temperature)
        implements ReasoningSettings {

    public SubAgentConfig {
        reasoningEffort = ConfigValues.trimToNull(reasoningEffort);
        thinking = ConfigValues.trimToNull(thinking);
    }
}
