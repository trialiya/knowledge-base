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
     * Кладёт заданное поверх того, что уже стоит в {@code options}, — на любом билдере, в том числе
     * на опциях модели ({@code mutate()}). Через {@code combineWith}, а не сеттеры: сеттер {@code
     * extraBody} заменил бы карту целиком и стёр бы {@code extra-body} модели (маршрутизацию,
     * флаги провайдера), а {@code combineWith} сливает её по ключам верхнего уровня, как {@code
     * ChatClient} сливает уровень чата ({@link ReasoningOptions.Level#applyTo}) с опциями модели.
     */
    default void applyTo(OpenAiChatOptions.Builder options) {
        final @Nullable String reasoningEffort = reasoningEffort();
        final @Nullable String thinking = thinking();
        final OpenAiChatOptions.Builder overrides = OpenAiChatOptions.builder();
        if (reasoningEffort != null) {
            overrides.reasoningEffort(reasoningEffort);
        }
        if (thinking != null) {
            overrides.extraBody(Map.of("thinking", Map.of("type", thinking)));
        }
        options.combineWith(overrides);
    }
}
