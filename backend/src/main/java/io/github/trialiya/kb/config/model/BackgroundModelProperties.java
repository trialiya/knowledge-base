package io.github.trialiya.kb.config.model;

import org.jspecify.annotations.Nullable;

/**
 * Модель фонового запроса — того, что идёт за спиной разговора и выбора модели в чате не наследует:
 * сводка истории ({@link SummarizeProperties}), название чата ({@link ChatTopicProperties}). Как
 * модель ложится на запрос — {@code BackgroundCallOptions}, как поля размышлений — {@link
 * ReasoningSettings#applyTo}.
 */
public interface BackgroundModelProperties extends ReasoningSettings {

    /** id модели запроса; {@code null} — модель чата по умолчанию. */
    @Nullable
    String model();
}
