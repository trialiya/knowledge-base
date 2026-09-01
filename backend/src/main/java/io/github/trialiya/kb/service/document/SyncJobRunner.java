package io.github.trialiya.kb.service.document;

import io.github.trialiya.kb.model.doc.sync.SyncEvent;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Turns a long document operation into a live event stream.
 *
 * <p>Export, compare and import all have the same shape: they run for a while, they know what they
 * are doing at each step, and the person who started them wants to see that rather than a spinner.
 * Each of them takes a {@code Consumer<SyncEvent>} and returns a summary; this class is the one
 * place that decides how those events reach the browser.
 *
 * <p>Two things it owes the caller. The job runs off the request thread — returning the {@link
 * SseEmitter} releases the servlet thread, so the work has to be somewhere else. And it stops when
 * the browser goes away: a closed connection makes the next {@code send} fail, which cancels the
 * job instead of letting it grind through the rest of a tree nobody is watching.
 */
@Slf4j
@Component
public class SyncJobRunner {

    /**
     * Generous, because the point of these jobs is that they can be long, but not unbounded: a
     * worker that wedged would otherwise pin the connection forever.
     */
    private static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final ExecutorService executor;

    public SyncJobRunner(@Qualifier("documentSyncExecutor") ExecutorService executor) {
        this.executor = executor;
    }

    /** A document operation: reports progress through {@code sink}, returns its summary. */
    @FunctionalInterface
    public interface Job {
        Object run(Consumer<SyncEvent> sink);
    }

    /**
     * Starts {@code job} and streams its events. The stream always ends with exactly one terminal
     * frame — {@link SyncEvent.Type#DONE} carrying the summary, or {@link SyncEvent.Type#ERROR}
     * carrying a message the UI can show — unless the client hung up first.
     *
     * @param name what to call the job in the logs
     */
    public SseEmitter run(String name, Job job) {
        SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
        AtomicBoolean alive = new AtomicBoolean(true);
        AtomicInteger processed = new AtomicInteger();

        emitter.onCompletion(() -> alive.set(false));
        emitter.onTimeout(() -> alive.set(false));
        emitter.onError(e -> alive.set(false));

        executor.execute(
                () -> {
                    try {
                        Object summary =
                                job.run(
                                        event -> {
                                            processed.set(event.processed());
                                            send(emitter, alive, event);
                                        });
                        send(emitter, alive, SyncEvent.done(processed.get(), summary));
                        emitter.complete();
                    } catch (CancellationException e) {
                        log.debug("{} cancelled — client disconnected", name);
                        emitter.complete();
                    } catch (Exception e) {
                        log.warn("{} failed: {}", name, e.toString());
                        send(emitter, alive, SyncEvent.error(reasonOf(e)));
                        emitter.complete();
                    }
                });
        return emitter;
    }

    /**
     * @throws CancellationException when the client is gone — unwound by {@link #run} to stop the
     *     job where it stands
     */
    private void send(SseEmitter emitter, AtomicBoolean alive, SyncEvent event) {
        if (!alive.get()) {
            throw new CancellationException("client disconnected");
        }
        try {
            emitter.send(SseEmitter.event().data(event));
        } catch (Exception e) {
            alive.set(false);
            CancellationException cancelled =
                    new CancellationException("send failed: " + e.getMessage());
            cancelled.initCause(e);
            throw cancelled;
        }
    }

    /** The message a user can act on, without the stack trace or the exception's class name. */
    private static String reasonOf(Exception e) {
        if (e instanceof ResponseStatusException rse && rse.getReason() != null) {
            return rse.getReason();
        }
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
