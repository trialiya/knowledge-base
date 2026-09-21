package io.github.trialiya.kb.service.chat.script;

import io.github.trialiya.kb.config.model.ScriptProperties;
import io.github.trialiya.kb.config.model.ScriptProperties.Schedule;
import io.github.trialiya.kb.model.script.ScriptResult;
import io.github.trialiya.kb.tools.RunCancellation;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

/**
 * Saved scripts the deployment runs on a clock of its own — {@code kb.script.schedules[]}.
 *
 * <p><b>The schedule is the deployment's, never the repository's.</b> A manifest says what may be
 * run; deciding that something should run every night, unattended, on this machine, is a decision
 * only the deployment can make — and it makes it in its own configuration, where an operator can
 * see it without reading a branch.
 *
 * <p><b>Always read-only</b>, for the reason spelled out on {@link Schedule}: nobody is watching.
 *
 * <p><b>What is kept is the last run of each schedule</b>, in memory, for the Settings panel and
 * the log. Not a history — that is a feature with its own questions (retention, notification, who
 * reads it), and a ring buffer pretending to be one would answer none of them. This answers the
 * question a deployment asks first: did it run, and did it break.
 */
@Slf4j
@Service
public class ScheduledScriptService {

    private final ScriptProperties properties;
    private final SavedScriptResolver resolver;
    private final ScriptRunner runner;
    private final TaskScheduler taskScheduler;

    /** Last outcome per schedule name; empty for one that has not fired yet. */
    private final Map<String, LastRun> lastRuns = new ConcurrentHashMap<>();

    public ScheduledScriptService(
            ScriptProperties properties,
            SavedScriptResolver resolver,
            ScriptRunner runner,
            TaskScheduler taskScheduler) {
        this.properties = properties;
        this.resolver = resolver;
        this.runner = runner;
        this.taskScheduler = taskScheduler;
    }

    /**
     * Registers every configured schedule.
     *
     * <p>A bad entry fails the start, unlike a bad entry in a repository's manifest: this is the
     * deployment's own configuration, an operator wrote it, and a schedule that silently does not
     * exist is the failure mode that takes a month to notice.
     */
    @PostConstruct
    void register() {
        if (properties.schedules().isEmpty()) {
            return;
        }
        if (!properties.enabled()) {
            throw new IllegalStateException(
                    "kb.script.schedules is configured but kb.script.enabled=false — there is no"
                            + " sandbox to run them in");
        }
        Set<String> names = new LinkedHashSet<>();
        for (Schedule schedule : properties.schedules()) {
            String name = requireValid(schedule, names);
            taskScheduler.schedule(() -> run(schedule), new CronTrigger(schedule.cron()));
            log.info(
                    "Scheduled script '{}': {} on '{}', read-only",
                    name,
                    schedule.script(),
                    schedule.cron());
        }
    }

    private static String requireValid(Schedule schedule, Set<String> names) {
        String where = "kb.script.schedules";
        if (schedule.script() == null || schedule.script().isBlank()) {
            throw new IllegalStateException(where + ": every entry needs a script name");
        }
        if (AttachmentScriptService.addresses(schedule.script())) {
            throw new IllegalStateException(
                    where
                            + "["
                            + schedule.displayName()
                            + "]: an attachment belongs to a chat or a document, not to a clock —"
                            + " schedule a script the repository declares instead");
        }
        if (schedule.cron() == null || !CronExpression.isValidExpression(schedule.cron())) {
            throw new IllegalStateException(
                    where
                            + "["
                            + schedule.displayName()
                            + "].cron is not a cron expression: \""
                            + schedule.cron()
                            + "\" (six fields, e.g. \"0 0 3 * * *\")");
        }
        if (!names.add(schedule.displayName())) {
            throw new IllegalStateException(
                    where + ": duplicate name \"" + schedule.displayName() + "\"");
        }
        return schedule.displayName();
    }

    /**
     * What each schedule's last run looked like, for {@code GET /api/settings/script/schedules}.
     */
    public List<Status> statuses() {
        return properties.schedules().stream()
                .map(
                        schedule ->
                                new Status(
                                        schedule.displayName(),
                                        schedule.script(),
                                        schedule.project(),
                                        schedule.cron(),
                                        lastRuns.get(schedule.displayName())))
                .toList();
    }

    /**
     * One firing. Nothing thrown here may escape: a scheduled task that throws is dropped by the
     * scheduler, and a schedule that quietly stops after one bad night is worse than one that logs
     * a failure every night.
     */
    private void run(Schedule schedule) {
        String name = schedule.displayName();
        long startedAt = System.currentTimeMillis();
        try {
            ScriptResult result =
                    runner.run(
                            resolver.resolve(
                                    schedule.project(),
                                    schedule.script(),
                                    schedule.args(),
                                    schedule.timeoutSeconds(),
                                    false,
                                    null),
                            RunCancellation.none());
            lastRuns.put(
                    name,
                    new LastRun(
                            Instant.ofEpochMilli(startedAt),
                            result.error() == null,
                            result.error() == null ? null : String.valueOf(result.error()),
                            result.stats().elapsedMs()));
            if (result.error() == null) {
                log.info("Scheduled script '{}' finished: {}", name, result.getFormattedResponse());
            } else {
                log.warn("Scheduled script '{}' failed: {}", name, result.error());
            }
        } catch (RuntimeException e) {
            log.warn("Scheduled script '{}' could not run", name, e);
            lastRuns.put(
                    name,
                    new LastRun(
                            Instant.ofEpochMilli(startedAt),
                            false,
                            String.valueOf(e.getMessage()),
                            System.currentTimeMillis() - startedAt));
        }
    }

    /**
     * @param name what the configuration calls this schedule
     * @param script the saved script it runs
     * @param project the repository it runs against; null — the default project
     * @param cron the expression it fires on
     * @param lastRun what happened the last time it did; null — it has not fired since startup
     */
    public record Status(
            String name,
            String script,
            @Nullable String project,
            String cron,
            @Nullable LastRun lastRun) {}

    /**
     * @param at when the run started
     * @param ok whether the script finished
     * @param error why it did not, as one line; null when it did
     * @param elapsedMs how long it took
     */
    public record LastRun(Instant at, boolean ok, @Nullable String error, long elapsedMs) {}
}
