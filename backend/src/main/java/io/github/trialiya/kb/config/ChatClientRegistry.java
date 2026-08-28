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

    private final String defaultModelId;
    private final ChatClient defaultClient;
    private final Map<String, ChatClient> byModelId;

    public ChatClientRegistry(
            String defaultModelId, ChatClient defaultClient, Map<String, ChatClient> byModelId) {
        this.defaultModelId = defaultModelId;
        this.defaultClient = defaultClient;
        this.byModelId = Map.copyOf(byModelId);
    }

    /**
     * The client for {@code modelId}; a model without an endpoint of its own gets the default one.
     * {@code null} means {@code kb.chat.default-model.id} and is resolved the same way — see {@link
     * ChatModelRegistry#forModel(String)}, whose routing this one must mirror.
     */
    public ChatClient forModel(@Nullable String modelId) {
        return byModelId.getOrDefault(resolveModelId(modelId), defaultClient);
    }

    /**
     * The id the run actually goes out with — {@code null} spelled out as {@code
     * kb.chat.default-model.id}. Needed wherever the model is recorded rather than routed by (the
     * model stamped on an answer, see {@code ChatHistoryService#markRunResult}): "the default one"
     * is not an answer once the default changes.
     */
    public String resolveModelId(@Nullable String modelId) {
        return modelId == null ? defaultModelId : modelId;
    }
}
