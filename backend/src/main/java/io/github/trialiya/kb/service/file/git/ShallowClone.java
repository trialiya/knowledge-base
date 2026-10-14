package io.github.trialiya.kb.service.file.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.Repository;

/**
 * Where a shallow clone's history is cut: the commits git's {@code shallow} file names. Git and
 * JGit both read such a commit as parentless, so blame and every walk back stop on it — and
 * credit it with every line older than the clone. A line that reached one of these commits is
 * "this old or older", not "written here".
 */
final class ShallowClone {

    private ShallowClone() {}

    /**
     * The boundary commits' full hashes; empty for a complete clone. Read from the common git
     * directory, not the worktree's own: a linked worktree has a git directory apart, the
     * {@code shallow} file is shared.
     */
    static Set<String> boundaries(Repository repository) {
        Path shallow = repository.getCommonDirectory().toPath().resolve("shallow");
        if (!Files.isRegularFile(shallow)) {
            return Set.of();
        }
        try {
            return Files.readAllLines(shallow, StandardCharsets.US_ASCII).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + shallow, e);
        }
    }
}
