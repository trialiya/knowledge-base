package io.github.trialiya.kb.config.model;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Насколько модели «размышлять» в запросе, который задаёт это своими ключами и выбора в чате не
 * наследует: фоновые запросы ({@link BackgroundModelProperties}) и сабагент поиска ({@link
 * SubAgentConfig}). Уровни, которые выбирают в самом чате, — {@link ReasoningOptions}.
 *
 * <p>Два написания одной ручки, какое понимает эндпоинт — его дело: {@code reasoning_effort} у
 * OpenAI и {@code thinking.type} у распространённого вендорского расширения. {@code null} — поле не
 * отправляется вовсе: эндпоинт, который не знает {@code thinking}, отвергает весь запрос, а не
 * игнорирует поле.
 */
public interface ReasoningSettings {

    /** {@code reasoning_effort} запроса; {@code null} — не отправляется. */
    @Nullable
    String reasoningEffort();

    /** {@code type} поля {@code thinking} в теле запроса; {@code null} — не отправляется. */
    @Nullable
    String thinking();

    /**
     * Кладёт заданное в опции запроса, как {@link ReasoningOptions.Level#applyTo}. {@code extraBody}
     * здесь — только {@code thinking}, и сеттер билдера заменяет карту целиком: звать на чистом
     * билдере, а с опциями модели сливать через {@code combineWith} (по ключам верхнего уровня —
     * {@code thinking} заменяется целиком, остальные поля модели остаются).
     */
    default void applyTo(OpenAiChatOptions.Builder options) {
        final @Nullable String reasoningEffort = reasoningEffort();
        final @Nullable String thinking = thinking();
        if (reasoningEffort != null) {
            options.reasoningEffort(reasoningEffort);
        }
        if (thinking != null) {
            options.extraBody(Map.of("thinking", Map.of("type", thinking)));
        }
    }
}
