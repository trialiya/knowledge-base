package io.github.trialiya.kb.service.file;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Path policy for one repository: how a repo-relative path is spelled, which spellings are refused,
 * and where such a path is allowed to land on disk.
 *
 * <p>Split out of {@link GitService} because none of it needs Git: the rules are about strings and
 * about the working tree's boundary, and they are what every read and write goes through first.
 */
final class RepoPaths {

    private static final Pattern SAFE_GIT_RELATIVE_PATH =
            Pattern.compile("^[\\p{L}\\p{N}._/\\- ]+$");

    /** File names to always exclude from results (OS/IDE junk). */
    private static final Set<String> IGNORED_FILES =
            Set.of(".DS_Store", "Thumbs.db", "desktop.ini", ".directory");

    /** File extensions to always exclude from results. */
    private static final Set<String> IGNORED_EXTENSIONS =
            Set.of(
                    ".class", ".jar", ".war", ".ear", ".o", ".so", ".dylib", ".dll", ".exe", ".pyc",
                    ".pyo", ".swp", ".swo", ".bak", ".tmp", ".orig");

    private final Path root;

    /**
     * {@link #root} with every symlink in it resolved. Comparison base for {@link #confine}: the
     * repository itself may legitimately live behind a symlinked parent (a {@code /tmp} → {@code
     * /private/tmp} style mount), in which case real paths of its own files would never start with
     * the textual {@link #root}.
     */
    private final Path realRoot;

    RepoPaths(Path root) {
        this.root = root;
        try {
            this.realRoot = root.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve repository path: " + root, e);
        }
    }

    /** Absolute, normalized path of the working tree. */
    Path root() {
        return root;
    }

    /** Where a normalized repo-relative path lands, without asking whether it may. */
    Path resolve(String normalized) {
        return root.resolve(normalized).normalize();
    }

    /** The repo-relative, forward-slash spelling of an absolute path inside the tree. */
    String relativize(Path absolute) {
        return toForwardSlashes(root.relativize(absolute).toString());
    }

    /**
     * Resolves a repo-relative path to its absolute location and confirms it really is inside the
     * working tree — textually first, then through the filesystem.
     *
     * <p>The textual check ({@link Path#normalize()} plus a prefix comparison) is not enough on its
     * own: it rewrites the path as a string and knows nothing about symlinks. A <em>tracked</em>
     * symlink pointing outside the repository — or a symlinked directory anywhere along the path —
     * passes it, while the read or write itself lands wherever the link points. Resolving the
     * deepest component that actually exists closes that: for an existing file it is the file
     * itself (final link included), for a path being created it is the nearest existing ancestor,
     * which is exactly what {@code createFile} needs.
     *
     * @return the absolute, normalized path — link-resolution is only the check, callers keep
     *     reading and writing through the path the repository names
     */
    Path confine(String normalized) {
        Path absolute = resolve(normalized);
        if (!absolute.startsWith(root)) {
            throw new IllegalArgumentException("Path traversal not allowed: " + normalized);
        }
        for (Path probe = absolute; probe != null; probe = probe.getParent()) {
            Path real;
            try {
                real = probe.toRealPath();
            } catch (IOException ignored) {
                // Not on disk yet (createFile) or a dangling symlink — ask its parent instead.
                continue;
            }
            if (!real.startsWith(realRoot)) {
                throw new IllegalArgumentException(
                        "Path escapes the repository via a symlink: " + normalized);
            }
            return absolute;
        }
        // Unreachable in practice: the repository root itself always resolves.
        return absolute;
    }

    /**
     * The one spelling of a repo-relative path: backslashes turned round, and {@code ./} and
     * doubled slashes collapsed, so {@code "./docs//a.md"} and {@code "docs/a.md"} are the same
     * string everywhere.
     *
     * <p>Git's index is keyed on the canonical form, so before this every entry point disagreed
     * with itself: {@code getFileContent("./docs/a.md")} answered "File not found" for a file that
     * is plainly there, and {@code createFile} wrote the file to disk and only then failed to stage
     * it — reporting a .gitignore rule that does not exist. Callers that match a path against a
     * pattern need it too: a glob written the obvious way ({@code "secrets/**"}) does not match
     * {@code "./secrets/key.pem"}, so any policy checked on an uncanonical path checks nothing.
     *
     * <p>Validation runs on the raw form first, on purpose. Canonicalising {@code "/etc/passwd"}
     * would strip nothing but canonicalising {@code "a/../../etc"} would resolve a traversal this
     * method must instead refuse, so the refusals happen while the path still says what the caller
     * wrote. What is dropped afterwards — {@code "."} and empty segments — cannot change where a
     * path points.
     *
     * @throws IllegalArgumentException if the path is unsafe, or names nothing once collapsed
     */
    static String normalize(@NonNull String filePath) {
        String forward = toForwardSlashes(filePath.strip());
        requireSafe(forward);
        if (!forward.contains("./")
                && !forward.contains("//")
                && !forward.endsWith("/.")
                && !forward.endsWith("/")
                && !forward.equals(".")) {
            return forward;
        }
        StringBuilder canonical = new StringBuilder(forward.length());
        for (String segment : forward.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (!canonical.isEmpty()) {
                canonical.append('/');
            }
            canonical.append(segment);
        }
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException("Path must name a file: " + filePath);
        }
        return canonical.toString();
    }

    /**
     * A directory path as the tree tools spell it: the repo root is {@code ""}, and a leading or
     * trailing slash is not a different directory.
     */
    static String normalizeDir(@Nullable String sub) {
        if (sub == null || sub.isBlank()) {
            return "";
        }
        String s = toForwardSlashes(sub.strip()).replaceAll("^/+|/+$", "");
        if (s.contains("..")) {
            throw new IllegalArgumentException("Path traversal not allowed");
        }
        return s;
    }

    private static void requireSafe(String path) {
        if (path.isBlank()) {
            throw new IllegalArgumentException("Path must not be blank");
        }
        if (path.startsWith("/")
                || path.startsWith("-")
                || path.contains("..")
                || path.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
        if (!SAFE_GIT_RELATIVE_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Path contains unsupported characters: " + path);
        }
    }

    static String toForwardSlashes(String path) {
        return path.replace('\\', '/');
    }

    /** The last segment of a repo-relative path. */
    static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    static boolean isInsideGitDir(String normalized) {
        return normalized.equals(".git") || normalized.startsWith(".git/");
    }

    /** Returns {@code true} for OS/IDE artefacts that should never appear in results. */
    static boolean isJunkFile(String path) {
        String name = fileName(path);
        if (IGNORED_FILES.contains(name)) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return dot >= 0 && IGNORED_EXTENSIONS.contains(name.substring(dot).toLowerCase());
    }

    /** Index of the first {@code *} or {@code ?} in a glob, or {@code -1} when it has none. */
    static int indexOfWildcard(String glob) {
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' || c == '?') {
                return i;
            }
        }
        return -1;
    }
}
