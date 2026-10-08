package io.github.trialiya.kb.config.model;

import org.jspecify.annotations.Nullable;

/**
 * Модель фонового запроса — того, что идёт за спиной разговора и выбора модели в чате не наследует:
 * сводка истории ({@link SummarizeProperties}), название чата ({@link ChatTopicProperties}). Как
 * эти значения ложатся на запрос — {@code BackgroundCallOptions}.
 */
public interface BackgroundModelProperties extends ReasoningSettings {

    /** id модели запроса; {@code null} — модель чата по умолчанию. */
    @Nullable
    String model();
}
