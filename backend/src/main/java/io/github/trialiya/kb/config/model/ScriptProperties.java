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
 * @param extendedGuideEnabled append the tutorial half of the handbook — when to prefer a script,
 *     how to structure one, worked examples, what not to do. On by default: a weak model needs it.
 *     A deployment running a strong model can switch it off and keep only the reference half, which
 *     no model can guess — the {@code kb} API, the budgets, the error kinds
 * @param guide the reference half of the markdown handbook, injected into the system prompt for as
 *     long as the tool is enabled (see {@code ScriptGuideService})
 * @param extendedGuide the tutorial half, appended to {@code guide} while {@code
 *     extendedGuideEnabled} is on
 * @param editGuide reference appendix, appended only when writes are actually available — telling a
 *     model about {@code kb.edit} it cannot call wastes its attempts
 * @param extendedEditGuide tutorial appendix for writes; needs both gates — writes available and
 *     {@code extendedGuideEnabled} on
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
        boolean extendedGuideEnabled,
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
            boolean extendedGuideEnabled,
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
        this.extendedGuideEnabled = extendedGuideEnabled;
        this.guide = guide != null ? guide : DEFAULT_GUIDE;
        this.extendedGuide = extendedGuide != null ? extendedGuide : DEFAULT_EXTENDED_GUIDE;
        this.editGuide = editGuide != null ? editGuide : DEFAULT_EDIT_GUIDE;
        this.extendedEditGuide =
                extendedEditGuide != null ? extendedEditGuide : DEFAULT_EXTENDED_EDIT_GUIDE;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(10);
        this.maxTimeout = maxTimeout != null ? maxTimeout : Duration.ofSeconds(30);
        this.cancelPoll = cancelPoll != null ? cancelPoll : Duration.ofMillis(50);
        this.limits = limits != null ? limits : new Limits(0, null, null, 0, 0, 0, 0, 0, null);
        this.denyGlobs = denyGlobs != null ? List.copyOf(denyGlobs) : List.of();
        this.allowGlobs = allowGlobs != null ? List.copyOf(allowGlobs) : List.of();
    }

    /** All-defaults instance with the tool enabled — for tests and programmatic setups. */
    public static ScriptProperties enabledWithDefaults() {
        return new ScriptProperties(
                true, true, true, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * Per-run budgets. These are the practical meaning of "restricted file access": confining a
     * script to tracked files still lets it drag the whole repository into the model's context, so
     * every read is metered as well as authorised. Any value left at zero/null falls back to the
     * constant beside it.
     *
     * @param maxFilesRead distinct files one run may read
     * @param maxBytesRead total bytes one run may read across all files
     * @param maxFileBytes largest single file a script may read
     * @param maxGrepMatches matches one {@code kb.grep} call may return. Raising it above 200
     *     changes nothing — {@code GitService.grepContent} caps there for every caller — so the
     *     default matches that ceiling instead of promising the model a number it cannot get
     * @param maxCalls total {@code kb.*} calls per run — the backstop for a tight loop that stays
     *     under every other budget (re-reading one already-charged file, say)
     * @param maxLogChars total characters {@code kb.log} may accumulate
     * @param maxResultChars JSON size cap for the script's return value
     * @param maxEditedFiles files one run may create or modify — the guard against a buggy loop
     *     rewriting the repository
     * @param maxEditedBytes total size of the files one run may write
     */
    public record Limits(
            int maxFilesRead,
            DataSize maxBytesRead,
            DataSize maxFileBytes,
            int maxGrepMatches,
            int maxCalls,
            int maxLogChars,
            int maxResultChars,
            int maxEditedFiles,
            DataSize maxEditedBytes) {

        private static final int DEFAULT_MAX_FILES_READ = 200;
        private static final DataSize DEFAULT_MAX_BYTES_READ = DataSize.ofMegabytes(4);
        private static final DataSize DEFAULT_MAX_FILE_BYTES = DataSize.ofKilobytes(512);
        private static final int DEFAULT_MAX_GREP_MATCHES = 200;
        private static final int DEFAULT_MAX_CALLS = 2000;
        private static final int DEFAULT_MAX_LOG_CHARS = 20_000;
        private static final int DEFAULT_MAX_RESULT_CHARS = 20_000;
        private static final int DEFAULT_MAX_EDITED_FILES = 20;
        private static final DataSize DEFAULT_MAX_EDITED_BYTES = DataSize.ofKilobytes(256);

        public Limits(
                int maxFilesRead,
                @Nullable DataSize maxBytesRead,
                @Nullable DataSize maxFileBytes,
                int maxGrepMatches,
                int maxCalls,
                int maxLogChars,
                int maxResultChars,
                int maxEditedFiles,
                @Nullable DataSize maxEditedBytes) {
            this.maxFilesRead = maxFilesRead > 0 ? maxFilesRead : DEFAULT_MAX_FILES_READ;
            this.maxBytesRead = maxBytesRead != null ? maxBytesRead : DEFAULT_MAX_BYTES_READ;
            this.maxFileBytes = maxFileBytes != null ? maxFileBytes : DEFAULT_MAX_FILE_BYTES;
            this.maxGrepMatches = maxGrepMatches > 0 ? maxGrepMatches : DEFAULT_MAX_GREP_MATCHES;
            this.maxCalls = maxCalls > 0 ? maxCalls : DEFAULT_MAX_CALLS;
            this.maxLogChars = maxLogChars > 0 ? maxLogChars : DEFAULT_MAX_LOG_CHARS;
            this.maxResultChars = maxResultChars > 0 ? maxResultChars : DEFAULT_MAX_RESULT_CHARS;
            this.maxEditedFiles = maxEditedFiles > 0 ? maxEditedFiles : DEFAULT_MAX_EDITED_FILES;
            this.maxEditedBytes =
                    maxEditedBytes != null ? maxEditedBytes : DEFAULT_MAX_EDITED_BYTES;
        }
    }
}
