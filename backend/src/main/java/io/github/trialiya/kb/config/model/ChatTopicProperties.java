package io.github.trialiya.kb.config.model;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Автоматическое название чата — фоновый запрос после ответа модели ({@code AiTopicService}).
 * Значения по умолчанию и смысл каждого ключа — в {@code application.yaml}, раздел {@code
 * kb.chat.topic}.
 *
 * <p>Модель здесь своя по той же причине, что у суммаризации ({@link SummarizeProperties#model}):
 * запрос идёт в фоне, выбранная в чате модель на него не влияет, и модель подешевле ничем не
 * оплачивается. Эндпоинт — всё тот же {@code spring.ai.openai.base-url}.
 */
@ConfigurationProperties(prefix = "kb.chat.topic")
public record ChatTopicProperties(
        boolean enabled,
        @Nullable String model,
        @Nullable String reasoningEffort,
        @Nullable String thinking) {

    public ChatTopicProperties {
        model = ConfigValues.trimToNull(model);
        reasoningEffort = ConfigValues.trimToNull(reasoningEffort);
        thinking = ConfigValues.trimToNull(thinking);
    }
}
