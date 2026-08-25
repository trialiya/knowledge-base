package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitBranchStatus;
import java.io.IOException;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.BranchTrackingStatus;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.jspecify.annotations.Nullable;

/**
 * Which branch this repository is on, and what it could be on instead — the read half of the user's
 * git commands, kept apart from {@link GitService}'s file surface the way {@link GitWriter} keeps
 * the writes apart.
 *
 * <p>Reads only, and only from the refs on disk: no remote is contacted here. The upstream counters
 * are therefore as fresh as the last fetch, which is what native git reports too — a count that
 * quietly went to the network would turn opening a panel into a remote round trip.
 */
class GitBranches {

    /** Abbreviation length for a detached HEAD's commit, matching git's own default. */
    private static final int ABBREV_LEN = 7;

    private final Repository repository;
    private final Git git;

    GitBranches(Repository repository, Git git) {
        this.repository = repository;
        this.git = git;
    }

    /**
     * @see GitService#branchStatus()
     */
    GitBranchStatus status() {
        try {
            // A branch with no commits yet still has a name — HEAD is a symbolic ref to a branch
            // that does not exist. That is an unborn branch, not a detached HEAD, and the two
            // differ in what the panel may offer: nothing to switch away from versus nothing to
            // commit onto.
            boolean unborn = repository.resolve(Constants.HEAD) == null;
            Ref head = repository.exactRef(Constants.HEAD);
            boolean detached = !unborn && head != null && !head.isSymbolic();
            // Null only when HEAD itself is unreadable — a repository mid-creation, or one whose
            // HEAD was hand-edited. Nothing to report about it beyond that, and guessing a branch
            // name would be worse than saying the checkout is not on one.
            @Nullable String branch = repository.getBranch();
            if (branch == null) {
                return new GitBranchStatus("HEAD", true, unborn, null, 0, 0, List.of());
            }

            if (detached) {
                // The indicator wants what git shows in its own prompt, not the full hash.
                return new GitBranchStatus(
                        abbreviate(branch), true, false, null, 0, 0, localBranches());
            }
            @Nullable BranchTrackingStatus tracking =
                    unborn ? null : BranchTrackingStatus.of(repository, branch);
            return new GitBranchStatus(
                    branch,
                    false,
                    unborn,
                    tracking == null
                            ? null
                            : Repository.shortenRefName(tracking.getRemoteTrackingBranch()),
                    tracking == null ? 0 : tracking.getAheadCount(),
                    tracking == null ? 0 : tracking.getBehindCount(),
                    unborn ? List.of() : localBranches());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the branch status", e);
        }
    }

    private static String abbreviate(String hash) {
        return hash.length() > ABBREV_LEN ? hash.substring(0, ABBREV_LEN) : hash;
    }

    /** Local branches in git's order; a repository that cannot list them has none to offer. */
    private List<String> localBranches() {
        try {
            return git.branchList().call().stream()
                    .map(ref -> Repository.shortenRefName(ref.getName()))
                    .toList();
        } catch (GitAPIException e) {
            throw new IllegalStateException("Failed to list branches", e);
        }
    }
}
