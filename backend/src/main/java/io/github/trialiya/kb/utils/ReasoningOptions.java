package io.github.trialiya.kb.utils;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * Насколько модели «размышлять» в запросе, который настраивается сам, а не наследует чат: фоновые
 * запросы ({@link BackgroundCallOptions}) и сабагент поиска. Два написания одной ручки — {@code
 * reasoning_effort} у OpenAI и {@code thinking.type} у распространённого вендорского расширения;
 * какое понимает эндпоинт — его дело.
 *
 * <p>Не заданное ({@code null}) не ставится вовсе: эндпоинт, который не знает поле {@code thinking},
 * отвергает весь запрос, а не игнорирует его.
 */
public final class ReasoningOptions {

    private ReasoningOptions() {}

    /**
     * @param baseExtraBody extra-body, поверх которого ложится {@code thinking}: остальные его поля
     *     (маршрутизация, флаги провайдера) запросу нужны так же. {@code null} — запрос extra-body не
     *     наследует, и {@code thinking} будет в нём единственным полем
     */
    public static OpenAiChatOptions.Builder apply(
            OpenAiChatOptions.Builder options,
            @Nullable String reasoningEffort,
            @Nullable String thinking,
            @Nullable Map<String, Object> baseExtraBody) {
        if (reasoningEffort != null) {
            options.reasoningEffort(reasoningEffort);
        }
        if (thinking != null) {
            final Map<String, Object> body = baseExtraBody == null ? new HashMap<>() : new HashMap<>(baseExtraBody);
            body.put("thinking", Map.of("type", thinking));
            options.extraBody(body);
        }
        return options;
    }
}
