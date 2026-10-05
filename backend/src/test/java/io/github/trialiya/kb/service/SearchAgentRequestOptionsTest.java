package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.model.SubAgentConfig;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * What a sub-agent request carries is decided by its own settings alone: a field left unset is not
 * sent, because an endpoint that does not know {@code thinking} rejects the whole request, and an
 * OpenAI reasoning model rejects any temperature but its own.
 */
class SearchAgentRequestOptionsTest {

    private static final Duration DEADLINE = Duration.ofMinutes(10);

    @Test
    void unsetReasoningFieldsAreNotSent() {
        OpenAiChatOptions options = SearchAgentService.requestOptions(config(null, null, null), DEADLINE)
                .build();

        assertThat(options.getModel()).isEqualTo("sub-model");
        assertThat(options.getMaxTokens()).isEqualTo(12000);
        assertThat(options.getTimeout()).isEqualTo(DEADLINE);
        assertThat(options.getTemperature()).isNull();
        assertThat(options.getReasoningEffort()).isNull();
        assertThat(options.getExtraBody()).isNullOrEmpty();
    }

    @Test
    void configuredReasoningFieldsAreSent() {
        OpenAiChatOptions options = SearchAgentService.requestOptions(config("low", "disabled", 0.6), DEADLINE)
                .build();

        assertThat(options.getTemperature()).isEqualTo(0.6);
        assertThat(options.getReasoningEffort()).isEqualTo("low");
        assertThat(options.getExtraBody()).isEqualTo(Map.of("thinking", Map.of("type", "disabled")));
    }

    /** An empty environment variable is "not set", not an empty value sent to the provider. */
    @Test
    void emptyEnvironmentValuesBindAsUnset() {
        SubAgentConfig bound = new Binder(new MapConfigurationPropertySource(Map.of(
                        "kb.search.subagent.enabled", "true",
                        "kb.search.subagent.model-id", "sub-model",
                        "kb.search.subagent.max-tokens", "12000",
                        "kb.search.subagent.max-iterations", "30",
                        "kb.search.subagent.reasoning-effort", " ",
                        "kb.search.subagent.thinking", "",
                        "kb.search.subagent.temperature", "")))
                .bind("kb.search.subagent", SubAgentConfig.class)
                .get();

        assertThat(bound.reasoningEffort()).isNull();
        assertThat(bound.thinking()).isNull();
        assertThat(bound.temperature()).isNull();
    }

    private static SubAgentConfig config(String reasoningEffort, String thinking, Double temperature) {
        return new SubAgentConfig(
                true, "sub-model", 12000, 30, java.util.Set.of(), reasoningEffort, thinking, temperature);
    }
}
