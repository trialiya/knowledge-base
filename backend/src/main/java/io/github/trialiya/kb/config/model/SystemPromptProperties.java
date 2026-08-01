package io.github.trialiya.kb.config.model;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Binding for {@code kb.system-prompt.prompt} and {@code kb.system-prompt.extended-prompt} — the
 * system prompt split into reference (for all models) and extended (tutorial for weak models).
 *
 * @param prompt the reference half of the system prompt (rules, tool reference, concise guidance) —
 *     sent to all models
 * @param extendedPrompt the tutorial half (decision trees, workflow examples, common mistakes) —
 *     appended only for runs whose model is flagged {@code weak} ({@code
 *     ChatModelProperties.ModelOption#weak})
 */
@ConfigurationProperties(prefix = "kb.system-prompt")
public record SystemPromptProperties(Resource prompt, Resource extendedPrompt) {

    private static final Resource DEFAULT_PROMPT = new ClassPathResource("prompt/sys.md");

    private static final Resource DEFAULT_EXTENDED_PROMPT =
            new ClassPathResource("prompt/sys-extended.md");

    public SystemPromptProperties(@Nullable Resource prompt, @Nullable Resource extendedPrompt) {
        this.prompt = prompt != null ? prompt : DEFAULT_PROMPT;
        this.extendedPrompt = extendedPrompt != null ? extendedPrompt : DEFAULT_EXTENDED_PROMPT;
    }
}
