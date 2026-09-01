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
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.model.openai.autoconfigure.AbstractOpenAiProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil;
import org.springframework.ai.model.openai.autoconfigure.OpenAiAutoConfigurationUtil.ResolvedConnectionProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties;
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
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
 *
 * <p>Every connection is logged with its call deadline at startup — see {@link #logConnection}.
 */
@Slf4j
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
        // The autoconfigured connection is built elsewhere (OpenAiChatAutoConfiguration) and takes
        // its deadline straight from these properties — the same value the connections below get,
        // unless spring.ai.openai.chat.timeout overrides it for them (see callTimeout).
        logConnection(
                "default (spring.ai.openai)",
                commonProperties.getTimeout(),
                commonProperties.getMaxRetries());
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

    // Builder.toolCallingManager is deprecated for removal upstream (the advisor chain drives the
    // loop instead), but the OpenAI autoconfiguration still sets it on the default connection —
    // an alternative endpoint is built exactly like the default one until upstream drops it.
    @SuppressWarnings("removal")
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
        logConnection(option.id(), connection.getTimeout(), connection.getMaxRetries());
        // Mirrors the autoconfiguration: the meter registry is handed to the client only when
        // connection-pool metrics are asked for, otherwise the pool is not instrumented at all.
        MeterRegistry meters =
                connection.isConnectionPoolMetricsEnabled() ? meterRegistry.getIfAvailable() : null;
        // Both clients get the same deadline, and both need it: the blocking one carries the whole
        // context in a single request (/compact, summarization, the search sub-agent), the
        // streaming one carries a long generation — either one outlives the framework default.
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
                .options(chatProperties.toOptions())
                .toolCallingManager(toolCallingManager)
                .observationRegistry(observations)
                .meterRegistry(meters)
                .build();
    }

    /**
     * The call deadline of one connection, at startup, where it can be read against a failure.
     *
     * <p>It is the deadline of the whole call, not of one idle socket read, and it is enforced on
     * the blocking client exactly as on the streaming one — so the longest single request the app
     * makes decides whether the value is enough. That request is a compaction round: it carries the
     * entire live context in one blocking call and answers with a whole document, which is why a
     * chat whose answers all fit inside the deadline can still lose every {@code /compact}. The cut
     * arrives as {@code OpenAIInvalidDataException: Error reading response} over an {@code
     * InterruptedIOException: timeout} — a message that names neither the deadline nor the property
     * behind it, hence this line.
     *
     * <p>The framework default is warned about rather than logged: it is 60s, short enough that
     * reaching it says the connection was left unconfigured rather than tuned.
     */
    private static void logConnection(String connection, Duration timeout, int maxRetries) {
        if (AbstractOpenAiProperties.DEFAULT_TIMEOUT.equals(timeout)) {
            log.warn(
                    "Model connection {}: call timeout {} is the framework default — a round"
                            + " longer than that is cut with \"Error reading response\"; set"
                            + " spring.ai.openai.timeout (KB_CHAT_STREAM_TIMEOUT)",
                    connection,
                    timeout);
        } else {
            log.info(
                    "Model connection {}: call timeout {}, max retries {}",
                    connection,
                    timeout,
                    maxRetries);
        }
    }

    /**
     * The deadline a connection of its own ends up with: the chat-level property when it was set,
     * the shared {@code spring.ai.openai.timeout} otherwise. Pinned by a test — the merge is
     * upstream's ({@link OpenAiAutoConfigurationUtil#resolveCommonProperties}), and an endpoint of
     * its own silently dropping back to the 60s default is the one outcome that has to fail loudly
     * here rather than an hour later, mid-compaction.
     */
    static Duration callTimeout(
            OpenAiCommonProperties commonProperties, OpenAiChatProperties chatProperties) {
        return OpenAiAutoConfigurationUtil.resolveCommonProperties(commonProperties, chatProperties)
                .getTimeout();
    }
}
