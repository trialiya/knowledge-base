package io.github.trialiya.kb.utils;

import io.github.trialiya.kb.config.model.BackgroundModelProperties;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
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
     * Клиент фонового запроса: модель чата с опциями из {@code properties} поверх её собственных.
     * Системный промпт, инструменты и прочее вызывающий дописывает сам.
     */
    public static ChatClient.Builder clientBuilder(OpenAiChatModel chatModel, BackgroundModelProperties properties) {
        return ChatClient.builder(chatModel).defaultOptions(of(chatModel, properties));
    }

    /**
     * Своё — на чистом билдере, а с опциями модели сливается через {@code combineWith}: так ложатся
     * и уровни рассуждений чата ({@code ReasoningOptions.Level#applyTo} поверх опций модели в {@code
     * ChatClient}), и {@code extra-body} модели (маршрутизация, флаги провайдера) остаётся под
     * {@code thinking} запроса, а не стирается им.
     */
    static OpenAiChatOptions.Builder of(OpenAiChatModel chatModel, BackgroundModelProperties properties) {
        final OpenAiChatOptions.Builder overrides = OpenAiChatOptions.builder();
        final @Nullable String model = properties.model();
        if (model != null) {
            overrides.model(model);
        }
        properties.applyTo(overrides);
        return chatModel.getOptions().mutate().combineWith(overrides);
    }
}
