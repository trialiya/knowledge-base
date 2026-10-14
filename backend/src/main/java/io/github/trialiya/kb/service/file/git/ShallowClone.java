package io.github.trialiya.kb.service.file.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Repository;

/**
 * Where a shallow clone's history is cut: the commits git's {@code shallow} file names. Git and
 * JGit both read such a commit as parentless, so blame and every walk back stop on it — and
 * credit it with every line older than the clone. A line that reached one of these commits is
 * "this old or older", not "written here".
 *
 * <p>The file is read here rather than through JGit's {@code ObjectDatabase.getShallowCommits()}:
 * that one caches in fields with no synchronisation, and a request racing another on a changed
 * file can be handed {@code null}.
 */
final class ShallowClone {

    private ShallowClone() {}

    /**
     * The boundary commits' full hashes; empty for a complete clone, including one a {@code fetch
     * --unshallow} has just completed (the file is gone). Read from the common git directory, not
     * the worktree's own: a linked worktree has a git directory apart, the {@code shallow} file is
     * shared.
     *
     * @throws UncheckedIOException if the file is there but cannot be read — answering as if the
     *     clone were complete would credit the cut commit with every older line
     */
    static Set<String> boundaries(Repository repository) {
        Path shallow = repository.getCommonDirectory().toPath().resolve("shallow");
        try {
            return Files.readAllLines(shallow, StandardCharsets.US_ASCII).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
        } catch (NoSuchFileException e) {
            return Set.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + shallow, e);
        }
    }
}
