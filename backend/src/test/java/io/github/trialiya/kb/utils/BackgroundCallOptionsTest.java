package io.github.trialiya.kb.utils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ChatTopicProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Фоновый запрос ложится поверх опций модели, а не вместо них: {@code extra-body} модели
 * (маршрутизация шлюза) остаётся, {@code thinking} запроса заменяет модельный целиком, а не
 * заданное не трогает настроенного у модели.
 */
class BackgroundCallOptionsTest {

    private static final Map<String, Object> MODEL_BODY = Map.of(
            "provider", Map.of("order", "a"),
            "thinking", Map.of("type", "enabled", "budget_tokens", 2048));

    private final OpenAiChatModel chatModel = mock(OpenAiChatModel.class);

    BackgroundCallOptionsTest() {
        when(chatModel.getOptions())
                .thenReturn(OpenAiChatOptions.builder()
                        .model("chat-model")
                        .reasoningEffort("high")
                        .extraBody(MODEL_BODY)
                        .build());
    }

    @Test
    void configuredFieldsGoOverTheModelsOwn() {
        final OpenAiChatOptions options = BackgroundCallOptions.of(
                        chatModel, new ChatTopicProperties(true, "cheap-model", "low", "disabled"))
                .build();

        assertThat(options.getModel()).isEqualTo("cheap-model");
        assertThat(options.getReasoningEffort()).isEqualTo("low");
        assertThat(options.getExtraBody())
                .isEqualTo(Map.of("provider", Map.of("order", "a"), "thinking", Map.of("type", "disabled")));
    }

    @Test
    void unsetFieldsLeaveTheModelsOptionsAsTheyAre() {
        final OpenAiChatOptions options = BackgroundCallOptions.of(
                        chatModel, new ChatTopicProperties(true, null, null, null))
                .build();

        assertThat(options.getModel()).isEqualTo("chat-model");
        assertThat(options.getReasoningEffort()).isEqualTo("high");
        assertThat(options.getExtraBody()).isEqualTo(MODEL_BODY);
    }
}
