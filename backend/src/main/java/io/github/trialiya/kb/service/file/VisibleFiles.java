package io.github.trialiya.kb.service.file;

import io.github.trialiya.kb.model.project.Project;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.lib.Repository;
import org.springframework.util.AntPathMatcher;

/**
 * Which paths this project exposes, and what git knows about each — the Git index widened by the
 * project's {@code allow-globs}.
 *
 * <p>The one place that answers "may this path be served at all", so both the read tools ({@link
 * GitService}) and the write tools ({@link GitWriter}) ask the same question of the same code
 * rather than each keeping its own idea of what is visible.
 */
final class VisibleFiles {

    /**
     * Ant semantics for {@code Project#allowGlobs}, not {@code java.nio} glob: {@code notes/**} has
     * to match {@code notes/todo.md} and {@code notes/a/b.md} alike, and Ant is also what {@code
     * pathGlob} looks like elsewhere in the tool surface.
     */
    private static final AntPathMatcher GLOB_MATCHER = new AntPathMatcher();

    private final Project project;
    private final RepoPaths paths;
    private final Repository repository;

    /**
     * The directories {@code allow-globs} match under, so the working-tree walk behind {@link
     * #all()} covers those instead of the whole repository. Empty only when the project configures
     * no globs at all — {@code ProjectCatalog} rejects a glob without a root.
     */
    private final List<String> allowGlobRoots;

    VisibleFiles(Project project, RepoPaths paths, Repository repository) {
        this.project = project;
        this.paths = paths;
        this.repository = repository;
        this.allowGlobRoots = globRoots(project.allowGlobs());
    }

    /**
     * What the gate established about a path: where it is, and what the index said about it.
     *
     * @param tracked whether the index held the path at the moment the gate read it. Every {@link
     *     Resolved} comes from that one read, so a caller that needs both answers — a write, which
     *     must know whether staging is allowed — gets them without asking the index twice.
     */
    record Resolved(Path absolute, boolean tracked) {}

    /**
     * The gate in front of every read and every write: the path is confined to the working tree and
     * must be tracked, or an untracked file the project's {@code allow-globs} admit.
     *
     * <p>The refusal message is the same whether the path is untracked-but-present or genuinely
     * missing, so a caller can't use it to fingerprint which unrelated files (e.g. a gitignored
     * {@code .env}) happen to exist on disk.
     */
    Resolved require(String normalized) {
        // Security: confine to the repo before touching the filesystem.
        Path absolute = paths.confine(normalized);
        boolean tracked = isTracked(normalized);
        if (!tracked && !isUntrackedAllowed(normalized)) {
            throw new IllegalArgumentException("File not found: " + normalized);
        }
        return new Resolved(absolute, tracked);
    }

    boolean isTracked(String path) {
        // getEntry(path) returns the path's first index entry — stage 0 normally, stage 1+ while a
        // merge conflict is unresolved. Either way the file is tracked.
        return readIndex().getEntry(path) != null;
    }

    /**
     * The Git index as it is on disk right now.
     *
     * <p>Deliberately never cached in a field: {@code createFile} stages a file and then asks the
     * index whether the staging took, and a snapshot from before the {@code git add} would answer
     * about the wrong index. Within one operation the answer is passed along instead (see {@link
     * Resolved}), which is what keeps an edit at a single read.
     */
    private DirCache readIndex() {
        try {
            return repository.readDirCache();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read Git index: " + paths.root(), e);
        }
    }

    /**
     * All paths currently in the Git index, matching {@code git ls-files}'s default behaviour.
     * Files with an unresolved merge conflict have only stage-1..3 entries (no stage 0); they are
     * still tracked, so their stages collapse to a single de-duplicated path here (index entries
     * are sorted by path, so duplicates are adjacent and the set stays in ls-files order).
     */
    List<String> trackedPaths() {
        DirCache cache = readIndex();
        Set<String> tracked = LinkedHashSet.newLinkedHashSet(cache.getEntryCount());
        for (int i = 0; i < cache.getEntryCount(); i++) {
            tracked.add(cache.getEntry(i).getPathString());
        }
        return List.copyOf(tracked);
    }

    /**
     * Whether one of the project's {@code allow-globs} covers {@code normalized}.
     *
     * <p>Git's own metadata is never covered, whatever the globs say. Only this path can reach it —
     * the index lists nothing under {@code .git/} — so the exclusion belongs here, where every
     * consumer of the globs picks it up.
     */
    boolean matchesAllowGlobs(String normalized) {
        if (RepoPaths.isInsideGitDir(normalized)) {
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
        return matchesAllowGlobs(normalized) && Files.isRegularFile(paths.resolve(normalized));
    }

    /** The roots the untracked grep run is confined to. */
    List<String> allowGlobRoots() {
        return allowGlobRoots;
    }

    /**
     * Every path the read tools serve: the tracked ones from the index, plus — when the project
     * configures {@code allow-globs} — the untracked files those globs admit, in one deterministic
     * order (tracked first, then the admitted untracked ones sorted by path).
     */
    List<String> paths() {
        return all().paths();
    }

    /**
     * What the read tools see, and which part of it git knows about — from a single index read.
     *
     * <p>Listings need both halves at once: the paths to build the tree from, and the answer to
     * "does this one have history" for every node in it. Handing them out together is what keeps a
     * browse request at one {@code DirCache} read instead of one per question asked of it.
     *
     * @param paths every visible path, as {@link #paths()} describes them
     * @param tracked the subset of them that is in the index — a plain membership test, with no
     *     second meaning attached to it being empty (an unborn branch has no entries yet)
     */
    record Visible(List<String> paths, Set<String> tracked) {}

    Visible all() {
        List<String> tracked = trackedPaths();
        Set<String> trackedSet = Set.copyOf(tracked);
        if (project.allowGlobs().isEmpty()) {
            return new Visible(tracked, trackedSet);
        }
        List<String> admitted = admittedUntracked(trackedSet);
        if (admitted.isEmpty()) {
            return new Visible(tracked, trackedSet);
        }
        List<String> all = new ArrayList<>(tracked.size() + admitted.size());
        all.addAll(tracked);
        all.addAll(admitted);
        return new Visible(List.copyOf(all), trackedSet);
    }

    /**
     * The untracked files this project's {@code allow-globs} admit, sorted by path.
     *
     * <p>Walks the working tree rather than asking git, because git cannot answer: it prunes a
     * wholly-ignored directory and reports {@code build/} instead of the report files inside it,
     * which are the point of the feature. The walk starts at {@link #allowGlobRoots}, so its cost
     * is the size of the named area and not of the repository.
     *
     * @param tracked the index, already read by the caller
     */
    private List<String> admittedUntracked(Set<String> tracked) {
        if (project.allowGlobs().isEmpty()) {
            return List.of();
        }
        List<String> admitted = new ArrayList<>();
        for (String root : allowGlobRoots) {
            Path start = paths.resolve(root);
            // A wildcard-free glob names one file, and its "root" is that file: walking would skip
            // it and the path would read fine while being absent from every listing.
            if (Files.isRegularFile(start)) {
                if (!tracked.contains(root) && matchesAllowGlobs(root)) {
                    admitted.add(root);
                }
                continue;
            }
            if (!Files.isDirectory(start)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(start)) {
                walk.filter(Files::isRegularFile)
                        .map(p -> paths.relativize(p))
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
     * has one: {@code ProjectCatalog} refuses a configuration where it would not.
     *
     * <p>A root inside {@code .git} is dropped rather than walked: {@link #matchesAllowGlobs}
     * discards everything found there anyway, and the object database is the one directory in a
     * repository where a pointless full walk really costs something.
     */
    private static List<String> globRoots(List<String> globs) {
        Set<String> roots = new LinkedHashSet<>();
        for (String glob : globs) {
            int wildcard = RepoPaths.indexOfWildcard(glob);
            String root = wildcard < 0 ? glob : glob.substring(0, glob.lastIndexOf('/', wildcard));
            if (!RepoPaths.isInsideGitDir(root)) {
                roots.add(root);
            }
        }
        return List.copyOf(roots);
    }
}
