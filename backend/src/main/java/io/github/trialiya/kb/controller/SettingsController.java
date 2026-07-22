package io.github.trialiya.kb.controller;

import io.github.trialiya.kb.config.model.ChatModelProperties;
import io.github.trialiya.kb.config.model.ChatModelProperties.ModelOption;
import io.github.trialiya.kb.config.model.EmbeddingConfiguration;
import io.github.trialiya.kb.config.model.SubAgentConfig;
import io.github.trialiya.kb.config.model.SummarizeProperties;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final ChatModelProperties chatModelProperties;
    private final EmbeddingConfiguration embeddingConfiguration;
    private final SubAgentConfig subAgentConfig;
    private final @Nullable Integer chatMaxTokens;
    private final @Nullable Double chatTemperature;
    private final @Nullable Double chatTopP;
    private final SummarizeProperties summarizeProperties;

    public SettingsController(
            ChatModelProperties chatModelProperties,
            EmbeddingConfiguration embeddingConfiguration,
            SubAgentConfig subAgentConfig,
            OpenAiChatModel openAiChatModel,
            SummarizeProperties summarizeProperties) {
        this.chatModelProperties = chatModelProperties;
        this.embeddingConfiguration = embeddingConfiguration;
        this.subAgentConfig = subAgentConfig;
        this.chatMaxTokens = openAiChatModel.getDefaultOptions().getMaxTokens();
        this.chatTemperature = openAiChatModel.getDefaultOptions().getTemperature();
        this.chatTopP = openAiChatModel.getDefaultOptions().getTopP();
        this.summarizeProperties = summarizeProperties;
    }

    /** Full AI configuration snapshot consumed by the Settings → Модели panel. */
    @GetMapping("/ai-config")
    public AiConfigResponse getAiConfig() {
        return new AiConfigResponse(
                new ChatSection(
                        chatModelProperties.defaultModel(),
                        chatModelProperties.models(),
                        new ChatOptions(chatMaxTokens, chatTemperature, chatTopP)),
                new EmbeddingSection(
                        embeddingConfiguration.model(),
                        embeddingConfiguration.reindexBatchSize(),
                        new ChunkerInfo(
                                embeddingConfiguration.chunker().maxTokens(),
                                embeddingConfiguration.chunker().overlapTokens()),
                        new CacheInfo(
                                embeddingConfiguration.cache().enabled(),
                                embeddingConfiguration.cache().ttlDays())),
                new SearchCodebaseSection(
                        subAgentConfig.enabled(),
                        subAgentConfig.modelId(),
                        subAgentConfig.maxTokens(),
                        subAgentConfig.maxIterations()),
                summarizeProperties);
    }

    public record AiConfigResponse(
            ChatSection chat,
            EmbeddingSection embedding,
            SearchCodebaseSection searchCodebase,
            SummarizeProperties summarize) {}

    public record ChatSection(
            ModelOption defaultModel, List<ModelOption> models, ChatOptions options) {}

    /** Core inference parameters from spring.ai.openai.chat.options.*. */
    public record ChatOptions(Integer maxTokens, Double temperature, Double topP) {}

    public record EmbeddingSection(
            String model, int reindexBatchSize, ChunkerInfo chunker, CacheInfo cache) {}

    public record ChunkerInfo(int maxTokens, int overlapTokens) {}

    public record CacheInfo(boolean enabled, int ttlDays) {}

    public record SearchCodebaseSection(
            boolean enabled, String modelId, int maxTokens, int maxIterations) {}
}
