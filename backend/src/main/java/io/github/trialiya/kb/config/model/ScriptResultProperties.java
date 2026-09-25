package io.github.trialiya.kb.config.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where a chat keeps what its scripts returned, so a later script of the same chat can read it
 * ({@code kb.result(id)}) and {@code saveScriptResult} can turn it into an attachment — see {@code
 * ChatScriptResults}.
 *
 * <p>A properties class of its own rather than another component of {@link ScriptProperties}: the
 * record there is built positionally all over the tests, and nothing about sandbox budgets changes
 * because a result is kept afterwards.
 *
 * @param enabled keep results at all; off, a script's result gets no id, {@code kb.result} finds
 *     nothing — rows kept while it was on included — and the handbook does not mention any of it
 * @param maxChars the largest value (as JSON) that is kept; a larger one is still returned to the
 *     model, truncated to {@code kb.script.limits.max-result-chars}, but gets no id; below that
 *     limit it is refused at startup ({@code ScriptGuideService})
 * @param keepPerChat how many of a chat's most recent results stay readable; older ones are dropped
 *     as new ones arrive
 */
@ConfigurationProperties(prefix = "kb.script.results")
public record ScriptResultProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("500000") int maxChars,
        @DefaultValue("30") int keepPerChat) {

    /**
     * A non-positive number is a misconfiguration, not a way to switch keeping off — that is {@code
     * enabled}. Refused at startup: {@code keep-per-chat: 0} would delete every row the moment it
     * is written while still handing out its id.
     */
    public ScriptResultProperties {
        if (maxChars <= 0 || keepPerChat <= 0) {
            throw new IllegalArgumentException(
                    "kb.script.results.max-chars and keep-per-chat must be positive, got "
                            + maxChars
                            + " and "
                            + keepPerChat
                            + "; to keep nothing, set kb.script.results.enabled=false");
        }
    }

    public static ScriptResultProperties defaults() {
        return new ScriptResultProperties(true, 500_000, 30);
    }
}
