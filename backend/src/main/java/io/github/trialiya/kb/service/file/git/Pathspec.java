package io.github.trialiya.kb.service.file.git;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A caller's {@code pathGlob}, compiled to match the way git's own pathspec matches.
 *
 * <p>Needed because the untracked grep run spends its pathspec slot on the project's allow-glob
 * roots — pathspecs combine as OR, so passing the caller's glob alongside them would widen the
 * search instead of narrowing it. Re-applying it by hand only works if it means the same thing in
 * both runs, so this reproduces git's rules rather than Ant's, which differ on both counts that
 * matter here. A pathspec with no wildcard in it is a path <em>prefix</em>, so {@code notes} means
 * everything under {@code notes/}; and a wildcard crosses {@code /} freely, so {@code src/*.java}
 * reaches {@code src/a/b/C.java} and {@code *.java} — the tool's own documented example — reaches
 * every {@code .java} in the tree. Ant says no to both.
 */
record Pathspec(@Nullable Pattern pattern, String literal) {

    /** {@code null} for "no glob given" — matches everything. */
    static @Nullable Pathspec of(@Nullable String glob) {
        if (glob == null) {
            return null;
        }
        if (RepoPaths.indexOfWildcard(glob) < 0) {
            return new Pathspec(null, glob);
        }
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                // git's wildmatch runs without WM_PATHNAME here, so both cross '/', and '**'
                // falls out of '*' repeated rather than needing a rule of its own.
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '[' -> {
                    int close = glob.indexOf(']', i + 1);
                    if (close < 0) {
                        regex.append("\\[");
                    } else {
                        // Java's class syntax is git's, save for the negation character.
                        String body = glob.substring(i + 1, close);
                        regex.append('[')
                                .append(body.startsWith("!") ? "^" + body.substring(1) : body)
                                .append(']');
                        i = close;
                    }
                }
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return new Pathspec(Pattern.compile(regex.toString()), glob);
    }

    boolean matches(String path) {
        if (pattern == null) {
            return path.equals(literal) || path.startsWith(literal + "/");
        }
        return pattern.matcher(path).matches();
    }
}
