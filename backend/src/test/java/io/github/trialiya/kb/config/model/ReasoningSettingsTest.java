package io.github.trialiya.kb.config.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Поля размышлений ложатся поверх билдера, на котором уже стоит {@code extra-body} модели, и не
 * стирают его — у настроек фоновых запросов и сабагента и у уровня чата одинаково.
 */
class ReasoningSettingsTest {

    private static final Map<String, Object> MODEL_BODY = Map.of(
            "provider", Map.of("order", "a"),
            "thinking", Map.of("type", "enabled", "budget_tokens", 2048));

    private static OpenAiChatOptions.Builder modelOptions() {
        return OpenAiChatOptions.builder().model("chat-model").extraBody(MODEL_BODY);
    }

    @Test
    void settingsKeepTheModelsOtherExtraBodyFields() {
        final OpenAiChatOptions.Builder options = modelOptions();

        new ChatTopicProperties(true, null, "low", "disabled").applyTo(options);

        final OpenAiChatOptions built = options.build();
        assertThat(built.getReasoningEffort()).isEqualTo("low");
        assertThat(built.getExtraBody())
                .isEqualTo(Map.of("provider", Map.of("order", "a"), "thinking", Map.of("type", "disabled")));
    }

    @Test
    void unsetSettingsLeaveTheBuilderAsItIs() {
        final OpenAiChatOptions.Builder options = modelOptions();

        new ChatTopicProperties(true, null, null, null).applyTo(options);

        final OpenAiChatOptions built = options.build();
        assertThat(built.getModel()).isEqualTo("chat-model");
        assertThat(built.getReasoningEffort()).isNull();
        assertThat(built.getExtraBody()).isEqualTo(MODEL_BODY);
    }

    @Test
    void chatLevelKeepsTheModelsOtherExtraBodyFields() {
        final OpenAiChatOptions.Builder options = modelOptions();

        new ReasoningOptions.Level("off", null, null, Map.of("thinking", Map.of("type", "disabled"))).applyTo(options);

        assertThat(options.build().getExtraBody())
                .isEqualTo(Map.of("provider", Map.of("order", "a"), "thinking", Map.of("type", "disabled")));
    }

    @Test
    void theModelsExtraBodyIsNotModifiedInPlace() {
        final Map<String, Object> modelBody = new HashMap<>(MODEL_BODY);
        final OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().extraBody(modelBody);

        new ChatTopicProperties(true, null, null, "disabled").applyTo(options);

        assertThat(modelBody).isEqualTo(MODEL_BODY);
        assertThat(options.build().getExtraBody())
                .isEqualTo(Map.of("provider", Map.of("order", "a"), "thinking", Map.of("type", "disabled")));
    }
}
