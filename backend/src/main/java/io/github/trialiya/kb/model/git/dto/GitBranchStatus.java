package io.github.trialiya.kb.model.git.dto;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where this working tree currently sits and what it could switch to — the answer behind the branch
 * indicator in the files panel and, later, behind the branch picker.
 *
 * <p>{@link #ahead()}/{@link #behind()} are read from the refs on disk, so they are as fresh as the
 * last fetch and no fresher. That is git's own answer and the honest one: nothing here contacts a
 * remote, and a panel that showed a live count would have to reach the network on every render.
 *
 * @param current the checked-out branch's short name, or the abbreviated commit when {@link
 *     #detached()} — never null, because a repository with an unborn branch still reports the name
 *     HEAD points at
 * @param detached whether HEAD names a commit rather than a branch. A checkout in this state
 *     accepts no commit that would survive a switch, so the UI says so instead of offering the
 *     operations that would strand work
 * @param unborn whether the branch has no commits yet — a fresh {@code git init}. There is nothing
 *     to be ahead or behind of, and no branch list to speak of
 * @param upstream short name of the branch this one tracks ({@code origin/main}), or null when it
 *     tracks nothing — then {@link #ahead()} and {@link #behind()} are zero and mean nothing
 * @param ahead commits this branch has that its upstream does not
 * @param behind commits the upstream has that this branch does not — what a pull would bring in
 * @param branches local branches, current one included, in git's own order (alphabetical); empty
 *     while the branch is unborn
 * @param dirty whether the working tree has uncommitted changes to tracked files. What decides
 *     whether a switch is offered plainly or with the question of what to do with them first — git
 *     refuses the switch itself, and being told before the click beats being told by a failure
 * @param merging whether a merge is in progress, conflicts included — the repository is not in
 *     {@code SAFE} state. While it is, {@code merge --abort} is the way out, and the panel says so
 *     rather than leaving a half-merged tree looking like an ordinary one
 * @param conflicts paths with unresolved conflicts; empty unless {@link #merging()}
 */
public record GitBranchStatus(
        String current,
        boolean detached,
        boolean unborn,
        @Nullable String upstream,
        int ahead,
        int behind,
        List<String> branches,
        boolean dirty,
        boolean merging,
        List<String> conflicts) {

    public GitBranchStatus {
        branches = branches == null ? List.of() : List.copyOf(branches);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
    }
}
