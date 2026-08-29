package io.github.trialiya.kb.config.model;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Thresholds driving {@code SummarizeService}. Bound from {@code kb.chat.summarize} — the values in
 * effect, and the environment variables that override them, are in {@code application.yaml}.
 *
 * @param tokenThreshold tokens in the compressible slice that trigger a round. Measured on the
 *     slice — the messages older than the live tail — not on the whole window: it asks "is there
 *     enough here to be worth compressing", which is the same question {@code
 *     messageCountThreshold} asks by count, and either answer is enough to start a round. These are
 *     the provider's own tokens wherever the conversation carries measurements, and only the {@code
 *     charsPerToken} estimate where it does not — so the same number means real tokens on a
 *     measured chat and a rough guess on an unmeasured one.
 * @param messageCountThreshold number of compressible messages that trigger a round. The cheap half
 *     of the pair above: it catches long dialogues that are not yet heavy in tokens.
 * @param overlapMessages number of recent messages kept *outside* the summarized window so the
 *     model always has some live context to anchor against.
 * @param overlapUserMessages minimum number of recent <em>user</em> messages kept outside the
 *     summarized window. Applied together with {@code overlapMessages}, not instead of it: the live
 *     tail must satisfy both, so a turn that produced a long tool marathon cannot push the user's
 *     own last questions into the summary just because the raw message count already fits. Note
 *     that the tail rules only ever move the boundary earlier, so none of them bounds the size of
 *     the live window — a marathon inside the last few turns stays live until later questions push
 *     it out.
 * @param summaryCollapseThreshold when the number of stored summary messages would reach this
 *     value, they are collapsed into a single meta-summary instead.
 * @param charsPerToken how many characters are used per estimated token — the fallback weighing,
 *     used only where a conversation carries no measurements to subtract. Lower it to 3 for
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
