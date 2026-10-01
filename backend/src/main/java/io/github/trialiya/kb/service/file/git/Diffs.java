package io.github.trialiya.kb.service.file.git;

import io.github.trialiya.kb.model.git.dto.GitCommit;
import io.github.trialiya.kb.model.git.dto.GitDiffEntry;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.EditList;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.patch.FileHeader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.jspecify.annotations.Nullable;

/**
 * Commits and unified diffs as this service hands them out: JGit's objects mapped onto the API's
 * {@link GitCommit} and {@link GitDiffEntry}, with patches bounded and lines counted.
 */
final class Diffs {

    /** Truncate very large diffs. */
    static final int MAX_DIFF_LINES = 500;

    /**
     * Minimum length for abbreviated commit hashes, matching native git's own default (grows
     * automatically if ambiguous — see {@link
     * ObjectReader#abbreviate(org.eclipse.jgit.lib.AnyObjectId, int)}).
     */
    private static final int ABBREV_LEN = 7;

    /**
     * Status letter for an admitted untracked file in {@link GitService#getUncommittedChanges} — git's own
     * {@code A/M/D/R/C} say what the index holds, and this one says the index holds nothing.
     */
    private static final String UNTRACKED_STATUS = "U";

    private Diffs() {}

    /** Caps a unified diff at {@value #MAX_DIFF_LINES} lines, marking it when it was cut. */
    static String truncate(String diff) {
        if (diff.lines().count() <= MAX_DIFF_LINES) {
            return diff;
        }
        return diff.lines().limit(MAX_DIFF_LINES).collect(Collectors.joining("\n")) + "\n... (truncated)";
    }

    /** A unified diff split into the file's header lines and the hunks themselves. */
    record Parts(@Nullable String header, @Nullable String body) {}

    /**
     * Splits off the file header ({@code diff --git}, {@code index}, {@code --- a/…}, {@code +++
     * b/…}) so the API can hand it out beside the hunks: it describes the file, not its lines, and
     * a reader that already knows which file it is looking at has no use for it inside the code.
     *
     * <p>The boundary is the first {@code @@}: further down a patch such a line can be file
     * content, and before it there is nothing but the header. A patch without {@code @@} is not
     * split at all — it has no boundary but does have content (a binary-file notice), and the
     * content must not end up filed as metadata.
     */
    static Parts split(@Nullable String patch) {
        if (patch == null) {
            return new Parts(null, null);
        }
        int hunk = patch.startsWith("@@") ? 0 : patch.indexOf("\n@@") + 1;
        if (hunk <= 0) {
            return new Parts(null, patch);
        }
        String header = patch.substring(0, hunk).strip();
        return new Parts(header.isEmpty() ? null : header, patch.substring(hunk));
    }

    record Stats(int additions, int deletions, String diff) {}

    /** Unified diff + added/removed line counts between two in-memory revisions of one file. */
    static Stats between(String before, String after) {
        RawText a = new RawText(before.getBytes(StandardCharsets.UTF_8));
        RawText b = new RawText(after.getBytes(StandardCharsets.UTF_8));
        EditList edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
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

    static GitCommit toGitCommit(
            RevCommit commit, @Nullable List<GitDiffEntry> files, ObjectReader reader, boolean includeBody)
            throws IOException {
        return toGitCommit(commit, files, reader, includeBody, false);
    }

    static GitCommit toGitCommit(
            RevCommit commit,
            @Nullable List<GitDiffEntry> files,
            ObjectReader reader,
            boolean includeBody,
            boolean includeParents)
            throws IOException {
        PersonIdent author = commit.getAuthorIdent();
        OffsetDateTime date =
                author.getWhenAsInstant().atZone(author.getZoneId()).toOffsetDateTime();
        return new GitCommit(
                commit.getName(),
                reader.abbreviate(commit, ABBREV_LEN).name(),
                author.getName(),
                author.getEmailAddress(),
                date,
                commit.getShortMessage(),
                includeBody ? messageBody(commit) : null,
                files,
                includeParents
                        ? Arrays.stream(commit.getParents())
                                .map(RevCommit::getName)
                                .toList()
                        : null);
    }

    /**
     * Всё сообщение коммита после первой пустой строки, или {@code null}, если тела нет.
     *
     * <p>Режем по пустой строке, а не вычитанием {@link RevCommit#getShortMessage()} из полного
     * текста: короткое сообщение JGit склеивает перенесённый subject в одну строку через пробел, и
     * такой префикс в полном тексте уже не найдётся.
     */
    static @Nullable String messageBody(RevCommit commit) {
        String full = commit.getFullMessage().replace("\r\n", "\n");
        int blankLine = full.indexOf("\n\n");
        if (blankLine < 0) return null;
        // Слева режем только переносы, а не пробелы: тело часто открывается блоком кода, и
        // strip() снял бы отступ у одной первой строки, оставив остальные — вышел бы сломанный
        // отступ вместо цитаты.
        String body = full.substring(blankLine + 2).stripTrailing().replaceFirst("^\n+", "");
        return body.isBlank() ? null : body;
    }

    /**
     * Maps one JGit {@link DiffEntry} to the API's {@link GitDiffEntry}, using the change type JGit
     * already computed (add/modify/delete/rename/copy) rather than inferring it from add/delete
     * line counts: an append-only edit to an existing file adds lines and deletes none, and is
     * still an {@code M}.
     */
    static GitDiffEntry toGitDiffEntry(
            DiffEntry entry, DiffFormatter formatter, boolean includePatch, ByteArrayOutputStream patchOut)
            throws IOException {
        @Nullable String oldPath = normalizedDiffPath(entry.getOldPath());
        @Nullable String newPath = normalizedDiffPath(entry.getNewPath());

        String status =
                switch (entry.getChangeType()) {
                    case ADD -> "A";
                    case DELETE -> "D";
                    case RENAME -> "R";
                    case COPY -> "C";
                    default -> "M";
                };
        // JGit only reports /dev/null (→ null, see normalizedDiffPath) for the side that doesn't
        // exist: oldPath for ADD, newPath for DELETE/everything else — so whichever side `status`
        // picks is always real.
        String path = Objects.requireNonNull("D".equals(status) ? oldPath : newPath);
        // Renames AND copies both carry a meaningful source path; everything else has none.
        String reportedOldPath = "R".equals(status) || "C".equals(status) ? oldPath : null;

        int add = 0;
        int del = 0;
        FileHeader header = formatter.toFileHeader(entry);
        if (header.getPatchType() == FileHeader.PatchType.UNIFIED) {
            for (Edit edit : header.toEditList()) {
                add += edit.getEndB() - edit.getBeginB();
                del += edit.getEndA() - edit.getBeginA();
            }
        }

        // Обрезается патч целиком, а уже потом делится: лимит считает строки того, что собрал
        // formatter, и шапка занимает место наравне с ними, где бы её потом ни показали.
        Parts parts = includePatch ? split(truncate(formatted(entry, formatter, patchOut))) : new Parts(null, null);
        return new GitDiffEntry(status, path, reportedOldPath, add, del, parts.header(), parts.body());
    }

    /** One entry's unified diff as text; the buffer is the formatter's own, hence the reset. */
    private static String formatted(DiffEntry entry, DiffFormatter formatter, ByteArrayOutputStream patchOut)
            throws IOException {
        patchOut.reset();
        formatter.format(entry);
        return patchOut.toString(StandardCharsets.UTF_8);
    }

    /** The path {@link #toGitDiffEntry} files an entry under: the old one only for a deletion. */
    static String reportedPath(DiffEntry entry) {
        return entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath();
    }

    private static @Nullable String normalizedDiffPath(String path) {
        return DiffEntry.DEV_NULL.equals(path) ? null : path;
    }

    /**
     * An admitted untracked file as a whole-file {@code U}. There is no blob to diff against, so
     * the counters come from the working-tree content itself. A binary file, and one the caller
     * could not or would not read ({@code content == null}), reports zero lines and no patch rather
     * than a number read off its bytes.
     */
    static GitDiffEntry untracked(String path, byte @Nullable [] content, boolean includePatch) {
        if (content == null || RepoFiles.isBinary(content)) {
            return new GitDiffEntry(UNTRACKED_STATUS, path, null, 0, 0, null, null);
        }
        // Список изменений считает строки у каждого допущенного файла, поэтому строки считаются
        // по байтам: раскладывать в строки то, чего никто не покажет, — работа на весь размер
        // файла ради одного числа.
        int lineCount = countLines(content);
        String patchHeader = null;
        String patch = null;
        if (includePatch) {
            String text = new String(content, StandardCharsets.UTF_8);
            // Финальный перевод строки закрывает последнюю строку, а не начинает новую: без этого
            // файл из трёх строк показывал бы «+4» и лишний «+» в конце патча — не так, как те же
            // три строки считает git у отслеживаемого файла.
            String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
            // Пустой файл и файл из одного перевода строки — разное: во втором есть строка,
            // пустая.
            List<String> lines = text.isEmpty() ? List.of() : List.of(body.split("\n", -1));
            // Шапка тут не отделяется, а собирается: у файла вне git нет ханков, зато имя его
            // такие же метаданные, как и у остальных, и приходит оно тем же полем.
            patchHeader = "+++ b/" + path;
            StringBuilder sb = new StringBuilder();
            lines.stream()
                    .limit(MAX_DIFF_LINES)
                    .forEach(l -> sb.append('+').append(l).append('\n'));
            if (lines.size() > MAX_DIFF_LINES) {
                sb.append("... (truncated)\n");
            }
            // Пустой файл: строк нет, и показывать в блоке кода нечего — одна шапка над пустым
            // блоком читалась бы как сломанный патч.
            patch = sb.isEmpty() ? null : sb.toString();
        }
        return new GitDiffEntry(UNTRACKED_STATUS, path, null, lineCount, 0, patchHeader, patch);
    }

    /**
     * Сколько строк в этих байтах по счёту git: последний перевод строки закрывает строку, а не
     * начинает новую, и у пустого файла строк нет.
     */
    private static int countLines(byte[] content) {
        if (content.length == 0) {
            return 0;
        }
        int lines = 0;
        for (byte b : content) {
            if (b == '\n') {
                lines++;
            }
        }
        return content[content.length - 1] == '\n' ? lines : lines + 1;
    }
}
