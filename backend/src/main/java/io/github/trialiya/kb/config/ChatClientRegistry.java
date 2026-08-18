package io.github.trialiya.kb.config;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Which {@link ChatClient} a run goes through. One per connection, not one per model: models served
 * by the default endpoint all share the default client and differ only by the {@code model} option
 * on the request, while a model with its own {@code kb.chat.models[].base-url}/{@code api-key} has
 * a client of its own here (see {@link ChatModelRegistry}).
 *
 * <p>Every client is assembled by the same code with the same advisors and the same tools — the
 * endpoint is the only difference, so switching model cannot switch behaviour.
 */
public class ChatClientRegistry {

    private final ChatClient defaultClient;
    private final Map<String, ChatClient> byModelId;

    public ChatClientRegistry(ChatClient defaultClient, Map<String, ChatClient> byModelId) {
        this.defaultClient = defaultClient;
        this.byModelId = Map.copyOf(byModelId);
    }

    /**
     * The client for {@code modelId}. {@code null} — no model override for this run — and any model
     * without an endpoint of its own both get the default client.
     */
    public ChatClient forModel(@Nullable String modelId) {
        return modelId == null ? defaultClient : byModelId.getOrDefault(modelId, defaultClient);
    }
}
