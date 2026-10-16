package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.StatusCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.jspecify.annotations.Nullable;

/**
 * Uncommitted changes: the tracked files diffed against HEAD, plus the untracked files the
 * project's {@code allow-globs} admit. {@code GitService.getUncommittedChanges} is the public
 * surface and says what each half means; this class is how the list is put together.
 */
@Slf4j
final class WorkingTreeChanges {

    private final RepoPaths paths;
    private final Repository repository;
    private final Git git;
    private final VisibleFiles visible;

    WorkingTreeChanges(RepoPaths paths, Repository repository, Git git, VisibleFiles visible) {
        this.paths = paths;
        this.repository = repository;
        this.git = git;
        this.visible = visible;
    }

    /**
     * The changes the {@code wanted} pathspecs keep — all of them when empty; what each half means
     * is on {@code GitService.getUncommittedChanges}.
     */
    List<GitDiffEntry> list(boolean includePatch, boolean includeUntracked, List<Pathspec> wanted) {
        Status status = status(wanted);

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
                    // Переименование — это пара из удаления и добавления, поэтому искать его
                    // незачем, когда сканировать нечего кроме правок: детектор всё равно грузит
                    // содержимое кандидатов, а пар среди одних правок не бывает. Незакрытый
                    // конфликт слияния тут заодно с ними: чем он окажется в сравнении с HEAD,
                    // по набору из status не видно.
                    formatter.setDetectRenames(!status.getAdded().isEmpty()
                            || !status.getRemoved().isEmpty()
                            || !status.getMissing().isEmpty()
                            || !status.getConflicting().isEmpty());
                    formatter.setPathFilter(PathFilterGroup.createFromStrings(changedPaths));

                    for (DiffEntry entry : formatter.scan(oldTree, newTree)) {
                        // The scan still covers every changed path even when one was asked for:
                        // rename detection needs both sides, and narrowing the path filter would
                        // report a renamed file as an unrelated add. Both sides are matched so
                        // that a rename opens under either of its names.
                        if (!admits(wanted, entry.getNewPath()) && !admits(wanted, entry.getOldPath())) continue;
                        // Мусорный путь отсеивается до разбора: собрать его патч, чтобы тут же
                        // его выбросить, — работа целиком впустую.
                        if (RepoPaths.isJunkFile(Diffs.reportedPath(entry))) continue;
                        entries.add(Diffs.toGitDiffEntry(entry, formatter, includePatch, patchOut));
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("Failed to diff working tree against HEAD", e);
            }
        }

        if (!includeUntracked) {
            return entries;
        }

        // Straight off the status: what git already reported as untracked is by definition
        // untracked and not ignored, so the globs are the only question left to ask about it —
        // walking the allow-glob area again would answer nothing this does not.
        status.getUntracked().stream()
                .filter(path -> admits(wanted, path))
                .filter(path -> !RepoPaths.isJunkFile(path))
                .filter(visible::matchesAllowGlobs)
                .sorted()
                .forEach(path -> entries.add(untrackedDiffEntry(path, includePatch)));

        return entries;
    }

    /**
     * The working tree's status, read of one path when the caller asked about one path and nothing
     * about that path can turn out to be half of a rename.
     *
     * <p>Status is the expensive half of this call — it stats the whole working tree, and the diff
     * that follows only re-reads the paths it named. A single-file question (the files panel opens
     * one change, a tool asks about one path) does not need the rest of the tree stat'ed, and git's
     * own {@code status -- <path>} narrows the same way.
     *
     * <p>The exception is a rename: it is a pair, and a pair is only visible when both of its
     * halves are in the same scan. So a narrowed answer is kept only while it holds nothing added,
     * removed or missing — a modification can never be one half of a rename, and everything else
     * falls back to the full status the rename detector needs. A pathspec with a wildcard is not
     * narrowed at all: git resolves those against the whole tree anyway.
     */
    private Status status(List<Pathspec> wanted) {
        String only = wanted.size() == 1 ? wanted.get(0).literal() : null;
        if (only != null && RepoPaths.indexOfWildcard(only) < 0) {
            Status narrow = statusOf(only);
            if (narrow.getAdded().isEmpty()
                    && narrow.getRemoved().isEmpty()
                    && narrow.getMissing().isEmpty()) {
                return narrow;
            }
        }
        return statusOf(null);
    }

    /**
     * @param only a path or path prefix to limit the walk to, or null for the whole tree
     */
    private Status statusOf(@Nullable String only) {
        StatusCommand command = git.status();
        if (only != null) {
            command.addPath(only);
        }
        try {
            return command.call();
        } catch (GitAPIException e) {
            throw new IllegalStateException("Failed to compute working tree status", e);
        }
    }

    /**
     * Whether the caller's filters keep this path. No filters keeps everything — the unnarrowed
     * call is the common one, and it must not depend on any spec matching.
     */
    private static boolean admits(List<Pathspec> filters, @Nullable String path) {
        return filters.isEmpty() || (path != null && filters.stream().anyMatch(filter -> filter.matches(path)));
    }

    /**
     * An admitted untracked file as a whole-file {@code U}, see {@link Diffs#untracked}. An
     * oversized or unreadable file goes in as {@code null} and is listed without lines or patch.
     */
    private GitDiffEntry untrackedDiffEntry(String path, boolean includePatch) {
        byte @Nullable [] content;
        try {
            // Through RepoPaths.confine like every other read: an admitted untracked path may be a
            // symlink out of the repository, and the change list must not be the one place that
            // follows it.
            Path absolute = paths.confine(path);
            content = Files.size(absolute) > RepoFiles.MAX_FILE_SIZE ? null : Files.readAllBytes(absolute);
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Cannot read untracked file {} for the change list", path, e);
            content = null;
        }
        return Diffs.untracked(path, content, includePatch);
    }

    /** HEAD's tree, or an empty tree when the branch is unborn (no commits yet). */
    private AbstractTreeIterator headTreeIterator(ObjectReader reader) throws IOException {
        ObjectId headId = repository.resolve("HEAD");
        if (headId == null) {
            return new EmptyTreeIterator();
        }
        try (RevWalk revWalk = new RevWalk(repository)) {
            return CommitDiff.tree(reader, revWalk.parseCommit(headId));
        }
    }
}
