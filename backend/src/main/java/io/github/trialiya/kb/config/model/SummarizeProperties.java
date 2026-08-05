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
 *       overlap-user-messages: 5
 *       summary-collapse-threshold: 5
 *       chars-per-token: 4
 * </pre>
 *
 * @param tokenThreshold approximate token budget for the "live" messages window. When the total
 *     estimated tokens across unsummarized messages exceeds this value, a new summarization round
 *     is triggered <em>and</em> the round is forced to compress far enough back for the surviving
 *     tail to fit the budget again — the overlap parameters below give way to it, since they can
 *     only move the boundary earlier and so bound nothing on their own. The one hard ceiling on the
 *     live window. Rule of thumb: 1 token ≈ 4 characters (English/code mix).
 * @param messageCountThreshold minimum number of compressible messages before summarization kicks
 *     in. Unlike {@code tokenThreshold} this one measures the slice about to be compressed, and it
 *     only starts rounds — it never decides how far back the boundary goes.
 * @param overlapMessages number of recent messages kept *outside* the summarized window so the
 *     model always has some live context to anchor against.
 * @param overlapUserMessages minimum number of recent <em>user</em> messages kept outside the
 *     summarized window. Applied together with {@code overlapMessages}, not instead of it: the live
 *     tail must satisfy both, so a turn that produced a long tool marathon cannot push the user's
 *     own last questions into the summary just because the raw message count already fits.
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
        int overlapUserMessages,
        int summaryCollapseThreshold,
        int charsPerToken) {}
