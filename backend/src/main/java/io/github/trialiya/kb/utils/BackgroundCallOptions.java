package io.github.trialiya.kb.utils;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Опции фонового запроса к модели — того, что идёт за спиной разговора и ничьего выбора модели не
 * наследует: сводка истории, название чата. Стоят на клиенте такого запроса, а не на вызове: клиент
 * у каждого свой, и другого запроса на нём не бывает.
 *
 * <p>Начинать обязательно с опций самой модели, а не с чистого {@code OpenAiChatOptions.builder()}:
 * у пустого билдера модель не пустая, а своя по умолчанию (библиотечная), и опции запроса кладутся
 * ПОВЕРХ настроенных у модели — то есть чистый билдер молча увёл бы каждый запрос на чужую модель,
 * о которой в конфигурации деплоя нет ни слова. Отсюда же и «не задано — не ставим»: настроенное у
 * модели остаётся как есть.
 */
public final class BackgroundCallOptions {

    private BackgroundCallOptions() {}

    /**
     * @param model id модели запроса; {@code null} — модель чата по умолчанию
     * @param reasoningEffort {@code reasoning_effort}; {@code null} — поле не отправляется
     * @param thinking {@code type} поля {@code thinking} в теле запроса; {@code null} — поле не
     *     отправляется вовсе: эндпоинт, который его не знает, отвергает весь запрос
     */
    public static OpenAiChatOptions.Builder of(
            OpenAiChatModel chatModel,
            @Nullable String model,
            @Nullable String reasoningEffort,
            @Nullable String thinking) {
        final OpenAiChatOptions.Builder options = chatModel.getOptions().mutate();
        if (model != null) {
            options.model(model);
        }
        if (reasoningEffort != null) {
            options.reasoningEffort(reasoningEffort);
        }
        if (thinking != null) {
            options.extraBody(Map.of("thinking", Map.of("type", thinking)));
        }
        return options;
    }

    /**
     * Пустая строка от {@code ${ПЕРЕМЕННАЯ:}} в конфигурации — «не задано», а не «задано пустым».
     */
    public static @Nullable String trimToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
