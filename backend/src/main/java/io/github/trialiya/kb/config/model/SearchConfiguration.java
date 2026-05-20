package io.github.trialiya.kb.config.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Search-related settings, bound from:
 *
 * <pre>
 * kb:
 *   search:
 *     keyword:
 *       limit: 20
 *     semantic:
 *       threshold: 0.30
 *       limit: 20
 *     hybrid:
 *       keyword-weight: 0.4   # weight for keyword score  (0..1)
 *       semantic-weight: 0.6  # weight for semantic score (0..1)
 *       threshold: 0.20
 *       limit: 20
 * </pre>
 */
@ConfigurationProperties(prefix = "kb.search")
public record SearchConfiguration(
        KeywordConfig keyword, SemanticConfig semantic, HybridConfig hybrid) {

    public record KeywordConfig(int limit) {}

    public record SemanticConfig(double threshold, int limit) {}

    public record HybridConfig(
            double keywordWeight, double semanticWeight, double threshold, int limit) {}
}
