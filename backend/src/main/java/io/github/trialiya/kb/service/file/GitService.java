package io.github.trialiya.kb.service.file;

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
import io.github.trialiya.kb.service.file.outline.LanguageDetector;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
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

    /** When a file exceeds {@code RepoFiles.MAX_FILE_SIZE}, this many lines from head and tail. */
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

    /**
     * Status letter for an admitted untracked file in {@link #getUncommittedChanges} — git's own
     * {@code A/M/D/R/C} say what the index holds, and this one says the index holds nothing.
     */
    private static final String UNTRACKED_STATUS = "U";

    /** Tree listing order: directories first, then by name, case-insensitively. */
    private static final Comparator<GitFileNode> NODE_ORDER =
            Comparator.<GitFileNode, Boolean>comparing(n -> FileEntryType.DIRECTORY != n.type())
                    .thenComparing(GitFileNode::name, String.CASE_INSENSITIVE_ORDER);

    private final Project project;

    /** Where the working tree is, and every rule about which paths may reach into it. */
    private final RepoPaths paths;

    private final Repository repository;
    private final Git git;
    private final OutlineService outlineService;

    /** Which paths this project exposes, and what the index says about them. */
    private final VisibleFiles visible;

    /** The working-tree writes, kept apart from this far larger read surface. */
    private final GitWriter writer;

    public GitService(Project project, OutlineService outlineService) {
        this.project = project;
        this.paths = new RepoPaths(project.path());
        this.outlineService = outlineService;
        try {
            this.repository =
                    new FileRepositoryBuilder().setWorkTree(paths.root().toFile()).build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open Git repository at " + paths.root(), e);
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
                            + paths.root());
        }
        this.git = new Git(repository);
        this.visible = new VisibleFiles(project, paths, repository);
        this.writer = new GitWriter(project, paths, visible, git);
        log.info("GitService initialised for project {}: {}", project.id(), paths.root());
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
     * {@link VisibleFiles#paths()}).
     */
    public List<GitFileNode> getFileTree(@Nullable String subPath) {
        String base = RepoPaths.normalizeDir(subPath);
        return listDirectories(visible.all(), Set.of(base)).getOrDefault(base, List.of());
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
     * @param visible what to build the listings from, and which of it git knows about
     * @param bases directory paths to list ("" — repo root); paths that are not directories simply
     *     come back with an empty listing
     */
    private Map<String, List<GitFileNode>> listDirectories(
            VisibleFiles.Visible visible, Set<String> bases) {
        Set<String> tracked = visible.tracked();
        // Directory nodes de-duplicate by path (many files share one subdirectory), hence the
        // LinkedHashMap per base rather than a plain list.
        Map<String, LinkedHashMap<String, GitFileNode>> acc = new LinkedHashMap<>();
        for (String base : bases) {
            acc.put(base, new LinkedHashMap<>());
        }

        for (String path : visible.paths()) {
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
        String target = RepoPaths.normalizeDir(path);
        VisibleFiles.Visible files = visible.all();
        @Nullable FileEntryType type = resolvePathType(target, files.paths());

        List<String> ancestors = includeAncestors ? ancestorDirs(target) : List.of();
        Set<String> bases = new LinkedHashSet<>(ancestors);
        boolean isDirectory = type == FileEntryType.DIRECTORY;
        if (isDirectory) bases.add(target);
        Map<String, List<GitFileNode>> listings =
                bases.isEmpty() ? Map.of() : listDirectories(files, bases);

        List<GitTreeLevel> tree =
                ancestors.stream()
                        .map(dir -> new GitTreeLevel(dir, listings.getOrDefault(dir, List.of())))
                        .toList();

        boolean targetTracked = files.tracked().contains(target);

        return new GitPathView(
                target,
                type,
                // The path is vouched for by the `tracked` list resolvePathType() just read, so
                // re-checking it via isTracked() would re-read the index for nothing.
                type == FileEntryType.FILE
                        ? getFileContent(target, null, null, targetTracked)
                        : null,
                isDirectory ? listings.getOrDefault(target, List.of()) : null,
                tree,
                // The root and any missing path count as tracked: there is nothing to warn about.
                target.isEmpty()
                        || type == null
                        || targetTracked
                        || isTrackedPrefix(files.tracked(), target));
    }

    /** Whether any tracked file lives under {@code dir} — the directory form of the membership. */
    private static boolean isTrackedPrefix(Set<String> tracked, String dir) {
        String prefix = dir + "/";
        return tracked.stream().anyMatch(p -> p.startsWith(prefix));
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
                logCommand.addPath(RepoPaths.toForwardSlashes(filePath.strip()));
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
                        : RepoPaths.toForwardSlashes(filePath.strip());
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

        VisibleFiles.Visible files = visible.all();
        List<String> allFiles = files.paths();
        Set<String> tracked = files.tracked();

        record Scored(String path, String name, int score) {}
        return allFiles.stream()
                .map(
                        path -> {
                            String name = RepoPaths.fileName(path);
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
                            return new Scored(path, name, score);
                        })
                .filter(s -> s.score() >= 0)
                .sorted(
                        Comparator.comparingInt(Scored::score)
                                .reversed()
                                .thenComparingInt(s -> s.path().length()))
                .limit(limit)
                .map(
                        s ->
                                new GitFileNode(
                                        s.path(),
                                        s.name(),
                                        FileEntryType.FILE,
                                        fileSize(s.path()),
                                        tracked.contains(s.path())))
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
     * @return match blocks in order of appearance; with {@code includeUntracked} the two runs are
     *     merged and the whole list comes back ordered by path instead, so a file's blocks stay
     *     together rather than splitting around the seam between the runs. Empty if nothing matched
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
                pathGlob == null || pathGlob.isBlank()
                        ? null
                        : RepoPaths.toForwardSlashes(pathGlob.strip());
        List<GitGrepMatch> tracked =
                GitGrep.parse(
                        exec(GitGrep.args(pattern, glob, regex, ctx, null)),
                        ctx,
                        limit,
                        project.id());
        // No roots left to search is not "search everywhere": without a pathspec the untracked run
        // would sweep the whole working tree.
        if (!includeUntracked || visible.allowGlobRoots().isEmpty()) {
            return tracked;
        }

        // A second, separately bounded run: `--untracked` cannot be added to the one above without
        // also dragging in every other untracked file in the repository, and
        // `--no-exclude-standard`
        // would send it through node_modules and build/. Rooting it at the globs' own directories
        // keeps the walk the size of the named area.
        List<GitGrepMatch> extra =
                GitGrep.parse(
                        exec(GitGrep.args(pattern, null, regex, ctx, visible.allowGlobRoots())),
                        ctx,
                        Integer.MAX_VALUE,
                        project.id());
        Set<String> trackedPaths = Set.copyOf(visible.trackedPaths());
        @Nullable Pathspec pathspec = Pathspec.of(glob);
        List<GitGrepMatch> merged = new ArrayList<>(tracked);
        extra.stream()
                // The roots are wider than the globs, and `--untracked` reports tracked files too;
                // `glob` is re-applied by hand because it is spent on the pathspec above.
                .filter(m -> !trackedPaths.contains(m.path()))
                .filter(m -> visible.matchesAllowGlobs(m.path()))
                .filter(m -> pathspec == null || pathspec.matches(m.path()))
                .forEach(merged::add);
        // Cut only once everything invisible is gone, or a large untracked area would spend the
        // whole cap on matches nobody gets to see.
        return merged.stream()
                .sorted(Comparator.comparing(GitGrepMatch::path))
                .limit(limit)
                .toList();
    }

    // ── File content ────────────────────────────────────────────────────────

    /**
     * Returns the content of a <b>tracked</b> file, optimised for AI/LLM consumption.
     *
     * <p>Only files tracked by Git are served (checked against the index), plus the untracked files
     * the project's {@code allow-globs} explicitly admit (see {@link VisibleFiles}). Any other
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
        return getFileContent(filePath, fromLine, toLine, null);
    }

    /**
     * @param vouchedTracked non-null when the caller already resolved {@code filePath} against a
     *     previously-read index (e.g. {@link #browsePath}'s {@code tracked} list) — skips the
     *     redundant {@link VisibleFiles#require} re-check that would otherwise re-read the index,
     *     and answers the returned {@code tracked} with what that index said.
     */
    private GitFileContent getFileContent(
            @NonNull String filePath,
            @Nullable Integer fromLine,
            @Nullable Integer toLine,
            @Nullable Boolean vouchedTracked) {
        FileBytes fb = readTrackedFile(filePath, vouchedTracked);
        String language = LanguageDetector.detect(fb.path());

        if (fb.binary()) {
            return new GitFileContent(
                    project.id(),
                    fb.path(),
                    fb.tracked(),
                    null,
                    true,
                    fb.size(),
                    language,
                    0,
                    false,
                    null,
                    null);
        }

        String full = RepoFiles.decodeToLf(fb.bytes());
        // Split keeping a stable line index; -1 keeps trailing empty lines.
        String[] lines = full.split("\n", -1);
        int total = lines.length;

        boolean rangeRequested = fromLine != null || toLine != null;

        // Oversized file with no explicit range → head+tail excerpt.
        if (!rangeRequested && fb.size() > RepoFiles.MAX_FILE_SIZE) {
            String excerpt = headTailExcerpt(lines);
            return new GitFileContent(
                    project.id(),
                    fb.path(),
                    fb.tracked(),
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
                    fb.tracked(),
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
                    fb.tracked(),
                    "",
                    false,
                    fb.size(),
                    language,
                    total,
                    true,
                    from,
                    Math.max(from, to));
        }
        String slice = String.join("\n", Arrays.asList(lines).subList(from - 1, to));
        boolean truncated = from > 1 || to < total;
        return new GitFileContent(
                project.id(),
                fb.path(),
                fb.tracked(),
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

        String source = RepoFiles.decodeToLf(fb.bytes());
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
        Path absolute = visible.require(normalized).absolute();
        return new GitFileInfo(
                normalized,
                RepoFiles.sizeOf(normalized, absolute),
                RepoFiles.isBinary(
                        RepoFiles.readWindow(
                                normalized, absolute, 0, RepoFiles.BINARY_SNIFF_BYTES)),
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
        Path absolute = visible.require(normalized).absolute();
        long size = RepoFiles.sizeOf(normalized, absolute);
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
        byte[] window = RepoFiles.readWindow(normalized, absolute, from, (int) want);
        // The binary flag describes the file, not the window: it is defined on the head of the
        // file, so unless this window already covers that head — a short window at offset 0 does
        // not — the head is read again to answer it. Otherwise a four-byte peek at a file whose
        // first NUL sits at byte 100 would come back "not binary".
        boolean windowCoversHead =
                from == 0 && want >= Math.min(size, RepoFiles.BINARY_SNIFF_BYTES);
        byte[] head =
                windowCoversHead
                        ? window
                        : RepoFiles.readWindow(
                                normalized, absolute, 0, RepoFiles.BINARY_SNIFF_BYTES);
        return new GitFileBytes(normalized, window, from, size, RepoFiles.isBinary(head));
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
        Path absolute = visible.require(normalized).absolute();
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

    /**
     * Bytes of a validated, visible file, whether it sniffed as binary, and whether git tracks it —
     * the last one straight off the gate that cleared the read, so the answer costs no second index
     * lookup.
     */
    private record FileBytes(
            String path, byte[] bytes, long size, boolean binary, boolean tracked) {}

    /**
     * Validates that {@code filePath} is a file this project serves and reads it once. Centralises
     * the security checks shared by {@link #getFileContent} and {@link #getFileOutline}.
     */
    private FileBytes readTrackedFile(String filePath) {
        return readTrackedFile(filePath, null);
    }

    /**
     * @param vouchedTracked non-null when the caller already resolved {@code filePath} against a
     *     previously-read index (e.g. {@link #browsePath}) — skips the {@link VisibleFiles#require}
     *     re-check and the index re-read it entails, and its value is that index's answer to
     *     "tracked". Never pass a value from a caller that hasn't done that check: {@code require}
     *     is the gate that stops untracked/gitignored files from being served.
     */
    private FileBytes readTrackedFile(String filePath, @Nullable Boolean vouchedTracked) {
        String normalized = normalizePath(filePath);
        if (vouchedTracked != null) {
            // Security: confine to the repo before touching the filesystem — the only half of the
            // gate a vouched-for path still has to pass.
            return readBytes(normalized, paths.confine(normalized), vouchedTracked);
        }
        VisibleFiles.Resolved resolved = visible.require(normalized);
        return readBytes(normalized, resolved.absolute(), resolved.tracked());
    }

    /** Reads a file the gate has already cleared. */
    private static FileBytes readBytes(String normalized, Path absolute, boolean tracked) {
        long size = RepoFiles.sizeOf(normalized, absolute);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(absolute);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read file: " + normalized, e);
        }
        return new FileBytes(normalized, bytes, size, RepoFiles.isBinary(bytes), tracked);
    }

    // ── The repository itself ───────────────────────────────────────────────

    /**
     * Whether the working tree can be written at all. One half of "may the model edit this project"
     * — the configured intent is the other half, and {@code GitRegistry} combines them so a
     * read-only mount (e.g. a ro Docker volume) withholds the edit tools whatever the configuration
     * says.
     */
    public boolean isRepoWritable() {
        return Files.isWritable(paths.root());
    }

    /**
     * Absolute, normalized path of the indexed repository. Reported by the admin panel so it is
     * visible which working tree the model actually reads.
     */
    public Path repoPath() {
        return paths.root();
    }

    // ── Working-tree writes ─────────────────────────────────────────────────

    // Delegated to GitWriter, which holds the rules a write obeys; the methods stay here because
    // GitRegistry hands callers a GitService and it is the whole tool surface for one project.

    /**
     * @see GitWriter#createFile
     */
    public GitEditResult createFile(@NonNull String filePath, @NonNull String content) {
        return writer.createFile(filePath, content);
    }

    /**
     * @see GitWriter#createBinaryFile
     */
    public GitEditResult createBinaryFile(@NonNull String filePath, byte @NonNull [] content) {
        return writer.createBinaryFile(filePath, content);
    }

    /**
     * @see GitWriter#editFile
     */
    public GitEditResult editFile(
            @NonNull String filePath,
            @NonNull String oldString,
            @NonNull String newString,
            boolean replaceAll) {
        return writer.editFile(filePath, oldString, newString, replaceAll);
    }

    /**
     * @see GitWriter#replaceTrackedFile
     */
    public GitEditResult replaceTrackedFile(@NonNull String filePath, @NonNull String newContent) {
        return writer.replaceTrackedFile(filePath, newContent);
    }

    /**
     * @see GitWriter#replaceTrackedBytes
     */
    public GitEditResult replaceTrackedBytes(@NonNull String filePath, byte @NonNull [] content) {
        return writer.replaceTrackedBytes(filePath, content);
    }

    /**
     * @see GitWriter#requireCreatable
     */
    public String requireCreatable(@NonNull String filePath, @NonNull String content) {
        return writer.requireCreatable(filePath, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @see GitWriter#requireCreatable
     */
    public String requireCreatable(@NonNull String filePath, byte @NonNull [] content) {
        return writer.requireCreatable(filePath, content);
    }

    /**
     * @see GitWriter#requireReplaceable
     */
    public String requireReplaceable(@NonNull String filePath, byte @NonNull [] content) {
        return writer.requireReplaceable(filePath, content);
    }

    /**
     * @see GitWriter#requireWritable
     */
    public String requireWritable(@NonNull String filePath, byte @NonNull [] content) {
        return writer.requireWritable(filePath, content);
    }

    /**
     * A file this project's configuration allows an edit to land on, and git's answer about it —
     * handed out only by {@link #requireEditable}, which is what makes it a vouch: holding one is
     * proof the read gate has already cleared the path against the index.
     *
     * @param tracked from that same index read, which is why {@link #getFileContent(EditableFile)}
     *     can serve the file without a second one
     */
    public record EditableFile(String path, boolean tracked) {}

    /**
     * @see GitWriter#requireEditable
     */
    public EditableFile requireEditable(@NonNull String filePath) {
        String normalized = normalizePath(filePath);
        return new EditableFile(normalized, writer.requireEditable(normalized).tracked());
    }

    /**
     * Full content of a file already cleared by {@link #requireEditable} — the read {@code kb.edit}
     * does right after its permission check, at no second index read.
     */
    public GitFileContent getFileContent(@NonNull EditableFile file) {
        return getFileContent(file.path(), null, null, file.tracked());
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
        return Files.exists(paths.confine(normalizePath(filePath)));
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
     * <p>The untracked files this project's {@code allow-globs} admit are listed alongside them
     * under a status of their own, {@code U}. Those never reach the index — {@link #editFile}
     * leaves them untracked on purpose — so a diff against HEAD cannot see them, and leaving them
     * out would mean the review surface shows nothing of what the assistant wrote there. {@code U}
     * rather than {@code A} because they are not staged for anything: an {@code A} would tell the
     * model the file is on its way into the next commit, and the difference decides whether a
     * change has to be mentioned to the user or is simply there. Files the globs admit but {@code
     * .gitignore} hides are not listed: the globs make them readable, they do not make a build
     * artefact into a change worth reviewing. Every other untracked file stays out too — no tool
     * can read it back, so naming it would only advertise a file no follow-up call can open.
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
                        if (RepoPaths.isJunkFile(mapped.path())) continue;
                        entries.add(mapped);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to diff working tree against HEAD", e);
            }
        }

        // Straight off the status: what git already reported as untracked is by definition
        // untracked and not ignored, so the globs are the only question left to ask about it —
        // walking the allow-glob area again would answer nothing this does not.
        status.getUntracked().stream()
                .filter(path -> !RepoPaths.isJunkFile(path))
                .filter(visible::matchesAllowGlobs)
                .sorted()
                .forEach(path -> entries.add(untrackedDiffEntry(path, includePatch)));

        return entries;
    }

    /**
     * An admitted untracked file as a whole-file {@code U}. There is no blob to diff against, so
     * the counters come from the working-tree content itself, and a binary or oversized file
     * reports zero lines and no patch rather than a number read off its bytes.
     */
    private GitDiffEntry untrackedDiffEntry(String path, boolean includePatch) {
        byte @Nullable [] content;
        try {
            // Through confineToRepo like every other read: an admitted untracked path may be a
            // symlink out of the repository, and the change list must not be the one place that
            // follows it.
            Path absolute = paths.confine(path);
            content =
                    Files.size(absolute) > RepoFiles.MAX_FILE_SIZE
                            ? null
                            : Files.readAllBytes(absolute);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Cannot read untracked file {} for the change list", path, e);
            content = null;
        }
        if (content == null || RepoFiles.isBinary(content)) {
            return new GitDiffEntry(UNTRACKED_STATUS, path, null, 0, 0, null);
        }
        String text = new String(content, StandardCharsets.UTF_8);
        List<String> lines = text.isEmpty() ? List.of() : List.of(text.split("\n", -1));
        String patch = null;
        if (includePatch) {
            StringBuilder sb = new StringBuilder("+++ b/").append(path).append('\n');
            lines.stream()
                    .limit(Diffs.MAX_DIFF_LINES)
                    .forEach(l -> sb.append('+').append(l).append('\n'));
            if (lines.size() > Diffs.MAX_DIFF_LINES) {
                sb.append("... (truncated)\n");
            }
            patch = sb.toString();
        }
        return new GitDiffEntry(UNTRACKED_STATUS, path, null, lines.size(), 0, patch);
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
     * Every path the read tools serve — tracked files in {@code git ls-files} order, followed by
     * the untracked files the project's {@code allow-globs} admit. Exposed for {@code
     * KbScriptApi.files()}: a script filters the list itself in one pass, where the tree tools
     * would need one call per directory level.
     */
    public List<String> listTrackedFiles() {
        return visible.paths();
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
            patch = Diffs.truncate(patchOut.toString(StandardCharsets.UTF_8));
        }
        return new GitDiffEntry(status, path, reportedOldPath, add, del, patch);
    }

    private static @Nullable String normalizedDiffPath(String path) {
        return DiffEntry.DEV_NULL.equals(path) ? null : path;
    }

    /**
     * The one spelling of a repo-relative path, as {@link RepoPaths#normalize} defines it.
     *
     * <p>Kept here as the entry point every caller outside this package already knows.
     *
     * @throws IllegalArgumentException if the path is unsafe, or names nothing once collapsed
     */
    public static String normalizePath(@NonNull String filePath) {
        return RepoPaths.normalize(filePath);
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
                            .directory(paths.root().toFile())
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Git command interrupted: " + command, e);
        } catch (IOException e) {
            throw new IllegalStateException("Git command failed: " + command, e);
        }
    }

    private long fileSize(String relativePath) {
        try {
            return Files.size(paths.resolve(relativePath));
        } catch (IOException e) {
            return -1;
        }
    }
}
