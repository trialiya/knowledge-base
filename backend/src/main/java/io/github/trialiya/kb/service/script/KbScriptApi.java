package io.github.trialiya.kb.service.script;

import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileOutline;
import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.service.GitService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.jspecify.annotations.Nullable;
import org.springframework.util.AntPathMatcher;

/**
 * The object bound as {@code kb} inside a script — and the only way out of the sandbox.
 *
 * <p>The script's engine is built with no filesystem, no host class lookup and no threads (see
 * {@code ScriptRunner}), so there is no {@code java.io} to restrict in the first place; file access
 * exists only because these methods provide it. Each one goes through {@link GitService}, which
 * serves tracked files only and confines every path to the working tree, and through {@link
 * ScriptSession}, which applies the configured glob policy and the per-run budgets.
 *
 * <p>Methods are annotated {@link HostAccess.Export} one by one and the context is built with
 * {@code HostAccess.EXPLICIT}: anything not annotated here — including everything inherited from
 * {@code Object} — is invisible to the guest. Return values are handed over as {@link ProxyObject}
 * / {@link ProxyArray} rather than raw Java collections, so scripts see ordinary JS arrays and
 * objects and no host class ever leaks through a result.
 *
 * <p>Overloads exist purely for the model's benefit: {@code kb.read(path)} and {@code kb.read(path,
 * from, to)} are separate methods because polyglot host calls are arity-matched, and a weak model
 * that omits an optional argument would otherwise get an unhelpful arity error.
 */
public final class KbScriptApi {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final GitService gitService;
    private final DocumentService documentService;
    private final ScriptSession session;

    /**
     * Guest-side formatter for {@link #log}, injected after the context exists. Java's view of a JS
     * value is a debug string ({@code Value.toString()}); running the guest's own {@code
     * JSON.stringify} is what makes {@code kb.log({a: 1})} readable in the result.
     */
    private @Nullable Function<Value, String> formatter;

    public KbScriptApi(
            GitService gitService, DocumentService documentService, ScriptSession session) {
        this.gitService = gitService;
        this.documentService = documentService;
        this.session = session;
    }

    void bindFormatter(Function<Value, String> formatter) {
        this.formatter = formatter;
    }

    // ── Listing ─────────────────────────────────────────────────────────────

    /** Every tracked path the script is allowed to see. */
    @HostAccess.Export
    public Object files() {
        return files(null);
    }

    /**
     * Tracked paths matching an Ant-style glob ({@code "**}{@code /*.java"}, {@code "backend/**"}).
     */
    @HostAccess.Export
    public Object files(@Nullable String glob) {
        session.chargeCall();
        List<Object> paths = new ArrayList<>();
        for (String path : gitService.listTrackedFiles()) {
            if (!session.isVisible(path)) {
                continue;
            }
            if (glob == null || glob.isBlank() || MATCHER.match(glob, path)) {
                paths.add(path);
            }
        }
        return ProxyArray.fromList(paths);
    }

    // ── Reading ─────────────────────────────────────────────────────────────

    /** Full text of a tracked file. */
    @HostAccess.Export
    public String read(String path) {
        return read(path, 0, 0);
    }

    /**
     * Lines {@code fromLine}..{@code toLine} (1-based, inclusive) of a tracked file; {@code 0} on
     * either side means "from the start" / "to the end".
     */
    @HostAccess.Export
    public String read(String path, int fromLine, int toLine) {
        session.chargeCall();
        session.requireVisible(path);
        GitFileContent content =
                gitService.getFileContent(
                        path, fromLine > 0 ? fromLine : null, toLine > 0 ? toLine : null);
        if (content.binary()) {
            // Not a budget: no limit would make this file readable. Same exception type as the
            // equivalent refusal in GitService, so the model sees RUNTIME and stops retrying.
            throw new IllegalArgumentException("Cannot read a binary file: " + content.path());
        }
        // The per-file budget guards whole-file reads only — refusing a line range out of a big
        // file would contradict the very advice its error message gives.
        if (fromLine <= 0 && toLine <= 0) {
            session.checkFileSize(content.path(), content.sizeBytes());
        }
        String text = content.content() == null ? "" : content.content();
        session.chargeRead(content.path(), text.getBytes(StandardCharsets.UTF_8).length);
        return text;
    }

    /** Structural outline (classes, methods, ...) of a tracked source file, without its text. */
    @HostAccess.Export
    public Object outline(String path) {
        session.chargeCall();
        session.requireVisible(path);
        GitFileOutline outline = gitService.getFileOutline(path);
        session.chargeRead(outline.path(), 0);
        List<Object> symbols = new ArrayList<>();
        outline.symbols()
                .forEach(
                        symbol -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("kind", symbol.kind());
                            row.put("name", symbol.name());
                            row.put("signature", symbol.signature());
                            row.put("startLine", symbol.startLine());
                            row.put("endLine", symbol.endLine());
                            symbols.add(ProxyObject.fromMap(row));
                        });
        return ProxyArray.fromList(symbols);
    }

    // ── Searching ───────────────────────────────────────────────────────────

    /** Case-insensitive literal search across the text of tracked files. */
    @HostAccess.Export
    public Object grep(String pattern) {
        return grep(pattern, null);
    }

    /**
     * As {@link #grep(String)}, with an options object: {@code {glob, regex, context, max}}.
     *
     * @param options guest object; missing and mistyped members fall back to the defaults
     */
    @HostAccess.Export
    public Object grep(String pattern, @Nullable Value options) {
        session.chargeCall();
        String glob = member(options, "glob", Value::isString, Value::asString);
        Boolean regex = member(options, "regex", Value::isBoolean, Value::asBoolean);
        Integer context = member(options, "context", Value::isNumber, Value::asInt);
        Integer max = member(options, "max", Value::isNumber, Value::asInt);

        List<GitGrepMatch> matches =
                gitService.grepContent(
                        pattern,
                        glob,
                        regex != null && regex,
                        context != null && context > 0 ? context : 0,
                        session.cappedGrepLimit(max));

        List<Object> rows = new ArrayList<>();
        for (GitGrepMatch match : matches) {
            if (!session.isVisible(match.path())) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", match.path());
            row.put("line", match.matchLine());
            row.put("text", match.text());
            rows.add(ProxyObject.fromMap(row));
        }
        return ProxyArray.fromList(rows);
    }

    /** Hybrid (keyword + semantic) search over the knowledge base documents. */
    @HostAccess.Export
    public Object searchDocs(String query) {
        return searchDocs(query, 0);
    }

    /**
     * As {@link #searchDocs(String)}, capped at {@code limit} hits ({@code 0} — the default cap).
     */
    @HostAccess.Export
    public Object searchDocs(String query, int limit) {
        session.chargeCall();
        List<SearchResult> hits =
                documentService.hybridSearch(query, null, limit > 0 ? limit : null, null, null);
        List<Object> rows = new ArrayList<>();
        for (SearchResult hit : hits) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("docId", hit.id());
            row.put("title", hit.title());
            row.put("snippet", hit.snippet());
            rows.add(ProxyObject.fromMap(row));
        }
        return ProxyArray.fromList(rows);
    }

    // ── Output ──────────────────────────────────────────────────────────────

    /** Records a line in the run's log; objects are rendered with the guest's {@code JSON}. */
    @HostAccess.Export
    public void log(@Nullable Value value) {
        session.chargeCall();
        session.log(format(value));
    }

    private String format(@Nullable Value value) {
        if (value == null || value.isNull()) {
            return "null";
        }
        if (value.isString()) {
            return value.asString();
        }
        if (formatter == null) {
            return value.toString();
        }
        try {
            String formatted = formatter.apply(value);
            return formatted == null ? "null" : formatted;
        } catch (RuntimeException e) {
            // JSON.stringify throws on cycles and on BigInt — a log line is never worth failing
            // the whole run for.
            return "[unserializable: " + e.getClass().getSimpleName() + "]";
        }
    }

    private static <T> @Nullable T member(
            @Nullable Value options,
            String name,
            java.util.function.Predicate<Value> typeCheck,
            Function<Value, T> reader) {
        if (options == null || options.isNull() || !options.hasMembers()) {
            return null;
        }
        Value member = options.getMember(name);
        if (member == null || member.isNull() || !typeCheck.test(member)) {
            return null;
        }
        return reader.apply(member);
    }
}
