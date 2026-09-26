package io.github.trialiya.kb.config.model;

import org.jspecify.annotations.Nullable;

/**
 * Модель фонового запроса — того, что идёт за спиной разговора и выбора модели в чате не наследует:
 * сводка истории ({@link SummarizeProperties}), название чата ({@link ChatTopicProperties}). Как
 * эти значения ложатся на запрос — {@code BackgroundCallOptions}.
 */
public interface BackgroundModelProperties {

    /** id модели запроса; {@code null} — модель чата по умолчанию. */
    @Nullable String model();

    /** {@code reasoning_effort}; {@code null} — поле не отправляется. */
    @Nullable String reasoningEffort();

    /**
     * {@code type} поля {@code thinking} в теле запроса; {@code null} — поле не отправляется вовсе:
     * эндпоинт, который его не знает, отвергает весь запрос.
     */
    @Nullable String thinking();
}
