package io.github.trialiya.kb.config.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.util.unit.DataSize;

/**
 * Binding for {@code kb.script.*} — the {@code runScript} tool (see {@code ScriptFunction}).
 *
 * <p>Every value has a code-level default, so a deployment that only flips {@code enabled: true}
 * still gets a bounded configuration; {@code application.yaml} spells the same defaults out again
 * for discoverability.
 *
 * @param enabled expose {@code runScript} to the chat model at all; off by default — a sandbox is
 *     still code execution, so this is an explicit opt-in like {@code kb.mcp.enabled}
 * @param attachmentRun let the model run a script that arrived as an attachment — {@code
 *     runSavedScript} with {@code attachment:<id>}. On by default wherever scripts are on: the
 *     sandbox is the same and such a run is forced read-only, so what it adds over a script the
 *     model writes itself is not capability but provenance — the code came from whoever uploaded
 *     the file. A deployment that is willing to run what its own repository declares but not what
 *     sits in its knowledge base turns this off. Unlike every other flag here it defaults to
 *     <em>on</em> ({@code @DefaultValue("true")}): it narrows a capability {@code enabled} already
 *     granted, and defaulting it off would take away the attachment a user just uploaded with no
 *     line in the configuration to point at
 * @param attachmentEdit let a script that came from an attachment write too, where the project and
 *     {@code editEnabled} already allow writing. Off by default, and deliberately the one flag here
 *     that has to be turned on by hand: an attachment is code somebody uploaded, a document's
 *     attachment is code somebody <em>else</em> uploaded, and read-only is what keeps the worst
 *     case at "time was wasted". A deployment that turns it on is saying its knowledge base is as
 *     trusted as its repository
 * @param editEnabled let scripts write — {@code kb.edit} / {@code kb.create} for text, {@code
 *     kb.writeBytes} / {@code kb.createBytes} for raw bytes. Necessary but not sufficient: {@code
 *     kb.projects[].edit-enabled} must be on and the working tree writable, exactly as for the
 *     {@code editFile} tool (see {@code ScriptEditPolicy}). Separate from that flag so a deployment
 *     can keep the edit tools and still hand the model read-only scripts
 * @param guide the reference half of the markdown handbook, injected into the system prompt for as
 *     long as the tool is enabled (see {@code ScriptGuideService})
 * @param extendedGuide the standing order to load the {@code script-writing} skill before writing a
 *     script ({@code SkillService}), appended to {@code guide} for a run whose model is flagged
 *     {@code weak} ({@code ChatModelProperties.ModelOption#weak}). The worked examples themselves
 *     are not here — they are the skill, loaded on demand; a strong model reaches them through the
 *     catalogue's trigger instead of being ordered outright
 * @param editGuide reference appendix, appended only when writes are actually available — telling a
 *     model about a method it cannot call wastes its attempts
 * @param extendedEditGuide the same standing order for the {@code script-editing} skill; needs both
 *     gates — writes available and the run's model flagged weak
 * @param schedules saved scripts this deployment runs on a clock of its own — {@code
 *     kb.script.schedules[]}, see {@link Schedule}. Empty by default: a repository declaring a
 *     script says what may be run, not that anything should be
 * @param timeout wall-clock budget for one script when the model does not ask for a specific one
 * @param maxTimeout ceiling for the tool's own {@code timeoutSeconds} argument
 * @param cancelPoll how often the watchdog re-checks the deadline and the run's cancellation flag
 * @param limits per-run budgets; see {@link Limits}
 */
@ConfigurationProperties(prefix = "kb.script")
public record ScriptProperties(
        boolean enabled,
        boolean editEnabled,
        boolean attachmentRun,
        boolean attachmentEdit,
        Resource guide,
        Resource extendedGuide,
        Resource editGuide,
        Resource extendedEditGuide,
        List<Schedule> schedules,
        Duration timeout,
        Duration maxTimeout,
        Duration cancelPoll,
        Limits limits) {

    private static final Resource DEFAULT_GUIDE = new ClassPathResource("prompt/script-run.md");

    private static final Resource DEFAULT_EXTENDED_GUIDE =
            new ClassPathResource("prompt/script-run-extended.md");

    private static final Resource DEFAULT_EDIT_GUIDE =
            new ClassPathResource("prompt/script-run-edit.md");

    private static final Resource DEFAULT_EXTENDED_EDIT_GUIDE =
            new ClassPathResource("prompt/script-run-edit-extended.md");

    public ScriptProperties(
            boolean enabled,
            boolean editEnabled,
            @DefaultValue("true") boolean attachmentRun,
            boolean attachmentEdit,
            @Nullable Resource guide,
            @Nullable Resource extendedGuide,
            @Nullable Resource editGuide,
            @Nullable Resource extendedEditGuide,
            @Nullable List<Schedule> schedules,
            @Nullable Duration timeout,
            @Nullable Duration maxTimeout,
            @Nullable Duration cancelPoll,
            @Nullable Limits limits) {
        this.enabled = enabled;
        this.editEnabled = editEnabled;
        this.attachmentRun = attachmentRun;
        this.attachmentEdit = attachmentEdit;
        this.guide = guide != null ? guide : DEFAULT_GUIDE;
        this.extendedGuide = extendedGuide != null ? extendedGuide : DEFAULT_EXTENDED_GUIDE;
        this.editGuide = editGuide != null ? editGuide : DEFAULT_EDIT_GUIDE;
        this.extendedEditGuide =
                extendedEditGuide != null ? extendedEditGuide : DEFAULT_EXTENDED_EDIT_GUIDE;
        this.schedules = schedules == null ? List.of() : List.copyOf(schedules);
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(10);
        this.maxTimeout = maxTimeout != null ? maxTimeout : Duration.ofSeconds(30);
        this.cancelPoll = cancelPoll != null ? cancelPoll : Duration.ofMillis(50);
        this.limits = limits != null ? limits : new Limits(0, null, 0, 0, 0, 0, null);
    }

    /** All-defaults instance with the tool enabled — for tests and programmatic setups. */
    public static ScriptProperties enabledWithDefaults() {
        return new ScriptProperties(
                true, true, true, false, null, null, null, null, null, null, null, null, null);
    }

    /**
     * One scheduled run: a saved script this deployment runs itself, on a cron, with no one
     * watching.
     *
     * <p><b>Read-only, always.</b> A run nobody is looking at is the worst possible place for an
     * unreviewed change to the working tree: there is no diff to see, no message to attribute it
     * to, and nobody to notice for a week. Scripts that write are for the chat, where all three
     * exist.
     *
     * <p><b>The result lives in the log and in the Settings panel, and nowhere else.</b> Keeping a
     * history of scheduled runs is a feature of its own — retention, notification, who reads it —
     * and pretending a ring of the last few runs in memory is that feature would be the worse
     * answer. What is here answers "did it run and did it break", which is what a deployment needs
     * before it needs anything else.
     *
     * @param name what this schedule is called in the log and in the panel; unique, defaults to
     *     {@link #script()}
     * @param project which repository's manifest the name comes from; empty — the default project
     * @param script the script's name in that manifest ({@code attachment:<id>} is not accepted: an
     *     attachment belongs to a chat or a document, not to a clock)
     * @param args arguments by declared name, checked against the declaration before each run
     * @param cron a Spring cron expression, six fields — {@code "0 0 3 * * *"} is 03:00 daily
     * @param timeoutSeconds wall-clock budget; null leaves the script's own, then {@code
     *     kb.script.timeout}
     */
    public record Schedule(
            @Nullable String name,
            @Nullable String project,
            String script,
            Map<String, Object> args,
            String cron,
            @Nullable Integer timeoutSeconds) {

        public Schedule {
            args = args == null ? Map.of() : Map.copyOf(args);
        }

        /** What to call it when the configuration named nothing. */
        public String displayName() {
            return name == null || name.isBlank() ? script : name;
        }
    }

    /**
     * Per-run budgets, and only the ones that bound something the run can actually reach.
     *
     * <p>They used to be justified as context protection, which for a script they are not: what a
     * script reads goes into its own memory and reaches the model only through the value it returns
     * and what it logs — and those two have caps of their own. What is left for the read budgets is
     * the backend: wall-clock (bounded by {@code timeout}), and heap, which cannot be capped on the
     * community engine, so metering host-supplied bytes is the nearest available bound. Sized to
     * stop a runaway loop rather than to stop the work: a repository-wide pass is the whole point
     * of the tool, and a budget that a single honest pass exhausts is a budget in the wrong place.
     *
     * <p>Two former budgets are gone rather than retuned, because neither bounded anything. A
     * per-file ceiling was one line-range loop away from being circumvented, and {@code GitService}
     * already excerpts an oversized whole-file read on its own; a per-grep match ceiling could only
     * ever be lowered, since {@code GitService.grepContent} caps every caller at 200, and lowering
     * it turned one repository scan into several.
     *
     * <p>Any value left at zero/null falls back to the constant beside it.
     *
     * @param maxFilesRead distinct files one run may read
     * @param maxBytesRead total bytes one run may read across all files
     * @param maxCalls {@code kb.*} calls per run that actually did work — a repeat with the same
     *     arguments is answered from the run's cache and does not count (see {@code
     *     ScriptSession#call}). The backstop for a loop that does something new every iteration and
     *     still stays under every other budget
     * @param maxLogChars total characters {@code kb.log} may accumulate
     * @param maxResultChars JSON size cap for the script's return value
     * @param maxEditedFiles files one run may create or modify — the guard against a buggy loop
     *     rewriting the repository
     * @param maxEditedBytes total size of the files one run may write
     */
    public record Limits(
            int maxFilesRead,
            DataSize maxBytesRead,
            int maxCalls,
            int maxLogChars,
            int maxResultChars,
            int maxEditedFiles,
            DataSize maxEditedBytes) {

        /**
         * Comfortably past a whole repository, which is the size of task the tool exists for: this
         * project alone tracks 629 files, so the old ceiling of 200 refused "read every Java file"
         * on the very codebase the tool was written against.
         */
        private static final int DEFAULT_MAX_FILES_READ = 2000;

        /** Roughly ten passes over a repository of this project's size — a loop, not a job. */
        private static final DataSize DEFAULT_MAX_BYTES_READ = DataSize.ofMegabytes(32);

        private static final int DEFAULT_MAX_CALLS = 2000;
        private static final int DEFAULT_MAX_LOG_CHARS = 20_000;
        private static final int DEFAULT_MAX_RESULT_CHARS = 20_000;
        private static final int DEFAULT_MAX_EDITED_FILES = 20;
        private static final DataSize DEFAULT_MAX_EDITED_BYTES = DataSize.ofKilobytes(256);

        public Limits(
                int maxFilesRead,
                @Nullable DataSize maxBytesRead,
                int maxCalls,
                int maxLogChars,
                int maxResultChars,
                int maxEditedFiles,
                @Nullable DataSize maxEditedBytes) {
            this.maxFilesRead = maxFilesRead > 0 ? maxFilesRead : DEFAULT_MAX_FILES_READ;
            this.maxBytesRead = maxBytesRead != null ? maxBytesRead : DEFAULT_MAX_BYTES_READ;
            this.maxCalls = maxCalls > 0 ? maxCalls : DEFAULT_MAX_CALLS;
            this.maxLogChars = maxLogChars > 0 ? maxLogChars : DEFAULT_MAX_LOG_CHARS;
            this.maxResultChars = maxResultChars > 0 ? maxResultChars : DEFAULT_MAX_RESULT_CHARS;
            this.maxEditedFiles = maxEditedFiles > 0 ? maxEditedFiles : DEFAULT_MAX_EDITED_FILES;
            this.maxEditedBytes =
                    maxEditedBytes != null ? maxEditedBytes : DEFAULT_MAX_EDITED_BYTES;
        }
    }
}
