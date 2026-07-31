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
 * @param guide markdown handbook injected into the system prompt while the tool is enabled (see
 *     {@code ScriptGuideService}); weak models cannot use the tool from its description alone
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
        Resource guide,
        Duration timeout,
        Duration maxTimeout,
        Duration cancelPoll,
        Limits limits,
        List<String> denyGlobs,
        List<String> allowGlobs) {

    private static final Resource DEFAULT_GUIDE = new ClassPathResource("prompt/script-run.md");

    public ScriptProperties(
            boolean enabled,
            @Nullable Resource guide,
            @Nullable Duration timeout,
            @Nullable Duration maxTimeout,
            @Nullable Duration cancelPoll,
            @Nullable Limits limits,
            @Nullable List<String> denyGlobs,
            @Nullable List<String> allowGlobs) {
        this.enabled = enabled;
        this.guide = guide != null ? guide : DEFAULT_GUIDE;
        this.timeout = timeout != null ? timeout : Duration.ofSeconds(10);
        this.maxTimeout = maxTimeout != null ? maxTimeout : Duration.ofSeconds(30);
        this.cancelPoll = cancelPoll != null ? cancelPoll : Duration.ofMillis(50);
        this.limits = limits != null ? limits : new Limits(0, null, null, 0, 0, 0, 0);
        this.denyGlobs = denyGlobs != null ? List.copyOf(denyGlobs) : List.of();
        this.allowGlobs = allowGlobs != null ? List.copyOf(allowGlobs) : List.of();
    }

    /** All-defaults instance with the tool enabled — for tests and programmatic setups. */
    public static ScriptProperties enabledWithDefaults() {
        return new ScriptProperties(true, null, null, null, null, null, null, null);
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
     */
    public record Limits(
            int maxFilesRead,
            DataSize maxBytesRead,
            DataSize maxFileBytes,
            int maxGrepMatches,
            int maxCalls,
            int maxLogChars,
            int maxResultChars) {

        private static final int DEFAULT_MAX_FILES_READ = 200;
        private static final DataSize DEFAULT_MAX_BYTES_READ = DataSize.ofMegabytes(4);
        private static final DataSize DEFAULT_MAX_FILE_BYTES = DataSize.ofKilobytes(512);
        private static final int DEFAULT_MAX_GREP_MATCHES = 200;
        private static final int DEFAULT_MAX_CALLS = 2000;
        private static final int DEFAULT_MAX_LOG_CHARS = 20_000;
        private static final int DEFAULT_MAX_RESULT_CHARS = 20_000;

        public Limits(
                int maxFilesRead,
                @Nullable DataSize maxBytesRead,
                @Nullable DataSize maxFileBytes,
                int maxGrepMatches,
                int maxCalls,
                int maxLogChars,
                int maxResultChars) {
            this.maxFilesRead = maxFilesRead > 0 ? maxFilesRead : DEFAULT_MAX_FILES_READ;
            this.maxBytesRead = maxBytesRead != null ? maxBytesRead : DEFAULT_MAX_BYTES_READ;
            this.maxFileBytes = maxFileBytes != null ? maxFileBytes : DEFAULT_MAX_FILE_BYTES;
            this.maxGrepMatches = maxGrepMatches > 0 ? maxGrepMatches : DEFAULT_MAX_GREP_MATCHES;
            this.maxCalls = maxCalls > 0 ? maxCalls : DEFAULT_MAX_CALLS;
            this.maxLogChars = maxLogChars > 0 ? maxLogChars : DEFAULT_MAX_LOG_CHARS;
            this.maxResultChars = maxResultChars > 0 ? maxResultChars : DEFAULT_MAX_RESULT_CHARS;
        }
    }
}
