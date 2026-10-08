package io.github.trialiya.kb.config.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "kb.chat")
public record ChatModelProperties(ModelOption defaultModel, List<ModelOption> models) {

    public ChatModelProperties {
        models = models == null ? List.of() : List.copyOf(models);
        if (defaultModel != null && defaultModel.hasOwnEndpoint()) {
            throw new IllegalArgumentException(
                    "kb.chat.default-model: base-url/api-key belong to spring.ai.openai.* here —"
                            + " the default model is served by the autoconfigured connection, and a"
                            + " second one for the same model would only shadow it. To give it an"
                            + " endpoint of its own, list it under kb.chat.models with the same id.");
        }
    }

    /**
     * @param weak whether this model needs the {@code runScript} tutorial half ({@code
     *     script-run-extended.md} and its edit counterpart) spelled out — see {@code
     *     ScriptGuideService}. Defaults to {@code true} (the safe assumption for a model nobody has
     *     rated yet) when a deployment's config omits the field entirely.
     * @param streamUsage whether this model's endpoint understands {@code
     *     stream_options.include_usage}. On (the default) it is what makes token counting possible
     *     at all: without the field an OpenAI-compatible endpoint sends no usage in stream mode,
     *     and a run has nothing to count. Turn it off only for a gateway that rejects the field
     *     outright — such a gateway fails every run, and the price of the switch is that this
     *     model's answers carry no token figure.
     * @param contextTokens the model's context window, in tokens. Not a limit the app enforces —
     *     the provider does that — but the number every automatic decision about the size of the
     *     history is measured against: when a written summary stops waiting for a pause and folds
     *     into the history ({@code kb.chat.summarize.apply-at-ratio}), and when the chat compacts
     *     itself before the next answer ({@code kb.chat.summarize.auto-compact-at-ratio}). It lives
     *     here, per model, because the windows differ by an order of magnitude between them, and a
     *     share of the wrong window is either a chat that rewrites its history for nothing or one
     *     that never does it in time. Omitted — this model gets neither decision: its summaries
     *     wait for the pause, and it is never compacted automatically.
     * @param baseUrl the OpenAI-compatible endpoint this model lives behind. Omitted — the model is
     *     served by {@code spring.ai.openai.base-url} with the deployment's own key, which is the
     *     usual case. Set — the model gets a connection of its own (see {@code ChatModelRegistry}),
     *     and then {@code apiKey} is mandatory: a foreign host and the default host's token is
     *     never a combination anyone means, so it fails at startup rather than at the first
     *     request. Only meaningful under {@code kb.chat.models}: the default model's endpoint is
     *     {@code spring.ai.openai.*}, so naming one on {@code kb.chat.default-model} is rejected.
     * @param apiKey the token for this model. Inherited from {@code spring.ai.openai.api-key} when
     *     absent. May be set on its own — same host, separate token (separate quota or account) —
     *     but never omitted alongside a {@code baseUrl}.
     * @param replayReasoning whether this model gets its own past reasoning ({@code
     *     reasoning_content}) back on the assistant messages of the history — only where an answer
     *     came with one, so a model that does not reason sends nothing extra. A thinking model with
     *     tools (DeepSeek) expects it — without it the provider renders a finished turn differently
     *     from the one in progress, and the prompt cache breaks right after the previous question
     *     on every turn that follows a turn with tool calls. On by default. Turn it off with care
     *     and only for an endpoint that rejects the field outright (spring-ai#6968): it changes
     *     every past turn that carried reasoning, so the chats' cached prefixes are invalidated at
     *     once, and from then on every turn after one with tool calls is paid again in full. The
     *     reasoning stays stored either way — turning it back on needs no migration of history.
     * @param reasoning the reasoning levels a user picks from in the chat, and what each of them
     *     sends — see {@link ReasoningOptions}. Omitted — the model gets no selector, and its
     *     requests carry only what its own options say.
     */
    public record ModelOption(
            String id,
            String label,
            @DefaultValue("true") boolean weak,
            @DefaultValue("true") boolean streamUsage,
            @Nullable Integer contextTokens,
            @JsonIgnore @Nullable String baseUrl,
            @JsonIgnore @Nullable String apiKey,
            @DefaultValue("true") boolean replayReasoning,
            @Nullable ReasoningOptions reasoning) {

        public ModelOption {
            baseUrl = ConfigValues.trimToNull(baseUrl);
            apiKey = ConfigValues.trimToNull(apiKey);
            if (baseUrl != null && apiKey == null) {
                throw new IllegalArgumentException("kb.chat model \""
                        + id
                        + "\": base-url is set, so api-key must be set too — a model on its"
                        + " own host cannot borrow the default host's token");
            }
        }

        /**
         * Hides the token. {@code @JsonIgnore} covers only the JSON path, while the record's
         * generated {@code toString} would print the raw key into any log line or diagnostic that
         * renders the properties bean.
         */
        @Override
        public String toString() {
            return ("ModelOption[id=%s, label=%s, weak=%s, streamUsage=%s, contextTokens=%s,"
                            + " baseUrl=%s, apiKey=%s, replayReasoning=%s, reasoning=%s]")
                    .formatted(
                            id,
                            label,
                            weak,
                            streamUsage,
                            contextTokens,
                            baseUrl,
                            apiKey == null ? null : "***",
                            replayReasoning,
                            reasoning);
        }

        /**
         * Whether this model needs a connection of its own rather than the shared default one.
         * Reported to the Settings panel (as {@code ownEndpoint}) because the URL and the token
         * themselves are not: a panel that shows which model talks to a separate host leaks
         * nothing, one that shows where and with what does.
         */
        @JsonProperty("ownEndpoint")
        public boolean hasOwnEndpoint() {
            return baseUrl != null || apiKey != null;
        }
    }

    public boolean isAllowed(@Nullable String id) {
        return id != null && (id.equals(defaultModel.id()) || models.stream().anyMatch(m -> id.equals(m.id())));
    }

    /**
     * Whether the model behind {@code id} needs the {@code runScript} tutorial half. {@code null}
     * means "no override for this run" — the default model's flag applies, same as {@code
     * resolveModel}'s null-means-default-model convention. An {@code id} that matches neither the
     * default nor a configured alternative (should not happen past {@link #isAllowed}) is treated
     * as weak: the tutorial is redundant text for a strong model, but its absence can leave a weak
     * one unable to use the tool at all — the cheaper mistake is the safe default.
     */
    public boolean isWeak(@Nullable String id) {
        return option(id).map(ModelOption::weak).orElse(true);
    }

    /**
     * Окно модели в токенах или {@code null}, если оно не названо. Разбор {@code id} — тот же, что
     * у {@link #isWeak}; неизвестная модель окна не имеет: автоматика, не знающая предела, обязана
     * не делать ничего, а не выбирать его за конфигурацию.
     */
    public @Nullable Integer contextTokens(@Nullable String id) {
        return option(id).map(ModelOption::contextTokens).orElse(null);
    }

    /**
     * Просить ли у эндпоинта этой модели usage в стриме. Разбор {@code id} — тот же, что у {@link
     * #isWeak}; неизвестная модель считается умеющей, потому что цена ошибки здесь обратная: лишний
     * запрос счётчика эндпоинту, который его поймёт, стоит ничего, а его отсутствие — прогон без
     * цифры навсегда.
     */
    public boolean streamUsage(@Nullable String id) {
        return option(id).map(ModelOption::streamUsage).orElse(true);
    }

    /**
     * Возвращать ли этой модели её рассуждения в истории. Разбор {@code id} — тот же, что у {@link
     * #isWeak}; неизвестная модель получает умолчание — да: рассуждение уходит, только если ответ с
     * ним пришёл, а выключают флаг поимённо, у эндпоинта, который поля не знает.
     */
    public boolean replayReasoning(@Nullable String id) {
        return option(id).map(ModelOption::replayReasoning).orElse(true);
    }

    /**
     * Уровень рассуждений, на котором пойдёт запрос к этой модели: {@code requested}, если он у неё
     * есть, иначе её {@code reasoning.default}. Пусто — отправлять нечего: у модели нет уровней,
     * или ни выбора, ни умолчания. Разбор {@code id} — тот же, что у {@link #isWeak}.
     */
    public Optional<ReasoningOptions.Level> reasoningLevel(@Nullable String id, @Nullable String requested) {
        return option(id).map(ModelOption::reasoning).flatMap(r -> r.resolve(requested));
    }

    /**
     * Есть ли такой уровень хоть у одной модели. Проверка выбора, принятого без модели на руках
     * (очередь сообщений, {@code PUT /reasoning}): чей он, решится при прогоне, а опечатку видно
     * уже сейчас.
     */
    public boolean isKnownReasoningLevel(@Nullable String level) {
        return level != null
                && Stream.concat(Stream.of(defaultModel), models.stream())
                        .map(ModelOption::reasoning)
                        .filter(Objects::nonNull)
                        .anyMatch(r -> r.find(level).isPresent());
    }

    /**
     * Модель по id — общий разбор для вопросов «что у этой модели»: {@code null} и id модели по
     * умолчанию — она сама, иначе — одна из {@link #models}; неизвестный id — пусто, и что это
     * значит, решает каждый вопрос сам (у каждого своя безопасная сторона).
     */
    private Optional<ModelOption> option(@Nullable String id) {
        if (id == null || id.equals(defaultModel.id())) {
            return Optional.of(defaultModel);
        }
        return models.stream().filter(m -> id.equals(m.id())).findFirst();
    }
}
