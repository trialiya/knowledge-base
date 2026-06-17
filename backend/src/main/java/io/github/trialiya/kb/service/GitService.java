package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.model.git.dto.GitFileOutline;
import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import io.github.trialiya.kb.model.git.dto.OutlineResult;
import io.github.trialiya.kb.service.outline.LanguageDetector;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service for read-only Git repository operations.
 *
 * <p>All commands run against the repository at {@code kb.project-path}. Files matched by {@code
 * .gitignore} are excluded from tree/search results via {@code git ls-files} and {@code git log}
 * (which inherently respect ignore rules).
 */
@Slf4j

    private static final Pattern SAFE_GIT_RELATIVE_PATH =
            Pattern.compile("^[\\p{L}\\p{N}._/\\- ]+$");
@Service
public class GitService {

    private static final long MAX_FILE_SIZE = 512 * 1024; // 512 KB — skip huge files
    private static final int MAX_DIFF_LINES = 500; // truncate very large diffs

    /** Bytes inspected when sniffing for binary content (a NUL byte ⇒ binary). */
    private static final int BINARY_SNIFF_BYTES = 8192;

    /** When a file exceeds MAX_FILE_SIZE, return this many lines from the head and tail. */
    private static final int TRUNCATE_HEAD_LINES = 200;

    private static final int TRUNCATE_TAIL_LINES = 50;

    /** File names to always exclude from uncommitted changes (OS/IDE junk). */
    private static final java.util.Set<String> IGNORED_FILES =
            java.util.Set.of(".DS_Store", "Thumbs.db", "desktop.ini", ".directory");

    /** File extensions to always exclude from uncommitted changes. */
    private static final java.util.Set<String> IGNORED_EXTENSIONS =
            java.util.Set.of(
                    ".class", ".jar", ".war", ".ear", ".o", ".so", ".dylib", ".dll", ".exe", ".pyc",
                    ".pyo", ".swp", ".swo", ".bak", ".tmp", ".orig");

    /** Record separator used in custom git log format. */
    private static final String RS = "\u001e";

    /** Unit separator for fields inside one record. */
    private static final String US = "\u001f";

    private final Path repoPath;
    private final OutlineService outlineService;

    public GitService(
            @Value("${kb.project-path}") String projectPath, OutlineService outlineService) {
        this.repoPath = Path.of(projectPath).toAbsolutePath().normalize();
        this.outlineService = outlineService;
        log.info("GitService initialised for repo: {}", repoPath);
    }

    // ── File tree ────────────────────────────────────────────────────────────

    /**
     * Returns tracked files/directories under {@code subPath} (or repo root if null). Uses {@code
     * git ls-files} so .gitignore'd files are automatically excluded.
     */
    public List<GitFileNode> getFileTree(@Nullable String subPath) {
        String base = normalizeSub(subPath);
        // git ls-files lists tracked files; untracked/ignored are excluded.
        List<String> args = new ArrayList<>(List.of("git", "ls-files", "--full-name"));
        if (!base.isEmpty()) {
            args.add(base);
        }
        List<String> lines = exec(args);

        // Build a de-duplicated list: files + their direct parent directories
        // relative to the requested subPath.
        var nodes = new java.util.LinkedHashMap<String, GitFileNode>();
        int prefixLen = base.isEmpty() ? 0 : base.length() + 1; // +1 for trailing '/'

        for (String line : lines) {
            if (line.length() <= prefixLen) continue;
            String relative = line.substring(prefixLen);
            int slash = relative.indexOf('/');
            if (slash >= 0) {
                // It's inside a subdirectory — add directory node
                String dirName = relative.substring(0, slash);
                String dirPath = base.isEmpty() ? dirName : base + "/" + dirName;
                nodes.putIfAbsent(dirPath, new GitFileNode(dirPath, dirName, "directory", null));
            } else {
                // Direct file
                long size = fileSize(line);
                nodes.putIfAbsent(line, new GitFileNode(line, relative, "file", size));
            }
        }
        return new ArrayList<>(nodes.values());
    }

    // ── Commit history ───────────────────────────────────────────────────────

    /**
     * Returns recent commit history.
     *
     * @param maxCount max commits to return (default 20, capped at 100)
     * @param filePath optional — limit history to a specific file
     */
    public List<GitCommit> getCommitLog(int maxCount, @Nullable String filePath) {
        int limit = Math.min(Math.max(maxCount, 1), 100);
        // Format: hash, short hash, author, email, ISO date, subject — separated by US
        String format = String.join(US, "%H", "%h", "%an", "%ae", "%aI", "%s");
        List<String> args =
                new ArrayList<>(
                        List.of(
                                "git",
                                "log",
                                "--format=" + RS + format,
                                "-n",
                                String.valueOf(limit)));
        if (filePath != null && !filePath.isBlank()) {
            args.add("--");
            args.add(filePath.strip());
        }
        String raw = String.join("\n", exec(args));
        List<GitCommit> commits = new ArrayList<>();
        for (String record : raw.split(RS)) {
            if (record.isBlank()) continue;
            String[] f = record.strip().split(US, -1);
            if (f.length < 6) continue;
            commits.add(new GitCommit(f[0], f[1], f[2], f[3], parseDate(f[4]), f[5], null));
        }
        return commits;
    }

    // ── Diff for commit(s) ──────────────────────────────────────────────────

    /**
     * Returns changed files with optional unified diff for one or more commits.
     *
     * @param commitHashes comma-separated commit hashes
     * @param includePatch whether to include unified diff text
     */
    public List<GitCommit> getCommitDiff(@NonNull String commitHashes, boolean includePatch) {
        return getCommitDiff(commitHashes, includePatch, null);
    }

    /**
     * Returns changed files with optional unified diff for one or more commits, optionally
     * restricted to a single file.
     *
     * @param commitHashes comma-separated commit hashes
     * @param includePatch whether to include unified diff text
     * @param filePath optional path (relative to repo root) to restrict the diff to a single file;
     *     null or blank means the whole commit
     */
    public List<GitCommit> getCommitDiff(
            @NonNull String commitHashes, boolean includePatch, String filePath) {
        String spec = (filePath == null || filePath.isBlank()) ? null : filePath.strip();
        List<GitCommit> result = new ArrayList<>();
        for (String hash : commitHashes.split(",")) {
            String h = hash.strip();
            if (h.isEmpty()) continue;
            result.add(diffForSingleCommit(h, includePatch, spec));
        }
        return result;
    }

    private GitCommit diffForSingleCommit(String hash, boolean includePatch, String filePath) {
        // Commit metadata
        String format = String.join(US, "%H", "%h", "%an", "%ae", "%aI", "%s");
        List<String> metaLines = exec(List.of("git", "log", "-1", "--format=" + format, hash));
        String meta = String.join("", metaLines).strip();
        String[] f = meta.split(US, -1);
        if (f.length < 6) {
            throw new IllegalArgumentException("Commit not found: " + hash);
        }

        // Changed files with stats
        List<String> statCmd =
                new ArrayList<>(
                        List.of(
                                "git",
                                "diff-tree",
                                "--no-commit-id",
                                "-r",
                                "--numstat",
                                "--find-renames",
                                hash));
        if (filePath != null) {
            statCmd.add("--");
            statCmd.add(filePath);
        }
        List<String> statLines = exec(statCmd);

        // Optionally get patches
        Map<String, String> patches;
        if (includePatch) {
            List<String> patchCmd =
                    new ArrayList<>(
                            List.of(
                                    "git",
                                    "diff-tree",
                                    "--no-commit-id",
                                    "-r",
                                    "-p",
                                    "--find-renames",
                                    hash));
            if (filePath != null) {
                patchCmd.add("--");
                patchCmd.add(filePath);
            }
            patches = parsePatchBlocks(exec(patchCmd));
        } else {
            patches = Collections.emptyMap();
        }

        List<GitDiffEntry> entries = new ArrayList<>();
        for (String line : statLines) {
            if (line.isBlank()) continue;
            // numstat format: additions\tdeletions\tpath  (or old\tnew for renames)
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) continue;
            int add = parseStat(parts[0]);
            int del = parseStat(parts[1]);
            String pathPart = parts[2];
            String oldPath = null;
            String entryPath;
            // Rename: "old => new" or "{old => new}/rest"
            if (pathPart.contains(" => ")) {
                String[] rp = parseRenamePath(pathPart);
                oldPath = rp[0];
                entryPath = rp[1];
            } else {
                entryPath = pathPart;
            }
            String status =
                    oldPath != null
                            ? "R"
                            : (add > 0 && del == 0 ? "A" : (add == 0 && del > 0 ? "D" : "M"));
            String patch = patches.getOrDefault(entryPath, patches.get(oldPath));
            if (patch != null && patch.lines().count() > MAX_DIFF_LINES) {
                patch =
                        patch.lines().limit(MAX_DIFF_LINES).collect(Collectors.joining("\n"))
                                + "\n... (truncated)";
            }
            entries.add(new GitDiffEntry(status, entryPath, oldPath, add, del, patch));
        }

        return new GitCommit(f[0], f[1], f[2], f[3], parseDate(f[4]), f[5], entries);
    }

    // ── File search ─────────────────────────────────────────────────────────

    /**
     * Fuzzy-searches tracked files by name. {@code pattern} is matched as a <b>subsequence</b> of
     * each file's name, so {@code "mgi"} matches {@code "MessageInput"}. Results are ranked by how
     * well the characters align to word boundaries (start of name, camelCase humps, and {@code - _
     * . /} separators) and by consecutive runs, so the most "intentional" match floats to the top.
     * Falls back to matching the full path when the name alone doesn't match.
     *
     * @param pattern partial file name; blank returns an empty list
     * @param maxResults capped at 50
     */
    public List<GitFileNode> searchFiles(@NonNull String pattern, int maxResults) {
        if (pattern.isBlank()) return List.of();
        String q = pattern.strip().toLowerCase();
        int limit = Math.min(Math.max(maxResults, 1), 50);

        List<String> allFiles = exec(List.of("git", "ls-files", "--full-name"));

        record Scored(String path, int score) {}
        return allFiles.stream()
                .map(
                        path -> {
                            String name =
                                    path.contains("/")
                                            ? path.substring(path.lastIndexOf('/') + 1)
                                            : path;
                            int score = fuzzyScore(q, name);
                            if (score < 0) {
                                // Name alone didn't match — try the whole path, but rank it
                                // below any name match so file-name hits always win.
                                int pathScore = fuzzyScore(q, path);
                                score = pathScore < 0 ? -1 : pathScore - 1000;
                            }
                            // Demote test files by ~30 % so production sources rank higher.
                            if (score > 0 && isTestPath(path)) {
                                score = score * 7 / 10;
                            }
                            return new Scored(path, score);
                        })
                .filter(s -> s.score() >= 0)
                .sorted(
                        java.util.Comparator.comparingInt(Scored::score)
                                .reversed()
                                .thenComparingInt(s -> s.path().length()))
                .limit(limit)
                .map(
                        s -> {
                            String name =
                                    s.path().contains("/")
                                            ? s.path().substring(s.path().lastIndexOf('/') + 1)
                                            : s.path();
                            return new GitFileNode(s.path(), name, "file", fileSize(s.path()));
                        })
                .toList();
    }

    private static boolean isTestPath(String path) {
        return path.startsWith("src/test/")
                || path.contains("/src/test/")
                || path.startsWith("test/")
                || path.contains("/test/");
    }

    /**
     * Subsequence fuzzy-match score of {@code query} (already lower-cased) against {@code text}
     * (original case, for boundary detection). Returns {@code -1} when {@code query} is not a
     * subsequence of {@code text}; otherwise a non-negative score where higher means a tighter,
     * more boundary-aligned match.
     */
    private static int fuzzyScore(String query, String text) {
        if (query.isEmpty()) return 0;
        int score = 0;
        int qi = 0;
        int run = 0;
        for (int ti = 0; ti < text.length() && qi < query.length(); ti++) {
            if (Character.toLowerCase(text.charAt(ti)) == query.charAt(qi)) {
                boolean boundary;
                if (ti == 0) {
                    boundary = true;
                } else {
                    char prev = text.charAt(ti - 1);
                    boundary =
                            prev == '-'
                                    || prev == '_'
                                    || prev == '/'
                                    || prev == '.'
                                    || (Character.isLowerCase(prev)
                                            && Character.isUpperCase(text.charAt(ti)));
                }
                run++;
                score += 1 + run * 2 + (boundary ? 15 : 0);
                qi++;
            } else {
                run = 0;
            }
        }
        if (qi < query.length()) return -1; // not all query chars consumed
        // Prefer shorter names (fewer unmatched leftover characters).
        return Math.max(0, score - (text.length() - query.length()));
    }

    // ── Content grep ────────────────────────────────────────────────────────

    /**
     * Searches the contents of tracked files for lines matching {@code pattern}.
     *
     * <p>Delegates to {@code git grep}, which searches only tracked files (honouring {@code
     * .gitignore}) and is orders of magnitude faster than scanning the filesystem. Binary files are
     * skipped automatically by git grep.
     *
     * <p>The search is <b>literal by default</b> ({@code --fixed-strings}). Pass {@code regex=true}
     * to enable POSIX extended regular expressions. The search is always <b>case-insensitive</b>
     * ({@code -i}) because the AI often doesn't know exact casing.
     *
     * <p>When {@code contextLines > 0} the raw git grep output contains context lines (prefixed
     * with {@code -}) and groups separated by {@code --}. These are collapsed into one {@link
     * GitGrepMatch} per contiguous block so the caller sees grouped context rather than one record
     * per raw line.
     *
     * @param pattern literal string or regex to search for
     * @param pathGlob optional glob to restrict search to matching paths (e.g. {@code "*.java"},
     *     {@code "src/main/**"}); null means all tracked files
     * @param regex if true, treat {@code pattern} as an extended regex; otherwise literal
     * @param contextLines number of context lines before and after each match (like grep -C); 0
     *     means match line only; capped at 10
     * @param maxResults maximum number of match blocks to return; capped at 200
     * @return list of match blocks in order of appearance; empty list if nothing matched
     */
    public List<GitGrepMatch> grepContent(
            @NonNull String pattern,
            @Nullable String pathGlob,
            boolean regex,
            int contextLines,
            int maxResults) {

        int ctx = Math.min(Math.max(contextLines, 0), 10);
        int limit = Math.min(Math.max(maxResults, 1), 200);

        if (!regex && (pattern.contains(".*") || pattern.contains("|"))) {
            log.warn(
                    "grepContent: pattern '{}' looks like regex but regex=false — using literal match",
                    pattern);
        }

        // Build: git grep -n -i [--fixed-strings|-E] [-C <ctx>] -- <pattern> [-- <glob>]
        List<String> args = new ArrayList<>();
        args.add("git");
        args.add("grep");
        args.add("-n"); // line numbers
        args.add("-i"); // case-insensitive
        if (!regex) {
            args.add("--fixed-strings");
        } else {
            args.add("-E"); // extended regex
        }
        if (ctx > 0) {
            args.add("-C");
            args.add(String.valueOf(ctx));
        }
        args.add("--");
        args.add(pattern);
        if (pathGlob != null && !pathGlob.isBlank()) {
            args.add("--"); // second -- separates pattern from pathspecs
            args.add(pathGlob.strip());
        }

        List<String> lines = exec(args);
        return parseGrepOutput(lines, ctx, limit);
    }

    /**
     * Parses raw {@code git grep [-C ctx]} output into grouped {@link GitGrepMatch} blocks.
     *
     * <p>Without context (ctx=0) each output line is {@code path:linenum:text} and maps directly to
     * one match block.
     *
     * <p>With context git grep emits:
     *
     * <ul>
     *   <li>{@code path:linenum:text} — match line (separator {@code :})
     *   <li>{@code path-linenum-text} — context line (separator {@code -})
     *   <li>{@code --} — group separator between non-adjacent blocks
     * </ul>
     *
     * Adjacent lines belonging to the same file+block are folded into one {@link GitGrepMatch}
     * whose {@code text} reproduces the git grep format ({@code :N:} for matches, {@code -N-} for
     * context). The {@code matchLine} field holds the line number of the first match in the block.
     */
    private static List<GitGrepMatch> parseGrepOutput(List<String> lines, int ctx, int limit) {

        List<GitGrepMatch> results = new ArrayList<>();

        if (ctx == 0) {
            // Simple case: one match per line, format "path:linenum:text"
            for (String line : lines) {
                if (line.isBlank()) continue;
                ParsedLine pl = parseLine(line);
                if (pl == null) continue;
                results.add(new GitGrepMatch(pl.path, pl.lineNum, pl.text));
                if (results.size() >= limit) break;
            }
            return results;
        }

        // Context case: group consecutive lines into blocks separated by "--"
        // A "block" = all lines until the next "--" separator.
        // Within a block we accumulate lines and track the first match line number.
        String currentPath = null;
        int firstMatchLine = -1;
        var blockBuf = new StringBuilder();

        for (String line : lines) {
            if (line.equals("--")) {
                // Flush current block
                if (currentPath != null && firstMatchLine >= 0) {
                    results.add(new GitGrepMatch(currentPath, firstMatchLine, blockBuf.toString()));
                    if (results.size() >= limit) return results;
                }
                currentPath = null;
                firstMatchLine = -1;
                blockBuf.setLength(0);
                continue;
            }
            if (line.isBlank()) continue;

            ParsedLine pl = parseLine(line);
            if (pl == null) continue;

            // Start new block or continue existing one.
            // git grep -C groups lines from the same file together between "--" separators,
            // so path should be consistent within a block; reset on path change just in case.
            if (!pl.path.equals(currentPath)) {
                if (currentPath != null && firstMatchLine >= 0) {
                    results.add(new GitGrepMatch(currentPath, firstMatchLine, blockBuf.toString()));
                    if (results.size() >= limit) return results;
                }
                currentPath = pl.path;
                firstMatchLine = -1;
                blockBuf.setLength(0);
            }

            // Append formatted line: ":N:text" for match, "-N-text" for context
            char sep = pl.isMatch ? ':' : '-';
            blockBuf.append(sep).append(pl.lineNum).append(sep).append(pl.text).append('\n');

            if (pl.isMatch && firstMatchLine < 0) {
                firstMatchLine = pl.lineNum;
            }
        }

        // Flush last block
        if (currentPath != null && firstMatchLine >= 0 && results.size() < limit) {
            results.add(new GitGrepMatch(currentPath, firstMatchLine, blockBuf.toString()));
        }

        return results;
    }

    /** Parsed representation of one raw git grep output line. */
    private record ParsedLine(String path, int lineNum, String text, boolean isMatch) {}

    /**
     * Parses one raw git grep line.
     *
     * <p>Format: {@code <path><sep><linenum><sep><text>} where sep is {@code ':'} for match lines
     * and {@code '-'} for context lines.
     *
     * <p>Returns {@code null} if the line cannot be parsed.
     */
    @Nullable
    private static ParsedLine parseLine(String line) {
        // Find first separator that matches pattern <sep><digits><sep>
        int sepIdx = findFirstFieldSep(line);
        if (sepIdx < 0) return null;

        char sep = line.charAt(sepIdx);
        boolean isMatch = sep == ':';
        String path = line.substring(0, sepIdx);
        String rest = line.substring(sepIdx + 1); // "linenum<sep>text"

        // rest starts with digits followed by sep
        int numEnd = findLineNumEnd(rest);
        if (numEnd < 0) return null;

        int lineNum;
        try {
            lineNum = Integer.parseInt(rest.substring(0, numEnd));
        } catch (NumberFormatException e) {
            return null;
        }
        String text = rest.substring(numEnd + 1);
        return new ParsedLine(path, lineNum, text, isMatch);
    }

    /**
     * Returns the index of the first {@code ':'} or {@code '-'} in {@code s} that is followed
     * immediately by one or more digits and then another {@code ':'} or {@code '-'} — i.e. the git
     * grep field separator between path and line number.
     */
    private static int findFirstFieldSep(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ':' && c != '-') continue;
            int j = i + 1;
            if (j >= s.length() || !Character.isDigit(s.charAt(j))) continue;
            while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
            if (j < s.length() && (s.charAt(j) == ':' || s.charAt(j) == '-')) return i;
        }
        return -1;
    }

    /**
     * Given {@code rest} = {@code "<digits><sep><text>"}, returns the index of {@code <sep>}.
     * Returns -1 if the string does not start with digits followed by {@code ':'} or {@code '-'}.
     */
    private static int findLineNumEnd(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        if (i > 0 && i < s.length() && (s.charAt(i) == ':' || s.charAt(i) == '-')) return i;
        return -1;
    }

    // ── File content ────────────────────────────────────────────────────────

    /**
     * Returns the content of a <b>tracked</b> file, optimised for AI/LLM consumption.
     *
     * <p>Only files tracked by Git are served. Untracked files (those returned by {@code git
     * ls-files --others}) are explicitly rejected even though they exist on disk: their content is
     * unreviewed working-tree state and serving it would both leak arbitrary local files and feed
     * unverified data to the model. Ignored files (via {@code .gitignore}) are untracked by
     * definition and therefore rejected too.
     *
     * <p>The returned {@link GitFileContent} carries metadata the model can act on without extra
     * calls: detected {@code language}, total {@code lineCount}, a {@code truncated} flag, and the
     * {@code fromLine}/{@code toLine} actually returned.
     *
     * <p><b>Range reading.</b> When {@code fromLine}/{@code toLine} are supplied, only that 1-based
     * inclusive slice is returned — letting the model read one function out of a large file instead
     * of the whole thing. When omitted, the full file is returned, except oversized files (&gt; 512
     * KB) which return a head+tail excerpt with {@code truncated=true}.
     *
     * @param filePath path relative to repo root
     * @param fromLine first line to return (1-based, inclusive); null for start of file
     * @param toLine last line to return (1-based, inclusive); null for end of file
     */
    public GitFileContent getFileContent(
            @NonNull String filePath, @Nullable Integer fromLine, @Nullable Integer toLine) {
        FileBytes fb = readTrackedFile(filePath);
        String language = LanguageDetector.detect(fb.path());

        if (fb.binary()) {
            return new GitFileContent(
                    fb.path(), null, true, fb.size(), language, 0, false, null, null);
        }

        String full = new String(fb.bytes(), StandardCharsets.UTF_8);
        // Split keeping a stable line index; -1 keeps trailing empty lines.
        String[] lines = full.split("\n", -1);
        int total = lines.length;

        boolean rangeRequested = fromLine != null || toLine != null;

        // Oversized file with no explicit range → head+tail excerpt.
        if (!rangeRequested && fb.size() > MAX_FILE_SIZE) {
            String excerpt = headTailExcerpt(lines);
            return new GitFileContent(
                    fb.path(), excerpt, false, fb.size(), language, total, true, null, null);
        }

        if (!rangeRequested) {
            return new GitFileContent(
                    fb.path(), full, false, fb.size(), language, total, false, null, null);
        }

        // Clamp the requested range into [1, total].
        int from = fromLine == null ? 1 : Math.max(1, fromLine);
        int to = toLine == null ? total : Math.min(total, toLine);
        if (from > total || from > to) {
            // Empty/invalid slice — return no content but keep metadata truthful.
            return new GitFileContent(
                    fb.path(),
                    "",
                    false,
                    fb.size(),
                    language,
                    total,
                    true,
                    from,
                    Math.max(from, to));
        }
        String slice = String.join("\n", java.util.Arrays.asList(lines).subList(from - 1, to));
        boolean truncated = from > 1 || to < total;
        return new GitFileContent(
                fb.path(), slice, false, fb.size(), language, total, truncated, from, to);
    }

    /** Convenience overload: full file, no range. */
    public GitFileContent getFileContent(@NonNull String filePath) {
        return getFileContent(filePath, null, null);
    }

    /**
     * Returns a structural outline (classes, methods, functions, ...) of a tracked source file
     * without its full text. Backed by tree-sitter when available, regex otherwise; the {@code
     * parser} field reports which was used.
     *
     * @param filePath path relative to repo root
     * @throws IllegalArgumentException if the file is binary or its language is not supported for
     *     outlining (supported: java, javascript, typescript, python, sql)
     */
    public GitFileOutline getFileOutline(@NonNull String filePath) {
        FileBytes fb = readTrackedFile(filePath);
        String language = LanguageDetector.detect(fb.path());

        if (fb.binary()) {
            throw new IllegalArgumentException("Cannot outline a binary file: " + fb.path());
        }
        if (!outlineService.isLanguageSupported(language)) {
            throw new IllegalArgumentException(
                    "Unsupported language for outline: "
                            + (language == null ? "unknown" : language)
                            + " (supported: java, javascript, typescript, python, sql)");
        }

        String source = new String(fb.bytes(), StandardCharsets.UTF_8);
        int total = source.split("\n", -1).length;
        OutlineResult result = outlineService.outline(language, source);
        return new GitFileOutline(fb.path(), language, total, result.parser(), result.symbols());
    }

    /** Returns the first {@code TRUNCATE_HEAD_LINES} and last {@code TRUNCATE_TAIL_LINES} lines. */
    private static String headTailExcerpt(String[] lines) {
        if (lines.length <= TRUNCATE_HEAD_LINES + TRUNCATE_TAIL_LINES) {
            return String.join("\n", lines);
        }
        var sb = new StringBuilder();
        for (int i = 0; i < TRUNCATE_HEAD_LINES; i++) {
            sb.append(lines[i]).append('\n');
        }
        int omitted = lines.length - TRUNCATE_HEAD_LINES - TRUNCATE_TAIL_LINES;
        sb.append("... (").append(omitted).append(" lines omitted) ...\n");
        for (int i = lines.length - TRUNCATE_TAIL_LINES; i < lines.length; i++) {
            sb.append(lines[i]).append('\n');
        }
        return sb.toString();
    }

    /** Bytes of a validated, tracked file plus whether it sniffed as binary. */
    private record FileBytes(String path, byte[] bytes, long size, boolean binary) {}

    /**
     * Validates that {@code filePath} is a tracked, in-repo file and reads it once. Centralises the
     * security checks shared by {@link #getFileContent} and {@link #getFileOutline}.
     */
    private FileBytes readTrackedFile(String filePath) {
        String normalized = filePath.strip();
        requireSafeGitRelativePath(normalized);

        // Security: confine to the repo before touching the filesystem.
        Path absolute = repoPath.resolve(normalized).normalize();
        if (!absolute.startsWith(repoPath)) {
            throw new IllegalArgumentException("Path traversal not allowed: " + normalized);
        }

        // Only tracked files may be served. `git ls-files -- <path>` prints the path on stdout
        // iff Git tracks it, and prints nothing otherwise (untracked, ignored, or missing) — with
        // no stderr noise, which matters because exec() merges stderr into its returned lines. We
        // then disambiguate untracked-but-present from genuinely-missing so the caller (and the
        // AI) gets a precise, intentional rejection rather than a vague "not found".
        List<String> tracked =
                exec(List.of("git", "ls-files", "--full-name", "-z", "--", normalized));
        boolean isTracked =
                tracked.stream()
                        .flatMap(l -> java.util.Arrays.stream(l.split("\0")))
                        .anyMatch(p -> p.equals(normalized));
        if (!isTracked) {
            if (Files.exists(absolute)) {
                throw new IllegalArgumentException(
                        "Refusing to read untracked file: " + normalized);
            }
            throw new IllegalArgumentException("File not found: " + normalized);
        }

        long size;
        try {
            size = Files.size(absolute);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file size: " + normalized, e);
        }

        byte[] bytes;
        try {
            bytes = Files.readAllBytes(absolute);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file: " + normalized, e);
        }

        return new FileBytes(normalized, bytes, size, isBinary(bytes));
    }

    /**
     * Heuristic binary detection matching Git's own behaviour: a file is treated as binary if a NUL
     * byte appears within the first {@value #BINARY_SNIFF_BYTES} bytes. Cheap and allocation-free,
     * and accurate for the source/text files an AI assistant is asked to read.
     */
    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, BINARY_SNIFF_BYTES);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    // ── Uncommitted changes ────────────────────────────────────────────────

    /**
     * Returns uncommitted changes in the working tree, excluding files matched by {@code
     * .gitignore}.
     *
     * <p>Covers three categories:
     *
     * <ul>
     *   <li><b>Modified/deleted tracked files</b> (both staged and unstaged) — via {@code git diff
     *       HEAD --numstat}
     *   <li><b>New untracked files</b> not ignored — via {@code git ls-files --others
     *       --exclude-standard}
     * </ul>
     *
     * @param includePatch whether to include unified diff text for modified files
     */
    public List<GitDiffEntry> getUncommittedChanges(boolean includePatch) {
        List<GitDiffEntry> entries = new ArrayList<>();

        // 1) Staged + unstaged modifications vs HEAD
        List<String> statLines =
                exec(List.of("git", "diff", "HEAD", "--numstat", "--find-renames"));

        List<String> patchLines =
                includePatch
                        ? exec(List.of("git", "diff", "HEAD", "-p", "--find-renames"))
                        : Collections.emptyList();
        var patches =
                includePatch
                        ? parsePatchBlocks(patchLines)
                        : Collections.<String, String>emptyMap();

        for (String line : statLines) {
            if (line.isBlank()) continue;
            String[] parts = line.split("\t", 3);
            if (parts.length < 3) continue;
            int add = parseStat(parts[0]);
            int del = parseStat(parts[1]);
            String pathPart = parts[2];
            String oldPath = null;
            String filePath;
            if (pathPart.contains(" => ")) {
                String[] rp = parseRenamePath(pathPart);
                oldPath = rp[0];
                filePath = rp[1];
            } else {
                filePath = pathPart;
            }
            if (isJunkFile(filePath)) continue;

            String status;
            if (oldPath != null) {
                status = "R";
            } else {
                // Check if file exists on disk to distinguish delete from modify
                boolean existsOnDisk = Files.exists(repoPath.resolve(filePath));
                status = !existsOnDisk ? "D" : (add > 0 && del == 0 ? "A" : "M");
            }

            String patch = patches.getOrDefault(filePath, patches.get(oldPath));
            if (patch != null && patch.lines().count() > MAX_DIFF_LINES) {
                patch =
                        patch.lines().limit(MAX_DIFF_LINES).collect(Collectors.joining("\n"))
                                + "\n... (truncated)";
            }
            entries.add(new GitDiffEntry(status, filePath, oldPath, add, del, patch));
        }

        // 2) Untracked files (not ignored by .gitignore)
        List<String> untrackedFiles =
                exec(List.of("git", "ls-files", "--others", "--exclude-standard"));

        for (String file : untrackedFiles) {
            if (file.isBlank()) continue;
            // Skip if already covered by diff HEAD (shouldn't happen, but be safe)
            String f = file.strip();
            if (isJunkFile(f)) continue;
            if (entries.stream().anyMatch(e -> f.equals(e.path()))) continue;

            long size = fileSize(f);
            int lineCount = 0;
            if (size > 0 && size <= MAX_FILE_SIZE) {
                try {
                    lineCount =
                            (int) Files.lines(repoPath.resolve(f), StandardCharsets.UTF_8).count();
                } catch (IOException | UncheckedIOException ignored) {
                    // binary or unreadable — leave lineCount as 0
                }
            }
            entries.add(new GitDiffEntry("A", f, null, lineCount, 0, null));
        }

        return entries;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static void requireSafeGitRelativePath(String path) {
        if (path.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
        if (path.startsWith("/") || path.startsWith("-") || path.contains("..") || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
        if (path.contains("\\")) {
            throw new IllegalArgumentException("Invalid path separator: " + path);
        }
        if (!SAFE_GIT_RELATIVE_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Path contains unsupported characters: " + path);
        }
    }

    /** Returns {@code true} for OS/IDE artefacts that should never appear in results. */
    private static boolean isJunkFile(String path) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        if (IGNORED_FILES.contains(name)) return true;
        int dot = name.lastIndexOf('.');
        return dot >= 0 && IGNORED_EXTENSIONS.contains(name.substring(dot).toLowerCase());
    }

    private List<String> exec(List<String> command) {
        try {
            ProcessBuilder pb =
                    new ProcessBuilder(command)
                            .directory(repoPath.toFile())
                            .redirectErrorStream(true);
            Process process = pb.start();
            List<String> lines;
            try (var reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(), StandardCharsets.UTF_8))) {
                lines = reader.lines().toList();
            }
            int exit = process.waitFor();
            if (exit != 0) {
                String output = String.join("\n", lines);
                log.warn("Git command exited {}: {} → {}", exit, command, output);
                // For some commands non-zero is expected (e.g. diff --no-index)
                // so we still return whatever output we got.
            }
            return lines;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git command failed: " + command, e);
        }
    }

    private String normalizeSub(@Nullable String sub) {
        if (sub == null || sub.isBlank()) return "";
        String s = sub.strip().replaceAll("^/+|/+$", "");
        if (s.contains("..")) throw new IllegalArgumentException("Path traversal not allowed");
        return s;
    }

    private long fileSize(String relativePath) {
        try {
            return Files.size(repoPath.resolve(relativePath));
        } catch (IOException e) {
            return -1;
        }
    }

    private static OffsetDateTime parseDate(String iso) {
        try {
            return OffsetDateTime.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseStat(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Parses git rename path formats: "a => b", "{a => b}/rest", "prefix/{a => b}/suffix" */
    private static String[] parseRenamePath(String raw) {
        Pattern p = Pattern.compile("^(.*?)\\{(.+?) => (.+?)}(.*)$");
        Matcher m = p.matcher(raw);
        if (m.matches()) {
            String prefix = m.group(1);
            String suffix = m.group(4);
            return new String[] {prefix + m.group(2) + suffix, prefix + m.group(3) + suffix};
        }
        // Simple "old => new"
        String[] parts = raw.split(" => ", 2);
        return parts.length == 2 ? parts : new String[] {raw, raw};
    }

    /**
     * Splits unified diff output into per-file patch blocks. Key = new file path from "b/..."
     * header.
     */
    private static java.util.Map<String, String> parsePatchBlocks(List<String> lines) {
        var map = new java.util.LinkedHashMap<String, String>();
        String currentFile = null;
        var buf = new StringBuilder();
        for (String line : lines) {
            if (line.startsWith("diff --git")) {
                if (currentFile != null) {
                    map.put(currentFile, buf.toString());
                }
                buf.setLength(0);
                // Extract b/path from "diff --git a/old b/new"
                int bIdx = line.lastIndexOf(" b/");
                currentFile = bIdx >= 0 ? line.substring(bIdx + 3) : null;
            }
            buf.append(line).append('\n');
        }
        if (currentFile != null) {
            map.put(currentFile, buf.toString());
        }
        return map;
    }
}
