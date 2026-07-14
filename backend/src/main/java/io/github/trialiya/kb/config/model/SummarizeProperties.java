package io.github.trialiya.kb.config.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Thresholds driving {@code SummarizeService}.
 *
 * <pre>
 * kb:
 *   chat:
 *     summarize:
 *       token-threshold: 3000
 *       message-count-threshold: 20
 *       overlap-messages: 10
 *       summary-collapse-threshold: 5
 *       chars-per-token: 4
 * </pre>
 *
 * @param tokenThreshold approximate token budget for the "live" messages window. When the total
 *     estimated tokens across unsummarized messages exceeds this value, a new summarization round
 *     is triggered. Rule of thumb: 1 token ≈ 4 characters (English/code mix).
 * @param messageCountThreshold minimum number of compressible messages before summarization kicks
 *     in.
 * @param overlapMessages number of recent messages kept *outside* the summarized window so the
 *     model always has some live context to anchor against.
 * @param summaryCollapseThreshold when the number of stored summary messages would reach this
 *     value, they are collapsed into a single meta-summary instead.
 * @param charsPerToken how many characters are used per estimated token. Lower it to 3 for
 *     mostly-code conversations, raise it to 5 for prose.
 */
@ConfigurationProperties(prefix = "kb.chat.summarize")
public record SummarizeProperties(
        int tokenThreshold,
        int messageCountThreshold,
        int overlapMessages,
        int summaryCollapseThreshold,
        int charsPerToken) {}
