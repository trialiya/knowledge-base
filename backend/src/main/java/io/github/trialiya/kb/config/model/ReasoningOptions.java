package io.github.trialiya.kb.config.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.bind.Name;

/**
 * Уровни рассуждений, из которых пользователь выбирает в чате, — блок {@code reasoning} у модели
 * ({@code kb.chat.default-model} / {@code kb.chat.models[]}). Модель без блока селектора не
 * получает: умеет ли она рассуждать и чем это включается, знает только конфигурация.
 *
 * <p>Уровень — это не значение одного поля, а то, что надо отправить провайдеру: у OpenAI это
 * {@code reasoning_effort}, у DeepSeek/GLM — {@code thinking.type}, у Qwen на vLLM — {@code
 * chat_template_kwargs.enable_thinking}, у OpenRouter — {@code reasoning.effort}. Поэтому у уровня
 * два необязательных «что отправить», и задавать можно любое из них или оба.
 *
 * @param defaultLevel уровень, на котором чат идёт, пока пользователь не выбрал другой. Не задан —
 *     ничего не отправляется, и решают опции самой модели ({@code spring.ai.openai.chat.options})
 *     или провайдер; селектор тогда показывает пункт «по умолчанию»
 * @param levels уровни в порядке показа в селекторе
 */
public record ReasoningOptions(
        @Name("default") @JsonProperty("default") @Nullable String defaultLevel, List<Level> levels) {

    public ReasoningOptions {
        defaultLevel = ConfigValues.trimToNull(defaultLevel);
        levels = levels == null ? List.of() : List.copyOf(levels);
        final Set<String> ids = new HashSet<>();
        for (final Level level : levels) {
            if (!ids.add(level.id())) {
                throw new IllegalArgumentException("reasoning: level \"" + level.id() + "\" is listed twice");
            }
        }
        if (defaultLevel != null && !ids.contains(defaultLevel)) {
            throw new IllegalArgumentException(
                    "reasoning.default \"" + defaultLevel + "\" is not one of the levels " + ids);
        }
    }

    /**
     * Уровень, на котором пойдёт запрос: названный, если он у этой модели есть, иначе — умолчание.
     * Чужой уровень не ошибка: выбор хранится у чата, а модель в нём переключают, и уровень,
     * выбранный под прежнюю, у новой может не существовать.
     */
    public Optional<Level> resolve(@Nullable String requested) {
        return find(requested).or(() -> find(defaultLevel));
    }

    public Optional<Level> find(@Nullable String id) {
        return id == null
                ? Optional.empty()
                : levels.stream().filter(l -> id.equals(l.id())).findFirst();
    }

    /**
     * Один уровень. Наружу ({@code GET /api/chats/models}) уходят только {@code id} и {@code
     * label}: что именно уровень отправляет, селектору не нужно, а {@code extra-body} у шлюза бывает
     * и маршрутизацией, которую в браузере показывать незачем.
     *
     * @param id то, что выбирают в чате и хранят у него ({@code chat_topic.reasoning})
     * @param label подпись в селекторе; не задана — фронт подписывает известные id ({@code low},
     *     {@code high}, {@code off}, …) сам, остальные показывает как есть
     * @param reasoningEffort значение {@code reasoning_effort} запроса
     * @param extraBody поля тела запроса поверх {@code extra-body} модели. Слияние — по ключам
     *     верхнего уровня (так сливает {@code OpenAiChatOptions}): {@code thinking} уровня заменяет
     *     {@code thinking} модели целиком, остальные её поля остаются
     */
    public record Level(
            String id,
            @Nullable String label,
            @JsonIgnore @Nullable String reasoningEffort,
            @JsonIgnore @Nullable Map<String, Object> extraBody) {

        public Level {
            final String trimmed = ConfigValues.trimToNull(id);
            if (trimmed == null) {
                throw new IllegalArgumentException("reasoning: a level needs an id");
            }
            id = trimmed;
            label = ConfigValues.trimToNull(label);
            reasoningEffort = ConfigValues.trimToNull(reasoningEffort);
            extraBody = extraBody == null || extraBody.isEmpty() ? null : Map.copyOf(extraBody);
        }

        /**
         * Кладёт уровень в опции запроса. Только то, что задано: опции запроса ложатся поверх опций
         * модели, и незаданное здесь оставляет настроенное у неё как есть. {@code extraBody} модели
         * подмешивать не нужно — {@code OpenAiChatOptions} сам сливает его с этим по ключам.
         */
        public void applyTo(OpenAiChatOptions.Builder options) {
            if (reasoningEffort != null) {
                options.reasoningEffort(reasoningEffort);
            }
            if (extraBody != null) {
                options.extraBody(extraBody);
            }
        }
    }
}
