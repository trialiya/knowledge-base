package io.github.trialiya.kb.service;

import io.github.trialiya.kb.model.git.dto.FileEntryType;
import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.model.git.dto.GitFileBytes;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileInfo;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.model.git.dto.GitFileOutline;
import io.github.trialiya.kb.model.git.dto.GitGrepMatch;
import io.github.trialiya.kb.model.git.dto.GitPathView;
import io.github.trialiya.kb.model.git.dto.GitTreeLevel;
import io.github.trialiya.kb.model.git.dto.OutlineResult;
import io.github.trialiya.kb.model.project.Project;
import io.github.trialiya.kb.service.outline.LanguageDetector;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
import org.eclipse.jgit.lib.Constants;
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
import org.springframework.util.AntPathMatcher;

/**
 * Service for Git repository operations on <b>one</b> project: read-only browsing/search plus
 * opt-in working-tree writes ({@link #createFile}/{@link #editFile}, exposed to the model only when
 * the project allows edits and the tree is writable — see {@code GitRegistry}, {@code
 * GitEditFunction}).
 *
 * <p>One instance per configured project, owned by {@code GitRegistry}: callers ask it for the
 * project they mean rather than injecting this service directly, which is what keeps two projects
 * from ever sharing a repository handle.
 *
 * <p>All operations run against this project's repository via JGit, in-process — no {@code git}
 * subprocess, no argv, no output parsing — except {@link #grepContent}, which still shells out to
 * {@code git grep} (JGit has no equivalent). Files matched by {@code .gitignore} are excluded from
 * tree/search/status results the same way native git excludes them.
 */
@Slf4j
public class GitService {

    private static final Pattern SAFE_GIT_RELATIVE_PATH =
            Pattern.compile("^[\\p{L}\\p{N}._/\\- ]+$");

    /**
     * Ant semantics for {@code Project#allowGlobs}, not {@code java.nio} glob: {@code notes/**} has
     * to match {@code notes/todo.md} and {@code notes/a/b.md} alike, and Ant is also what {@code
     * pathGlob} looks like elsewhere in the tool surface.
     */
    private static final AntPathMatcher GLOB_MATCHER = new AntPathMatcher();

    private static final long MAX_FILE_SIZE = 512 * 1024; // 512 KB — skip huge files
    private static final int MAX_DIFF_LINES = 500; // truncate very large diffs

    /** Bytes inspected when sniffing for binary content (a NUL byte ⇒ binary). */
    private static final int BINARY_SNIFF_BYTES = 8192;

    /**
     * Largest window {@link #getFileBytes} will hand back in one call. Not a limit on the file:
     * bytes are read positionally, so anything bigger is read window by window — which is also the
     * only shape in which a caller can process a file larger than it can hold.
     */
    private static final long MAX_BYTE_WINDOW = 1024 * 1024;

    /**
     * Chunk size for reads that only pass bytes through, never keeping them ({@link #hashFile}).
     */
    private static final int STREAM_BUFFER_BYTES = 8192;

    /** When a file exceeds MAX_FILE_SIZE, return this many lines from the head and tail. */
    private static final int TRUNCATE_HEAD_LINES = 200;

    private static final int TRUNCATE_TAIL_LINES = 50;

    /**
     * Minimum length for abbreviated commit hashes, matching native git's own default (grows
     * automatically if ambiguous — see {@link
     * ObjectReader#abbreviate(org.eclipse.jgit.lib.AnyObjectId, int)}).
     */
    private static final int ABBREV_LEN = 7;

    /** How far back {@link #searchCommits} walks before giving up on finding more matches. */
    private static final int COMMIT_SEARCH_SCAN = 2000;

    /** Tree listing order: directories first, then by name, case-insensitively. */
    private static final Comparator<GitFileNode> NODE_ORDER =
            Comparator.<GitFileNode, Boolean>comparing(n -> FileEntryType.DIRECTORY != n.type())
                    .thenComparing(GitFileNode::name, String.CASE_INSENSITIVE_ORDER);

    /** File names to always exclude from uncommitted changes (OS/IDE junk). */
    private static final Set<String> IGNORED_FILES =
            Set.of(".DS_Store", "Thumbs.db", "desktop.ini", ".directory");

    /** File extensions to always exclude from uncommitted changes. */
    private static final Set<String> IGNORED_EXTENSIONS =
            Set.of(
                    ".class", ".jar", ".war", ".ear", ".o", ".so", ".dylib", ".dll", ".exe", ".pyc",
                    ".pyo", ".swp", ".swo", ".bak", ".tmp", ".orig");

    private final Project project;

    private final Path repoPath;

    /**
     * {@link #repoPath} with every symlink in it resolved. Comparison base for {@link
     * #requireInsideRepo}: the repository itself may legitimately live behind a symlinked parent (a
     * {@code /tmp} → {@code /private/tmp} style mount), in which case real paths of its own files
     * would never start with the textual {@link #repoPath}.
     */
    private final Path repoRealPath;

    private final Repository repository;
    private final Git git;
    private final OutlineService outlineService;

    /**
     * The directories {@code allow-globs} match under, so the working-tree walk behind {@link
     * #visiblePaths} covers those instead of the whole repository. Empty only when the project
     * configures no globs at all — {@link #requireRootedGlobs} rejects a glob without a root.
     */
    private final List<String> allowGlobRoots;

    public GitService(Project project, OutlineService outlineService) {
        requireRootedGlobs(project);
        this.project = project;
        this.allowGlobRoots = globRoots(project.allowGlobs());
        this.repoPath = project.path();
        try {
            this.repoRealPath = repoPath.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve repository path: " + repoPath, e);
        }
        this.outlineService = outlineService;
        try {
            this.repository = new FileRepositoryBuilder().setWorkTree(repoPath.toFile()).build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open Git repository at " + repoPath, e);
        }
        // FileRepositoryBuilder.build() never touches disk to verify a .git dir exists — without
        // this check a bad configured path would silently produce empty results from every tool
        // (readDirCache() on a missing index just returns 0 entries) instead of failing the
        // registry's construction with an actionable error.
        if (!repository.getDirectory().isDirectory()) {
            throw new IllegalStateException(
                    "Not a Git repository (no .git found) for project \""
                            + project.id()
                            + "\": "
                            + repoPath);
        }
        this.git = new Git(repository);
        log.info("GitService initialised for project {}: {}", project.id(), repoPath);
    }

    /**
     * Closed by {@code GitRegistry}, which owns the instances — this service is no longer a bean of
     * its own, so its lifecycle is the registry's.
     */
    void closeRepository() {
        repository.close();
    }

    /** The repository this instance serves. */
    public Project project() {
        return project;
    }

    // ── File tree ────────────────────────────────────────────────────────────

    /**
     * Returns visible files/directories under {@code subPath} (or repo root if null), directories
     * first then alphabetically (case-insensitive): the tracked files from the Git index, plus —
     * when the project configures {@code allow-globs} — the untracked files those globs admit (see
     * {@link #visiblePaths()}).
     */
    public List<GitFileNode> getFileTree(@Nullable String subPath) {
        String base = normalizeSub(subPath);
        return listDirectories(visiblePaths(), Set.of(base)).getOrDefault(base, List.of());
    }

    /**
     * Lists several directories in a single pass over the index.
     *
     * <p>Each tracked path is walked segment by segment; whenever a prefix of it is one of the
     * requested {@code bases}, the child at that level (a subdirectory or the file itself) is added
     * to that base's listing. So the whole ancestor chain of a deeply nested file costs one index
     * read and one scan, instead of one full scan per level as repeated {@link
     * #getFileTree(String)} calls would.
     *
     * <p>A node is reported as untracked when nothing tracked passes through it: the file itself is
     * only admitted by {@code allow-globs}, or the directory holds no tracked file at all. That is
     * what the file browser greys out, and what tells the model the path carries no history.
     *
     * @param visible paths to build the listings from, as returned by {@link #visiblePaths()}
     * @param bases directory paths to list ("" — repo root); paths that are not directories simply
     *     come back with an empty listing
     */
    private Map<String, List<GitFileNode>> listDirectories(
            List<String> visible, Set<String> bases) {
        Set<String> tracked = new LinkedHashSet<>(trackedPaths());
        // Directory nodes de-duplicate by path (many files share one subdirectory), hence the
        // LinkedHashMap per base rather than a plain list.
        Map<String, LinkedHashMap<String, GitFileNode>> acc = new LinkedHashMap<>();
        for (String base : bases) {
            acc.put(base, new LinkedHashMap<>());
        }

        for (String path : visible) {
            boolean isTracked = tracked.contains(path);
            int from = 0;
            while (true) {
                int slash = path.indexOf('/', from);
                String dir = from == 0 ? "" : path.substring(0, from - 1);
                LinkedHashMap<String, GitFileNode> bucket = acc.get(dir);
                if (bucket != null) {
                    if (slash >= 0) {
                        String name = path.substring(from, slash);
                        String dirPath = dir.isEmpty() ? name : dir + "/" + name;
                        GitFileNode node =
                                new GitFileNode(
                                        dirPath, name, FileEntryType.DIRECTORY, null, isTracked);
                        // A directory counts as tracked as soon as one tracked file runs through
                        // it, whichever order the paths arrive in.
                        bucket.merge(dirPath, node, (old, fresh) -> old.tracked() ? old : fresh);
                    } else {
                        String name = path.substring(from);
                        bucket.putIfAbsent(
                                path,
                                new GitFileNode(
                                        path, name, FileEntryType.FILE, fileSize(path), isTracked));
                    }
                }
                if (slash < 0) break;
                from = slash + 1;
            }
        }

        Map<String, List<GitFileNode>> result = new LinkedHashMap<>();
        acc.forEach(
                (base, nodes) ->
                        result.put(base, nodes.values().stream().sorted(NODE_ORDER).toList()));
        return result;
    }

    // ── Opening a path in the file browser ───────────────────────────────────

    /**
     * Resolves {@code path} into everything the file browser needs to render it: what the path is
     * (file / directory / missing), its content or listing, and — when {@code includeAncestors} —
     * the listings of every directory from the repo root down to the path's parent, so the tree can
     * be expanded to it without walking the levels one request at a time.
     *
     * @param path path relative to repo root; null or blank means the root itself
     * @param includeAncestors whether to include the ancestor listings; a caller that already has
     *     them cached passes false and gets only the path itself
     */
    public GitPathView browsePath(@Nullable String path, boolean includeAncestors) {
        String target = normalizeSub(path);
        List<String> tracked = visiblePaths();
        @Nullable FileEntryType type = resolvePathType(target, tracked);

        List<String> ancestors = includeAncestors ? ancestorDirs(target) : List.of();
        Set<String> bases = new LinkedHashSet<>(ancestors);
        boolean isDirectory = type == FileEntryType.DIRECTORY;
        if (isDirectory) bases.add(target);
        Map<String, List<GitFileNode>> listings =
                bases.isEmpty() ? Map.of() : listDirectories(tracked, bases);

        List<GitTreeLevel> tree =
                ancestors.stream()
                        .map(dir -> new GitTreeLevel(dir, listings.getOrDefault(dir, List.of())))
                        .toList();

        return new GitPathView(
                target,
                type,
                // knownTracked=true: resolvePathType() just confirmed this against the same
                // `tracked` list, so re-checking via isTracked() would re-read the index for
                // nothing.
                type == FileEntryType.FILE ? getFileContent(target, null, null, true) : null,
                isDirectory ? listings.getOrDefault(target, List.of()) : null,
                tree,
                // The root and any missing path count as tracked: there is nothing to warn about.
                target.isEmpty() || type == null || isTracked(target) || isTrackedPrefix(target));
    }

    /**
     * Whether any tracked file lives under {@code dir} — the directory form of {@link #isTracked}.
     */
    private boolean isTrackedPrefix(String dir) {
        String prefix = dir + "/";
        return trackedPaths().stream().anyMatch(p -> p.startsWith(prefix));
    }

    /**
     * {@code FILE}, {@code DIRECTORY} or {@code null} for missing — the repo root is a directory.
     */
    private static @Nullable FileEntryType resolvePathType(String path, List<String> tracked) {
        if (path.isEmpty()) return FileEntryType.DIRECTORY;
        String prefix = path + "/";
        for (String candidate : tracked) {
            if (candidate.equals(path)) return FileEntryType.FILE;
            if (candidate.startsWith(prefix)) return FileEntryType.DIRECTORY;
        }
        return null;
    }

    /**
     * Directories from the repo root down to {@code path}'s parent; {@code path} itself is never
     * included — for the root that means no ancestors at all, not a self-reference.
     */
    private static List<String> ancestorDirs(String path) {
        if (path.isEmpty()) return List.of();
        List<String> dirs = new ArrayList<>();
        dirs.add("");
        for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
            dirs.add(path.substring(0, slash));
        }
        return dirs;
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

    /**
     * Commits matching {@code query} — a prefix of the hash or a substring of the subject line —
     * newest first.
     *
     * <p>Git indexes neither, so this is a linear walk from HEAD, bounded by {@link
     * #COMMIT_SEARCH_SCAN} commits: on a long history an unmatched query would otherwise read the
     * whole repository to answer a keystroke. Matching deliberately ignores the message body — the
     * picker shows the subject, and a hit the user cannot see in the row reads as a wrong result.
     *
     * @param query hash prefix or subject substring, already stripped and non-blank
     * @param maxCount max commits to return, capped at 100
     */
    public List<GitCommit> searchCommits(@NonNull String query, int maxCount) {
        if (query.isBlank()) return List.of();
        String q = query.strip().toLowerCase();
        int limit = Math.min(Math.max(maxCount, 1), 100);

        // Обход строим сами, а не через git.log(): LogCommand отдаёт свой RevWalk как
        // Iterable, и закрыть его уже нечем — а выходим мы отсюда почти всегда по
        // break, на каждое нажатие клавиши в поиске.
        try (RevWalk walk = new RevWalk(repository)) {
            ObjectId head = repository.resolve(Constants.HEAD);
            // Repository has no commits yet — an empty history, not an error.
            if (head == null) return List.of();
            walk.markStart(walk.parseCommit(head));

            ObjectReader reader = walk.getObjectReader();
            List<GitCommit> matches = new ArrayList<>();
            int scanned = 0;
            for (RevCommit commit : walk) {
                if (++scanned > COMMIT_SEARCH_SCAN) break;
                if (!matchesCommit(commit, q)) continue;
                matches.add(toGitCommit(commit, null, reader));
                if (matches.size() >= limit) break;
            }
            return matches;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to search commit log", e);
        }
    }

    private static boolean matchesCommit(RevCommit commit, String lowerQuery) {
        return commit.getName().toLowerCase().startsWith(lowerQuery)
                || commit.getShortMessage().toLowerCase().contains(lowerQuery);
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
            @NonNull String commitHashes, boolean includePatch, @Nullable String filePath) {
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

    private GitCommit diffForSingleCommit(
            String hash, boolean includePatch, @Nullable String filePath) {
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

        List<String> allFiles = visiblePaths();

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
                            return new GitFileNode(
                                    s.path(), name, FileEntryType.FILE, fileSize(s.path()));
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
     * @param includeUntracked also search the untracked files this project's {@code allow-globs}
     *     admit; off by default, so a plain search answers about the committed codebase
     * @return list of match blocks in order of appearance; empty list if nothing matched
     */
    public List<GitGrepMatch> grepContent(
            @NonNull String pattern,
            @Nullable String pathGlob,
            boolean regex,
            int contextLines,
            int maxResults,
            boolean includeUntracked) {

        int ctx = Math.min(Math.max(contextLines, 0), 10);
        int limit = Math.min(Math.max(maxResults, 1), 200);

        if (!regex && (pattern.contains(".*") || pattern.contains("|"))) {
            log.warn(
                    "grepContent: pattern '{}' looks like regex but regex=false — using literal match",
                    pattern);
        }

        String glob =
                pathGlob == null || pathGlob.isBlank() ? null : toForwardSlashes(pathGlob.strip());
        List<GitGrepMatch> tracked =
                parseGrepOutput(
                        exec(grepArgs(pattern, glob, regex, ctx, null)), ctx, limit, project.id());
        if (!includeUntracked || project.allowGlobs().isEmpty()) {
            return tracked;
        }

        // A second, separately bounded run: `--untracked` cannot be added to the one above without
        // also dragging in every other untracked file in the repository, and
        // `--no-exclude-standard`
        // would send it through node_modules and build/. Rooting it at the globs' own directories
        // keeps the walk the size of the named area.
        List<GitGrepMatch> extra =
                parseGrepOutput(
                        exec(grepArgs(pattern, null, regex, ctx, allowGlobRoots)),
                        ctx,
                        Integer.MAX_VALUE,
                        project.id());
        Set<String> trackedPaths = new LinkedHashSet<>(trackedPaths());
        List<GitGrepMatch> merged = new ArrayList<>(tracked);
        extra.stream()
                // The roots are wider than the globs, and `--untracked` reports tracked files too;
                // `glob` is re-applied by hand because it is spent on the pathspec above.
                .filter(m -> !trackedPaths.contains(m.path()))
                .filter(m -> matchesAllowGlobs(m.path()))
                .filter(m -> glob == null || GLOB_MATCHER.match(glob, m.path()))
                .forEach(merged::add);
        // Cut only once everything invisible is gone, or a large untracked area would spend the
        // whole cap on matches nobody gets to see.
        return merged.stream()
                .sorted(Comparator.comparing(GitGrepMatch::path))
                .limit(limit)
                .toList();
    }

    /**
     * One {@code git grep} invocation: {@code git grep -n -i [--untracked --no-exclude-standard]
     * [--fixed-strings|-E] [-C ctx] -- <pattern> [-- <pathspec>…]}.
     *
     * @param roots when non-null, the run covers untracked and {@code .gitignore}d files under
     *     these directories instead of the index
     */
    private static List<String> grepArgs(
            String pattern,
            @Nullable String pathspec,
            boolean regex,
            int ctx,
            @Nullable List<String> roots) {
        List<String> args = new ArrayList<>(List.of("git", "grep", "-n", "-i"));
        if (roots != null) {
            args.add("--untracked");
            args.add("--no-exclude-standard");
        }
        args.add(regex ? "-E" : "--fixed-strings");
        if (ctx > 0) {
            args.add("-C");
            args.add(String.valueOf(ctx));
        }
        args.add("--");
        args.add(pattern);
        if (pathspec != null || roots != null) {
            args.add("--"); // second -- separates the pattern from pathspecs
        }
        if (pathspec != null) {
            args.add(pathspec);
        }
        if (roots != null) {
            args.addAll(roots);
        }
        return args;
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
    private static List<GitGrepMatch> parseGrepOutput(
            List<String> lines, int ctx, int limit, String project) {

        List<GitGrepMatch> results = new ArrayList<>();

        if (ctx == 0) {
            // Simple case: one match per line, format "path:linenum:text"
            for (String line : lines) {
                if (line.isBlank()) continue;
                ParsedLine pl = parseLine(line);
                if (pl == null) continue;
                results.add(new GitGrepMatch(project, pl.path, pl.lineNum, pl.text));
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
                    results.add(
                            new GitGrepMatch(
                                    project, currentPath, firstMatchLine, blockBuf.toString()));
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
                    results.add(
                            new GitGrepMatch(
                                    project, currentPath, firstMatchLine, blockBuf.toString()));
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
            results.add(
                    new GitGrepMatch(project, currentPath, firstMatchLine, blockBuf.toString()));
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
     * <p>Only files tracked by Git are served (checked against the index), plus the untracked files
     * the project's {@code allow-globs} explicitly admit ({@link #isUntrackedAllowed}). Any other
     * untracked file is rejected even though it exists on disk: its content is unreviewed
     * working-tree state and serving it would both leak arbitrary local files and feed unverified
     * data to the model. Ignored files (via {@code .gitignore}) are rejected always, globs or not.
     * The rejection message is identical whether the path is untracked-but-present or genuinely
     * absent — see {@link #readTrackedFile} — so this check cannot be used to probe for the
     * existence of arbitrary files (e.g. {@code .env}) via disk presence alone.
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
        return getFileContent(filePath, fromLine, toLine, false);
    }

    /**
     * @param knownTracked true when the caller already verified {@code filePath} against a
     *     previously-read index (e.g. {@link #browsePath}'s {@code tracked} list) — skips the
     *     redundant {@link #isTracked} re-check that would otherwise re-read the Git index.
     */
    private GitFileContent getFileContent(
            @NonNull String filePath,
            @Nullable Integer fromLine,
            @Nullable Integer toLine,
            boolean knownTracked) {
        FileBytes fb = readTrackedFile(filePath, knownTracked);
        String language = LanguageDetector.detect(fb.path());

        if (fb.binary()) {
            return new GitFileContent(
                    project.id(), fb.path(), null, true, fb.size(), language, 0, false, null, null);
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
                    project.id(),
                    fb.path(),
                    excerpt,
                    false,
                    fb.size(),
                    language,
                    total,
                    true,
                    null,
                    null);
        }

        if (!rangeRequested) {
            return new GitFileContent(
                    project.id(),
                    fb.path(),
                    full,
                    false,
                    fb.size(),
                    language,
                    total,
                    false,
                    null,
                    null);
        }

        // Clamp the requested range into [1, total].
        int from = fromLine == null ? 1 : Math.max(1, fromLine);
        int to = toLine == null ? total : Math.min(total, toLine);
        if (from > total || from > to) {
            // Empty/invalid slice — return no content but keep metadata truthful.
            return new GitFileContent(
                    project.id(),
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
                project.id(),
                fb.path(),
                slice,
                false,
                fb.size(),
                language,
                total,
                truncated,
                from,
                to);
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
        if (language == null || !outlineService.isLanguageSupported(language)) {
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

    // ── Bytes (binary files included) ───────────────────────────────────────

    /**
     * Size, binary flag and detected language of a tracked file, without reading its content.
     *
     * <p>The question {@link #getFileContent} cannot answer cheaply: it has to read the file to
     * tell whether it is binary, and for a binary one everything it read is thrown away. Scripts
     * ask this first and only then decide what to read the file with, so it stays a stat plus an 8
     * KB sniff whatever the file's size.
     */
    public GitFileInfo getFileInfo(@NonNull String filePath) {
        String normalized = normalizePath(filePath);
        Path absolute = requireTracked(normalized, false);
        return new GitFileInfo(
                normalized,
                sizeOf(normalized, absolute),
                isBinary(readWindow(normalized, absolute, 0, BINARY_SNIFF_BYTES)),
                LanguageDetector.detect(normalized));
    }

    /**
     * Raw bytes of a tracked file, binary ones included — the byte-level counterpart of {@link
     * #getFileContent}, which decodes as UTF-8 and therefore serves text only.
     *
     * <p>Only the requested window is read from disk, so the cost of the call follows {@code
     * length} rather than the size of the file: a caller stepping through a 200 MB archive never
     * materialises more than its own window. That is also why there is no truncation flag here — a
     * short window is what was asked for, not an excerpt substituted for the whole.
     *
     * @param offset first byte to return, 0-based; clamped to the end of the file
     * @param length how many bytes to return; {@code 0} or less means "to the end of the file"
     */
    public GitFileBytes getFileBytes(@NonNull String filePath, long offset, long length) {
        String normalized = normalizePath(filePath);
        Path absolute = requireTracked(normalized, false);
        long size = sizeOf(normalized, absolute);
        long from = Math.min(Math.max(offset, 0), size);
        long want = length > 0 ? Math.min(length, size - from) : size - from;
        if (want > MAX_BYTE_WINDOW) {
            throw new IllegalArgumentException(
                    "Cannot read "
                            + want
                            + " bytes at once (max "
                            + MAX_BYTE_WINDOW / 1024
                            + " KB): "
                            + normalized
                            + ". Read the file in windows (offset, length).");
        }
        byte[] window = readWindow(normalized, absolute, from, (int) want);
        // The binary flag describes the file, not the window: it is defined on the head of the
        // file, so unless this window already covers that head — a short window at offset 0 does
        // not — the head is read again to answer it. Otherwise a four-byte peek at a file whose
        // first NUL sits at byte 100 would come back "not binary".
        boolean windowCoversHead = from == 0 && want >= Math.min(size, BINARY_SNIFF_BYTES);
        byte[] head =
                windowCoversHead ? window : readWindow(normalized, absolute, 0, BINARY_SNIFF_BYTES);
        return new GitFileBytes(normalized, window, from, size, isBinary(head));
    }

    /**
     * SHA-256 of a tracked file's bytes, lowercase hex.
     *
     * <p>Streamed, so it costs nothing in memory whatever the file's size — which is the point:
     * comparing two builds, or spotting that a fixture changed, otherwise means pulling both files
     * through a caller that only wants to know whether they differ.
     */
    public String hashFile(@NonNull String filePath) {
        String normalized = normalizePath(filePath);
        Path absolute = requireTracked(normalized, false);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
        try (InputStream in = Files.newInputStream(absolute)) {
            byte[] buffer = new byte[STREAM_BUFFER_BYTES];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file: " + normalized, e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Bytes of a validated, tracked file plus whether it sniffed as binary. */
    private record FileBytes(String path, byte[] bytes, long size, boolean binary) {}

    /**
     * Validates that {@code filePath} is a tracked, in-repo file and reads it once. Centralises the
     * security checks shared by {@link #getFileContent} and {@link #getFileOutline}.
     */
    private FileBytes readTrackedFile(String filePath) {
        return readTrackedFile(filePath, false);
    }

    /**
     * @param knownTracked true when the caller already confirmed {@code filePath} is tracked
     *     against a previously-read index — skips the {@link #isTracked} re-check (and the index
     *     re-read it entails). Never set this from a caller that hasn't actually done that check:
     *     it is the gate that stops untracked/gitignored files from being served.
     */
    private FileBytes readTrackedFile(String filePath, boolean knownTracked) {
        String normalized = normalizePath(filePath);
        Path absolute = requireTracked(normalized, knownTracked);

        long size = sizeOf(normalized, absolute);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(absolute);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file: " + normalized, e);
        }

        return new FileBytes(normalized, bytes, size, isBinary(bytes));
    }

    /**
     * The gate in front of every read: the path is confined to the working tree and must be
     * tracked, or an untracked file the project's {@code allow-globs} admit.
     *
     * <p>The refusal message is the same whether the path is untracked-but-present or genuinely
     * missing, so a caller can't use it to fingerprint which unrelated files (e.g. a gitignored
     * {@code .env}) happen to exist on disk.
     *
     * @return the absolute path of the file, confined to the repository
     */
    private Path requireTracked(String normalized, boolean knownTracked) {
        // Security: confine to the repo before touching the filesystem.
        Path absolute = confineToRepo(normalized);
        if (!knownTracked && !isTracked(normalized) && !isUntrackedAllowed(normalized)) {
            throw new IllegalArgumentException("File not found: " + normalized);
        }
        return absolute;
    }

    // ── Untracked files admitted by the project's allow-globs ───────────────

    /**
     * Whether one of the project's {@code allow-globs} covers {@code normalized}.
     *
     * <p>Git's own metadata is never covered, whatever the globs say. Only this path can reach it —
     * the index lists nothing under {@code .git/} — so the exclusion belongs here, where every
     * consumer of the globs picks it up.
     */
    private boolean matchesAllowGlobs(String normalized) {
        if (isInsideGitDir(normalized)) {
            return false;
        }
        for (String glob : project.allowGlobs()) {
            if (GLOB_MATCHER.match(glob, normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code normalized} is an untracked file this project's {@code allow-globs} admit.
     *
     * <p>The globs name a working area by path, and inside it the working tree is the truth: git's
     * opinion is not consulted at all, so a file {@code .gitignore} hides is served like any other
     * — that is what a deployment opts into by naming the area (build reports and logs, the usual
     * reason for wanting this, live in exactly such a directory). Everything outside the globs
     * stays on the tracked-files rule, which is why the globs must name a real directory (see
     * {@link #globRoots}) instead of sweeping the repository.
     */
    private boolean isUntrackedAllowed(String normalized) {
        return matchesAllowGlobs(normalized)
                && Files.isRegularFile(repoPath.resolve(normalized).normalize());
    }

    private static boolean isInsideGitDir(String normalized) {
        return normalized.equals(".git") || normalized.startsWith(".git/");
    }

    /**
     * Every path the read tools serve: the tracked ones from the index, plus — when the project
     * configures {@code allow-globs} — the untracked files those globs admit, in one deterministic
     * order (tracked first, then the admitted untracked ones sorted by path).
     */
    private List<String> visiblePaths() {
        List<String> tracked = trackedPaths();
        if (project.allowGlobs().isEmpty()) {
            return tracked;
        }
        List<String> admitted = admittedUntracked();
        if (admitted.isEmpty()) {
            return tracked;
        }
        List<String> all = new ArrayList<>(tracked.size() + admitted.size());
        all.addAll(tracked);
        all.addAll(admitted);
        return List.copyOf(all);
    }

    /**
     * The untracked files this project's {@code allow-globs} admit, sorted by path.
     *
     * <p>Walks the working tree rather than asking git, because git cannot answer: it prunes a
     * wholly-ignored directory and reports {@code build/} instead of the report files inside it,
     * which are the point of the feature. The walk starts at {@link #allowGlobRoots}, so its cost
     * is the size of the named area and not of the repository.
     */
    private List<String> admittedUntracked() {
        if (project.allowGlobs().isEmpty()) {
            return List.of();
        }
        Set<String> tracked = new LinkedHashSet<>(trackedPaths());
        List<String> admitted = new ArrayList<>();
        for (String root : allowGlobRoots) {
            Path start = repoPath.resolve(root).normalize();
            if (!Files.isDirectory(start)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(start)) {
                walk.filter(Files::isRegularFile)
                        .map(p -> toForwardSlashes(repoPath.relativize(p).toString()))
                        .filter(p -> !tracked.contains(p))
                        .filter(this::matchesAllowGlobs)
                        .forEach(admitted::add);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to list " + root, e);
            }
        }
        return admitted.stream().distinct().sorted().toList();
    }

    /**
     * The directory each glob is rooted in — {@code "notes/**"} yields {@code "notes"}. Every glob
     * has one: {@link #requireRootedGlobs} refuses a configuration where it would not.
     */
    private static List<String> globRoots(List<String> globs) {
        Set<String> roots = new LinkedHashSet<>();
        for (String glob : globs) {
            int wildcard = indexOfWildcard(glob);
            roots.add(wildcard < 0 ? glob : glob.substring(0, glob.lastIndexOf('/', wildcard)));
        }
        return List.copyOf(roots);
    }

    /**
     * Refuses {@code allow-globs} whose first path segment is already a wildcard.
     *
     * <p>Two reasons, and the second is why this is a hard failure rather than a warning. The walk
     * behind {@link #admittedUntracked} is rooted at the glob's literal prefix, so a glob without
     * one would scan the whole working tree on every tree expand. And since these globs override
     * {@code .gitignore}, the blast radius of {@code **}{@code /*} is every secret in the tree — a
     * deployment should have to name the directory it is opening up.
     */
    private static void requireRootedGlobs(Project project) {
        List<String> unrooted =
                project.allowGlobs().stream()
                        .filter(glob -> indexOfWildcard(glob) >= 0)
                        .filter(glob -> glob.lastIndexOf('/', indexOfWildcard(glob)) <= 0)
                        .toList();
        if (!unrooted.isEmpty()) {
            throw new IllegalStateException(
                    "kb.projects["
                            + project.id()
                            + "].allow-globs must start with a directory, not a wildcard: "
                            + String.join(", ", unrooted)
                            + " — these globs override .gitignore, so the area has to be named");
        }
    }

    private static int indexOfWildcard(String glob) {
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' || c == '?') {
                return i;
            }
        }
        return -1;
    }

    private static long sizeOf(String normalized, Path absolute) {
        try {
            return Files.size(absolute);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file size: " + normalized, e);
        }
    }

    /**
     * {@code length} bytes starting at {@code offset}, or fewer when the file ends first — so a
     * caller may ask for more than is there (the binary sniff does) without checking the size.
     */
    private static byte[] readWindow(String normalized, Path absolute, long offset, int length) {
        if (length <= 0) {
            return new byte[0];
        }
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try (SeekableByteChannel channel = Files.newByteChannel(absolute)) {
            channel.position(offset);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // read() fills what it can per call; loop until the window or the file ends.
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file: " + normalized, e);
        }
        return buffer.position() == length
                ? buffer.array()
                : Arrays.copyOf(buffer.array(), buffer.position());
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
     * Whether the working tree can be written at all. One half of "may the model edit this project"
     * — the configured intent is the other half, and {@code GitRegistry} combines them so a
     * read-only mount (e.g. a ro Docker volume) withholds the edit tools whatever the configuration
     * says.
     */
    public boolean isRepoWritable() {
        return Files.isWritable(repoPath);
    }

    /**
     * Absolute, normalized path of the indexed repository. Reported by the admin panel so it is
     * visible which working tree the model actually reads.
     */
    public Path repoPath() {
        return repoPath;
    }

    /**
     * Creates a new file in the working tree, visible to every read tool of this service from the
     * moment it returns.
     *
     * <p>Normally that means staging it ({@code git add}), since the read tools serve tracked
     * files. A path this project's {@code allow-globs} admit is left untracked instead — the globs
     * name a working area that stays out of commits, and staging it would take it out of that area
     * — unless the path is already in the index, which is the tracked-but-deleted case below.
     *
     * <p>Refused when: the path already exists on disk, the path is matched by {@code .gitignore}
     * (staging would silently skip it, leaving an unreadable orphan — the file is removed again and
     * the call fails), the name is an OS/IDE junk artefact, or the content exceeds {@value
     * #MAX_FILE_SIZE} bytes.
     */
    public GitEditResult createFile(@NonNull String filePath, @NonNull String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        String normalized = requireCreatable(filePath, bytes);
        int lines = content.isEmpty() ? 0 : content.split("\n", -1).length;
        return create(normalized, bytes, lines);
    }

    /**
     * As {@link #createFile}, from raw bytes — the only way to create a file whose content is not
     * text (a fixture image, a keystore, a compiled artefact a test compares against).
     *
     * <p>Line counters come back as zero rather than as a count of accidental {@code 0x0A} bytes: a
     * binary file has no lines, and a number that looks like one would be read as if it did.
     */
    public GitEditResult createBinaryFile(@NonNull String filePath, byte @NonNull [] content) {
        return create(requireCreatable(filePath, content), content, 0);
    }

    /** Shared tail of the two create paths: write, stage, report. */
    private GitEditResult create(String normalized, byte[] content, int lines) {
        // Only presence on disk blocks creation. A tracked-but-deleted file (removed from the
        // working tree, still in the index) is deliberately allowed — editFile can't read it, so
        // createFile is the only way to restore it; the staging below refreshes the index entry.
        Path absolute = repoPath.resolve(normalized).normalize();
        if (Files.exists(absolute)) {
            throw new IllegalArgumentException(
                    "File already exists: " + normalized + ". Use editFile to modify it.");
        }

        // Nothing is created that would stay untracked. The allow-globs area is served for reading
        // and editing what is already there — the files in it are produced by something else (a
        // build, a person's notes), and a new one staged into the index would leave that area
        // whether the model meant to or not. Checked before the write, so nothing reaches disk.
        if (!isTracked(normalized) && matchesAllowGlobs(normalized)) {
            throw new IllegalArgumentException(
                    "Cannot create files under the project's allow-globs: "
                            + normalized
                            + ". Files there can be read and edited, not created.");
        }

        try {
            Path parent = absolute.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(absolute, content);
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

        log.info("createFile: '{}' created and staged ({} bytes)", normalized, content.length);
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
        log.info("editFile: '{}' — {} occurrence(s) replaced", fb.path(), occurrences);
        return writeUpdatedText(fb, text, updated, crlf);
    }

    /**
     * Replaces the whole text of a tracked file, with the same write/stage semantics and the same
     * refusals (binary, too large) as {@link #editFile}.
     *
     * <p>Exists for {@code runScript}: a script may edit one file several times, and each of those
     * edits was already validated against the pending text as it accumulated (see {@code
     * ScriptSession}). Replaying them one by one here would re-do that work and multiply the
     * writes; writing the final text once keeps a script's changes to one atomic write and one diff
     * per file. Not exposed as a tool — the exact-match contract of {@link #editFile} is what
     * forces a model to quote real content, and nothing should be able to skip it.
     */
    public GitEditResult replaceTrackedFile(@NonNull String filePath, @NonNull String newContent) {
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
        return writeUpdatedText(fb, text, newContent.replace("\r\n", "\n"), crlf);
    }

    /**
     * Replaces the whole content of a tracked file with raw bytes — the binary counterpart of
     * {@link #replaceTrackedFile}, and like it not exposed as a tool of its own.
     *
     * <p>Whole-content only, because there is no meaningful partial edit here: the exact-match
     * contract of {@link #editFile} is defined on text, and a byte offset carries none of the
     * evidence that the caller is looking at what it thinks it is. What the user reviews is
     * therefore not a diff but git's own answer for a binary change — the two sizes and the fact
     * that they differ.
     */
    public GitEditResult replaceTrackedBytes(@NonNull String filePath, byte @NonNull [] content) {
        String normalized = requireReplaceable(filePath, content);
        Path absolute = repoPath.resolve(normalized).normalize();
        long before = sizeOf(normalized, absolute);

        writeAtomically(normalized, content);
        stageIfTracked(normalized);

        log.info("wrote '{}' ({} → {} bytes)", normalized, before, content.length);
        String diff =
                "Binary files a/"
                        + normalized
                        + " and b/"
                        + normalized
                        + " differ ("
                        + before
                        + " → "
                        + content.length
                        + " bytes)";
        return new GitEditResult("edit", normalized, 0, 0, 0, diff);
    }

    /**
     * Everything {@link #replaceTrackedBytes} refuses before it touches the disk: a path that is
     * not writable ({@code .git/}, a junk name, an escape from the tree), a path no read tool would
     * serve back afterwards (untracked), and content too large.
     *
     * <p>Split out for the same reason as {@link #requireCreatable}: {@code runScript} stages its
     * writes and applies them only when the script has finished, and a refusal that does not depend
     * on the state of the tree belongs to the script's own runtime, where it is an error the model
     * can still correct and nothing has reached disk.
     *
     * @return the normalized path, as {@link #replaceTrackedBytes} will spell it
     */
    public String requireReplaceable(@NonNull String filePath, byte @NonNull [] content) {
        String normalized = requireWritable(filePath, content);
        requireTracked(normalized, false);
        return normalized;
    }

    /** Shared tail of the two edit paths: diff, atomic write, stage, report. */
    private GitEditResult writeUpdatedText(
            FileBytes fb, String text, String updated, boolean crlf) {
        DiffStats stats = diffStrings(text, updated);
        writeAtomically(fb.path(), crlf ? updated.replace("\n", "\r\n") : updated);
        stageIfTracked(fb.path());

        int lines = updated.isEmpty() ? 0 : updated.split("\n", -1).length;
        log.info("wrote '{}' (+{}/-{})", fb.path(), stats.additions(), stats.deletions());
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

    /**
     * Stages a path that was just written.
     *
     * <p>Not just cosmetics for an edit: a same-size edit written within the same clock tick is
     * "racily clean" and JGit's status (unlike native git) can miss it entirely — the index update
     * makes the change deterministically visible to {@link #getUncommittedChanges}. It also matches
     * {@link #createFile}: everything the model changed is staged, ready for user review.
     *
     * @param what how to name the file if staging fails ({@code "created"} / {@code "edited"})
     */
    private void stage(String normalized, String what) {
        try {
            git.add().addFilepattern(normalized).call();
        } catch (GitAPIException e) {
            throw new IllegalStateException("Failed to stage " + what + " file: " + normalized, e);
        }
    }

    /**
     * Stages an edited file only when it is tracked. An untracked file admitted by the project's
     * {@code allow-globs} must stay untracked through an edit — staging it would silently promote a
     * deliberately-uncommitted file into the next commit.
     */
    private void stageIfTracked(String normalized) {
        if (isTracked(normalized)) {
            stage(normalized, "edited");
        }
    }

    private void writeAtomically(String relativePath, String content) {
        writeAtomically(relativePath, content.getBytes(StandardCharsets.UTF_8));
    }

    /** Writes via a temp file + atomic move so a crash never leaves a half-written file. */
    private void writeAtomically(String relativePath, byte[] content) {
        Path target = repoPath.resolve(relativePath).normalize();
        Path tmp = null;
        try {
            tmp = Files.createTempFile(target.getParent(), ".kb-edit-", ".tmp");
            Files.write(tmp, content);
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
        String normalized = normalizePath(filePath);
        confineToRepo(normalized);
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
     * <p>Tracked files — added/modified/deleted, staged or not — are diffed directly against HEAD,
     * mirroring {@code git diff HEAD}. Files staged by {@link #createFile}/{@link #editFile} are in
     * the index and therefore show up as {@code A}.
     *
     * <p>The untracked files this project's {@code allow-globs} admit are listed alongside them as
     * {@code A}. Those never reach the index — {@link #editFile} leaves them untracked on purpose —
     * so a diff against HEAD cannot see them, and leaving them out would mean the review surface
     * shows nothing of what the assistant wrote there. Files the globs admit but {@code .gitignore}
     * hides are not listed: the globs make them readable, they do not make a build artefact into a
     * change worth reviewing. Every other untracked file stays out too — no tool can read it back,
     * so naming it would only advertise a file no follow-up call can open.
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

        for (String path : admittedUntracked()) {
            if (isJunkFile(path) || !status.getUntracked().contains(path)) continue;
            entries.add(untrackedDiffEntry(path, includePatch));
        }

        return entries;
    }

    /**
     * An admitted untracked file as a whole-file addition. There is no blob to diff against, so the
     * counters come from the working-tree content itself, and a binary or oversized file reports
     * zero lines and no patch rather than a number read off its bytes.
     */
    private GitDiffEntry untrackedDiffEntry(String path, boolean includePatch) {
        byte @Nullable [] content;
        try {
            Path absolute = repoPath.resolve(path).normalize();
            content = Files.size(absolute) > MAX_FILE_SIZE ? null : Files.readAllBytes(absolute);
        } catch (IOException e) {
            log.warn("Cannot read untracked file {} for the change list", path, e);
            content = null;
        }
        if (content == null || isBinary(content)) {
            return new GitDiffEntry("A", path, null, 0, 0, null);
        }
        String text = new String(content, StandardCharsets.UTF_8);
        List<String> lines = text.isEmpty() ? List.of() : List.of(text.split("\n", -1));
        String patch = null;
        if (includePatch) {
            StringBuilder sb = new StringBuilder("+++ b/").append(path).append('\n');
            lines.stream()
                    .limit(MAX_DIFF_LINES)
                    .forEach(l -> sb.append('+').append(l).append('\n'));
            if (lines.size() > MAX_DIFF_LINES) {
                sb.append("... (truncated)\n");
            }
            patch = sb.toString();
        }
        return new GitDiffEntry("A", path, null, lines.size(), 0, patch);
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

    /**
     * Whether something already occupies {@code filePath} in the working tree, tracked or not.
     *
     * <p>For {@code kb.create}, which needs the answer <em>before</em> the run's writes are applied
     * — discovering the clash only at apply time would mean a script that "created" twenty files
     * fails after some of them already exist. Deliberately mirrors what {@code createFile} itself
     * refuses on, including untracked and gitignored files.
     */
    public boolean exists(@NonNull String filePath) {
        return Files.exists(confineToRepo(normalizePath(filePath)));
    }

    /**
     * Everything {@link #createFile} refuses before it touches the disk: an unsafe or unwritable
     * path ({@code .git/}, a junk name, an escape from the tree) and content too large to serve
     * back afterwards.
     *
     * <p>Split out for {@code kb.create}, which stages its writes and applies them only once the
     * script has finished. Those refusals do not depend on the state of the tree, so leaving them
     * to the apply step would turn a script's own mistake — {@code kb.create('.git/hooks/x')} on
     * the third of five files — into two files written and a run that failed anyway, which is
     * exactly the outcome buffering exists to prevent. Checked while the script is still running,
     * it is an ordinary {@code RUNTIME} error the model can correct, and nothing reaches disk.
     *
     * @return the normalized path, as {@link #createFile} will spell it
     */
    public String requireCreatable(@NonNull String filePath, @NonNull String content) {
        return requireWritable(filePath, content.getBytes(StandardCharsets.UTF_8));
    }

    /** As {@link #requireCreatable(String, String)}, for a file created from raw bytes. */
    public String requireCreatable(@NonNull String filePath, byte @NonNull [] content) {
        return requireWritable(filePath, content);
    }

    /**
     * The two refusals every write shares, whatever the file's state: the path may be written to at
     * all, and the content is small enough to be served back afterwards.
     *
     * <p>Named separately from {@link #requireCreatable} and {@link #requireReplaceable} because
     * {@code runScript} needs exactly this pair, and neither of the others, for a file the same run
     * has already staged: such a file is on no disk and in no index yet, so "must exist" and "must
     * be tracked" are both wrong questions to ask about it.
     *
     * @return the normalized path, as the write will spell it
     */
    public String requireWritable(@NonNull String filePath, byte @NonNull [] content) {
        String normalized = validateWritablePath(filePath);
        if (content.length > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(
                    "Content too large (max " + MAX_FILE_SIZE / 1024 + " KB): " + normalized);
        }
        return normalized;
    }

    /**
     * Every path the read tools serve — tracked files in {@code git ls-files} order, followed by
     * the untracked files the project's {@code allow-globs} admit. Exposed for {@code
     * KbScriptApi.files()}: a script filters the list itself in one pass, where the tree tools
     * would need one call per directory level.
     */
    public List<String> listTrackedFiles() {
        return visiblePaths();
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
            RevCommit commit, @Nullable List<GitDiffEntry> files, ObjectReader reader)
            throws IOException {
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
        @Nullable String oldPath = normalizedDiffPath(entry.getOldPath());
        @Nullable String newPath = normalizedDiffPath(entry.getNewPath());

        String status =
                switch (entry.getChangeType()) {
                    case ADD -> "A";
                    case DELETE -> "D";
                    case RENAME -> "R";
                    case COPY -> "C";
                    default -> "M";
                };
        // JGit only reports /dev/null (→ null, see normalizedDiffPath) for the side that doesn't
        // exist: oldPath for ADD, newPath for DELETE/everything else — so whichever side `status`
        // picks is always real.
        String path = Objects.requireNonNull("D".equals(status) ? oldPath : newPath);
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

    private static @Nullable String normalizedDiffPath(String path) {
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

    /**
     * The one spelling of a repo-relative path: backslashes turned round, and {@code ./} and
     * doubled slashes collapsed, so {@code "./docs//a.md"} and {@code "docs/a.md"} are the same
     * string everywhere.
     *
     * <p>Git's index is keyed on the canonical form, so before this every entry point disagreed
     * with itself: {@code getFileContent("./docs/a.md")} answered "File not found" for a file that
     * is plainly there, and {@code createFile} wrote the file to disk and only then failed to stage
     * it — reporting a .gitignore rule that does not exist. Callers that match a path against a
     * pattern need it too: a glob written the obvious way ({@code "secrets/**"}) does not match
     * {@code "./secrets/key.pem"}, so any policy checked on an uncanonical path checks nothing.
     *
     * <p>Validation runs on the raw form first, on purpose. Canonicalising {@code "/etc/passwd"}
     * would strip nothing but canonicalising {@code "a/../../etc"} would resolve a traversal this
     * method must instead refuse, so the refusals happen while the path still says what the caller
     * wrote. What is dropped afterwards — {@code "."} and empty segments — cannot change where a
     * path points.
     *
     * @throws IllegalArgumentException if the path is unsafe, or names nothing once collapsed
     */
    public static String normalizePath(@NonNull String filePath) {
        String forward = toForwardSlashes(filePath.strip());
        requireSafeGitRelativePath(forward);
        if (!forward.contains("./")
                && !forward.contains("//")
                && !forward.endsWith("/.")
                && !forward.endsWith("/")
                && !forward.equals(".")) {
            return forward;
        }
        StringBuilder canonical = new StringBuilder(forward.length());
        for (String segment : forward.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (!canonical.isEmpty()) {
                canonical.append('/');
            }
            canonical.append(segment);
        }
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException("Path must name a file: " + filePath);
        }
        return canonical.toString();
    }

    /**
     * Resolves a repo-relative path to its absolute location and confirms it really is inside the
     * working tree — textually first, then through the filesystem.
     *
     * <p>The textual check ({@link Path#normalize()} plus a prefix comparison) is not enough on its
     * own: it rewrites the path as a string and knows nothing about symlinks. A <em>tracked</em>
     * symlink pointing outside the repository — or a symlinked directory anywhere along the path —
     * passes it, while the read or write itself lands wherever the link points. Resolving the
     * deepest component that actually exists closes that: for an existing file it is the file
     * itself (final link included), for a path being created it is the nearest existing ancestor,
     * which is exactly what {@link #createFile} needs.
     *
     * @return the absolute, normalized path — link-resolution is only the check, callers keep
     *     reading and writing through the path the repository names
     */
    private Path confineToRepo(String normalized) {
        Path absolute = repoPath.resolve(normalized).normalize();
        if (!absolute.startsWith(repoPath)) {
            throw new IllegalArgumentException("Path traversal not allowed: " + normalized);
        }
        for (Path probe = absolute; probe != null; probe = probe.getParent()) {
            Path real;
            try {
                real = probe.toRealPath();
            } catch (IOException ignored) {
                // Not on disk yet (createFile) or a dangling symlink — ask its parent instead.
                continue;
            }
            if (!real.startsWith(repoRealPath)) {
                throw new IllegalArgumentException(
                        "Path escapes the repository via a symlink: " + normalized);
            }
            return absolute;
        }
        // Unreachable in practice: the repository root itself always resolves.
        return absolute;
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
