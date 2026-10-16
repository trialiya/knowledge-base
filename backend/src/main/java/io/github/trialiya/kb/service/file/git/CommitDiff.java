package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.errors.AmbiguousObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.jspecify.annotations.Nullable;

/**
 * One commit read with its changes — the commit diff the model's {@code getCommitDiff} and the
 * browser's Commit tab share. {@link Diffs} turns the result into DTOs; reading the commit and
 * telling an unknown revision from a broken repository is this class's.
 */
final class CommitDiff {

    private CommitDiff() {}

    /**
     * One commit with the files it changed against its first parent — the empty tree for a root
     * commit, as {@code git diff-tree --root} reads it.
     *
     * @param filePath narrows the diff itself — the tool's reading of a path, renames included
     * @param only keeps the entry reported under this path, found among the whole commit's entries
     * @param includeParents name the parents — for the browser's Commit tab, not for the model
     */
    static GitCommit of(
            Repository repository,
            String hash,
            boolean includePatch,
            @Nullable String filePath,
            boolean includeBody,
            @Nullable String only,
            boolean includeParents) {
        try (RevWalk revWalk = new RevWalk(repository);
                ObjectReader reader = repository.newObjectReader()) {
            RevCommit commit = revWalk.parseCommit(CommitFiles.commitOf(repository, hash));
            RevCommit parent = commit.getParentCount() > 0 ? revWalk.parseCommit(commit.getParent(0)) : null;

            // Without the empty tree the very first commit of a repo would show no files at all.
            AbstractTreeIterator oldTree = parent == null ? new EmptyTreeIterator() : tree(reader, parent);
            List<GitDiffEntry> entries =
                    Diffs.scan(repository, oldTree, tree(reader, commit), includePatch, filePath, only, null);
            return Diffs.toGitCommit(commit, entries, reader, includeBody, includeParents);
        } catch (MissingObjectException | IncorrectObjectTypeException e) {
            throw new IllegalArgumentException("Commit not found: " + hash, e);
        } catch (AmbiguousObjectException e) {
            throw new IllegalArgumentException("Ambiguous commit reference: " + hash, e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed reading commit: " + hash, e);
        }
    }

    /** A commit's tree, ready to diff. */
    static AbstractTreeIterator tree(ObjectReader reader, RevCommit commit) throws IOException {
        CanonicalTreeParser parser = new CanonicalTreeParser();
        parser.reset(reader, commit.getTree());
        return parser;
    }
}
