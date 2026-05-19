package io.github.trialiya.kb.service;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.stereotype.Service;

/**
 * Thin wrapper around Spring AI's {@link OpenAiEmbeddingModel} that produces {@code float[]}
 * vectors.
 *
 * <p>Configure via {@code application.yaml}:
 *
 * <pre>
 * spring:
 *   ai:
 *     openai:
 *       embedding:
 *         options:
 *           model: text-embedding-3-small   # 1536-dim (default)
 *           # model: text-embedding-3-large # 3072-dim – update VECTOR(N) in SQL too
 * </pre>
 */
@Slf4j
@Service
public class EmbeddingService {

    private final OpenAiEmbeddingModel embeddingModel;

    public EmbeddingService(OpenAiEmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Embeds a single text string and returns the raw {@code float[]} vector. */
    public EmbeddingResponse embed(String text) {
        return embeddingModel.call(
                new EmbeddingRequest(List.of(text), OpenAiEmbeddingOptions.builder().build()));
    }

    /**
     * Concatenates {@code title} and {@code description}, then embeds the result. A newline
     * separator helps the model distinguish the two fields.
     */
    public EmbeddingResponse embedDocument(String title, String description) {
        StringBuilder sb = new StringBuilder(title == null ? "" : title.trim());
        if (description != null && !description.isBlank()) {
            sb.append("\n").append(description.trim());
        }
        return embed(sb.toString());
    }
}
