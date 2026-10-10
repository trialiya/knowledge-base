package io.github.trialiya.kb.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.trialiya.kb.config.model.SubAgentConfig;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

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

    private static SubAgentConfig config(String reasoningEffort, String thinking, Double temperature) {
        return new SubAgentConfig(
                true, "sub-model", 12000, 30, java.util.Set.of(), reasoningEffort, thinking, temperature);
    }
}
