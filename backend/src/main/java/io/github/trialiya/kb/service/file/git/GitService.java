package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.FileEntryType;
import io.github.trialiya.kb.model.git.dto.GitBranchStatus;
import io.github.trialiya.kb.model.git.dto.GitCommandResult;
import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitCommitGrepResult;
import io.github.trialiya.kb.model.git.dto.GitCommitSearchResult;
import io.github.trialiya.kb.model.git.dto.GitComparison;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import io.github.trialiya.kb.model.git.dto.GitEditResult;
import io.github.trialiya.kb.model.git.dto.GitFileBlame;
import io.github.trialiya.kb.model.git.dto.GitFileBytes;
import io.github.trialiya.kb.model.git.dto.GitFileContent;
import io.github.trialiya.kb.model.git.dto.GitFileInfo;
import io.github.trialiya.kb.model.git.dto.GitFileNode;
import io.github.trialiya.kb.model.git.dto.GitFileOutline;
import io.github.trialiya.kb.model.git.dto.GitGrepHits;
import io.github.trialiya.kb.model.git.dto.GitGrepResult;
import io.github.trialiya.kb.model.git.dto.GitLineOrigin;
import io.github.trialiya.kb.model.git.dto.GitPathView;
import io.github.trialiya.kb.model.git.dto.GitRefs;
import io.github.trialiya.kb.model.git.dto.GitTreeLevel;
import io.github.trialiya.kb.model.git.dto.TextEdit;
import io.github.trialiya.kb.model.project.Project;
import io.github.trialiya.kb.service.file.outline.OutlineService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
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
 * subprocess, no argv, no output parsing — except {@link #grepHits}, {@link #getBlame} and {@link
 * #getLineLog}, which shell out to {@code git grep}, {@code git blame} and {@code git log -L}
 * through {@link GitGrepRunner}, {@link GitBlameRunner} and {@link GitLineLog} (JGit has no grep
 * and no line-range log, and its blame cannot skip {@code .git-blame-ignore-revs}), and the
 * user's network commands ({@link #fetch}, see {@code GitCommands}), which shell out to reuse the
 * host's credentials. Files matched by {@code .gitignore} are excluded from tree/search/status
 * results the same way native git excludes them.
 */
@Slf4j
public class GitService {

    /**
     * Most commits the push dialog is given. A branch further ahead than this is not a list any
     * more, and the counter next to the branch keeps saying how many there really are.
     */
    private static final int MAX_OUTGOING = 100;

    /** Most commits one history listing or search returns — what a larger {@code maxCount} is cut to. */
    public static final int MAX_COMMITS = CommitSearch.MAX_RESULTS;

    private final Project project;

    /** Where the working tree is, and every rule about which paths may reach into it. */
    private final RepoPaths paths;

    private final Repository repository;
    private final Git git;
    private final OutlineService outlineService;

    /** Which paths this project exposes, and what the index says about them. */
    private final VisibleFiles visible;

    /** Content search, through a {@code git grep} subprocess. */
    private final GitGrepRunner grep;

    /** Line authorship, through a {@code git blame} subprocess. */
    private final GitBlameRunner blame;

    /** {@code git log -L}: the commits that changed a range of lines. */
    private final GitLineLog lineLog;

    /** Where a substring entered a line, through JGit. */
    private final LineOriginTracer origins;

    /** The working-tree writes, kept apart from this far larger read surface. */
    private final GitWriter writer;

    /** Which branch this repository is on and what else it has. */
    private final GitBranches branches;

    /** The git commands a user runs from the UI; reached only through {@code GitRegistry}. */
    private final GitCommands commands;

    /** What the working tree holds that HEAD does not. */
    private final WorkingTreeChanges changes;

    /** Raw bytes, binary files included: windows, whole files for previews, hashes. */
    private final RepoBytes bytes;

    public GitService(Project project, OutlineService outlineService) {
        this.project = project;
        this.paths = new RepoPaths(project.path());
        this.outlineService = outlineService;
        try {
            this.repository = new FileRepositoryBuilder()
                    .setWorkTree(paths.root().toFile())
                    .build();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to open Git repository at " + paths.root(), e);
        }
        // FileRepositoryBuilder.build() never touches disk to verify a .git dir exists — without
        // this check a bad configured path would silently produce empty results from every tool
        // (readDirCache() on a missing index just returns 0 entries) instead of failing the
        // registry's construction with an actionable error.
        if (!repository.getDirectory().isDirectory()) {
            throw new IllegalStateException(
                    "Not a Git repository (no .git found) for project \"" + project.id() + "\": " + paths.root());
        }
        this.git = new Git(repository);
        this.visible = new VisibleFiles(project, paths, repository);
        this.grep = new GitGrepRunner(paths, repository, visible);
        this.blame = new GitBlameRunner(paths, repository, visible);
        this.lineLog = new GitLineLog(paths, repository, git, visible);
        this.origins = new LineOriginTracer(repository, visible);
        this.writer = new GitWriter(project, paths, visible, git);
        this.branches = new GitBranches(repository, git);
        this.commands = new GitCommands(paths, repository, git, branches);
        this.changes = new WorkingTreeChanges(paths, repository, git, visible);
        this.bytes = new RepoBytes(repository, visible);
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

    /**
     * The revision a read names, or {@code null} for the working tree. Every read that can look at
     * either takes {@code rev} first: {@code null} and blank both mean "the working tree", so a
     * caller passes what it was given without choosing a method for it.
     */
    private static @Nullable String snapshot(@Nullable String rev) {
        return rev == null || rev.isBlank() ? null : rev;
    }

    /** An optional file path in git's spelling; {@code null} and blank both mean "none given". */
    private static @Nullable String pathOrNull(@Nullable String filePath) {
        return filePath == null || filePath.isBlank() ? null : RepoPaths.toForwardSlashes(filePath.strip());
    }

    // ── File tree ────────────────────────────────────────────────────────────

    /**
     * Returns visible files/directories under {@code subPath} (or repo root if null), directories
     * first then alphabetically (case-insensitive): the tracked files from the Git index, plus —
     * when the project configures {@code allow-globs} — the untracked files those globs admit (see
     * {@link VisibleFiles#paths()}). With {@code rev} — the same listing of that commit's tree: no
     * uncommitted change reaches it, and a file deleted since is still there.
     *
     * @param rev the commit to list; {@code null} or blank — the working tree. Anything git reads as
     *     a commit: a full or short hash, a branch, a tag, {@code HEAD~2}
     * @throws IllegalArgumentException if the revision is unknown or ambiguous
     */
    public List<GitFileNode> getFileTree(@Nullable String rev, @Nullable String subPath) {
        String at = snapshot(rev);
        if (at != null) return fileTreeAt(at, subPath);
        return RepoBrowse.tree(workingTree(visible.all()), RepoPaths.normalizeDir(subPath));
    }

    /** {@link #getFileTree} of a commit's tree. */
    private List<GitFileNode> fileTreeAt(String rev, @Nullable String subPath) {
        try (CommitFiles.Commit commit = CommitFiles.Commit.open(repository, rev.strip())) {
            return listingAt(commit, RepoPaths.normalizeDir(subPath));
        }
    }

    /** Потомок каталога коммита как узел дерева: в коммите отслеживается всё. */
    private static GitFileNode committedNode(CommitFiles.Child child) {
        return child.directory()
                ? new GitFileNode(child.path(), child.name(), FileEntryType.DIRECTORY, null)
                : new GitFileNode(child.path(), child.name(), FileEntryType.FILE, child.size());
    }

    /** The working tree as the browser lists it: the index widened by {@code allow-globs}. */
    private RepoBrowse.Snapshot workingTree(VisibleFiles.Visible files) {
        return new RepoBrowse.Snapshot(files.paths(), files.tracked(), visible::fileSize, this::fileExists);
    }

    // ── Opening a path in the file browser ───────────────────────────────────

    /**
     * Resolves {@code path} into everything the file browser needs to render it: what the path is
     * (file / directory / missing), its content or listing, and — when {@code includeAncestors} —
     * the listings of every directory from the repo root down to the path's parent, so the tree can
     * be expanded to it without walking the levels one request at a time.
     *
     * <p>With {@code rev} — the same view of that commit's tree, what the browser's revision mode
     * renders; see {@link #pathViewAt}.
     *
     * @param rev the commit to read; {@code null} or blank — the working tree
     * @param path path relative to repo root; null or blank means the root itself
     * @param includeAncestors whether to include the ancestor listings; a caller that already has
     *     them cached passes false and gets only the path itself
     * @throws IllegalArgumentException if the revision is unknown or ambiguous
     */
    public GitPathView browsePath(@Nullable String rev, @Nullable String path, boolean includeAncestors) {
        String at = snapshot(rev);
        if (at != null) return pathViewAt(at, path, includeAncestors);
        String target = RepoPaths.normalizeDir(path);
        VisibleFiles.Visible files = visible.all();
        return RepoBrowse.browse(
                workingTree(files), target, includeAncestors, tracked -> readContent(target, null, null, tracked));
    }

    /**
     * The same view of a commit's tree — what the file browser's revision mode renders.
     *
     * <p>A separate reading rather than a branch inside the working-tree one: the two answer from
     * different sources, and mixing them in one response is exactly the confusion the mode exists
     * to prevent. Whether the path is *visible* is not asked here — everything in a commit is
     * already the repository's published history, the same rule {@code getFileContent}'s {@code
     * commit} argument follows.
     *
     * @param rev anything git reads as a commit — a full or short hash, a branch, a tag, {@code
     *     HEAD~2}
     */
    private GitPathView pathViewAt(String rev, @Nullable String path, boolean includeAncestors) {
        String target = RepoPaths.normalizeDir(path);
        try (CommitFiles.Commit commit = CommitFiles.Commit.open(repository, rev.strip())) {
            CommitFiles.Entry entry = commit.entry(target);
            // Каталог, под которым нет ни одного открываемого файла, — missing, как и в листинге.
            // Ответ на это даёт сам листинг, поэтому он читается до выбора типа: отдельный обход
            // поддерева ради того же ответа стоил бы второго чтения. Корень остаётся каталогом
            // и пустым: сказать «такого пути нет» про корень коммита было бы неправдой.
            List<GitFileNode> nodes = listingAt(commit, entry, target);
            boolean directory = entry.kind() == CommitFiles.Kind.DIRECTORY && (!nodes.isEmpty() || target.isEmpty());
            List<GitTreeLevel> tree = includeAncestors
                    ? RepoBrowse.ancestorDirs(target).stream()
                            .map(dir -> new GitTreeLevel(dir, listingAt(commit, dir)))
                            .toList()
                    : List.of();
            if (entry.kind() == CommitFiles.Kind.FILE) {
                return new GitPathView(
                        target, FileEntryType.FILE, contentAt(commit, entry, target), null, tree, commit.name(), true);
            }
            if (directory) {
                return new GitPathView(target, FileEntryType.DIRECTORY, null, nodes, tree, commit.name(), true);
            }
            // Путь, которого в коммите нет, — missing, а не отказ: браузер так и рисует, а
            // предупреждать об «отслеживании» тут не о чем.
            return new GitPathView(target, null, null, null, tree, commit.name(), true);
        }
    }

    /** Листинг каталога коммита в порядке браузера. */
    private static List<GitFileNode> listingAt(CommitFiles.Commit commit, String dir) {
        return ordered(commit.children(dir));
    }

    /** Листинг каталога, который уже нашёл {@code entry}: дерево второй раз не читается. */
    private static List<GitFileNode> listingAt(CommitFiles.Commit commit, CommitFiles.Entry entry, String dir) {
        return ordered(commit.children(entry, dir));
    }

    private static List<GitFileNode> ordered(List<CommitFiles.Child> children) {
        return RepoBrowse.ordered(
                children.stream().map(GitService::committedNode).toList());
    }

    /**
     * Содержимое пути в коммите, либо null, если отдать его нельзя — блоб больше предела чтения из
     * истории.
     *
     * <p>Отказ содержимого не уносит весь ответ: дерево и листинги предков к нему отношения не
     * имеют, а без них браузер показал бы одну ошибку вместо панели — и починить адрес было бы
     * негде. Что именно нечитаемо, видно по {@code file: null} у типа {@code FILE}.
     */
    private static @Nullable GitFileContent contentAt(CommitFiles.Commit commit, CommitFiles.Entry entry, String path) {
        try {
            CommitFiles.Blob blob = commit.blob(entry, path);
            return FileViews.of(path, true, blob.commit(), blob.bytes(), blob.size(), null, null);
        } catch (IllegalArgumentException e) {
            log.debug("No content for {} at {}: {}", path, commit.name(), e.getMessage());
            return null;
        }
    }

    // ── Commit history ───────────────────────────────────────────────────────

    /**
     * History from HEAD with an exact {@code truncated} — the model's listing; see {@link
     * #getCommitLog(int, String, boolean, String, boolean)}.
     */
    public GitCommitSearchResult getCommitLog(int maxCount, @Nullable String filePath, boolean includeBody) {
        return getCommitLog(maxCount, filePath, includeBody, null, true);
    }

    /**
     * Recent commit history, newest first.
     *
     * @param maxCount max commits to return (default 20, capped at {@value #MAX_COMMITS})
     * @param filePath optional — limit history to a specific file
     * @param includeBody fill each commit's {@code body} with the message below the subject
     * @param rev optional — walk from this revision instead of HEAD, so a browser showing a
     *     commit's snapshot describes its paths by the history of that snapshot: a commit made
     *     after {@code rev} did not touch what the snapshot holds
     * @param exact whether {@code truncated} must say that history really goes on — at the price
     *     of walking on to one more commit, which along a path can mean the whole history; without
     *     it the walk stops at {@code maxCount} and {@code truncated} only says the page is full
     * @return the commits with {@code truncated}
     */
    public GitCommitSearchResult getCommitLog(
            int maxCount, @Nullable String filePath, boolean includeBody, @Nullable String rev, boolean exact) {
        return getCommitLog(maxCount, 0, filePath, includeBody, rev, exact);
    }

    /**
     * A page of the history further down: the same walk with the first {@code skip} commits left
     * out — what the file browser's history list asks for under "show more".
     */
    public GitCommitSearchResult getCommitLog(
            int maxCount,
            int skip,
            @Nullable String filePath,
            boolean includeBody,
            @Nullable String rev,
            boolean exact) {
        return CommitSearch.log(
                repository, maxCount, skip, new CommitSearch.Scope(false, includeBody, rev, filePath), exact);
    }

    /**
     * The commits that changed lines {@code fromLine}…{@code toLine} of the file, numbered as the
     * working tree has it, newest first; see {@link GitLineLog}.
     *
     * @param maxCount max commits to return, capped at {@value #MAX_COMMITS}
     */
    public GitCommitSearchResult getLineLog(
            @NonNull String filePath,
            @Nullable Integer fromLine,
            @Nullable Integer toLine,
            int maxCount,
            boolean includeBody) {
        int limit = Math.min(Math.max(maxCount, 1), MAX_COMMITS);
        return lineLog.log(normalizePath(filePath), fromLine, toLine, limit, includeBody);
    }

    /**
     * The commits this branch would publish — what the push dialog lists before the push runs.
     *
     * <p>Read off the refs on disk like every other count here, so it is as fresh as the last
     * fetch. With an upstream the answer is exactly {@code upstream..HEAD}, the same range the
     * "ahead" counter reports. Without one — a branch created in the panel and never pushed — that
     * range would be empty while the push is about to publish real work, so the commits reachable
     * from HEAD but from no remote-tracking ref are listed instead: that is what a first push
     * actually sends.
     *
     * @param maxCount how many to return, capped at {@value #MAX_OUTGOING}
     */
    public List<GitCommit> getOutgoingCommits(int maxCount) {
        return branches.outgoing(Math.min(Math.max(maxCount, 1), MAX_OUTGOING));
    }

    /**
     * Commits matching {@code query} — a prefix of the hash or a substring of the subject line —
     * newest first, walking from HEAD. The phrase placeholder picker's lookup; see {@link
     * CommitSearch} for how far back it looks.
     *
     * @param query hash prefix or subject substring; blank — an empty answer
     * @param maxCount max commits to return, capped at {@value #MAX_COMMITS}
     * @return the matches with {@code truncated}: the picker has to tell "no such commit" from "not
     *     that far back", and "these" from "the first of more"
     */
    public GitCommitSearchResult searchCommits(@NonNull String query, int maxCount) {
        return CommitSearch.search(repository, query, maxCount, CommitSearch.Scope.SUBJECT);
    }

    /**
     * Commits whose subject or description contains {@code query}, or whose hash starts with it,
     * newest first, walking from HEAD — the model's search through history ({@code getCommitLog}
     * with a query).
     *
     * @param filePath optional — only commits that touched this file or directory
     * @param includeBody fill {@link GitCommit#body()}; the description is searched either way
     * @return the matches with {@code truncated}: the walk is bounded, and the model has to tell "no
     *     such commit" from "not that far back"
     */
    public GitCommitSearchResult searchCommitLog(
            @NonNull String query, int maxCount, @Nullable String filePath, boolean includeBody) {
        return CommitSearch.search(
                repository, query, maxCount, new CommitSearch.Scope(true, includeBody, null, filePath));
    }

    /**
     * Commits whose subject or description contains {@code query}, or whose hash starts with it,
     * newest first, each with where it matched — the subject, the lines of the description, or the
     * hash alone — rather than the description itself: the search page shows those places.
     *
     * @param rev optional — walk from this revision instead of HEAD
     */
    public GitCommitGrepResult grepCommits(@NonNull String query, int maxCount, @Nullable String rev) {
        return CommitSearch.grep(repository, query, maxCount, rev);
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
     * restricted to a single file. A single named commit also carries its full message body; a list
     * does not.
     *
     * @param commitHashes comma-separated commit hashes
     * @param includePatch whether to include unified diff text
     * @param filePath optional path (relative to repo root) to restrict the diff to a single file;
     *     null or blank means the whole commit
     */
    public List<GitCommit> getCommitDiff(
            @NonNull String commitHashes, boolean includePatch, @Nullable String filePath) {
        String spec = pathOrNull(filePath);
        List<String> hashes = Arrays.stream(commitHashes.split(","))
                .map(String::strip)
                .filter(h -> !h.isEmpty())
                .toList();
        // Сообщение целиком — только когда коммит назвали один. Тогда спрашивают «почему это
        // меняли», и тело рядом с диффом стоит дёшево; на списке из двадцати хешей оно вернуло бы
        // ровно те десятки тысяч токенов, ради которых в getCommitLog заведён флаг.
        boolean includeBody = hashes.size() == 1;
        List<GitCommit> result = new ArrayList<>();
        for (String hash : hashes) {
            result.add(CommitDiff.of(repository, hash, includePatch, spec, includeBody, null, false));
        }
        return result;
    }

    /**
     * One commit as the file browser describes the snapshot it shows: the full message and the
     * files the commit changed against its first parent (a root commit — against the empty tree, a
     * merge — against the branch it was merged into, as {@code git show --first-parent} reads it).
     *
     * <p>Unlike {@link #getCommitDiff}, {@code rev} is one revision and is never split on commas:
     * it comes from the revision picker, where a comma is part of what was typed, not a list.
     *
     * <p>With {@code filePath} the answer holds that file's entry alone — what the browser asks
     * for, with the patch, when it opens one file of the commit. The path is matched after rename
     * detection rather than handed to the diff as a filter: a filter hides the other side of a
     * rename, and a renamed file would come back as a whole new file.
     *
     * @param rev anything git reads as a commit — a full or short hash, a branch, a tag, {@code
     *     HEAD~2}
     */
    public GitCommit getCommit(@NonNull String rev, boolean includePatch, @Nullable String filePath) {
        String only = pathOrNull(filePath);
        return CommitDiff.of(repository, rev.strip(), includePatch, null, true, only, true);
    }

    /**
     * How {@code head} differs from {@code base} — the file browser's changes mode with a base to
     * compare against: the files, the commits between the two and their common ancestor; see {@link
     * GitComparison}.
     *
     * <p>As with {@link #getCommit}, {@code filePath} keeps that file's entry alone, matched after
     * rename detection, and the answer then carries no history: the browser asks for it, with the
     * patch, on every file it opens.
     *
     * @param base the revision compared against — anything git reads as a commit: a hash, a branch,
     *     a tag, {@code HEAD~2}
     * @param head the revision compared, read the same way
     * @param direct diff against {@code base} itself rather than the common ancestor
     * @param scope a file or directory to narrow the comparison to — both the files and the
     *     history between the two; {@code null} or blank compares the whole repository
     */
    public GitComparison compare(
            @NonNull String base,
            @NonNull String head,
            boolean direct,
            boolean includePatch,
            @Nullable String filePath,
            @Nullable String scope) {
        String only = pathOrNull(filePath);
        return RevisionCompare.compare(
                repository, base.strip(), head.strip(), direct, includePatch, only, RepoPaths.normalizeDir(scope));
    }

    // ── File search ─────────────────────────────────────────────────────────

    /** Fuzzy-searches the visible files by name; see {@link VisibleFiles#searchByName}. */
    public List<GitFileNode> searchFiles(@NonNull String pattern, int maxResults) {
        return visible.searchByName(pattern, maxResults);
    }

    // ── Content grep ────────────────────────────────────────────────────────

    /**
     * Searches file contents for lines matching {@code pattern}, saying whether there is more than
     * it returned; see {@link GitGrepRunner#grepHits}.
     */
    public GitGrepHits grepHits(
            @NonNull String pattern,
            @Nullable String pathGlob,
            boolean regex,
            @Nullable String rev,
            int contextLines,
            int maxResults,
            boolean includeUntracked) {
        return grep.grepHits(pattern, pathGlob, regex, rev, contextLines, maxResults, includeUntracked);
    }

    /**
     * The search page's content search, grouped by file and saying whether it was cut; see {@link
     * GitGrepRunner#grepPage}.
     */
    public GitGrepResult grepPage(
            @NonNull String pattern,
            @Nullable String pathGlob,
            boolean regex,
            @Nullable String rev,
            boolean includeUntracked,
            int maxResults) {
        return grep.grepPage(pattern, pathGlob, regex, rev, includeUntracked, maxResults);
    }

    // ── Line authorship ─────────────────────────────────────────────────────

    /**
     * Who last changed each line of a file, with the revisions {@code .git-blame-ignore-revs} names
     * skipped: of a tracked file in the working tree, or — with {@code rev} — of the file as of
     * that commit; see {@link GitBlameRunner#blame} and {@link GitBlameRunner#blameAt}.
     *
     * @param rev the commit to read; {@code null} or blank — the working tree
     */
    public GitFileBlame getBlame(@Nullable String rev, @NonNull String filePath) {
        return getBlame(rev, filePath, null, null, BlameMatching.EXACT);
    }

    /**
     * {@link #getBlame(String, String)} narrowed to a line range — git walks the history of those
     * lines only. Bounds work as in {@link #getFileContent(String, String, Integer, Integer)}: a
     * missing one is the file's edge, one outside the file is pulled in, and a range that misses
     * the file entirely answers with no hunks rather than an error. Both bounds null is the whole
     * file, as with the two-argument form. An end before the start is the caller's mistake, not an
     * empty range: told apart from a start past the file's end, which only the file can answer.
     *
     * <p>Two questions ({@link BlameMatching}): {@code exact} — which commit last touched each line,
     * any edit counted, as the two-argument form and GitHub answer; otherwise who wrote the lines,
     * past re-indents and moves within the file, chosen for the file by its normalized name.
     *
     * @param fromLine first line (1-based, inclusive); null for start of file
     * @param toLine last line (1-based, inclusive); null for end of file
     * @throws IllegalArgumentException if {@code toLine} is before {@code fromLine}
     */
    public GitFileBlame getBlame(
            @Nullable String rev,
            @NonNull String filePath,
            @Nullable Integer fromLine,
            @Nullable Integer toLine,
            boolean exact) {
        BlameMatching matching = exact ? BlameMatching.EXACT : BlameMatching.authorship(normalizePath(filePath));
        return getBlame(rev, filePath, fromLine, toLine, matching);
    }

    /**
     * The blame with the matching spelled out — what both public forms come down to, and a test's
     * way of trying each switch alone.
     */
    GitFileBlame getBlame(
            @Nullable String rev,
            @NonNull String filePath,
            @Nullable Integer fromLine,
            @Nullable Integer toLine,
            BlameMatching matching) {
        GitBlame.requireOrdered(fromLine, toLine);
        GitBlameRunner.@Nullable Range range =
                fromLine == null && toLine == null ? null : new GitBlameRunner.Range(fromLine, toLine);
        String at = snapshot(rev);
        String normalized = normalizePath(filePath);
        return at == null ? blame.blame(normalized, range, matching) : blame.blameAt(at, normalized, range, matching);
    }

    /**
     * The commit where {@code query} entered line {@code line} of the file — a walk back through
     * the versions of that line; see {@link LineOriginTracer}.
     *
     * @param rev the commit the line is numbered in; {@code null} or blank — the working tree
     * @param line 1-based
     * @param query the substring, matched literally and case-insensitively
     * @throws IllegalArgumentException for a line below 1 or a blank query, and as {@link
     *     #getBlame(String, String)}
     */
    public GitLineOrigin getLineOrigin(
            @Nullable String rev, @NonNull String filePath, int line, @NonNull String query) {
        if (line < 1) {
            throw new IllegalArgumentException("line must be 1 or more: " + line);
        }
        if (query.isBlank()) {
            throw new IllegalArgumentException("query must not be blank");
        }
        return origins.origin(normalizePath(filePath), snapshot(rev), line, query);
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
     * <p>With {@code rev} — the file as of that commit; see {@link #fileContentAt}.
     *
     * @param rev the commit to read; {@code null} or blank — the working tree
     * @param filePath path relative to repo root
     * @param fromLine first line to return (1-based, inclusive); null for start of file
     * @param toLine last line to return (1-based, inclusive); null for end of file
     */
    public GitFileContent getFileContent(
            @Nullable String rev, @NonNull String filePath, @Nullable Integer fromLine, @Nullable Integer toLine) {
        String at = snapshot(rev);
        return at == null
                ? readContent(filePath, fromLine, toLine, null)
                : fileContentAt(at, filePath, fromLine, toLine);
    }

    /**
     * @param vouchedTracked non-null when the caller already resolved {@code filePath} against a
     *     previously-read index (e.g. {@link #browsePath}'s {@code tracked} list) — skips the
     *     redundant {@link VisibleFiles#require} re-check that would otherwise re-read the index,
     *     and answers the returned {@code tracked} with what that index said.
     */
    private GitFileContent readContent(
            String filePath, @Nullable Integer fromLine, @Nullable Integer toLine, @Nullable Boolean vouchedTracked) {
        FileBytes fb = readTrackedFile(filePath, vouchedTracked);
        return FileViews.of(fb.path(), fb.tracked(), null, fb.bytes(), fb.size(), fromLine, toLine);
    }

    /**
     * The file as of a commit — {@code git show <rev>:<path>}. Reads the commit's tree, never the
     * working copy, so an uncommitted edit on disk does not show through and a file deleted since
     * that commit still reads.
     *
     * <p>Serves whatever the commit holds, without the working-tree gate {@code getFileContent}
     * applies: a path in a commit is committed history by definition, and {@link #getCommitDiff}
     * already hands that same content out as a patch. Files bigger than 512 KB and binaries are
     * reported the same way as on disk.
     *
     * @param commitHash any revision git resolves: a full or short hash, a branch, a tag, {@code
     *     HEAD~2}
     * @param filePath path relative to repo root, as it was spelled in that commit
     * @param fromLine first line to return (1-based, inclusive); null for start of file
     * @param toLine last line to return (1-based, inclusive); null for end of file
     * @throws IllegalArgumentException if the revision is unknown or ambiguous, the commit holds no
     *     file at that path, or the object there is too large to read at all (32 MB — a blob that
     *     size is an artefact somebody committed, not source)
     */
    private GitFileContent fileContentAt(
            String commitHash, String filePath, @Nullable Integer fromLine, @Nullable Integer toLine) {
        String normalized = normalizePath(filePath);
        CommitFiles.Blob blob = CommitFiles.read(repository, commitHash.strip(), normalized);
        return FileViews.of(normalized, true, blob.commit(), blob.bytes(), blob.size(), fromLine, toLine);
    }

    /** Convenience overload: full file, no range. */
    public GitFileContent getFileContent(@NonNull String filePath) {
        return getFileContent(null, filePath, null, null);
    }

    /**
     * Returns a structural outline (classes, methods, functions, ...; headings for markdown) of a
     * source file without its full text. Backed by tree-sitter when available, regex otherwise, and
     * by the document section parser for markdown; the {@code parser} field reports which was used.
     *
     * <p>Serves the untracked files {@code allow-globs} admit on the same terms {@link
     * #getFileContent} does, and says which it answered about in the outline's {@code tracked}
     * field.
     *
     * <p>With {@code rev} — the outline of the file as of that commit, read from the commit's tree
     * on the terms of {@link #fileContentAt}.
     *
     * @param rev the commit to read; {@code null} or blank — the working tree
     * @param filePath path relative to repo root
     * @throws IllegalArgumentException if the file is binary or its language is not supported for
     *     outlining ({@link OutlineService#supportedLanguages}), or — with {@code rev} — the revision
     *     is unknown or holds no such file
     */
    public GitFileOutline getFileOutline(@Nullable String rev, @NonNull String filePath) {
        String at = snapshot(rev);
        if (at != null) {
            String normalized = normalizePath(filePath);
            CommitFiles.Blob blob = CommitFiles.read(repository, at.strip(), normalized);
            return FileOutlines.of(outlineService, normalized, true, blob.bytes());
        }
        FileBytes fb = readTrackedFile(filePath);
        return FileOutlines.of(outlineService, fb.path(), fb.tracked(), fb.bytes());
    }

    // ── Bytes (binary files included) ───────────────────────────────────────

    /** Size, binary flag and language of a served file, without its content; see {@link RepoBytes#info}. */
    public GitFileInfo getFileInfo(@NonNull String filePath) {
        return bytes.info(filePath);
    }

    /** A window of a served file's raw bytes, binary files included; see {@link RepoBytes#window}. */
    public GitFileBytes getFileBytes(@NonNull String filePath, long offset, long length) {
        return bytes.window(filePath, offset, length);
    }

    /**
     * A whole file for the browser to show — the working tree's, or with {@code rev} the commit's;
     * see {@link RepoBytes#whole}.
     */
    public GitFileBytes getRawFile(@Nullable String rev, @NonNull String filePath) {
        String at = snapshot(rev);
        return at == null ? bytes.whole(filePath) : bytes.wholeAt(at, filePath);
    }

    /** SHA-256 of a served file's bytes, lowercase hex; see {@link RepoBytes#sha256}. */
    public String hashFile(@NonNull String filePath) {
        return bytes.sha256(filePath);
    }

    /**
     * Bytes of a validated, visible file and whether git tracks it — straight off the gate that
     * cleared the read, so the answer costs no second index lookup.
     */
    private record FileBytes(String path, byte[] bytes, long size, boolean tracked) {}

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
        return new FileBytes(normalized, bytes, size, tracked);
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

    // ── Branches and the user's commands ────────────────────────────────────

    /**
     * The named revisions this repository has — what the file browser offers as snapshots to look
     * at. Branch names come from {@link #branchStatus()} too, but that answer is about the working
     * tree's own position; this one is about what may be opened.
     */
    public GitRefs refs() {
        return branches.refs();
    }

    /**
     * Which branch the working tree is on, how far it has drifted from its upstream, and what other
     * branches there are — see {@link GitBranchStatus}.
     *
     * <p>A plain read, available on every project: the panel says which branch it is showing
     * whether or not anyone may change it. The counters are as fresh as the last fetch — nothing
     * here contacts a remote.
     */
    public GitBranchStatus branchStatus() {
        return branches.status();
    }

    /**
     * Updates the remote-tracking refs of this repository ({@code git fetch --prune --no-tags}) —
     * what makes the "behind" counter mean anything, since it is read off those refs.
     *
     * <p>Writes nothing in the working tree: the branch stays where it is, and a pull remains a
     * separate, explicit step. Guarded by {@code GitRegistry.requireGitCommands} like every other
     * command a user runs.
     */
    public GitCommandResult fetch() {
        return commands.fetch();
    }

    /**
     * Brings in what the upstream has ({@code git pull --ff-only}) — a fast-forward and nothing
     * else.
     *
     * <p>Diverged histories are refused rather than merged: the merge commit a plain pull would
     * write is a decision, and nobody makes a decision by clicking "update". The user merges
     * deliberately, and a merge that ends in conflict has {@link #abortMerge()} as its way out.
     */
    public GitCommandResult pull() {
        return commands.pull();
    }

    /**
     * Publishes this branch to its remote ({@code git push}), setting the upstream on a branch that
     * tracks nothing yet when the repository leaves no doubt which remote that is.
     *
     * <p>Never forced, in any spelling: a rejected push means the remote moved, and overwriting
     * someone else's history is not something a click may do. Guarded by its own grant ({@code
     * git-commands.push-enabled}) — it is the one command that sends this repository's content
     * outside the deployment.
     */
    public GitCommandResult push() {
        return commands.push();
    }

    /**
     * Moves the checkout onto {@code branch}, creating it at the current commit when {@code create}
     * — {@code git switch} and {@code git switch -c}.
     *
     * <p>Never forced: a switch that would overwrite uncommitted changes is refused, naming the
     * files, and the user stashes or commits them first. Losing a working tree to a click is the
     * one outcome this whole feature must not have.
     */
    public GitCommandResult switchBranch(@NonNull String branch, boolean create) {
        return commands.switchBranch(branch, create);
    }

    /**
     * Puts the tracked changes aside ({@code git stash push}) — the way out of "the switch you
     * asked for would overwrite these files".
     */
    public GitCommandResult stashPush() {
        return commands.stashPush();
    }

    /**
     * Brings the newest stash back and drops it ({@code git stash pop}). A pop that conflicts keeps
     * the stash: the work would otherwise exist nowhere but a conflicted working tree.
     */
    public GitCommandResult stashPop() {
        return commands.stashPop();
    }

    /**
     * Commits the tracked changes with {@code message} — the same files the panel lists under
     * "changes", including the edits the assistant staged.
     *
     * <p>Refused when the repository has no commit identity: it belongs to whoever owns the
     * deployment, and a history signed with a made-up address is worse than a refusal.
     */
    public GitCommandResult commit(@NonNull String message) {
        return commands.commit(message);
    }

    /**
     * Commits only the given paths ({@code git commit -- <paths>}) — what the commit dialog sends
     * when the user unticks some of the changed files.
     *
     * <p>An empty list means the same as {@link #commit(String)}: everything tracked. A selection
     * is staged path by path and committed with those paths alone, so the edits the assistant left
     * in the index do not ride along uninvited.
     */
    public GitCommandResult commit(@NonNull String message, @NonNull List<String> paths) {
        return commands.commit(message, paths);
    }

    /**
     * Restores one tracked file to its committed state ({@code git restore}) — the only command
     * here that destroys work rather than moving it, which is why it takes a path and never a whole
     * tree.
     */
    public GitCommandResult discard(@NonNull String filePath) {
        return commands.discard(filePath);
    }

    /**
     * Leaves an unfinished merge ({@code git merge --abort}) — the guaranteed way out of a
     * conflicted working tree, and the reason a conflicted pull is not a dead end.
     */
    public GitCommandResult abortMerge() {
        return commands.abortMerge();
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
            @NonNull String filePath, @NonNull String oldString, @NonNull String newString, boolean replaceAll) {
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
     * @see GitWriter#previewEdited
     */
    public String previewEdited(@NonNull String filePath, @NonNull List<TextEdit> edits) {
        return writer.previewEdited(filePath, edits);
    }

    /**
     * @see GitWriter#requireDeletable
     */
    public void requireDeletable(@NonNull String filePath, @NonNull String expectedContent) {
        writer.requireDeletable(filePath, expectedContent);
    }

    /**
     * @see GitWriter#deleteFile
     */
    public void deleteFile(@NonNull String filePath, @NonNull String expectedContent) {
        writer.deleteFile(filePath, expectedContent);
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
        return readContent(file.path(), null, null, file.tracked());
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
     * <p>A caller that only cares about what the next commit will carry asks for the tracked half
     * alone — see {@link #getUncommittedChanges(boolean, boolean, List)}.
     *
     * @param includePatch whether to include unified diff text for modified files
     */
    public List<GitDiffEntry> getUncommittedChanges(boolean includePatch) {
        return changes.list(includePatch, true, List.of());
    }

    /**
     * The same list narrowed to one file — what the files panel asks for when it opens a single
     * change: the list it already drew needs no patches, and computing every file's patch to show
     * one of them is the whole working tree's diff per click.
     *
     * <p>The path is matched as itself, not as a pathspec: the panel asks by whatever the URL has
     * selected, a directory among it, and a prefix match would answer that with the first changed
     * file under it — a diff shown under a name that is not its own.
     *
     * @param onlyPath a file path, or {@code null}/blank for the whole working tree
     */
    public List<GitDiffEntry> getUncommittedChanges(boolean includePatch, @Nullable String onlyPath) {
        return changes.list(
                includePatch,
                true,
                onlyPath == null || onlyPath.isBlank() ? List.of() : List.of(Pathspec.exact(normalizePath(onlyPath))));
    }

    /**
     * The same list narrowed to the paths asked for, matched as git matches a pathspec ({@link
     * Pathspec}): a name without a wildcard is a path prefix, so {@code docs} means everything
     * under {@code docs/}, and a wildcard crosses {@code /}, so {@code *.java} reaches every {@code
     * .java} in the tree. Several filters combine as OR, the way several pathspecs do on a git
     * command line.
     *
     * <p>A renamed file matches under either of its names: the model asking about the path it knows
     * gets the rename rather than nothing.
     *
     * @param pathFilters paths, directories or globs to keep; empty for the whole working tree
     * @throws IllegalArgumentException if a filter is not a spellable repo-relative path
     */
    public List<GitDiffEntry> getUncommittedChanges(boolean includePatch, List<String> pathFilters) {
        return getUncommittedChanges(includePatch, true, pathFilters);
    }

    /**
     * The same list with the untracked half switchable: with {@code includeUntracked=false} the
     * answer is the tracked changes alone — exactly what the next commit will carry, and nothing
     * about the {@code allow-globs} area that will stay outside it whatever happens.
     *
     * <p>Not a filter over the result but work never done: each admitted file is read to count its
     * lines, and with {@code includePatch} its whole content becomes the patch. The status scan
     * that names them is shared with the tracked half and happens either way.
     *
     * @param includeUntracked also list the untracked files this project's {@code allow-globs}
     *     admit, under status {@code U}
     * @param pathFilters paths, directories or globs to keep; empty for the whole working tree
     * @throws IllegalArgumentException if a filter is not a spellable repo-relative path
     */
    public List<GitDiffEntry> getUncommittedChanges(
            boolean includePatch, boolean includeUntracked, List<String> pathFilters) {
        return changes.list(
                includePatch,
                includeUntracked,
                pathFilters.stream()
                        .map(filter -> filter == null ? "" : filter.strip())
                        // "." and "./" name the repo root, which normalizePath refuses and which
                        // means here what an omitted filter means: the whole working tree.
                        .filter(filter -> !filter.isEmpty() && !filter.equals(".") && !filter.equals("./"))
                        .map(filter -> Pathspec.of(normalizePath(filter)))
                        .filter(Objects::nonNull)
                        .toList());
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

    /**
     * Лежит ли за путём файл, который можно открыть. Каталог и символьная ссылка сюда не проходят
     * по тому же правилу, по которому не попадают в листинги: показанный, но не открывающийся путь
     * — отказ в ответ на клик.
     */
    private boolean fileExists(String relativePath) {
        return Files.isRegularFile(paths.resolve(relativePath), LinkOption.NOFOLLOW_LINKS);
    }
}
