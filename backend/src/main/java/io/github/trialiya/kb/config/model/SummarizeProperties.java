package io.github.trialiya.kb.config.model;

import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Thresholds driving {@code SummarizeService}. Bound from {@code kb.chat.summarize} — the values in
 * effect, and the environment variables that override them, are in {@code application.yaml}.
 *
 * @param tokenThreshold tokens in the compressible slice that trigger a round. Measured on the
 *     slice — the messages older than the live tail — not on the whole window: it asks "is there
 *     enough here to be worth compressing", which is the same question {@code
 *     messageCountThreshold} asks by count, and either answer is enough to start a round. The
 *     weight is the heavier of two answers — the provider's own measurements wherever the
 *     conversation carries them, and the {@code charsPerToken} estimate — so the same number means
 *     real tokens on a measured chat and a rough guess on an unmeasured one.
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
 * @param applyAfter how long the chat must stay quiet before a written summary is folded into the
 *     history. The pause is what makes the fold free: the provider caches the prompt from byte
 *     zero, so rewriting the head of the history costs a full uncached request while the cache is
 *     still warm, and nothing once it has expired. Measured from the last row of the chat — the
 *     cache goes cold from the last request, whatever that request was. Set it a little above the
 *     provider's own cache lifetime.
 * @param applyAtRatio the share of the model's context window at which a parked summary is applied
 *     without waiting for a pause: past it the conversation is expensive enough that a lost cache
 *     is the cheaper half of the trade. A share rather than a number of tokens because the number
 *     itself is the model's — see {@code ChatModelProperties.ModelOption#contextTokens}; a model
 *     that names no window has no threshold at all and waits for the pause.
 * @param applyAtQueue how many parked summaries make the queue apply itself with no other reason. A
 *     parked summary does not shorten the prompt, so every next round reads a window longer than
 *     the one before it: after a few rounds in a row that length outweighs the cache the fold would
 *     cost. On a model that names no context window this is the only bound besides the pause —
 *     {@code applyAtRatio} has no number to work from there.
 * @param autoCompactAtRatio the share of the model's context window at which the chat compacts
 *     itself before the next answer ({@code AutoCompactService}) — the last line, well above {@code
 *     applyAtRatio}: two or three tool-heavy rounds fill a window faster than background
 *     summarization writes anything, and the run that crosses it does not fail on the provider's
 *     limit, it never starts. The number is the model's for the same reason as above, and a model
 *     with no named window is never compacted automatically. Leave room under the window itself:
 *     the compaction round reads the same context it is compacting.
 * @param charsPerToken how many characters are used per estimated token — the second opinion next
 *     to the measurements, and the only weighing on a conversation that carries none. Lower it to 3
 *     for mostly-code conversations, raise it to 5 for prose.
 * @param model the model a summarization round runs on. Blank — the chat's own default ({@code
 *     spring.ai.openai.chat.model}), which is what the round used before this knob existed. The
 *     round never sees the model the user picked in the chat: it is a background request of its
 *     own, reading a slice nobody is waiting for, so it is the one place where a cheaper model is
 *     free of any trade-off the answer path would have to make. The id must be served by {@code
 *     spring.ai.openai.base-url} — this is an option on the request, not a second connection, so an
 *     entry of {@code kb.chat.models} with an endpoint of its own cannot be named here.
 * @param reasoningEffort the {@code reasoning_effort} of the summarization request. Blank — the
 *     field is not sent, and the model reasons as much as it defaults to. The pair below with
 *     {@code thinking}: two spellings of the same knob, and which one an endpoint understands is
 *     the endpoint's business — {@code reasoning_effort} is the OpenAI one, {@code thinking} the
 *     common vendor extension. Worth turning down here more than anywhere else: the summary is a
 *     re-telling of material the model already has in front of it, and a round of it easily spends
 *     more tokens on reasoning than the document it writes is long.
 * @param thinking the {@code type} of the {@code thinking} field sent in the summarization
 *     request's body — {@code disabled} to turn reasoning off on an endpoint that spells it this
 *     way. Blank — the field is not sent at all, which is the only safe default: an endpoint that
 *     does not know the field rejects the whole request rather than ignoring it.
 */
@ConfigurationProperties(prefix = "kb.chat.summarize")
public record SummarizeProperties(
        int tokenThreshold,
        int messageCountThreshold,
        int overlapMessages,
        int overlapUserMessages,
        int summaryCollapseThreshold,
        Duration applyAfter,
        double applyAtRatio,
        int applyAtQueue,
        double autoCompactAtRatio,
        int charsPerToken,
        @Nullable String model,
        @Nullable String reasoningEffort,
        @Nullable String thinking) {

    public SummarizeProperties {
        // Пустая строка приходит от `${ПЕРЕМЕННАЯ:}` в application.yaml — «не задано», а не
        // «задано пустым»: пустая модель, отправленная провайдеру, это отказ на каждом раунде.
        model = trimToNull(model);
        reasoningEffort = trimToNull(reasoningEffort);
        thinking = trimToNull(thinking);
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
