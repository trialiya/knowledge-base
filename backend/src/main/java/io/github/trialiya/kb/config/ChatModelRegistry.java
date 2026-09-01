package io.github.trialiya.kb.config;

import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties.ModelOption;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil.ResolvedConnectionProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Which {@link OpenAiChatModel} — that is, which connection — serves a given model id.
 *
 * <p>Selecting a model normally changes only the {@code model} field of one request: every entry of
 * {@code kb.chat.models} is served by the single autoconfigured connection ({@code
 * spring.ai.openai.base-url} + {@code api-key}), and that connection is what {@link
 * #forModel(String)} returns. An entry that carries its own {@code base-url}/{@code api-key} cannot
 * be reached that way, so it gets a second {@code OpenAiChatModel} built here, over its own HTTP
 * client, and {@code forModel} hands that one out instead.
 *
 * <p>The extra connections are built once at startup, not per request: an OkHttp client owns a
 * connection pool, and one per call would leak sockets.
 */
public class ChatModelRegistry {

    private final String defaultModelId;
    private final OpenAiChatModel defaultModel;
    private final Map<String, OpenAiChatModel> byModelId;

    public ChatModelRegistry(
            String defaultModelId,
            OpenAiChatModel defaultModel,
            Map<String, OpenAiChatModel> byModelId) {
        this.defaultModelId = defaultModelId;
        this.defaultModel = defaultModel;
        this.byModelId = Map.copyOf(byModelId);
    }

    /**
     * The connection for {@code modelId}, falling back to the autoconfigured one for every model
     * that did not ask for an endpoint of its own.
     *
     * <p>{@code null} — no override for this run — is not a fourth case: it means {@code
     * kb.chat.default-model.id} and is resolved as such, so a {@code kb.chat.models} entry that
     * repeats the default id with an endpoint of its own serves every run of that model, not only
     * the ones where the picker named it explicitly.
     */
    public OpenAiChatModel forModel(@Nullable String modelId) {
        return byModelId.getOrDefault(modelId == null ? defaultModelId : modelId, defaultModel);
    }

    /** Model ids that got a connection of their own. */
    public Set<String> ownEndpointModelIds() {
        return byModelId.keySet();
    }

    /**
     * Builds one {@link OpenAiChatModel} per {@code kb.chat.models} entry with an endpoint of its
     * own. Everything except the URL and the token is taken from the autoconfigured connection —
     * timeout, retries, proxy, custom headers, and the whole of {@code
     * spring.ai.openai.chat.options.*} — so a second endpoint stays a second endpoint and does not
     * quietly become a second set of inference parameters.
     */
    public static ChatModelRegistry build(
            OpenAiChatModel defaultModel,
            ChatModelProperties chatModelProperties,
            OpenAiCommonProperties commonProperties,
            OpenAiChatProperties chatProperties,
            ToolCallingManager toolCallingManager,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers) {
        List<OpenAiHttpClientBuilderCustomizer> customizers =
                httpClientCustomizers.orderedStream().toList();
        ObservationRegistry observations =
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP);
        Map<String, OpenAiChatModel> byModelId = new LinkedHashMap<>();
        for (ModelOption option : chatModelProperties.models()) {
            if (option.hasOwnEndpoint()) {
                byModelId.put(
                        option.id(),
                        buildModel(
                                option,
                                commonProperties,
                                chatProperties,
                                toolCallingManager,
                                observations,
                                meterRegistry,
                                customizers));
            }
        }
        return new ChatModelRegistry(
                chatModelProperties.defaultModel().id(), defaultModel, byModelId);
    }

    /**
     * The shared connection — every model that did not name an endpoint of its own — built here
     * instead of by {@code OpenAiChatAutoConfiguration}, whose bean stands down for ours
     * ({@code @ConditionalOnMissingBean}). One reason: the request deadline, which upstream leaves
     * at 60s and no property can move (see {@link #withCallDeadline}).
     *
     * <p>What comes out is otherwise the connection Spring AI would have built, with one thing not
     * carried over: a {@code ChatModelObservationConvention} bean, upstream's customization point
     * for observation naming, is not applied — nothing here declares one.
     */
    public static OpenAiChatModel buildDefaultModel(
            OpenAiCommonProperties commonProperties,
            OpenAiChatProperties chatProperties,
            ToolCallingManager toolCallingManager,
            ObjectProvider<ObservationRegistry> observationRegistry,
            ObjectProvider<MeterRegistry> meterRegistry,
            ObjectProvider<OpenAiHttpClientBuilderCustomizer> httpClientCustomizers) {
        return buildConnection(
                OpenAiAutoConfigurationUtil.resolveCommonProperties(
                        commonProperties, chatProperties),
                chatProperties,
                toolCallingManager,
                observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP),
                meterRegistry,
                httpClientCustomizers.orderedStream().toList());
    }

    private static OpenAiChatModel buildModel(
            ModelOption option,
            OpenAiCommonProperties commonProperties,
            OpenAiChatProperties chatProperties,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observations,
            ObjectProvider<MeterRegistry> meterRegistry,
            List<OpenAiHttpClientBuilderCustomizer> customizers) {
        // The same merge the OpenAI autoconfiguration does (chat-level connection settings over
        // common ones), with this model's URL and token laid on top. A model that named only a
        // token keeps the default base-url — same host, its own account.
        ResolvedConnectionProperties connection =
                OpenAiAutoConfigurationUtil.resolveCommonProperties(
                        commonProperties, chatProperties);
        if (option.baseUrl() != null) {
            connection.setBaseUrl(option.baseUrl());
            // The provider of the default connection does not carry over to another host. In
            // OpenAiSetup these flags outrank the URL: GitHub Models rewrites any other base-url
            // back to models.github.ai, and Microsoft Foundry folds in the default deployment
            // name and credential — either one would silently discard the URL configured here.
            // The provider of the new host is still detected from the URL itself, so an entry
            // pointing at an Azure or GitHub endpoint keeps its own dialect.
            connection.setGitHubModels(false);
            connection.setMicrosoftFoundry(false);
            connection.setMicrosoftDeploymentName(null);
            connection.setMicrosoftFoundryServiceVersion(null);
            connection.setCredential(null);
        }
        if (option.apiKey() != null) {
            connection.setApiKey(option.apiKey());
        }
        return buildConnection(
                connection,
                chatProperties,
                toolCallingManager,
                observations,
                meterRegistry,
                customizers);
    }

    // Builder.toolCallingManager is deprecated for removal upstream (the advisor chain drives the
    // loop instead), but the OpenAI autoconfiguration still sets it — every connection here is
    // built exactly like the autoconfigured one until upstream drops it.
    @SuppressWarnings("removal")
    private static OpenAiChatModel buildConnection(
            ResolvedConnectionProperties connection,
            OpenAiChatProperties chatProperties,
            ToolCallingManager toolCallingManager,
            ObservationRegistry observations,
            ObjectProvider<MeterRegistry> meterRegistry,
            List<OpenAiHttpClientBuilderCustomizer> customizers) {
        OpenAiChatOptions options =
                withCallDeadline(chatProperties.toOptions(), connection.getTimeout());
        // Mirrors the autoconfiguration: the meter registry is handed to the client only when
        // connection-pool metrics are asked for, otherwise the pool is not instrumented at all.
        MeterRegistry meters =
                connection.isConnectionPoolMetricsEnabled() ? meterRegistry.getIfAvailable() : null;
        // Both clients get the same deadline: a blocking call can carry the whole context in one
        // request, a streaming one a long generation, and either outlives the framework default.
        return OpenAiChatModel.builder()
                .openAiClient(
                        OpenAiSetup.setupSyncClient(
                                connection.getBaseUrl(),
                                connection.getApiKey(),
                                connection.getCredential(),
                                connection.getMicrosoftDeploymentName(),
                                connection.getMicrosoftFoundryServiceVersion(),
                                connection.getOrganizationId(),
                                connection.isMicrosoftFoundry(),
                                connection.isGitHubModels(),
                                connection.getModel(),
                                connection.getTimeout(),
                                connection.getMaxRetries(),
                                connection.getProxy(),
                                connection.getCustomHeaders(),
                                observations,
                                meters,
                                customizers))
                .openAiClientAsync(
                        OpenAiSetup.setupAsyncClient(
                                connection.getBaseUrl(),
                                connection.getApiKey(),
                                connection.getCredential(),
                                connection.getMicrosoftDeploymentName(),
                                connection.getMicrosoftFoundryServiceVersion(),
                                connection.getOrganizationId(),
                                connection.isMicrosoftFoundry(),
                                connection.isGitHubModels(),
                                connection.getModel(),
                                connection.getTimeout(),
                                connection.getMaxRetries(),
                                connection.getProxy(),
                                connection.getCustomHeaders(),
                                observations,
                                meters,
                                customizers))
                .options(options)
                .toolCallingManager(toolCallingManager)
                .observationRegistry(observations)
                .meterRegistry(meters)
                .build();
    }

    /**
     * Puts the connection's deadline into the request options, which is what actually holds a long
     * call open.
     *
     * <p>The client deadline ({@code spring.ai.openai.timeout}) does not decide when a chat call is
     * cut. {@code OpenAiChatModel} builds the SDK's {@code RequestOptions} from the chat options of
     * the prompt, a request deadline outranks the client's, and {@code OpenAiChatOptions} carries
     * one whether or not anybody asked: its builder defaults {@code timeout} to 60s, and neither
     * {@code spring.ai.openai.chat.timeout} nor {@code spring.ai.openai.chat.options.timeout}
     * reaches that field. So without this line every call in the application is a 60s call.
     * Measured, not deduced: against a server that answers after 75s, a client built with a 10m
     * timeout dies at 61s with {@code InterruptedIOException: timeout}; with the deadline in the
     * options, the same call answers.
     *
     * <p>Sixty seconds is enough for an answer, which is why this surfaced late and only on the
     * longest single request the application makes — a compaction round, which carries the entire
     * live context in one blocking call and answers with a whole document. It arrives as {@code
     * OpenAIInvalidDataException: Error reading response}, naming neither deadline.
     *
     * <p>On the defaults rather than per call: a request that overrides options — the model of a
     * run, {@code /compact} — merges over these, so it inherits the deadline instead of having to
     * remember it.
     */
    private static OpenAiChatOptions withCallDeadline(OpenAiChatOptions options, Duration timeout) {
        return options.mutate().timeout(timeout).build();
    }
}
