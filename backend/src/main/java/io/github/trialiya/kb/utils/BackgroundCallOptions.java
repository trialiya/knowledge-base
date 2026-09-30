package io.github.trialiya.kb.utils;

import io.github.trialiya.kb.config.model.BackgroundModelProperties;
import java.util.HashMap;
import java.util.Map;
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

    private static OpenAiChatOptions.Builder of(OpenAiChatModel chatModel, BackgroundModelProperties properties) {
        final OpenAiChatOptions.Builder options = chatModel.getOptions().mutate();
        final @Nullable String model = properties.model();
        final @Nullable String reasoningEffort = properties.reasoningEffort();
        final @Nullable String thinking = properties.thinking();
        if (model != null) {
            options.model(model);
        }
        if (reasoningEffort != null) {
            options.reasoningEffort(reasoningEffort);
        }
        if (thinking != null) {
            // Поверх extra-body модели, а не вместо него: остальные поля (маршрутизация, флаги
            // провайдера) фоновому запросу нужны так же, как запросу чата.
            final Map<String, Object> body = new HashMap<>();
            final @Nullable Map<String, Object> configured =
                    chatModel.getOptions().getExtraBody();
            if (configured != null) {
                body.putAll(configured);
            }
            body.put("thinking", Map.of("type", thinking));
            options.extraBody(body);
        }
        return options;
    }
}
