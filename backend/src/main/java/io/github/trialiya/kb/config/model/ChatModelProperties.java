package io.github.trialiya.kb.config.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
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
     */
    public record ModelOption(
            String id,
            String label,
            @DefaultValue("true") boolean weak,
            @JsonIgnore @Nullable String baseUrl,
            @JsonIgnore @Nullable String apiKey) {

        public ModelOption {
            baseUrl = trimToNull(baseUrl);
            apiKey = trimToNull(apiKey);
            if (baseUrl != null && apiKey == null) {
                throw new IllegalArgumentException(
                        "kb.chat model \""
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
            return "ModelOption[id=%s, label=%s, weak=%s, baseUrl=%s, apiKey=%s]"
                    .formatted(id, label, weak, baseUrl, apiKey == null ? null : "***");
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

        private static @Nullable String trimToNull(@Nullable String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    public boolean isAllowed(@Nullable String id) {
        return id != null
                && (id.equals(defaultModel.id())
                        || models.stream().anyMatch(m -> id.equals(m.id())));
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
        if (id == null || id.equals(defaultModel.id())) {
            return defaultModel.weak();
        }
        return models.stream()
                .filter(m -> id.equals(m.id()))
                .findFirst()
                .map(ModelOption::weak)
                .orElse(true);
    }
}
