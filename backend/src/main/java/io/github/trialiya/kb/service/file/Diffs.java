package io.github.trialiya.kb.service.file;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;

/** Unified diffs as this service hands them out: bounded, and countable. */
final class Diffs {

    /** Truncate very large diffs. */
    static final int MAX_DIFF_LINES = 500;

    private Diffs() {}

    /** Caps a unified diff at {@value #MAX_DIFF_LINES} lines, marking it when it was cut. */
    static String truncate(String diff) {
        if (diff.lines().count() <= MAX_DIFF_LINES) {
            return diff;
        }
        return diff.lines().limit(MAX_DIFF_LINES).collect(Collectors.joining("\n"))
                + "\n... (truncated)";
    }

    record Stats(int additions, int deletions, String diff) {}

    /** Unified diff + added/removed line counts between two in-memory revisions of one file. */
    static Stats between(String before, String after) {
        RawText a = new RawText(before.getBytes(StandardCharsets.UTF_8));
        RawText b = new RawText(after.getBytes(StandardCharsets.UTF_8));
        EditList edits =
                DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                        .diff(RawTextComparator.DEFAULT, a, b);
        int add = 0;
        int del = 0;
        for (Edit edit : edits) {
            add += edit.getEndB() - edit.getBeginB();
            del += edit.getEndA() - edit.getBeginA();
        }
        var out = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.format(edits, a, b);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to format diff", e);
        }
        return new Stats(add, del, truncate(out.toString(StandardCharsets.UTF_8)));
    }
}
