package io.github.trialiya.kb.config.model;

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "kb.chat")
public record ChatModelProperties(ModelOption defaultModel, List<ModelOption> models) {

    public ChatModelProperties {
        models = models == null ? List.of() : List.copyOf(models);
    }

    /**
     * @param weak whether this model needs the {@code runScript} tutorial half ({@code
     *     script-run-extended.md} and its edit counterpart) spelled out — see {@code
     *     ScriptGuideService}. Defaults to {@code true} (the safe assumption for a model nobody has
     *     rated yet) when a deployment's config omits the field entirely.
     */
    public record ModelOption(String id, String label, @DefaultValue("true") boolean weak) {}

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
