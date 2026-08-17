package io.github.trialiya.kb.service.script;

import io.github.trialiya.kb.model.doc.dto.SearchResult;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileInfo;
import io.github.trialiya.kb.model.git.dto.GitFileOutline;
import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import io.github.trialiya.kb.service.DocumentService;
import io.github.trialiya.kb.service.GitService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
 * <p>Not final: {@code KbEditScriptApi} extends it with the write methods, and which of the two is
 * bound is how "may this script write" is expressed to the guest — an unavailable method is absent,
 * not refusing.
 *
 * <p>Overloads exist purely for the model's benefit: {@code kb.read(path)} and {@code kb.read(path,
 * from, to)} are separate methods because polyglot host calls are arity-matched, and a weak model
 * that omits an optional argument would otherwise get an unhelpful arity error.
 *
 * <p>Every read-only method here is memoized through {@link ScriptSession#call} on its own
 * arguments: a second identical call is answered from the first one's result and spends nothing.
 * Because the returned {@link ProxyArray}/{@link ProxyObject} values write through to their backing
 * Java collections, the cache stores plain data and a fresh proxy is built around a fresh copy on
 * every call, hit or miss — otherwise a script sorting or mutating one call's result would corrupt
 * what a later identical call gets back.
 */
public class KbScriptApi {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /**
     * Bytes one {@code kb.readBytes} / {@code kb.readBase64} call may hand over.
     *
     * <p>Far below the run's byte budget on purpose. A byte reaches the script as a JS number, so a
     * window costs an order of magnitude more in the guest than it does on disk, and the script
     * that asked for a whole 40 MB archive rarely wanted more than its header. Windowing is not a
     * workaround here — it is how a file larger than the run can hold is processed at all.
     */
    private static final int MAX_BYTES_PER_CALL = 256 * 1024;

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

    /**
     * The path as the repository spells it. Every path a script names is put through this before
     * anything is decided about it, because the glob policy is a string match: a script asking for
     * {@code "./secrets/key.pem"} must be answered by the same rule that covers {@code
     * "secrets/key.pem"}, and a deny-glob written the obvious way matches only the latter.
     *
     * <p>It also spares the model a whole class of dead end. A leading {@code ./} is a natural
     * thing to write and every {@code kb} method used to refuse it — reads as "File not found" for
     * a file plainly in the listing, writes as a stranger error still.
     */
    static String canonical(String path) {
        return GitService.normalizePath(path);
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
        List<String> paths =
                session.call(
                        Arrays.<Object>asList("files", glob),
                        () -> {
                            List<String> result = new ArrayList<>();
                            for (String path : gitService.listTrackedFiles()) {
                                if (!session.isVisible(path)) {
                                    continue;
                                }
                                if (glob == null || glob.isBlank() || MATCHER.match(glob, path)) {
                                    result.add(path);
                                }
                            }
                            return result;
                        });
        // Strings are immutable — a shallow copy of the list is all a fresh proxy needs.
        return ProxyArray.fromList(new ArrayList<>(paths));
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
        String canonical = canonical(path);
        return session.call(
                Arrays.<Object>asList("read", canonical, fromLine, toLine),
                () -> {
                    session.requireVisible(canonical);
                    GitFileContent content =
                            gitService.getFileContent(
                                    canonical,
                                    fromLine > 0 ? fromLine : null,
                                    toLine > 0 ? toLine : null);
                    if (content.binary()) {
                        // Not a budget, and not a refusal to open the file either — only a refusal
                        // to pretend its bytes are text. Decoding them as UTF-8 would hand back a
                        // string full of replacement characters that no longer round-trips, so the
                        // model is sent to the two methods that do serve bytes as bytes. Same
                        // exception type as the equivalent refusal in GitService, so it arrives as
                        // RUNTIME and the model stops retrying kb.read.
                        throw new IllegalArgumentException(
                                "Cannot read "
                                        + content.path()
                                        + " as text: it is a binary file. Read its bytes instead"
                                        + " — kb.readBytes(path[, offset, length]) for an array of"
                                        + " byte values, kb.readBase64(path[, offset, length]) for"
                                        + " base64.");
                    }
                    // GitService answers an oversized whole-file read with a head+tail excerpt.
                    // For a person reading a plaque that is a courtesy; for a script it is a wrong
                    // answer that looks like a right one — every count it goes on to make would
                    // silently be of the middle-less file. So the excerpt is refused and the
                    // script is told the one call that does return exact text. (Line ranges are
                    // exempt at the source, which is why this cannot be a size threshold: any
                    // ceiling on the file is one range loop away from being circumvented anyway.)
                    // truncated() is also set for an ordinary range read, so only a whole-file
                    // request can have been cut short against the caller's wishes.
                    if (fromLine <= 0 && toLine <= 0 && content.truncated()) {
                        throw new ScriptLimitExceededException(
                                "Cannot read "
                                        + content.path()
                                        + " whole: it is "
                                        + content.sizeBytes()
                                        + " bytes, and a whole-file read that large comes back"
                                        + " excerpted. Read line ranges instead:"
                                        + " kb.read(path, from, to).");
                    }
                    String text = content.content() == null ? "" : content.content();
                    session.chargeRead(
                            content.path(), text.getBytes(StandardCharsets.UTF_8).length);
                    return text;
                });
    }

    // ── Bytes (binary files included) ───────────────────────────────────────

    /**
     * Size, binary flag and detected language of a tracked file, without its content: {@code {path,
     * size, binary, language}}.
     *
     * <p>Charged as a call and nothing more — it hands over no content, which is also why it does
     * not count as having looked at the file (see {@code ScriptSession#requireRead}).
     */
    @HostAccess.Export
    public Object stat(String path) {
        String canonical = canonical(path);
        Map<String, Object> row =
                session.call(
                        Arrays.<Object>asList("stat", canonical),
                        () -> {
                            session.requireVisible(canonical);
                            GitFileInfo info = gitService.getFileInfo(canonical);
                            Map<String, Object> result = new LinkedHashMap<>();
                            result.put("path", info.path());
                            result.put("size", info.sizeBytes());
                            result.put("binary", info.binary());
                            result.put("language", info.language());
                            return result;
                        });
        return ProxyObject.fromMap(new LinkedHashMap<>(row));
    }

    /** Bytes of a tracked file — binary ones included — as an array of numbers 0..255. */
    @HostAccess.Export
    public Object readBytes(String path) {
        return readBytes(path, 0, 0);
    }

    /**
     * As {@link #readBytes(String)}, for the window starting at {@code offset} ({@code 0}-based)
     * and {@code length} bytes long; {@code 0} for {@code length} means "to the end of the file".
     */
    @HostAccess.Export
    public Object readBytes(String path, int offset, int length) {
        byte[] bytes = readWindow(path, offset, length, "readBytes");
        List<Object> values = new ArrayList<>(bytes.length);
        for (byte b : bytes) {
            values.add(b & 0xFF);
        }
        return ProxyArray.fromList(values);
    }

    /** The same bytes as {@link #readBytes(String)}, base64-encoded. */
    @HostAccess.Export
    public String readBase64(String path) {
        return readBase64(path, 0, 0);
    }

    /**
     * As {@link #readBase64(String)}, for one window — see {@link #readBytes(String, int, int)}.
     */
    @HostAccess.Export
    public String readBase64(String path, int offset, int length) {
        return Base64.getEncoder().encodeToString(readWindow(path, offset, length, "readBase64"));
    }

    /**
     * SHA-256 of a tracked file's bytes, lowercase hex. Reads the whole file however large it is,
     * and hands back 64 characters — which is the whole point: "did these two files change" is
     * answerable without either of them entering the script.
     */
    @HostAccess.Export
    public String hash(String path) {
        String canonical = canonical(path);
        return session.call(
                Arrays.<Object>asList("hash", canonical),
                () -> {
                    session.requireVisible(canonical);
                    String hex = gitService.hashFile(canonical);
                    session.chargeScan(canonical);
                    return hex;
                });
    }

    /**
     * Shared body of {@link #readBytes} and {@link #readBase64}: one window of a file's bytes,
     * memoized on the window rather than on the method, since the two ask for the same thing and
     * differ only in how they hand it over.
     *
     * <p>The size is settled before anything is read, so a whole-file read of something far larger
     * than the run can hold is refused instead of allocated. The cached array is never handed to
     * the guest — the callers copy or encode it — so nothing the script does can corrupt what a
     * later identical call gets back.
     *
     * @param method the caller's own name, for an error message that names a method the script can
     *     actually retry with
     */
    private byte[] readWindow(String path, int offset, int length, String method) {
        String canonical = canonical(path);
        return session.call(
                Arrays.<Object>asList("bytes", canonical, offset, length),
                () -> {
                    session.requireVisible(canonical);
                    long size = gitService.getFileInfo(canonical).sizeBytes();
                    long from = Math.min(Math.max(offset, 0), size);
                    long want = length > 0 ? Math.min(length, size - from) : size - from;
                    if (want > MAX_BYTES_PER_CALL) {
                        throw new ScriptLimitExceededException(
                                "Budget exceeded: maxBytesPerCall="
                                        + MAX_BYTES_PER_CALL
                                        + " bytes per kb."
                                        + method
                                        + " call, but "
                                        + canonical
                                        + " has "
                                        + want
                                        + " bytes left to read. Read it in windows: kb."
                                        + method
                                        + "(path, offset, length).");
                    }
                    // Charged before the read so the byte budget bounds what is allocated, not
                    // only what is handed over.
                    session.chargeRead(canonical, want);
                    return gitService.getFileBytes(canonical, from, want).bytes();
                });
    }

    /** Structural outline (classes, methods, ...) of a tracked source file, without its text. */
    @HostAccess.Export
    public Object outline(String path) {
        String canonical = canonical(path);
        List<Map<String, Object>> symbols =
                session.call(
                        Arrays.<Object>asList("outline", canonical),
                        () -> {
                            session.requireVisible(canonical);
                            GitFileOutline outline = gitService.getFileOutline(canonical);
                            session.chargeRead(outline.path(), 0);
                            List<Map<String, Object>> rows = new ArrayList<>();
                            outline.symbols()
                                    .forEach(
                                            symbol -> {
                                                Map<String, Object> row = new LinkedHashMap<>();
                                                row.put("kind", symbol.kind());
                                                row.put("name", symbol.name());
                                                row.put("signature", symbol.signature());
                                                row.put("startLine", symbol.startLine());
                                                row.put("endLine", symbol.endLine());
                                                rows.add(row);
                                            });
                            return rows;
                        });
        return ProxyArray.fromList(freshRows(symbols));
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
        String glob = member(options, "glob", Value::isString, Value::asString);
        Boolean regex = member(options, "regex", Value::isBoolean, Value::asBoolean);
        Integer context = member(options, "context", Value::isNumber, Value::asInt);
        Integer max = member(options, "max", Value::isNumber, Value::asInt);

        List<Map<String, Object>> rows =
                session.call(
                        Arrays.<Object>asList("grep", pattern, glob, regex, context, max),
                        () -> {
                            List<GitGrepMatch> matches =
                                    gitService.grepContent(
                                            pattern,
                                            glob,
                                            regex != null && regex,
                                            context != null && context > 0 ? context : 0,
                                            // GitService caps every caller at 200; passing the
                                            // request through means a script asking for fewer gets
                                            // fewer, and asking for more is not an error.
                                            max != null && max > 0 ? max : Integer.MAX_VALUE);

                            List<Map<String, Object>> result = new ArrayList<>();
                            long bytes = 0;
                            for (GitGrepMatch match : matches) {
                                if (!session.isVisible(match.path())) {
                                    continue;
                                }
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("path", match.path());
                                row.put("line", match.matchLine());
                                row.put("text", match.text());
                                result.add(row);
                                bytes += match.text().getBytes(StandardCharsets.UTF_8).length;
                                // The script has now been shown current text of this file, which
                                // is what the edit rule asks for — see ScriptSession.requireRead.
                                session.noteSeen(match.path());
                            }
                            // Only what the script actually gets back is charged: a match inside a
                            // denied path was never handed over, and charging for it would let the
                            // glob policy spend someone's budget.
                            session.chargeSearch(bytes);
                            return result;
                        });
        return ProxyArray.fromList(freshRows(rows));
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
        List<Map<String, Object>> rows =
                session.call(
                        Arrays.<Object>asList("searchDocs", query, limit),
                        () -> {
                            List<SearchResult> hits =
                                    documentService.hybridSearch(
                                            query, null, limit > 0 ? limit : null, null, null);
                            List<Map<String, Object>> result = new ArrayList<>();
                            long bytes = 0;
                            for (SearchResult hit : hits) {
                                Map<String, Object> row = new LinkedHashMap<>();
                                row.put("docId", hit.id());
                                row.put("title", hit.title());
                                row.put("snippet", hit.snippet());
                                result.add(row);
                                bytes += utf8Length(hit.title()) + utf8Length(hit.snippet());
                            }
                            session.chargeDocSearch(bytes);
                            return result;
                        });
        return ProxyArray.fromList(freshRows(rows));
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

    /**
     * A fresh {@link ProxyObject} over a fresh copy of each row, so the array handed to the guest
     * this call shares no mutable state with a cached result a later identical call will also
     * return. {@code ProxyArray.fromList}/{@code ProxyObject.fromMap} write through to their
     * backing collection — a script that sorts or edits its result would otherwise reorder or
     * rewrite the cache entry itself, corrupting what the next identical call gets back.
     */
    private static List<Object> freshRows(List<Map<String, Object>> rows) {
        List<Object> copies = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            copies.add(ProxyObject.fromMap(new LinkedHashMap<>(row)));
        }
        return copies;
    }

    private static int utf8Length(@Nullable String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
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
