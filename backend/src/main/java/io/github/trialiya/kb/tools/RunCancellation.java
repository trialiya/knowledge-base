package io.github.trialiya.kb.tools;

import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.model.ToolContext;

/**
 * The run's "stop" flag, passed to tools through the {@link ToolContext}.
 *
 * <p>{@code ChatRunService.cancel} disposes the Reactor stream, which unsubscribes the response but
 * does nothing to a tool that is already executing. For every existing tool that is invisible — a
 * git read returns in milliseconds. A script does not: without this flag a runaway {@code
 * runScript} would keep burning CPU long after the user pressed stop, and shutdown would wait for
 * it.
 *
 * <p>Absent from contexts built outside {@code ChatRunService} (the synchronous endpoint, the
 * search sub-agent, tests); there the wall-clock timeout is the only limit, which is why {@link
 * #none()} is a valid answer rather than an error.
 */
public record RunCancellation(AtomicBoolean stopRequested) {

    public static final String KEY = "runCancellation";

    /** A cancellation that never fires — for call paths with no stoppable run behind them. */
    public static RunCancellation none() {
        return new RunCancellation(new AtomicBoolean(false));
    }

    /** Pulls the token out of a {@link ToolContext}; never null, so callers need no null check. */
    public static RunCancellation from(@Nullable ToolContext context) {
        if (context == null) {
            return none();
        }
        return context.getContext().get(KEY) instanceof RunCancellation cancellation
                ? cancellation
                : none();
    }

    public boolean isStopRequested() {
        return stopRequested.get();
    }
}
