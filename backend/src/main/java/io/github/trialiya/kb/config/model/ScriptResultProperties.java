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
 * @param enabled keep results at all; off, a script's result gets no id and {@code kb.result} finds
 *     nothing
 * @param maxChars the largest value (as JSON) that is kept; a larger one is still returned to the
 *     model — truncated, as before — but gets no id
 * @param keepPerChat how many of a chat's most recent results stay readable; older ones are dropped
 *     as new ones arrive
 */
@ConfigurationProperties(prefix = "kb.script.results")
public record ScriptResultProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1000000") int maxChars,
        @DefaultValue("50") int keepPerChat) {

    public static ScriptResultProperties defaults() {
        return new ScriptResultProperties(true, 1_000_000, 50);
    }
}
