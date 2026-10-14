package io.github.trialiya.kb.service.file.git;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;

/**
 * Where a shallow clone's history is cut: the commits git's {@code shallow} file names. Git and
 * JGit both read such a commit as parentless, so blame and every walk back stop on it — and
 * credit it with every line older than the clone. A line that reached one of these commits is
 * "this old or older", not "written here".
 */
@Slf4j
final class ShallowClone {

    private ShallowClone() {}

    /**
     * The boundary commits' full hashes; empty for a complete clone. JGit's own reading of the file
     * — the one its walks cut parents by, kept until the file changes, and taken from the common
     * git directory, so a linked worktree sees its main repository's cut.
     *
     * <p>A file that cannot be read (a {@code fetch --unshallow} deleting it mid-read) answers
     * empty: the marks are a caveat on the answer, and losing them is better than losing the
     * answer.
     */
    static Set<String> boundaries(Repository repository) {
        try {
            return repository.getObjectDatabase().getShallowCommits().stream()
                    .map(ObjectId::name)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            log.warn("Cannot read the shallow commits of {}: {}", repository.getDirectory(), e.toString());
            return Set.of();
        }
    }
}
