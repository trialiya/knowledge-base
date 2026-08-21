package io.github.trialiya.kb.utils;

/**
 * The exact-match text replacement behind the {@code editFile} and {@code editDocument} tools:
 * {@code oldString} must occur exactly once (or {@code replaceAll} must say every occurrence is
 * meant), character-for-character.
 *
 * <p>Shared by the two because the contract — not the storage — is what makes the edit safe: a
 * model that can quote a unique fragment of the current text has demonstrably seen it, and a
 * fragment that no longer matches means the text moved under the caller. That doubles as the
 * optimistic concurrency check, which is why neither tool asks for a version or a prior read.
 */
public final class ExactEdit {

    private ExactEdit() {}

    /**
     * Result of a replacement.
     *
     * @param text the new full text
     * @param occurrences how many occurrences were replaced (1 unless {@code replaceAll})
     */
    public record Result(String text, int occurrences) {}

    /**
     * Rejects a fragment that cannot edit anything, whatever the text turns out to be — an empty
     * one, or one identical to its replacement.
     *
     * <p>Callable on its own so a caller that has to open something first (a file on disk) answers
     * a broken pair of arguments before spending the read on it, and reports the argument rather
     * than whatever the read happened to complain about.
     */
    public static void requireUsableFragment(String oldString, String newString) {
        if (oldString.isEmpty()) {
            throw new IllegalArgumentException("oldString must not be empty");
        }
        if (oldString.equals(newString)) {
            throw new IllegalArgumentException("oldString and newString are identical");
        }
    }

    /**
     * A fragment with the line endings of the text it has to match: {@code \r\n} when that text
     * uses them, {@code \n} when it does not.
     *
     * <p>A model quoting a fragment reproduces the characters it was shown, but not reliably the
     * invisible ones — and a mismatch there fails the exact match with an error that says "re-read
     * the content", which a re-read cannot fix, because what comes back is the same text again.
     * Callers that store text as they received it (a document body) put both fragments through
     * this; callers that normalise the text itself (a file read as LF) normalise the fragments the
     * same way instead.
     */
    public static String alignLineEndings(String text, String fragment) {
        String normalized = fragment.replace("\r\n", "\n");
        return text.contains("\r\n") ? normalized.replace("\n", "\r\n") : normalized;
    }

    /**
     * Replaces {@code oldString} with {@code newString} in {@code text}.
     *
     * @param target what is being edited, as the model should see it in an error ({@code
     *     "src/App.java"}, {@code "document id=42"})
     * @param rereadTool the tool an error tells the model to re-read with ({@code getFileContent},
     *     {@code getDocument})
     * @throws IllegalArgumentException when the fragment is empty, identical to its replacement,
     *     missing, or ambiguous — every message names what the model has to do next
     */
    public static Result replace(
            String text,
            String oldString,
            String newString,
            boolean replaceAll,
            String target,
            String rereadTool) {
        requireUsableFragment(oldString, newString);

        int occurrences = countOccurrences(text, oldString);
        if (occurrences == 0) {
            throw new IllegalArgumentException(
                    "oldString not found in "
                            + target
                            + ". Re-read the current content ("
                            + rereadTool
                            + ") and pass an exact, character-for-character fragment including"
                            + " whitespace.");
        }
        if (occurrences > 1 && !replaceAll) {
            throw new IllegalArgumentException(
                    "oldString occurs "
                            + occurrences
                            + " times in "
                            + target
                            + ". Extend it with surrounding lines to make it unique, or pass"
                            + " replaceAll=true to replace every occurrence.");
        }
        return new Result(text.replace(oldString, newString), occurrences);
    }

    /** Non-overlapping occurrences of {@code needle} — what {@code String.replace} would touch. */
    public static int countOccurrences(String text, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
