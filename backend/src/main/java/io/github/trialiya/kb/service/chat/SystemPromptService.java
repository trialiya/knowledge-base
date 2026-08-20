package io.github.trialiya.kb.service.chat;

import io.github.trialiya.kb.config.model.SystemPromptProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

/**
 * Provides the extended system prompt guidance for weak models. The base system prompt (reference
 * rules and tool selection) is loaded as {@code defaultSystem} in {@code ChatConfig#chatClient} and
 * applies to all models. This service provides only the extended guidance (decision trees, workflow
 * examples, common mistakes), which is injected via the {@code {system_extended}} placeholder in
 * the base system prompt.
 *
 * <p>For weak models, the extended guidance is appended; for strong models, an empty string is
 * returned, saving context.
 */
@Service
public class SystemPromptService {

    private final String extendedForWeakModel;
    private final String extendedForStrongModel;

    public SystemPromptService(SystemPromptProperties properties) {
        this.extendedForWeakModel = read(properties.extendedPrompt());
        this.extendedForStrongModel = "";
    }

    /**
     * The extended system prompt (decision trees, examples, common mistakes) for the given model.
     * Returns the extended guidance for weak models, or empty string for strong models.
     *
     * @param weak {@code ChatModelProperties.ModelOption#weak} of the model being used — picks
     *     extended guidance or empty string
     */
    public String systemExtended(boolean weak) {
        return weak ? extendedForWeakModel : extendedForStrongModel;
    }

    private static String read(Resource resource) {
        try {
            return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8)
                    .strip();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read system prompt: " + resource, e);
        }
    }
}
