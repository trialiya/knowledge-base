package io.github.trialiya.kb.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties.ModelOption;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;

/**
 * A real {@link OpenAiChatModel} against a local stub endpoint, built by the same code that builds
 * every connection of the application ({@link ChatModelRegistry}) — so what a test sees on the wire
 * is what the application would send, not what a hand-assembled client would.
 */
public final class StubEndpointConnection {

    private static final String MODEL_ID = "stub-endpoint";

    private StubEndpointConnection() {}

    /** No retries, so a stub that answers wrong fails the test instead of being asked again. */
    public static OpenAiChatModel to(String baseUrl) {
        OpenAiCommonProperties common = new OpenAiCommonProperties();
        common.setBaseUrl("https://unused.example");
        common.setApiKey("sk-unused");
        common.setTimeout(Duration.ofSeconds(30));
        common.setMaxRetries(0);
        ModelOption defaultModel = new ModelOption("default", "Default", true, true, null, null, null, true, null);
        ModelOption option = new ModelOption(MODEL_ID, "Stub", true, true, null, baseUrl, "sk-stub", true, null);
        return ChatModelRegistry.build(
                        mock(OpenAiChatModel.class),
                        new ChatModelProperties(defaultModel, List.of(option)),
                        common,
                        new OpenAiChatProperties(),
                        ToolCallingManager.builder().build(),
                        absent(),
                        absent(),
                        empty())
                .forModel(MODEL_ID);
    }

    /** Провайдер без бина: {@code getIfAvailable(supplier)} отдаёт фолбэк вызывающего. */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> absent() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        when(provider.getIfAvailable(any(Supplier.class)))
                .thenAnswer(call -> ((Supplier<T>) call.getArgument(0)).get());
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> empty() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(call -> Stream.empty());
        return provider;
    }
}
