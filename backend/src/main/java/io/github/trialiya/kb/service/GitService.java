package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.model.git.dto.GitFileOutline;
import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import io.github.trialiya.kb.model.git.dto.OutlineResult;
import io.github.trialiya.kb.service.outline.LanguageDetector;
import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.errors.RevisionSyntaxException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service for Git repository operations: read-only browsing/search plus opt-in working-tree writes
 * ({@link #createFile}/{@link #editFile}, exposed to the model only when {@code
 * kb.git.edit-enabled=true} and the tree is writable — see {@code GitEditFunction}).
 *
 * <p>All operations run against the repository at {@code kb.project-path} via JGit, in-process — no
 * {@code git} subprocess, no argv, no output parsing — except {@link #grepContent}, which still
 * shells out to {@code git grep} (JGit has no equivalent). Files matched by {@code .gitignore} are
 * excluded from tree/search/status results the same way native git excludes them.
 */
@Slf4j
@Service
public class GitService {

    private static final Pattern SAFE_GIT_RELATIVE_PATH =
            Pattern.compile("^[\\p{L}\\p{N}._/\\- ]+$");

    private static final long MAX_FILE_SIZE = 512 * 1024; // 512 KB — skip huge files
    private static final int MAX_DIFF_LINES = 500; // truncate very large diffs

    /** Bytes inspected when sniffing for binary content (a NUL byte ⇒ binary). */
    private static final int BINARY_SNIFF_BYTES = 8192;

    /** When a file exceeds MAX_FILE_SIZE, return this many lines from the head and tail. */
    private static final int TRUNCATE_HEAD_LINES = 200;

    private static final int TRUNCATE_TAIL_LINES = 50;

    /**
     * Minimum length for abbreviated commit hashes, matching native git's own default (grows
     * automatically if ambiguous — see {@link
     * ObjectReader#abbreviate(org.eclipse.jgit.lib.AnyObjectId, int)}).
     */
    private static final int ABBREV_LEN = 7;

    /** File names to always exclude from uncommitted changes (OS/IDE junk). */
    private static final Set<String> IGNORED_FILES =
            Set.of(".DS_Store", "Thumbs.db", "desktop.ini", ".directory");

    /** File extensions to always exclude from uncommitted changes. */
    private static final Set<String> IGNORED_EXTENSIONS =
            Set.of(
                    ".class", ".jar", ".war", ".ear", ".o", ".so", ".dylib", ".dll", ".exe", ".pyc",
                    ".pyo", ".swp", ".swo", ".bak", ".tmp", ".orig");

    private final Path repoPath;
    private final Repository repository;
    private final Git git;
    private final OutlineService outlineService;

    public GitService(
            @Value("${kb.project-path}") String projectPath, OutlineService outlineService) {
        this.repoPath = Path.of(projectPath).toAbsolutePath().normalize();
        this.outlineService = outlineService;
        try {
            this.repository = new FileRepositoryBuilder().setWorkTree(repoPath.toFile()).build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open Git repository at " + repoPath, e);
        }
        // FileRepositoryBuilder.build() never touches disk to verify a .git dir exists — without
        // this check a bad kb.project-path would silently produce empty results from every
        // tool (readDirCache() on a missing index just returns 0 entries) instead of failing
        // Spring bean creation with an actionable error.
        if (!repository.getDirectory().isDirectory()) {
            throw new IllegalStateException("Not a Git repository (no .git found): " + repoPath);
        }
        this.git = new Git(repository);
        log.info("GitService initialised for repo: {}", repoPath);
    }

    @PreDestroy
    void closeRepository() {
        repository.close();
    }

    // ── File tree ────────────────────────────────────────────────────────────

    /**
     * Returns tracked files/directories under {@code subPath} (or repo root if null), directories
     * first then alphabetically (case-insensitive). Reads the Git index directly, so untracked and
     * ignored files are automatically excluded.
     */
    public List<GitFileNode> getFileTree(@Nullable String subPath) {
        String base = normalizeSub(subPath);
        List<String> lines =
                trackedPaths().stream()
                        .filter(p -> base.isEmpty() || p.equals(base) || p.startsWith(base + "/"))
                        .toList();

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
        return nodes.values().stream()
                .sorted(
                        Comparator.<GitFileNode, Boolean>comparing(
                                        n -> !"directory".equals(n.type()))
                                .thenComparing(GitFileNode::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
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
        try (ObjectReader reader = repository.newObjectReader()) {
            var logCommand = git.log().setMaxCount(limit);
            if (filePath != null && !filePath.isBlank()) {
                logCommand.addPath(toForwardSlashes(filePath.strip()));
            }
            List<GitCommit> commits = new ArrayList<>();
            for (RevCommit commit : logCommand.call()) {
                commits.add(toGitCommit(commit, null, reader));
            }
            return commits;
        } catch (NoHeadException e) {
            // Repository has no commits yet — an empty history, not an error.
            return List.of();
        } catch (GitAPIException | IOException e) {
            throw new IllegalStateException("Failed to read commit log", e);
        }
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
        String spec =
                (filePath == null || filePath.isBlank())
                        ? null
                        : toForwardSlashes(filePath.strip());
        List<GitCommit> result = new ArrayList<>();
        for (String hash : commitHashes.split(",")) {
            String h = hash.strip();
            if (h.isEmpty()) continue;
            result.add(diffForSingleCommit(h, includePatch, spec));
        }
        return result;
    }

    private GitCommit diffForSingleCommit(String hash, boolean includePatch, String filePath) {
        try (RevWalk revWalk = new RevWalk(repository);
                ObjectReader reader = repository.newObjectReader()) {
            RevCommit commit = revWalk.parseCommit(resolveCommitId(hash));
            RevCommit parent =
                    commit.getParentCount() > 0 ? revWalk.parseCommit(commit.getParent(0)) : null;

            // No parent (root commit) → diff against the empty tree, equivalent to `git diff-tree
            // --root`. Native git's diff-tree needs that flag explicitly and getCommitDiff never
            // passed it, so the very first commit of a repo used to come back with an empty files
            // list — fixed here, since it's the natural (and simpler) way to express it in JGit.
            AbstractTreeIterator oldTree =
                    parent == null ? new EmptyTreeIterator() : treeIterator(reader, parent);
            AbstractTreeIterator newTree = treeIterator(reader, commit);

            List<GitDiffEntry> entries = new ArrayList<>();
            var patchOut = new ByteArrayOutputStream();
            try (DiffFormatter formatter = new DiffFormatter(patchOut)) {
                formatter.setRepository(repository);
                formatter.setDetectRenames(true);
                if (filePath != null) {
                    formatter.setPathFilter(PathFilterGroup.createFromStrings(List.of(filePath)));
                }
                for (DiffEntry entry : formatter.scan(oldTree, newTree)) {
                    entries.add(toGitDiffEntry(entry, formatter, includePatch, patchOut));
                }
            }
            return toGitCommit(commit, entries, reader);
        } catch (MissingObjectException | IncorrectObjectTypeException e) {
            throw new IllegalArgumentException("Commit not found: " + hash, e);
        } catch (AmbiguousObjectException e) {
            throw new IllegalArgumentException("Ambiguous commit reference: " + hash, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading commit: " + hash, e);
        }
    }

    private ObjectId resolveCommitId(String hash) throws IOException {
        ObjectId id;
        try {
            id = repository.resolve(hash);
        } catch (RevisionSyntaxException e) {
            throw new IllegalArgumentException("Invalid commit reference: " + hash, e);
        }
        if (id == null) {
            throw new IllegalArgumentException("Commit not found: " + hash);
        }
        return id;
    }

    private static AbstractTreeIterator treeIterator(ObjectReader reader, RevCommit commit)
            throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, commit.getTree());
        return parser;
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

        List<String> allFiles = trackedPaths();

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
     * skipped automatically by git grep. JGit has no equivalent of {@code git grep}, so this is the
     * one operation in this class that still shells out to the {@code git} binary.
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
            args.add(toForwardSlashes(pathGlob.strip()));
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
     * <p>Only files tracked by Git are served (checked against the index). Untracked files are
     * explicitly rejected even though they exist on disk: their content is unreviewed working-tree
     * state and serving it would both leak arbitrary local files and feed unverified data to the
     * model. Ignored files (via {@code .gitignore}) are untracked by definition and therefore
     * rejected too. The rejection message is identical whether the path is untracked-but-present or
     * genuinely absent — see {@link #readTrackedFile} — so this check cannot be used to probe for
     * the existence of arbitrary files (e.g. {@code .env}) via disk presence alone.
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

        // Normalize CRLF → LF so Windows working-tree files don't leave \r at the end of each line.
        String full = new String(fb.bytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
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

        // Normalize CRLF → LF so Windows working-tree files don't leave \r at the end of each line.
        String source = new String(fb.bytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
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
        String normalized = toForwardSlashes(filePath.strip());
        requireSafeGitRelativePath(normalized);

        // Security: confine to the repo before touching the filesystem.
        Path absolute = repoPath.resolve(normalized).normalize();
        if (!absolute.startsWith(repoPath)) {
            throw new IllegalArgumentException("Path traversal not allowed: " + normalized);
        }

        // Only tracked files may be served. The message is the same whether the path is
        // untracked-but-present or genuinely missing, so a caller can't use it to fingerprint
        // which unrelated files (e.g. a gitignored .env) happen to exist on disk.
        if (!isTracked(normalized)) {
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

    // ── Working-tree writes (createFile / editFile) ─────────────────────────

    /**
     * Whether the working tree can be written at all. Used at startup to decide if the edit tools
     * ({@code GitEditFunction}) may be exposed to the model — a read-only mount (e.g. a ro Docker
     * volume) must simply not offer them.
     */
    public boolean isRepoWritable() {
        return Files.isWritable(repoPath);
    }

    /**
     * Creates a new file in the working tree and stages it ({@code git add}), so it immediately
     * becomes <em>tracked</em> and visible to every read tool of this service (which serve tracked
     * files only).
     *
     * <p>Refused when: the path already exists on disk, the path is matched by {@code .gitignore}
     * (staging would silently skip it, leaving an unreadable orphan — the file is removed again and
     * the call fails), the name is an OS/IDE junk artefact, or the content exceeds {@value
     * #MAX_FILE_SIZE} bytes.
     */
    public GitEditResult createFile(@NonNull String filePath, @NonNull String content) {
        String normalized = validateWritablePath(filePath);
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "Content too large (max " + MAX_FILE_SIZE / 1024 + " KB): " + normalized);
        }

        // Only presence on disk blocks creation. A tracked-but-deleted file (removed from the
        // working tree, still in the index) is deliberately allowed — editFile can't read it, so
        // createFile is the only way to restore it; the staging below refreshes the index entry.
        Path absolute = repoPath.resolve(normalized).normalize();
        if (Files.exists(absolute)) {
            throw new IllegalArgumentException(
                    "File already exists: " + normalized + ". Use editFile to modify it.");
        }

        try {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(absolute, content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create file: " + normalized, e);
        }

        // Stage the new file so it becomes tracked. JGit's AddCommand honours .gitignore: an
        // ignored path is silently NOT added — detect that, roll the write back and fail loudly
        // instead of leaving an untracked file no read tool can see.
        try {
            git.add().addFilepattern(normalized).call();
        } catch (GitAPIException e) {
            deleteQuietly(absolute);
            throw new IllegalStateException("Failed to stage created file: " + normalized, e);
        }
        if (!isTracked(normalized)) {
            deleteQuietly(absolute);
            throw new IllegalArgumentException(
                    "Path is ignored by .gitignore and cannot be created: " + normalized);
        }

        int lines = content.isEmpty() ? 0 : content.split("\n", -1).length;
        log.info("createFile: '{}' created and staged ({} lines)", normalized, lines);
        return new GitEditResult("create", normalized, lines, 0, lines, null);
    }

    /**
     * Replaces an exact occurrence of {@code oldString} with {@code newString} in a tracked text
     * file and stages the result (nothing is committed).
     *
     * <p>The match is exact and unique by default: zero occurrences or more than one (without
     * {@code replaceAll}) fail with a model-readable error, so the model must quote real, current
     * file content — this doubles as an optimistic concurrency check. Content is matched against
     * the LF-normalised text (the same view {@code getFileContent} returns); original CRLF line
     * endings are preserved on write. Binary files and files over {@value #MAX_FILE_SIZE} bytes are
     * refused.
     *
     * @return counters plus a unified diff of exactly this edit (truncated to {@value
     *     #MAX_DIFF_LINES} lines)
     */
    public GitEditResult editFile(
            @NonNull String filePath,
            @NonNull String oldString,
            @NonNull String newString,
            boolean replaceAll) {
        if (oldString.isEmpty()) {
            throw new IllegalArgumentException("oldString must not be empty");
        }
        if (oldString.equals(newString)) {
            throw new IllegalArgumentException("oldString and newString are identical");
        }

        FileBytes fb = readTrackedFile(filePath);
        if (fb.binary()) {
            throw new IllegalArgumentException("Cannot edit a binary file: " + fb.path());
        }
        if (fb.size() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "File too large to edit (max " + MAX_FILE_SIZE / 1024 + " KB): " + fb.path());
        }

        String original = new String(fb.bytes(), StandardCharsets.UTF_8);
        boolean crlf = original.contains("\r\n");
        String text = crlf ? original.replace("\r\n", "\n") : original;
        String oldLf = oldString.replace("\r\n", "\n");
        String newLf = newString.replace("\r\n", "\n");

        int occurrences = countOccurrences(text, oldLf);
        if (occurrences == 0) {
            throw new IllegalArgumentException(
                    "oldString not found in "
                            + fb.path()
                            + ". Re-read the current content (getFileContent) and pass an exact,"
                            + " character-for-character fragment including whitespace.");
        }
        if (occurrences > 1 && !replaceAll) {
            throw new IllegalArgumentException(
                    "oldString occurs "
                            + occurrences
                            + " times in "
                            + fb.path()
                            + ". Extend it with surrounding lines to make it unique, or pass"
                            + " replaceAll=true to replace every occurrence.");
        }

        String updated = text.replace(oldLf, newLf);
        DiffStats stats = diffStrings(text, updated);
        writeAtomically(fb.path(), crlf ? updated.replace("\n", "\r\n") : updated);

        // Stage the edit. Not just cosmetics: a same-size edit written within the same clock tick
        // is "racily clean" and JGit's status (unlike native git) can miss it entirely — the index
        // update makes the change deterministically visible to getUncommittedChanges. It also
        // matches createFile: everything the model changed is staged, ready for user review.
        try {
            git.add().addFilepattern(fb.path()).call();
        } catch (GitAPIException e) {
            throw new IllegalStateException("Failed to stage edited file: " + fb.path(), e);
        }

        int lines = updated.isEmpty() ? 0 : updated.split("\n", -1).length;
        log.info(
                "editFile: '{}' — {} occurrence(s) replaced (+{}/-{})",
                fb.path(),
                occurrences,
                stats.additions(),
                stats.deletions());
        return new GitEditResult(
                "edit", fb.path(), stats.additions(), stats.deletions(), lines, stats.diff());
    }

    private record DiffStats(int additions, int deletions, String diff) {}

    /** Unified diff + added/removed line counts between two in-memory revisions of one file. */
    private static DiffStats diffStrings(String before, String after) {
        RawText a = new RawText(before.getBytes(StandardCharsets.UTF_8));
        RawText b = new RawText(after.getBytes(StandardCharsets.UTF_8));
        EditList edits =
                DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                        .diff(RawTextComparator.DEFAULT, a, b);
        int add = 0;
        int del = 0;
        for (Edit edit : edits) {
            add += edit.getEndB() - edit.getBeginB();
            del += edit.getEndA() - edit.getBeginA();
        }
        var out = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.format(edits, a, b);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to format diff", e);
        }
        String diff = out.toString(StandardCharsets.UTF_8);
        if (diff.lines().count() > MAX_DIFF_LINES) {
            diff =
                    diff.lines().limit(MAX_DIFF_LINES).collect(Collectors.joining("\n"))
                            + "\n... (truncated)";
        }
        return new DiffStats(add, del, diff);
    }

    /** Writes via a temp file + atomic move so a crash never leaves a half-written file. */
    private void writeAtomically(String relativePath, String content) {
        Path target = repoPath.resolve(relativePath).normalize();
        Path tmp = null;
        try {
            tmp = Files.createTempFile(target.getParent(), ".kb-edit-", ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            // The move replaces the target's inode, so without this the edited file would end up
            // with the temp file's default mode (0600) — silently dropping e.g. the executable
            // bit of a script. Copy the original permissions onto the temp file before the swap.
            try {
                Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(target));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem (e.g. Windows) — permissions are not inode-bound there.
            }
            try {
                Files.move(
                        tmp,
                        target,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (tmp != null) {
                deleteQuietly(tmp);
            }
            throw new IllegalStateException("Cannot write file: " + relativePath, e);
        }
    }

    /**
     * Path validation shared by write operations: same character/traversal rules as reads, plus
     * {@code .git/} internals and junk artefacts are never writable.
     */
    private String validateWritablePath(String filePath) {
        String normalized = toForwardSlashes(filePath.strip());
        requireSafeGitRelativePath(normalized);
        Path absolute = repoPath.resolve(normalized).normalize();
        if (!absolute.startsWith(repoPath)) {
            throw new IllegalArgumentException("Path traversal not allowed: " + normalized);
        }
        if (normalized.equals(".git") || normalized.startsWith(".git/")) {
            throw new IllegalArgumentException("Writing into .git is not allowed");
        }
        if (isJunkFile(normalized)) {
            throw new IllegalArgumentException("Refusing to create junk file: " + normalized);
        }
        return normalized;
    }

    private static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Failed to clean up {}", path, e);
        }
    }

    // ── Uncommitted changes ────────────────────────────────────────────────

    /**
     * Returns uncommitted changes in the working tree, excluding files matched by {@code
     * .gitignore}.
     *
     * <p>Covers three categories:
     *
     * <ul>
     *   <li><b>Modified/deleted tracked files</b> (both staged and unstaged) — diffed directly
     *       against HEAD, mirroring {@code git diff HEAD}
     *   <li><b>New untracked files</b> not ignored — from {@code Status.getUntracked()}, mirroring
     *       {@code git ls-files --others --exclude-standard}
     * </ul>
     *
     * @param includePatch whether to include unified diff text for modified files
     */
    public List<GitDiffEntry> getUncommittedChanges(boolean includePatch) {
        Status status;
        try {
            status = git.status().call();
        } catch (GitAPIException e) {
            throw new IllegalStateException("Failed to compute working tree status", e);
        }

        Set<String> changedPaths = new LinkedHashSet<>();
        changedPaths.addAll(status.getAdded());
        changedPaths.addAll(status.getChanged());
        changedPaths.addAll(status.getModified());
        changedPaths.addAll(status.getRemoved());
        changedPaths.addAll(status.getMissing());
        // Unresolved merge conflicts live in none of the sets above, yet `git diff HEAD` shows
        // them (worktree content with conflict markers vs HEAD) — without this they'd vanish.
        changedPaths.addAll(status.getConflicting());

        List<GitDiffEntry> entries = new ArrayList<>();

        if (!changedPaths.isEmpty()) {
            try (ObjectReader reader = repository.newObjectReader()) {
                AbstractTreeIterator oldTree = headTreeIterator(reader);
                FileTreeIterator newTree = new FileTreeIterator(repository);

                var patchOut = new ByteArrayOutputStream();
                try (DiffFormatter formatter = new DiffFormatter(patchOut)) {
                    formatter.setRepository(repository);
                    formatter.setDetectRenames(true);
                    formatter.setPathFilter(PathFilterGroup.createFromStrings(changedPaths));

                    for (DiffEntry entry : formatter.scan(oldTree, newTree)) {
                        GitDiffEntry mapped =
                                toGitDiffEntry(entry, formatter, includePatch, patchOut);
                        if (isJunkFile(mapped.path())) continue;
                        entries.add(mapped);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to diff working tree against HEAD", e);
            }
        }

        // Untracked files (not ignored by .gitignore) — patch is never populated for these, only
        // a line count, same as before.
        for (String path : status.getUntracked()) {
            if (isJunkFile(path)) continue;

            long size = fileSize(path);
            int lineCount = 0;
            if (size > 0 && size <= MAX_FILE_SIZE) {
                try {
                    lineCount =
                            (int)
                                    Files.lines(repoPath.resolve(path), StandardCharsets.UTF_8)
                                            .count();
                } catch (IOException | UncheckedIOException ignored) {
                    // binary or unreadable — leave lineCount as 0
                }
            }
            entries.add(new GitDiffEntry("A", path, null, lineCount, 0, null));
        }

        return entries;
    }

    /** HEAD's tree, or an empty tree when the branch is unborn (no commits yet). */
    private AbstractTreeIterator headTreeIterator(ObjectReader reader) throws IOException {
        ObjectId headId = repository.resolve("HEAD");
        if (headId == null) {
            return new EmptyTreeIterator();
        }
        try (RevWalk revWalk = new RevWalk(repository)) {
            RevCommit head = revWalk.parseCommit(headId);
            return treeIterator(reader, head);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * All paths currently in the Git index, matching {@code git ls-files}'s default behaviour.
     * Files with an unresolved merge conflict have only stage-1..3 entries (no stage 0); they are
     * still tracked, so their stages collapse to a single de-duplicated path here (index entries
     * are sorted by path, so duplicates are adjacent and the set stays in ls-files order).
     */
    private List<String> trackedPaths() {
        DirCache cache;
        try {
            cache = repository.readDirCache();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Git index: " + repoPath, e);
        }
        Set<String> paths = LinkedHashSet.newLinkedHashSet(cache.getEntryCount());
        for (int i = 0; i < cache.getEntryCount(); i++) {
            paths.add(cache.getEntry(i).getPathString());
        }
        return List.copyOf(paths);
    }

    private boolean isTracked(String path) {
        try {
            // getEntry(path) returns the path's first index entry — stage 0 normally, stage 1+
            // while a merge conflict is unresolved. Either way the file is tracked.
            return repository.readDirCache().getEntry(path) != null;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Git index: " + repoPath, e);
        }
    }

    private static GitCommit toGitCommit(
            RevCommit commit, List<GitDiffEntry> files, ObjectReader reader) throws IOException {
        PersonIdent author = commit.getAuthorIdent();
        OffsetDateTime date =
                author.getWhenAsInstant().atZone(author.getZoneId()).toOffsetDateTime();
        return new GitCommit(
                commit.getName(),
                reader.abbreviate(commit, ABBREV_LEN).name(),
                author.getName(),
                author.getEmailAddress(),
                date,
                commit.getShortMessage(),
                files);
    }

    /**
     * Maps one JGit {@link DiffEntry} to the API's {@link GitDiffEntry}, using the change type JGit
     * already computed (add/modify/delete/rename/copy) rather than inferring it from add/delete
     * line counts — the previous numstat-based heuristic (add&gt;0 &amp;&amp; del==0 ⇒ "A")
     * misclassified an append-only edit to an *existing* file as "added".
     */
    private GitDiffEntry toGitDiffEntry(
            DiffEntry entry,
            DiffFormatter formatter,
            boolean includePatch,
            ByteArrayOutputStream patchOut)
            throws IOException {
        String oldPath = normalizedDiffPath(entry.getOldPath());
        String newPath = normalizedDiffPath(entry.getNewPath());

        String status =
                switch (entry.getChangeType()) {
                    case ADD -> "A";
                    case DELETE -> "D";
                    case RENAME -> "R";
                    case COPY -> "C";
                    default -> "M";
                };
        String path = "D".equals(status) ? oldPath : newPath;
        // Renames AND copies both carry a meaningful source path; everything else has none.
        String reportedOldPath = "R".equals(status) || "C".equals(status) ? oldPath : null;

        int add = 0;
        int del = 0;
        FileHeader header = formatter.toFileHeader(entry);
        if (header.getPatchType() == FileHeader.PatchType.UNIFIED) {
            for (Edit edit : header.toEditList()) {
                add += edit.getEndB() - edit.getBeginB();
                del += edit.getEndA() - edit.getBeginA();
            }
        }

        String patch = null;
        if (includePatch) {
            patchOut.reset();
            formatter.format(entry);
            patch = patchOut.toString(StandardCharsets.UTF_8);
            if (patch.lines().count() > MAX_DIFF_LINES) {
                patch =
                        patch.lines().limit(MAX_DIFF_LINES).collect(Collectors.joining("\n"))
                                + "\n... (truncated)";
            }
        }
        return new GitDiffEntry(status, path, reportedOldPath, add, del, patch);
    }

    private static String normalizedDiffPath(String path) {
        return DiffEntry.DEV_NULL.equals(path) ? null : path;
    }

    private static void requireSafeGitRelativePath(String path) {
        if (path.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
        if (path.startsWith("/")
                || path.startsWith("-")
                || path.contains("..")
                || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid path: " + path);
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

    /** Runs {@code git grep} as a subprocess — the one operation JGit cannot do in-process. */
    private List<String> exec(List<String> command) {
        try {
            // core.quotepath=false: without it, git quotes/octal-escapes any path containing
            // non-ASCII bytes (e.g. Cyrillic filenames) in grep output — "docs/проект" becomes
            // "\"docs/\\320\\277...\"", which breaks path parsing.
            List<String> withConfig = new ArrayList<>(command.size() + 2);
            withConfig.add(command.get(0));
            withConfig.add("-c");
            withConfig.add("core.quotepath=false");
            withConfig.addAll(command.subList(1, command.size()));

            ProcessBuilder pb =
                    new ProcessBuilder(withConfig)
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
                // git grep exits 1 when there are simply no matches — not an error, so we still
                // return whatever output we got (empty in that case).
            }
            return lines;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git command failed: " + command, e);
        }
    }

    private String normalizeSub(@Nullable String sub) {
        if (sub == null || sub.isBlank()) return "";
        String s = toForwardSlashes(sub.strip()).replaceAll("^/+|/+$", "");
        if (s.contains("..")) throw new IllegalArgumentException("Path traversal not allowed");
        return s;
    }

    private static String toForwardSlashes(String path) {
        return path.replace('\\', '/');
    }

    private long fileSize(String relativePath) {
        try {
            return Files.size(repoPath.resolve(relativePath));
        } catch (IOException e) {
            return -1;
        }
    }
}
