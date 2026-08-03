package io.github.trialiya.kb.config.model;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
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
 * @param editEnabled let scripts write through {@code kb.edit} / {@code kb.create}. Necessary but
 *     not sufficient: {@code kb.git.edit-enabled} must be on and the working tree writable, exactly
 *     as for the {@code editFile} tool (see {@code ScriptEditPolicy}). Separate from that flag so a
 *     deployment can keep the edit tools and still hand the model read-only scripts
 * @param guide the reference half of the markdown handbook, injected into the system prompt for as
 *     long as the tool is enabled (see {@code ScriptGuideService})
 * @param extendedGuide the tutorial half — when to prefer a script, how to structure one, worked
 *     examples, what not to do — appended to {@code guide} for a run whose model is flagged {@code
 *     weak} ({@code ChatModelProperties.ModelOption#weak}). A strong model gets only the reference
 *     half, which no model can guess — the {@code kb} API, the budgets, the error kinds
 * @param editGuide reference appendix, appended only when writes are actually available — telling a
 *     model about {@code kb.edit} it cannot call wastes its attempts
 * @param extendedEditGuide tutorial appendix for writes; needs both gates — writes available and
 *     the run's model flagged weak
 * @param timeout wall-clock budget for one script when the model does not ask for a specific one
 * @param maxTimeout ceiling for the tool's own {@code timeoutSeconds} argument
 * @param cancelPoll how often the watchdog re-checks the deadline and the run's cancellation flag
 * @param limits per-run budgets; see {@link Limits}
 * @param denyGlobs paths hidden from scripts <em>on top of</em> {@code .gitignore} (which already
 *     excludes untracked secrets). Empty by default — nothing extra is hidden
 * @param allowGlobs when non-empty, scripts see <em>only</em> matching paths — a whitelist on top
 *     of the tracked-files rule. Empty by default — no additional narrowing
 */
@ConfigurationProperties(prefix = "kb.script")
public record ScriptProperties(
        boolean enabled,
        boolean editEnabled,
        Resource guide,
        Resource extendedGuide,
        Resource editGuide,
        Resource extendedEditGuide,
        Duration timeout,
        Duration maxTimeout,
        Duration cancelPoll,
        Limits limits,
        List<String> denyGlobs,
        List<String> allowGlobs) {

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
            @Nullable Resource guide,
            @Nullable Resource extendedGuide,
            @Nullable Resource editGuide,
            @Nullable Resource extendedEditGuide,
            @Nullable Duration timeout,
            @Nullable Duration maxTimeout,
            @Nullable Duration cancelPoll,
            @Nullable Limits limits,
            @Nullable List<String> denyGlobs,
            @Nullable List<String> allowGlobs) {
        this.enabled = enabled;
        this.editEnabled = editEnabled;
        this.guide = guide != null ? guide : DEFAULT_GUIDE;
        this.extendedGuide = extendedGuide != null ? extendedGuide : DEFAULT_EXTENDED_GUIDE;
        this.editGuide = editGuide != null ? editGuide : DEFAULT_EDIT_GUIDE;
        this.extendedEditGuide =
                extendedEditGuide != null ? extendedEditGuide : DEFAULT_EXTENDED_EDIT_GUIDE;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(10);
        this.maxTimeout = maxTimeout != null ? maxTimeout : Duration.ofSeconds(30);
        this.cancelPoll = cancelPoll != null ? cancelPoll : Duration.ofMillis(50);
        this.limits = limits != null ? limits : new Limits(0, null, 0, 0, 0, 0, null);
        this.denyGlobs = denyGlobs != null ? List.copyOf(denyGlobs) : List.of();
        this.allowGlobs = allowGlobs != null ? List.copyOf(allowGlobs) : List.of();
    }

    /** All-defaults instance with the tool enabled — for tests and programmatic setups. */
    public static ScriptProperties enabledWithDefaults() {
        return new ScriptProperties(
                true, true, null, null, null, null, null, null, null, null, null, null);
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
